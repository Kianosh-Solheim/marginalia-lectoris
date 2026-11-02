/* Details view of the library, in the manner of Zotero: a sortable table of items (columns of your
   choice; rows open to show their chapters and notes) and, on the right, the selected item's fields,
   edited in place. Loaded after app.js and zotero.js. */

const DT_COLUMNS = [
  { key: 'creator', label: 'Creator', w: 170, get: d => shortCreator(d) },
  { key: 'year', label: 'Year', w: 74, get: d => d.year || '' },
  { key: 'title', label: 'Title', w: 0, get: d => d.title || '' },
  { key: 'citekey', label: 'Citation Key', w: 230, get: d => d.citekey || '' },
  { key: 'type', label: 'Item Type', w: 120, get: d => dtTypeLabel(d) },
  { key: 'file', label: 'File', w: 80, get: d => d.placeholder ? 'No file' : d.folder ? 'Folder' : (d.fileType || '') },
  { key: 'notes', label: 'Notes', w: 60, num: true, get: d => d.highlights || 0 },
  { key: 'progress', label: 'Read', w: 60, num: true, get: d => d.placeholder || d.folder ? -1 : Math.round((d.progress || 0) * 100), show: v => v < 0 ? '' : v + '%' },
  { key: 'tags', label: 'Tags', w: 180, get: d => splitTags(d.tags).join(', ') },
  { key: 'added', label: 'Date Added', w: 110, num: true, get: d => d.dateAdded || 0, show: v => v ? fmtDate(v) : '' },
  { key: 'zotero', label: 'Zotero', w: 60, get: d => d.zoteroKey ? '✓' : '' },
];
const DT_DEFAULT = ['creator', 'year', 'title', 'citekey', 'file', 'notes'];

const dt = {
  cols: (() => { try { const v = JSON.parse(localGet('ml.dtcols', 'null')); return Array.isArray(v) && v.length ? v : DT_DEFAULT.slice(); } catch (_) { return DT_DEFAULT.slice(); } })(),
  sort: localGet('ml.dtsort', 'title'),
  asc: localGet('ml.dtasc', '1') === '1',
  selected: +localGet('ml.dtsel', '0') || 0,
  open: new Set(),
  full: new Map(),      // id → full details (fetched when a row is opened or selected)
};

function dtTypeLabel(d) {
  if (d.folder) return 'Folder book';
  const t = { book: 'Book', article: 'Journal article', incollection: 'Book section', inbook: 'Book section', phdthesis: 'Thesis', techreport: 'Report', misc: 'Other' }[d.entryType];
  return t || (d.category === 'ARTICLE' ? 'Article' : 'Book');
}

/** "Smith", "Smith and Jones", "Smith et al." — as Zotero shows creators. */
function shortCreator(d) {
  const people = splitPeople((d.author || '').replace(/\s*\(ed\.?\)\s*/gi, ''));
  if (!people.length || /^unknown author$/i.test(people[0])) return '';
  const family = p => p.includes(',') ? p.split(',')[0].trim() : p.trim().split(/\s+/).pop();
  if (people.length === 1) return family(people[0]);
  if (people.length === 2) return `${family(people[0])} and ${family(people[1])}`;
  return `${family(people[0])} et al.`;
}

