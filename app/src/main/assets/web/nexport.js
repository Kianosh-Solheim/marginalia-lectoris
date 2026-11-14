/* Marginalia Lectoris — notes out as a small site in one HTML file, or as a PDF.
 *
 * What is open in the notes view decides what goes in: one note, a chapter, a book (or everything).
 * The HTML is a site in a single file (homepage, tree, a page per book, chapter and note) with the
 * logo and the covers inside it. The PDF has a front page, contents and the notes. Either can link
 * each note to its page in the source PDF, which then travels with it.
 */

// ------------------------------------------------------------------ name and logo (kept on the tablet)

let exportPrefsCache = null;
async function exportPrefs() {
  if (exportPrefsCache) return exportPrefsCache;
  let all = {}; try { all = await api('note-pages'); } catch (_) {}
  exportPrefsCache = Object.assign({ name: '', logo: '' }, all._export || {});
  return exportPrefsCache;
}
async function saveExportPrefs(p) {
  exportPrefsCache = Object.assign({}, exportPrefsCache || {}, p);
  await api('note-pages', { method: 'PUT', json: { key: '_export', page: exportPrefsCache } });
}
/** A picture file → a PNG data URL no larger than [max] pixels on its longest side. */
function imageToPng(blob, max) {
  return new Promise((res, rej) => {
    const url = URL.createObjectURL(blob), img = new Image();
    img.onload = () => {
      const k = Math.min(1, max / Math.max(img.naturalWidth || 1, img.naturalHeight || 1));
      const c = document.createElement('canvas'); c.width = Math.max(1, Math.round((img.naturalWidth || max) * k)); c.height = Math.max(1, Math.round((img.naturalHeight || max) * k));
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      try { res(c.toDataURL('image/png')); } catch (e) { rej(e); }
    };
    img.onerror = () => { URL.revokeObjectURL(url); rej(new Error('Could not read the picture')); };
    img.src = url;
  });
}
const dataUrlBytes = u => { const b = atob(u.split(',')[1]), a = new Uint8Array(b.length); for (let i = 0; i < b.length; i++) a[i] = b.charCodeAt(i); return a; };

/** Settings ▸ Notes export: the name and the logo on the front page. */
function notesExportSettingsSection() {
  const sec = h('section', { class: 'set-group', id: 'set-notes-export' }, h('h2', null, 'Notes export'));
  const name = h('input', { placeholder: 'e.g. Kianosh', style: { maxWidth: '260px' } });
  const prev = h('div', { class: 'nxlogo' }), file = h('input', { type: 'file', accept: 'image/*', hidden: true });
  const draw = p => { clear(prev); if (p.logo) prev.append(h('img', { src: p.logo, alt: 'Logo' })); else prev.append(h('span', { class: 'muted' }, 'No logo')); };
  exportPrefs().then(p => { name.value = p.name || ''; draw(p); });
  name.addEventListener('change', async () => { try { await saveExportPrefs({ name: name.value.trim() }); toast('Saved on the tablet', 1400); } catch (e) { fail(e); } });
  file.addEventListener('change', async () => {
    if (!file.files[0]) return;
    try { const logo = await imageToPng(file.files[0], 600); await saveExportPrefs({ logo }); draw(exportPrefsCache); toast('Logo saved on the tablet', 1400); } catch (e) { fail(e); }
    file.value = '';
  });
  sec.append(
    h('p', { class: 'muted', style: { fontSize: '13px' } }, 'Shown on the front page when you export notes as a site (HTML) or a PDF from the Notes view: “(Name)’s notes on …”.'),
    h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('b', null, 'Your name')), name),
    h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('b', null, 'Logo')), h('div', { class: 'row', style: { gap: '10px', alignItems: 'center' } }, prev, file,
      h('button', { class: 'btn', onclick: () => file.click() }, 'Choose a picture…'),
      h('button', { class: 'btn quiet', onclick: async () => { try { await saveExportPrefs({ logo: '' }); draw(exportPrefsCache); } catch (e) { fail(e); } } }, 'Remove'))));
  return sec;
}

// ------------------------------------------------------------------ what goes in

/**
 * The notes of the chosen scope, arranged as in the notes view.
 * { title, owner, logo, date, count, books: [{ id, title, author, cover, source, items, chapters: [{ name, number, items }] }], notes: { id: … } }
 */
