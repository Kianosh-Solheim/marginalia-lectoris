/* Marginalia Lectoris — literature matrices: sources down the side, your questions across the top.
 * Each cell holds your own text and the notes you link to it. Several matrices can exist side by side
 * (one per project); they are kept on the tablet with the note pages, under "matrix:<id>".
 */
const MATRIX_COLUMNS = ['Research question', 'Theory', 'Method', 'Data / case', 'Findings', 'Relevance to my thesis'];
const mxNewId = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
const mxBlank = name => ({ id: mxNewId(), name, columns: MATRIX_COLUMNS.map(n => ({ id: mxNewId(), name: n })), rows: [], created: Date.now(), modified: Date.now() });

async function matrixList() {
  const all = await api('note-pages');
  return Object.entries(all || {}).filter(([k, v]) => k.startsWith('matrix:') && v && Array.isArray(v.columns)).map(([k, v]) => ({ ...v, id: k.slice(7), rows: v.rows || [] }));
}
async function matrixPut(m) {
  m.modified = Date.now();
  await api('note-pages', { method: 'PUT', json: { key: 'matrix:' + m.id, page: { name: m.name, columns: m.columns, rows: m.rows, show: m.show || {}, created: m.created, modified: m.modified } } });
}
const mxCell = (row, colId) => { row.cells = row.cells || {}; return row.cells[colId] || (row.cells[colId] = { text: '', notes: [] }); };
const mxNoteLabel = n => (n.title || (n.highlightedText || n.userNotes || 'Note').replace(/\s+/g, ' ').slice(0, 60)).trim();
/** Read-only columns filled from the library; switched on per matrix. */
const MX_INFO = [['tags', 'Tags', d => splitTags(d.tags).join(', ')], ['year', 'Year', d => String(d.year || '')], ['citekey', 'Citekey', d => d.citekey || ''], ['doi', 'DOI', d => d.doi || '']];
const mxInfoOn = m => MX_INFO.filter(([k]) => m.show && m.show[k]);
const mxFilled = c => !!(c && ((c.text || '').trim() || (c.notes || []).length));

/** The list of matrices. */
async function showMatrices() {
  const app = clear(document.getElementById('app'));
  state.tab = 'matrix';
  app.appendChild(header());
  const main = h('main', { class: 'mxlist' }); app.appendChild(main);
  let list = [];
  try { [list] = await Promise.all([matrixList(), state.docs && state.docs.length ? null : loadLibrary()]); } catch (e) { fail(e); }
  list.sort((a, b) => (b.modified || 0) - (a.modified || 0));
  const create = () => modal((box, close) => {
    const name = h('input', { placeholder: 'For example: Master’s thesis, chapter 2' });
    const go = async () => { const m = mxBlank(name.value.trim() || 'Untitled matrix'); try { await matrixPut(m); close(); location.hash = '#/matrix/' + m.id; } catch (e) { fail(e); } };
    name.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
    box.append(h('h2', null, 'New literature matrix'),
      h('p', { class: 'muted' }, 'A table with your sources down the side and your questions across the top. It starts with six columns (' + MATRIX_COLUMNS.join(', ') + '); you can rename, add, remove and reorder them.'),
      h('label', { class: 'row' }, h('span', null, 'Name'), name),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: go }, 'Create')));
  });
  main.append(h('div', { class: 'row', style: { marginBottom: '14px' } }, h('h1', { style: { margin: 0 } }, 'Literature matrices'), h('span', { style: { flex: 1 } }), h('button', { class: 'btn fill', onclick: create }, '+ New matrix')));
  if (!list.length) { main.append(h('div', { class: 'empty' }, h('h2', null, 'No matrix yet'), h('p', { class: 'muted' }, 'A literature matrix compares your sources on the same questions: what each asks, how, with what, and what it means for your own work.'), h('button', { class: 'btn fill', onclick: create }, '+ New matrix'))); return; }
  list.forEach(m => {
    const filled = m.rows.reduce((s, r) => s + m.columns.filter(c => mxFilled((r.cells || {})[c.id])).length, 0), total = m.rows.length * m.columns.length;
    main.append(h('div', { class: 'mxcard', onclick: () => { location.hash = '#/matrix/' + m.id; } },
      h('b', { class: 'serif' }, m.name || 'Untitled matrix'),
      h('span', { class: 'muted' }, `${m.rows.length} source${m.rows.length === 1 ? '' : 's'} · ${m.columns.length} columns · ${filled} of ${total} cells filled`),
      h('span', { style: { flex: 1 } }),
      h('button', { class: 'btn quiet', title: 'Delete this matrix', onclick: e => { e.stopPropagation(); confirmBox('Delete this matrix?', `“${m.name}” and what you wrote in it are removed. Your sources and notes stay.`, 'Delete', async () => { try { await api('note-pages', { method: 'PUT', json: { key: 'matrix:' + m.id, page: null } }); showMatrices(); } catch (er) { fail(er); } }); } }, 'Delete')));
  });
}