function renderDetailsTable(shelf, docs) {
  const wrap = h('div', { class: 'dtwrap' });
  const tableBox = h('div', { class: 'dttable' });
  const panel = h('div', { class: 'dtpanel' });
  wrap.append(tableBox, panel);
  shelf.append(wrap);

  const cols = () => DT_COLUMNS.filter(c => dt.cols.includes(c.key));
  const sorted = () => {
    const c = DT_COLUMNS.find(x => x.key === dt.sort) || DT_COLUMNS[2];
    const list = docs.slice();
    list.sort((a, b) => {
      const va = c.get(a), vb = c.get(b);
      const r = c.num ? (va - vb) : String(va).localeCompare(String(vb), undefined, { sensitivity: 'base', numeric: true });
      return (dt.asc ? r : -r) || (a.title || '').localeCompare(b.title || '');
    });
    return list;
  };
  let order = [];

  const draw = () => {
    order = sorted();
    const cs = cols();
    const table = h('table', { class: 'dt' });
    const colgroup = h('colgroup', null, h('col', { style: { width: '22px' } }), ...cs.map(c => h('col', { style: c.w ? { width: c.w + 'px' } : null })));
    const head = h('tr', null, h('th', null, ''), ...cs.map(c => h('th', {
      class: (c.num ? 'num ' : '') + (dt.sort === c.key ? 'on' : ''), title: 'Sort by ' + c.label,
      onclick: () => { if (dt.sort === c.key) dt.asc = !dt.asc; else { dt.sort = c.key; dt.asc = !c.num; } localSet('ml.dtsort', dt.sort); localSet('ml.dtasc', dt.asc ? '1' : '0'); draw(); },
      oncontextmenu: e => { e.preventDefault(); columnMenu(e.clientX, e.clientY); },
    }, c.label, dt.sort === c.key ? h('span', { class: 'dtarrow' }, dt.asc ? ' ▲' : ' ▼') : null)));
    const body = h('tbody');
    order.forEach(d => {
      const isOpen = dt.open.has(d.id);
      const canOpen = d.highlights > 0 || d.folder || d.editedVolume;
      const tr = h('tr', { class: 'dtrow' + (dt.selected === d.id ? ' sel' : ''), 'data-id': d.id,
        onclick: () => select(d.id),
        ondblclick: () => { location.hash = canRead(d) ? `#/read/${d.id}` : `#/book/${d.id}`; } },
        h('td', { class: 'dttw' }, canOpen ? h('button', { class: 'dttwist', title: isOpen ? 'Close' : 'Show chapters and notes', onclick: e => { e.stopPropagation(); toggle(d.id); } }, isOpen ? '▾' : '▸') : ''),
        ...cs.map(c => { const v = c.get(d); return h('td', { class: (c.num ? 'num ' : '') + 'c-' + c.key, title: c.key === 'title' ? v : null }, c.show ? c.show(v) : String(v)); }));
      onContext(tr, () => docMenu(d, () => showLibrary()));
      body.append(tr);
      if (isOpen) childRows(d, cs.length + 1).forEach(r => body.append(r));
    });
    table.append(colgroup, h('thead', null, head), body);
    clear(tableBox).append(
      h('div', { class: 'dtbar' }, h('span', { class: 'muted mono', style: { fontSize: '12px' } }, `${docs.length} item${docs.length === 1 ? '' : 's'}`),
        h('span', { style: { flex: 1 } }),
        h('button', { class: 'btn quiet', title: 'Choose the columns (or right-click a column heading)', onclick: e => { const r = e.target.getBoundingClientRect(); columnMenu(r.left, r.bottom + 4); } }, 'Columns ▾')),
      table);
    const selRow = table.querySelector('tr.sel');
    if (selRow && !dt.scrolled) { selRow.scrollIntoView({ block: 'nearest' }); dt.scrolled = true; }
  };

  const childRows = (d, span) => {
    const f = dt.full.get(d.id);
    if (!f) return [h('tr', { class: 'dtchild' }, h('td', { colspan: span, class: 'muted' }, 'Loading…'))];
    const rows = [];
    const chapters = d.folder ? (f.parts || []).map(p => ({ title: p.title, authors: p.author, go: `#/book/${p.id}`, n: p.number }))
      : (f.chapters || []).filter(c => c.kind === 'chapter').map(c => ({ title: c.title, authors: c.authors, n: c.number, go: isPdf(d) ? `#/read/${d.id}?page=${c.startPage}` : `#/read/${d.id}?href=${encodeURIComponent(c.href || '')}&pos=${c.startPage}`, pages: c.printedPages }));
    chapters.forEach(c => rows.push(h('tr', { class: 'dtchild', onclick: () => { location.hash = c.go; } },
      h('td'), h('td', { colspan: span - 1 }, h('span', { class: 'dtkind' }, '§'), h('span', null, (c.n ? c.n + '  ' : '') + c.title),
        c.authors ? h('span', { class: 'muted' }, ' — ' + splitPeople(c.authors).join(', ')) : null, c.pages ? h('span', { class: 'muted mono' }, '  pp. ' + c.pages) : null))));
    (f.highlightList || []).slice().sort((a, b) => a.pageNumber - b.pageNumber).forEach(n => {
      const loc = n.printedPageLabel ? 'p. ' + n.printedPageLabel : (n.chapterTitle || '');
      rows.push(h('tr', { class: 'dtchild', onclick: () => { location.hash = isPdf(d) ? `#/read/${d.id}?page=${n.pageNumber}&hl=${n.id}` : (canRead(d) ? `#/read/${d.id}?pos=${n.pageNumber}&hl=${n.id}` : `#/book/${d.id}`); } },
        h('td'), h('td', { colspan: span - 1 }, h('span', { class: 'dtkind' }, '✎'), h('b', null, n.title || 'Note'),
          n.highlightedText ? h('span', { class: 'muted' }, ' — ' + n.highlightedText.slice(0, 140) + (n.highlightedText.length > 140 ? '…' : '')) : null,
          loc ? h('span', { class: 'muted mono' }, '  ' + loc) : null)));
    });
    if (!rows.length) rows.push(h('tr', { class: 'dtchild' }, h('td'), h('td', { colspan: span - 1, class: 'muted' }, 'No chapters or notes.')));
    return rows;
  };

  const fetchFull = async id => {
    if (dt.full.has(id)) return dt.full.get(id);
    const f = await api(`doc/${id}`);
    dt.full.set(id, f);
    return f;
  };

  const toggle = async id => {
    if (dt.open.has(id)) dt.open.delete(id); else { dt.open.add(id); draw(); try { await fetchFull(id); } catch (e) { fail(e); } }
    draw();
  };

  const select = async id => {
    dt.selected = id; localSet('ml.dtsel', String(id));
    tableBox.querySelectorAll('tr.dtrow').forEach(r => r.classList.toggle('sel', +r.dataset.id === id));
    clear(panel).append(h('div', { class: 'muted', style: { padding: '16px' } }, 'Loading…'));
    let f;
    try { f = await fetchFull(id); } catch (e) { clear(panel).append(h('div', { class: 'muted', style: { padding: '16px' } }, e.message)); return; }
    if (dt.selected !== id) return;
    const onSave = async changed => {
      // A field was saved: refresh the row from the tablet, then the table and the panel.
      dt.full.delete(id);
      try {
        const fresh = await fetchFull(id);
        const row = docs.find(x => x.id === id);
        if (row) Object.assign(row, { title: fresh.title, author: fresh.author, displayAuthor: fresh.displayAuthor, tags: fresh.tags, summary: fresh.summary,
          category: fresh.category, year: fresh.year, citekey: fresh.citekey, entryType: fresh.entryType, zoteroKey: fresh.zoteroKey, fileType: fresh.fileType, placeholder: fresh.placeholder });
        draw();
        if (dt.selected === id) drawPanel(panel, fresh, onSave);
      } catch (e) { fail(e); }
    };
    drawPanel(panel, f, onSave);
  };

  const columnMenu = (x, y) => {
    document.querySelectorAll('.dtmenu').forEach(m => m.remove());
    const menu = h('div', { class: 'dtmenu', style: { left: Math.min(x, innerWidth - 220) + 'px', top: Math.min(y, innerHeight - 380) + 'px' } });
    DT_COLUMNS.forEach(c => {
      const cb = h('input', { type: 'checkbox', checked: dt.cols.includes(c.key), disabled: c.key === 'title' });
      cb.addEventListener('change', () => {
        dt.cols = DT_COLUMNS.map(x => x.key).filter(k => k === c.key ? cb.checked : dt.cols.includes(k));
        localSet('ml.dtcols', JSON.stringify(dt.cols)); draw();
      });
      menu.append(h('label', null, cb, c.label));
    });
    menu.append(h('button', { class: 'btn quiet', onclick: () => { dt.cols = DT_DEFAULT.slice(); localSet('ml.dtcols', JSON.stringify(dt.cols)); menu.remove(); draw(); } }, 'Default columns'));
    document.body.append(menu);
    setTimeout(() => document.addEventListener('click', function off(e) { if (!menu.contains(e.target)) { menu.remove(); document.removeEventListener('click', off); } }), 0);
  };

  // Keyboard: ↑ ↓ choose, → ← open/close, Enter opens the item.
  const onKey = e => {
    if (!document.body.contains(wrap)) { document.removeEventListener('keydown', onKey); return; }
    if (/INPUT|TEXTAREA|SELECT/.test(document.activeElement && document.activeElement.tagName) || document.querySelector('.modal')) return;
    const i = order.findIndex(d => d.id === dt.selected);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const n = order[Math.max(0, Math.min(order.length - 1, i + (e.key === 'ArrowDown' ? 1 : -1)))];
      if (n) { select(n.id); const r = tableBox.querySelector(`tr[data-id="${n.id}"]`); if (r) r.scrollIntoView({ block: 'nearest' }); }
    } else if (e.key === 'ArrowRight' && i >= 0 && !dt.open.has(dt.selected)) toggle(dt.selected);
    else if (e.key === 'ArrowLeft' && dt.open.has(dt.selected)) toggle(dt.selected);
    else if (e.key === 'Enter' && i >= 0) { const d = order[i]; location.hash = canRead(d) ? `#/read/${d.id}` : `#/book/${d.id}`; }
  };
  document.addEventListener('keydown', onKey);

  dt.scrolled = false;
  draw();
  if (dt.selected && docs.some(d => d.id === dt.selected)) select(dt.selected);
  else clear(panel).append(h('div', { class: 'muted', style: { padding: '16px' } }, 'Choose an item to see and edit its details.'));
}

