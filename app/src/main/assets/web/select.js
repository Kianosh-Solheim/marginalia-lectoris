/* Several library items at once: choosing them (Select mode, Ctrl/⌘-click, Shift-click ranges,
   "Select all shown") and acting on them — source files and notes as zips, one notes document,
   the bibliography of the selection, tags and other details together, collections, Zotero, and
   removal (moved to Readmark/_to_delete). Loaded after app.js, zotero.js and details.js. */

const sel = { ids: new Set(), mode: false, last: null };
state.collection = localGet('ml.collection', '');
state.collections = {};

async function loadCollections() {
  try { state.collections = (await api('collections')).collections || {}; } catch (_) { state.collections = {}; }
  if (state.collection && !state.collections[state.collection]) state.collection = '';
  return state.collections;
}

function selIds() { return [...sel.ids].filter(id => state.docs.some(d => d.id === id)); }
function selDocs() { const ids = new Set(selIds()); return state.docs.filter(d => ids.has(d.id)); }

/**
 * A click on an item in the shelf. Returns true when it was a selection click (Select mode, or with
 * Ctrl/⌘ or Shift); the item then does not open.
 */
function selClick(e, d, shown) {
  const range = e.shiftKey && sel.last != null;
  if (!sel.mode && !e.ctrlKey && !e.metaKey && !range) return false;
  e.preventDefault(); e.stopPropagation();
  if (range) {
    const ids = shown.map(x => x.id);
    const a = ids.indexOf(sel.last), b = ids.indexOf(d.id);
    if (a >= 0 && b >= 0) ids.slice(Math.min(a, b), Math.max(a, b) + 1).forEach(id => sel.ids.add(id));
    else sel.ids.add(d.id);
  } else if (sel.ids.has(d.id)) sel.ids.delete(d.id); else sel.ids.add(d.id);
  sel.last = d.id;
  renderShelf();
  return true;
}

/** The bar at the bottom of the library while items are selected (or Select mode is on). */
function selectionBar(shown) {
  document.querySelectorAll('.selbar').forEach(b => b.remove());
  if (!sel.mode && !sel.ids.size) return;
  const n = selIds().length;
  const bar = h('div', { class: 'selbar' },
    h('b', null, n ? `${n} selected` : 'Select items'),
    h('button', { class: 'btn quiet', onclick: () => { shown.forEach(d => sel.ids.add(d.id)); renderShelf(); } }, `Select all shown (${shown.length})`),
    n ? h('button', { class: 'btn quiet', onclick: () => { sel.ids.clear(); renderShelf(); } }, 'Clear') : null,
    h('span', { style: { flex: 1 } }),
    ...(n ? bulkButtons(() => selDocs(), () => { renderShelf(); }) : [h('span', { class: 'muted', style: { fontSize: '12px' } }, 'Tap items to select them · Shift-click selects a range')]),
    h('button', { class: 'btn', onclick: () => { sel.mode = false; sel.ids.clear(); renderShelf(); } }, 'Done'));
  document.body.append(bar);
}

