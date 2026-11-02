/* Several library items at once: select them (Ctrl/⌘-click, Shift-click, or “Select” to tap them),
   then download their files or notes as one .zip, make a bibliography, organise them, or work with
   Zotero. Loaded after app.js, zotero.js and details.js. */

const sel = { ids: new Set(), anchor: 0, selecting: false, order: [] };

function selectedIds() { return [...sel.ids]; }
function selectedDocs() { return (state.docs || []).filter(d => sel.ids.has(d.id)); }

/** Handles a click on an item for selection. Returns true when the click selected (and should not open the item). */
function selectionClick(e, id, order) {
  sel.order = order;
  if (e.shiftKey && sel.anchor) {
    const a = order.indexOf(sel.anchor), b = order.indexOf(id);
    if (a >= 0 && b >= 0) { const [lo, hi] = a < b ? [a, b] : [b, a]; order.slice(lo, hi + 1).forEach(x => sel.ids.add(x)); }
    e.preventDefault(); markSelection(); return true;
  }
  if (e.ctrlKey || e.metaKey || sel.selecting) {
    if (sel.ids.has(id)) sel.ids.delete(id); else sel.ids.add(id);
    sel.anchor = id; markSelection(); return true;
  }
  return false;
}

function setSelection(ids) { sel.ids = new Set(ids); markSelection(); }
function clearSelection() { sel.ids.clear(); sel.anchor = 0; markSelection(); }

/** Shows the selection on cards, list rows and table rows, and the action bar. */
function markSelection() {
  document.querySelectorAll('[data-pick]').forEach(el => el.classList.toggle('picked', sel.ids.has(+el.dataset.pick)));
  renderBulkBar();
  if (typeof onSelectionChanged === 'function') onSelectionChanged();
}

function bulkReload() { clearSelection(); if (document.getElementById('shelf')) showLibrary(); }

function bulkQuery() { return 'ids=' + selectedIds().join(','); }

async function bulkPost(what, json, busy) {
  if (busy) toast(busy, 0);
  try { const r = await api('bulk/' + what, { method: 'POST', json: Object.assign({ ids: selectedIds() }, json || {}), timeout: 600000 }); if (r.message) toast(r.message, 5000); return r; }
  catch (e) { fail(e); return null; }
}

function renderBulkBar() {
  let bar = document.getElementById('bulkbar');
  const n = sel.ids.size;
  const inTable = state.view === 'table';
  if (!document.getElementById('shelf') || n === 0 || (inTable && n < 2 && !sel.selecting)) { if (bar) bar.remove(); return; }
  if (!bar) { bar = h('div', { id: 'bulkbar' }); document.body.append(bar); }
  const docs = selectedDocs();
  const withFiles = docs.filter(d => !d.placeholder).length;
  clear(bar).append(
    h('b', null, `${n} selected`),
    h('button', { class: 'btn quiet', onclick: () => setSelection(sel.order.length ? sel.order : (state.docs || []).map(d => d.id)) }, 'Select all'),
    h('button', { class: 'btn quiet', onclick: () => { sel.selecting = false; clearSelection(); renderShelf(); } }, 'Clear'),
    h('span', { class: 'bsep' }),
    h('button', { class: 'btn fill', disabled: !withFiles, title: 'The files as one .zip, named “Author - Year - Title”', onclick: () => download(`bulk/files?${bulkQuery()}`, `Packing ${withFiles} file${withFiles === 1 ? '' : 's'}…`) }, 'Download files (.zip)'),
    h('button', { class: 'btn', title: 'Each item’s notes and “Notes on …” overview, one folder per item', onclick: () => download(`bulk/notes?${bulkQuery()}`, 'Exporting the notes…') }, 'Export notes (.zip)'),
    menuButton('Bibliography ▾', [
      ['BibTeX (.bib)', 'One file for all selected items', () => download(`bulk/bib?${bulkQuery()}&format=bib`, 'Preparing BibTeX…')],
      ['RIS (.ris)', 'For Zotero, EndNote, Mendeley', () => download(`bulk/bib?${bulkQuery()}&format=ris`, 'Preparing RIS…')],
      ['Copy reference list', 'Alphabetical, in your default style', async () => { const r = await bulkPost('refs', { kind: 'references' }); if (r) copyText(r.text, `Reference list copied (${r.style})`); }],
      ['Copy citation keys', '[@key1; @key2] for Pandoc and Obsidian', async () => { const r = await bulkPost('refs', { kind: 'keys' }); if (r) copyText(r.text, 'Citation keys copied'); }],
    ]),
    menuButton('Organise ▾', [
      ['Add tags…', 'To every selected item', () => bulkTags(true)],
      ['Remove tags…', 'From every selected item', () => bulkTags(false)],
      ['Move to Books', '', async () => { if (await bulkPost('category', { category: 'BOOK' })) bulkReload(); }],
      ['Move to Articles', '', async () => { if (await bulkPost('category', { category: 'ARTICLE' })) bulkReload(); }],
      ['Add to a folder book…', 'As its chapters, in this order', () => bulkFolder()],
      ['Delete…', 'From the tablet, with their notes', () => confirmBox(`Delete ${n} item${n === 1 ? '' : 's'}?`, `${docs.slice(0, 6).map(d => '“' + d.title + '”').join(', ')}${n > 6 ? ' …' : ''} and their notes will be removed from the tablet.`, 'Delete', async () => { if (await bulkPost('delete', {}, 'Deleting…')) bulkReload(); })],
    ]),
    menuButton('Zotero ▾', [
      ['Check against Zotero', 'Which items differ from their Zotero item', () => bulkZoteroCheck()],
      ['Add missing to Zotero', 'New Zotero items for those not yet there', () => confirmBox('Add to Zotero?', `Items not yet in Zotero get a new Zotero item with their details and are linked to it.`, 'Add', async () => { if (await bulkPost('zotero-add', {}, 'Adding to Zotero…')) bulkReload(); })],
      ['Look up details', 'By DOI / ISBN / ISSN; only empty fields are filled', () => confirmBox('Look up details?', 'Each selected item with a DOI, ISBN or ISSN is looked up online. Only empty fields are filled in; nothing you have filled in is changed.', 'Look up', async () => { if (await bulkPost('lookup', {}, 'Looking up the details…')) bulkReload(); })],
    ]),
  );
}