async function exportModel(scope, opt, progress) {
  const prefs = await exportPrefs();
  const keep = n => !(opt.skipEmpty && !(n.userNotes || '').trim());
  const all = notesCache.list;
  let picked;
  if (scope.kind === 'n') picked = all.filter(n => n.id === scope.noteId);
  else if (scope.kind === 'c') picked = all.filter(n => n.documentId === scope.bookId && chapterOf(n) === scope.chap && keep(n));
  else if (scope.kind === 'b') picked = all.filter(n => n.documentId === scope.bookId && keep(n));
  else picked = all.filter(keep);
  const ids = new Set(picked.map(n => n.id));
  const bookIds = [...new Set(picked.map(n => n.documentId))];
  const model = { owner: prefs.name || '', logo: opt.front ? (prefs.logo || '') : '', date: new Date().toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' }),
    count: picked.length, opt: { quotes: opt.quotes, text: opt.text, tags: opt.tags, mentions: opt.mentions, links: opt.links, front: opt.front }, books: [], notes: {}, sources: [] };
  const one = scope.kind === 'n' ? picked[0] : null;
  model.subject = one ? (one.title || 'a note') : scope.kind === 'c' ? `${scope.chap || 'a chapter'} in ${vaultBookTitle(scope.bookId)}` : scope.kind === 'b' ? vaultBookTitle(scope.bookId) : 'my reading';
  model.title = (model.owner ? model.owner + (/s$/i.test(model.owner) ? '’' : '’s') + ' notes on ' : 'Notes on ') + model.subject;

  for (const bookId of bookIds.sort((a, b) => vaultBookTitle(a).localeCompare(vaultBookTitle(b)))) {
    const d = notesCache.docs[bookId] || {}, mine = picked.filter(n => n.documentId === bookId);
    const book = { id: bookId, title: vaultBookTitle(bookId), author: (mine[0] || {}).documentAuthor || '', cover: '', source: null, chapters: [], items: [] };
    if (opt.covers && d.id) {
      try { const r = await fetch(coverUrl(d), { credentials: 'same-origin' }); if (r.ok) book.cover = await imageToPng(await r.blob(), 420); } catch (_) {}
    }
    // The source file travels with the export (PDF only: a browser cannot open an EPUB at a page).
    if (opt.source !== 'none' && d.id && isPdf(d) && canRead(d)) {
      if (progress) progress(`Fetching the file of “${book.title}”…`);
      try {
        const r = await fetch(`/api/doc/${d.id}/file`, { credentials: 'same-origin' });
        if (r.ok) { const blob = await r.blob(); book.source = { name: mdName(book.title).slice(0, 80) + '.pdf', size: blob.size }; model.sources.push({ bookId, name: book.source.name, blob }); }
      } catch (_) {}
    }
    const chapters = [...new Set(mine.sort((x, y) => x.pageNumber - y.pageNumber || x.created - y.created).map(chapterOf))];
    const itemsOf = (kind, chap) => pageItems(kind, bookId, chap).items.filter(x => x.h != null || ids.has(x.n.id)).map(x => x.h != null ? { h: x.h, chap: x.chap } : { n: x.n.id })
      .filter((x, i, a) => x.n != null || (a[i + 1] && a[i + 1].n != null));   // a heading with nothing under it is dropped
    chapters.forEach(c => book.chapters.push({ name: c, number: c ? chapterNumber(bookId, c, null) : null, items: itemsOf('c', c) }));
    book.items = itemsOf('b', '').map(x => x.h != null && x.chap != null ? { h: x.h, c: chapters.indexOf(x.chap) } : x);
    model.books.push(book);
  }
  picked.forEach(n => {
    const d = notesCache.docs[n.documentId];
    const pdf = String(n.pdfPages || n.pageNumber).split(/[–-]/).map(x => +x.trim()).filter(Boolean);
    const printed = String(n.printedPageLabel || '').split('–').map(x => x.trim()).filter(Boolean);
    const isP = !d || isPdf(d);
    const segs = [];
    if (opt.text) { let last = 0; const s = String(n.userNotes || '');
      for (const m of s.matchAll(NOTE_LINK)) { if (m.index > last) segs.push({ t: s.slice(last, m.index) }); const t = vault.idx.resolve(m[1], n); segs.push(t && ids.has(t.id) ? { l: t.id, t: m[2] || m[1] } : { t: m[2] || m[1] }); last = m.index + m[0].length; }
      if (last < s.length) segs.push({ t: s.slice(last) }); }
    model.notes[n.id] = { id: n.id, book: n.documentId, chap: chapterOf(n), title: n.title || 'Untitled', quote: opt.quotes ? (n.highlightedText || '') : '', text: segs,
      tags: opt.tags ? splitTags(n.tags) : [], color: n.colorHex || '', where: noteLocus(n, d),
      pages: isP ? { from: pdf[0] || n.pageNumber, to: pdf[pdf.length - 1] || pdf[0] || n.pageNumber, a: printed[0] || '', b: printed.length > 1 ? printed[printed.length - 1] : '' } : null,
      back: opt.mentions ? vault.idx.back(n.id).filter(x => ids.has(x.id)).map(x => x.id) : [] };
  });
  return model;
}