// ------------------------------------------------------------------ the item panel

/** Fields in Zotero's order for each kind of item. [key, label, where] — where: 'doc' (the book itself) or 'bib'. */
function dtFields(type) {
  const common = [['YEAR', 'Date'], ['DOI', 'DOI'], ['URL', 'URL'], ['LANGUAGE', 'Language']];
  const book = [['EDITOR', 'Editor(s)'], ['TRANSLATOR', 'Translator'], ['SERIES', 'Series'], ['VOLUME', 'Volume'], ['EDITION', 'Edition'], ['PLACE', 'Place'], ['PUBLISHER', 'Publisher'], ['YEAR', 'Date'], ['PAGES', 'Pages'], ['ISBN', 'ISBN'], ['DOI', 'DOI'], ['URL', 'URL'], ['LANGUAGE', 'Language']];
  switch (type) {
    case 'article': return [['JOURNAL', 'Publication'], ['VOLUME', 'Volume'], ['ISSUE', 'Issue'], ['PAGES', 'Pages'], ['YEAR', 'Date'], ['SERIES', 'Series'], ['DOI', 'DOI'], ['ISSN', 'ISSN'], ['URL', 'URL'], ['LANGUAGE', 'Language']];
    case 'incollection': case 'inbook': return [['BOOKTITLE', 'Book Title'], ['EDITOR', 'Editor(s)'], ['TRANSLATOR', 'Translator'], ['SERIES', 'Series'], ['VOLUME', 'Volume'], ['EDITION', 'Edition'], ['PLACE', 'Place'], ['PUBLISHER', 'Publisher'], ['YEAR', 'Date'], ['PAGES', 'Pages'], ['ISBN', 'ISBN'], ['DOI', 'DOI'], ['URL', 'URL'], ['LANGUAGE', 'Language']];
    case 'phdthesis': return [['PUBLISHER', 'University'], ['PLACE', 'Place'], ...common, ['PAGES', 'Pages']];
    case 'techreport': return [['SERIES', 'Report Series'], ['ISSUE', 'Report Number'], ['PLACE', 'Place'], ['PUBLISHER', 'Institution'], ...common, ['PAGES', 'Pages']];
    case 'misc': return [['JOURNAL', 'Publication'], ['PUBLISHER', 'Publisher'], ['PLACE', 'Place'], ...common];
    default: return book;
  }
}