function bulkTags(adding) {
  const docs = selectedDocs();
  modal((box, close) => {
    if (adding) {
      const t = tagInput('', false);
      box.append(h('h2', null, `Add tags to ${docs.length} item${docs.length === 1 ? '' : 's'}`), t._widget || t,
        h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
          h('button', { class: 'btn fill', onclick: async () => { const add = splitTags(t.value); if (!add.length) return; close(); if (await bulkPost('tags', { add })) bulkReload(); } }, 'Add')));
    } else {
      const counts = {};
      docs.forEach(d => splitTags(d.tags).forEach(x => { const k = x.toLowerCase(); counts[k] = counts[k] || [x, 0]; counts[k][1]++; }));
      const chosen = new Set();
      const list = h('div', { class: 'tags', style: { margin: '8px 0' } });
      Object.values(counts).sort((a, b) => b[1] - a[1]).forEach(([x, c]) => {
        const b = h('button', { class: 'chip', onclick: () => { if (chosen.has(x)) chosen.delete(x); else chosen.add(x); b.classList.toggle('on', chosen.has(x)); } }, `#${x} ${c}`);
        list.append(b);
      });
      box.append(h('h2', null, 'Remove tags'), Object.keys(counts).length ? list : h('p', { class: 'muted' }, 'The selected items have no tags.'),
        h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
          h('button', { class: 'btn fill', onclick: async () => { if (!chosen.size) return; close(); if (await bulkPost('tags', { remove: [...chosen] })) bulkReload(); } }, 'Remove')));
    }
  });
}

function bulkFolder() {
  const folders = (state.docs || []).filter(d => d.folder);
  modal((box, close) => {
    box.append(h('h2', null, 'Add to a folder book'),
      folders.length ? h('div', { class: 'zpick' }, ...folders.map(f => h('button', { class: 'cand', type: 'button', onclick: async () => { close(); if (await bulkPost('folder', { folder: f.id })) bulkReload(); } }, h('b', null, f.title), h('span', null, `${f.partCount || 0} chapters`))))
        : h('p', { class: 'muted' }, 'There are no folder books yet. Make one with “+ New folder book”.'),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel')));
  });
}

async function bulkZoteroCheck() {
  const r = await bulkPost('zotero-check', {}, 'Comparing with Zotero…');
  if (!r) return;
  const items = r.items || [];
  const diff = items.filter(i => i.linked && i.differences.length), same = items.filter(i => i.linked && !i.differences.length), unlinked = items.filter(i => !i.linked);
  modal((box, close) => {
    box.style.width = '720px';
    const sec = (title, list, extra) => list.length ? h('div', { class: 'zimp' }, h('div', { class: 'label' }, `${title} · ${list.length}`), ...list.map(extra)) : null;
    box.append(...[h('h2', null, 'Checked against Zotero'),
      h('p', { class: 'muted', style: { marginTop: 0 } }, `${same.length} match Zotero, ${diff.length} differ, ${unlinked.length} not linked.`),
      sec('Differ from Zotero', diff, i => h('div', { class: 'zsug' }, h('div', null, h('b', null, i.title), h('div', { class: 'muted' }, i.differences.join(', '))),
        h('button', { class: 'btn', onclick: () => zoteroCheckDialog(i.id, null) }, 'Check'))),
      sec('Not linked to Zotero', unlinked, i => h('div', { class: 'zsug' }, h('div', null, h('b', null, i.title)), h('a', { class: 'btn quiet', href: `#/book/${i.id}`, onclick: close }, 'Book page'))),
      sec('Match Zotero', same, i => h('div', { class: 'zsug' }, h('div', null, i.title), h('span', { class: 'muted' }, '✓'))),
      h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: close }, 'Done'))].filter(Boolean));
  });
}

document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && sel.ids.size && !document.querySelector('.modal') && document.getElementById('shelf')) { sel.selecting = false; clearSelection(); }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a' && document.getElementById('shelf') && !/INPUT|TEXTAREA/.test((document.activeElement || {}).tagName || '')) {
    e.preventDefault(); setSelection(sel.order.length ? sel.order : (state.docs || []).map(d => d.id));
  }
});
window.addEventListener('hashchange', () => setTimeout(() => { const bar = document.getElementById('bulkbar'); if (bar && !document.getElementById('shelf')) bar.remove(); }, 0));
