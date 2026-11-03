/* Full-text search: in the open book (side panel in the reader, Ctrl/⌘-F) and in the whole library
   (the Search tab). The tablet keeps a full-text index of every book; matching ignores case, accents
   and line-break hyphens, and words match their longer forms unless "Whole words" is on.
   "Quoted words" are an exact phrase. Loaded after app.js. */

// ------------------------------------------------------------------ forgiving text (as on the tablet)

const FOLD = { 'ø': 'o', 'Ø': 'o', 'æ': 'ae', 'Æ': 'ae', 'œ': 'oe', 'Œ': 'oe', 'ß': 'ss', 'ł': 'l', 'Ł': 'l', 'đ': 'd', 'Đ': 'd', 'ð': 'd', 'Ð': 'd', 'þ': 'th', 'Þ': 'th', '’': "'", '‘': "'" };
function foldChar(c) { if (FOLD[c]) return FOLD[c]; return c.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase(); }
/** The forgiving form of s, and for each of its characters the index in s it came from. */
function normMap(s) {
  let out = ''; const map = [];
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    const f = /\s/.test(c) ? ' ' : foldChar(c);
    for (const ch of f) { if (ch === ' ' && out.endsWith(' ')) continue; map.push(i); out += ch; }
  }
  return [out, map];
}
const foldText = s => normMap(s)[0];
const tokensOf = s => foldText(s).split(/[^\p{L}\p{N}']+/u).map(t => t.replace(/^'+|'+$/g, '')).filter(Boolean);

function parseQuery(raw, whole) {
  const phrases = [];
  const rest = raw.replace(/"([^"]+)"/g, (_, p) => { const t = tokensOf(p); if (t.length) phrases.push(t.join(' ')); return ' '; });
  const needles = [...phrases, ...tokensOf(rest)];
  return {
    needles, whole, empty: !needles.length,
    /** Ranges [start, end) in a forgiving text where the search matches. */
    ranges(n) {
      const out = [];
      const isW = ch => /[\p{L}\p{N}]/u.test(ch || '');
      needles.forEach(needle => {
        let from = 0, i;
        while ((i = n.indexOf(needle, from)) >= 0) {
          let end = i + needle.length;
          if (!isW(n[i - 1]) && (!whole || !isW(n[end]))) {
            if (!whole) while (end < n.length && isW(n[end])) end++;
            out.push([i, end]);
          }
          from = i + 1;
        }
      });
      return out.sort((a, b) => a[0] - b[0]);
    },
  };
}

/** What the tablet's search indexer is doing, in words. */
function indexingText(ix) {
  if (!ix || !ix.running) return '';
  const what = ix.phase === 'ocr' ? `reading scanned pages with OCR: ${ix.page} of ${ix.pages}` : ix.pages ? `page ${ix.page} of ${ix.pages}` : 'reading';
  return `Preparing books for search: ${ix.done} of ${ix.total} ready · now “${ix.current}” (${what})` + (ix.ocrLeft && ix.phase !== 'ocr' ? ` · ${ix.ocrLeft} scanned pages wait for OCR` : '');
}

/** One book's search state, in words. */
function bookIndexText(st) {
  if (!st) return '';
  switch (st.state) {
    case 'done': return 'Search: ready' + (st.ocrDone ? ` (${st.ocrDone} scanned pages read with OCR)` : '');
    case 'ocr': return `Search: text ready · ${st.ocrLeft} scanned page${st.ocrLeft === 1 ? '' : 's'} still to read with OCR`;
    case 'reading': return st.pagesTotal ? `Search: being read now · page ${st.pagesDone} of ${st.pagesTotal}` : 'Search: being read now';
    case 'queued': return st.pagesDone ? `Search: waiting · ${st.pagesDone} of ${st.pagesTotal} pages read` : 'Search: waiting to be read';
    default: return 'Search: not available for this kind of file';
  }
}

function snippetEl(text, start, end) {
  return h('span', { class: 'snip' }, text.slice(0, start), h('mark', null, text.slice(start, end)), text.slice(end));
}

// ------------------------------------------------------------------ the library's text

async function showTextSearch(params) {
  const app = clear(document.getElementById('app'));
  state.tab = 'search';
  app.appendChild(header());
  const main = h('main', { class: 'tsearch' });
  app.appendChild(main);
  const input = h('input', { type: 'search', placeholder: 'Search the text of every book, and your notes…', value: params.get('q') || '', autofocus: true });
  const whole = h('input', { type: 'checkbox', checked: localGet('ml.findWhole', '0') === '1' });
  const status = h('div', { class: 'muted mono', style: { fontSize: '12px', minHeight: '18px' } });
  const ixLine = h('div', { class: 'ixline', hidden: true });
  const out = h('div');
  main.append(h('h1', null, 'Search'),
    h('div', { class: 'row', style: { gap: '10px', alignItems: 'center' } }, input, h('label', { class: 'row', style: { gap: '4px', fontSize: '13px', whiteSpace: 'nowrap' } }, whole, 'Whole words')),
    h('div', { class: 'muted', style: { fontSize: '12px', margin: '4px 0 10px' } }, 'Case, accents and words broken over a line are ignored; words also find their longer forms (vold → volden). "Quote" an exact phrase.'),
    ixLine, status, out);
  let seq = 0, timer = 0, again = 0, ixTimer = 0;
  const showIx = ix => { ixLine.textContent = indexingText(ix); ixLine.hidden = !ixLine.textContent; };
  const pollIx = async () => {
    if (!document.body.contains(main)) return;
    try { const r = await api('search-status'); showIx(r.indexing); if (r.indexing.running) ixTimer = setTimeout(pollIx, 2500); } catch (_) {}
  };
  pollIx();
  const run = async quiet => {
    const q = input.value.trim();
    history.replaceState(null, '', '#/search' + (q ? '?q=' + encodeURIComponent(q) : ''));
    if (!q) { clear(out); status.textContent = ''; return; }
    const my = ++seq;
    if (quiet !== true) status.textContent = 'Searching…';
    let r;
    try { r = await api(`search?q=${encodeURIComponent(q)}${whole.checked ? '&whole=1' : ''}`); } catch (e) { fail(e); return; }
    if (my !== seq) return;
    const total = r.books.reduce((a, b) => a + b.count, 0);
    const ix = r.indexing;
    status.textContent = `${total} match${total === 1 ? '' : 'es'} in ${r.books.length} book${r.books.length === 1 ? '' : 's'} · ${r.notes.length} note${r.notes.length === 1 ? '' : 's'}`;
    showIx(ix);
    // While books are still being read, search again now and then: more results come in.
    clearTimeout(again); if (ix && ix.running) again = setTimeout(() => { if (document.body.contains(main)) run(true); }, 5000);
    clear(out);
    if (r.notes.length) {
      out.append(h('div', { class: 'tsbook' }, h('div', { class: 'tshead' }, h('b', null, 'Your notes'), h('span', { class: 'muted mono' }, r.notes.length)),
        ...r.notes.slice(0, 30).map(n => h('a', { class: 'tshit', href: `#/read/${n.docId}?hl=${n.id}` },
          h('div', null, h('b', null, n.title || 'Note'), h('span', { class: 'muted' }, ' · ' + n.docTitle + (n.label ? ' · p. ' + n.label : ''))), snippetEl(n.snippet, n.start, n.end)))));
    }
    r.books.forEach(b => {
      const d = b.doc;
      const link = hit => isPdf(d) ? `#/read/${d.id}?page=${hit.loc}&find=${encodeURIComponent(q)}${whole.checked ? '&whole=1' : ''}`
        : `#/read/${d.id}?href=${encodeURIComponent(hit.href)}&pos=${hit.loc}&find=${encodeURIComponent(q)}&occ=${hit.occurrence}${whole.checked ? '&whole=1' : ''}`;
      out.append(h('div', { class: 'tsbook' },
        h('div', { class: 'tshead' }, h('a', { href: `#/book/${d.id}` }, h('b', null, d.title)), h('span', { class: 'muted' }, ' ' + (d.displayAuthor || d.author || '')),
          h('span', { class: 'muted mono', style: { marginLeft: 'auto' } }, `${b.count} match${b.count === 1 ? '' : 'es'}`)),
        ...b.hits.map(hit => h('a', { class: 'tshit', href: link(hit) },
          h('span', { class: 'tsloc mono' }, isPdf(d) ? 'p. ' + (hit.label || hit.loc) : (hit.label || 'pos. ' + hit.loc)), snippetEl(hit.snippet, hit.start, hit.end))),
        b.count > b.hits.length ? h('a', { class: 'muted', style: { fontSize: '12px' }, href: link(b.hits[0]) + '&panel=1' }, `Open the book to see all ${b.count} →`) : null));
    });
    if (!r.books.length && !r.notes.length) out.append(h('div', { class: 'empty' }, h('h2', null, 'Nothing found'),
      h('div', { class: 'muted' }, ix && ix.running ? 'Some books are still being read for search; try again in a little while.' : 'Try fewer or shorter words.')));
  };
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(run, 250); });
  input.addEventListener('keydown', e => { if (e.key === 'Enter') { clearTimeout(timer); run(); } });
  whole.addEventListener('change', () => { localSet('ml.findWhole', whole.checked ? '1' : '0'); run(); });
  input.focus();
  if (input.value) run();
}

