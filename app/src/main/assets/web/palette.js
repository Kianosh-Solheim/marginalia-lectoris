/* Marginalia Lectoris — the command palette (Ctrl+K) and the list of keyboard shortcuts (?).
 *
 * One box to get anywhere: type a few letters of a book, an article, a note or a writing document
 * to open it, or of an action ("dark", "tabs", "settings", "new document") to do it.
 */

let paletteData = null;   // { books, notes, docs } fetched when the palette first opens
async function paletteLoad(force) {
  if (paletteData && !force) return paletteData;
  const [books, notes, docs] = await Promise.all([
    loadLibrary().catch(() => []),
    (notesCache && notesCache.list ? Promise.resolve({ highlights: notesCache.list }) : api('highlights')).catch(() => ({ highlights: [] })),
    api('writing').catch(() => [])]);
  paletteData = { books: books || [], notes: notes.highlights || [], docs: Array.isArray(docs) ? docs : [] };
  return paletteData;
}
/** What can be done from anywhere. [words] are extra words that find it. */
function paletteActions() {
  const go = hash => () => { location.hash = hash; };
  const theme = t => () => { try { localStorage.setItem('ml.theme', t); } catch (_) {} applyTheme(); if (typeof route === 'function' && !workspace && document.querySelector('header.top')) route(); };
  const list = [
    ['Library', 'Go to', go('#/'), 'home all books articles'], ['Books', 'Go to', go('#/books'), ''], ['Articles', 'Go to', go('#/articles'), ''],
    ['Notes', 'Go to', go('#/notes'), 'vault highlights'], ['Writing', 'Go to', go('#/writing'), 'documents editor'], ['Search in the texts', 'Go to', go('#/search'), 'full text find'],
    ['Zotero', 'Go to', go('#/zotero'), ''], ['Presentations', 'Go to', go('#/presentations'), 'slides'], ['Settings', 'Go to', go('#/settings'), 'preferences backup ai'],
    ['New writing document', 'Do', async () => { try { const d = await api('writing', { method: 'POST', json: { title: 'Untitled' } }); location.hash = '#/write/' + encodeURIComponent(d.id) + '?new=1'; } catch (e) { fail(e); } }, 'create write'],
    ['Colours: dark', 'Do', theme('dark'), 'theme night mode'], ['Colours: light', 'Do', theme('light'), 'theme day mode'], ['Colours: follow the computer', 'Do', theme('auto'), 'theme auto'],
    ['Keyboard shortcuts', 'Help', () => shortcutsDialog(), 'keys help'],
    ['Sign out', 'Do', async () => { try { await api('logout', { method: 'POST' }); } catch (_) {} showLogin(); }, 'log out'],
  ];
  if (!EMBEDDED && !workspace) list.push(['Tabs: open several things side by side', 'Do', () => { localSet('ml.tabs', '1'); showWorkspace(location.hash || '#/'); }, 'split panes workspace']);
  if (EMBEDDED) list.push(['Open the library in a new tab', 'Do', () => parent.postMessage({ ml: 'open', hash: '#/' }, location.origin), 'tab'],
    ['Open the notes to the side', 'Do', () => parent.postMessage({ ml: 'open', hash: '#/notes', side: true }, location.origin), 'split tab'],
    ['Open writing to the side', 'Do', () => parent.postMessage({ ml: 'open', hash: '#/writing', side: true }, location.origin), 'split tab']);
  // Things that belong to what is open (the reader and the writer add theirs here).
  (window.paletteContext || []).forEach(a => list.unshift(a));
  return list.map(([title, kind, run, words]) => ({ title, kind, run, hay: norm(title + ' ' + (words || '')) }));
}
function openPalette() {
  if (document.querySelector('.palette')) return;
  const back = h('div', { class: 'palette' }), box = h('div', { class: 'palbox' });
  const input = h('input', { type: 'search', placeholder: 'Open a book, a note, a document — or type what you want to do…', spellcheck: 'false' });
  const list = h('div', { class: 'pallist' });
  let items = [], sel = 0, data = { books: [], notes: [], docs: [] };
  const close = () => { back.remove(); document.removeEventListener('keydown', onKey, true); };
  const actions = paletteActions();
  const score = (hay, title, terms) => { if (!terms.every(t => hay.includes(t))) return -1; const tl = norm(title); return (tl.startsWith(terms[0]) ? 6 : 0) + (terms.every(t => tl.includes(t)) ? 3 : 0) + Math.max(0, 2 - tl.length / 60); };
  const find = () => {
    const terms = norm(input.value.trim()).split(/\s+/).filter(Boolean);
    const out = [];
    const push = (arr, max) => arr.filter(x => x.s >= 0).sort((a, b) => b.s - a.s).slice(0, max).forEach(x => out.push(x));
    if (!terms.length) {
      // Nothing typed: what was open last, then the places.
      data.books.slice().sort((a, b) => (b.lastRead || 0) - (a.lastRead || 0)).slice(0, 5).forEach(d => out.push(bookItem(d, 0)));
      actions.slice(0, 12).forEach(a => out.push(Object.assign({ s: 0 }, a)));
    } else {
      push(actions.map(a => Object.assign({ s: score(a.hay, a.title, terms) + 1 }, a)).map(a => { if (a.s < 1) a.s = -1; return a; }), 5);
      push(data.books.map(d => bookItem(d, score(norm([d.title, d.author, d.tags].join(' ')), d.title, terms))), 8);
      push(data.docs.map(d => ({ title: d.title, kind: 'Document', sub: `${d.words || 0} words`, s: score(norm(d.title), d.title, terms), run: () => { location.hash = '#/write/' + encodeURIComponent(d.id); } })), 4);
      push(data.notes.map(n => ({ title: n.title || 'Untitled', kind: 'Note', sub: n.documentTitle || '', s: score(norm([n.title, n.tags, n.documentTitle].join(' ')), n.title || '', terms),
        run: () => { location.hash = '#/notes/n/' + n.id; } })), 8);
    }
    items = out; sel = 0; draw();
  };
  const bookItem = (d, s) => ({ title: d.title, kind: d.category === 'ARTICLE' ? 'Article' : 'Book', sub: d.author || '', s,
    run: e => { location.hash = canRead(d) && !(e && e.ctrlKey) ? `#/read/${d.id}` : `#/book/${d.id}`; } });
  const draw = () => {
    clear(list);
    if (!items.length) { list.append(h('div', { class: 'palnone muted' }, 'Nothing found.')); return; }
    items.forEach((it, i) => {
      const row = h('div', { class: 'palrow' + (i === sel ? ' on' : ''), onclick: e => { close(); it.run(e); } },
        h('span', { class: 'palt' }, it.title), it.sub ? h('span', { class: 'pals muted' }, it.sub) : null, h('span', { class: 'palk' }, it.kind));
      row.addEventListener('mousemove', () => { if (sel !== i) { sel = i; list.querySelectorAll('.palrow').forEach((r, k) => r.classList.toggle('on', k === sel)); } });
      list.append(row);
    });
    const on = list.querySelector('.palrow.on'); if (on) on.scrollIntoView({ block: 'nearest' });
  };
  const onKey = e => {
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); sel = Math.min(items.length - 1, sel + 1); draw(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); sel = Math.max(0, sel - 1); draw(); }
    else if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); const it = items[sel]; if (it) { close(); it.run(e); } }
  };
  document.addEventListener('keydown', onKey, true);
  input.addEventListener('input', find);
  back.addEventListener('mousedown', e => { if (e.target === back) close(); });
  box.append(input, list, h('div', { class: 'palfoot muted' }, '↑ ↓ to move · Enter to open · Ctrl+Enter opens a book’s page instead of the reader · Esc to close'));
  back.append(box); document.body.append(back);
  input.focus();
  find();
  paletteLoad(false).then(d => { data = d; find(); });
}
function shortcutsDialog() {
  const rows = [
    ['Everywhere', [['Ctrl+K', 'Open anything, or do anything (the command palette)'], ['?', 'This list']]],
    ['Notes', [['/', 'Search the notes'], ['[[', 'In a note: link to another note']]],
    ['Reader', [['← →', 'Previous and next page'], ['Ctrl+F', 'Search in the book']]],
    ['Writing', [['Ctrl+Shift+C', 'Insert a citation'], ['Ctrl+Alt+N', 'Notes panel'], ['Ctrl+Alt+M', 'Comment on the selection'], ['Ctrl+Alt+E', 'Formula'], ['Ctrl+S', 'Save now']]],
    ['Tabs', [['Ctrl+click', 'Open in a new tab'], ['Ctrl+Alt+click', 'Open in the other pane'], ['Middle click on a tab', 'Close it']]],
  ];
  modal((box, close) => {
    box.append(h('h2', null, 'Keyboard shortcuts'), h('div', { class: 'keys' }, rows.map(([group, keys]) => [h('div', { class: 'label keysg' }, group),
      keys.map(([k, what]) => h('div', { class: 'keyrow' }, h('kbd', null, k), h('span', null, what)))])),
      h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: close }, 'Close')));
  });
}
document.addEventListener('keydown', e => {
  if ((e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey && (e.key === 'k' || e.key === 'K')) {
    if (workspace || document.querySelector('.login')) return;
    e.preventDefault(); openPalette(); return;
  }
  if (e.key === '?' && !e.ctrlKey && !e.metaKey && !e.altKey) {
    const t = document.activeElement, tag = (t && t.tagName) || '';
    if (/^(INPUT|TEXTAREA|SELECT)$/.test(tag) || (t && t.isContentEditable) || document.querySelector('.modal, .palette, .login') || workspace) return;
    e.preventDefault(); shortcutsDialog();
  }
});
