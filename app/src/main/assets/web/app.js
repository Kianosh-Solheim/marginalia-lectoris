/* Marginalia Lectoris — library in the browser.
 * Talks to the web server inside the tablet app. Plain JavaScript, no build step. */
'use strict';

const COLORS = [
  ['Yellow', '#FFEB3B'], ['Amber', '#FFE082'], ['Orange', '#FF9800'], ['Green', '#81C784'],
  ['Blue', '#64B5F6'], ['Purple', '#BA68C8'], ['Coral', '#EF5350'], ['Grey', '#E4E4E7']
];

const state = {
  device: '',
  docs: [],
  view: localGet('ml.view', 'grid'),
  sort: localGet('ml.sort', 'recent'),
  tab: 'all',
  query: '',
  tag: '',
  category: localGet('ml.uploadCategory', 'BOOK'),
  uploadFolder: null,   // { id, title } while a folder book's page is open: uploads become its chapters
};

// ------------------------------------------------------------------ small helpers

function localGet(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (_) { return d; } }
function localSet(k, v) { try { localStorage.setItem(k, v); } catch (_) {} }

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'html') el.innerHTML = v;
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid === null || kid === undefined || kid === false) continue;
    el.appendChild(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

let toastTimer = 0;
function toast(msg, ms = 2600) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer);
  if (ms > 0) toastTimer = setTimeout(() => { t.hidden = true; }, ms);
}

function fmtSize(n) {
  if (!n) return '';
  if (n < 1024 * 1024) return Math.max(1, Math.round(n / 1024)) + ' KB';
  return (n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0) + ' MB';
}
function fmtDate(ms) { try { return new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }); } catch (_) { return ''; } }
function pct(p) { return Math.round((p || 0) * 100); }
function splitTags(s) { return (s || '').split(',').map(t => t.trim().replace(/^#/, '')).filter(Boolean); }
function norm(s) { return (s || '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, ''); }
function isPdf(d) { return (d.fileType || '').toUpperCase() === 'PDF' || /\.pdf$/i.test(d.fileName || ''); }
function isEpub(d) { return (d.fileType || '').toUpperCase() === 'EPUB' || /\.epub$/i.test(d.fileName || ''); }
function canRead(d) { return isPdf(d) || isEpub(d); }

class ApiError extends Error { constructor(status, msg) { super(msg); this.status = status; } }

async function api(path, opts = {}) {
  const o = { method: opts.method || 'GET', headers: { 'X-Marginalia': '1' }, credentials: 'same-origin' };
  if (opts.json !== undefined) { o.body = JSON.stringify(opts.json); o.headers['Content-Type'] = 'application/json'; }
  let r;
  const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timer = ctl ? setTimeout(() => ctl.abort(), opts.timeout || 90000) : 0;
  if (ctl) o.signal = ctl.signal;
  try { r = await fetch('/api/' + path, o); }
  catch (e) { throw new ApiError(0, e && e.name === 'AbortError' ? 'The tablet took too long to answer.' : 'The tablet is not answering. Is the app\'s server still switched on?'); }
  finally { clearTimeout(timer); }
  if (r.status === 401 && path !== 'login') { showLogin(); throw new ApiError(401, 'Please sign in.'); }
  const text = await r.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (_) { data = { error: text }; }
  if (!r.ok) throw new ApiError(r.status, data.error || ('Error ' + r.status));
  return data;
}

function fail(e) { console.error(e); if (e && e.status !== 401) toast(e.message || String(e), 5000); }

/** Downloads an export/file from the tablet, keeping the name the tablet gives it. */
async function download(path, busy = 'Preparing…') {
  toast(busy, 0);
  try {
    const r = await fetch('/api/' + path, { credentials: 'same-origin' });
    if (r.status === 401) { showLogin(); return; }
    if (!r.ok) { let m = 'Error ' + r.status; try { m = (await r.json()).error || m; } catch (_) {} throw new Error(m); }
    const cd = r.headers.get('Content-Disposition') || '';
    let name = 'download';
    const star = /filename\*=UTF-8''([^;]+)/i.exec(cd), plain = /filename="([^"]+)"/i.exec(cd);
    if (star) name = decodeURIComponent(star[1]); else if (plain) name = plain[1];
    const blob = await r.blob();
    const url = URL.createObjectURL(blob);
    const a = h('a', { href: url, download: name });
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    toast('Downloaded ' + name);
  } catch (e) { fail(e); }
}

async function copyText(text, what = 'Copied') {
  try { await navigator.clipboard.writeText(text); toast(what); }
  catch (_) {
    const ta = h('textarea', { style: { position: 'fixed', opacity: '0' } }, text);
    document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); toast(what); } catch (e) { toast('Could not copy'); }
    ta.remove();
  }
}

function modal(build, onClose) {
  const root = document.getElementById('modal-root');
  let closed = false;
  const close = () => { if (closed) return; closed = true; scrim.remove(); document.removeEventListener('keydown', onKey); if (onClose) onClose(); };
  const onKey = e => { if (e.key === 'Escape') close(); };
  const box = h('div', { class: 'modal', role: 'dialog' });
  const scrim = h('div', { class: 'scrim', onmousedown: e => { if (e.target === scrim) close(); } }, box);
  build(box, close);
  root.appendChild(scrim);
  document.addEventListener('keydown', onKey);
  const first = box.querySelector('input, textarea');
  if (first) setTimeout(() => first.focus(), 30);
  return close;
}

function confirmBox(title, text, okLabel, onOk) {
  modal((box, close) => {
    box.append(
      h('h2', null, title),
      h('p', null, text),
      h('div', { class: 'foot' },
        h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn fill', onclick: () => { close(); onOk(); } }, okLabel))
    );
  });
}

function swatches(current, onPick) {
  const wrap = h('div', { class: 'swatches' });
  COLORS.forEach(([name, hex]) => {
    const b = h('button', { title: name, style: { background: hex }, class: hex.toUpperCase() === (current || '').toUpperCase() ? 'on' : '' });
    b.addEventListener('click', e => { e.preventDefault(); wrap.querySelectorAll('button').forEach(x => x.classList.remove('on')); b.classList.add('on'); onPick(hex); });
    wrap.appendChild(b);
  });
  return wrap;
}

// ------------------------------------------------------------------ routing

function route() {
  const hash = location.hash.replace(/^#\/?/, '');
  const [path, qs] = hash.split('?');
  const params = new URLSearchParams(qs || '');
  const parts = path.split('/').filter(Boolean);
  closeReader();
  state.uploadFolder = null;
  state.currentBook = parts[0] === 'book' && parts[1] ? +parts[1] : null;
  state.currentDoc = null;
  if (parts[0] === 'book' && parts[1]) return showBook(+parts[1]);
  if (parts[0] === 'read' && parts[1]) return openReader(+parts[1], params);
  if (parts[0] === 'notes') { state.tab = 'notes'; return showNotes(); }
  if (parts[0] === 'settings') { state.tab = 'settings'; return showSettings(); }
  state.tab = ['books', 'articles'].includes(parts[0]) ? parts[0] : 'all';
  return showLibrary();
}
window.addEventListener('hashchange', route);

// ------------------------------------------------------------------ sign-in

function showLogin() {
  closeReader();
  const app = clear(document.getElementById('app'));
  const input = h('input', { inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: '7', placeholder: '••• •••', 'aria-label': 'PIN' });
  const err = h('div', { class: 'err' });
  const go = async () => {
    err.textContent = '';
    try {
      await api('login', { method: 'POST', json: { pin: input.value.replace(/\D/g, '') } });
      route();
    } catch (e) { err.textContent = e.message; input.select(); }
  };
  input.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
  app.appendChild(h('div', { class: 'login' }, h('div', { class: 'box' },
    h('div', { class: 'label' }, state.device || 'Tablet'),
    h('h1', null, 'Marginalia Lectoris'),
    h('div', { class: 'muted' }, 'Enter the PIN shown in the app under Settings → Library in the browser.'),
    input, err,
    h('button', { class: 'btn fill', style: { width: '100%', justifyContent: 'center' }, onclick: go }, 'Sign in'),
    h('p', { class: 'muted', style: { fontSize: '12px', marginBottom: 0 } }, 'This browser stays signed in for 30 days.')
  )));
  setTimeout(() => input.focus(), 30);
}

// ------------------------------------------------------------------ header

function header() {
  const tabs = [['all', 'Library', '#/'], ['books', 'Books', '#/books'], ['articles', 'Articles', '#/articles'], ['notes', 'Notes', '#/notes'], ['settings', 'Settings', '#/settings']];
  const search = h('input', { type: 'search', class: 'search', placeholder: 'Search title, author, tag…', value: state.query });
  search.addEventListener('input', () => {
    state.query = search.value;
    if (state.tab === 'notes') renderNotesList(); else renderShelf();
  });
  const file = h('input', { type: 'file', multiple: true, accept: '.pdf,.epub,.zip,.html,.htm,.xhtml,.txt,.md', hidden: true });
  file.addEventListener('change', () => { if (file.files.length) uploadFiles([...file.files]); file.value = ''; });
  return h('header', { class: 'top' }, h('div', { class: 'top-inner' },
    h('div', { class: 'brand', onclick: () => { location.hash = '#/'; } }, 'Marginalia Lectoris', h('small', null, state.device || 'Tablet')),
    h('nav', { class: 'tabs' }, tabs.map(([k, label, href]) =>
      h('button', { class: state.tab === k ? 'on' : '', onclick: () => { location.hash = href; } }, label))),
    h('div', { class: 'spacer' }),
    search,
    h('button', { class: 'btn fill', onclick: () => file.click(), title: 'Or drag files anywhere onto this page' }, '+ Add files'),
    file,
    h('button', { class: 'btn quiet', title: 'Sign this browser out', onclick: async () => { try { await api('logout', { method: 'POST' }); } catch (_) {} showLogin(); } }, 'Sign out')
  ));
}

// ------------------------------------------------------------------ right-click menus

let cmRoot = null;
function closeCtxMenu() { const r = cmRoot; cmRoot = null; if (r) r.remove(); }
['mousedown', 'wheel', 'blur', 'resize', 'hashchange'].forEach(ev => window.addEventListener(ev, e => {
  if (!cmRoot) return;
  if (ev === 'blur' && e.target !== window) return;
  if ((ev === 'mousedown' || ev === 'wheel') && cmRoot.contains(e.target)) return;
  closeCtxMenu();
}, true));
document.addEventListener('keydown', e => { if (e.key === 'Escape' && cmRoot) { e.stopPropagation(); closeCtxMenu(); } }, true);

/** Shows a menu at (x, y). Items: { label, hint, fn, sub (array or async function), color, danger, disabled } or '-'. */
function openCtxMenu(x, y, items) {
  closeCtxMenu();
  cmRoot = h('div', { class: 'cm-root', oncontextmenu: e => e.preventDefault() });
  document.body.append(cmRoot);
  const first = cmMenu(items, 0);
  cmRoot.append(first);
  cmPlace(first, x, y, null);
}

function cmPlace(m, x, y, fromRect) {
  const vw = window.innerWidth, vh = window.innerHeight, r = m.getBoundingClientRect();
  let left = x, top = y;
  if (fromRect) { left = fromRect.right - 2; top = fromRect.top - 4; if (left + r.width > vw - 4) left = Math.max(4, fromRect.left - r.width + 2); }
  else if (left + r.width > vw - 4) left = Math.max(4, vw - r.width - 4);
  if (top + r.height > vh - 4) top = Math.max(4, vh - r.height - 4);
  m.style.left = left + 'px'; m.style.top = top + 'px';
}

function cmMenu(items, level) {
  const m = h('div', { class: 'cm', 'data-level': level, role: 'menu' });
  const closeDeeper = () => [...cmRoot.querySelectorAll('.cm')].forEach(x => { if (+x.dataset.level > level) x.remove(); });
  const list = (items || []).filter(Boolean);
  if (!list.filter(x => x !== '-').length) list.splice(0, list.length, { label: 'Nothing here', disabled: true });
  list.forEach(it => {
    if (it === '-') { m.append(h('div', { class: 'cm-sep' })); return; }
    const b = h('button', { class: 'cm-item' + (it.danger ? ' danger' : '') + (it.sub ? ' has-sub' : ''), disabled: !!it.disabled, role: 'menuitem' },
      it.color ? h('i', { class: 'cm-sw', style: { background: it.color } }) : null,
      h('span', { class: 'cm-l' }, it.label),
      it.hint ? h('span', { class: 'cm-h' }, it.hint) : null,
      it.sub ? h('span', { class: 'cm-arrow' }, '▸') : null);
    let timer = 0;
    const openSub = async () => {
      closeDeeper();
      [...m.querySelectorAll('.cm-item.open')].forEach(x => x.classList.remove('open'));
      b.classList.add('open');
      const sm = cmMenu(Array.isArray(it.sub) ? it.sub : [{ label: 'Loading…', disabled: true }], level + 1);
      cmRoot.append(sm); cmPlace(sm, 0, 0, b.getBoundingClientRect());
      if (typeof it.sub === 'function') {
        let list;
        try { list = await it.sub(); } catch (e) { list = [{ label: e.message || String(e), disabled: true }]; }
        if (!sm.isConnected) return;
        const fresh = cmMenu(list && list.length ? list : [{ label: 'Nothing here', disabled: true }], level + 1);
        sm.replaceWith(fresh); cmPlace(fresh, 0, 0, b.getBoundingClientRect());
      }
    };
    if (it.sub) {
      b.addEventListener('mouseenter', () => { clearTimeout(timer); timer = setTimeout(openSub, 120); });
      b.addEventListener('mouseleave', () => clearTimeout(timer));
      b.addEventListener('click', e => { e.stopPropagation(); openSub(); });
    } else {
      b.addEventListener('mouseenter', () => { closeDeeper(); [...m.querySelectorAll('.cm-item.open')].forEach(x => x.classList.remove('open')); });
      b.addEventListener('click', () => { closeCtxMenu(); if (it.fn) it.fn(); });
    }
    m.append(b);
  });
  return m;
}

function onContext(el, build) {
  el.addEventListener('contextmenu', e => {
    const items = build(e);
    if (!items) return;
    e.preventDefault(); e.stopPropagation();
    openCtxMenu(e.clientX, e.clientY, items);
  });
}

/** In-text citation in the default style, with a page or location when known. */
function inTextFor(d, page) {
  const bib = (d && d.bib) || {};
  const st = (bib.styles || []).find(s => s.id === bib.defaultStyle) || (bib.styles || [])[0];
  let it = plainRef(st ? st.inText : '');
  if (!it || !page) return it;
  const p = String(page);
  const bare = p.replace(/^pp?\.\s*/, '');
  const id = st.id;
  const add = id === 'APA7' || id === 'HARVARD' ? (/[–-]/.test(bare) ? 'pp. ' : 'p. ') + bare : bare;
  if (id === 'CHICAGO_NOTES') return it.replace(/\.$/, '') + ', ' + add + '.';
  return /\)$/.test(it) ? it.replace(/\)$/, (id === 'MLA9' ? ' ' : ', ') + add + ')') : it + ', ' + add;
}

async function fullDoc(d) { return d.bib && d.highlightList ? d : api(`doc/${d.id}`); }

async function copyFromFile(path, what) {
  try {
    const r = await fetch('/api/' + path, { credentials: 'same-origin' });
    if (!r.ok) throw new Error('Error ' + r.status);
    copyText(await r.text(), what);
  } catch (e) { fail(e); }
}

// ----- library cards

function docMenu(d, onChanged) {
  const reload = onChanged || (() => route());
  const exportsFor = () => {
    const list = d.folder
      ? [['All chapters (.zip)', () => download(`doc/${d.id}/export/folder`, 'Exporting every chapter…')], ['BibTeX (.bib)', () => download(`doc/${d.id}/export/bib`, 'Preparing…')]]
      : EXPORTS.map(([k, label]) => [label, () => download(`doc/${d.id}/export/${k}`, 'Preparing ' + label.toLowerCase() + '…')]);
    const items = list.map(([label, fn]) => ({ label, fn }));
    if (!d.folder) items.push('-', { label: 'The book file', fn: () => download(`doc/${d.id}/file?download=1`, 'Downloading the book…') });
    if (isPdf(d)) items.push('-',
      { label: 'Clean text (.txt)', fn: () => download(`doc/${d.id}/export/clean-text`, 'Reading every page on the tablet…') },
      { label: 'Searchable PDF', fn: () => download(`doc/${d.id}/export/searchable-pdf`, 'Making a searchable PDF on the tablet…') });
    return items;
  };
  const copies = async () => {
    const f = await fullDoc(d);
    const bib = f.bib || {};
    const st = (bib.styles || []).find(s => s.id === bib.defaultStyle) || (bib.styles || [])[0] || {};
    return [
      st.inText ? { label: 'In-text citation', hint: plainRef(st.inText).slice(0, 34), fn: () => copyText(plainRef(st.inText), 'Citation copied') } : null,
      st.reference ? { label: 'Reference', hint: st.label, fn: () => copyText(plainRef(st.reference), 'Reference copied') } : null,
      f.citekey ? { label: 'Citation key', hint: f.citekey, fn: () => copyText(f.citekey, 'Citation key copied') } : null,
      f.overviewLink ? { label: 'Obsidian link', hint: f.overviewLink.slice(0, 34), fn: () => copyText(f.overviewLink, 'Link copied') } : null,
      '-',
      bib.bibtex ? { label: 'BibTeX', fn: () => copyText(bib.bibtex, 'BibTeX copied') } : null,
      bib.ris ? { label: 'RIS', fn: () => copyText(bib.ris, 'RIS copied') } : null,
      { label: 'Title', fn: () => copyText(f.title, 'Title copied') }
    ];
  };
  const withFull = fn => async () => { try { fn(await fullDoc(d)); } catch (e) { fail(e); } };
  const other = d.category === 'ARTICLE' ? ['BOOK', 'Books'] : ['ARTICLE', 'Articles'];
  return [
    canRead(d) ? { label: d.progress > 0 ? 'Continue reading' : 'Read', fn: () => { location.hash = `#/read/${d.id}`; } } : null,
    { label: d.folder ? 'Open the folder book' : 'Open details', fn: () => { location.hash = `#/book/${d.id}`; } },
    '-',
    { label: 'Export', sub: exportsFor() },
    { label: 'Copy', sub: copies },
    '-',
    { label: 'Edit details…', fn: withFull(f => editAll(f)) },
    d.folder ? null : { label: 'Look up details…', hint: 'DOI · ISBN · title', fn: withFull(f => editAll(f, 'lookup')) },
    d.folder ? null : { label: 'Change cover…', fn: () => changeCover(d) },
    { label: 'Tags…', fn: () => editTags(d, reload) },
    { label: `Move to ${other[1]}`, fn: async () => {
      try { await api(`doc/${d.id}`, { method: 'PATCH', json: { category: other[0] } }); toast(`Moved to ${other[1]}`); reload(); } catch (e) { fail(e); }
    } },
    d.folder ? null : { label: d.inFolder ? 'Move to another folder book…' : 'Add to a folder book…', fn: () => addToFolderBook(d) },
    { label: d.pinned ? 'Unpin from widget' : 'Pin to widget', fn: async () => {
      try { const r = await api(`doc/${d.id}/pin`, { method: 'POST', json: { pinned: !d.pinned } }); d.pinned = r.pinned; toast(r.pinned ? 'Pinned to the widget' : 'Unpinned from the widget'); reload(); } catch (e) { fail(e); }
    } },
    '-',
    { label: 'Delete…', danger: true, fn: () => confirmBox(d.folder ? 'Delete this folder book?' : 'Delete this book?',
      d.folder ? `“${d.title}” is removed. Its chapters go back on the shelf.` : `“${d.title}” and its ${d.highlights || 0} notes will be removed from the tablet.`, 'Delete', async () => {
        try { await api(`doc/${d.id}`, { method: 'DELETE' }); toast('Deleted'); if (location.hash.startsWith('#/book/')) location.hash = '#/'; else reload(); } catch (e) { fail(e); }
      }) }
  ];
}

function editTags(d, onSaved) {
  modal((box, close) => {
    const g = tagInput(d.tags || '', false);
    const save = async () => {
      try { await api(`doc/${d.id}`, { method: 'PATCH', json: { tags: g.value } }); tagCache = null; close(); toast('Tags saved'); onSaved && onSaved(); } catch (e) { fail(e); }
    };
    box.append(h('h2', null, 'Tags'), h('div', { class: 'muted', style: { marginBottom: '8px' } }, d.title),
      h('div', { class: 'tagwrap' }, g._widget),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save')));
  });
}

// ----- notes

function noteMenu(n, d, onChanged, openIt) {
  const recolor = c => async () => {
    try { const r = await api(`highlight/${n.id}`, { method: 'PATCH', json: { colorHex: c } }); Object.assign(n, r); toast('Colour changed'); onChanged && onChanged(); } catch (e) { fail(e); }
  };
  return [
    openIt ? { label: 'Show in book', fn: openIt } : null,
    { label: 'Edit…', fn: () => editNote(n, onChanged) },
    { label: 'Change colour', sub: COLORS.map(([name, hex]) => ({ label: name, color: hex, hint: (n.colorHex || '').toUpperCase() === hex ? '✓' : '', fn: recolor(hex) })) },
    '-',
    { label: 'Copy quote', fn: () => copyText(n.highlightedText, 'Quote copied') },
    { label: 'Copy as Markdown', fn: () => copyFromFile(`doc/${n.documentId}/export/note?id=${n.id}`, 'Note copied as Markdown') },
    { label: 'Copy Obsidian link', hint: `[[${(n.title || '').slice(0, 24)}…]]`, fn: () => copyText(`[[${n.title}]]`, 'Link copied') },
    { label: 'Download .md', fn: () => download(`doc/${n.documentId}/export/note?id=${n.id}`, 'Preparing the note…') },
    '-',
    { label: 'Delete…', danger: true, fn: () => confirmBox('Delete this note?', n.title, 'Delete', async () => {
      try { await api(`highlight/${n.id}`, { method: 'DELETE' }); toast('Note deleted'); onChanged && onChanged(); } catch (e) { fail(e); }
    }) }
  ];
}

// ----- reader

/** "Set as ▸" grouped: this chapter, people, title, identifiers, publication, journal, tags. */
function setAsSub(ctx, sel, onDone) {
  return async () => {
    const pv = await api(`doc/${ctx.d.id}/set-as/preview`, { method: 'POST', json: Object.assign({ text: sel.text }, sel.loc || {}) });
    const f = Object.fromEntries((pv.fields || []).map(x => [x.key, x]));
    const g = pv.guess || {}, ch = pv.chapter;
    const short = v => v ? (v.length > 30 ? v.slice(0, 30) + '…' : v) : '';
    const pick = (target, label, value, current, opts = {}) => () => setAsConfirm(ctx, sel, pv, Object.assign({ target, label, value, current }, opts), onDone);
    const field = (k, opts) => f[k] ? { label: f[k].label, hint: short(f[k].value) || '—', fn: pick(k, f[k].label, f[k].value, f[k].current, opts) } : null;
    const groups = [];
    if (pv.isBook) groups.push({ label: 'This chapter', hint: ch ? short(ch.title) : '', sub: [
      { label: 'Chapter title', hint: short(g.title), fn: pick('chapter:title', 'Chapter title', g.title, ch && ch.title) },
      { label: 'Chapter author', hint: short(g.author), fn: pick('chapter:author', 'Chapter author(s)', g.author, ch && ch.authors, { adds: ch && ch.authorsAdd }) },
      { label: 'Chapter abstract', fn: pick('chapter:abstract', 'Chapter abstract', g.abstract, ch && ch.abstract, { long: true }) },
      { label: 'Chapter tags', hint: short((pv.tags || []).join(', ')), fn: pick('chapter:tags', 'Chapter tags', (pv.tags || []).join(', '), ch && ch.tags, { tags: true }) }] });
    groups.push({ label: 'People', sub: [
      { label: pv.isBook ? 'Author of the book' : 'Author', hint: short(g.author), fn: pick('author', pv.isBook ? 'Author of the book' : 'Author', g.author, pv.bookAuthor) },
      f.EDITOR ? { label: pv.isBook ? 'Book editor' : 'Editor', hint: short(f.EDITOR.value), fn: pick('EDITOR', pv.isBook ? 'Book editor(s)' : 'Editor(s)', f.EDITOR.value, f.EDITOR.current, { adds: true }) } : null,
      field('TRANSLATOR')] });
    groups.push({ label: 'Title', sub: [field('TITLE', { long: true }), field('SUBTITLE')] });
    groups.push({ label: 'Identifiers', sub: ['DOI', 'ISBN', 'ISSN', 'URL'].map(k => field(k)) });
    groups.push({ label: 'Publication', sub: ['YEAR', 'PUBLISHER', 'PLACE', 'EDITION', 'SERIES', 'LANGUAGE'].map(k => field(k)) });
    groups.push({ label: pv.isBook ? 'Container' : 'Journal', sub: (pv.isBook ? ['BOOKTITLE', 'VOLUME', 'PAGES'] : ['JOURNAL', 'VOLUME', 'ISSUE', 'PAGES']).map(k => field(k)) });
    groups.push('-',
      { label: pv.isBook ? 'Abstract of the book' : 'Abstract', fn: pick('abstract', 'Abstract', g.abstract, pv.bookAbstract, { long: true }) },
      { label: 'Tags', hint: short((pv.tags || []).join(', ')), fn: pick('tags', 'Tags of the book', (pv.tags || []).join(', '), pv.bookTags, { tags: true }) });
    return groups;
  };
}

function selectionMenu(ctx, sel, onDone) {
  const q = sel.text.trim();
  const word = q.split(/\s+/).length <= 3;
  const enc = encodeURIComponent(q.slice(0, 300));
  const web = url => () => window.open(url, '_blank', 'noopener');
  return [
    { label: 'Highlight', sub: COLORS.map(([name, hex]) => ({ label: name, color: hex, fn: async () => {
      localSet('ml.color', hex);
      try { await sel.save({ colorHex: hex }); toast('Highlighted'); onDone(); } catch (e) { fail(e); }
    } })) },
    { label: 'Add note…', fn: () => noteDialog(ctx, sel, localGet('ml.color', '#FFEB3B'), onDone) },
    '-',
    { label: 'Copy', fn: () => copyText(sel.text, 'Copied') },
    { label: 'Copy with citation', hint: inTextFor(ctx.d, sel.pageRef).slice(0, 30), fn: () => {
      const it = inTextFor(ctx.d, sel.pageRef);
      copyText(it ? `“${q}” ${it}` : `“${q}”`, 'Quote and citation copied');
    } },
    '-',
    { label: 'Set as', sub: setAsSub(ctx, sel, onDone) },
    { label: 'Look up', sub: [
      word ? { label: 'Dictionary', hint: q.slice(0, 20), fn: web(`https://www.google.com/search?q=define+${enc}`) } : null,
      { label: 'Wikipedia', fn: web(`https://en.wikipedia.org/w/index.php?search=${enc}`) },
      { label: 'Google Scholar', fn: web(`https://scholar.google.com/scholar?q=${enc}`) },
      { label: 'Web search', fn: web(`https://www.google.com/search?q=${enc}`) },
      { label: 'Translate', fn: web(`https://translate.google.com/?sl=auto&tl=en&text=${enc}`) }] }
  ];
}

function pageMenu(ctx) {
  const ro = readerOpen;
  const p = ctx.place ? ctx.place() : {};
  const pdf = ro && ro.kind === 'pdf';
  const items = [];
  if (pdf && p.page) {
    const name = ctx.d.sourceFile || ctx.d.fileName || 'book.pdf';
    const where = p.label ? `printed page ${p.label}` : `PDF page ${p.page}`;
    items.push({ label: 'Copy Obsidian link to this page', hint: where, fn: () => copyText(`[[${name}#page=${p.page}|${name}, ${where}]]`, 'Link copied') });
  }
  items.push({ label: 'Copy web link to this place', fn: () => copyText(location.origin + '/' + (pdf ? `#/read/${ctx.d.id}?page=${p.page}` : `#/read/${ctx.d.id}?pos=${p.pos || 1}`), 'Link copied') });
  items.push({ label: pdf ? 'Go to page…' : 'Go to position…', fn: () => goToDialog(ctx, pdf) });
  items.push('-');
  items.push({ label: 'Start a chapter here…', hint: p.chapter ? 'now: ' + p.chapter.slice(0, 22) : '', fn: () => startChapterDialog(ctx, p) });
  if (pdf) items.push({ label: 'Text & OCR for this page…', fn: () => { const b = document.querySelector('.rbar button[title^="Text & OCR"]'); if (b) setTimeout(() => b.click(), 0); else toast('Text tools are not available for this PDF.'); } });
  items.push('-');
  if (!pdf) items.push({ label: 'View', sub: RS_VIEWS.map(([id, label, , patch]) => ({ label, hint: rsView() === id ? '✓' : '', fn: () => setReaderSettings(patch) })) });
  items.push({ label: 'Reader settings', fn: () => { const b = document.querySelector('.rbar button[title^="Reading settings"]'); if (b) setTimeout(() => b.click(), 0); } });
  items.push({ label: 'Contents', fn: () => { const b = [...document.querySelectorAll('.rbar button')].find(x => x.textContent === 'Contents'); if (b) b.click(); } });
  return items;
}

function goToDialog(ctx, pdf) {
  modal((box, close) => {
    const inp = h('input', { type: 'number', min: 1, max: ctx.pageCount || '', placeholder: pdf ? 'PDF page' : 'Position' });
    const go = () => { const n = +inp.value; if (!n) return; close(); if (ctx.goToPage) ctx.goToPage(n); };
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
    box.append(h('h2', null, pdf ? 'Go to page' : 'Go to position'),
      h('label', { class: 'fields' }, h('span', { class: 'label' }, pdf ? `PDF page (1–${ctx.pageCount || '?'})` : `Position (1–${ctx.pageCount || '?'})`), inp),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: go }, 'Go')));
  });
}

