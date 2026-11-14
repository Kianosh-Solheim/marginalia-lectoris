/* Marginalia Lectoris — the notes as a vault: a tree of books, chapters and notes on the left, the
 * open note or overview page in the middle, and its graph and outline on the right.
 *
 * Notes link to each other with [[Title of the other note]] (or [[Title|the words shown]]) written
 * in the note text; nothing else is stored. A link is found by the title, first within the same book.
 */

const NOTE_LINK = /\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]/g;
const chapterOf = n => (n.chapterTitle || '').trim();

/** Titles → notes, and who links to whom. */
function noteIndex(list) {
  const byTitle = new Map(), byId = new Map(), out = new Map(), back = new Map();
  list.forEach(n => { byId.set(n.id, n); const k = norm(n.title || '').trim(); if (k) { if (!byTitle.has(k)) byTitle.set(k, []); byTitle.get(k).push(n); } });
  const resolve = (title, from) => {
    const c = byTitle.get(norm(title).trim()); if (!c) return null;
    return (from && c.find(x => x.documentId === from.documentId && x.id !== from.id)) || c.find(x => !from || x.id !== from.id) || null;
  };
  list.forEach(n => {
    const ids = new Set();
    for (const m of String(n.userNotes || '').matchAll(NOTE_LINK)) { const t = resolve(m[1], n); if (t) ids.add(t.id); }
    out.set(n.id, [...ids]);
    ids.forEach(id => { if (!back.has(id)) back.set(id, []); back.get(id).push(n.id); });
  });
  return { byId, resolve, out: id => (out.get(id) || []).map(i => byId.get(i)), back: id => (back.get(id) || []).map(i => byId.get(i)) };
}
/** Note text with its [[links]] made clickable. */
function noteBody(text, from, idx, go) {
  const out = []; let last = 0; const s = String(text || '');
  for (const m of s.matchAll(NOTE_LINK)) {
    if (m.index > last) out.push(s.slice(last, m.index));
    const t = idx.resolve(m[1], from);
    out.push(t ? h('a', { class: 'nlink', href: `#/notes/n/${t.id}`, title: t.documentTitle, onclick: e => { e.preventDefault(); go(`#/notes/n/${t.id}`); } }, m[2] || m[1])
      : h('span', { class: 'nlink missing', title: 'No note has this title (yet)' }, m[2] || m[1]));
    last = m.index + m[0].length;
  }
  if (last < s.length) out.push(s.slice(last));
  return out;
}

/**
 * [[ in a note box: a list of note titles to pick from. Arrow keys and Enter (or a click) put the
 * title in; Escape closes the list.
 */