/** The action menus for a selection; [docs] gives the current selection, [after] redraws. */
function bulkButtons(docs, after) {
  const ids = () => docs().map(d => d.id);
  const q = () => 'ids=' + ids().join(',');
  const count = () => ids().length;
  const reload = async () => { try { await loadLibrary(); await loadCollections(); } catch (e) { fail(e); } after(); };
  const copyBib = async (format, label) => { try { const r = await api(`bulk/bib?${q()}&format=${format}`); copyText(r.text, `${label} copied (${count()})`); } catch (e) { fail(e); } };
  const run = async (path, body, msg) => {
    try { const r = await api('bulk/' + path, { method: 'POST', json: { ids: ids(), ...body }, timeout: 600000 }); toast(msg(r), 5000); await reload(); } catch (e) { fail(e); }
  };
  const inCollection = state.collection;
  return [
    menuButton('Download ▾', [
      ['Source files (.zip)', 'PDFs and EPUBs named “Allen et al. - 2020 - Title”', () => download(`bulk/files?${q()}`, `Packing ${count()} files…`)],
      ['Notes for Obsidian (.zip)', 'Each book’s notes and overview, a folder per book', () => download(`bulk/notes?${q()}`, 'Packing the notes…')],
      ['All notes in one document (.md)', 'Book by book: title, notes, quote, location', () => download(`bulk/combined?${q()}&format=md`, 'Putting the notes together…')],
      ['All notes as a PDF', 'The same, ready to print', () => download(`bulk/combined?${q()}&format=pdf`, 'Making the PDF…')],
    ]),
    menuButton('Cite ▾', [
      ['Copy references', 'Reference list in your citation style, sorted', () => copyBib('references', 'References')],
      ['Copy in-text citations', '(Allen et al. 2020; …)', () => copyBib('intext', 'Citations')],
      ['Copy citation keys', '@allen2020right, …', () => copyBib('keys', 'Citation keys')],
      ['BibTeX file (.bib)', 'For LaTeX, Obsidian or Zotero', () => download(`bulk/bib?${q()}&format=bibtex&download=1`, 'Preparing BibTeX…')],
      ['RIS file (.ris)', 'For Zotero, EndNote, Mendeley', () => download(`bulk/bib?${q()}&format=ris&download=1`, 'Preparing RIS…')],
    ]),
    menuButton('Edit ▾', [
      ['Add tags…', 'Added to every selected item', () => askTags('Add tags', 'Added to each item', tags => run('edit', { addTags: tags }, r => `Tags added to ${r.changed} item${r.changed === 1 ? '' : 's'}`))],
      ['Remove tags…', 'Taken off every selected item', () => askTags('Remove tags', 'Taken off each item', tags => run('edit', { removeTags: tags }, r => `Tags removed from ${r.changed} item${r.changed === 1 ? '' : 's'}`), docs())],
      ['Move to Books', '', () => run('edit', { category: 'BOOK' }, r => `${r.changed} moved to Books`)],
      ['Move to Articles', '', () => run('edit', { category: 'ARTICLE' }, r => `${r.changed} moved to Articles`)],
      ['Set item type…', 'Book, journal article, book section…', () => askType(t => run('edit', { entryType: t }, r => `Item type set for ${r.changed}`))],
      ['Add to a folder book…', 'As its chapters', () => askFolder(fid => run('edit', { folder: fid }, () => 'Added to the folder book'))],
    ]),
    menuButton('Collection ▾', [
      ['Add to a collection…', 'A saved selection you can reopen', () => askCollection(async name => {
        try { await api('collections', { method: 'POST', json: { name, ids: ids() } }); toast(`Added ${count()} to “${name}”`); await reload(); } catch (e) { fail(e); }
      })],
      ...(inCollection ? [[`Take out of “${inCollection}”`, 'The items stay in the library', async () => {
        try { await api('collections', { method: 'POST', json: { name: inCollection, ids: ids(), remove: true } }); sel.ids.clear(); toast('Taken out of the collection'); await reload(); } catch (e) { fail(e); }
      }]] : []),
    ]),
    menuButton('Zotero ▾', [
      ['Add to Zotero', 'New Zotero items for those not in Zotero yet', () => run('zotero-add', {}, r => `${r.done} added to Zotero` + (r.skipped ? ` · ${r.skipped} were already there` : '') + (r.failed.length ? ` · ${r.failed.length} failed: ${r.failed[0]}` : ''))],
      ['Get files from Zotero', 'For placeholders (Zotero keeps its copies)', () => run('zotero-files', {}, r => `${r.done} file${r.done === 1 ? '' : 's'} fetched` + (r.failed.length ? ` · ${r.failed.length} failed: ${r.failed[0]}` : ''))],
      ['Check citations…', 'One by one, against Zotero', () => checkMany(docs().filter(d => d.zoteroKey))],
      ['Sync with Zotero', 'Fetch what changed, send what you changed', async () => { try { toast('Syncing with Zotero…', 0); await api('zotero/sync', { method: 'POST', timeout: 180000 }); toast('Synced'); await reload(); } catch (e) { fail(e); } }],
    ]),
    h('button', { class: 'btn quiet', onclick: () => confirmBox(`Remove ${count()} item${count() === 1 ? '' : 's'} from the library?`,
      'Their folders (file, notes, overview, cover) are moved to Readmark/_to_delete on the tablet, so nothing is lost until you empty that folder yourself.', 'Remove',
      async () => { try { const r = await api('bulk/remove', { method: 'POST', json: { ids: ids() } }); sel.ids.clear(); toast(`${r.removed} moved to _to_delete`); await reload(); } catch (e) { fail(e); } }) }, 'Remove…'),
  ];
}