// ------------------------------------------------------------------ the site in one file

const EXPORT_CSS = `
:root{--ink:#111;--paper:#fff;--grey:#52525b;--rule:#e4e4e7;--wash:#f4f4f5;--link:#4338ca;--tint:#e0e7ff}
@media (prefers-color-scheme:dark){:root{--ink:#e7e5e4;--paper:#161618;--grey:#a8a8ad;--rule:#34343a;--wash:#232327;--link:#a5b4fc;--tint:#2c3260}}
*{box-sizing:border-box}html,body{margin:0;height:100%}
body{background:var(--paper);color:var(--ink);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
a{color:var(--link)}h1,h2,h3{font-family:"Iowan Old Style","Palatino Linotype",Palatino,Georgia,serif;line-height:1.2}
.app{display:grid;grid-template-columns:300px minmax(0,1fr);height:100vh}
.side{border-right:1px solid var(--rule);overflow-y:auto;padding:10px 8px 40px;font-size:13px}
.main{overflow-y:auto;padding:28px max(24px,calc((100% - 760px)/2)) 90px}
.brand{display:flex;gap:8px;align-items:center;padding:4px 6px 10px;cursor:pointer;font-weight:700}.brand img{height:26px}
.side input{width:100%;padding:6px 8px;margin-bottom:8px;border:1px solid var(--rule);background:var(--paper);color:var(--ink);font:inherit}
.row{display:flex;gap:4px;align-items:center;padding:3px 6px;border-radius:3px;cursor:pointer}.row:hover{background:var(--wash)}.row.on{background:var(--tint)}
.row.b{font-weight:600}.row.c{padding-left:20px}.row.n{padding-left:38px}.row span.t{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.row .k{color:var(--grey);font-size:11px}.car{width:14px;flex:none;font-size:10px;text-align:center}
.crumbs{font-size:12px;color:var(--grey);margin-bottom:10px}.crumbs a{color:inherit}
h1{font-size:30px;margin:0 0 14px}h2{font-size:21px;margin:26px 0 8px}
.props{display:grid;gap:5px;margin:0 0 18px;font-size:13px}.props>div{display:grid;grid-template-columns:120px 1fr;gap:10px}
.lab{font:11px ui-monospace,Consolas,monospace;text-transform:uppercase;color:var(--grey);letter-spacing:.04em}
blockquote{margin:0 0 14px;padding-left:12px;border-left:3px solid var(--ink);font:17px/1.5 "Iowan Old Style",Palatino,Georgia,serif;white-space:pre-wrap;overflow-wrap:anywhere}
.text{white-space:pre-wrap;overflow-wrap:anywhere}.tag{display:inline-block;border:1px solid var(--rule);padding:0 7px;margin-right:4px;font:11px ui-monospace,Consolas,monospace}
.item{padding:4px 0}.muted{color:var(--grey)}.back{margin-top:26px;padding-top:12px;border-top:1px solid var(--rule)}
.nav{display:flex;justify-content:space-between;gap:12px;margin-top:30px;padding-top:12px;border-top:1px solid var(--rule);font-size:13px}
.front{text-align:center;padding:6vh 0 30px}.front img.logo{max-width:220px;max-height:180px}.front h1{font-size:36px;margin:22px 0 8px}
.books{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:18px;margin-top:26px;text-align:left}
.bk{cursor:pointer}.bk img{width:100%;aspect-ratio:2/3;object-fit:cover;border:1px solid var(--rule)}.bk .ph{width:100%;aspect-ratio:2/3;border:1px solid var(--rule);background:var(--wash);display:flex;align-items:center;justify-content:center;padding:10px;text-align:center;font-family:Georgia,serif}
.bk b{display:block;margin-top:6px;font-size:14px;line-height:1.25}.cover{float:right;width:130px;margin:0 0 12px 18px;border:1px solid var(--rule)}
.note{break-inside:avoid;margin-bottom:26px}
@media (max-width:760px){.app{display:block;height:auto}.side{border-right:0;border-bottom:1px solid var(--rule);max-height:38vh}}
@media print{.side{display:none}.app{display:block;height:auto}.main{overflow:visible;padding:0}.front{page-break-after:always;padding-top:28vh}h1.bt{page-break-before:always}a{color:inherit;text-decoration:none}}
`;