// ------------------------------------------------------------------ in the open book

/** The reader's search panel. ctx.find = { q, whole, hits, i }. */
function readerSearchPanel(ctx, side) {
  const f = ctx.find || (ctx.find = { q: '', whole: localGet('ml.findWhole', '0') === '1', hits: [], notes: [], i: -1 });
  const input = h('input', { type: 'search', placeholder: 'Search in this book…', value: f.q });
  const whole = h('input', { type: 'checkbox', checked: f.whole });
  const info = h('div', { class: 'muted mono', style: { fontSize: '12px', margin: '6px 0' } });
  const list = h('div', { class: 'fhits' });
  const stLine = h('div', { class: 'ixline small' });
  const go = i => {
    if (!f.hits.length) return;
    f.i = (i + f.hits.length) % f.hits.length;
    draw();
    if (readerOpen && readerOpen.goToHit) readerOpen.goToHit(f.hits[f.i], f.q, f.whole);
  };
  const draw = () => {
    info.textContent = f.q ? (f.busy ? 'Searching…' : `${f.hits.length} match${f.hits.length === 1 ? '' : 'es'}${f.i >= 0 ? ` · ${f.i + 1} of ${f.hits.length}` : ''}${f.notes.length ? ` · ${f.notes.length} in notes` : ''}` +
      (f.status && f.status.state !== 'done' ? ' so far' : '')) : '';
    stLine.textContent = bookIndexText(f.status);
    stLine.classList.toggle('busy', !!f.status && f.status.state !== 'done');
    clear(list);
    f.hits.slice(0, 500).forEach((hit, k) => list.append(h('div', { class: 'fhit' + (k === f.i ? ' cur' : ''), onclick: () => go(k) },
      h('span', { class: 'tsloc mono' }, isPdf(ctx.d) ? 'p. ' + (hit.label || hit.loc) : (hit.label || 'pos. ' + hit.loc)), snippetEl(hit.snippet, hit.start, hit.end))));
    if (f.hits.length > 500) list.append(h('div', { class: 'muted' }, `… and ${f.hits.length - 500} more`));
    if (f.notes.length) {
      list.append(h('div', { class: 'label', style: { marginTop: '10px' } }, 'In your notes'));
      f.notes.forEach(n => list.append(h('div', { class: 'fhit', onclick: () => { const nn = ctx.notes.find(x => x.id === n.id); if (nn && readerOpen) readerOpen.goToNote(nn); } },
        h('b', null, n.title || 'Note'), ' ', snippetEl(n.snippet, n.start, n.end))));
    }
    const cur = list.querySelector('.fhit.cur'); if (cur) cur.scrollIntoView({ block: 'nearest' });
  };
  let timer = 0, seq = 0, again = 0;
  const run = async quiet => {
    const q = input.value.trim();
    const same = quiet === true && q === f.q;
    f.q = q; f.whole = whole.checked; if (!same) f.i = -1;
    if (!q) { f.hits = []; f.notes = []; if (readerOpen && readerOpen.clearFind) readerOpen.clearFind(); draw(); return; }
    const my = ++seq; if (!same) { f.busy = true; draw(); }
    try {
      const r = await api(`doc/${ctx.d.id}/search?q=${encodeURIComponent(q)}${f.whole ? '&whole=1' : ''}`, { timeout: 60000 });
      if (my !== seq) return;
      f.hits = r.hits; f.notes = r.notes; f.status = r.status;
    } catch (e) { fail(e); }
    f.busy = false;
    draw();
    // The book is still being read for search: look again in a moment (more matches come in).
    clearTimeout(again);
    if (f.status && f.status.state !== 'done' && f.status.state !== 'none') again = setTimeout(() => { if (document.body.contains(side) && f.q === q) run(true); }, 3000);
    if (readerOpen && readerOpen.markFind) readerOpen.markFind(f.q, f.whole);
  };
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(run, 300); });
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); clearTimeout(timer); if (f.q !== input.value.trim()) run().then(() => go(0)); else go(f.i + (e.shiftKey ? -1 : 1)); }
    e.stopPropagation();
  });
  whole.addEventListener('change', () => { localSet('ml.findWhole', whole.checked ? '1' : '0'); run(); });
  side.append(h('h3', null, 'Search in this book'),
    h('div', { class: 'row', style: { gap: '4px' } }, input,
      h('button', { class: 'btn quiet', title: 'Previous (Shift+Enter)', onclick: () => go(f.i - 1) }, '↑'),
      h('button', { class: 'btn quiet', title: 'Next (Enter)', onclick: () => go(f.i + 1) }, '↓')),
    h('label', { class: 'row', style: { gap: '4px', fontSize: '12px', marginTop: '4px' } }, whole, 'Whole words'),
    stLine, info, list);
  if (!f.status) api(`doc/${ctx.d.id}/search?q=`).then(r => { f.status = r.status; draw(); }).catch(() => {});
  draw();
  setTimeout(() => input.focus(), 0);
  return { run, go, input };
}