function linkComplete(ta) {
  let box = null, items = [], sel = 0, start = -1, titles = null;
  const load = async () => {
    if (titles) return titles;
    let list = notesCache && notesCache.list;
    if (!list) { try { list = (await api('highlights')).highlights || []; } catch (_) { list = []; } }
    titles = list.filter(n => n.title).map(n => ({ title: n.title, book: n.documentTitle || '' }));
    return titles;
  };
  const close = () => { if (box) { box.remove(); box = null; } start = -1; };
  const pick = i => {
    const it = items[i]; if (!it) return;
    const v = ta.value, end = ta.selectionStart;
    const after = v.slice(end).startsWith(']]') ? v.slice(end + 2) : v.slice(end);
    ta.value = v.slice(0, start) + it.title + ']]' + after;
    ta.selectionStart = ta.selectionEnd = start + it.title.length + 2;
    close(); ta.dispatchEvent(new Event('input', { bubbles: true })); ta.focus();
  };
  const draw = () => {
    if (!box) { box = h('div', { class: 'nlinkbox' }); document.body.append(box); }
    const r = ta.getBoundingClientRect();
    box.style.left = r.left + 'px'; box.style.top = Math.min(r.bottom + 2, innerHeight - 220) + 'px'; box.style.width = r.width + 'px';
    clear(box);
    if (!items.length) { box.append(h('div', { class: 'muted', style: { padding: '6px 8px' } }, 'No note with that title. Keep typing to link to one you will write later.')); return; }
    items.forEach((it, i) => box.append(h('div', { class: 'nlinkopt' + (i === sel ? ' on' : ''), onmousedown: e => { e.preventDefault(); pick(i); } }, h('b', null, it.title), h('span', { class: 'muted' }, ' · ' + it.book))));
  };
  ta.addEventListener('input', async () => {
    const pos = ta.selectionStart, before = ta.value.slice(0, pos), at = before.lastIndexOf('[[');
    if (at < 0 || /\]\]|\n/.test(before.slice(at + 2))) { close(); return; }
    start = at + 2;
    const q = norm(before.slice(start)).split(/\s+/).filter(Boolean);
    const all = await load();
    items = all.filter(t => q.every(w => norm(t.title).includes(w))).slice(0, 8); sel = 0;
    draw();
  });
  ta.addEventListener('keydown', e => {
    if (!box) return;
    if (e.key === 'ArrowDown') { e.preventDefault(); sel = Math.min(items.length - 1, sel + 1); draw(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); sel = Math.max(0, sel - 1); draw(); }
    else if ((e.key === 'Enter' || e.key === 'Tab') && items.length && !e.ctrlKey && !e.metaKey) { e.preventDefault(); e.stopPropagation(); pick(sel); }
    else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); }
  }, true);
  ta.addEventListener('blur', () => setTimeout(close, 150));
}
/** After a note got a new title: the [[links]] to the old title in other notes follow it. */
async function retitleLinks(oldTitle, newTitle, exceptId) {
  if (!oldTitle || !newTitle || norm(oldTitle) === norm(newTitle)) return 0;
  let list = notesCache && notesCache.list;
  if (!list) { try { list = (await api('highlights')).highlights || []; } catch (_) { return 0; } }
  let n = 0;
  for (const x of list) {
    if (x.id === exceptId || !x.userNotes || !x.userNotes.includes('[[')) continue;
    const next = x.userNotes.replace(NOTE_LINK, (m, t, shown) => norm(t).trim() === norm(oldTitle).trim() ? `[[${newTitle}${shown ? '|' + shown : ''}]]` : m);
    if (next === x.userNotes) continue;
    try { await api(`highlight/${x.id}`, { method: 'PATCH', json: { title: x.title, userNotes: next, tags: x.tags || '', colorHex: x.colorHex } }); x.userNotes = next; n++; } catch (_) {}
  }
  return n;
}

// ------------------------------------------------------------------ the graph

/** A small picture of a page and what it links to. nodes: [{ id, label, href, main }], edges: [[a, b]]. */
function noteGraph(nodes, edges, go) {
  const W = 320, H = 300, N = nodes.length;
  const P = nodes.map((n, i) => n.main ? { x: 0, y: 0 } : { x: Math.cos(i / Math.max(1, N - 1) * 2 * Math.PI) * 90, y: Math.sin(i / Math.max(1, N - 1) * 2 * Math.PI) * 90 });
  const at = Object.fromEntries(nodes.map((n, i) => [n.id, i]));
  const E = edges.filter(([a, b]) => at[a] != null && at[b] != null).map(([a, b]) => [at[a], at[b]]);
  for (let it = 0; it < 220; it++) {
    const F = P.map(() => ({ x: 0, y: 0 }));
    for (let i = 0; i < N; i++) for (let j = i + 1; j < N; j++) {
      let dx = P[i].x - P[j].x, dy = P[i].y - P[j].y; const d2 = Math.max(60, dx * dx + dy * dy), d = Math.sqrt(d2), f = 2600 / d2;
      dx /= d; dy /= d; F[i].x += dx * f; F[i].y += dy * f; F[j].x -= dx * f; F[j].y -= dy * f;
    }
    E.forEach(([a, b]) => { const dx = P[b].x - P[a].x, dy = P[b].y - P[a].y, d = Math.max(1, Math.hypot(dx, dy)), f = (d - 85) * 0.04; F[a].x += dx / d * f; F[a].y += dy / d * f; F[b].x -= dx / d * f; F[b].y -= dy / d * f; });
    P.forEach((p, i) => { if (nodes[i].main) return; p.x += Math.max(-8, Math.min(8, F[i].x)) - p.x * 0.01; p.y += Math.max(-8, Math.min(8, F[i].y)) - p.y * 0.01; });
  }
  const xs = P.map(p => p.x), ys = P.map(p => p.y);
  const minX = Math.min(...xs) - 70, maxX = Math.max(...xs) + 70, minY = Math.min(...ys) - 24, maxY = Math.max(...ys) + 34;
  const NS = 'http://www.w3.org/2000/svg', el = (t, a) => { const e = document.createElementNS(NS, t); Object.entries(a || {}).forEach(([k, v]) => e.setAttribute(k, v)); return e; };
  const svg = el('svg', { viewBox: `${minX} ${minY} ${Math.max(W, maxX - minX)} ${Math.max(H * 0.6, maxY - minY)}`, class: 'ngraph' });
  E.forEach(([a, b]) => svg.append(el('line', { x1: P[a].x, y1: P[a].y, x2: P[b].x, y2: P[b].y })));
  nodes.forEach((n, i) => {
    const g = el('g', { class: 'ngnode' + (n.main ? ' main' : '') + (n.kind ? ' ' + n.kind : ''), transform: `translate(${P[i].x},${P[i].y})` });
    g.append(el('circle', { r: n.main ? 8 : 5 }));
    const t = el('text', { y: n.main ? 22 : 17, 'text-anchor': 'middle' }); t.textContent = n.label.length > 30 ? n.label.slice(0, 28) + '…' : n.label; g.append(t);
    const tt = el('title'); tt.textContent = n.label; g.append(tt);
    if (n.href && !n.main) g.addEventListener('click', () => go(n.href));
    svg.append(g);
  });
  return svg;
}