function startChapterDialog(ctx, p) {
  modal((box, close) => {
    const inp = h('input', { placeholder: 'e.g. 3 The Rule of Law' });
    const save = async () => {
      const value = inp.value.trim(); if (!value) { inp.focus(); return; }
      try {
        const r = await api(`doc/${ctx.d.id}/set-as`, { method: 'POST', json: Object.assign({ target: 'chapter:new', value, text: value }, p.loc || {}) });
        close(); toast(r.message || 'Saved');
      } catch (e) { fail(e); }
    };
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') save(); });
    box.append(h('h2', null, 'Start a chapter here'),
      h('p', { class: 'muted', style: { marginTop: 0 } }, `The chapter begins ${p.page ? 'on PDF page ' + p.page : 'at this position'}. It is cited as a book section, with its own author and abstract.`),
      h('label', { class: 'fields' }, h('span', { class: 'label' }, 'Chapter title (a number in front is kept as its label)'), inp),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save')));
  });
}

/** Right-click inside the book: the selection's menu when the click is on selected text, otherwise the page's. */
function readerContext(ctx, e, inSelection) {
  if (inSelection && ctx.sel) return selectionMenu(ctx, ctx.sel, ctx.selDone);
  return pageMenu(ctx);
}

// ------------------------------------------------------------------ settings

async function showSettings() {
  const app = clear(document.getElementById('app'));
  app.appendChild(header());
  const main = h('main', { class: 'settings' }, h('div', { class: 'loading', style: { position: 'static', height: '200px' } }, h('div', { class: 'spin' })));
  app.appendChild(main);
  let data;
  try { data = await api('settings'); } catch (e) { fail(e); return; }
  renderSettings(main, data);
}

function settingsSlug(name) { return 'set-' + name.toLowerCase().replace(/[^a-z0-9]+/g, '-'); }

function renderSettings(main, data) {
  const y = window.scrollY;
  clear(main);
  const save = async patch => {
    try { const d = await api('settings', { method: 'PUT', json: patch }); toast('Saved on the tablet', 1400); return d; }
    catch (e) { fail(e); return null; }
  };
  const nav = h('nav', { class: 'set-nav' }, data.groups.map(g =>
    h('a', { href: '#', onclick: e => { e.preventDefault(); document.getElementById(settingsSlug(g.name)).scrollIntoView({ behavior: 'smooth', block: 'start' }); } }, g.name)),
    h('a', { href: '#', onclick: e => { e.preventDefault(); document.getElementById('set-storage').scrollIntoView({ behavior: 'smooth', block: 'start' }); } }, 'Storage & backup'));
  const body = h('div', { class: 'set-body' });
  data.groups.forEach(g => {
    const sec = h('section', { class: 'set-group', id: settingsSlug(g.name) }, h('h2', null, g.name));
    if (g.name === 'AI') sec.appendChild(aiKeys(data, main));
    g.fields.forEach(f => sec.appendChild(settingRow(f, data, main, save)));
    body.appendChild(sec);
  });
  body.appendChild(storageSection(data.info));
  main.append(h('div', { class: 'set-head' }, h('h1', null, 'Settings'),
    h('p', { class: 'muted' }, 'The same settings as on the tablet. Changes are saved there straight away.')),
    h('div', { class: 'set-layout' }, nav, body));
  window.scrollTo(0, y);
}

function settingRow(f, data, main, save) {
  const after = d => { if (d) { data.groups = d.groups; data.keys = d.keys; } };
  let control;
  if (f.type === 'bool') {
    const cb = h('input', { type: 'checkbox' }); cb.checked = !!f.value;
    cb.addEventListener('change', async () => { const d = await save({ [f.key]: cb.checked }); if (d) renderSettings(main, d); });
    control = h('label', { class: 'set-switch' }, cb, h('span', null, f.value ? 'On' : 'Off'));
    cb.addEventListener('change', () => { control.querySelector('span').textContent = cb.checked ? 'On' : 'Off'; });
  } else if (f.type === 'choice') {
    control = f.options.length <= 4
      ? h('div', { class: 'row' }, f.options.map(o => h('button', {
          class: 'chip' + (String(f.value) === o.value ? ' on' : ''),
          onclick: async () => { const d = await save({ [f.key]: o.value }); if (d) renderSettings(main, d); }
        }, o.label)))
      : (() => {
          const sel = h('select', null, f.options.map(o => { const op = h('option', { value: o.value }, o.label); if (String(f.value) === o.value) op.selected = true; return op; }));
          sel.addEventListener('change', async () => { const d = await save({ [f.key]: sel.value }); if (d) renderSettings(main, d); });
          return sel;
        })();
  } else if (f.type === 'number') {
    const inp = h('input', { type: 'number', min: f.min, max: f.max, step: f.step, value: f.value, class: 'set-num' });
    inp.addEventListener('change', async () => { const d = await save({ [f.key]: Number(inp.value) }); after(d); if (d) { const nf = d.groups.flatMap(g => g.fields).find(x => x.key === f.key); if (nf) inp.value = nf.value; } });
    control = h('div', { class: 'row' }, inp, h('span', { class: 'muted mono', style: { fontSize: '11px' } }, `${f.min}–${f.max}`));
  } else {
    const inp = f.type === 'longtext' ? h('textarea', { rows: 3 }) : h('input', { type: 'text' });
    inp.value = f.value == null ? '' : f.value;
    let last = inp.value;
    inp.addEventListener('change', async () => { if (inp.value === last) return; const d = await save({ [f.key]: inp.value }); if (d) { last = inp.value; after(d); } });
    control = inp;
  }
  return h('div', { class: 'set-row' + (f.type === 'longtext' ? ' wide' : '') },
    h('div', { class: 'set-label' }, h('div', null, f.label), f.help ? h('div', { class: 'muted set-help' }, f.help) : null),
    h('div', { class: 'set-control' }, control));
}

function aiKeys(data, main) {
  const box = h('div', { class: 'set-keys' },
    h('div', { class: 'set-label' }, h('div', null, 'API keys'),
      h('div', { class: 'muted set-help' }, 'Stored encrypted on the tablet and never included in backups. Only the last four characters are shown here.')));
  data.keys.forEach(k => {
    const inp = h('input', { type: 'password', placeholder: k.saved ? 'Paste a new key to replace it' : 'Paste key', autocomplete: 'off', spellcheck: 'false' });
    const send = async key => {
      try { const d = await api('settings/ai-key', { method: 'POST', json: { provider: k.provider, key } }); toast(key ? 'Key saved on the tablet' : 'Key removed', 1600); renderSettings(main, d); }
      catch (e) { fail(e); }
    };
    inp.addEventListener('keydown', e => { if (e.key === 'Enter' && inp.value.trim()) send(inp.value.trim()); });
    box.appendChild(h('div', { class: 'set-key' },
      h('div', { class: 'set-key-name' }, k.label,
        h('div', { class: 'mono set-key-state' + (k.saved ? ' ok' : '') }, k.saved ? `Saved  ••••${k.last4 || ''}` : 'No key')),
      inp,
      h('button', { class: 'btn fill', onclick: () => { if (inp.value.trim()) send(inp.value.trim()); else toast('Paste a key first'); } }, 'Save'),
      k.saved ? h('button', { class: 'btn', onclick: () => confirmBox('Remove this key?', `The ${k.label} key is deleted from the tablet. AI features using ${k.label} stop working until you add a key again.`, 'Remove', () => send('')) }, 'Remove') : null));
  });
  return box;
}

function storageSection(info) {
  const when = ms => ms ? new Date(ms).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : 'Never';
  return h('section', { class: 'set-group', id: 'set-storage' }, h('h2', null, 'Storage & backup'),
    h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('div', null, 'Library folder'), h('div', { class: 'muted set-help' }, 'Change it on the tablet, where the folder can be checked.')),
      h('div', { class: 'set-control mono', style: { fontSize: '12px', wordBreak: 'break-all' } }, info.libraryFolder || '—')),
    h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('div', null, 'Book folders last checked'), h('div', { class: 'muted set-help' }, 'Edits made to the notes and .ris/.bib files outside the app are read in.')),
      h('div', { class: 'set-control row' }, h('span', { class: 'mono', style: { fontSize: '12px' } }, when(info.lastFolderCheck)),
        h('button', { class: 'btn', onclick: async () => { try { await api('settings/check-folders', { method: 'POST' }); toast('Checking the folders…'); setTimeout(showSettings, 2500); } catch (e) { fail(e); } } }, 'Check now'))),
    h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('div', null, 'Backup'), h('div', { class: 'muted set-help' }, 'Backups are set up on the tablet, which chooses where they are saved.')),
      h('div', { class: 'set-control mono', style: { fontSize: '12px' } }, `Last backup: ${when(info.lastBackup)} · Automatic: ${info.autoBackup ? 'on' : 'off'}`)));
}

// ------------------------------------------------------------------ library

async function loadLibrary() {
  const data = await api('library');
  state.device = data.device || state.device;
  state.docs = data.documents || [];
  return state.docs;
}

async function showLibrary() {
  const app = clear(document.getElementById('app'));
  app.appendChild(header());
  const main = h('main', { id: 'main' }, h('div', { class: 'loading', style: { position: 'static', height: '200px' } }, h('div', { class: 'spin' })));
  app.appendChild(main);
  try { await loadLibrary(); } catch (e) { fail(e); return; }
  clear(main);
  main.append(h('div', { class: 'toolbar', id: 'toolbar' }), h('div', { class: 'tagbar', id: 'tagbar' }), h('div', { id: 'shelf' }));
  renderShelf();
}

function filteredDocs() {
  let docs = state.docs.slice();
  if (state.tab === 'books') docs = docs.filter(d => d.category !== 'ARTICLE');
  if (state.tab === 'articles') docs = docs.filter(d => d.category === 'ARTICLE');
  if (state.tag) docs = docs.filter(d => splitTags(d.tags).some(t => t.toLowerCase() === state.tag.toLowerCase()));
  const q = norm(state.query.trim().replace(/^#/, ''));
  if (!q) docs = docs.filter(d => !d.inFolder);
  if (q) {
    const terms = q.split(/\s+/);
    docs = docs.filter(d => { const hay = norm([d.title, d.displayAuthor, d.author, d.tags, d.fileName].join(' ')); return terms.every(t => hay.includes(t)); });
  }
  const by = {
    recent: (a, b) => b.lastRead - a.lastRead,
    added: (a, b) => b.dateAdded - a.dateAdded,
    title: (a, b) => a.title.localeCompare(b.title),
    author: (a, b) => (a.displayAuthor || '').localeCompare(b.displayAuthor || '') || a.title.localeCompare(b.title),
    progress: (a, b) => b.progress - a.progress,
    notes: (a, b) => b.highlights - a.highlights,
  }[state.sort] || ((a, b) => b.lastRead - a.lastRead);
  return docs.sort(by);
}

function coverUrl(d) { return `/api/doc/${d.id}/cover?v=${d.coverVersion || 0}`; }

function coverImg(d, cls) {
  const img = h('img', { src: coverUrl(d), alt: '', loading: 'lazy', class: cls || null, draggable: 'false' });
  img.addEventListener('error', () => {
    const fb = h('div', { class: 'fallback' + (cls ? ' ' + cls : '') }, d.title);
    img.replaceWith(fb);
  });
  return img;
}

/** A shelf cover in its own proportions (tall or wide), standing on the shelf line like in the app. */
function coverFrame(d, ...extras) {
  const frame = h('div', { class: 'frame' });
  const setRatio = r => {
    if (!(r > 0.2 && r < 3)) r = 2 / 3;
    frame.style.setProperty('--r', r.toFixed(4));
    frame.classList.toggle('tall', r < 2 / 3);
  };
  setRatio(d.coverRatio || 2 / 3);
  const img = h('img', { src: coverUrl(d), alt: '', loading: 'lazy', draggable: 'false' });
  img.addEventListener('load', () => { if (img.naturalWidth && img.naturalHeight) setRatio(img.naturalWidth / img.naturalHeight); });
  img.addEventListener('error', () => { img.replaceWith(h('div', { class: 'fallback' }, d.title)); setRatio(2 / 3); });
  frame.append(img, ...extras.filter(Boolean));
  return h('div', { class: 'cover' }, frame);
}

/** Dragging a book out of the page saves its file (Chrome and Edge). */
function makeDraggable(el, d) {
  el.setAttribute('draggable', 'true');
  el.addEventListener('dragstart', e => {
    const url = `${location.origin}/api/doc/${d.id}/file?download=1`;
    const mime = isPdf(d) ? 'application/pdf' : isEpub(d) ? 'application/epub+zip' : 'application/octet-stream';
    try { e.dataTransfer.setData('DownloadURL', `${mime}:${d.fileName || d.title}:${url}`); } catch (_) {}
    e.dataTransfer.setData('text/uri-list', url);
    e.dataTransfer.effectAllowed = 'copy';
    dragOut = true;
  });
  el.addEventListener('dragend', () => { dragOut = false; });
}

// ------------------------------------------------------------------ cover

const COVER_MAX = 1600;
/** While the cover dialog is open: hands it an image dropped or pasted anywhere on the page. */
let coverTake = null;

function isImageFile(f) { return !!f && (/^image\//.test(f.type || '') || /\.(jpe?g|png|webp|gif|bmp|avif|heic)$/i.test(f.name || '')); }

function coverButton(d) {
  return h('button', { class: 'btn quiet covbtn', type: 'button', onclick: () => changeCover(d) }, 'Change cover…');
}

/** Any image (file, clipboard image or a drawn PDF page) as a JPEG of at most COVER_MAX px on its long side. */
async function coverBlob(src) {
  let pic = src;
  if (src instanceof Blob) {
    try { pic = await createImageBitmap(src, { imageOrientation: 'from-image' }); }
    catch (_) {
      pic = await new Promise((res, rej) => {
        const i = new Image();
        i.onload = () => res(i);
        i.onerror = () => rej(new Error('The browser cannot read this image. Try a JPEG or PNG.'));
        i.src = URL.createObjectURL(src);
      });
    }
  }
  const w = pic.naturalWidth || pic.width, ht = pic.naturalHeight || pic.height;
  if (!w || !ht) throw new Error('This image is empty.');
  const k = Math.min(1, COVER_MAX / Math.max(w, ht));
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w * k)); c.height = Math.max(1, Math.round(ht * k));
  const g = c.getContext('2d');
  g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
  g.drawImage(pic, 0, 0, c.width, c.height);
  return new Promise((res, rej) => c.toBlob(b => b ? res(b) : rej(new Error('Could not prepare the image.')), 'image/jpeg', 0.9));
}

async function sendCover(id, blob) {
  let r;
  try {
    r = await fetch(`/api/doc/${id}/cover`, { method: 'POST', credentials: 'same-origin', headers: { 'X-Marginalia': '1', 'Content-Type': 'image/jpeg' }, body: blob });
  } catch (_) { throw new ApiError(0, 'The tablet is not answering. Is the app\'s server still switched on?'); }
  if (r.status === 401) { showLogin(); throw new ApiError(401, 'Please sign in.'); }
  const text = await r.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (_) { data = { error: text }; }
  if (!r.ok) throw new ApiError(r.status, data.error || ('Error ' + r.status));
  return data;
}

function clipboardImage(e) {
  const items = [...((e.clipboardData && e.clipboardData.items) || [])];
  const it = items.find(i => i.kind === 'file' && /^image\//.test(i.type));
  return it ? it.getAsFile() : null;
}

/** Ctrl+V with an image on a book's page opens the cover dialog with it. */
document.addEventListener('paste', e => {
  const img = clipboardImage(e);
  if (!img) return;
  if (coverTake) { e.preventDefault(); coverTake(img); return; }
  const t = e.target;
  if (!state.currentDoc || readerOpen || document.querySelector('.scrim')) return;
  if (t && (t.closest && t.closest('input, textarea, [contenteditable]'))) return;
  e.preventDefault();
  changeCover(state.currentDoc, img);
});

/**
 * The cover dialog: drop, paste or choose an image, use a page of the PDF, or go back to the
 * original. Nothing changes on the tablet until Save (or Reset) is pressed.
 */
function changeCover(d, initial) {
  if (coverTake) return;
  const folder = !!d.folder;
  const sources = isPdf(d) ? [d] : (d.parts || []).filter(isPdf);
  let chosen = null, chosenUrl = null, busy = false;
  let pdf = null, pdfFor = null, pageNo = 1, renderSeq = 0;

  modal((box, close) => {
    const prev = h('img', { class: 'coverprev', src: coverUrl(d), alt: '' });
    prev.addEventListener('error', () => { prev.removeAttribute('src'); prev.alt = 'No cover yet'; });
    const status = h('div', { class: 'muted', style: { fontSize: '13px', minHeight: '18px' } }, 'Current cover');
    const save = h('button', { class: 'btn fill', disabled: true }, 'Save cover');

    const show = (blob, label) => {
      chosen = blob;
      if (chosenUrl) URL.revokeObjectURL(chosenUrl);
      chosenUrl = URL.createObjectURL(blob);
      prev.src = chosenUrl;
      status.textContent = `${label} · ${Math.round(blob.size / 1024)} KB — press Save cover to use it`;
      save.disabled = false;
    };
    const take = async (file, label) => {
      try { status.textContent = 'Preparing the image…'; show(await coverBlob(file), label || file.name || 'Pasted image'); }
      catch (e) { status.textContent = ''; fail(e); }
    };
    coverTake = f => take(f);

    // Drop zone: drop, paste or choose a file.
    const file = h('input', { type: 'file', accept: 'image/*', hidden: true });
    file.addEventListener('change', () => { if (file.files[0]) take(file.files[0]); file.value = ''; });
    const zone = h('div', { class: 'coverdrop', tabindex: '0' },
      h('div', null, h('b', null, 'Drop an image here'), ' or paste one with Ctrl+V (⌘V)'),
      h('button', { class: 'btn', type: 'button', onclick: () => file.click() }, 'Choose image…'), file);
    zone.addEventListener('dragover', e => { e.preventDefault(); zone.classList.add('over'); });
    zone.addEventListener('dragleave', () => zone.classList.remove('over'));
    zone.addEventListener('drop', e => {
      e.preventDefault(); e.stopPropagation(); zone.classList.remove('over');
      const f = [...e.dataTransfer.files].find(isImageFile);
      if (f) take(f); else toast('Drop an image file (JPEG, PNG, WebP…)');
    });

    // A page of the PDF (for a folder book: of one of its chapters).
    let pdfPanel = null;
    if (sources.length) {
      const pick = sources.length > 1
        ? h('select', null, sources.map((p, i) => h('option', { value: i }, `${p.number ? p.number + '. ' : ''}${p.title}`)))
        : null;
      const pageIn = h('input', { type: 'number', min: 1, value: 1, style: { width: '76px' } });
      const of = h('span', { class: 'muted' }, '');
      const usePage = async () => {
        const src = sources[pick ? +pick.value : 0];
        const seq = ++renderSeq;
        try {
          status.textContent = 'Opening the PDF…';
          if (pdfFor !== src.id) {
            if (pdf) { pdf.destroy(); pdf = null; }
            pdfjsLib.GlobalWorkerOptions.workerSrc = 'lib/pdf.worker.min.js';
            pdf = await pdfjsLib.getDocument({ url: `/api/doc/${src.id}/file`, withCredentials: true, rangeChunkSize: 65536, disableAutoFetch: true, disableStream: true }).promise;
            pdfFor = src.id;
          }
          pageNo = Math.min(Math.max(1, parseInt(pageIn.value, 10) || 1), pdf.numPages);
          pageIn.value = pageNo; pageIn.max = pdf.numPages; of.textContent = `of ${pdf.numPages}`;
          status.textContent = `Drawing page ${pageNo}…`;
          const page = await pdf.getPage(pageNo);
          const base = page.getViewport({ scale: 1 });
          const vp = page.getViewport({ scale: Math.min(4, COVER_MAX / Math.max(base.width, base.height)) });
          const c = document.createElement('canvas');
          c.width = Math.round(vp.width); c.height = Math.round(vp.height);
          const g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
          await page.render({ canvasContext: g, viewport: vp }).promise;
          if (seq !== renderSeq) return;
          show(await coverBlob(c), `Page ${pageNo}${pick ? ' of ' + src.title : ''}`);
        } catch (e) { if (seq === renderSeq) { status.textContent = ''; fail(e); } }
      };
      const step = k => { pageIn.value = Math.max(1, (parseInt(pageIn.value, 10) || 1) + k); usePage(); };
      pageIn.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); usePage(); } });
      if (pick) pick.addEventListener('change', () => { pageIn.value = 1; usePage(); });
      pdfPanel = h('div', { class: 'coverpdf' },
        h('div', { class: 'label' }, folder ? 'Or a page of a chapter' : 'Or a page of the PDF'),
        pick,
        h('div', { class: 'row', style: { alignItems: 'center' } },
          h('button', { class: 'btn', type: 'button', title: 'Previous page', onclick: () => step(-1) }, '‹'),
          h('span', null, 'Page'), pageIn, of,
          h('button', { class: 'btn', type: 'button', title: 'Next page', onclick: () => step(1) }, '›'),
          h('button', { class: 'btn', type: 'button', onclick: usePage }, 'Use this page')));
    }

    const finish = async (work, done) => {
      if (busy) return;
      busy = true; save.disabled = true; status.textContent = 'Saving on the tablet…';
      try { await work(); close(); toast(done); tagCache = null; route(); }
      catch (e) { status.textContent = ''; fail(e); busy = false; save.disabled = !chosen; }
    };
    save.addEventListener('click', () => { if (chosen) finish(() => sendCover(d.id, chosen), 'Cover changed'); });
    const reset = h('button', { class: 'btn quiet', type: 'button', onclick: () => {
      if (reset.dataset.sure !== '1') { reset.dataset.sure = '1'; reset.textContent = folder ? 'Press again: plain title cover' : 'Press again to reset'; return; }
      finish(() => api(`doc/${d.id}/cover`, { method: 'DELETE', timeout: 120000 }), 'Cover reset');
    } }, folder ? 'Reset to a plain title cover' : (isPdf(d) ? 'Reset to the first page' : 'Reset to the original'));

    box.classList.add('covermodal');
    box.append(
      h('h2', null, 'Change cover'),
      h('div', { class: 'coverwrap' },
        h('div', { class: 'coverside' }, prev),
        h('div', { class: 'covermain' }, zone, pdfPanel, status)),
      h('div', { class: 'foot' }, reset, h('span', { style: { flex: '1' } }), h('button', { class: 'btn', onclick: close }, 'Cancel'), save));
    if (initial) take(initial);
  }, () => {
    coverTake = null;
    renderSeq++;
    if (pdf) { try { pdf.destroy(); } catch (_) {} pdf = null; }
    if (chosenUrl) URL.revokeObjectURL(chosenUrl);
  });
}