function askTags(title, help, onOk, fromDocs) {
  modal((box, close) => {
    const input = tagInput('', false);
    const existing = fromDocs ? [...new Set(fromDocs.flatMap(d => splitTags(d.tags)))].sort() : [];
    box.append(h('h2', null, title), h('p', { class: 'muted', style: { marginTop: 0 } }, help), input._widget || input,
      existing.length ? h('div', { class: 'tags', style: { margin: '8px 0' } }, existing.map(t => h('button', { class: 'tag', type: 'button', onclick: () => { onOk([t]); close(); } }, '#' + t))) : null,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn fill', onclick: () => { const t = splitTags(input.value); if (!t.length) { toast('Type a tag first.'); return; } close(); onOk(t); } }, title)));
  });
}

function askType(onOk) {
  modal((box, close) => {
    box.append(h('h2', null, 'Item type'), ...INFO_TYPES.map(([v, l]) => h('button', { class: 'cand', type: 'button', onclick: () => { close(); onOk(v); } }, h('b', null, l))),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel')));
  });
}

function askFolder(onOk) {
  const folders = state.docs.filter(x => x.folder);
  if (!folders.length) { toast('Make a folder book first (+ New folder book).'); return; }
  modal((box, close) => {
    box.append(h('h2', null, 'Add to a folder book'), ...folders.map(f => h('button', { class: 'cand', type: 'button', onclick: () => { close(); onOk(f.id); } }, h('b', null, f.title), h('span', null, `${f.partCount || 0} chapters`))),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel')));
  });
}

function askCollection(onOk) {
  modal((box, close) => {
    const name = h('input', { placeholder: 'New collection, e.g. SAMPOL390 reading list' });
    const names = Object.keys(state.collections || {});
    box.append(h('h2', null, 'Add to a collection'),
      ...names.map(n => h('button', { class: 'cand', type: 'button', onclick: () => { close(); onOk(n); } }, h('b', null, n), h('span', null, `${state.collections[n].length} items`))),
      h('label', { class: 'label', style: { marginTop: '10px', display: 'block' } }, 'New collection'), name,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn fill', onclick: () => { const v = name.value.trim(); if (!v) { toast('Give it a name.'); return; } close(); onOk(v); } }, 'Add')));
    setTimeout(() => name.focus(), 0);
  });
}

/** Citation check for several linked items, one after the other. */
function checkMany(list) {
  if (!list.length) { toast('None of the selected items is linked to Zotero.'); return; }
  let i = 0;
  const next = () => { if (i >= list.length) { toast('All checked'); return; } const d = list[i++]; zoteroCheckDialog(d.id, null, next); };
  next();
}

/** The collection chooser in the library toolbar: all items, one collection, or manage them. */
function collectionPicker() {
  const names = Object.keys(state.collections || {});
  const s = h('select', { style: { width: 'auto' }, title: 'Collections: saved selections' },
    h('option', { value: '', selected: !state.collection }, 'All items'),
    ...names.map(n => h('option', { value: n, selected: state.collection === n }, `${n} (${state.collections[n].length})`)),
    names.length ? h('option', { value: '@manage' }, 'Manage collections…') : null);
  s.addEventListener('change', () => {
    if (s.value === '@manage') { s.value = state.collection; manageCollections(); return; }
    state.collection = s.value; localSet('ml.collection', s.value); renderShelf();
  });
  return s;
}

function manageCollections() {
  modal((box, close) => {
    const draw = () => {
      clear(box).append(h('h2', null, 'Collections'),
        ...Object.keys(state.collections).map(n => h('div', { class: 'row', style: { justifyContent: 'space-between', borderBottom: '1px solid var(--rule)', padding: '6px 0' } },
          h('span', null, h('b', null, n), h('span', { class: 'muted' }, ` · ${state.collections[n].length} items`)),
          h('span', { class: 'row', style: { gap: '4px' } },
            h('button', { class: 'btn quiet', onclick: async () => { const to = prompt('New name', n); if (!to || to === n) return; try { await api('collections', { method: 'POST', json: { from: n, to } }); if (state.collection === n) state.collection = to; await loadCollections(); draw(); renderShelf(); } catch (e) { fail(e); } } }, 'Rename'),
            h('button', { class: 'btn quiet', onclick: async () => { try { await api('collections?name=' + encodeURIComponent(n), { method: 'DELETE' }); if (state.collection === n) state.collection = ''; await loadCollections(); draw(); renderShelf(); } catch (e) { fail(e); } } }, 'Delete')))),
        h('p', { class: 'muted', style: { fontSize: '12px' } }, 'Deleting a collection keeps its items in the library.'),
        h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: close }, 'Done')));
    };
    draw();
  });
}