// ------------------------------------------------------------------ the view

let vault = null;   // { left, mid, right, idx, open: Set, route }

async function showVault(parts) {
  const route = '#/' + parts.join('/');
  const app = document.getElementById('app');
  if (!vault || !document.body.contains(vault.root)) {
    clear(app).appendChild(header());
    const left = h('div', { class: 'nvleft' }), mid = h('div', { class: 'nvmid' }), right = h('div', { class: 'nvright' });
    const root = h('div', { class: 'nv' }, left, mid, right);
    app.appendChild(root);
    const fit = () => { if (document.body.contains(root)) root.style.height = `calc(100vh - ${Math.round(root.getBoundingClientRect().top + scrollY)}px)`; };
    fit(); window.addEventListener('resize', fit);
    vault = { root, left, mid, right, idx: null, shut: new Set(JSON.parse(localGet('ml.nvShut', '[]'))), tag: '' };
    mid.append(h('div', { class: 'loading', style: { position: 'static', height: '200px' } }, h('div', { class: 'spin' })));
    try { await loadVault(); } catch (e) { fail(e); return; }
  }
  if (vault.route !== route) vault.arr = null;
  vault.route = route;
  drawVault();
}
async function loadVault() {
  const [lib, all] = await Promise.all([loadLibrary(), api('highlights')]);
  notesCache = { docs: Object.fromEntries(lib.map(d => [d.id, d])), list: all.highlights || [] };
  vault.idx = noteIndex(notesCache.list);
  try { vault.pages = await api('note-pages'); } catch (_) { vault.pages = vault.pages || {}; }
}
const vaultGo = href => { if (location.hash === href) drawVault(); else location.hash = href; };
async function vaultReload() { try { await loadVault(); } catch (e) { fail(e); } drawVault(); }