function renderShelf() {
  const shelf = document.getElementById('shelf'); if (!shelf) return;
  const toolbar = document.getElementById('toolbar'), tagbar = document.getElementById('tagbar');
  const docs = filteredDocs();

  clear(toolbar).append(
    h('div', { class: 'label' }, `${docs.length} of ${state.docs.filter(d => !d.inFolder).length}`),
    h('button', { class: 'btn', onclick: () => newFolderBook() , title: 'One book made of several files, one per chapter' }, '+ New folder book'),
    h('div', { class: 'spacer' }),
    h('span', { class: 'label' }, 'Sort'),
    (() => {
      const s = h('select', { style: { width: 'auto' } },
        [['recent', 'Last read'], ['added', 'Date added'], ['title', 'Title'], ['author', 'Author'], ['progress', 'Progress'], ['notes', 'Most notes']]
          .map(([v, l]) => h('option', { value: v, selected: state.sort === v }, l)));
      s.addEventListener('change', () => { state.sort = s.value; localSet('ml.sort', s.value); renderShelf(); });
      return s;
    })(),
    h('button', { class: 'chip' + (state.view === 'grid' ? ' on' : ''), onclick: () => { state.view = 'grid'; localSet('ml.view', 'grid'); renderShelf(); } }, 'Covers'),
    h('button', { class: 'chip' + (state.view === 'list' ? ' on' : ''), onclick: () => { state.view = 'list'; localSet('ml.view', 'list'); renderShelf(); } }, 'List')
  );

  const counts = {};
  state.docs.forEach(d => splitTags(d.tags).forEach(t => { const k = t.toLowerCase(); counts[k] = counts[k] || [t, 0]; counts[k][1]++; }));
  const tags = Object.values(counts).sort((a, b) => b[1] - a[1]).slice(0, 40);
  clear(tagbar);
  if (tags.length) {
    tagbar.append(h('button', { class: 'chip' + (state.tag ? '' : ' on'), onclick: () => { state.tag = ''; renderShelf(); } }, 'All tags'));
    tags.forEach(([t, n]) => tagbar.append(h('button', { class: 'chip' + (state.tag.toLowerCase() === t.toLowerCase() ? ' on' : ''), onclick: () => { state.tag = t; renderShelf(); } }, `#${t} ${n}`)));
  }

  clear(shelf);
  if (!state.docs.length) {
    shelf.append(h('div', { class: 'empty' }, h('h2', null, 'The library is empty'), h('div', { class: 'muted' }, 'Drag PDF or EPUB files onto this page, or use “+ Add files”.')));
    return;
  }
  if (!docs.length) { shelf.append(h('div', { class: 'empty' }, h('h2', null, 'Nothing matches'), h('div', { class: 'muted' }, 'Try another search or tag.'))); return; }

  if (state.view === 'list') {
    const list = h('div', { class: 'list' });
    docs.forEach(d => {
      const row = h('div', { class: 'li', onclick: () => { location.hash = `#/book/${d.id}`; } },
        coverImg(d, 'ph'),
        h('div', { style: { minWidth: 0 } }, h('div', { class: 't' }, d.title), h('div', { class: 'muted', style: { fontSize: '13px' } }, d.displayAuthor || d.author)),
        h('div', { class: 'mono muted', style: { fontSize: '11px' } }, splitTags(d.tags).slice(0, 3).map(t => '#' + t).join(' ')),
        h('div', { class: 'mono', style: { fontSize: '12px' } }, `${pct(d.progress)}%`),
        h('div', { class: 'mono muted', style: { fontSize: '11px' } }, `${d.fileType} · ${d.highlights} notes`));
      makeDraggable(row, d);
      onContext(row, () => docMenu(d, () => showLibrary()));
      list.append(row);
    });
    shelf.append(list);
    return;
  }
  const grid = h('div', { class: 'grid' });
  docs.forEach(d => {
    const card = h('div', { class: 'card', onclick: () => { location.hash = `#/book/${d.id}`; }, title: 'Open · drag to your desktop to save the file' },
      coverFrame(d, h('span', { class: 'badge' }, d.folder ? `FOLDER · ${d.partCount || 0}` : d.fileType), d.pinned ? h('span', { class: 'pin', title: 'Pinned to the widget' }, '◆') : null),
      h('div', { class: 'bar' }, h('i', { style: { width: pct(d.progress) + '%' } })),
      h('div', { class: 't' }, d.title),
      h('div', { class: 'a' }, d.displayAuthor || d.author || ' '),
      h('div', { class: 'm' }, `${pct(d.progress)}% · ${d.highlights} note${d.highlights === 1 ? '' : 's'}`));
    makeDraggable(card, d);
    onContext(card, () => docMenu(d, () => showLibrary()));
    grid.append(card);
  });
  shelf.append(grid);
}

// ------------------------------------------------------------------ book page

const EXPORTS = [
  ['bundle', 'Complete bundle (.zip)', 'Book file, every note, Notes on …, highlight.md, cover'],
  ['moc', 'Notes overview (.md)', 'The “Notes on …” map of content'],
  ['notes', 'All notes in one file (.md)', 'Obsidian note with every highlight'],
  ['vault', 'Obsidian vault (.zip)', 'One file per note plus the overview'],
  ['bib', 'BibTeX (.bib)', 'The bibliographic record'],
  ['ris', 'RIS (.ris)', 'For Zotero, EndNote, Mendeley'],
];

async function showBook(id) {
  const app = clear(document.getElementById('app'));
  state.tab = '';
  app.appendChild(header());
  const main = h('main', null, h('div', { class: 'loading', style: { position: 'static', height: '200px' } }, h('div', { class: 'spin' })));
  app.appendChild(main);
  let d;
  try { d = await api(`doc/${id}`); } catch (e) { fail(e); clear(main).append(h('div', { class: 'empty' }, h('h2', null, 'Not found'), h('a', { href: '#/' }, 'Back to the library'))); return; }
  clear(main);
  renderBook(main, d);
}

function renderBook(main, d) {
  state.currentDoc = d;
  if (d.folder) return renderFolder(main, d);
  const exportMenu = menuButton('Export ▾', EXPORTS.map(([k, label, sub]) => [label, sub, () => download(`doc/${d.id}/export/${k}`, 'Preparing ' + label.toLowerCase() + '…')]));
  const tags = splitTags(d.tags);
  const left = h('div', null,
    h('div', { style: { cursor: canRead(d) ? 'pointer' : 'default' }, onclick: () => { if (canRead(d)) location.hash = `#/read/${d.id}`; } },
      (() => { const img = coverImg(d, 'bc'); makeDraggable(img, d); onContext(img, () => docMenu(d, () => showBook(d.id))); return img; })()),
    coverButton(d),
    h('div', { class: 'facts', style: { flexDirection: 'column', gap: '4px' } },
      h('span', null, `${d.fileType} · ${fmtSize(d.size)}`),
      h('span', null, `${d.category === 'ARTICLE' ? 'Article' : 'Book'} · ${d.totalPages} ${isEpub(d) ? 'positions' : 'pages'}`),
      h('span', null, `Added ${fmtDate(d.dateAdded)}`),
      h('span', null, `Last read ${fmtDate(d.lastRead)}`)),
    bibFacts(d));

  const right = h('div', null,
    h('div', { class: 'label' }, d.category === 'ARTICLE' ? 'Article' : 'Book', d.editedVolume ? ' · Edited volume' : '',
      d.folderNav ? h('span', null, ' · Chapter ', d.folderNav.number, ' of ', h('a', { href: `#/book/${d.folderNav.id}` }, d.folderNav.title)) : null),
    h('h1', null, d.title),
    h('div', { class: 'by' }, d.displayAuthor || d.author || 'Unknown author'),
    tags.length ? h('div', { class: 'tags' }, tags.map(t => h('span', { class: 'tag' }, '#' + t))) : null,
    h('div', { class: 'facts' },
      h('span', null, `${pct(d.progress)}% read`),
      d.chapter ? h('span', null, d.chapter) : null,
      h('span', null, `${d.highlights} note${d.highlights === 1 ? '' : 's'}`)),
    h('div', { class: 'bar', style: { height: '4px', background: 'var(--rule)', marginBottom: '16px' } }, h('i', { style: { display: 'block', height: '100%', background: '#000', width: pct(d.progress) + '%' } })),
    h('div', { class: 'row' },
      canRead(d) ? h('button', { class: 'btn fill', onclick: () => { location.hash = `#/read/${d.id}`; } }, d.progress > 0 ? 'Continue reading' : 'Read') : null,
      h('button', { class: 'btn', onclick: () => download(`doc/${d.id}/file?download=1`, 'Downloading the book…') }, 'Download file'),
      exportMenu,
      h('button', { class: 'btn', onclick: () => editAll(d) }, 'Edit details'),
      h('button', { class: 'btn', onclick: () => addToFolderBook(d) }, d.folderNav ? 'Move to another folder book…' : 'Add to a folder book…'),
      h('button', { class: 'btn', onclick: async () => {
        try { const r = await api(`doc/${d.id}/pin`, { method: 'POST', json: { pinned: !d.pinned } }); d.pinned = r.pinned; toast(r.pinned ? 'Pinned to the widget' : 'Unpinned from the widget'); renderBook(clear(main), d); } catch (e) { fail(e); }
      } }, d.pinned ? 'Unpin from widget' : 'Pin to widget'),
      h('button', { class: 'btn quiet', onclick: () => confirmBox('Delete this book?', `“${d.title}” and its ${d.highlights} notes will be removed from the tablet.`, 'Delete', async () => {
        try { await api(`doc/${d.id}`, { method: 'DELETE' }); toast('Deleted'); location.hash = '#/'; } catch (e) { fail(e); }
      }) }, 'Delete')),
    d.summary ? h('div', { class: 'section' }, h('h2', null, 'Abstract'), h('div', { class: 'summary' }, d.summary)) : null,
    d.chapters ? chaptersSection(d) : null,
    notesSection(d, main),
    bibSection(d, main));

  main.append(h('div', { class: 'book' }, left, right));
}

// ------------------------------------------------------------------ folder books

let tagCache = null;
async function allTags() {
  if (!tagCache) tagCache = api('tags').catch(() => ({ documents: [], notes: [] }));
  return tagCache;
}

/**
 * A tag field: each tag is a chip (hover shows ×), new tags are typed in the small box and added
 * with Enter or a comma, Backspace in the empty box removes the last one. Below it, the tags used
 * before can be clicked to add. Returns a hidden input whose value is “a, b, c” (so forms read
 * it like a text field) with the widget in ._widget.
 */
function tagInput(initial, forNotes) {
  const hidden = h('input', { type: 'hidden' });
  let tags = [];
  const norm = t => t.trim().replace(/^#+/, '').replace(/\s+/g, ' ');
  const has = t => tags.some(x => x.toLowerCase() === t.toLowerCase());
  const chips = h('div', { class: 'tagchips' });
  const typed = h('input', { class: 'tagtype', placeholder: 'Add a tag and press Enter', 'aria-label': 'Add a tag' });
  const field = h('div', { class: 'tagfield', onclick: e => { if (e.target === field || e.target === chips) typed.focus(); } }, chips, typed);
  const suggestions = h('div', { class: 'tagpick' });
  let all = [], expanded = false;

  const sync = () => {};   // the value is read live from the chips (see the getter below)
  const drawChips = () => {
    clear(chips);
    tags.forEach((t, i) => chips.append(h('span', { class: 'tagchip' }, '#' + t,
      h('button', { type: 'button', class: 'x', title: 'Remove ' + t, 'aria-label': 'Remove ' + t, onclick: e => { e.stopPropagation(); tags.splice(i, 1); sync(); drawChips(); drawSuggestions(); } }, '×'))));
  };
  const add = raw => {
    let changed = false;
    raw.split(/[,;]/).map(norm).filter(Boolean).forEach(t => { if (!has(t)) { tags.push(t); changed = true; } });
    if (changed) { sync(); drawChips(); }
    drawSuggestions();
  };
  const drawSuggestions = () => {
    clear(suggestions);
    const partial = norm(typed.value).toLowerCase();
    const list = all.filter(t => !has(t) && (!partial || t.toLowerCase().includes(partial)));
    if (!list.length) return;
    const shown = expanded || partial ? list.slice(0, 80) : list.slice(0, 16);
    suggestions.append(h('span', { class: 'label' }, partial ? 'Matching tags — click to add' : 'Your tags — click to add'));
    shown.forEach(t => suggestions.append(h('button', { class: 'chip', type: 'button', onmousedown: e => e.preventDefault(), onclick: () => { typed.value = ''; add(t); typed.focus(); } }, '#' + t)));
    if (!expanded && !partial && list.length > shown.length) suggestions.append(h('button', { class: 'btn quiet', type: 'button', style: { padding: '2px 6px' }, onclick: () => { expanded = true; drawSuggestions(); } }, `All ${list.length}…`));
  };

  typed.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ',' || e.key === ';' || (e.key === 'Tab' && typed.value.trim())) {
      if (typed.value.trim()) { e.preventDefault(); add(typed.value); typed.value = ''; }
      else if (e.key === 'Enter') e.preventDefault();
    } else if (e.key === 'Backspace' && !typed.value && tags.length) {
      tags.pop(); sync(); drawChips(); drawSuggestions();
    }
  });
  typed.addEventListener('input', () => {
    if (/[,;]/.test(typed.value)) { add(typed.value); typed.value = ''; }
    drawSuggestions();
  });
  typed.addEventListener('paste', e => {
    const text = (e.clipboardData || window.clipboardData).getData('text');
    if (/[,;\n]/.test(text)) { e.preventDefault(); add(text.replace(/\n/g, ',')); }
  });
  // A tag still being typed when the form is saved is kept.
  typed.addEventListener('blur', () => { if (typed.value.trim()) { add(typed.value); typed.value = ''; } });
  Object.defineProperty(hidden, 'value', {
    get() { const pending = norm(typed.value); return (pending && !has(pending) ? [...tags, pending] : tags).join(', '); },
    set(v) { tags = []; (v || '').split(/[,;]/).map(norm).filter(Boolean).forEach(t => { if (!has(t)) tags.push(t); }); drawChips(); }
  });
  hidden.value = initial || '';
  allTags().then(t => {
    const seen = new Set();
    all = (forNotes ? [...t.notes, ...t.documents] : [...t.documents, ...t.notes]).filter(x => { const k = x.toLowerCase(); if (seen.has(k)) return false; seen.add(k); return true; });
    drawSuggestions();
  });
  hidden._widget = h('div', null, field, suggestions);
  return hidden;
}

function folderDetailsDialog(title, init, onSave) {
  modal((box, close) => {
    const t = h('input', { value: init.title || '', placeholder: 'Ethical Theory and Ontology' });
    const e = h('input', { value: init.editors || '', placeholder: 'Name Nameson; Other Person' });
    const g = tagInput(init.tags || '', false);
    const s = h('textarea', { rows: 4 }, init.summary || '');
    const save = async () => {
      if (!t.value.trim()) { t.focus(); return; }
      try { await onSave({ title: t.value.trim(), editors: e.value, tags: g.value, summary: s.value }); close(); } catch (err) { fail(err); }
    };
    box.append(h('h2', null, title), h('p', { class: 'muted', style: { marginTop: 0 } }, 'One book made of several files, one per chapter — for example an edited volume.'),
      h('div', { class: 'fields' },
        h('label', null, h('span', { class: 'label' }, 'Title'), t),
        h('label', null, h('span', { class: 'label' }, 'Editor(s) — separate with ;'), e),
        h('div', { class: 'tagwrap' }, h('span', { class: 'label' }, 'Tags'), g._widget),
        h('label', null, h('span', { class: 'label' }, 'Abstract (optional)'), s)),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save')));
  });
}

function newFolderBook(withParts) {
  folderDetailsDialog('New folder book', {}, async v => {
    const f = await api('folders', { method: 'POST', json: Object.assign(v, { parts: withParts || [] }) });
    toast('Folder book made');
    location.hash = `#/book/${f.id}`;
  });
}

async function addToFolderBook(d) {
  try { await loadLibrary(); } catch (e) { fail(e); return; }
  const folders = state.docs.filter(x => x.folder);
  if (!folders.length) return newFolderBook([d.id]);
  modal((box, close) => {
    box.append(h('h2', null, 'Add to a folder book'), h('p', { class: 'muted', style: { marginTop: 0 } }, `“${d.title}” becomes a chapter of the book you choose.`));
    folders.forEach(f => box.append(h('button', { class: 'btn', style: { display: 'flex', width: '100%', justifyContent: 'space-between', marginBottom: '6px', padding: '10px 12px' }, onclick: async () => {
      try { await api(`doc/${f.id}/parts`, { method: 'POST', json: { ids: [d.id] } }); close(); toast(`Added to “${f.title}”`); location.hash = `#/book/${f.id}`; } catch (e) { fail(e); }
    } }, h('span', { class: 'serif', style: { fontSize: '15px' } }, f.title), h('span', null, `${f.partCount || 0} chapters`))));
    box.append(h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: () => { close(); newFolderBook([d.id]); } }, 'New folder book…')));
  });
}

function pickFromLibrary(folder, onPicked) {
  const pickable = state.docs.filter(x => !x.folder && !x.inFolder);
  modal((box, close) => {
    box.style.width = '640px';
    const chosen = new Set();
    const q = h('input', { type: 'search', placeholder: 'Search the library' });
    const list = h('div', { style: { maxHeight: '50vh', overflow: 'auto', borderTop: '1px solid var(--ink)', marginTop: '8px' } });
    const count = h('button', { class: 'btn fill', disabled: true }, 'Add');
    const draw = () => {
      clear(list);
      const terms = norm(q.value).split(/\s+/).filter(Boolean);
      pickable.filter(x => terms.every(t => norm(x.title + ' ' + x.displayAuthor).includes(t))).forEach(x => {
        const cb = h('input', { type: 'checkbox', checked: chosen.has(x.id) });
        cb.addEventListener('change', () => { if (cb.checked) chosen.add(x.id); else chosen.delete(x.id); count.disabled = !chosen.size; count.textContent = chosen.size ? `Add ${chosen.size}` : 'Add'; });
        list.append(h('label', { class: 'row', style: { padding: '7px 2px', borderBottom: '1px solid var(--rule)', cursor: 'pointer', flexWrap: 'nowrap' } }, cb,
          h('span', { style: { minWidth: 0 } }, h('b', { class: 'serif' }, x.title), h('div', { class: 'muted', style: { fontSize: '12px' } }, `${x.displayAuthor || x.author} · ${x.fileType}`))));
      });
      if (!list.firstChild) list.append(h('div', { class: 'muted', style: { padding: '10px 0' } }, 'Nothing to add.'));
    };
    q.addEventListener('input', draw);
    count.addEventListener('click', async () => { try { await onPicked([...chosen]); close(); } catch (e) { fail(e); } });
    box.append(h('h2', null, `Add to “${folder.title}”`), q, list, h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), count));
    draw();
  });
}