/** The code of the exported site. It runs inside the exported file and uses nothing from outside it. */
function exportRuntime() {
  const D = JSON.parse(document.getElementById('data').textContent);
  const el = (tag, attrs, ...kids) => {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => { if (v == null || v === false) return; if (k === 'class') e.className = v; else if (k.startsWith('on')) e.addEventListener(k.slice(2), v); else e.setAttribute(k, v); });
    kids.flat(4).forEach(k => { if (k != null && k !== false) e.append(k.nodeType ? k : document.createTextNode(String(k))); });
    return e;
  };
  const side = document.getElementById('side'), main = document.getElementById('main');
  const put = (parent, ...kids) => kids.flat(4).forEach(k => { if (k != null && k !== false) parent.append(k.nodeType ? k : document.createTextNode(String(k))); });
  const book = id => D.books.find(b => String(b.id) === String(id));
  const go = h => { location.hash = h; };
  const link = (h, label) => el('a', { href: h }, label);
  const order = []; D.books.forEach(b => b.chapters.forEach(c => c.items.forEach(x => { if (x.n != null) order.push(x.n); })));
  let q = '', shut = {};
  /** Opens the source PDF at a page: the copy inside this file, or the one in the files folder beside it. */
  const blobs = {};
  const openSource = (b, page, ev) => {
    const tag = document.getElementById('src-' + b.id);
    if (!tag) return true;                                 // a normal link to files/…pdf#page=N
    ev.preventDefault();
    if (!blobs[b.id]) { const s = atob(tag.textContent.trim()), a = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i); blobs[b.id] = URL.createObjectURL(new Blob([a], { type: 'application/pdf' })); }
    window.open(blobs[b.id] + '#page=' + page, '_blank');
    return false;
  };
  const place = n => {
    const b = book(n.book), out = [n.chap ? n.chap + ' · ' : ''];
    if (!n.pages) { out.push(n.where.replace(n.chap + ' · ', '')); return out; }
    const one = (page, label) => D.opt.links && b.source ? el('a', { href: 'files/' + encodeURIComponent(b.source.name) + '#page=' + page, target: '_blank', onclick: e => openSource(b, page, e) }, 'page ' + label) : 'page ' + label;
    out.push(one(n.pages.from, n.pages.a || n.pages.from));
    if (n.pages.to > n.pages.from) out.push(' – ', one(n.pages.to, n.pages.b || n.pages.to));
    if (n.pages.a) out.push(el('span', { class: 'muted' }, ' · PDF p. ' + n.pages.from + (n.pages.to > n.pages.from ? '–' + n.pages.to : '')));
    return out;
  };
  const noteLink = id => { const n = D.notes[id]; return D.opt.links ? link('#/n/' + id, n.title) : n.title; };
  const noteBody = (n, full) => [
    n.quote ? el('blockquote', { style: n.color ? 'border-left-color:' + n.color : null }, n.quote) : null,
    n.text.length ? el('div', { class: 'text' }, n.text.map(s => s.l != null && D.opt.links ? link('#/n/' + s.l, s.t) : s.t)) : null,
    n.tags.length ? el('div', { style: 'margin-top:8px' }, n.tags.map(t => el('span', { class: 'tag' }, '#' + t))) : null,
    full ? null : el('div', { class: 'muted', style: 'font-size:12px;margin-top:6px' }, place(n))];
  const items = (list, b) => list.map(x => x.h != null
    ? el('h2', null, x.c != null && x.c >= 0 && D.opt.links ? link('#/c/' + b.id + '/' + x.c, x.h) : x.h)
    : el('div', { class: 'item' }, noteLink(x.n), el('span', { class: 'muted' }, ' · ', D.notes[x.n].pages && D.notes[x.n].pages.a ? 'p. ' + D.notes[x.n].pages.a + (D.notes[x.n].pages.b ? '–' + D.notes[x.n].pages.b : '') : (D.notes[x.n].pages ? 'PDF p. ' + D.notes[x.n].pages.from : ''))));
  const front = () => el('div', { class: 'front' }, D.logo ? el('img', { class: 'logo', src: D.logo, alt: '' }) : null, el('h1', null, D.title),
    el('div', { class: 'muted' }, D.date + ' · ' + D.count + ' note' + (D.count === 1 ? '' : 's') + ' · ' + D.books.length + ' source' + (D.books.length === 1 ? '' : 's')));

  function tree() {
    side.textContent = '';
    const words = q.toLowerCase().split(/\s+/).filter(Boolean);
    const hit = n => !words.length || words.every(w => (n.title + ' ' + n.quote + ' ' + n.text.map(s => s.t).join('') + ' ' + n.tags.join(' ')).toLowerCase().includes(w));
    const search = el('input', { type: 'search', placeholder: 'Search the notes…', value: q });
    search.addEventListener('input', () => { q = search.value; tree(); const s = side.querySelector('input'); s.focus(); s.setSelectionRange(q.length, q.length); });
    put(side, el('div', { class: 'brand', onclick: () => go('#/') }, D.logo ? el('img', { src: D.logo, alt: '' }) : null, el('span', null, D.owner ? D.owner + '’s notes' : 'Notes')), search);
    const cur = location.hash;
    D.books.forEach(b => {
      const bk = 'b' + b.id, open = words.length || !shut[bk];
      put(side, el('div', { class: 'row b' + (cur === '#/b/' + b.id ? ' on' : '') }, el('span', { class: 'car', onclick: () => { shut[bk] = !shut[bk]; tree(); } }, open ? '▾' : '▸'),
        el('span', { class: 't', title: b.title, onclick: () => go('#/b/' + b.id) }, b.title)));
      if (!open) return;
      b.chapters.forEach((c, i) => {
        const notes = c.items.filter(x => x.n != null && hit(D.notes[x.n]));
        if (words.length && !notes.length) return;
        const ck = bk + ':' + i, openC = words.length || !shut[ck], plain = b.chapters.length === 1 && !c.name;
        if (!plain) put(side, el('div', { class: 'row c' + (cur === '#/c/' + b.id + '/' + i ? ' on' : '') }, el('span', { class: 'car', onclick: () => { shut[ck] = !shut[ck]; tree(); } }, openC ? '▾' : '▸'),
          el('span', { class: 't', title: c.name, onclick: () => go('#/c/' + b.id + '/' + i) }, c.name || 'No chapter'), el('span', { class: 'k' }, notes.length)));
        if (openC) notes.forEach(x => put(side, el('div', { class: 'row n' + (cur === '#/n/' + x.n ? ' on' : ''), title: D.notes[x.n].title, onclick: () => go('#/n/' + x.n) }, el('span', { class: 't' }, D.notes[x.n].title))));
      });
    });
  }
  function page() {
    const p = location.hash.replace(/^#\/?/, '').split('/');
    main.textContent = ''; main.scrollTop = 0;
    if (p[0] === 'print') {
      // Everything in one run, for paper.
      if (D.opt.front) put(main, front());
      D.books.forEach(b => { put(main, el('h1', { class: 'bt' }, b.title), b.author ? el('div', { class: 'muted' }, b.author) : null);
        b.chapters.forEach(c => { if (c.name) put(main, el('h2', null, c.name));
          c.items.forEach(x => put(main, x.h != null ? el('h3', null, x.h) : el('div', { class: 'note' }, el('h3', { style: 'margin:0 0 6px' }, D.notes[x.n].title), noteBody(D.notes[x.n], false)))); }); });
      document.querySelector('.app').className = 'app printing'; side.style.display = 'none';
      setTimeout(() => window.print(), 400);
      return;
    }
    if (p[0] === 'n' && D.notes[p[1]]) {
      const n = D.notes[p[1]], b = book(n.book), ci = b.chapters.findIndex(c => c.name === n.chap), at = order.indexOf(n.id);
      put(main, el('div', { class: 'crumbs' }, link('#/', 'Home'), ' / ', link('#/b/' + b.id, b.title), n.chap ? [' / ', link('#/c/' + b.id + '/' + ci, n.chap)] : null),
        el('h1', null, n.title),
        el('div', { class: 'props' },
          el('div', null, el('span', { class: 'lab' }, 'Source'), el('span', null, b.title + (b.author ? ' · ' + b.author : ''))),
          el('div', null, el('span', { class: 'lab' }, 'Place'), el('span', null, place(n)))),
        noteBody(n, true),
        n.back.length ? el('div', { class: 'back' }, el('div', { class: 'lab' }, 'Linked mentions · ' + n.back.length), n.back.map(id => el('div', { class: 'item' }, noteLink(id)))) : null,
        el('div', { class: 'nav' }, at > 0 ? link('#/n/' + order[at - 1], '‹ ' + D.notes[order[at - 1]].title) : el('span'), at >= 0 && at < order.length - 1 ? link('#/n/' + order[at + 1], D.notes[order[at + 1]].title + ' ›') : el('span')));
    } else if (p[0] === 'c' && book(p[1]) && book(p[1]).chapters[+p[2]]) {
      const b = book(p[1]), c = b.chapters[+p[2]];
      put(main, el('div', { class: 'crumbs' }, link('#/', 'Home'), ' / ', link('#/b/' + b.id, b.title)), el('h1', null, c.name || 'No chapter'),
        el('div', { class: 'props' }, el('div', null, el('span', { class: 'lab' }, 'Book'), el('span', null, b.title)),
          c.number != null && c.number !== '' ? el('div', null, el('span', { class: 'lab' }, 'Chapter number'), el('span', null, String(c.number))) : null,
          el('div', null, el('span', { class: 'lab' }, 'Notes'), el('span', null, String(c.items.filter(x => x.n != null).length)))),
        items(c.items, b));
    } else if (p[0] === 'b' && book(p[1])) {
      const b = book(p[1]);
      put(main, el('div', { class: 'crumbs' }, link('#/', 'Home')), b.cover ? el('img', { class: 'cover', src: b.cover, alt: '' }) : null, el('h1', null, 'Notes on ' + b.title),
        el('div', { class: 'props' }, b.author ? el('div', null, el('span', { class: 'lab' }, 'Author'), el('span', null, b.author)) : null,
          el('div', null, el('span', { class: 'lab' }, 'Notes'), el('span', null, String(b.chapters.reduce((a, c) => a + c.items.filter(x => x.n != null).length, 0)))),
          b.source ? el('div', null, el('span', { class: 'lab' }, 'File'), el('span', null, el('a', { href: 'files/' + encodeURIComponent(b.source.name), target: '_blank', onclick: e => openSource(b, 1, e) }, b.source.name))) : null),
        items(b.items, b));
    } else {
      put(main, D.opt.front ? front() : el('h1', null, D.title),
        D.books.length === 1 && D.count === 1 ? el('p', null, noteLink(order[0])) : el('div', { class: 'books' }, D.books.map(b => el('div', { class: 'bk', onclick: () => go('#/b/' + b.id) },
          b.cover ? el('img', { src: b.cover, alt: '' }) : el('div', { class: 'ph' }, b.title), el('b', null, b.title), el('span', { class: 'muted', style: 'font-size:12px' }, b.author)))));
    }
    tree();
  }
  window.addEventListener('hashchange', page);
  document.title = D.title;
  if (!location.hash && D.count === 1 && !D.opt.front) location.hash = '#/n/' + order[0]; else page();
}