/** Books → chapters → notes, as the filter leaves them. */
function vaultTree() {
  const q = norm(state.query.trim().replace(/^#/, '')), terms = q ? q.split(/\s+/) : [];
  const hits = notesCache.list.filter(n => (!vault.tag || splitTags(n.tags).includes(vault.tag)) &&
    (!terms.length || terms.every(t => norm([n.title, n.highlightedText, n.userNotes, n.tags, n.documentTitle, n.documentAuthor, n.chapterTitle].join(' ')).includes(t))));
  const books = new Map();
  hits.forEach(n => { if (!books.has(n.documentId)) books.set(n.documentId, { id: n.documentId, title: n.documentTitle || 'Untitled', author: n.documentAuthor || '', notes: [] }); books.get(n.documentId).notes.push(n); });
  const list = [...books.values()].sort((a, b) => a.title.localeCompare(b.title));
  list.forEach(b => {
    b.notes.sort((x, y) => x.pageNumber - y.pageNumber || x.created - y.created);
    const ch = new Map(); b.notes.forEach(n => { const k = chapterOf(n); if (!ch.has(k)) ch.set(k, []); ch.get(k).push(n); });
    b.chapters = [...ch.entries()].map(([name, notes]) => ({ name, notes }));
  });
  return { books: list, count: hits.length, filtered: !!terms.length || !!vault.tag };
}
function drawVault() {
  const add = (el, ...kids) => el.append(...kids.flat(3).filter(x => x != null && x !== false));
  if (!vault || !vault.idx) return;
  const parts = vault.route.replace(/^#\//, '').split('/'), kind = parts[1], go = vaultGo;
  const tree = vaultTree();
  const cur = kind === 'n' ? vault.idx.byId.get(+parts[2]) : null;
  const curBook = kind === 'n' && cur ? cur.documentId : (kind === 'b' || kind === 'c') ? +parts[2] : null;
  const curChap = kind === 'c' ? decodeURIComponent(parts.slice(3).join('/')) : cur ? chapterOf(cur) : null;
  const toggle = key => { if (vault.shut.has(key)) vault.shut.delete(key); else vault.shut.add(key); localSet('ml.nvShut', JSON.stringify([...vault.shut].slice(-400))); drawVault(); };

  // ---- left: the tree
  const left = clear(vault.left);
  left.append(h('div', { class: 'nvbar' }, h('span', { class: 'label' }, `${tree.count} note${tree.count === 1 ? '' : 's'}${tree.filtered ? ' shown' : ''}`), h('span', { style: { flex: 1 } }),
    h('button', { class: 'btn quiet', title: 'The plain list of all notes', onclick: () => { localSet('ml.notesView', 'list'); vault = null; location.hash = '#/notes'; showNotes(['notes']); } }, 'List view')));
  const tr = h('div', { class: 'nvtree' });
  tree.books.forEach(b => {
    // Books are shut, except the one you are in and those you opened yourself.
    const key = (curBook === b.id ? 'b' : 'ob') + b.id;
    const openB = tree.filtered || (curBook === b.id ? !vault.shut.has(key) : vault.shut.has(key));
    tr.append(h('div', { class: 'nvrow book' + (kind === 'b' && curBook === b.id ? ' on' : '') },
      h('span', { class: 'nvcaret', onclick: () => toggle(key) }, openB ? '▾' : '▸'),
      h('span', { class: 'nvname', title: b.title, onclick: () => go(`#/notes/b/${b.id}`) }, b.title), h('span', { class: 'nvcount' }, b.notes.length)));
    if (!openB) return;
    b.chapters.forEach(c => {
      const single = b.chapters.length === 1 && !c.name;
      const ck = 'c' + b.id + ':' + c.name, openC = single || tree.filtered || !vault.shut.has(ck);
      if (!single) tr.append(h('div', { class: 'nvrow chap' + (kind === 'c' && curBook === b.id && curChap === c.name ? ' on' : '') },
        h('span', { class: 'nvcaret', onclick: () => toggle(ck) }, openC ? '▾' : '▸'),
        h('span', { class: 'nvname', title: c.name || 'No chapter', onclick: () => go(`#/notes/c/${b.id}/${encodeURIComponent(c.name)}`) }, c.name || 'No chapter'), h('span', { class: 'nvcount' }, c.notes.length)));
      if (openC) c.notes.forEach(n => tr.append(h('div', { class: 'nvrow leaf' + (single ? ' flat' : '') + (cur && cur.id === n.id ? ' on' : ''), title: n.title, onclick: () => go(`#/notes/n/${n.id}`) },
        h('span', { class: 'nvdot', style: { background: n.colorHex || '#E4E4E7' } }), h('span', { class: 'nvname' }, n.title || 'Untitled'))));
    });
  });
  if (!tree.books.length) tr.append(h('div', { class: 'muted', style: { padding: '10px' } }, tree.filtered ? 'No notes match.' : 'No notes yet.'));
  left.append(tr);
  const tags = new Map(); notesCache.list.forEach(n => splitTags(n.tags).forEach(t => tags.set(t, (tags.get(t) || 0) + 1)));
  if (tags.size) left.append(h('div', { class: 'nvtags' }, h('div', { class: 'label' }, 'Tags'),
    [...tags.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, 80).map(([t, c]) =>
      h('span', { class: 'tag' + (vault.tag === t ? ' on' : ''), onclick: () => { vault.tag = vault.tag === t ? '' : t; drawVault(); } }, '#' + t, h('span', { class: 'muted' }, ' ' + c)))));

  // ---- middle and right
  const mid = clear(vault.mid), right = clear(vault.right);
  const outline = [];
  const linkTo = n => h('a', { class: 'nlink', href: `#/notes/n/${n.id}`, onclick: e => { e.preventDefault(); go(`#/notes/n/${n.id}`); } }, n.title || 'Untitled');
  const bookTitle = id => { const n = notesCache.list.find(x => x.documentId === id); return n ? n.documentTitle : 'Book'; };
  const crumbs = (...c) => h('div', { class: 'nvcrumbs' }, c.filter(Boolean).reduce((a, x, i) => a.concat(i ? [' / ', x] : [x]), []));
  const crumb = (label, href) => h('a', { href, onclick: e => { e.preventDefault(); go(href); } }, label);
  let gNodes = [], gEdges = [];

  if (cur) {
    const d = notesCache.docs[cur.documentId], tagsOf = splitTags(cur.tags), back = vault.idx.back(cur.id), outs = vault.idx.out(cur.id);
    const chapHref = `#/notes/c/${cur.documentId}/${encodeURIComponent(chapterOf(cur))}`;
    const openIt = () => { location.hash = isPdf(d) ? `#/read/${d.id}?page=${cur.pageNumber}&hl=${cur.id}` : `#/read/${d.id}?pos=${cur.pageNumber}&hl=${cur.id}`; };
    add(mid, crumbs(crumb(cur.documentTitle, `#/notes/b/${cur.documentId}`), chapterOf(cur) ? crumb(chapterOf(cur), chapHref) : null, cur.title),
      h('h1', { class: 'nvtitle' }, cur.title || 'Untitled'),
      h('div', { class: 'nvprops' },
        h('div', null, h('span', { class: 'label' }, 'Tags'), tagsOf.length ? h('span', { class: 'tags' }, tagsOf.map(t => h('span', { class: 'tag', onclick: () => { vault.tag = t; drawVault(); } }, '#' + t))) : h('span', { class: 'muted' }, 'none')),
        h('div', null, h('span', { class: 'label' }, 'Source'), h('span', null, h('a', { href: `#/book/${cur.documentId}` }, cur.documentTitle), cur.documentAuthor ? ' · ' + cur.documentAuthor : '')),
        h('div', null, h('span', { class: 'label' }, 'Place'), h('span', null, noteLocus(cur, d) || '—'))),
      cur.highlightedText ? h('blockquote', { class: 'nvquote', style: { borderLeftColor: cur.colorHex || '#000' } }, cur.highlightedText) : null,
      cur.userNotes ? h('div', { class: 'nvtext' }, noteBody(cur.userNotes, cur, vault.idx, go)) : h('div', { class: 'muted nvtext' }, 'No text of your own on this note yet. Press Edit to write some; type [[ to link to another note.'),
      h('div', { class: 'acts nvacts' },
        h('button', { class: 'btn fill', onclick: () => editNote(cur, () => vaultReload()) }, 'Edit'),
        d && canRead(d) ? h('button', { class: 'btn', onclick: openIt }, 'Show in book') : null,
        h('button', { class: 'btn', onclick: () => copyFromFile(`doc/${cur.documentId}/export/note?id=${cur.id}`, 'Note copied as Markdown') }, 'Copy as Markdown'),
        h('button', { class: 'btn', onclick: () => download(`doc/${cur.documentId}/export/note?id=${cur.id}`, 'Preparing the note…') }, '.md'),
        h('button', { class: 'btn quiet', onclick: () => confirmBox('Delete this note?', cur.title, 'Delete', async () => {
          try { await api(`highlight/${cur.id}`, { method: 'DELETE' }); toast('Note deleted'); await loadVault(); vaultGo(chapHref); } catch (e) { fail(e); }
        }) }, 'Delete')),
      h('div', { class: 'nvback' }, h('div', { class: 'label' }, `Linked mentions · ${back.length}`),
        back.length ? back.map(b => h('div', { class: 'nvmention' }, linkTo(b), h('span', { class: 'muted' }, ' · ' + b.documentTitle),
          h('div', { class: 'muted' }, (String(b.userNotes).split('\n').find(l => norm(l).includes(norm(cur.title))) || '').slice(0, 200))))
          : h('div', { class: 'muted' }, 'No other note links here. In another note, type [[ and pick this one.')));
    gNodes = [{ id: 'n' + cur.id, label: cur.title, main: true }, { id: 'chap', label: chapterOf(cur) || cur.documentTitle, href: chapHref, kind: 'page' }];
    gEdges = [['chap', 'n' + cur.id]];
    const seen = new Set([cur.id]);
    outs.concat(back).forEach(x => { if (seen.has(x.id)) return; seen.add(x.id); gNodes.push({ id: 'n' + x.id, label: x.title, href: `#/notes/n/${x.id}` }); });
    outs.forEach(x => gEdges.push(['n' + cur.id, 'n' + x.id])); back.forEach(x => gEdges.push(['n' + x.id, 'n' + cur.id]));
    seen.forEach(a => { if (a !== cur.id) vault.idx.out(a).forEach(x => { if (seen.has(x.id) && x.id !== cur.id) gEdges.push(['n' + a, 'n' + x.id]); }); });
  } else if (kind === 'b' || kind === 'c') {
    // An overview page: the notes of a book or of one chapter, as links under headings.
    const notes = notesCache.list.filter(n => n.documentId === curBook && (kind === 'b' || chapterOf(n) === curChap)).sort((x, y) => x.pageNumber - y.pageNumber || x.created - y.created);
    const title = kind === 'b' ? bookTitle(curBook) : (curChap || 'No chapter');
    add(mid, crumbs(kind === 'c' ? crumb(bookTitle(curBook), `#/notes/b/${curBook}`) : null, title),
      h('h1', { class: 'nvtitle' }, kind === 'b' ? 'Notes on ' + title : title),
      h('div', { class: 'nvprops' },
        h('div', null, h('span', { class: 'label' }, 'Source'), h('span', null, h('a', { href: `#/book/${curBook}` }, bookTitle(curBook)))),
        h('div', null, h('span', { class: 'label' }, 'Notes'), h('span', null, String(notes.length)))));
    if (!notes.length) add(mid, h('div', { class: 'muted' }, 'No notes here.'));
    gNodes = [{ id: 'page', label: title, main: true }];
    if (kind === 'c') { gNodes.push({ id: 'book', label: bookTitle(curBook), href: `#/notes/b/${curBook}`, kind: 'page' }); gEdges.push(['book', 'page']); }
    // The page is automatic (notes in page order, a book's under its chapters) until you arrange it;
    // after that it keeps your order and headings, and notes written later wait at the bottom.
    const key = kind === 'b' ? 'b:' + curBook : 'c:' + curBook + ':' + curChap, saved = vault.pages[key], byId = new Map(notes.map(n => [n.id, n]));
    const auto = () => {
      const groups = new Map(); notes.forEach(n => { const k = kind === 'b' ? chapterOf(n) : ''; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(n); });
      const items = [];
      [...groups.entries()].forEach(([name, list]) => { if (kind === 'b' && (groups.size > 1 || name)) items.push({ h: name || 'No chapter', chap: name }); list.forEach(n => items.push({ n: n.id })); });
      return items;
    };
    let items = saved ? (saved.items || []).filter(x => x.h != null || byId.has(x.n)) : auto();
    const placed = new Set(items.filter(x => x.n != null).map(x => x.n)), fresh = saved ? notes.filter(n => !placed.has(n.id)) : [];
    const where = n => n.printedPageLabel ? 'p. ' + n.printedPageLabel : noteLocus(n, notesCache.docs[n.documentId]).split(' · ').pop();
    const arranging = vault.arr && vault.arr.key === key;
    if (!arranging) {
      if (notes.length) add(mid, h('div', { class: 'nvacts', style: { margin: '0 0 6px' } },
        h('button', { class: 'btn', title: 'Put the notes in your own order and add headings', onclick: () => { vault.arr = { key, items: items.concat(fresh.map(n => ({ n: n.id }))).map(x => Object.assign({}, x)) }; drawVault(); } }, 'Arrange'),
        saved ? h('span', { class: 'muted', style: { alignSelf: 'center', fontSize: '12px' } }, 'Your own arrangement') : h('span', { class: 'muted', style: { alignSelf: 'center', fontSize: '12px' } }, 'Automatic: in page order')));
      const show = n => {
        add(mid, h('div', { class: 'nvitem' }, linkTo(n), h('span', { class: 'muted' }, ' · ' + where(n))));
        if (gNodes.length < 42) { gNodes.push({ id: 'n' + n.id, label: n.title, href: `#/notes/n/${n.id}` }); gEdges.push(['page', 'n' + n.id]); }
      };
      items.forEach((x, k) => {
        if (x.h != null) {
          const hid = 'nvh' + k; outline.push([x.h, hid]);
          add(mid, h('h2', { class: 'nvh', id: hid }, x.chap != null ? crumb(x.h, `#/notes/c/${curBook}/${encodeURIComponent(x.chap)}`) : x.h));
        } else show(byId.get(x.n));
      });
      if (fresh.length) { outline.push(['New notes', 'nvhnew']); add(mid, h('h2', { class: 'nvh', id: 'nvhnew' }, 'New notes ', h('span', { class: 'muted', style: { fontSize: '13px', fontWeight: 400 } }, '· not placed yet')), []); fresh.forEach(show); }
      notes.forEach(n => vault.idx.out(n.id).forEach(x => { if (byId.has(x.id)) gEdges.push(['n' + n.id, 'n' + x.id]); }));
    } else {
      // Arranging: drag the rows (or use the arrows), add headings, then Done.
      const A = vault.arr, list = h('div', { class: 'nvarr' });
      const move = (from, to) => { if (to < 0 || to >= A.items.length || from === to) return; const [x] = A.items.splice(from, 1); A.items.splice(to, 0, x); drawList(); };
      let dragFrom = -1;
      const drawList = focusAt => {
        clear(list);
        A.items.forEach((x, k) => {
          const row = h('div', { class: 'nvarow' + (x.h != null ? ' head' : ''), draggable: 'true' },
            h('span', { class: 'nvgrip', title: 'Drag to move' }, '⋮⋮'),
            x.h != null ? h('input', { value: x.h, placeholder: 'Heading', oninput: e => { x.h = e.target.value; delete x.chap; }, onmousedown: e => e.stopPropagation() })
              : h('span', { class: 'nvname' }, (byId.get(x.n) || {}).title || 'Untitled', h('span', { class: 'muted' }, ' · ' + where(byId.get(x.n)))),
            h('button', { class: 'btn quiet', title: 'Add a heading above this row', onclick: () => { A.items.splice(k, 0, { h: '' }); drawList(k); } }, '+ Heading'),
            h('button', { class: 'btn quiet', title: 'Move up', onclick: () => move(k, k - 1) }, '↑'),
            h('button', { class: 'btn quiet', title: 'Move down', onclick: () => move(k, k + 1) }, '↓'),
            x.h != null ? h('button', { class: 'btn quiet', title: 'Remove this heading (the notes stay)', onclick: () => { A.items.splice(k, 1); drawList(); } }, '×') : h('span', { style: { width: '26px' } }));
          row.addEventListener('dragstart', e => { if (e.target.tagName === 'INPUT') { e.preventDefault(); return; } dragFrom = k; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(k)); row.classList.add('dragging'); });
          row.addEventListener('dragend', () => { dragFrom = -1; list.querySelectorAll('.over,.dragging').forEach(r => r.classList.remove('over', 'dragging')); });
          row.addEventListener('dragover', e => { if (dragFrom < 0) return; e.preventDefault(); list.querySelectorAll('.over').forEach(r => r.classList.remove('over')); row.classList.add('over'); });
          row.addEventListener('drop', e => { if (dragFrom < 0) return; e.preventDefault(); const from = dragFrom; dragFrom = -1; move(from, from < k ? k - 1 : k); });
          list.append(row);
        });
        const end = h('div', { class: 'nvarow end' }, h('span', { class: 'muted' }, 'Drop here to put it last'));
        end.addEventListener('dragover', e => { if (dragFrom < 0) return; e.preventDefault(); list.querySelectorAll('.over').forEach(r => r.classList.remove('over')); end.classList.add('over'); });
        end.addEventListener('drop', e => { if (dragFrom < 0) return; e.preventDefault(); const from = dragFrom; dragFrom = -1; move(from, A.items.length - 1); });
        list.append(end);
        if (focusAt != null) { const inp = list.children[focusAt] && list.children[focusAt].querySelector('input'); if (inp) inp.focus(); }
      };
      const store = async page => {
        try { await api('note-pages', { method: 'PUT', json: { key, page } }); if (page) vault.pages[key] = page; else delete vault.pages[key]; vault.arr = null; drawVault(); }
        catch (e) { fail(e); }
      };
      add(mid, h('div', { class: 'nvacts', style: { margin: '0 0 10px' } },
        h('button', { class: 'btn fill', onclick: () => store({ items: A.items.filter(x => x.h == null || x.h.trim()).map(x => x.h != null ? { h: x.h.trim() } : { n: x.n }) }) }, 'Done'),
        h('button', { class: 'btn', onclick: () => { vault.arr = null; drawVault(); } }, 'Cancel'),
        h('button', { class: 'btn', onclick: () => { A.items.push({ h: '' }); drawList(A.items.length - 1); } }, '+ Heading at the end'),
        saved ? h('button', { class: 'btn quiet', title: 'Forget your arrangement and list the notes in page order again', onclick: () => confirmBox('Back to the automatic page?', 'Your order and headings on this page are forgotten. The notes themselves are not touched.', 'Reset', () => store(null)) }, 'Reset to automatic') : null),
        h('div', { class: 'muted', style: { fontSize: '13px', marginBottom: '8px' } }, 'Drag the rows by ⋮⋮ or use the arrows. A heading groups the notes below it.'), list);
      drawList();
    }
  } else {
    add(mid, h('h1', { class: 'nvtitle' }, 'Notes'),
      h('p', { class: 'muted' }, 'Pick a book, a chapter or a note on the left. A book or chapter opens its overview page, which lists its notes as links.'),
      h('p', { class: 'muted' }, 'To link one note to another, edit it and type [[ — a list of note titles appears. The linked note then shows it under “Linked mentions”, and both appear in the graph.'),
      h('div', { class: 'label', style: { marginTop: '18px' } }, 'Latest notes'),
      notesCache.list.slice().sort((a, b) => b.created - a.created).slice(0, 15).map(n => h('div', { class: 'nvitem' }, linkTo(n), h('span', { class: 'muted' }, ' · ' + n.documentTitle))));
  }
  if (gNodes.length > 1) add(right, h('div', { class: 'label' }, 'Graph'), noteGraph(gNodes, gEdges, go));
  if (outline.length) add(right, h('div', { class: 'label', style: { marginTop: '14px' } }, 'Outline'),
    outline.map(([t, id]) => h('div', { class: 'nvout', onclick: () => { const e = document.getElementById(id); if (e) e.scrollIntoView({ behavior: 'smooth', block: 'start' }); } }, t)));
  if (cur) {
    const outs = vault.idx.out(cur.id);
    add(right, h('div', { class: 'label', style: { marginTop: '14px' } }, `Links from this note · ${outs.length}`), outs.length ? outs.map(x => h('div', { class: 'nvout' }, linkTo(x))) : h('div', { class: 'muted' }, 'None yet.'));
  }
  vault.mid.scrollTop = 0;
}