function renderFolder(main, d) {
  state.uploadFolder = { id: d.id, title: d.title };
  const parts = d.parts || [];
  const tags = splitTags(d.tags);
  const file = h('input', { type: 'file', multiple: true, accept: '.pdf,.epub,.zip,.html,.htm,.txt,.md', hidden: true });
  file.addEventListener('change', () => { if (file.files.length) uploadFiles([...file.files]); file.value = ''; });
  const reload = () => showBook(d.id);
  const partAction = async (path, opts) => { try { await api(path, opts); reload(); } catch (e) { fail(e); } };
  const resume = parts.find(p => p.id === d.resumeId);

  const left = h('div', null,
    h('div', { style: { cursor: parts.length ? 'pointer' : 'default' }, onclick: () => { if (resume) location.hash = `#/read/${resume.id}`; } }, coverImg(d, 'bc')),
    coverButton(d),
    h('div', { class: 'facts', style: { flexDirection: 'column', gap: '4px' } },
      h('span', null, `Folder book · ${parts.length} chapter${parts.length === 1 ? '' : 's'}`),
      h('span', null, `${parts.reduce((a, p) => a + (p.totalPages || 0), 0)} pages in all`),
      h('span', null, `Added ${fmtDate(d.dateAdded)}`),
      h('span', null, `Last read ${fmtDate(d.lastRead)}`)),
    bibFacts(d));

  const list = h('div', { class: 'chapters' });
  if (!parts.length) list.append(h('div', { class: 'empty', style: { padding: '28px 16px' } }, h('h2', null, 'No chapters yet'),
    h('div', { class: 'muted' }, 'Drag the chapter PDFs onto this page, use “Add files”, or add books already in the library.')));
  parts.forEach((p, i) => {
    const meta = [p.fileType, `${p.totalPages} ${isEpub(p) ? 'positions' : 'pages'}`, p.progress >= 0.999 ? 'read ✓' : p.progress > 0 ? `${pct(p.progress)}% read` : '', p.highlights ? `${p.highlights} notes` : '', p.id === d.resumeId && d.progress > 0 ? '◆ reading now' : ''].filter(Boolean).join(' · ');
    list.append(h('div', { class: 'ch' },
      h('div', { class: 'no' }, p.number),
      h('div', { style: { minWidth: 0 } },
        h('a', { class: 'ct', href: `#/read/${p.id}`, style: { textDecoration: 'none' } }, p.title),
        p.author && p.author !== 'Unknown Author' ? h('div', { class: 'ca' }, p.author) : null,
        h('div', { class: 'cm' }, meta)),
      h('div', { class: 'row', style: { gap: '2px' } },
        canRead(p) ? h('button', { class: 'btn quiet', onclick: () => { location.hash = `#/read/${p.id}`; } }, 'Read') : null,
        h('button', { class: 'btn quiet', title: 'Details, notes and exports of this chapter', onclick: () => { location.hash = `#/book/${p.id}`; } }, 'Open'),
        h('button', { class: 'btn quiet', title: 'Cite this chapter as a book section', onclick: () => citePart(d, p) }, 'Cite'),
        partExportMenu(p),
        h('button', { class: 'btn quiet', title: 'Move up', disabled: i === 0, onclick: () => partAction(`doc/${d.id}/parts/${p.id}/move`, { method: 'POST', json: { delta: -1 } }) }, '↑'),
        h('button', { class: 'btn quiet', title: 'Move down', disabled: i === parts.length - 1, onclick: () => partAction(`doc/${d.id}/parts/${p.id}/move`, { method: 'POST', json: { delta: 1 } }) }, '↓'),
        h('button', { class: 'btn quiet', title: 'Take out of this book (it goes back on the shelf)', onclick: () => partAction(`doc/${d.id}/parts/${p.id}`, { method: 'DELETE' }) }, '×'))));
  });

  // Notes, grouped by chapter
  const notesBox = h('div');
  const byPart = new Map(parts.map(p => [p.id, []]));
  (d.highlightList || []).forEach(n => { if (byPart.has(n.documentId)) byPart.get(n.documentId).push(n); });
  let anyNotes = false;
  parts.forEach(p => {
    const ns = byPart.get(p.id) || [];
    if (!ns.length) return;
    anyNotes = true;
    notesBox.append(h('div', { class: 'label', style: { marginTop: '12px' } }, `${p.number} · ${p.title}`));
    ns.forEach(n => notesBox.append(noteItem(n, p, reload)));
  });
  if (!anyNotes) notesBox.append(h('div', { class: 'muted' }, 'No notes yet. Open a chapter and select text to make one.'));

  const right = h('div', null,
    h('div', { class: 'label' }, 'Folder book'),
    h('h1', null, d.title),
    h('div', { class: 'by' }, d.displayAuthor || d.author || ''),
    tags.length ? h('div', { class: 'tags' }, tags.map(t => h('span', { class: 'tag' }, '#' + t))) : null,
    h('div', { class: 'facts' }, h('span', null, `${pct(d.progress)}% read`), resume && d.progress > 0 ? h('span', null, `Reading: ${resume.title}`) : null, h('span', null, `${d.highlights} notes`)),
    h('div', { class: 'bar', style: { height: '4px', background: 'var(--rule)', marginBottom: '16px' } }, h('i', { style: { display: 'block', height: '100%', background: '#000', width: pct(d.progress) + '%' } })),
    h('div', { class: 'row' },
      resume ? h('button', { class: 'btn fill', onclick: () => { location.hash = `#/read/${resume.id}`; } }, d.progress > 0 ? 'Continue reading' : 'Read from the start') : null,
      h('button', { class: 'btn', onclick: () => file.click(), title: 'Or drag files onto this page' }, '+ Add files'), file,
      h('button', { class: 'btn', onclick: () => pickFromLibrary(d, async ids => { await api(`doc/${d.id}/parts`, { method: 'POST', json: { ids } }); toast('Added'); reload(); }) }, 'Add from library'),
      menuButton('Export ▾', [
        ['All chapters (.zip)', 'Every chapter’s notes, overview, BibTeX and file, plus a contents note', () => download(`doc/${d.id}/export/folder`, 'Exporting every chapter…')],
        ['BibTeX of the book (.bib)', 'The book’s own record', () => download(`doc/${d.id}/export/bib`, 'Preparing…')],
      ]),
      h('button', { class: 'btn', onclick: () => editAll(d) }, 'Edit details'),
      h('button', { class: 'btn quiet', onclick: () => confirmBox('Delete this folder book?', `The folder book is removed. Its ${parts.length} chapter files and their notes stay in the library and are shown on the shelf again.`, 'Delete', async () => {
        try { await api(`doc/${d.id}`, { method: 'DELETE' }); toast('Deleted'); location.hash = '#/'; } catch (e) { fail(e); }
      }) }, 'Delete')),
    d.summary ? h('div', { class: 'section' }, h('h2', null, 'Abstract'), h('div', { class: 'summary' }, d.summary)) : null,
    h('div', { class: 'section' }, h('h2', null, 'Chapters', h('span', { class: 'count' }, parts.length)), list),
    h('div', { class: 'section' }, h('h2', null, 'Notes', h('span', { class: 'count' }, d.highlights)), notesBox),
    bibSection(d, main));
  main.append(h('div', { class: 'book' }, left, right));
}

function menuButton(label, items, cls = 'btn') {
  const wrap = h('div', { class: 'menu' });
  const pop = h('div', { class: 'menu-pop', hidden: true });
  items.forEach(([t, sub, fn]) => pop.append(h('button', { onclick: () => { pop.hidden = true; fn(); } }, h('b', null, t), sub ? h('span', null, sub) : null)));
  const btn = h('button', { class: cls, onclick: e => { e.stopPropagation(); document.querySelectorAll('.menu-pop').forEach(x => { if (x !== pop) x.hidden = true; }); pop.hidden = !pop.hidden; } }, label);
  document.addEventListener('click', () => { pop.hidden = true; });
  wrap.append(btn, pop);
  return wrap;
}

/** One editor for everything about a book: its details and its bibliographic record (kept in step). */
function editAll(d, focus) {
  const bib = d.bib || { fields: {}, labels: {} };
  const f = bib.fields || {};
  modal((box, close) => {
    box.style.width = '860px';
    const folder = !!d.folder;
    const t = h('input', { value: d.title });
    const a = h('input', { value: folder ? (d.editors || d.author || '') : d.author, placeholder: folder ? 'Name Nameson; Other Person' : '' });
    const g = tagInput(d.tags || '', false);
    const s = h('textarea', { rows: 5 }, d.summary || '');
    const reset = h('input', { type: 'checkbox' });
    let category = d.category === 'ARTICLE' ? 'ARTICLE' : 'BOOK';
    const catBox = h('span', { class: 'row', style: { gap: '4px' } });
    const drawCat = () => clear(catBox).append(
      h('button', { class: 'chip' + (category === 'BOOK' ? ' on' : ''), type: 'button', onclick: () => { category = 'BOOK'; drawCat(); } }, 'Book'),
      h('button', { class: 'chip' + (category === 'ARTICLE' ? ' on' : ''), type: 'button', onclick: () => { category = 'ARTICLE'; drawCat(); } }, 'Article'));
    drawCat();
    const inputs = {};
    const grid = h('div', { class: 'bibgrid' });
    Object.keys(bib.labels || {}).forEach(k => {
      if (k === 'TITLE' || k === 'CITEKEY' || (folder && k === 'EDITOR')) return;   // title and editors are the ones above
      const i = h('input', { value: f[k] || '' });
      inputs[k] = i;
      grid.append(h('label', null, h('span', { class: 'label' }, bib.labels[k]), i));
    });
    let entryType = bib.entryType || '', typeChanged = false;
    const mainTitle = () => {
      const sub = (inputs.SUBTITLE && inputs.SUBTITLE.value.trim()) || '';
      const m = t.value.trim();
      return sub && m.toLowerCase().endsWith((': ' + sub).toLowerCase()) ? m.slice(0, m.length - sub.length - 2).trim() : m;
    };
    const lookPanel = lookupPanel({
      d, inputs, labels: bib.labels || {}, a, s, folder, mainTitle,
      current: () => { const o = { TITLE: mainTitle(), TYPE: entryType }; Object.entries(inputs).forEach(([k, i]) => { o[k] = i.value; }); if (folder) o.EDITOR = a.value; return o; },
      apply: chosen => {
        let main = mainTitle(), retitle = false;
        const mark = el => { el.classList.add('justfilled'); setTimeout(() => el.classList.remove('justfilled'), 4000); };
        chosen.forEach(r => {
          if (r.key === 'AUTHOR' || (folder && r.key === 'EDITOR')) { a.value = r.found; mark(a); }
          else if (r.key === 'ABSTRACT') { s.value = r.found; mark(s); }
          else if (r.key === 'TYPE') {
            entryType = r.found; typeChanged = true;
            if (r.found === 'article' && !folder) { category = 'ARTICLE'; drawCat(); }
            if (r.found === 'book' || r.found === 'incollection') { category = 'BOOK'; drawCat(); }
          }
          else if (r.key === 'TITLE') { main = r.found; retitle = true; }
          else if (inputs[r.key]) { inputs[r.key].value = r.found; mark(inputs[r.key]); if (r.key === 'SUBTITLE') retitle = true; }
        });
        if (retitle) { const sub = (inputs.SUBTITLE && inputs.SUBTITLE.value.trim()) || ''; t.value = sub && !main.toLowerCase().endsWith(sub.toLowerCase()) ? `${main}: ${sub}` : main; mark(t); }
      }
    });
    const save = async () => {
      if (!t.value.trim()) { t.focus(); return; }
      try {
        const tags = g.value.split(',').map(x => x.trim()).filter(Boolean).join(', ');
        await api(`doc/${d.id}`, { method: 'PATCH', json: { title: t.value, author: a.value, tags, summary: s.value, resetCover: reset.checked, category } });
        tagCache = null;
        const sub = (inputs.SUBTITLE && inputs.SUBTITLE.value.trim()) || '';
        let main = t.value.trim();
        if (sub && main.toLowerCase().endsWith((': ' + sub).toLowerCase())) main = main.slice(0, main.length - sub.length - 2).trim();
        const body = { TITLE: main };
        Object.entries(inputs).forEach(([k, i]) => { body[k] = i.value; });
        if (typeChanged) body._type = entryType;
        await api(`doc/${d.id}/bib`, { method: 'PUT', json: body });
        close(); toast('Saved'); showBook(d.id);
      } catch (e) { fail(e); }
    };
    // RIS: fill in from a file or pasted text; copy or download this item's record.
    const risFile = h('input', { type: 'file', accept: '.ris,.txt,application/x-research-info-systems', hidden: true });
    const overwrite = h('input', { type: 'checkbox' });
    const applyRis = async text => {
      try {
        const r = await api(`doc/${d.id}/ris`, { method: 'POST', json: { text, overwrite: overwrite.checked } });
        close(); toast(r.risFilled ? `Filled ${r.risFilled} field${r.risFilled === 1 ? '' : 's'} from RIS` : 'The RIS record had nothing new'); tagCache = null; showBook(d.id);
      } catch (e) { fail(e); }
    };
    risFile.addEventListener('change', async () => { const f0 = risFile.files[0]; if (f0) applyRis(await f0.text()); risFile.value = ''; });
    const pasteRis = async () => {
      if (navigator.clipboard && window.isSecureContext) {
        try { const txt = await navigator.clipboard.readText(); if (txt && /TY\s{1,2}-/.test(txt)) return applyRis(txt); } catch (_) {}
      }
      modal((pb, pclose) => {
        const ta = h('textarea', { rows: 12, placeholder: 'TY  - BOOK\nTI  - …\nAU  - …\nER  - ' });
        pb.append(h('h2', null, 'Paste a RIS record'), h('p', { class: 'muted', style: { marginTop: 0 } }, 'Press Ctrl+V (⌘V) in the box. Browsers only let pages read the clipboard directly over https.'), ta,
          h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: pclose }, 'Cancel'), h('button', { class: 'btn fill', onclick: () => { pclose(); applyRis(ta.value); } }, 'Fill in')));
      });
    };
    const copyRis = async () => {
      try {
        const r = await fetch(`/api/doc/${d.id}/export/ris`, { credentials: 'same-origin' });
        if (!r.ok) throw new Error('Error ' + r.status);
        copyText(await r.text(), 'RIS copied');
      } catch (e) { fail(e); }
    };
    const risPanel = h('div', { class: 'rispanel' },
      h('div', { class: 'label' }, 'RIS — Zotero, EndNote, Mendeley, library catalogues'),
      h('div', { class: 'row' },
        h('button', { class: 'btn', type: 'button', onclick: () => risFile.click() }, 'Import RIS file…'), risFile,
        h('button', { class: 'btn', type: 'button', onclick: pasteRis }, 'Paste RIS…'),
        h('button', { class: 'btn', type: 'button', onclick: copyRis }, 'Copy RIS'),
        h('button', { class: 'btn', type: 'button', onclick: () => download(`doc/${d.id}/export/ris`, 'Preparing RIS…') }, 'Download .ris')),
      h('label', { class: 'row', style: { flexDirection: 'row', fontSize: '13px' } }, overwrite, h('span', null, 'Importing replaces fields that are already filled (otherwise only empty ones are filled)')));

    box.append(h('h2', null, 'Edit details'),
      h('div', { class: 'row', style: { margin: '4px 0 10px' } }, h('span', { class: 'label' }, folder ? 'Folder book · shelf' : 'Shelf'), catBox),
      h('div', { class: 'fields' },
        h('label', null, h('span', { class: 'label' }, 'Title'), t),
        h('label', null, h('span', { class: 'label' }, folder ? 'Editor(s) — separate with ;' : 'Author(s)'), a),
        h('div', { class: 'tagwrap' }, h('span', { class: 'label' }, 'Tags'), g._widget),
        h('label', null, h('span', { class: 'label' }, 'Abstract'), s),
        folder ? null : h('label', { class: 'row', style: { flexDirection: 'row' } }, reset, h('span', null, 'Make the cover again from the file'))),
      h('hr', { class: 'rule' }),
      h('div', { class: 'label', style: { marginBottom: '8px' } }, 'Bibliography — used for citations, BibTeX and exports'),
      lookPanel, h('div', { style: { height: '10px' } }),
      risPanel, h('div', { style: { height: '10px' } }),
      grid,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save')));
    if (focus === 'bib') setTimeout(() => { const first = grid.querySelector('input'); if (first) { first.scrollIntoView({ block: 'center' }); first.focus(); } }, 40);
    if (focus === 'lookup') setTimeout(() => { lookPanel.scrollIntoView({ block: 'center' }); const b = lookPanel.querySelector('button'); if (b) b.click(); }, 60);
  });
}

/** References come with *italics* marks: shown as italics, copied without them. */
function plainRef(t) { return (t || '').replace(/\*/g, ''); }
function refHtml(t) {
  const esc = (t || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return esc.replace(/\*([^*]+)\*/g, '<em>$1</em>').replace(/(https?:\/\/[^\s<]+[^\s<.,;)])/g, '<a href="$1" target="_blank" rel="noopener">$1</a>');
}

/** Bibliographic facts shown under the cover. */
function bibFacts(d) {
  const f = (d.bib && d.bib.fields) || {}, labels = (d.bib && d.bib.labels) || {};
  const order = ['EDITOR', 'YEAR', 'PUBLISHER', 'PLACE', 'EDITION', 'SERIES', 'VOLUME', 'ISBN', 'ISSN', 'DOI', 'TRANSLATOR', 'JOURNAL', 'BOOKTITLE', 'PAGES', 'LANGUAGE', 'URL'];
  const short = { EDITOR: 'Editor(s)', YEAR: 'Year', PLACE: 'Place', ISSUE: 'Issue', JOURNAL: 'Journal', BOOKTITLE: 'In', PAGES: 'Pages' };
  const rows = order.filter(k => (f[k] || '').trim()).map(k =>
    h('div', { class: 'bf' }, h('span', { class: 'label' }, short[k] || labels[k] || k),
      k === 'DOI' ? (() => { const doi = f[k].trim().replace(/^(?:https?:\/\/)?(?:dx\.)?doi\.org\//i, '').replace(/^doi:\s*/i, ''); return h('a', { href: 'https://doi.org/' + doi, target: '_blank', rel: 'noopener' }, doi); })() :
      k === 'URL' ? h('a', { href: f[k].trim(), target: '_blank', rel: 'noopener' }, 'link') : h('span', null, f[k])));
  return h('div', { class: 'bibfacts' }, rows.length ? rows : h('div', { class: 'muted', style: { fontSize: '12px' } }, 'No ISBN, publisher or year yet.'),
    h('button', { class: 'btn quiet', style: { padding: '3px 0', marginTop: '4px' }, onclick: () => editAll(d, 'bib') }, rows.length ? 'Edit' : 'Add details'));
}

function chaptersSection(d) {
  const list = h('div', { class: 'chapters' });
  let showSections = localGet('ml.sections', '0') === '1';
  const draw = () => {
    clear(list);
    d.chapters.filter(c => showSections || c.kind !== 'section').forEach(c => {
      const pages = c.printedPages ? `pp. ${c.printedPages}` : (isPdf(d) ? `PDF p. ${c.startPage}–${c.endPage}` : '');
      const go = () => { location.hash = isPdf(d) ? `#/read/${d.id}?page=${c.startPage}` : `#/read/${d.id}?href=${encodeURIComponent(c.href || '')}&pos=${c.startPage}`; };
      list.append(h('div', { class: 'ch ' + (c.kind === 'part' ? 'part' : c.kind === 'section' ? 'sec' : c.kind === 'matter' ? 'matter' : '') },
        h('div', { class: 'no' }, c.number || ''),
        h('div', { style: { minWidth: 0 } },
          h('a', { class: 'ct', href: 'javascript:void 0', onclick: go, style: { textDecoration: 'none' } }, c.title),
          c.authors ? h('div', { class: 'ca' }, c.authors.split(';').map(s => s.trim()).join(', ')) : null,
          h('div', { class: 'cm' }, [pages, c.notes ? `${c.notes} notes` : '', c.progress >= 0.999 ? 'read ✓' : c.progress > 0 ? `${pct(c.progress)}% read` : '', splitTags(c.tags).map(t => '#' + t).join(' ')].filter(Boolean).join(' · '))),
        c.kind === 'chapter' ? h('div', { class: 'row', style: { gap: '2px' } },
          h('button', { class: 'btn quiet', onclick: go }, 'Read'),
          h('button', { class: 'btn quiet', title: 'Cite this chapter as a book section', onclick: () => citeChapter(d, c) }, 'Cite'),
          chapterExportMenu(d, c)) : h('div')));
    });
  };
  draw();
  return h('div', { class: 'section' },
    h('h2', null, 'Chapters', h('span', { class: 'count' }, d.chapters.filter(c => c.kind === 'chapter').length),
      h('span', { class: 'spacer' }),
      h('button', { class: 'chip' + (showSections ? ' on' : ''), onclick: e => { showSections = !showSections; localSet('ml.sections', showSections ? '1' : '0'); e.target.classList.toggle('on', showSections); draw(); } }, 'Sections')),
    list);
}

function noteLocus(n, d) {
  const bits = [];
  if (n.chapterTitle) bits.push(n.chapterTitle);
  if (n.printedPageLabel) bits.push((/[–-]/.test(n.printedPageLabel) ? 'pp. ' : 'p. ') + n.printedPageLabel);
  else if (n.pageEquivalent) bits.push('p. ' + n.pageEquivalent);
  if (!d || isPdf(d)) bits.push('PDF p. ' + n.pageNumber); else if (!n.printedPageLabel) bits.push('pos. ' + n.pageNumber);
  return bits.join(' · ');
}

function noteItem(n, d, onChanged) {
  const tags = splitTags(n.tags);
  const openIt = () => {
    if (!d || !canRead(d)) return;
    location.hash = isPdf(d) ? `#/read/${d.id}?page=${n.pageNumber}&hl=${n.id}` : `#/read/${d.id}?pos=${n.pageNumber}&hl=${n.id}`;
  };
  const el = h('div', { class: 'note' },
    h('div', { class: 'sw', style: { background: n.colorHex || '#E4E4E7' } }),
    h('div', { style: { minWidth: 0 } },
      h('div', { class: 'nt' }, n.title),
      h('blockquote', null, n.highlightedText),
      n.userNotes ? h('div', { class: 'nn' }, n.userNotes) : null,
      tags.length ? h('div', { class: 'tags' }, tags.map(t => h('span', { class: 'tag' }, '#' + t))) : null,
      h('div', { class: 'nl' }, noteLocus(n, d)),
      h('div', { class: 'acts' },
        d && canRead(d) ? h('button', { class: 'btn', onclick: openIt }, 'Show in book') : null,
        h('button', { class: 'btn', onclick: () => editNote(n, onChanged) }, 'Edit'),
        h('button', { class: 'btn', onclick: () => copyText(`> ${n.highlightedText}\n\n${n.userNotes || ''}`.trim(), 'Note copied') }, 'Copy'),
        h('button', { class: 'btn', onclick: () => download(`doc/${n.documentId}/export/note?id=${n.id}`, 'Preparing the note…') }, '.md'),
        h('button', { class: 'btn quiet', onclick: () => confirmBox('Delete this note?', n.title, 'Delete', async () => {
          try { await api(`highlight/${n.id}`, { method: 'DELETE' }); toast('Note deleted'); onChanged && onChanged(); } catch (e) { fail(e); }
        }) }, 'Delete'))));
  onContext(el, () => noteMenu(n, d, onChanged, d && canRead(d) ? openIt : null));
  return el;
}

function editNote(n, onSaved) {
  modal((box, close) => {
    let color = n.colorHex || '#FFEB3B';
    const t = h('input', { value: n.title }), body = h('textarea', { rows: 7 }, n.userNotes || ''), g = tagInput(n.tags || '', true);
    const save = async () => {
      try {
        const r = await api(`highlight/${n.id}`, { method: 'PATCH', json: { title: t.value, userNotes: body.value, tags: g.value, colorHex: color } });
        Object.assign(n, r); tagCache = null; close(); toast('Note saved'); onSaved && onSaved(r);
      } catch (e) { fail(e); }
    };
    body.addEventListener('keydown', e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) save(); });
    box.append(h('h2', null, 'Edit note'), h('div', { class: 'fields' },
      h('div', { class: 'quote' }, n.highlightedText),
      h('label', null, h('span', { class: 'label' }, 'Title'), t),
      h('label', null, h('span', { class: 'label' }, 'Note'), body),
      h('div', { class: 'tagwrap' }, h('span', { class: 'label' }, 'Tags'), g._widget),
      h('div', null, h('div', { class: 'label', style: { marginBottom: '4px' } }, 'Colour'), swatches(color, c => { color = c; }))),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save')));
  });
}

function notesSection(d, main) {
  const list = h('div');
  const notes = (d.highlightList || []).slice();
  let order = localGet('ml.noteOrder', 'place');
  const draw = () => {
    clear(list);
    const sorted = notes.slice().sort(order === 'new' ? (a, b) => b.created - a.created : (a, b) => a.pageNumber - b.pageNumber || a.created - b.created);
    if (!sorted.length) list.append(h('div', { class: 'muted' }, canRead(d) ? 'No notes yet. Open the book and select text to make one.' : 'No notes yet.'));
    sorted.forEach(n => list.append(noteItem(n, d, () => showBook(d.id))));
  };
  draw();
  return h('div', { class: 'section' },
    h('h2', null, 'Notes', h('span', { class: 'count' }, notes.length), h('span', { class: 'spacer' }),
      h('button', { class: 'chip' + (order === 'place' ? ' on' : ''), onclick: e => { order = 'place'; localSet('ml.noteOrder', order); e.target.parentNode.querySelectorAll('.chip').forEach(c => c.classList.remove('on')); e.target.classList.add('on'); draw(); } }, 'In order'),
      h('button', { class: 'chip' + (order === 'new' ? ' on' : ''), onclick: e => { order = 'new'; localSet('ml.noteOrder', order); e.target.parentNode.querySelectorAll('.chip').forEach(c => c.classList.remove('on')); e.target.classList.add('on'); draw(); } }, 'Newest')),
    list);
}

function bibSection(d, main) {
  const bib = d.bib || { fields: {}, labels: {}, styles: [] };
  let style = bib.defaultStyle || 'CHICAGO_AUTHOR_DATE';
  const body = h('div');
  const draw = () => clear(body).append(citeBlock(bib, style));
  draw();
  return h('div', { class: 'section' },
    h('h2', null, 'Bibliography', h('span', { class: 'spacer' }),
      h('button', { class: 'btn', onclick: () => editAll(d, 'bib') }, 'Edit details')),
    h('div', { class: 'row', style: { marginBottom: '4px' } }, h('span', { class: 'label' }, 'Style'), styleSelect(bib, v => { style = v; draw(); })),
    body,
    bibFiles(bib, { ris: `doc/${d.id}/export/ris`, bib: `doc/${d.id}/export/bib` }),
    bib.bibtex ? h('details', null, h('summary', { class: 'label', style: { cursor: 'pointer', marginTop: '10px' } }, 'BibTeX and RIS'),
      h('pre', { class: 'bibtex' }, bib.bibtex), bib.ris ? h('pre', { class: 'bibtex' }, bib.ris) : null) : null);
}

// ------------------------------------------------------------------ citations

const TYPE_LABELS = { book: 'Book', article: 'Article', incollection: 'Book section (chapter)', inbook: 'Book section (chapter)', phdthesis: 'Thesis', techreport: 'Report', misc: 'Other' };

/** The citation-style dropdown. The choice becomes the default on the tablet too (app and web). */
function styleSelect(bib, onChange) {
  const sel = h('select', { class: 'stylesel', title: 'Citation style', 'aria-label': 'Citation style' },
    (bib.styles || []).map(s => h('option', { value: s.id }, s.label)));
  sel.value = bib.defaultStyle || 'CHICAGO_AUTHOR_DATE';
  sel.addEventListener('change', async () => {
    bib.defaultStyle = sel.value;
    onChange(sel.value);
    try { await api('citation-style', { method: 'POST', json: { style: sel.value } }); } catch (e) { fail(e); }
  });
  return sel;
}

/** One style's reference, with copy buttons. */
function citeBlock(bib, styleId) {
  const s = (bib.styles || []).find(x => x.id === styleId) || (bib.styles || [])[0];
  if (!s) return h('div', { class: 'muted' }, 'No citation yet.');
  const it = plainRef(s.inText || '');
  return h('div', { class: 'cite' },
    h('div', { class: 'ref', html: refHtml(s.reference) }),
    h('div', { class: 'row', style: { marginTop: '6px' } },
      h('button', { class: 'btn', onclick: () => copyText(plainRef(s.reference), 'Reference copied') }, 'Copy reference'),
      it ? h('button', { class: 'btn', title: it, onclick: () => copyText(it, 'Citation copied') },
        it.length > 28 ? (s.id === 'CHICAGO_NOTES' ? 'Copy footnote' : 'Copy in-text citation') : 'Copy ' + it) : null));
}

/** BibTeX and RIS, to copy or download. [paths] are the export addresses. */
function bibFiles(bib, paths) {
  return h('div', { class: 'row', style: { marginTop: '10px' } },
    bib.ris ? h('button', { class: 'btn quiet', onclick: () => copyText(bib.ris, 'RIS copied') }, 'Copy RIS') : null,
    paths.ris ? h('button', { class: 'btn quiet', onclick: () => download(paths.ris, 'Preparing RIS…') }, 'Download .ris') : null,
    bib.bibtex ? h('button', { class: 'btn quiet', onclick: () => copyText(bib.bibtex, 'BibTeX copied') }, 'Copy BibTeX') : null,
    paths.bib ? h('button', { class: 'btn quiet', onclick: () => download(paths.bib, 'Preparing BibTeX…') }, 'Download .bib') : null);
}

/** A chapter cited on its own — a book section, as in Zotero. */
function citeModal(title, sub, bib, paths, warn) {
  modal((box, close) => {
    box.style.width = '640px';
    let style = bib.defaultStyle || 'CHICAGO_AUTHOR_DATE';
    const body = h('div');
    const draw = () => clear(body).append(citeBlock(bib, style));
    draw();
    box.append(...[
      h('h2', null, title),
      sub ? h('div', { class: 'muted', style: { marginTop: '-6px', marginBottom: '12px' }, html: sub }) : null,
      warn ? h('div', { class: 'warn' }, warn) : null,
      h('div', { class: 'row', style: { marginBottom: '10px' } }, h('span', { class: 'label' }, 'Style'), styleSelect(bib, v => { style = v; draw(); })),
      body,
      bibFiles(bib, paths),
      bib.bibtex ? h('details', null, h('summary', { class: 'label', style: { cursor: 'pointer', marginTop: '12px' } }, 'BibTeX and RIS'),
        h('pre', { class: 'bibtex' }, bib.bibtex), bib.ris ? h('pre', { class: 'bibtex' }, bib.ris) : null) : null,
      h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: close }, 'Close'))].filter(Boolean));
  });
}