/** One matrix. */
async function showMatrix(id) {
  const app = clear(document.getElementById('app'));
  state.tab = 'matrix';
  app.appendChild(header());
  const main = h('main', { class: 'mxpage' }); app.appendChild(main);
  let m = null, notes = [];
  try {
    const [list, hl] = await Promise.all([matrixList(), api('highlights').catch(() => ({ highlights: [] })), state.docs && state.docs.length ? null : loadLibrary()]);
    m = list.find(x => x.id === id); notes = hl.highlights || [];
  } catch (e) { fail(e); }
  if (!m) { main.append(h('div', { class: 'empty' }, h('h2', null, 'Matrix not found'), h('a', { href: '#/matrix' }, 'All matrices'))); return; }
  const docOf = did => (state.docs || []).find(d => d.id === did);
  const noteOf = nid => notes.find(n => n.id === nid);
  const whoOf = d => d ? (d.displayAuthor || d.author || '') : '';
  const titleOf = r => { const d = docOf(r.doc); return d ? d.title : (r.title || 'Removed from the library'); };

  // Saving: a little after the last change, and when you leave.
  const status = h('span', { class: 'muted mono', style: { fontSize: '11px' } }, 'Saved');
  let timer = 0, dirty = false, saving = null;
  const flush = async () => { clearTimeout(timer); if (!dirty) return; dirty = false; status.textContent = 'Saving…'; try { await (saving = matrixPut(m)); if (!dirty) status.textContent = 'Saved'; } catch (e) { dirty = true; status.textContent = 'Not saved'; fail(e); } };
  const changed = () => { dirty = true; status.textContent = 'Not saved yet'; clearTimeout(timer); timer = setTimeout(flush, 700); };
  window.writingLeave = () => { flush(); };
  window.addEventListener('beforeunload', flush);

  const name = h('input', { class: 'mxname', value: m.name || '', title: 'The name of the matrix' });
  name.addEventListener('input', () => { m.name = name.value; changed(); });
  const wrap = h('div', { class: 'mxwrap' });
  const grow = ta => { ta.style.height = 'auto'; ta.style.height = Math.max(54, ta.scrollHeight + 2) + 'px'; };

  /** Sources come in four ways: ticked in the library, by tag, cited in a writing document, by author or year. */
  function addSources() {
    const have = new Set(m.rows.map(r => r.doc)), docs = state.docs || [];
    const tagCount = new Map();
    docs.forEach(d => splitTags(d.tags).forEach(t => { const k = norm(t); const e = tagCount.get(k) || { name: t, n: 0 }; e.n++; tagCount.set(k, e); }));
    const allTags = [...tagCount.entries()].sort((a, b) => b[1].n - a[1].n || a[1].name.localeCompare(b[1].name));
    modal((box, close) => {
      box.style.width = '720px';
      let mode = localGet('ml.mxAdd', 'lib'), chosen = new Set(), shown = [], note = '';
      const pickedTags = new Set(); let allOf = false, cited = null, citedMissing = 0, writing = null;
      const q = h('input', { type: 'search', placeholder: 'Search the library' });
      const who = h('input', { type: 'search', placeholder: 'Author', style: { flex: 2 } });
      const y1 = h('input', { type: 'number', placeholder: 'From year', style: { flex: 1, minWidth: '90px' } }), y2 = h('input', { type: 'number', placeholder: 'To year', style: { flex: 1, minWidth: '90px' } });
      const tabs = h('div', { class: 'seg mxtabs' }), controls = h('div', { class: 'mxctl' });
      const list = h('div', { style: { maxHeight: '44vh', overflow: 'auto', borderTop: '1px solid var(--ink)', marginTop: '8px' } });
      const info = h('div', { class: 'muted', style: { fontSize: '12px', marginTop: '6px', minHeight: '16px' } });
      const ok = h('button', { class: 'btn fill', disabled: true }, 'Add');
      const count = () => { ok.disabled = !chosen.size; ok.textContent = chosen.size ? `Add ${chosen.size}` : 'Add'; };
      const yearOf = d => +(String(d.year || '').match(/\d{4}/) || [0])[0];
      /** What the chosen way finds; [tick] says whether the finds start out ticked. */
      const matches = () => {
        note = '';
        if (mode === 'lib') { const terms = norm(q.value).split(/\s+/).filter(Boolean); return docs.filter(x => terms.every(t => norm([x.title, whoOf(x), x.tags, x.year, x.citekey].join(' ')).includes(t))); }
        if (mode === 'tag') {
          if (!pickedTags.size) { note = 'Choose one or more tags.'; return []; }
          return docs.filter(d => { const mine = new Set(splitTags(d.tags).map(norm)); const hit = [...pickedTags].filter(t => mine.has(t)).length; return allOf ? hit === pickedTags.size : hit > 0; });
        }
        if (mode === 'doc') {
          if (!cited) { note = 'Choose a document.'; return []; }
          if (citedMissing) note = `${citedMissing} cited source${citedMissing === 1 ? ' is' : 's are'} only in Zotero, not in the library, and cannot be added.`;
          return docs.filter(d => cited.has(d.id));
        }
        const a = norm(who.value).split(/\s+/).filter(Boolean), from = +y1.value || 0, to = +y2.value || 0;
        if (!a.length && !from && !to) { note = 'Write an author, a year span, or both.'; return []; }
        return docs.filter(d => a.every(t => norm(whoOf(d) + ' ' + (d.author || '')).includes(t)) && (!(from || to) || (yearOf(d) && (!from || yearOf(d) >= from) && (!to || yearOf(d) <= to))));
      };
      const drawList = retick => {
        shown = matches();
        if (retick) chosen = new Set(mode === 'lib' ? [] : shown.filter(x => !have.has(x.id)).map(x => x.id));
        clear(list);
        shown.slice(0, 400).forEach(x => {
          const inside = have.has(x.id);
          const cb = h('input', { type: 'checkbox', checked: inside || chosen.has(x.id), disabled: inside });
          cb.addEventListener('change', () => { if (cb.checked) chosen.add(x.id); else chosen.delete(x.id); count(); });
          const nn = notes.filter(n => n.documentId === x.id).length, tg = splitTags(x.tags);
          list.append(h('label', { class: 'row', style: { padding: '7px 2px', borderBottom: '1px solid var(--rule)', cursor: inside ? 'default' : 'pointer', flexWrap: 'nowrap', opacity: inside ? .5 : 1 } }, cb,
            h('span', { style: { minWidth: 0 } }, h('b', { class: 'serif' }, x.title),
              h('div', { class: 'muted', style: { fontSize: '12px' } }, [whoOf(x), x.year, inside ? 'already in the matrix' : (nn ? `${nn} note${nn === 1 ? '' : 's'}` : ''), tg.length ? tg.map(t => '#' + t).join(' ') : ''].filter(Boolean).join(' · ')))));
        });
        if (!list.firstChild) list.append(h('div', { class: 'muted', style: { padding: '10px 0' } }, note || 'Nothing matches.'));
        const fresh = shown.filter(x => !have.has(x.id)).length;
        info.textContent = list.firstChild && shown.length ? [`${shown.length} found`, shown.length - fresh ? `${shown.length - fresh} already in the matrix` : '', note].filter(Boolean).join(' · ') : '';
        count();
      };
      const drawControls = () => {
        clear(controls);
        if (mode === 'lib') controls.append(q);
        else if (mode === 'tag') {
          const chips = h('div', { class: 'mxtagpick' });
          if (!allTags.length) chips.append(h('span', { class: 'muted' }, 'No book or article has a tag yet.'));
          allTags.forEach(([k, t]) => { const b = h('button', { class: 'mxtag' + (pickedTags.has(k) ? ' on' : ''), onclick: () => { if (pickedTags.has(k)) pickedTags.delete(k); else pickedTags.add(k); b.classList.toggle('on'); drawList(true); } }, '#' + t.name, h('span', { class: 'mono' }, ' ' + t.n)); chips.append(b); });
          const all = h('input', { type: 'checkbox', checked: allOf }); all.addEventListener('change', () => { allOf = all.checked; drawList(true); });
          controls.append(chips, h('label', { class: 'row', style: { flexDirection: 'row', fontSize: '13px', marginTop: '6px' } }, all, h('span', null, 'Only sources that carry all the chosen tags')));
        } else if (mode === 'doc') {
          const sel = h('select', null, h('option', { value: '' }, writing ? 'Choose a writing document…' : 'Loading…'));
          const fill = () => { (writing || []).forEach(d => sel.append(h('option', { value: d.id }, d.title || 'Untitled'))); sel.firstChild.textContent = (writing || []).length ? 'Choose a writing document…' : 'No writing documents yet'; };
          if (writing) fill(); else api('writing').then(w => { writing = Array.isArray(w) ? w : []; fill(); }).catch(fail);
          sel.addEventListener('change', async () => {
            cited = null; citedMissing = 0; if (!sel.value) return drawList(true);
            try {
              const d = await api('writing/' + encodeURIComponent(sel.value)), ids = citedIds(d.content || {});
              cited = new Set();
              ids.forEach(cid => { const p = String(cid).split(':'); const hit = p[0] === 'ml' ? docs.find(x => x.id === +p[1]) : p[0] === 'z' ? docs.find(x => x.zoteroKey && x.zoteroKey === p[1]) : null; if (hit) cited.add(hit.id); else citedMissing++; });
              if (!ids.length) { cited = new Set(); }
            } catch (e) { fail(e); }
            drawList(true); if (cited && !cited.size && !citedMissing) info.textContent = 'This document cites nothing yet.';
          });
          controls.append(sel);
        } else controls.append(h('div', { class: 'row', style: { flexDirection: 'row', gap: '8px' } }, who, y1, y2));
      };
      const drawTabs = () => {
        clear(tabs);
        [['lib', 'Library'], ['tag', 'By tag'], ['doc', 'Cited in a document'], ['who', 'Author or year']].forEach(([k, label]) =>
          tabs.append(h('button', { class: mode === k ? 'on' : '', onclick: () => { mode = k; localSet('ml.mxAdd', k); drawTabs(); drawControls(); drawList(true); const f = controls.querySelector('input[type=search], select'); if (f) f.focus(); } }, label)));
      };
      q.addEventListener('input', () => drawList(false));
      [who, y1, y2].forEach(x => x.addEventListener('input', () => drawList(true)));
      ok.addEventListener('click', () => { docs.filter(d => chosen.has(d.id) && !have.has(d.id)).forEach(d => m.rows.push({ doc: d.id, title: d.title || '', cells: {} })); close(); changed(); draw_(); });
      box.append(h('h2', null, 'Add sources to the matrix'), tabs, controls, list, info, h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), ok));
      drawTabs(); drawControls(); drawList(true);
    });
  }

  function pickNotes(row, col) {
    const cell = mxCell(row, col.id);
    modal((box, close) => {
      box.style.width = '680px';
      const chosen = new Set(cell.notes || []);
      const q = h('input', { type: 'search', placeholder: 'Search these notes' });
      const every = h('input', { type: 'checkbox' });
      const list = h('div', { style: { maxHeight: '50vh', overflow: 'auto', borderTop: '1px solid var(--ink)', marginTop: '8px' } });
      const draw = () => {
        clear(list);
        const terms = norm(q.value).split(/\s+/).filter(Boolean);
        notes.filter(n => every.checked || n.documentId === row.doc || chosen.has(n.id))
          .filter(n => terms.every(t => norm([n.title, n.highlightedText, n.userNotes, n.tags, every.checked ? n.documentTitle : ''].join(' ')).includes(t))).slice(0, 400).forEach(n => {
            const cb = h('input', { type: 'checkbox', checked: chosen.has(n.id) });
            cb.addEventListener('change', () => { if (cb.checked) chosen.add(n.id); else chosen.delete(n.id); });
            const quote = (n.highlightedText || n.userNotes || '').replace(/\s+/g, ' ').slice(0, 170);
            list.append(h('label', { class: 'row', style: { padding: '7px 2px', borderBottom: '1px solid var(--rule)', cursor: 'pointer', flexWrap: 'nowrap', alignItems: 'flex-start' } }, cb,
              h('span', { style: { minWidth: 0 } }, h('b', null, mxNoteLabel(n)), h('div', { class: 'muted', style: { fontSize: '12px' } }, [every.checked && n.documentId !== row.doc ? n.documentTitle : '', n.printedPageLabel ? 'p. ' + n.printedPageLabel : '', quote].filter(Boolean).join(' · ')))));
          });
        if (!list.firstChild) list.append(h('div', { class: 'muted', style: { padding: '10px 0' } }, every.checked ? 'Nothing matches.' : 'This source has no notes yet. Tick “Notes from every source” to look further.'));
      };
      q.addEventListener('input', draw); every.addEventListener('change', draw);
      box.append(h('h2', null, `Notes for “${col.name}”`), h('p', { class: 'muted', style: { marginTop: 0 } }, titleOf(row)), q,
        h('label', { class: 'row', style: { flexDirection: 'row', fontSize: '13px', marginTop: '6px' } }, every, h('span', null, 'Notes from every source')), list,
        h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: () => { cell.notes = [...chosen]; close(); changed(); draw_(); } }, 'Done')));
      draw();
    });
  }

  function moveCol(i, by) { const j = i + by; if (j < 0 || j >= m.columns.length) return; const [c] = m.columns.splice(i, 1); m.columns.splice(j, 0, c); changed(); draw_(); }
  function removeCol(i) {
    const c = m.columns[i], used = m.rows.filter(r => mxFilled((r.cells || {})[c.id])).length;
    const go = () => { m.columns.splice(i, 1); m.rows.forEach(r => { if (r.cells) delete r.cells[c.id]; }); changed(); draw_(); };
    if (used) confirmBox('Remove this column?', `“${c.name}” has something in ${used} row${used === 1 ? '' : 's'}. That text and those links are removed with it (the notes themselves stay).`, 'Remove', go); else go();
  }
  function removeRow(i) {
    const r = m.rows[i], used = m.columns.some(c => mxFilled((r.cells || {})[c.id]));
    const go = () => { m.rows.splice(i, 1); changed(); draw_(); };
    if (used) confirmBox('Take this source out of the matrix?', `What you wrote about “${titleOf(r)}” in this matrix is removed. The source and its notes stay in the library.`, 'Take out', go); else go();
  }
  function moveRow(i, by) { const j = i + by; if (j < 0 || j >= m.rows.length) return; const [r] = m.rows.splice(i, 1); m.rows.splice(j, 0, r); changed(); draw_(); }
  function sortRows(by) { const key = r => norm(by === 'author' ? whoOf(docOf(r.doc)) + ' ' + titleOf(r) : by === 'year' ? (String((docOf(r.doc) || {}).year || '9999')) + ' ' + whoOf(docOf(r.doc)) : titleOf(r)); m.rows.sort((a, b) => key(a).localeCompare(key(b))); changed(); draw_(); }

  function draw_() {
    const top = wrap.scrollTop, left = wrap.scrollLeft;
    clear(wrap);
    if (!m.rows.length) { wrap.append(h('div', { class: 'empty' }, h('h2', null, 'No sources yet'), h('p', { class: 'muted' }, 'Choose the books and articles this matrix compares.'), h('button', { class: 'btn fill', onclick: addSources }, '+ Add sources'))); return; }
    const table = h('table', { class: 'mx' });
    const infoCols = mxInfoOn(m);
    const head = h('tr', null, h('th', { class: 'mxsrc' }, 'Source'), infoCols.map(([k, label]) => h('th', { class: 'mxinfo', title: 'From the library; change it on the source’s own page' }, label)));
    m.columns.forEach((c, i) => {
      const inp = h('input', { value: c.name, title: 'Rename the column' });
      inp.addEventListener('input', () => { c.name = inp.value; changed(); });
      head.append(h('th', null, h('div', { class: 'mxth' }, inp, menuButton('▾', [
        i > 0 ? ['Move left', '', () => moveCol(i, -1)] : null, i < m.columns.length - 1 ? ['Move right', '', () => moveCol(i, 1)] : null,
        ['Remove', 'The column and what is in it', () => removeCol(i)]].filter(Boolean), 'btn quiet mxmini'))));
    });
    head.append(h('th', { class: 'mxadd' }, h('button', { class: 'btn quiet', title: 'Add a column', onclick: addCol }, '+ Column')));
    table.append(h('thead', null, head));
    const body = h('tbody');
    m.rows.forEach((r, i) => {
      const d = docOf(r.doc), nn = notes.filter(n => n.documentId === r.doc).length;
      const tr = h('tr', null, h('td', { class: 'mxsrc' },
        d ? h('a', { class: 'serif mxtitle', href: '#/book/' + d.id, title: 'Open the page of this source' }, d.title) : h('span', { class: 'serif mxtitle muted' }, titleOf(r)),
        h('div', { class: 'muted', style: { fontSize: '12px' } }, [whoOf(d), nn ? `${nn} note${nn === 1 ? '' : 's'}` : ''].filter(Boolean).join(' · ')),
        h('div', { class: 'mxrowbtns' },
          h('button', { class: 'btn quiet mxmini', title: 'Move up', disabled: i === 0, onclick: () => moveRow(i, -1) }, '↑'),
          h('button', { class: 'btn quiet mxmini', title: 'Move down', disabled: i === m.rows.length - 1, onclick: () => moveRow(i, 1) }, '↓'),
          d ? h('a', { class: 'btn quiet mxmini', href: '#/read/' + d.id, title: 'Read it' }, 'Read') : null,
          h('button', { class: 'btn quiet mxmini', title: 'Take this source out of the matrix', onclick: () => removeRow(i) }, '×'))));
      infoCols.forEach(([k, label, get]) => tr.append(h('td', { class: 'mxinfo' + (k === 'tags' ? '' : ' mono') }, d ? (k === 'doi' && get(d) ? h('a', { href: 'https://doi.org/' + get(d).replace(/^(https?:\/\/)?(dx\.)?doi\.org\//i, ''), target: '_blank', rel: 'noopener' }, get(d)) : get(d)) : '')));
      m.columns.forEach(c => {
        const cell = (r.cells || {})[c.id] || { text: '', notes: [] };
        const ta = h('textarea', { placeholder: c.name, rows: 2 }, cell.text || '');
        ta.addEventListener('input', () => { mxCell(r, c.id).text = ta.value; grow(ta); changed(); });
        const chips = h('div', { class: 'mxchips' });
        (cell.notes || []).forEach(nid => {
          const n = noteOf(nid);
          chips.append(h('span', { class: 'mxchip' + (n ? '' : ' gone'), title: n ? [(n.highlightedText || '').replace(/\s+/g, ' ').slice(0, 300), n.userNotes].filter(Boolean).join('\n— ') : 'This note no longer exists' },
            n ? h('a', { href: '#/notes/n/' + n.id }, mxNoteLabel(n) + (n.printedPageLabel ? ' · p. ' + n.printedPageLabel : '')) : h('span', null, 'Deleted note'),
            h('button', { title: 'Unlink this note from the cell (the note stays)', onclick: () => { const cl = mxCell(r, c.id); cl.notes = (cl.notes || []).filter(x => x !== nid); changed(); draw_(); } }, '×')));
        });
        chips.append(h('button', { class: 'mxlink', title: 'Link notes from this source to the cell — or drag a note here', onclick: () => pickNotes(r, c) }, '+ note'));
        const td = h('td', null, ta, chips);
        td.addEventListener('dragover', e => { if ([...e.dataTransfer.types].includes('application/x-ml-note')) { e.preventDefault(); td.classList.add('over'); } });
        td.addEventListener('dragleave', () => td.classList.remove('over'));
        td.addEventListener('drop', async e => {
          const nid = +e.dataTransfer.getData('application/x-ml-note'); td.classList.remove('over'); if (!nid) return; e.preventDefault();
          if (!noteOf(nid)) { try { notes = (await api('highlights')).highlights || notes; } catch (_) {} }
          const cl = mxCell(r, c.id); if (!(cl.notes || []).includes(nid)) { cl.notes = [...(cl.notes || []), nid]; changed(); draw_(); }
        });
        tr.append(td);
      });
      tr.append(h('td', { class: 'mxadd' }));
      body.append(tr);
    });
    table.append(body); wrap.append(table);
    wrap.querySelectorAll('textarea').forEach(grow);
    wrap.scrollTop = top; wrap.scrollLeft = left;
  }
  function addCol() { const c = { id: mxNewId(), name: 'New column' }; m.columns.push(c); changed(); draw_(); const inputs = wrap.querySelectorAll('th input'); const last = inputs[inputs.length - 1]; if (last) { last.focus(); last.select(); wrap.scrollLeft = wrap.scrollWidth; } }

  const exp = async kind => { try { await flush(); await matrixExport(m, kind, { docOf, noteOf, whoOf, titleOf }); } catch (e) { fail(e); } };
  const barHost = h('div');
  function showBar() { clear(barHost).append(h('div', { class: 'mxbar' },
    h('a', { class: 'btn quiet', href: '#/matrix', title: 'All matrices' }, '‹ Matrices'), name,
    h('button', { class: 'btn fill', onclick: addSources }, '+ Add sources'),
    h('button', { class: 'btn', onclick: addCol }, '+ Column'),
    menuButton('Sort ▾', [['By author', 'Then by title', () => sortRows('author')], ['By title', '', () => sortRows('title')], ['By year', 'Oldest first; sources without a year last', () => sortRows('year')]]),
    menuButton('Show ▾', MX_INFO.map(([k, label]) => [(m.show && m.show[k] ? '✓ ' : '') + label, 'A read-only column from the library', () => { m.show = m.show || {}; m.show[k] = !m.show[k]; changed(); showBar(); draw_(); }])),
    menuButton('Export ▾', [
      ['Excel', 'A workbook (.xlsx)', () => exp('xlsx')],
      ['CSV', 'For any spreadsheet program', () => exp('csv')],
      ['Markdown table', 'One note for Obsidian, with [[links]] to your notes', () => exp('md')],
      ['Obsidian database', 'A Base: one note per source and a table over them (zip)', () => exp('base')]]),
    h('span', { style: { flex: 1 } }), status)); }
  showBar(); main.append(barHost, wrap);
  const fit = () => { wrap.style.height = Math.max(240, innerHeight - wrap.getBoundingClientRect().top - 12) + 'px'; };
  draw_(); fit(); window.addEventListener('resize', fit);
}

/** Small workbook writer: one sheet, text cells, wrapped, first row bold and frozen. */
async function mxXlsx(rows, widths) {
  const esc = s => String(s == null ? '' : s).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const colName = i => { let s = ''; i++; while (i) { s = String.fromCharCode(65 + (i - 1) % 26) + s; i = Math.floor((i - 1) / 26); } return s; };
  const sheet = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    '<sheetViews><sheetView workbookViewId="0"><pane xSplit="1" ySplit="1" topLeftCell="B2" activePane="bottomRight" state="frozen"/></sheetView></sheetViews>' +
    '<cols>' + widths.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('') + '</cols><sheetData>' +
    rows.map((r, y) => `<row r="${y + 1}">` + r.map((v, x) => `<c r="${colName(x)}${y + 1}" t="inlineStr" s="${y ? 1 : 2}"><is><t xml:space="preserve">${esc(v)}</t></is></c>`).join('') + '</row>').join('') + '</sheetData></worksheet>';
  const zip = new JSZip();
  zip.file('[Content_Types].xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>');
  zip.file('_rels/.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>');
  zip.file('xl/workbook.xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Matrix" sheetId="1" r:id="rId1"/></sheets></workbook>');
  zip.file('xl/_rels/workbook.xml.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>');
  zip.file('xl/styles.xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf></cellXfs></styleSheet>');
  zip.file('xl/worksheets/sheet1.xml', sheet);
  return zip.generateAsync({ type: 'blob', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
}

/** Property names for Obsidian: short, lower case, one per column. */
function mxKeys(columns) {
  const taken = new Set(['matrix', 'title', 'author', 'notes_page', 'tags', 'source_tags', 'year', 'citekey', 'doi']);
  return columns.map(c => {
    let k = norm(c.name).replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'column', base = k, i = 2;
    while (taken.has(k)) k = base + '_' + i++;
    taken.add(k); return k;
  });
}

async function matrixExport(m, kind, { docOf, noteOf, whoOf, titleOf }) {
  const file = safeFileName(m.name || 'Literature matrix');
  const cellOf = (r, c) => (r.cells || {})[c.id] || { text: '', notes: [] };
  const info = mxInfoOn(m), infoOf = (r, x) => { const d = docOf(r.doc); return d ? x[2](d) : ''; };
  const linked = cl => (cl.notes || []).map(noteOf).filter(Boolean);
  const plain = cl => [(cl.text || '').trim(), ...linked(cl).map(n => '– ' + mxNoteLabel(n) + (n.printedPageLabel ? ' (p. ' + n.printedPageLabel + ')' : ''))].filter(Boolean).join('\n');
  if (kind === 'xlsx' || kind === 'csv') {
    const rows = [['Source', 'Author', ...info.map(x => x[1]), ...m.columns.map(c => c.name)], ...m.rows.map(r => [titleOf(r), whoOf(docOf(r.doc)), ...info.map(x => infoOf(r, x)), ...m.columns.map(c => plain(cellOf(r, c)))])];
    if (kind === 'xlsx') return saveBlob(await mxXlsx(rows, [38, 24, ...info.map(x => x[0] === 'year' ? 8 : 22), ...m.columns.map(() => 42)]), file + '.xlsx');
    const q = v => '"' + String(v).replace(/"/g, '""') + '"';
    return saveBlob(new Blob(['﻿' + rows.map(r => r.map(q).join(',')).join('\r\n') + '\r\n'], { type: 'text/csv;charset=utf-8' }), file + '.csv');
  }
  const link = n => '[[' + mdName(n.title || mxNoteLabel(n)) + ']]';
  if (kind === 'md') {
    const td = s => String(s).replace(/\|/g, '\\|').replace(/\r?\n/g, '<br>');
    const L = ['---', 'tags:', '  - Literature_matrix', '---', '', '# ' + (m.name || 'Literature matrix'), '',
      '| Source | ' + [...info.map(x => x[1]), ...m.columns.map(c => td(c.name))].join(' | ') + ' |', '|' + ' --- |'.repeat(m.columns.length + info.length + 1)];
    m.rows.forEach(r => {
      const who = whoOf(docOf(r.doc));
      L.push('| ' + td('**' + titleOf(r) + '**' + (who ? '\n' + who : '')) + ' | ' + info.map(x => td(infoOf(r, x)) + ' | ').join('') + m.columns.map(c => { const cl = cellOf(r, c); return td([(cl.text || '').trim(), linked(cl).map(link).join(' ')].filter(Boolean).join('\n')); }).join(' | ') + ' |');
    });
    return saveBlob(new Blob([L.join('\n') + '\n'], { type: 'text/markdown;charset=utf-8' }), file + '.md');
  }
  // An Obsidian Base: one note per source with a property per column, and the table that shows them.
  const y = s => JSON.stringify(String(s == null ? '' : s)), keys = mxKeys(m.columns), folder = mdName(m.name || 'Literature matrix');
  const zip = new JSZip(), root = zip.folder(folder), taken = new Set();
  m.rows.forEach(r => {
    let f = mdName(titleOf(r)), k = 2; while (taken.has(f.toLowerCase())) f = mdName(titleOf(r)) + ' (' + k++ + ')'; taken.add(f.toLowerCase());
    const d = docOf(r.doc), L = ['---', 'matrix: ' + y(m.name), 'title: ' + y(titleOf(r)), 'author: ' + y(whoOf(d)), 'notes_page: ' + y('[[' + mdName('Notes on ' + titleOf(r)) + ']]')];
    info.forEach(x => { const v = infoOf(r, x); if (x[0] === 'tags') L.push('source_tags:' + (v ? '' : ' []'), ...splitTags(v).map(t => '  - ' + y(t))); else if (x[0] === 'year' && /^\d{4}$/.test(v)) L.push('year: ' + v); else L.push(x[0] + ': ' + y(v)); });
    m.columns.forEach((c, i) => L.push(keys[i] + ': ' + y((cellOf(r, c).text || '').trim())));
    L.push('tags:', '  - Literature_matrix', '---', '', '# ' + titleOf(r), '');
    m.columns.forEach(c => { const cl = cellOf(r, c); if (!mxFilled(cl)) return; L.push('## ' + c.name, ''); if ((cl.text || '').trim()) L.push(cl.text.trim(), ''); const ln = linked(cl); if (ln.length) L.push(...ln.map(n => '- ' + link(n)), ''); });
    root.file(f + '.md', L.join('\n'));
  });
  const B = ['filters:', '  and:', '    - matrix == ' + y(m.name), 'properties:', '  file.name:', '    displayName: Source', '  note.author:', '    displayName: Author'];
  const infoKey = x => x[0] === 'tags' ? 'source_tags' : x[0];
  info.forEach(x => B.push('  note.' + infoKey(x) + ':', '    displayName: ' + y(x[1])));
  m.columns.forEach((c, i) => B.push('  note.' + keys[i] + ':', '    displayName: ' + y(c.name)));
  B.push('views:', '  - type: table', '    name: ' + y(m.name || 'Matrix'), '    order:', '      - file.name', '      - note.author', ...info.map(x => '      - note.' + infoKey(x)), ...keys.map(k => '      - note.' + k));
  root.file(folder + '.base', B.join('\n') + '\n');
  saveBlob(await zip.generateAsync({ type: 'blob' }), file + ' (Obsidian).zip');
  toast('Unpack the zip into your vault and open the .base file');
}
