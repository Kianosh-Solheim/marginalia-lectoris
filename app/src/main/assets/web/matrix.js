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
  await api('note-pages', { method: 'PUT', json: { key: 'matrix:' + m.id, page: { name: m.name, columns: m.columns, rows: m.rows, created: m.created, modified: m.modified } } });
}
const mxCell = (row, colId) => { row.cells = row.cells || {}; return row.cells[colId] || (row.cells[colId] = { text: '', notes: [] }); };
const mxNoteLabel = n => (n.title || (n.highlightedText || n.userNotes || 'Note').replace(/\s+/g, ' ').slice(0, 60)).trim();
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

  function addSources() {
    const have = new Set(m.rows.map(r => r.doc)), pickable = (state.docs || []).filter(d => !have.has(d.id));
    modal((box, close) => {
      box.style.width = '680px';
      const chosen = new Set();
      const q = h('input', { type: 'search', placeholder: 'Search the library' });
      const list = h('div', { style: { maxHeight: '52vh', overflow: 'auto', borderTop: '1px solid var(--ink)', marginTop: '8px' } });
      const ok = h('button', { class: 'btn fill', disabled: true }, 'Add');
      const draw = () => {
        clear(list);
        const terms = norm(q.value).split(/\s+/).filter(Boolean);
        pickable.filter(x => terms.every(t => norm(x.title + ' ' + whoOf(x) + ' ' + (x.tags || '')).includes(t))).slice(0, 300).forEach(x => {
          const cb = h('input', { type: 'checkbox', checked: chosen.has(x.id) });
          cb.addEventListener('change', () => { if (cb.checked) chosen.add(x.id); else chosen.delete(x.id); ok.disabled = !chosen.size; ok.textContent = chosen.size ? `Add ${chosen.size}` : 'Add'; });
          const nn = notes.filter(n => n.documentId === x.id).length;
          list.append(h('label', { class: 'row', style: { padding: '7px 2px', borderBottom: '1px solid var(--rule)', cursor: 'pointer', flexWrap: 'nowrap' } }, cb,
            h('span', { style: { minWidth: 0 } }, h('b', { class: 'serif' }, x.title), h('div', { class: 'muted', style: { fontSize: '12px' } }, [whoOf(x), nn ? `${nn} note${nn === 1 ? '' : 's'}` : ''].filter(Boolean).join(' · ')))));
        });
        if (!list.firstChild) list.append(h('div', { class: 'muted', style: { padding: '10px 0' } }, pickable.length ? 'Nothing matches.' : 'Everything in the library is already in this matrix.'));
      };
      q.addEventListener('input', draw);
      ok.addEventListener('click', () => { chosen.forEach(did => m.rows.push({ doc: did, title: (docOf(did) || {}).title || '', cells: {} })); close(); changed(); draw_(); });
      box.append(h('h2', null, 'Add sources to the matrix'), q, list, h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), ok));
      draw();
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
  function sortRows(by) { const key = r => norm(by === 'author' ? whoOf(docOf(r.doc)) + ' ' + titleOf(r) : titleOf(r)); m.rows.sort((a, b) => key(a).localeCompare(key(b))); changed(); draw_(); }

  function draw_() {
    const top = wrap.scrollTop, left = wrap.scrollLeft;
    clear(wrap);
    if (!m.rows.length) { wrap.append(h('div', { class: 'empty' }, h('h2', null, 'No sources yet'), h('p', { class: 'muted' }, 'Choose the books and articles this matrix compares.'), h('button', { class: 'btn fill', onclick: addSources }, '+ Add sources'))); return; }
    const table = h('table', { class: 'mx' });
    const head = h('tr', null, h('th', { class: 'mxsrc' }, 'Source'));
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
  main.append(h('div', { class: 'mxbar' },
    h('a', { class: 'btn quiet', href: '#/matrix', title: 'All matrices' }, '‹ Matrices'), name,
    h('button', { class: 'btn fill', onclick: addSources }, '+ Add sources'),
    h('button', { class: 'btn', onclick: addCol }, '+ Column'),
    menuButton('Sort ▾', [['By author', 'Then by title', () => sortRows('author')], ['By title', '', () => sortRows('title')]]),
    menuButton('Export ▾', [
      ['Excel', 'A workbook (.xlsx)', () => exp('xlsx')],
      ['CSV', 'For any spreadsheet program', () => exp('csv')],
      ['Markdown table', 'One note for Obsidian, with [[links]] to your notes', () => exp('md')],
      ['Obsidian database', 'A Base: one note per source and a table over them (zip)', () => exp('base')]]),
    h('span', { style: { flex: 1 } }), status), wrap);
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
  const taken = new Set(['matrix', 'title', 'author', 'notes_page', 'tags']);
  return columns.map(c => {
    let k = norm(c.name).replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'column', base = k, i = 2;
    while (taken.has(k)) k = base + '_' + i++;
    taken.add(k); return k;
  });
}

async function matrixExport(m, kind, { docOf, noteOf, whoOf, titleOf }) {
  const file = safeFileName(m.name || 'Literature matrix');
  const cellOf = (r, c) => (r.cells || {})[c.id] || { text: '', notes: [] };
  const linked = cl => (cl.notes || []).map(noteOf).filter(Boolean);
  const plain = cl => [(cl.text || '').trim(), ...linked(cl).map(n => '– ' + mxNoteLabel(n) + (n.printedPageLabel ? ' (p. ' + n.printedPageLabel + ')' : ''))].filter(Boolean).join('\n');
  if (kind === 'xlsx' || kind === 'csv') {
    const rows = [['Source', 'Author', ...m.columns.map(c => c.name)], ...m.rows.map(r => [titleOf(r), whoOf(docOf(r.doc)), ...m.columns.map(c => plain(cellOf(r, c)))])];
    if (kind === 'xlsx') return saveBlob(await mxXlsx(rows, [38, 24, ...m.columns.map(() => 42)]), file + '.xlsx');
    const q = v => '"' + String(v).replace(/"/g, '""') + '"';
    return saveBlob(new Blob(['﻿' + rows.map(r => r.map(q).join(',')).join('\r\n') + '\r\n'], { type: 'text/csv;charset=utf-8' }), file + '.csv');
  }
  const link = n => '[[' + mdName(n.title || mxNoteLabel(n)) + ']]';
  if (kind === 'md') {
    const td = s => String(s).replace(/\|/g, '\\|').replace(/\r?\n/g, '<br>');
    const L = ['---', 'tags:', '  - Literature_matrix', '---', '', '# ' + (m.name || 'Literature matrix'), '',
      '| Source | ' + m.columns.map(c => td(c.name)).join(' | ') + ' |', '|' + ' --- |'.repeat(m.columns.length + 1)];
    m.rows.forEach(r => {
      const who = whoOf(docOf(r.doc));
      L.push('| ' + td('**' + titleOf(r) + '**' + (who ? '\n' + who : '')) + ' | ' + m.columns.map(c => { const cl = cellOf(r, c); return td([(cl.text || '').trim(), linked(cl).map(link).join(' ')].filter(Boolean).join('\n')); }).join(' | ') + ' |');
    });
    return saveBlob(new Blob([L.join('\n') + '\n'], { type: 'text/markdown;charset=utf-8' }), file + '.md');
  }
  // An Obsidian Base: one note per source with a property per column, and the table that shows them.
  const y = s => JSON.stringify(String(s == null ? '' : s)), keys = mxKeys(m.columns), folder = mdName(m.name || 'Literature matrix');
  const zip = new JSZip(), root = zip.folder(folder), taken = new Set();
  m.rows.forEach(r => {
    let f = mdName(titleOf(r)), k = 2; while (taken.has(f.toLowerCase())) f = mdName(titleOf(r)) + ' (' + k++ + ')'; taken.add(f.toLowerCase());
    const d = docOf(r.doc), L = ['---', 'matrix: ' + y(m.name), 'title: ' + y(titleOf(r)), 'author: ' + y(whoOf(d)), 'notes_page: ' + y('[[' + mdName('Notes on ' + titleOf(r)) + ']]')];
    m.columns.forEach((c, i) => L.push(keys[i] + ': ' + y((cellOf(r, c).text || '').trim())));
    L.push('tags:', '  - Literature_matrix', '---', '', '# ' + titleOf(r), '');
    m.columns.forEach(c => { const cl = cellOf(r, c); if (!mxFilled(cl)) return; L.push('## ' + c.name, ''); if ((cl.text || '').trim()) L.push(cl.text.trim(), ''); const ln = linked(cl); if (ln.length) L.push(...ln.map(n => '- ' + link(n)), ''); });
    root.file(f + '.md', L.join('\n'));
  });
  const B = ['filters:', '  and:', '    - matrix == ' + y(m.name), 'properties:', '  file.name:', '    displayName: Source', '  note.author:', '    displayName: Author'];
  m.columns.forEach((c, i) => B.push('  note.' + keys[i] + ':', '    displayName: ' + y(c.name)));
  B.push('views:', '  - type: table', '    name: ' + y(m.name || 'Matrix'), '    order:', '      - file.name', '      - note.author', ...keys.map(k => '      - note.' + k));
  root.file(folder + '.base', B.join('\n') + '\n');
  saveBlob(await zip.generateAsync({ type: 'blob' }), file + ' (Obsidian).zip');
  toast('Unpack the zip into your vault and open the .base file');
}