// ------------------------------------------------------------------ marking matches on the page

/** Marks matches in a PDF.js text layer: just the matching words, inside their spans. */
function markFindInTextLayer(textDiv, q, whole) {
  if (!textDiv) return;
  textDiv.querySelectorAll('span.fspan').forEach(s => { s.textContent = s.textContent; s.classList.remove('fspan'); });
  const query = q ? parseQuery(q, whole) : null;
  if (!query || query.empty) return;
  const spans = [...textDiv.querySelectorAll('span')].filter(s => !s.querySelector('span'));
  let all = ''; const starts = [];
  spans.forEach(s => { starts.push(all.length); all += (s.textContent || '') + ' '; });
  const [n, map] = normMap(all);
  const per = new Map();
  query.ranges(n).forEach(([a, b]) => {
    const sa = map[a], sb = map[Math.max(a, b - 1)] + 1;
    spans.forEach((s, k) => {
      const st = starts[k], en = st + (s.textContent || '').length;
      if (en > sa && st < sb) { const l = per.get(s) || []; l.push([Math.max(0, sa - st), Math.min(en, sb) - st]); per.set(s, l); }
    });
  });
  per.forEach((ranges, s) => {
    const t = s.textContent; let pos = 0;
    const parts = [];
    ranges.sort((x, y) => x[0] - y[0]).forEach(([x, y]) => { if (x < pos) x = pos; if (y <= x) return; parts.push(document.createTextNode(t.slice(pos, x))); parts.push(h('mark', { class: 'find-hit' }, t.slice(x, y))); pos = y; });
    parts.push(document.createTextNode(t.slice(pos)));
    s.textContent = ''; parts.forEach(p => s.append(p)); s.classList.add('fspan');
  });
}

/** DOM ranges in a document where the search matches (for EPUB sections). */
function findRangesIn(doc, q, whole) {
  const query = parseQuery(q, whole);
  if (query.empty || !doc || !doc.body) return [];
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
  const nodes = []; let all = '';
  for (let t = walker.nextNode(); t; t = walker.nextNode()) { nodes.push([t, all.length]); all += t.nodeValue; }
  const [n, map] = normMap(all);
  const at = pos => { let lo = 0, hi = nodes.length - 1; while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (nodes[mid][1] <= pos) lo = mid; else hi = mid - 1; } return nodes[lo]; };
  return query.ranges(n).map(([a, b]) => {
    const sa = map[a], sb = map[b - 1] + 1;
    const [na, oa] = at(sa), [nb, ob] = at(sb - 1);
    const r = doc.createRange();
    try { r.setStart(na, sa - oa); r.setEnd(nb, sb - ob); } catch (_) { return null; }
    return r;
  }).filter(Boolean);
}