function drawPanel(panel, f, onSaved) {
  const bib = (f.bib && f.bib.fields) || {};
  const type = (f.bib && f.bib.entryType) || (f.category === 'ARTICLE' ? 'article' : 'book');
  const saveDoc = async body => { await api(`doc/${f.id}`, { method: 'PATCH', json: body }); onSaved(); };
  const saveBib = async body => { await api(`doc/${f.id}/bib`, { method: 'PUT', json: body }); onSaved(); };

  /** A value shown as text; click to edit, Enter or leaving the field saves, Escape cancels. */
  const field = (label, value, save, opts = {}) => {
    const val = h('div', { class: 'dtval' + (value ? '' : ' empty') + (opts.mono ? ' mono' : ''), title: opts.readonly ? null : 'Click to edit' }, value || opts.placeholder || '');
    const row = h('div', { class: 'dtfield' }, h('div', { class: 'dtlab' }, label), val);
    if (opts.readonly) { val.classList.add('ro'); return row; }
    val.addEventListener('click', () => {
      if (val.querySelector('input,textarea')) return;
      const input = opts.multiline ? h('textarea', { rows: 6 }) : h('input', { type: 'text' });
      input.value = value || '';
      clear(val).append(input); val.classList.remove('empty');
      input.focus(); input.select && !opts.multiline && input.select();
      let done = false;
      const finish = async ok => {
        if (done) return; done = true;
        const nv = input.value.trim();
        if (!ok || nv === (value || '').trim()) { clear(val).append(value || opts.placeholder || ''); if (!value) val.classList.add('empty'); return; }
        clear(val).append(nv || opts.placeholder || ''); val.classList.add('saving');
        try { await save(nv); toast('Saved'); } catch (e) { fail(e); clear(val).append(value || ''); }
        val.classList.remove('saving');
      };
      input.addEventListener('keydown', e => {
        if (e.key === 'Escape') { e.preventDefault(); finish(false); }
        if (e.key === 'Enter' && (!opts.multiline || e.ctrlKey || e.metaKey)) { e.preventDefault(); finish(true); }
        e.stopPropagation();
      });
      input.addEventListener('blur', () => finish(true));
    });
    return row;
  };

  const section = (title, open, ...content) => {
    const body = h('div', { class: 'dtsecbody', hidden: !open });
    body.append(...content.filter(Boolean));
    const key = 'ml.dtsec.' + title;
    const isOpen = localGet(key, open ? '1' : '0') === '1';
    body.hidden = !isOpen;
    const head = h('button', { class: 'dtsechead', onclick: () => { body.hidden = !body.hidden; localSet(key, body.hidden ? '0' : '1'); head.lastChild.textContent = body.hidden ? '▸' : '▾'; } },
      h('span', null, title), h('span', null, isOpen ? '▾' : '▸'));
    return h('div', { class: 'dtsec' }, head, body);
  };

  // Item type
  const typeSel = h('select', null, ...INFO_TYPES.map(([v, l]) => h('option', { value: v, selected: v === type }, l)));
  typeSel.addEventListener('change', () => saveBib({ _type: typeSel.value }).catch(fail));
  const catSel = h('select', null, h('option', { value: 'BOOK', selected: f.category !== 'ARTICLE' }, 'Books'), h('option', { value: 'ARTICLE', selected: f.category === 'ARTICLE' }, 'Articles'));
  catSel.addEventListener('change', () => saveDoc({ category: catSel.value }).catch(fail));

  const authorLabel = f.folder ? 'Editor(s)' : 'Author(s)';
  const info = [
    h('div', { class: 'dtfield' }, h('div', { class: 'dtlab' }, 'Item Type'), h('div', { class: 'dtval' }, typeSel)),
    field('Title', f.title, v => saveDoc({ title: v })),
    field(authorLabel, splitPeople(f.author).join('; '), v => saveDoc({ author: joinPeople(splitPeople(v)) }), { placeholder: 'Name Nameson; Other Person' }),
    ...dtFields(type).filter(([k]) => !(f.folder && k === 'EDITOR')).map(([k, label]) => field(label, bib[k], v => saveBib({ [k]: v }), { mono: k === 'DOI' || k === 'ISBN' || k === 'ISSN' })),
    field('Citation Key', bib.CITEKEY, v => saveBib({ CITEKEY: v }), { mono: true, placeholder: f.citekey ? `${f.citekey} (automatic)` : '' }),
    h('div', { class: 'dtfield' }, h('div', { class: 'dtlab' }, 'Shelf'), h('div', { class: 'dtval' }, catSel)),
    field('Date Added', fmtDate(f.dateAdded), null, { readonly: true }),
    f.placeholder || f.folder ? null : field('Last Read', f.lastRead ? fmtDate(f.lastRead) : '', null, { readonly: true }),
  ];

  const tags = splitTags(f.tags);
  const notes = (f.highlightList || []).slice().sort((a, b) => a.pageNumber - b.pageNumber);
  const attach = h('div', { class: 'dtattach' },
    h('img', { src: coverUrl(f), alt: '', class: 'dtcover', onclick: () => { location.hash = `#/book/${f.id}`; } }),
    h('div', null,
      h('div', { class: 'mono', style: { fontSize: '12px' } }, f.placeholder ? 'No file yet · from Zotero' : f.folder ? `Folder book · ${(f.parts || []).length} chapters` : `${f.fileType} · ${fmtSize(f.size)}${f.totalPages ? ` · ${f.totalPages} ${isEpub(f) ? 'positions' : 'pages'}` : ''}`),
      f.fileName && !f.placeholder && !f.folder ? h('div', { class: 'muted', style: { fontSize: '12px', wordBreak: 'break-all' } }, f.fileName) : null,
      h('div', { class: 'row', style: { gap: '4px', marginTop: '6px' } },
        canRead(f) ? h('a', { class: 'btn fill', href: `#/read/${f.id}` }, f.progress > 0 ? 'Continue' : 'Read') : null,
        h('a', { class: 'btn', href: `#/book/${f.id}` }, 'Book page'),
        f.placeholder ? h('button', { class: 'btn', onclick: () => placeholderFetch(f, () => onSaved()) }, 'Get file from Zotero') : null,
        !f.placeholder && !f.folder ? h('button', { class: 'btn quiet', onclick: () => download(`doc/${f.id}/file?download=1`, 'Downloading…') }, 'Download') : null)));

  const zot = h('div', { class: 'dtzot' });
  zoteroConnected().then(ok => {
    if (!ok) { zot.append(h('div', { class: 'muted' }, 'Zotero is not connected (Settings ▸ Zotero).')); return; }
    if (f.zoteroKey) zot.append(h('div', { class: 'zstate ok' }, '▣ Linked to Zotero' + (f.zoteroSynced ? ' · kept in step' : '')),
      h('div', { class: 'row', style: { gap: '4px', marginTop: '6px' } },
        h('button', { class: 'btn', onclick: () => zoteroCheckDialog(f.id, null, () => onSaved()) }, 'Check citation'),
        h('a', { class: 'btn quiet', href: zoteroLink(f.zoteroKey) }, 'Open in Zotero')));
    else zot.append(h('div', { class: 'muted' }, 'Not linked to a Zotero item.'), h('a', { class: 'btn', href: `#/book/${f.id}`, style: { marginTop: '6px' } }, 'Link or add on the book page'));
  });

  clear(panel).append(
    h('div', { class: 'dthead' }, h('div', { class: 'dttitle' }, f.title), h('div', { class: 'muted' }, [f.displayAuthor || f.author, f.year].filter(Boolean).join(' · '))),
    section('Info', true, ...info),
    section('Abstract', true, field('', f.summary, v => saveDoc({ summary: v }), { multiline: true, placeholder: 'Add an abstract…' })),
    section(`Tags · ${tags.length}`, true, field('', tags.join(', '), v => saveDoc({ tags: splitTags(v).join(', ') }), { placeholder: 'Add tags, separated by commas…' }),
      tags.length ? h('div', { class: 'tags', style: { margin: '4px 0 0' } }, tags.map(t => h('span', { class: 'tag' }, '#' + t))) : null),
    section(`Notes · ${notes.length}`, notes.length > 0, ...(notes.length ? notes.slice(0, 50).map(n => h('div', { class: 'dtnote', onclick: () => { location.hash = canRead(f) ? (isPdf(f) ? `#/read/${f.id}?page=${n.pageNumber}&hl=${n.id}` : `#/read/${f.id}?pos=${n.pageNumber}&hl=${n.id}`) : `#/book/${f.id}`; } },
      h('b', null, n.title || 'Note'), n.highlightedText ? h('div', { class: 'muted' }, n.highlightedText.slice(0, 160) + (n.highlightedText.length > 160 ? '…' : '')) : null,
      n.printedPageLabel ? h('div', { class: 'mono muted', style: { fontSize: '11px' } }, 'p. ' + n.printedPageLabel) : null)) : [h('div', { class: 'muted' }, 'No notes yet.')])),
    section('Attachment', true, attach),
    section('Zotero', true, zot));
}