const htmlEsc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
async function blobBase64(blob) {
  const a = new Uint8Array(await blob.arrayBuffer()); let s = '';
  for (let i = 0; i < a.length; i += 0x8000) s += String.fromCharCode.apply(null, a.subarray(i, i + 0x8000));
  return btoa(s);
}
/** The whole site as one HTML file. With [inside], the source PDFs are carried in the file itself. */
async function exportHtml(model, inside) {
  const data = Object.assign({}, model); delete data.sources;
  let embedded = '';
  if (inside) for (const s of model.sources) embedded += `<script type="application/octet-stream" id="src-${s.bookId}">${await blobBase64(s.blob)}</script>\n`;
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${htmlEsc(model.title)}</title>
<meta name="generator" content="Marginalia Lectoris">
<style>${EXPORT_CSS}</style></head>
<body><div class="app"><nav class="side" id="side"></nav><main class="main" id="main"></main></div>
<script type="application/json" id="data">${JSON.stringify(data).replace(/</g, '\\u003c')}</script>
${embedded}<script>(${exportRuntime.toString()})();</script>
</body></html>
`;
}

// ------------------------------------------------------------------ the PDF (set with Typst)

const ty = s => '#"' + String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/[\r\t]/g, ' ').replace(/\n/g, '\\n') + '";';
/** Text with its line breaks kept. */
const tyLines = s => String(s || '').split(/\n{2,}/).map(par => par.split('\n').map(ty).join('#linebreak();')).join('#parbreak();');
/** The notes as a Typst document: front page, contents, then books ▸ chapters ▸ notes. Returns { source, files }. */
function exportTypst(model) {
  const files = {}, L = [];
  const has = id => !!model.notes[id];
  L.push('#set document(title: ' + ty(model.title).slice(1, -1) + ')',
    '#set page(paper: "a4", margin: (x: 2.4cm, y: 2.4cm), numbering: "1")',
    '#set text(font: "Libertinus Serif", size: 11pt)', '#set par(justify: true, leading: 0.62em)',
    '#show link: set text(fill: rgb("#3730a3"))',
    '#show heading.where(level: 1): it => { pagebreak(weak: true); v(0.4cm); text(size: 22pt, it.body); v(0.3cm) }',
    '#show heading.where(level: 2): it => { v(0.5cm); text(size: 16pt, it.body); v(0.1cm) }',
    '#show heading.where(level: 3): it => { v(0.3cm); text(size: 13pt, style: "italic", it.body) }',
    '#show heading.where(level: 4): it => { v(0.25cm); text(size: 11.5pt, weight: "bold", it.body) }',
    '#show quote.where(block: true): it => block(inset: (left: 10pt, y: 2pt), stroke: (left: 1.5pt + luma(90)), it.body)');
  if (model.opt.front) {
    if (model.logo) files['logo.png'] = new Blob([dataUrlBytes(model.logo)], { type: 'image/png' });
    L.push('#page(numbering: none)[#align(center + horizon)[',
      model.logo ? '#image("/logo.png", height: 4.5cm, fit: "contain")\n#v(1.2cm)' : '',
      `#text(size: 26pt, weight: "bold")[${ty(model.title)}]`, '#v(0.6cm)',
      `#text(size: 12pt, fill: luma(90))[${ty(`${model.date} · ${model.count} note${model.count === 1 ? '' : 's'}`)}]`, ']]');
  }
  if (model.count > 1) L.push('#outline(title: [Contents], depth: 2)', '#pagebreak(weak: true)');
  const place = (n, b) => {
    if (!n.pages) return ty(n.where);
    const one = (page, label) => model.opt.links && b.source ? `#link("files/${encodeURIComponent(b.source.name)}#page=${page}")[${ty('page ' + label)}]` : ty('page ' + label);
    let s = (n.chap ? ty(n.chap + ' · ') : '') + one(n.pages.from, n.pages.a || n.pages.from);
    if (n.pages.to > n.pages.from) s += ty(' – ') + one(n.pages.to, n.pages.b || n.pages.to);
    if (n.pages.a) s += ty(` · PDF p. ${n.pages.from}${n.pages.to > n.pages.from ? '–' + n.pages.to : ''}`);
    return s;
  };
  model.books.forEach(b => {
    L.push(`= ${ty(b.title)} <b${b.id}>`);
    if (b.cover) { files[`cover-${b.id}.png`] = new Blob([dataUrlBytes(b.cover)], { type: 'image/png' }); L.push(`#align(center)[#image("/cover-${b.id}.png", height: 5cm, fit: "contain")]`); }
    if (b.author) L.push(`#text(fill: luma(90))[${ty(b.author)}]`, '');
    b.chapters.forEach(c => {
      if (c.name) L.push(`== ${ty(c.name)}`);
      c.items.forEach(x => {
        if (x.h != null) { L.push(`=== ${ty(x.h)}`); return; }
        const n = model.notes[x.n];
        L.push(`#heading(level: 4, outlined: false)[${ty(n.title)}] <n${n.id}>`);
        if (n.quote) L.push(`#quote(block: true)[${tyLines(n.quote)}]`);
        if (n.text.length) L.push('#block[' + n.text.map(s => s.l != null && model.opt.links && has(s.l) ? `#link(<n${s.l}>)[${ty(s.t)}]` : tyLines(s.t)).join('') + ']');
        const foot = [];
        if (n.tags.length) foot.push(ty(n.tags.map(t => '#' + t).join('  ')));
        foot.push(place(n, b));
        L.push(`#text(size: 9pt, fill: luma(90))[${foot.join(ty('   ·   '))}]`);
        if (n.back.length) L.push(`#text(size: 9pt, fill: luma(90))[${ty('Linked from: ')}${n.back.filter(has).map(id => model.opt.links ? `#link(<n${id}>)[${ty(model.notes[id].title)}]` : ty(model.notes[id].title)).join(ty('; '))}]`);
        L.push('#v(0.35cm)');
      });
    });
  });
  return { source: L.filter(x => x !== '').join('\n') + '\n', files };
}