function escHtml(s) { return (s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

async function citeChapter(d, c) {
  try {
    const r = await api(`doc/${d.id}/chapter?index=${c.index}`);
    const eds = (r.editors || '').split(';').map(x => x.trim()).filter(Boolean).join(', ');
    citeModal('Cite this chapter', `Book section in <em>${escHtml(r.bookTitle)}</em>${eds ? ', edited by ' + escHtml(eds) : ''}${r.printedPages ? ', pp. ' + escHtml(r.printedPages) : ''}`,
      r.bib, { ris: `doc/${d.id}/export/chapter?index=${c.index}&format=ris`, bib: `doc/${d.id}/export/chapter?index=${c.index}&format=bib` },
      !r.authors ? 'The chapter’s author is not set yet, so the book’s editors are used. Add the author in the app’s chapter list.' : (!r.printedPages ? 'The chapter’s printed pages are not set; add them in the app’s chapter list for a complete reference.' : ''));
  } catch (e) { fail(e); }
}

async function citePart(folder, p) {
  try {
    const pd = await api(`doc/${p.id}`);
    citeModal('Cite this chapter', `Book section in <em>${escHtml(folder.title)}</em>`, pd.bib,
      { ris: `doc/${p.id}/export/ris`, bib: `doc/${p.id}/export/bib` },
      !p.author || p.author === 'Unknown Author' ? 'The chapter’s author is not set. Open the chapter and use Edit details.' : '');
  } catch (e) { fail(e); }
}

function chapterExportMenu(d, c) {
  const q = `doc/${d.id}/export/chapter?index=${c.index}`;
  return menuButton('Export ▾', [
    ['Chapter PDF', 'Only this chapter’s pages', () => download(q + '&format=pdf', 'Making the chapter PDF…')],
    ['RIS (.ris)', 'Book section for Zotero, EndNote, Mendeley', () => download(q + '&format=ris', 'Preparing RIS…')],
    ['BibTeX (.bib)', '@incollection with the book, editors and pages', () => download(q + '&format=bib', 'Preparing BibTeX…')],
    ['Notes (Markdown)', c.notes ? `${c.notes} note${c.notes === 1 ? '' : 's'} from this chapter` : 'No notes in this chapter yet', () => download(q + '&format=notes', 'Preparing the notes…')],
    ['Everything (.zip)', 'Notes, chapter PDF, BibTeX and cover', () => download(q, 'Exporting the chapter…')],
  ], 'btn quiet');
}

function partExportMenu(p) {
  return menuButton('Export ▾', [
    ['Chapter file', `The ${p.fileType} itself`, () => download(`doc/${p.id}/file?download=1`, 'Preparing the file…')],
    ['RIS (.ris)', 'Book section for Zotero, EndNote, Mendeley', () => download(`doc/${p.id}/export/ris`, 'Preparing RIS…')],
    ['BibTeX (.bib)', '@incollection with the book and editors', () => download(`doc/${p.id}/export/bib`, 'Preparing BibTeX…')],
    ['Notes (Markdown)', p.highlights ? `${p.highlights} note${p.highlights === 1 ? '' : 's'}` : 'No notes yet', () => download(`doc/${p.id}/export/notes`, 'Preparing the notes…')],
    ['Everything (.zip)', 'File, notes, overview, BibTeX and cover', () => download(`doc/${p.id}/export/bundle`, 'Preparing the bundle…')],
  ], 'btn quiet');
}

// ------------------------------------------------------------------ look up details online

/**
 * “Look up details” inside the editor. Runs on the tablet only when pressed, then shows what was
 * found next to what is filled in; nothing changes until Apply (and then Save).
 */
function lookupPanel(ctx) {
  const panel = h('div', { class: 'lookpanel' });
  const status = h('div', { class: 'muted', style: { fontSize: '13px' } });
  const out = h('div');
  const btn = h('button', { class: 'btn fill', type: 'button' }, 'Look up details');
  const byTitle = h('button', { class: 'btn', type: 'button' }, 'Search by title');
  const hint = () => {
    const ids = ['DOI', 'ISBN', 'ISSN'].filter(k => ctx.inputs[k] && ctx.inputs[k].value.trim());
    return ids.length ? `Uses the ${ids.join(', ')} — Crossref, Open Library and Google Books` : 'No DOI, ISBN or ISSN yet: searches by title and author';
  };
  const sub = h('span', { class: 'muted', style: { fontSize: '12px' } }, hint());
  Object.values(ctx.inputs).forEach(i => i.addEventListener('input', () => { sub.textContent = hint(); }));
  const run = async search => {
    btn.disabled = byTitle.disabled = true; clear(out);
    status.textContent = search ? 'Searching by title…' : 'Looking up…';
    try {
      const fields = {}; Object.entries(ctx.inputs).forEach(([k, i]) => { fields[k] = i.value; });
      const r = await api(`doc/${ctx.d.id}/lookup`, { method: 'POST', json: { fields, title: ctx.mainTitle(), author: ctx.a.value, search }, timeout: 60000 });
      status.textContent = r.note || '';
      if (r.found) compareView(r.found);
      else if (r.candidates && r.candidates.length) candidatesView(r.candidates);
      else status.textContent = 'Nothing was found.';
    } catch (e) { status.textContent = e.message || String(e); }
    btn.disabled = byTitle.disabled = false;
  };
  btn.addEventListener('click', () => run(false));
  byTitle.addEventListener('click', () => run(true));

  const candidatesView = list => {
    clear(out).append(h('div', { class: 'cands' }, list.map(c => h('button', { class: 'cand', type: 'button', onclick: () => { status.textContent = `Chosen: ${c.label}`; compareView(c.found); } },
      h('b', null, c.label), h('span', null, c.detail)))));
  };

  const compareView = found => {
    const rows = [];
    const cur = ctx.current();
    const push = (key, label, now, fnd) => { if ((fnd || '').trim()) rows.push({ key, label, now: (now || '').trim(), found: fnd.trim() }); };
    if (found.author) push(ctx.folder ? 'EDITOR' : 'AUTHOR', ctx.folder ? 'Editor(s)' : 'Author(s)', ctx.a.value, found.author.split(';').map(x => x.trim()).filter(Boolean).join('; '));
    Object.entries(found.fields || {}).forEach(([k, v]) => {
      if (k === 'CITEKEY' || (ctx.folder && k === 'EDITOR' && found.author)) return;
      if (k !== 'TITLE' && !ctx.inputs[k] && !(ctx.folder && k === 'EDITOR')) return;
      push(k, ctx.labels[k] || k, cur[k], v);
    });
    if (found.abstract) push('ABSTRACT', 'Abstract', ctx.s.value, found.abstract);
    if (found.entryType) push('TYPE', 'Type', cur.TYPE, found.entryType);
    rows.forEach(r => { r.same = sameValue(r.key, r.now, r.found); r.use = !r.same && !r.now; });
    const diff = rows.filter(r => !r.same), same = rows.filter(r => r.same);
    const show = (k, v) => k === 'TYPE' ? (TYPE_LABELS[v] || v || '—') : (v || '—');
    const table = h('div', { class: 'cmp' },
      h('div', { class: 'cmph' }, h('span', null, 'Field'), h('span', null, 'Now'), h('span', null, 'Found'), h('span', null, '')));
    diff.forEach(r => {
      const keep = h('button', { type: 'button', class: 'chip' }, r.now ? 'Keep' : 'Leave empty');
      const use = h('button', { type: 'button', class: 'chip' }, r.now ? 'Use new' : 'Fill in');
      const paint = () => { keep.classList.toggle('on', !r.use); use.classList.toggle('on', r.use); line.classList.toggle('chosen', r.use); };
      keep.onclick = () => { r.use = false; paint(); };
      use.onclick = () => { r.use = true; paint(); };
      const line = h('div', { class: 'cmpr' + (r.key === 'ABSTRACT' ? ' long' : '') },
        h('span', { class: 'label' }, r.label), h('span', { class: 'now' + (r.now ? '' : ' blank') }, show(r.key, r.now)), h('span', { class: 'fnd' }, show(r.key, r.found)),
        h('span', { class: 'row', style: { gap: '4px', flexWrap: 'nowrap' } }, keep, use));
      paint();
      table.append(line);
    });
    const allNew = () => { diff.forEach(r => { r.use = true; }); compareRedraw(); };
    const onlyEmpty = () => { diff.forEach(r => { r.use = !r.now; }); compareRedraw(); };
    const compareRedraw = () => { table.querySelectorAll('.cmpr').forEach((ln, i) => { const r = diff[i]; ln.classList.toggle('chosen', r.use); const [k, u] = ln.querySelectorAll('.chip'); k.classList.toggle('on', !r.use); u.classList.toggle('on', r.use); }); };
    const apply = () => {
      const chosen = diff.filter(r => r.use);
      ctx.apply(chosen);
      clear(out); status.textContent = chosen.length ? `Filled in ${chosen.length} field${chosen.length === 1 ? '' : 's'} — check them, then Save.` : 'Nothing changed.';
    };
    clear(out).append(
      h('div', { class: 'muted', style: { fontSize: '12px', margin: '4px 0 6px' } },
        `From ${(found.sources || []).join(', ') || 'the lookup'} · ${diff.length} differ${diff.length === 1 ? 's' : ''}${same.length ? ` · ${same.length} already match` : ''}`),
      diff.length ? table : h('div', { class: 'muted' }, 'Everything found already matches what is filled in.'),
      diff.length ? h('div', { class: 'row', style: { marginTop: '8px' } },
        h('button', { class: 'btn quiet', type: 'button', onclick: onlyEmpty }, 'Only fill empty'),
        h('button', { class: 'btn quiet', type: 'button', onclick: allNew }, 'Use all new'),
        h('span', { style: { flex: '1' } }),
        h('button', { class: 'btn', type: 'button', onclick: () => { clear(out); status.textContent = ''; } }, 'Discard'),
        h('button', { class: 'btn fill', type: 'button', onclick: apply }, 'Apply chosen')) : '');
  };

  panel.append(h('div', { class: 'label' }, 'Look up online — DOI, ISBN, ISSN or title'),
    h('div', { class: 'row' }, btn, byTitle, sub), status, out);
  return panel;
}

function normText(s) { return (s || '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim(); }
function isbn13(s) {
  const d = (s || '').replace(/[^0-9Xx]/g, '').toUpperCase();
  if (d.length !== 10) return d;
  const core = '978' + d.slice(0, 9);
  let sum = 0; for (let i = 0; i < 12; i++) sum += (+core[i]) * (i % 2 ? 3 : 1);
  return core + ((10 - sum % 10) % 10);
}
function sameValue(key, a, b) {
  if (['AUTHOR', 'EDITOR', 'TRANSLATOR'].includes(key)) {
    const fams = s => (s || '').split(/;|&| and /).map(n => n.trim()).filter(Boolean)
      .map(n => normText(n.includes(',') ? n.split(',')[0] : n.split(/\s+/).pop())).filter(Boolean).sort().join('|');
    return fams(a) === fams(b);
  }
  if (['EDITION', 'VOLUME', 'ISSUE'].includes(key)) { const n = x => ((x || '').match(/\d+/) || [normText(x)])[0]; return n(a) === n(b); }
  if (key === 'ISBN') return isbn13(a) === isbn13(b);
  if (key === 'DOI') { const c = x => (x || '').trim().toLowerCase().replace(/^(?:https?:\/\/)?(?:dx\.)?doi\.org\//, '').replace(/^doi:\s*/, ''); return c(a) === c(b); }
  if (key === 'ABSTRACT') return normText(a).slice(0, 200) === normText(b).slice(0, 200);
  if (key === 'LANGUAGE') return normText(a).slice(0, 2) === normText(b).slice(0, 2);
  return normText(a) === normText(b);
}

// ------------------------------------------------------------------ all notes

let notesCache = null;
async function showNotes() {
  const app = clear(document.getElementById('app'));
  app.appendChild(header());
  const main = h('main', null, h('div', { id: 'noteslist' }, h('div', { class: 'loading', style: { position: 'static', height: '200px' } }, h('div', { class: 'spin' }))));
  app.appendChild(main);
  try {
    const [lib, all] = await Promise.all([loadLibrary(), api('highlights')]);
    notesCache = { docs: Object.fromEntries(lib.map(d => [d.id, d])), list: all.highlights || [] };
  } catch (e) { fail(e); return; }
  renderNotesList();
}

function renderNotesList() {
  const box = document.getElementById('noteslist'); if (!box || !notesCache) return;
  clear(box);
  const q = norm(state.query.trim().replace(/^#/, ''));
  const terms = q ? q.split(/\s+/) : [];
  const hits = notesCache.list.filter(n => !terms.length || terms.every(t => norm([n.title, n.highlightedText, n.userNotes, n.tags, n.documentTitle, n.documentAuthor, n.chapterTitle].join(' ')).includes(t)));
  box.append(h('div', { class: 'toolbar' }, h('div', { class: 'label' }, `${hits.length} note${hits.length === 1 ? '' : 's'}${terms.length ? ' match' : ''}`)));
  if (!hits.length) { box.append(h('div', { class: 'empty' }, h('h2', null, terms.length ? 'No notes match' : 'No notes yet'))); return; }
  const groups = new Map();
  hits.forEach(n => { if (!groups.has(n.documentId)) groups.set(n.documentId, []); groups.get(n.documentId).push(n); });
  const ordered = [...groups.entries()].sort((a, b) => Math.max(...b[1].map(n => n.created)) - Math.max(...a[1].map(n => n.created)));
  ordered.forEach(([docId, notes]) => {
    const d = notesCache.docs[docId];
    notes.sort((a, b) => a.pageNumber - b.pageNumber);
    box.append(h('div', { class: 'notes-book section' },
      h('h3', { onclick: () => { location.hash = `#/book/${docId}`; } }, notes[0].documentTitle),
      h('div', { class: 'muted' }, notes[0].documentAuthor, ` · ${notes.length} note${notes.length === 1 ? '' : 's'}`),
      notes.map(n => noteItem(n, d, () => showNotes()))));
  });
}

// ------------------------------------------------------------------ uploads (drag & drop)

let dragDepth = 0, dragOut = false;
function hasFiles(e) { return e.dataTransfer && [...(e.dataTransfer.types || [])].includes('Files'); }
window.addEventListener('dragenter', e => {
  if (dragOut || !hasFiles(e) || readerOpen || coverTake) return;
  tagCache = null;
  e.preventDefault(); dragDepth++;
  const image = state.currentDoc && [...(e.dataTransfer.items || [])].some(i => i.kind === 'file' && /^image\//.test(i.type));
  document.querySelector('#drop .drop-title').textContent = image ? `Drop to make this the cover of “${state.currentDoc.title}”` : state.uploadFolder ? `Drop to add chapters to “${state.uploadFolder.title}”` : 'Drop to add to the library';
  document.getElementById('drop-sub').textContent = state.uploadFolder ? 'Each file becomes a chapter, in the order dropped' : `Adds as ${state.category === 'ARTICLE' ? 'articles' : 'books'} · PDF · EPUB · DAISY · HTML · Markdown · text`;
  document.getElementById('drop').hidden = false;
});
window.addEventListener('dragover', e => { if (!dragOut && hasFiles(e)) { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; } });
window.addEventListener('dragleave', e => { if (!hasFiles(e)) return; dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) document.getElementById('drop').hidden = true; });
window.addEventListener('drop', e => {
  if (!hasFiles(e)) return;
  e.preventDefault(); dragDepth = 0; document.getElementById('drop').hidden = true;
  if (readerOpen) return;
  let files = [...e.dataTransfer.files];
  // An image dropped on a book's page (or into the cover dialog) becomes its cover.
  const image = files.find(isImageFile);
  if (coverTake) { if (image) coverTake(image); else toast('Drop an image file (JPEG, PNG, WebP…)'); return; }
  if (image && state.currentDoc) { changeCover(state.currentDoc, image); files = files.filter(f => !isImageFile(f)); }
  const ris = files.filter(f => /\.ris$/i.test(f.name));
  if (ris.length) {
    files = files.filter(f => !/\.ris$/i.test(f.name));
    if (state.currentBook) ris[0].text().then(async text => {
      try { const r = await api(`doc/${state.currentBook}/ris`, { method: 'POST', json: { text, overwrite: false } }); toast(`Filled ${r.risFilled || 0} fields from ${ris[0].name}`); showBook(state.currentBook); } catch (err) { fail(err); }
    });
    else toast('Open a book first, then drop its .ris file on its page');
  }
  if (files.length) uploadFiles(files);
});

const uploadQueue = [];
let uploading = false;
function uploadPanel() {
  const p = document.getElementById('uploads');
  if (!p.firstChild) {
    const cat = h('span', { class: 'row', style: { gap: '4px' } });
    const drawCat = () => clear(cat).append(
      h('button', { class: 'chip' + (state.category !== 'ARTICLE' ? ' on' : ''), onclick: () => { state.category = 'BOOK'; localSet('ml.uploadCategory', 'BOOK'); drawCat(); } }, 'Book'),
      h('button', { class: 'chip' + (state.category === 'ARTICLE' ? ' on' : ''), onclick: () => { state.category = 'ARTICLE'; localSet('ml.uploadCategory', 'ARTICLE'); drawCat(); } }, 'Article'));
    drawCat();
    p.append(h('div', { class: 'uh' }, 'Adding to the library', h('span', { class: 'spacer' }), cat,
      h('button', { class: 'btn quiet', style: { padding: '2px 6px', marginLeft: '4px' }, onclick: () => { if (!uploading) { clear(p); p.hidden = true; } } }, '×')),
      h('div', { id: 'uplist' }));
  }
  p.hidden = false;
  return document.getElementById('uplist');
}

function uploadFiles(files) {
  const list = uploadPanel();
  files.forEach(f => {
    const bar = h('i'), status = h('div', { class: 's' }, 'Waiting · ' + fmtSize(f.size));
    const item = h('div', { class: 'ui' }, h('div', { class: 'n' }, f.name), h('div', { class: 'p' }, bar), status);
    list.prepend(item);
    uploadQueue.push({ f, bar, status, folder: state.uploadFolder ? state.uploadFolder.id : null });
  });
  pump();
}

function pump() {
  if (uploading || !uploadQueue.length) return;
  uploading = true;
  const job = uploadQueue.shift();
  const xhr = new XMLHttpRequest();
  xhr.open('POST', `/api/upload?name=${encodeURIComponent(job.f.name)}&category=${state.category}${job.folder ? '&folder=' + job.folder : ''}`);
  xhr.setRequestHeader('X-Marginalia', '1');
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');
  xhr.upload.onprogress = e => {
    if (!e.lengthComputable) return;
    const p = e.loaded / e.total;
    job.bar.style.width = (p * 100).toFixed(1) + '%';
    job.status.textContent = p < 1 ? `Sending · ${pct(p)}% of ${fmtSize(e.total)}` : 'Reading title, author and cover on the tablet…';
  };
  xhr.onload = () => {
    let data = {}; try { data = JSON.parse(xhr.responseText); } catch (_) {}
    if (xhr.status === 401) { showLogin(); }
    if (xhr.status >= 200 && xhr.status < 300) {
      job.bar.style.width = '100%';
      job.status.textContent = `Added: ${data.title || job.f.name}${data.displayAuthor ? ' — ' + data.displayAuthor : ''}`;
      refreshAfterUpload();
    } else {
      job.bar.style.background = '#fff'; job.status.textContent = 'Failed: ' + (data.error || xhr.status);
    }
    uploading = false; pump(); hideUploadsLater();
  };
  xhr.onerror = () => { job.status.textContent = 'Failed: the tablet did not answer.'; uploading = false; pump(); };
  xhr.send(job.f);
}

let hideTimer = 0;
function hideUploadsLater() {
  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => { if (!uploading && !uploadQueue.length) { const p = document.getElementById('uploads'); clear(p); p.hidden = true; } }, 12000);
}

let refreshTimer = 0;
function refreshAfterUpload() {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(async () => {
    if (document.getElementById('shelf')) { try { await loadLibrary(); renderShelf(); } catch (_) {} }
    else if (state.uploadFolder && !uploading && !uploadQueue.length) { showBook(state.uploadFolder.id); }
  }, 400);
}

// ------------------------------------------------------------------ reader (shared)

let readerOpen = null;   // { kind, destroy(), ... }

let rsPoll = 0;
function closeReader() {
  clearInterval(rsPoll); rsPoll = 0;
  rsListeners.clear();
  if (readerOpen) { try { readerOpen.destroy(); } catch (_) {} readerOpen = null; }
  document.removeEventListener('keydown', readerKeys);
}

function readerKeys(e) {
  if (!readerOpen || e.target.closest('input, textarea, .modal')) return;
  if (e.key === 'ArrowRight' || e.key === 'PageDown') { readerOpen.next && readerOpen.next(); e.preventDefault(); }
  if (e.key === 'ArrowLeft' || e.key === 'PageUp') { readerOpen.prev && readerOpen.prev(); e.preventDefault(); }
  if (e.key === 'Escape' && !document.querySelector('.scrim')) history.back();
}

async function openReader(id, params) {
  const app = clear(document.getElementById('app'));
  const titleB = h('b', null, '…'), placeS = h('span', null, '');
  const side = h('aside', { class: 'rside', hidden: localGet('ml.side', '1') !== '1' });
  const mainArea = h('div', { class: 'rmain' }, h('div', { class: 'loading', id: 'rload' }, h('div', { class: 'spin' }), h('div', { id: 'rloadmsg' }, 'Opening the book…')));
  const status = h('div', { class: 'rstatus' });
  const zoomBox = h('span', { class: 'row', style: { gap: '4px' } });
  const sideTabs = { toc: null, notes: null };
  let sideMode = localGet('ml.sideMode', 'notes');
  const bar = h('div', { class: 'rbar' },
    h('button', { class: 'btn', onclick: () => { if (history.length > 1) history.back(); else location.hash = `#/book/${id}`; }, title: 'Back (Esc)' }, '←'),
    h('div', { class: 'rt' }, titleB, placeS),
    zoomBox,
    sideTabs.toc = h('button', { class: 'btn', onclick: () => toggleSide('toc') }, 'Contents'),
    sideTabs.notes = h('button', { class: 'btn', onclick: () => toggleSide('notes') }, 'Notes'));
  const readerRoot = h('div', { class: 'reader' }, bar, h('div', { class: 'rbody' }, mainArea, side), status);
  app.append(readerRoot);
  applyReaderChrome(readerRoot);
  document.addEventListener('keydown', readerKeys);

  let d;
  try { d = await api(`doc/${id}`); } catch (e) {
    fail(e);
    const m = document.getElementById('rloadmsg'); if (m) m.textContent = 'Could not open the book: ' + (e.message || e);
    const sp = document.querySelector('#rload .spin'); if (sp) sp.remove();
    return;
  }
  if (d.folder) { if (d.resumeId) location.replace(`#/read/${d.resumeId}`); else location.replace(`#/book/${d.id}`); return; }
  titleB.textContent = d.title;
  const nav = d.folderNav;
  if (nav) {
    bar.insertBefore(h('span', { class: 'row', style: { gap: '2px' } },
      h('button', { class: 'btn quiet', disabled: !nav.prev, title: nav.prev ? 'Previous chapter: ' + nav.prev.title : '', onclick: () => { location.hash = `#/read/${nav.prev.id}`; } }, '‹ Ch.'),
      h('a', { class: 'mono', href: `#/book/${nav.id}`, style: { fontSize: '11px', textDecoration: 'none' }, title: nav.title }, `${nav.number}/${nav.count}`),
      h('button', { class: 'btn quiet', disabled: !nav.next, title: nav.next ? 'Next chapter: ' + nav.next.title : '', onclick: () => { location.hash = `#/read/${nav.next.id}`; } }, 'Ch. ›')), zoomBox);
  }
  document.title = d.title + ' · Marginalia Lectoris';

  const ctx = {
    d, side, mainArea, status, zoomBox, placeS, nav,
    notes: (d.highlightList || []).slice(),
    tocItems: [],
    sideMode: () => sideMode,
    renderSide: null,
  };

  function toggleSide(mode) {
    if (!side.hidden && sideMode === mode) { side.hidden = true; localSet('ml.side', '0'); }
    else { side.hidden = false; sideMode = mode; localSet('ml.side', '1'); localSet('ml.sideMode', mode); }
    drawSide();
  }
  function drawSide() {
    sideTabs.toc.classList.toggle('fill', !side.hidden && sideMode === 'toc');
    sideTabs.notes.classList.toggle('fill', !side.hidden && sideMode === 'notes');
    if (side.hidden) return;
    clear(side);
    if (sideMode === 'toc') {
      side.append(h('h3', null, 'Contents'));
      const toc = h('div', { class: 'toc' });
      if (!ctx.tocItems.length) toc.append(h('div', { class: 'muted' }, 'This book has no table of contents.'));
      ctx.tocItems.forEach(t => toc.append(h('a', { class: 'l' + Math.min(2, t.level) + (t.current ? ' cur' : ''), onclick: () => t.go() }, t.title)));
      side.append(toc);
    } else {
      side.append(h('h3', null, 'Notes', h('span', { class: 'mono muted', style: { fontSize: '12px', fontWeight: 400, marginLeft: '6px' } }, ctx.notes.length)));
      if (!ctx.notes.length) side.append(h('div', { class: 'muted' }, 'Select text in the book to highlight it or add a note.'));
      ctx.notes.slice().sort((a, b) => a.pageNumber - b.pageNumber).forEach(n => {
        const ni = h('div', { class: 'nitem', onclick: () => readerOpen && readerOpen.goToNote(n) },
          h('div', { class: 'q', style: { borderLeftColor: n.colorHex || '#E4E4E7' } }, n.highlightedText),
          n.userNotes ? h('div', { style: { fontSize: '13px', marginTop: '3px', whiteSpace: 'pre-wrap' } }, n.userNotes.length > 220 ? n.userNotes.slice(0, 220) + '…' : n.userNotes) : null,
          h('div', { class: 'w' }, n.title, ' · ', noteLocus(n, d), ' · ', h('a', { href: 'javascript:void 0', onclick: e => { e.stopPropagation(); editNote(n, () => { ctx.refreshNotes(); }); } }, 'edit')));
        onContext(ni, () => noteMenu(n, d, () => ctx.refreshNotes(), () => readerOpen && readerOpen.goToNote(n)));
        side.append(ni);
      });
    }
  }
  ctx.renderSide = drawSide;
  ctx.refreshNotes = async () => {
    try { ctx.notes = await api(`doc/${id}/highlights`); } catch (e) { fail(e); }
    if (readerOpen && readerOpen.notesChanged) readerOpen.notesChanged();
    drawSide();
  };
  drawSide();

  await loadReaderSettings();
  applyReaderChrome(readerRoot);
  rsListeners.add(() => applyReaderChrome(readerRoot));
  clearInterval(rsPoll); rsPoll = setInterval(pollReaderSettings, 10000);
  if (isPdf(d) || isEpub(d)) zoomBox.append(readerSettingsButton(isPdf(d) ? 'pdf' : 'epub'));
  try {
    if (isPdf(d)) readerOpen = await openPdf(ctx, params);
    else if (isEpub(d)) readerOpen = await openEpub(ctx, params);
    else { document.getElementById('rloadmsg').textContent = 'This kind of file can only be read on the tablet.'; return; }
  } catch (e) {
    console.error(e);
    const msg = document.getElementById('rloadmsg'); if (msg) msg.textContent = 'Could not open the book: ' + (e.message || e);
    return;
  }
  const ld = document.getElementById('rload'); if (ld) ld.remove();
  drawSide();
  const hl = +params.get('hl');
  if (hl) { const n = ctx.notes.find(x => x.id === hl); if (n) setTimeout(() => readerOpen && readerOpen.goToNote(n), 300); }
}

/** At the end of a chapter of a folder book: the next chapter. */
function endOfChapterCard(ctx) {
  const nav = ctx.nav;
  if (!nav) return null;
  return h('div', { class: 'section', style: { background: '#fff', maxWidth: '560px', margin: '0 auto', width: 'calc(100% - 32px)' } },
    h('div', { class: 'label' }, `End of chapter ${nav.number} of ${nav.count} · ${nav.title}`),
    nav.next
      ? h('div', null, h('h2', { class: 'serif', style: { margin: '6px 0 2px' } }, nav.next.title), nav.next.author ? h('div', { class: 'serif', style: { fontStyle: 'italic' } }, nav.next.author) : null,
          h('div', { class: 'row', style: { marginTop: '10px' } }, h('button', { class: 'btn fill', onclick: () => { location.hash = `#/read/${nav.next.id}`; } }, 'Next chapter →'),
            h('a', { class: 'btn', href: `#/book/${nav.id}` }, 'All chapters')))
      : h('div', null, h('h2', { class: 'serif', style: { margin: '6px 0 8px' } }, 'That was the last chapter.'), h('a', { class: 'btn', href: `#/book/${nav.id}` }, 'Back to the book')));
}

// ------------------------------------------------------------------ reading settings (shared with the tablet)

const RS_DEFAULTS = { theme: 'WHITE', fontSizeSp: 18, fontFamily: 'SERIF', textAlign: 'JUSTIFY', lineSpacing: 1.6, marginDp: 24, topMarginDp: 16, bottomMarginDp: 16, paragraphStyle: 'BOTH', viewMode: 'PAGINATED', columns: 1, publisherStyles: true };
const RS_THEMES = {
  WHITE: { bg: '#ffffff', fg: '#18181b', hl: 'multiply' },
  CREAM: { bg: '#fbf0d9', fg: '#26211c', hl: 'multiply' },
  DARK: { bg: '#000000', fg: '#ffffff', hl: 'screen' },
};
const RS_FONTS = {
  SERIF: '"Iowan Old Style", "Palatino Linotype", Palatino, "Book Antiqua", Georgia, serif',
  SANS: 'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
  MONO: 'ui-monospace, "Cascadia Mono", Consolas, Menlo, monospace',
};
let RS = Object.assign({}, RS_DEFAULTS);
/** One page, two pages side by side like an open book, or vertical scrolling (EPUBs). */
const RS_VIEWS = [
  ['ONE', 'One page', '<svg viewBox="0 0 20 16" width="20" height="16"><rect x="5.5" y="1.5" width="9" height="13" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>', { viewMode: 'PAGINATED', columns: 1 }],
  ['TWO', 'Two pages', '<svg viewBox="0 0 20 16" width="20" height="16"><rect x="1.5" y="1.5" width="8" height="13" fill="none" stroke="currentColor" stroke-width="1.5"/><rect x="10.5" y="1.5" width="8" height="13" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>', { viewMode: 'PAGINATED', columns: 2 }],
  ['SCROLL', 'Scroll', '<svg viewBox="0 0 20 16" width="20" height="16"><path d="M4 3h9M4 6.5h9M4 10h9M4 13.5h6" stroke="currentColor" stroke-width="1.5"/><path d="M16.5 2v11.5M14.5 11.5l2 2.5 2-2.5" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>', { viewMode: 'VERTICAL_SCROLL' }],
];
function rsView() { return RS.viewMode === 'VERTICAL_SCROLL' ? 'SCROLL' : +RS.columns === 2 ? 'TWO' : 'ONE'; }
/** The quick switch in the reader bar. */
function viewSwitch() {
  const box = h('span', { class: 'vswitch', role: 'group', 'aria-label': 'Page view' });
  const draw = () => clear(box).append(...RS_VIEWS.map(([id, label, icon, patch]) =>
    h('button', { class: 'btn' + (rsView() === id ? ' fill' : ''), title: label, 'aria-label': label, 'aria-pressed': rsView() === id ? 'true' : 'false', onclick: () => setReaderSettings(patch) }, h('span', { class: 'vi', html: icon }))));
  draw();
  rsListeners.add(draw);
  return box;
}
let rsSaveTimer = null, rsChangedAt = 0;
const rsListeners = new Set();

async function loadReaderSettings() {
  try { RS = Object.assign({}, RS_DEFAULTS, await api('reader-settings')); } catch (_) {}
  return RS;
}
/** Changes settings here, applies them at once, and saves them on the tablet shortly after. */
function setReaderSettings(patch) {
  const before = Object.assign({}, RS);
  Object.assign(RS, patch);
  rsChangedAt = Date.now();
  rsListeners.forEach(fn => fn(RS, before));
  clearTimeout(rsSaveTimer);
  rsSaveTimer = setTimeout(() => api('reader-settings', { method: 'PUT', json: RS }).catch(fail), 450);
}
/** Picks up a change made on the tablet while the book is open here. */
async function pollReaderSettings() {
  if (Date.now() - rsChangedAt < 4000) return;
  try {
    const fresh = Object.assign({}, RS_DEFAULTS, await api('reader-settings'));
    if (JSON.stringify(fresh) === JSON.stringify(RS) || Date.now() - rsChangedAt < 4000) return;
    const before = RS; RS = fresh;
    rsListeners.forEach(fn => fn(RS, before));
  } catch (_) {}
}
function applyReaderChrome(root) {
  if (!root) return;
  root.classList.remove('rt-white', 'rt-cream', 'rt-dark');
  root.classList.add('rt-' + (RS.theme || 'WHITE').toLowerCase());
}

/** The “Aa” panel in the reader. [kind] is 'epub' or 'pdf' (PDFs only take the colour theme). */
function readerSettingsButton(kind) {
  const wrap = h('div', { class: 'menu' });
  const pop = h('div', { class: 'rspop', hidden: true });
  const btn = h('button', { class: 'btn', title: 'Reading settings (shared with the tablet)', onclick: e => { e.stopPropagation(); pop.hidden = !pop.hidden; if (!pop.hidden) draw(); } }, 'Aa');
  pop.addEventListener('click', e => e.stopPropagation());
  document.addEventListener('click', () => { pop.hidden = true; });
  const chips = (key, opts, disabled) => h('div', { class: 'row', style: { gap: '4px' } }, opts.map(([v, label]) =>
    h('button', { class: 'chip' + (String(RS[key]) === String(v) ? ' on' : ''), disabled: !!disabled, onclick: () => { setReaderSettings({ [key]: v }); draw(); } }, label)));
  const line = (label, el, note) => h('div', { class: 'rsline' }, h('span', { class: 'label' }, label), el, note ? h('div', { class: 'muted rsnote' }, note) : null);
  function draw() {
    clear(pop);
    const own = !!RS.publisherStyles;
    const scroll = RS.viewMode === 'VERTICAL_SCROLL';
    pop.append(h('div', { class: 'rshead' }, h('b', null, 'Reading settings'), h('span', { class: 'muted' }, 'Shared with the tablet')));
    pop.append(line('Theme', chips('theme', [['WHITE', 'White'], ['CREAM', 'Cream'], ['DARK', 'Black']])));
    if (kind !== 'epub') {
      pop.append(h('div', { class: 'muted rsnote' }, 'PDFs keep their own pages; the theme tints them like on the tablet. Text settings apply to EPUBs.'));
      return;
    }
    const size = h('span', { class: 'mono', style: { minWidth: '44px', textAlign: 'center' } }, Math.round(RS.fontSizeSp) + ' pt');
    pop.append(line('Text size', h('div', { class: 'row', style: { gap: '4px' } },
      h('button', { class: 'btn', onclick: () => { setReaderSettings({ fontSizeSp: Math.max(10, Math.round(RS.fontSizeSp) - 1) }); draw(); } }, 'A−'), size,
      h('button', { class: 'btn', onclick: () => { setReaderSettings({ fontSizeSp: Math.min(40, Math.round(RS.fontSizeSp) + 1) }); draw(); } }, 'A+'))));
    pop.append(line('View', h('div', { class: 'row', style: { gap: '4px' } }, RS_VIEWS.map(([id, label, icon, patch]) =>
      h('button', { class: 'chip vchip' + (rsView() === id ? ' on' : ''), onclick: () => { setReaderSettings(patch); draw(); } }, h('span', { class: 'vi', html: icon }), label))),
      rsView() === 'TWO' ? 'Two pages side by side, like an open book. A narrow window shows one page.' : rsView() === 'SCROLL' ? 'Scroll down through each chapter.' : ''));
    pop.append(line('Margins', chips('marginDp', [[12, 'Narrow'], [24, 'Normal'], [36, 'Wide'], [48, 'Widest']])));
    pop.append(line('Book’s own styles', chips('publisherStyles', [[true, 'On'], [false, 'Off']]),
      own ? 'The book’s fonts and spacing are kept. Turn off to use your font, alignment, line spacing and paragraphs.' : ''));
    pop.append(line('Font', chips('fontFamily', [['SERIF', 'Serif'], ['SANS', 'Sans'], ['MONO', 'Mono']], own)));
    pop.append(line('Line spacing', chips('lineSpacing', [[1.3, 'Tight'], [1.6, 'Normal'], [1.9, 'Loose']], own)));
    pop.append(line('Alignment', chips('textAlign', [['JUSTIFY', 'Justified'], ['LEFT', 'Left']], own)));
    pop.append(line('Paragraphs', chips('paragraphStyle', [['INDENT', 'Indent'], ['SPACED', 'Spaced'], ['BOTH', 'Both'], ['NONE', 'Plain']], own)));
  }
  wrap.append(btn, pop);
  return wrap;
}

/** The CSS put into every chapter of an EPUB, the way the tablet's reader sets it. */
function epubCss() {
  const th = RS_THEMES[RS.theme] || RS_THEMES.WHITE;
  let css = `html, body { background: ${th.bg} !important; color: ${th.fg} !important; }
    ::selection { background: ${RS.theme === 'DARK' ? 'rgba(120, 160, 255, .45)' : 'rgba(0, 60, 255, .25)'}; }`;
  if (RS.theme === 'DARK') css += ` a { color: #9ec1ff !important; } img, svg { background: #fff; }`;
  if (!RS.publisherStyles) {
    const [ind, sp] = { INDENT: [1.25, 0], SPACED: [0, 0.65], BOTH: [1.25, 0.5], NONE: [0, 0] }[RS.paragraphStyle] || [1.25, 0.5];
    css += `
      body, p, div, li, blockquote, dd, dt, td, th, span, a, em, i, b, strong, h1, h2, h3, h4, h5, h6 { font-family: ${RS_FONTS[RS.fontFamily] || RS_FONTS.SERIF} !important; }
      body, p, li, blockquote, dd, td { line-height: ${RS.lineSpacing} !important; }
      p, li, blockquote, dd { text-align: ${RS.textAlign === 'LEFT' ? 'left' : 'justify'} !important; hyphens: auto; -webkit-hyphens: auto; }
      p { text-indent: ${ind}em !important; margin-top: 0 !important; margin-bottom: ${sp}em !important; }
      h1 + p, h2 + p, h3 + p, h4 + p, h5 + p, h6 + p, hr + p, blockquote + p, p:first-child { text-indent: 0 !important; }`;
  }
  return css;
}

// ------------------------------------------------------------------ Set as …

/**
 * “Set as ▾” on the selection bar: the selected text becomes a chapter's title, author, abstract
 * or tags, the book's editor or tags, or one of its bibliographic details — as on the tablet.
 */
function setAsButton(ctx, sel, onDone) {
  const wrap = h('div', { class: 'menu' });
  const pop = h('div', { class: 'setpop', hidden: true });
  const btn = h('button', { class: 'btn', onmousedown: e => e.preventDefault(), onclick: e => { e.stopPropagation(); if (pop.hidden) open(); else pop.hidden = true; } }, 'Set as ▾');
  pop.addEventListener('mousedown', e => e.preventDefault());
  pop.addEventListener('click', e => e.stopPropagation());
  document.addEventListener('click', () => { pop.hidden = true; });
  let pv = null;
  async function open() {
    pop.hidden = false;
    clear(pop).append(h('div', { class: 'muted', style: { padding: '12px' } }, 'Reading the selection…'));
    try { pv = await api(`doc/${ctx.d.id}/set-as/preview`, { method: 'POST', json: Object.assign({ text: sel.text }, sel.loc || {}) }); }
    catch (e) { clear(pop).append(h('div', { class: 'muted', style: { padding: '12px' } }, e.message || String(e))); return; }
    draw();
  }
  const item = (label, value, onPick, hint) => h('button', { class: 'setitem' + (value ? '' : ' none'), onclick: onPick },
    h('b', null, label), h('span', null, value ? value.slice(0, 70) + (value.length > 70 ? '…' : '') : (hint || 'type it in')));
  function draw() {
    clear(pop);
    const f = Object.fromEntries((pv.fields || []).map(x => [x.key, x]));
    const ch = pv.chapter;
    const g = pv.guess || {};
    const pick = (target, label, value, current, opts = {}) => () => { pop.hidden = true; setAsConfirm(ctx, sel, pv, Object.assign({ target, label, value, current }, opts), onDone); };
    if (pv.isBook) {
      pop.append(h('div', { class: 'sethead' }, h('span', { class: 'label' }, 'This chapter'),
        h('span', { class: 'muted' }, ch ? [ch.label, ch.title].filter(Boolean).join('. ') + (ch.authors ? ' · ' + ch.authors.split(';').map(x => x.trim()).join(', ') : ' · author not set')
          : (pv.chapterHint ? `“${pv.chapterHint}” is not in the chapter list yet` : 'No chapter list yet — one is made'))));
      pop.append(h('div', { class: 'setgrid' },
        item('Chapter title', g.title, pick('chapter:title', 'Chapter title', g.title, ch && ch.title)),
        item('Chapter author', g.author, pick('chapter:author', 'Chapter author(s)', g.author, ch && ch.authors, { adds: ch && ch.authorsAdd })),
        item('Chapter abstract', g.abstract, pick('chapter:abstract', 'Chapter abstract', g.abstract, ch && ch.abstract, { long: true })),
        item('Chapter tags', (pv.tags || []).join(', '), pick('chapter:tags', 'Chapter tags', (pv.tags || []).join(', '), ch && ch.tags, { tags: true })),
        item('Book editor', f.EDITOR && f.EDITOR.value, pick('EDITOR', 'Book editor(s)', f.EDITOR && f.EDITOR.value, f.EDITOR && f.EDITOR.current, { adds: true }))));
    }
    pop.append(h('div', { class: 'sethead' }, h('span', { class: 'label' }, pv.isBook ? 'The book' : 'This article')));
    const order = ['TITLE', 'SUBTITLE', 'YEAR', 'DOI', 'ISBN', 'ISSN', 'PUBLISHER', 'PLACE', 'EDITION', 'JOURNAL', 'BOOKTITLE', 'VOLUME', 'ISSUE', 'PAGES', 'SERIES', 'URL', 'LANGUAGE', 'TRANSLATOR'].concat(pv.isBook ? [] : ['EDITOR']);
    pop.append(h('div', { class: 'setgrid' },
      item('Tags', (pv.tags || []).join(', '), pick('tags', 'Tags of the book', (pv.tags || []).join(', '), pv.bookTags, { tags: true })),
      order.filter(k => f[k]).map(k => item(f[k].label, f[k].value, pick(k, f[k].label, f[k].value, f[k].current, { long: k === 'TITLE' }), 'not found in the selection'))));
  }
  wrap.append(btn, pop);
  return wrap;
}

function setAsConfirm(ctx, sel, pv, opt, onDone) {
  modal((box, close) => {
    const input = opt.long ? h('textarea', { rows: 4 }, opt.value || '') : h('input', { value: opt.value || '' });
    let off = new Set();
    const tagBox = h('div', { class: 'row', style: { gap: '4px' } });
    const tagList = () => input.value.split(/[,;·•|]/).map(x => x.trim()).filter(Boolean);
    const drawTags = () => {
      clear(tagBox);
      tagList().forEach(t => tagBox.append(h('button', { class: 'chip' + (off.has(t) ? '' : ' on'), type: 'button', title: off.has(t) ? 'Left out' : 'Click to leave out',
        onclick: () => { if (off.has(t)) off.delete(t); else off.add(t); drawTags(); } }, '#' + t)));
    };
    if (opt.tags) { input.addEventListener('input', drawTags); drawTags(); }
    const cur = (opt.current || '').trim();
    const note = opt.tags ? (cur ? `Already: ${cur}` : '') : cur ? `${opt.adds ? 'Adds to' : 'Replaces'}: ${cur.split(';').map(x => x.trim()).join(', ')}` : '';
    const save = async () => {
      const value = opt.tags ? tagList().filter(t => !off.has(t)).join(', ') : input.value.trim();
      if (!value) { input.focus(); return; }
      try {
        const r = await api(`doc/${ctx.d.id}/set-as`, { method: 'POST', json: Object.assign({ target: opt.target, value, text: sel.text }, sel.loc || {}) });
        close(); toast(r.message || 'Saved'); tagCache = null; onDone(true);
      } catch (e) { fail(e); }
    };
    input.addEventListener('keydown', e => { if (e.key === 'Enter' && (!opt.long || e.ctrlKey || e.metaKey)) { e.preventDefault(); save(); } });
    box.append(...[
      h('h2', null, 'Set as ' + opt.label.toLowerCase()),
      h('div', { class: 'quote' }, sel.text.length > 300 ? sel.text.slice(0, 300) + '…' : sel.text),
      h('label', { class: 'fields', style: { marginTop: '10px' } }, h('span', { class: 'label' }, opt.label), input),
      opt.tags ? h('div', { style: { marginTop: '8px' } }, h('div', { class: 'muted', style: { fontSize: '12px', marginBottom: '4px' } }, 'Click a tag to leave it out'), tagBox) : null,
      note ? h('div', { class: 'muted', style: { fontSize: '12px', marginTop: '6px' } }, note) : null,
      !opt.value && !opt.tags ? h('div', { class: 'muted', style: { fontSize: '12px', marginTop: '6px' } }, 'Could not find it in the selection — type it here.') : null,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save'))].filter(Boolean));
  });
}

/** The bar shown while text is selected: highlight in a colour, or write a note. */
function selectionBar(ctx, sel, onDone) {
  const old = ctx.mainArea.querySelector('.selbar'); if (old) old.remove();
  ctx.sel = sel || null; ctx.selDone = sel ? onDone : null;
  if (!sel) return;
  let color = localGet('ml.color', '#FFEB3B');
  const quick = async c => {
    color = c; localSet('ml.color', c);
    try { await sel.save({ colorHex: c }); toast('Highlighted'); onDone(); } catch (e) { fail(e); }
  };
  const bar = h('div', { class: 'selbar' },
    h('span', { class: 'sq' }, '“' + sel.text.slice(0, 80) + (sel.text.length > 80 ? '…' : '') + '”'),
    h('div', { class: 'swatches', title: 'Highlight in this colour' }, COLORS.slice(0, 7).map(([name, hex]) => h('button', { title: 'Highlight · ' + name, style: { background: hex }, onmousedown: e => e.preventDefault(), onclick: () => quick(hex) }))),
    h('button', { class: 'btn fill', onmousedown: e => e.preventDefault(), onclick: () => noteDialog(ctx, sel, color, onDone) }, 'Note…'),
    h('button', { class: 'btn', onmousedown: e => e.preventDefault(), onclick: () => copyText(sel.text, 'Copied') }, 'Copy'),
    setAsButton(ctx, sel, onDone),
    h('button', { class: 'btn quiet', onmousedown: e => e.preventDefault(), onclick: () => { onDone(true); } }, '×'));
  ctx.mainArea.append(bar);
}

function noteDialog(ctx, sel, color, onDone) {
  modal((box, close) => {
    const t = h('input', { placeholder: 'Leave empty for an automatic title' }), body = h('textarea', { rows: 7, placeholder: 'Your thoughts…' }), g = tagInput('', true);
    const save = async () => {
      try { await sel.save({ title: t.value, userNotes: body.value, tags: g.value, colorHex: color }); tagCache = null; close(); toast('Note saved'); onDone(); }
      catch (e) { fail(e); }
    };
    body.addEventListener('keydown', e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) save(); });
    box.append(h('h2', null, 'New note'), h('div', { class: 'fields' },
      h('div', { class: 'quote' }, sel.text),
      h('div', { class: 'label' }, sel.where || ''),
      h('label', null, h('span', { class: 'label' }, 'Title'), t),
      h('label', null, h('span', { class: 'label' }, 'Note'), body),
      h('div', { class: 'tagwrap' }, h('span', { class: 'label' }, 'Tags'), g._widget),
      h('div', null, h('div', { class: 'label', style: { marginBottom: '4px' } }, 'Colour'), swatches(color, c => { color = c; localSet('ml.color', c); }))),
      h('div', { class: 'foot' }, h('span', { class: 'muted', style: { marginRight: 'auto', fontSize: '12px' } }, 'Ctrl+Enter saves'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save note')));
  });
}

const LETTER = /[\p{L}\p{N}]/u;
/** Finds [quote] in [flat] (both reduced to lower-case letters and digits), allowing small differences
 *  in the middle; [continued] also accepts a note that began on the previous page. */
function findLoose(flat, quote, continued) {
  let q = ''; for (const ch of (quote || '').toLowerCase()) if (LETTER.test(ch)) q += ch;
  if (q.length < 3 || !flat) return null;
  let start = flat.indexOf(q);
  if (start >= 0) return [start, start + q.length];
  const head = q.slice(0, Math.min(36, q.length)), tail = q.slice(-Math.min(28, q.length));
  start = flat.indexOf(head);
  if (start >= 0) {
    const t = flat.indexOf(tail, start + head.length);
    const end = t >= 0 && t - start < q.length * 1.3 + 40 ? t + tail.length : Math.min(flat.length, start + q.length);
    return [start, end];
  }
  if (continued) { const t = flat.indexOf(tail); if (t >= 0 && t < q.length * 1.3 + 40) return [0, t + tail.length]; }
  return null;
}

function cleanSelected(text) {
  return text.replace(/­/g, '').replace(/(\p{Ll})-\s*\n\s*(\p{Ll})/gu, '$1$2').replace(/\s+/g, ' ').trim();
}

// ------------------------------------------------------------------ PDF reader

async function openPdf(ctx, params) {
  const d = ctx.d;
  pdfjsLib.GlobalWorkerOptions.workerSrc = 'lib/pdf.worker.min.js';
  // Only the pieces of the file that are needed are fetched (small ranges, no full download), so a
  // large book opens at its page quickly. The tablet serves a reordered copy of large PDFs when ready.
  const task = pdfjsLib.getDocument({
    url: `/api/doc/${d.id}/file${d.webCopy ? '?web=1' : ''}`, withCredentials: true,
    rangeChunkSize: 65536, disableAutoFetch: true, disableStream: true
  });
  const pdf = await task.promise;
  const labels = await pdf.getPageLabels().catch(() => null);
  const n = pdf.numPages;
  const scroll = h('div', { class: 'pdfscroll' }), pagesEl = h('div', { class: 'pdfpages' });
  scroll.append(pagesEl);
  ctx.mainArea.append(scroll);

  let zoom = parseFloat(localGet('ml.zoom', '0')) || 0; // 0 = fit width
  const first = await pdf.getPage(1);
  const baseVp = first.getViewport({ scale: 1 });
  const fitScale = () => Math.min(3, Math.max(0.4, (scroll.clientWidth - 40) / baseVp.width));
  const scale = () => zoom || Math.min(fitScale(), 1.8);

  // Text mode, per-page fixes and OCR language (shared with the tablet). Pages that use OCR or a
  // repair get their text from the tablet, so they can be selected and highlighted here too.
  let tcfg = await api(`doc/${d.id}/text-config`).catch(() => null);
  let cfgVersion = 0;
  const tCache = new Map(); // page -> the tablet's text for it
  const needsTablet = i => { if (!tcfg) return false; const o = (tcfg.overrides || {})[i]; if (o) return o !== 'ORIGINAL'; return tcfg.mode !== 'ORIGINAL'; };
  const badge = (el, text, busy) => {
    let b = el.querySelector('.tsrc');
    if (!text) { if (b) b.remove(); return; }
    if (!b) { b = h('span', { class: 'tsrc' }); el.append(b); }
    b.textContent = text; b.classList.toggle('busy', !!busy);
  };
  /** Replaces the page's text layer with the words the tablet read (OCR or repaired), placed on the page. */
  async function applyTabletText(i, el, textDiv) {
    const ver = cfgVersion;
    let r = tCache.get(i);
    if (!r) {
      badge(el, 'Reading the text on the tablet…', true);
      try { r = await api(`doc/${d.id}/page-text?page=${i}`, { timeout: 180000 }); }
      catch (e) { badge(el, 'Text: ' + (e.message || 'failed')); return; }
      if (ver !== cfgVersion) return;
      tCache.set(i, r);
    }
    if (!textDiv.isConnected) return;
    if (r.same) { badge(el, null); return; }
    const W = el.clientWidth, H = el.clientHeight;
    clear(textDiv);
    const divs = [], strs = [];
    (r.words || []).forEach(([t, l, tp, rr, b]) => {
      if (!t) return;
      const sp = document.createElement('span');
      sp.textContent = t;
      const hh = Math.max(3, (b - tp) * H);
      sp.style.left = (l * W) + 'px'; sp.style.top = (tp * H) + 'px';
      sp.style.fontSize = (hh * 0.9) + 'px'; sp.style.fontFamily = 'sans-serif';
      sp._w = Math.max(1, (rr - l) * W);
      textDiv.append(sp); divs.push(sp);
    });
    const widths = divs.map(sp => sp.getBoundingClientRect().width);
    divs.forEach((sp, k) => {
      if (widths[k] > 0) sp.style.transform = `scaleX(${sp._w / widths[k]})`;
      sp.textContent = sp.textContent + ' ';
      strs.push(sp.textContent);
    });
    textDiv.classList.add('tablet');
    el._text = { divs, strs };
    paintHighlights(i);
    badge(el, r.source);
  }

  const pages = [];
  const rendered = new Map(); // index -> { scale, task }
  for (let i = 1; i <= n; i++) {
    const el = h('div', { class: 'ppage', 'data-page': i }, h('span', { class: 'pl' }, labels && labels[i - 1] && labels[i - 1] !== String(i) ? `${labels[i - 1]} · PDF ${i}` : `${i}`));
    pages.push(el);
    pagesEl.append(el);
  }
  const endCard = endOfChapterCard(ctx);
  if (endCard) pagesEl.append(endCard);
  const sizeAll = () => {
    const s = scale();
    pages.forEach(el => { el.style.width = Math.floor(baseVp.width * s) + 'px'; el.style.height = Math.floor(baseVp.height * s) + 'px'; });
  };
  sizeAll();

  async function renderPage(i) {
    const el = pages[i - 1];
    const s = scale();
    const have = rendered.get(i);
    if (have && have.scale === s) return;
    rendered.set(i, { scale: s });
    const page = await pdf.getPage(i);
    const vp = page.getViewport({ scale: s });
    el.style.width = Math.floor(vp.width) + 'px'; el.style.height = Math.floor(vp.height) + 'px';
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const canvas = h('canvas');
    canvas.width = Math.floor(vp.width * dpr); canvas.height = Math.floor(vp.height * dpr);
    canvas.style.width = Math.floor(vp.width) + 'px'; canvas.style.height = Math.floor(vp.height) + 'px';
    const textDiv = h('div', { class: 'textLayer' });
    textDiv.style.setProperty('--scale-factor', vp.scale);
    const hlDiv = h('div', { class: 'hl-layer' });
    await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp, transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : null }).promise;
    if (rendered.get(i)?.scale !== s) return;
    [...el.querySelectorAll('canvas, .textLayer, .hl-layer')].forEach(x => x.remove());
    el.append(canvas, hlDiv, textDiv);
    const tc = await page.getTextContent();
    const textDivs = [], strs = [];
    await pdfjsLib.renderTextLayer({ textContentSource: tc, container: textDiv, viewport: vp, textDivs, textContentItemsStr: strs }).promise;
    el._text = { divs: textDivs, strs };
    paintHighlights(i);
    if (needsTablet(i)) applyTabletText(i, el, textDiv).catch(console.error); else badge(el, null);
  }

  function unrender(i) {
    const el = pages[i - 1];
    if (!rendered.has(i)) return;
    rendered.delete(i);
    [...el.querySelectorAll('canvas, .textLayer, .hl-layer')].forEach(x => x.remove());
    el._text = null;
  }

  /** Marks a note's words on the page by finding its text in the page's text layer. */
  function paintHighlights(i) {
    const el = pages[i - 1];
    const layer = el.querySelector('.hl-layer');
    if (!layer || !el._text) return;
    clear(layer);
    const notes = ctx.notes.filter(x => x.pageNumber === i || (x.pageNumber === i - 1 && x.printedPageLabel && /[–-]/.test(x.printedPageLabel)));
    if (!notes.length) return;
    let flat = ''; const owner = [];
    el._text.strs.forEach((str, k) => { for (const ch of str.toLowerCase()) { if (LETTER.test(ch)) { flat += ch; owner.push(k); } } });
    const base = el.getBoundingClientRect();
    notes.forEach(note => {
      const span = findLoose(flat, note.highlightedText, note.pageNumber !== i);
      if (!span) return;
      const [start, end] = span;
      const ks = new Set(); for (let p = start; p < end && p < owner.length; p++) ks.add(owner[p]);
      ks.forEach(k => {
        const div = el._text.divs[k]; if (!div || !div.textContent.trim()) return;
        const r = div.getBoundingClientRect();
        layer.append(h('div', { style: { left: (r.left - base.left) + 'px', top: (r.top - base.top) + 'px', width: r.width + 'px', height: r.height + 'px', background: note.colorHex || '#FFEB3B' }, title: note.title }));
      });
    });
  }

  const visible = new Set();
  const io = new IntersectionObserver(entries => {
    entries.forEach(en => { const i = +en.target.dataset.page; if (en.isIntersecting) visible.add(i); else visible.delete(i); });
    schedule();
  }, { root: scroll, rootMargin: '600px 0px' });
  let pending = 0;
  function schedule() {
    clearTimeout(pending);
    pending = setTimeout(() => {
      const want = [...visible].sort((a, b) => a - b);
      want.forEach(i => renderPage(i).catch(console.error));
      [...rendered.keys()].forEach(i => { if (!visible.has(i) && Math.abs(i - current) > 6) unrender(i); });
    }, 60);
  }

  let current = 1;
  const slider = h('input', { type: 'range', min: 1, max: n, value: 1 });
  const where = h('span', null, '');
  const chapterOf = p => { let t = ''; ctx.tocItems.forEach(x => { if (x.page <= p) t = x.title; }); return t; };
  function updatePlace() {
    const mid = scroll.scrollTop + scroll.clientHeight * 0.35;
    let p = 1;
    for (const el of pages) { if (el.offsetTop <= mid) p = +el.dataset.page; else break; }
    if (p !== current) { current = p; slider.value = p; saveProgress(); }
    const lab = labels && labels[p - 1];
    where.textContent = `${lab && lab !== String(p) ? 'p. ' + lab + ' · ' : ''}PDF ${p} / ${n}`;
    ctx.placeS.textContent = [chapterOf(p), `PDF page ${p} of ${n}`].filter(Boolean).join(' · ');
    ctx.tocItems.forEach(t => { t.current = false; });
    const cur = ctx.tocItems.filter(t => t.page <= p).pop(); if (cur) cur.current = true;
  }
  let progTimer = 0;
  function saveProgress() {
    clearTimeout(progTimer);
    progTimer = setTimeout(() => { api(`doc/${d.id}/progress`, { method: 'POST', json: { page: current, total: n } }).catch(() => {}); }, 2500);
  }
  scroll.addEventListener('scroll', () => { updatePlace(); }, { passive: true });
  slider.addEventListener('change', () => goPage(+slider.value));
  function goPage(p, smooth) { const el = pages[Math.max(1, Math.min(n, p)) - 1]; scroll.scrollTo({ top: el.offsetTop - 10, behavior: smooth ? 'smooth' : 'auto' }); }

  const zl = h('span', { class: 'mono', style: { fontSize: '11px', minWidth: '44px', textAlign: 'center' } });
  const setZoom = z => {
    const keep = current;
    zoom = z; localSet('ml.zoom', String(z));
    zl.textContent = Math.round(scale() * 100) + '%';
    sizeAll(); rendered.clear();
    pages.forEach((el, k) => { [...el.querySelectorAll('canvas, .textLayer, .hl-layer')].forEach(x => x.remove()); el._text = null; });
    goPage(keep); schedule();
  };
  ctx.zoomBox.append(
    h('button', { class: 'btn quiet', title: 'Smaller', onclick: () => setZoom(Math.max(0.4, +(scale() - 0.15).toFixed(2))) }, '−'), zl,
    h('button', { class: 'btn quiet', title: 'Larger', onclick: () => setZoom(Math.min(3, +(scale() + 0.15).toFixed(2))) }, '+'),
    h('button', { class: 'btn quiet', title: 'Fit the width', onclick: () => setZoom(0) }, 'Fit'));
  zl.textContent = Math.round(scale() * 100) + '%';
  ctx.status.append(where, slider, h('span', null, d.fileType));
  if (tcfg) ctx.zoomBox.prepend(pdfTextButton());

  /** “Text & OCR”: the book's text mode, OCR language, a fix for this page, a page check and exports. */
  function pdfTextButton() {
    const wrap = h('div', { class: 'menu' });
    const pop = h('div', { class: 'rspop tpop', hidden: true });
    const btn = h('button', { class: 'btn', title: 'Text & OCR (shared with the tablet)', onclick: e => { e.stopPropagation(); pop.hidden = !pop.hidden; if (!pop.hidden) { report = null; draw(); } } }, 'Text');
    pop.addEventListener('click', e => e.stopPropagation());
    document.addEventListener('click', () => { pop.hidden = true; });
    let report = null, checking = false, saving = false;
    const save = async patch => {
      saving = true; draw();
      try { tcfg = await api(`doc/${d.id}/text-config`, { method: 'PUT', json: patch }); cfgVersion++; tCache.clear(); setZoom(zoom); toast('Text setting saved — the tablet uses it too'); }
      catch (e) { fail(e); }
      saving = false; draw();
    };
    const line = (label, el, note) => h('div', { class: 'rsline' }, h('span', { class: 'label' }, label), el, note ? h('div', { class: 'muted rsnote' }, note) : null);
    function draw() {
      clear(pop);
      const page = current;
      const mode = (tcfg.modes || []).find(m => m.id === tcfg.mode) || {};
      const pageOpt = (tcfg.overrides || {})[page] || 'FOLLOW_DOCUMENT';
      const opt = (tcfg.pageOptions || []).find(o => o.id === pageOpt) || {};
      pop.append(h('div', { class: 'rshead' }, h('b', null, 'Text & OCR'), h('span', { class: 'muted' }, 'Shared with the tablet')));
      pop.append(line('Text of this book', h('div', { class: 'row', style: { gap: '4px' } }, (tcfg.modes || []).map(m =>
        h('button', { class: 'chip' + (m.id === tcfg.mode ? ' on' : ''), disabled: saving, title: m.description, onclick: () => save({ mode: m.id }) }, m.label))), mode.description));
      const lang = h('select', { disabled: saving }, (tcfg.languages || []).map(l => h('option', { value: l.code }, l.label)));
      lang.value = tcfg.ocrLanguage || 'AUTO';
      lang.addEventListener('change', () => save({ ocrLanguage: lang.value }));
      pop.append(line('OCR language', lang));
      const sel = h('select', { disabled: saving }, (tcfg.pageOptions || []).map(o => h('option', { value: o.id }, o.id === 'FOLLOW_DOCUMENT' ? 'Same as the book' : o.title)));
      sel.value = pageOpt;
      sel.addEventListener('change', () => save({ page, override: sel.value }));
      const fixed = Object.keys(tcfg.overrides || {}).length;
      pop.append(line(`This page · PDF ${page}`, sel, pageOpt !== 'FOLLOW_DOCUMENT' ? opt.description : (fixed ? `${fixed} page${fixed === 1 ? ' has' : 's have'} its own fix.` : '')));
      const checkBtn = h('button', { class: 'btn', disabled: checking, onclick: async () => {
        checking = true; report = null; draw();
        try { report = await api(`doc/${d.id}/page-check?page=${page}`, { timeout: 120000 }); } catch (e) { report = { error: e.message || String(e) }; }
        checking = false; draw();
      } }, checking ? 'Checking…' : 'Check this page');
      pop.append(h('div', { class: 'row' }, checkBtn, h('span', { class: 'muted', style: { fontSize: '12px' } }, 'The tablet looks at the page and its text.')));
      if (report && report.error) pop.append(h('div', { class: 'warn' }, report.error));
      else if (report) {
        const rec = report.recommended || {};
        pop.append(h('div', { class: 'tcheck' },
          h('div', { class: 'tclass tc-' + ((report.classification || {}).id || '').toLowerCase() }, (report.classification || {}).title || ''),
          h('div', { class: 'rsnote' }, report.summary || (report.classification || {}).advice || ''),
          h('div', { class: 'muted rsnote' }, `${report.words} word${report.words === 1 ? '' : 's'} in the PDF's own text · now using: ${report.activeSource || '—'}`),
          (report.issues || []).length ? h('ul', { class: 'tissues' }, report.issues.map(x => h('li', null, h('b', null, x.title), h('div', { class: 'muted' }, x.explanation)))) : null,
          report.sampleOriginal || report.sampleRepaired ? h('details', null, h('summary', { class: 'label', style: { cursor: 'pointer' } }, 'Sample text'),
            report.sampleOriginal ? h('div', null, h('div', { class: 'label' }, 'As in the PDF'), h('pre', { class: 'bibtex' }, report.sampleOriginal)) : null,
            report.sampleRepaired && report.sampleRepaired !== report.sampleOriginal ? h('div', null, h('div', { class: 'label' }, 'Repaired'), h('pre', { class: 'bibtex' }, report.sampleRepaired)) : null) : null,
          rec.id && rec.id !== 'FOLLOW_DOCUMENT' && rec.id !== pageOpt ? h('div', { class: 'row' }, h('span', { class: 'rsnote' }, 'Recommended: ', h('b', null, rec.title || rec.label)),
            h('button', { class: 'btn fill', disabled: saving, onclick: () => save({ page, override: rec.id }) }, 'Use it for this page')) : null));
      }
      pop.append(line('Export the text', h('div', { class: 'row', style: { gap: '4px' } },
        h('button', { class: 'btn', onclick: () => download(`doc/${d.id}/export/clean-text`, 'Reading every page on the tablet…') }, 'Clean text (.txt)'),
        h('button', { class: 'btn', onclick: () => download(`doc/${d.id}/export/searchable-pdf`, 'Making a searchable PDF on the tablet…') }, 'Searchable PDF')),
        'Uses the settings above. OCR runs on the tablet, so a scanned book can take a few minutes.'));
    }
    wrap.append(btn, pop);
    return wrap;
  }
  let resizeT = 0;
  const onResize = () => { clearTimeout(resizeT); resizeT = setTimeout(() => { if (!zoom) setZoom(0); }, 250); };
  window.addEventListener('resize', onResize);

  // Contents from the PDF's bookmarks (read in the background, so opening does not wait for it)
  (async () => { try {
    const outline = await pdf.getOutline();
    const items = [];
    const walk = async (list, level) => {
      for (const it of list || []) {
        let page = null;
        try {
          const dest = typeof it.dest === 'string' ? await pdf.getDestination(it.dest) : it.dest;
          if (dest && dest[0]) page = (await pdf.getPageIndex(dest[0])) + 1;
        } catch (_) {}
        if (page) items.push({ title: it.title, level, page, go: () => goPage(page) });
        if (it.items && it.items.length && level < 3) await walk(it.items, level + 1);
      }
    };
    await walk(outline, 0);
    ctx.tocItems = items;
    updatePlace(); ctx.renderSide();
  } catch (_) {} })();

  // Selection → bar
  let selTimer = 0;
  const onSel = () => {
    clearTimeout(selTimer);
    selTimer = setTimeout(() => {
      const s = window.getSelection();
      if (!s || s.isCollapsed || !scroll.contains(s.anchorNode)) { if (!document.querySelector('.scrim')) selectionBar(ctx, null); return; }
      const text = cleanSelected(s.toString());
      if (text.length < 2) return;
      const pageOf = node => { const el = (node.nodeType === 1 ? node : node.parentElement).closest('.ppage'); return el ? +el.dataset.page : current; };
      let a = pageOf(s.anchorNode), b = pageOf(s.focusNode); if (a > b) [a, b] = [b, a];
      const lab = labels && labels[a - 1];
      const labB = labels && labels[b - 1];
      const printedOk = x => x && /\d|^[ivxlc]+$/i.test(x);
      selectionBar(ctx, {
        text,
        pageRef: printedOk(lab) && lab !== String(a) ? (b > a && printedOk(labB) ? `${lab}–${labB}` : lab) : (b > a ? `${a}–${b}` : String(a)),
        where: `${chapterOf(a) ? chapterOf(a) + ' · ' : ''}PDF page ${a}${b > a ? '–' + b : ''}`,
        loc: { page: a, chapter: chapterOf(a) },
        save: async extra => {
          const r = await api(`doc/${d.id}/highlights`, { method: 'POST', json: Object.assign({ text, page: a, endPage: b, chapter: chapterOf(a), printedLabel: lab && /\d|^[ivxlc]+$/i.test(lab) && lab !== String(a) ? lab : '' }, extra) });
          ctx.notes.push(r);
          return r;
        }
      }, cancelled => { window.getSelection().removeAllRanges(); selectionBar(ctx, null); if (!cancelled) { [a, b].forEach(paintHighlights); ctx.renderSide(); } });
    }, 250);
  };
  document.addEventListener('selectionchange', onSel);

  ctx.pageCount = n;
  ctx.goToPage = p => goPage(p);
  let menuPage = 0;
  ctx.place = () => {
    const p = menuPage || current;
    const lab = labels && labels[p - 1];
    return { page: p, label: lab && lab !== String(p) && /\d|^[ivxlc]+$/i.test(lab) ? lab : '', chapter: chapterOf(p), loc: { page: p, chapter: chapterOf(p) } };
  };
  onContext(scroll, e => {
    const pg = e.target.closest && e.target.closest('.ppage');
    menuPage = pg ? +pg.dataset.page : current;
    const s = window.getSelection();
    let inSel = false;
    if (s && !s.isCollapsed && ctx.sel) {
      for (let i = 0; i < s.rangeCount; i++) {
        for (const r of s.getRangeAt(i).getClientRects()) if (e.clientX >= r.left - 2 && e.clientX <= r.right + 2 && e.clientY >= r.top - 2 && e.clientY <= r.bottom + 2) inSel = true;
      }
    }
    return readerContext(ctx, e, inSel);
  });

  const startPage = +params.get('page') || d.currentPage || 1;
  requestAnimationFrame(() => { goPage(startPage); updatePlace(); pages.forEach(p => io.observe(p)); });

  return {
    kind: 'pdf',
    next: () => goPage(current + 1, true),
    prev: () => goPage(current - 1, true),
    goToNote: note => { goPage(note.pageNumber, true); },
    notesChanged: () => { [...rendered.keys()].forEach(paintHighlights); },
    destroy: () => {
      document.removeEventListener('selectionchange', onSel);
      window.removeEventListener('resize', onResize);
      io.disconnect();
      clearTimeout(progTimer);
      api(`doc/${d.id}/progress`, { method: 'POST', json: { page: current, total: n } }).catch(() => {});
      pdf.destroy();
    }
  };
}

// ------------------------------------------------------------------ EPUB reader

async function openEpub(ctx, params) {
  const d = ctx.d;
  const msg = document.getElementById('rloadmsg');
  const [buf, index] = await Promise.all([
    fetch(`/api/doc/${d.id}/file`, { credentials: 'same-origin' }).then(r => { if (!r.ok) throw new Error('Could not load the book file.'); return r.arrayBuffer(); }),
    api(`doc/${d.id}/epub-index`).catch(() => null)
  ]);
  if (msg) msg.textContent = 'Laying out pages…';
  const book = ePub();
  await book.open(buf, 'binary');
  await book.ready;

  const wrap = h('div', { class: 'epubwrap' });
  const view = h('div', { class: 'epubview' });
  const prevEl = h('div', { class: 'side', title: 'Previous page (←)' }, '‹'), nextEl = h('div', { class: 'side', title: 'Next page (→)' }, '›');
  wrap.append(prevEl, view, nextEl);
  ctx.mainArea.append(wrap);

  // Laid out with the tablet's reading settings (theme, size, margins, columns, fonts …).
  const isScroll = () => RS.viewMode === 'VERTICAL_SCROLL';
  const spreadFor = () => (!isScroll() && +RS.columns === 2 ? 'auto' : 'none');
  const SPREAD_MIN = 640;
  const frame = () => {
    const two = !isScroll() && +RS.columns === 2;
    view.style.padding = isScroll() ? `0 ${RS.marginDp}px` : `${RS.topMarginDp}px ${RS.marginDp}px ${RS.bottomMarginDp}px`;
    view.style.maxWidth = (two ? 1440 : 820) + RS.marginDp * 2 + 'px';
    wrap.classList.toggle('book', !isScroll());
    wrap.classList.toggle('scrollv', isScroll());
  };
  /** The spine down the middle, shown only while two pages are actually side by side. */
  const markSpread = () => {
    let on = false;
    try { on = !isScroll() && rendition.manager && rendition.manager.layout && rendition.manager.layout.divisor > 1; } catch (_) {}
    view.classList.toggle('spread', !!on);
  };
  frame();
  const rendition = book.renderTo(view, { width: '100%', height: '100%', flow: isScroll() ? 'scrolled-doc' : 'paginated', spread: spreadFor(), minSpreadWidth: SPREAD_MIN, allowScriptedContent: false });
  const styleInto = contents => {
    const doc = contents && contents.document; if (!doc || !doc.head) return;
    let st = doc.getElementById('ml-reader-style');
    if (!st) { st = doc.createElement('style'); st.id = 'ml-reader-style'; doc.head.appendChild(st); }
    st.textContent = epubCss();
  };
  rendition.hooks.content.register(styleInto);
  // Pages must always start on a page boundary. Dragging a selection to the edge (or a link
  // taking focus) can scroll the page strip by any amount, and page turns then keep that offset —
  // so the strip is snapped back to the nearest whole page / spread when it comes to rest.
  let snapT = 0, dragging = false;
  const snap = () => {
    if (isScroll() || dragging) return;
    const m = rendition.manager; if (!m || !m.container || !m.layout) return;
    const c = m.container, dl = m.layout.delta; if (!dl) return;
    const sl = c.scrollLeft, target = Math.round(sl / dl) * dl;
    if (Math.abs(sl - target) > 1) { c.scrollLeft = target; try { rendition.reportLocation(); } catch (_) {} }
  };
  const watchStrip = () => {
    const c = rendition.manager && rendition.manager.container;
    if (!c || c._mlSnap) return;
    c._mlSnap = true;
    c.addEventListener('scroll', () => { clearTimeout(snapT); snapT = setTimeout(snap, 160); }, { passive: true });
  };
  rendition.on('rendered', watchStrip);
  rendition.on('relocated', () => { watchStrip(); clearTimeout(snapT); snapT = setTimeout(snap, 160); });
  rendition.hooks.content.register(contents => {
    const doc = contents.document; if (!doc) return;
    doc.addEventListener('mousedown', () => { dragging = true; });
    doc.addEventListener('mouseup', () => { dragging = false; clearTimeout(snapT); snapT = setTimeout(snap, 120); });
  });
  window.addEventListener('mouseup', () => { if (dragging) { dragging = false; clearTimeout(snapT); snapT = setTimeout(snap, 120); } });
  let lastSize = '', sizeT = 0;
  const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => {
    const r = view.getBoundingClientRect(); const key = Math.round(r.width) + 'x' + Math.round(r.height);
    if (key === lastSize) return; const first = !lastSize; lastSize = key; if (first) return;
    clearTimeout(sizeT);
    sizeT = setTimeout(async () => {
      const cfi = lastLoc && lastLoc.start ? lastLoc.start.cfi : null;
      try { rendition.resize(); } catch (_) {}
      if (cfi) { try { await rendition.display(cfi); } catch (_) {} }
      markSpread();
      painted.forEach(c => { try { rendition.annotations.remove(c, 'highlight'); } catch (_) {} }); painted.clear();
      setTimeout(() => (rendition.getContents() || []).forEach(c => paintIn(c)), 120);
    }, 200);
  }) : null;
  if (ro) ro.observe(view);
  rendition.themes.fontSize(Math.round(RS.fontSizeSp / 18 * 100) + '%');
  const hlStyle = color => ({ fill: color || '#FFEB3B', 'fill-opacity': RS.theme === 'DARK' ? '0.35' : '0.4', 'mix-blend-mode': (RS_THEMES[RS.theme] || RS_THEMES.WHITE).hl });
  rsListeners.add(async (now, before) => {
    const cfi = lastLoc && lastLoc.start ? lastLoc.start.cfi : null;
    frame();
    (rendition.getContents() || []).forEach(styleInto);
    if (now.fontSizeSp !== before.fontSizeSp) rendition.themes.fontSize(Math.round(now.fontSizeSp / 18 * 100) + '%');
    if (now.viewMode !== before.viewMode) rendition.flow(isScroll() ? 'scrolled-doc' : 'paginated');
    if (now.viewMode !== before.viewMode || +now.columns !== +before.columns) rendition.spread(spreadFor(), SPREAD_MIN);
    try { rendition.resize(); } catch (_) {}
    // Highlights are drawn for one layout; draw them again for the new one.
    const repaint = () => { painted.forEach(c => { try { rendition.annotations.remove(c, 'highlight'); } catch (_) {} }); painted.clear(); (rendition.getContents() || []).forEach(c => paintIn(c)); };
    setTimeout(async () => { if (cfi) { try { await rendition.display(cfi); } catch (_) {} } markSpread(); setTimeout(repaint, 120); }, 60);
  });

  prevEl.onclick = () => rendition.prev();
  nextEl.onclick = () => rendition.next();

  // Contents
  const flatToc = [];
  const walk = (items, level) => (items || []).forEach(it => { flatToc.push({ title: (it.label || '').trim(), href: it.href, level, go: () => rendition.display(it.href) }); walk(it.subitems, level + 1); });
  walk(book.navigation && book.navigation.toc, 0);
  ctx.tocItems = flatToc;
  const fileOf = href => (href || '').split('#')[0].replace(/^\.?\//, '');
  const tocFor = href => { const f = fileOf(href); let t = null; flatToc.forEach(x => { if (fileOf(x.href) === f || f.endsWith('/' + fileOf(x.href)) || fileOf(x.href).endsWith('/' + f)) t = t || x; }); return t; };

  // App positions <-> places in the book
  const resourceFor = href => {
    if (!index) return null;
    const c = fileOf(href);
    return index.resources.find(r => r.href === c) || index.resources.find(r => r.href.endsWith('/' + c)) || index.resources.find(r => c.endsWith('/' + r.href)) || index.resources.find(r => r.href.split('/').pop() === c.split('/').pop());
  };
  const spineHrefFor = res => { const items = book.spine.spineItems || []; const it = items.find(s => res.href === s.href || res.href.endsWith('/' + s.href) || s.href.endsWith('/' + res.href) || res.href.split('/').pop() === s.href.split('/').pop()); return it ? it.href : null; };
  const locatePos = pos => {
    if (!index) return null;
    let r = null; index.resources.forEach(x => { if (x.first <= pos) r = x; });
    r = r || index.resources[0];
    return r ? { href: spineHrefFor(r), progression: Math.max(0, Math.min(1, (pos - r.first) / Math.max(1, r.count))) } : null;
  };

  // Text-offset helpers inside a chapter document
  const bodyText = doc => (doc.body ? doc.body.textContent : '') || '';
  function offsetOf(doc, node, off) {
    const r = doc.createRange(); r.setStart(doc.body, 0);
    try { r.setEnd(node, off); } catch (_) { return 0; }
    return r.toString().length;
  }
  function pageLabelAt(doc, range) {
    const marks = [...doc.querySelectorAll('[epub\\:type~="pagebreak"], [role="doc-pagebreak"], span[id^="page"], a[id^="page"], span[id^="pg"], [class*="pagebreak"], [class*="pagenum"]')];
    let label = '';
    for (const m of marks) {
      const rel = m.compareDocumentPosition(range.startContainer);
      if (rel & Node.DOCUMENT_POSITION_FOLLOWING || m.contains(range.startContainer)) {
        const t = (m.getAttribute('title') || m.getAttribute('aria-label') || m.textContent || m.id || '').trim();
        const v = t.replace(/^(page|side|pg|p\.?)\s*/i, '').trim();
        if (v && v.length < 12) label = v;
      } else break;
    }
    return label;
  }
  function rangeForText(doc, quote) {
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
    let flat = ''; const map = [];
    let n;
    while ((n = walker.nextNode())) {
      const v = n.nodeValue.toLowerCase();
      for (let k = 0; k < v.length; k++) if (LETTER.test(v[k])) { flat += v[k]; map.push([n, k]); }
    }
    const span = findLoose(flat, quote, false);
    if (!span) return null;
    const a = map[span[0]], b = map[Math.min(span[1], map.length) - 1];
    if (!a || !b) return null;
    const r = doc.createRange(); r.setStart(a[0], a[1]); r.setEnd(b[0], b[1] + 1);
    return r;
  }

  // Show the book's notes as highlights
  const painted = new Map(); // noteId -> cfi
  function paintIn(contents) {
    const doc = contents.document; if (!doc || !doc.body) return;
    const sectionHref = contents.section ? contents.section.href : '';
    const res = resourceFor(sectionHref);
    ctx.notes.forEach(note => {
      if (painted.has(note.id)) return;
      if (res && (note.pageNumber < res.first || note.pageNumber >= res.first + res.count)) {
        // Not in this chapter by position; still try the text when the chapter title matches.
        const t = tocFor(sectionHref);
        if (!(t && note.chapterTitle && t.title.toLowerCase() === note.chapterTitle.toLowerCase())) return;
      }
      const range = rangeForText(doc, note.highlightedText);
      if (!range) return;
      let cfi = null;
      try { cfi = contents.cfiFromRange(range); } catch (_) {}
      if (!cfi) return;
      painted.set(note.id, cfi);
      rendition.annotations.highlight(cfi, { id: note.id }, () => { const nn = ctx.notes.find(x => x.id === note.id); if (nn) editNote(nn, () => ctx.refreshNotes()); }, 'ml-hl', hlStyle(note.colorHex));
    });
  }
  rendition.hooks.content.register(contents => { setTimeout(() => paintIn(contents), 50); });

  // Place, progress and the status line
  let lastLoc = null;
  const where = h('span', null, '');
  const fontBox = h('span', { class: 'row', style: { gap: '4px' } });
  const fl = h('span', { class: 'mono', style: { fontSize: '11px', minWidth: '40px', textAlign: 'center' }, title: 'Text size (as on the tablet)' }, Math.round(RS.fontSizeSp) + ' pt');
  rsListeners.add(now => { fl.textContent = Math.round(now.fontSizeSp) + ' pt'; });
  const setFont = sp => setReaderSettings({ fontSizeSp: Math.max(10, Math.min(40, Math.round(sp))) });
  ctx.zoomBox.append(viewSwitch(), h('button', { class: 'btn quiet', title: 'Smaller text', onclick: () => setFont(RS.fontSizeSp - 1) }, 'A−'), fl, h('button', { class: 'btn quiet', title: 'Larger text', onclick: () => setFont(RS.fontSizeSp + 1) }, 'A+'));
  ctx.status.append(where, h('span', { class: 'spacer' }), h('span', null, 'EPUB'), fontBox);
  const endCard = endOfChapterCard(ctx);
  if (endCard) { endCard.style.position = 'absolute'; endCard.style.left = '50%'; endCard.style.bottom = '18px'; endCard.style.transform = 'translateX(-50%)'; endCard.style.zIndex = '25'; endCard.hidden = true; ctx.mainArea.append(endCard); }
  rendition.on('relocated', loc => {
    lastLoc = loc;
    markSpread();
    if (endCard) endCard.hidden = !loc.atEnd;
    const st = loc.start || {};
    const t = tocFor(st.href);
    const res = resourceFor(st.href);
    const disp = st.displayed || {};
    let pos = '';
    if (res && disp.total) pos = `pos. ${res.first + Math.floor((Math.max(0, disp.page - 1) / disp.total) * res.count)} of ${index.total}`;
    where.textContent = [t ? t.title : '', disp.total ? `page ${disp.page} of ${disp.total} in this chapter` : '', pos].filter(Boolean).join(' · ');
    ctx.placeS.textContent = t ? t.title : '';
    flatToc.forEach(x => { x.current = x === t; });
    try { localSet('ml.epubLoc.' + d.id, st.cfi || ''); } catch (_) {}
  });

  // Selection → bar
  rendition.on('selected', (cfiRange, contents) => {
    const range = contents.range(cfiRange);
    const text = cleanSelected(range ? range.toString() : '');
    if (text.length < 2) return;
    const doc = contents.document;
    const href = contents.section ? contents.section.href : (lastLoc && lastLoc.start.href) || '';
    const total = bodyText(doc).length || 1;
    const progression = range ? offsetOf(doc, range.startContainer, range.startOffset) / total : 0;
    const label = range ? pageLabelAt(doc, range) : '';
    const t = tocFor(href);
    selectionBar(ctx, {
      text,
      pageRef: label || '',
      where: [t ? t.title : '', label ? 'p. ' + label : ''].filter(Boolean).join(' · '),
      loc: { href, progression, chapter: t ? t.title : '' },
      save: async extra => {
        const r = await api(`doc/${d.id}/highlights`, { method: 'POST', json: Object.assign({ text, href, progression, cfi: cfiRange, pageLabel: label, chapter: t ? t.title : '' }, extra) });
        ctx.notes.push(r);
        painted.set(r.id, cfiRange);
        rendition.annotations.highlight(cfiRange, { id: r.id }, () => { const nn = ctx.notes.find(x => x.id === r.id); if (nn) editNote(nn, () => ctx.refreshNotes()); }, 'ml-hl', hlStyle(r.colorHex));
        return r;
      }
    }, cancelled => {
      try { contents.window.getSelection().removeAllRanges(); } catch (_) {}
      selectionBar(ctx, null);
      if (!cancelled) ctx.renderSide();
    });
  });
  rendition.on('keydown', e => readerKeys(e));

  ctx.pageCount = index && index.total ? index.total : 0;
  ctx.goToPage = async n => { try { await goTo(locatePos(n)); } catch (e) { fail(e); } };
  ctx.place = () => {
    const st = (lastLoc && lastLoc.start) || {};
    const disp = st.displayed || {};
    const res = resourceFor(st.href);
    const progression = disp.total ? Math.max(0, disp.page - 1) / disp.total : 0;
    const pos = res && disp.total ? res.first + Math.floor(progression * res.count) : (d.currentPage || 1);
    const t = tocFor(st.href);
    return { pos, chapter: t ? t.title : '', loc: { href: st.href || '', progression, chapter: t ? t.title : '' } };
  };
  rendition.hooks.content.register(contents => {
    const doc = contents.document; if (!doc) return;
    doc.addEventListener('contextmenu', e => {
      const fr = contents.window.frameElement;
      const r = fr ? fr.getBoundingClientRect() : { left: 0, top: 0 };
      const s = contents.window.getSelection();
      let inSel = false;
      if (s && !s.isCollapsed && ctx.sel) {
        for (let i = 0; i < s.rangeCount; i++) for (const q of s.getRangeAt(i).getClientRects()) if (e.clientX >= q.left - 2 && e.clientX <= q.right + 2 && e.clientY >= q.top - 2 && e.clientY <= q.bottom + 2) inSel = true;
      }
      e.preventDefault();
      openCtxMenu(r.left + e.clientX, r.top + e.clientY, readerContext(ctx, e, inSel));
    });
  });
  onContext(view, e => readerContext(ctx, e, false));

  // Where to start: a note, a chapter, the app's own position, or where this browser left off
  const target = async () => {
    const pos = +params.get('pos');
    const hrefParam = params.get('href');
    if (hrefParam) { const r = resourceFor(hrefParam); const sh = r && spineHrefFor(r); return { href: sh || hrefParam, progression: 0 }; }
    if (pos) return locatePos(pos);
    const saved = localGet('ml.epubLoc.' + d.id, '');
    if (saved) return { cfi: saved };
    if (d.currentPage > 1) return locatePos(d.currentPage);
    return null;
  };
  async function goTo(t) {
    if (!t) return rendition.display();
    if (t.cfi) { try { return await rendition.display(t.cfi); } catch (_) { return rendition.display(); } }
    if (!t.href) return rendition.display();
    await rendition.display(t.href);
    if (t.progression > 0.02) {
      const loc = rendition.currentLocation();
      const total = loc && loc.start && loc.start.displayed ? loc.start.displayed.total : 0;
      const steps = Math.min(60, Math.floor(t.progression * total));
      for (let k = 0; k < steps; k++) await rendition.next();
    }
  }
  await goTo(await target());

  return {
    kind: 'epub',
    next: () => rendition.next(),
    prev: () => rendition.prev(),
    goToNote: async note => {
      const cfi = painted.get(note.id);
      if (cfi) { await rendition.display(cfi); return; }
      await goTo(locatePos(note.pageNumber));
    },
    notesChanged: () => {
      painted.forEach((cfi) => { try { rendition.annotations.remove(cfi, 'highlight'); } catch (_) {} });
      painted.clear();
      (rendition.getContents() || []).forEach(c => paintIn(c));
    },
    destroy: () => { try { ro && ro.disconnect(); } catch (_) {} try { rendition.destroy(); } catch (_) {} try { book.destroy(); } catch (_) {} }
  };
}

// ------------------------------------------------------------------ start

(async function start() {
  try {
    const s = await api('session');
    state.device = s.device || '';
    if (!s.signedIn) return showLogin();
    route();
  } catch (e) {
    clear(document.getElementById('app')).append(h('div', { class: 'login' }, h('div', { class: 'box' }, h('h1', null, 'Marginalia Lectoris'), h('p', null, e.message))));
  }
})();