// ------------------------------------------------------------------ the dialog

/** scope: { kind: 'n' | 'c' | 'b' | 'all', bookId, chap, noteId } — whatever is open in the notes view. */
function exportNotesDialog(scope) {
  const saved = (() => { try { return JSON.parse(localGet('ml.nxOpt', '{}')); } catch (_) { return {}; } })();
  const o = Object.assign({ format: 'html', quotes: true, text: true, tags: true, mentions: true, skipEmpty: false, covers: true, front: true, links: true, source: 'none' }, saved);
  const what = scope.kind === 'n' ? 'this note' : scope.kind === 'c' ? `the chapter “${scope.chap || 'No chapter'}”` : scope.kind === 'b' ? `the book “${vaultBookTitle(scope.bookId)}”` : 'all your notes';
  modal((box, close) => {
    box.classList.add('wide');
    const tick = (key, label, hint) => { const c = h('input', { type: 'checkbox' }); c.checked = !!o[key]; c.addEventListener('change', () => { o[key] = c.checked; draw(); }); return h('label', { class: 'nxtick', title: hint || '' }, c, h('span', null, label)); };
    const fmt = h('div', { class: 'wnmodes' }), src = h('select', { style: { width: 'auto' } }), srcNote = h('div', { class: 'muted', style: { fontSize: '12px' } }), info = h('div', { class: 'muted', style: { fontSize: '13px' } });
    const draw = () => {
      clear(fmt);
      [['html', 'A site in one file (.html)', 'Homepage, tree and a page per book, chapter and note. Opens in any browser, offline.'], ['pdf', 'PDF', 'Front page, contents, then the notes, set for paper.']].forEach(([k, l, d]) =>
        fmt.append(h('button', { class: 'wnmode' + (o.format === k ? ' on' : ''), onclick: () => { o.format = k; if (k === 'pdf' && o.source === 'inside') o.source = 'zip'; draw(); } }, h('b', null, l), h('div', { class: 'muted' }, d))));
      clear(src).append(h('option', { value: 'none' }, 'No, leave the source files out'), h('option', { value: 'zip' }, 'Yes, beside it in a zip (files stay small)'), o.format === 'html' ? h('option', { value: 'inside' }, 'Yes, inside the HTML (one file, but large)') : null);
      src.value = o.source;
      srcNote.textContent = o.source === 'none' ? 'Notes show their page, without a link to it.' : 'Each note links to its page in the source PDF. Only PDFs can travel along; notes from EPUBs get no page link.';
      exportPrefs().then(p => { info.textContent = o.front ? `Front page: “${(p.name ? p.name + (/s$/i.test(p.name) ? '’' : '’s') + ' notes on …' : 'Notes on …')}”${p.logo ? ', with your logo' : ', no logo yet'}. Name and logo are set in Settings ▸ Notes export.` : ''; });
    };
    src.addEventListener('change', () => { o.source = src.value; draw(); });
    const go = h('button', { class: 'btn fill', onclick: async () => { localSet('ml.nxOpt', JSON.stringify(o)); close(); await runNotesExport(scope, o, false); } }, 'Export');
    box.append(h('h2', null, 'Export notes'), h('div', { class: 'muted', style: { marginBottom: '10px' } }, 'What goes in: ' + what + '.'), fmt,
      h('div', { class: 'label' }, 'Include'),
      h('div', { class: 'nxticks' }, tick('quotes', 'Quotes'), tick('text', 'Your own text'), tick('tags', 'Tags'), tick('mentions', 'Linked mentions'), tick('covers', 'Book covers'), tick('front', 'Front page'),
        tick('links', 'Clickable links', 'Between notes, from the contents, and to the page in the source file'),
        scope.kind === 'n' ? null : tick('skipEmpty', 'Leave out notes without text of your own')),
      info,
      h('div', { class: 'label', style: { marginTop: '10px' } }, 'Source files (PDF) along with it'), src, srcNote,
      h('div', { class: 'foot' }, h('button', { class: 'btn quiet', style: { marginRight: 'auto' }, title: 'If the PDF engine fails: the same notes through the browser’s own Print ▸ Save as PDF', onclick: async () => { close(); await runNotesExport(scope, o, true); } }, 'Print instead…'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'), go));
    draw();
  });
}
async function runNotesExport(scope, o, printInstead) {
  const save = (blob, name) => { const a = h('a', { href: URL.createObjectURL(blob), download: name }); document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 8000); };
  try {
    toast('Gathering the notes…', 0);
    const model = await exportModel(scope, printInstead ? Object.assign({}, o, { source: 'none' }) : o, m => toast(m, 0));
    if (!model.count) { toast('There are no notes to export with these choices'); return; }
    const base = mdName(model.title).replace(/[’‘]/g, "'").replace(/[“”]/g, '').slice(0, 100);
    if (printInstead) {
      const url = URL.createObjectURL(new Blob([await exportHtml(model, false)], { type: 'text/html' }));
      toast('', 1); window.open(url + '#/print', '_blank');
      return;
    }
    let main, name;
    if (o.format === 'pdf') {
      const t = exportTypst(model);
      toast('Setting the pages…', 0);
      main = await typstPdf(t.source, t.files); name = base + '.pdf';
    } else { main = new Blob([await exportHtml(model, o.source === 'inside')], { type: 'text/html' }); name = base + '.html'; }
    if (o.source === 'zip' && model.sources.length) {
      toast('Packing the zip…', 0);
      const zip = new JSZip(); zip.file(name, main);
      model.sources.forEach(s => zip.file('files/' + s.name, s.blob));
      save(await zip.generateAsync({ type: 'blob', compression: 'STORE' }), base + '.zip');
      toast('Unpack the zip and keep the “files” folder beside the notes', 4000);
    } else { save(main, name); toast('', 1); }
  } catch (e) { fail(e); }
}
