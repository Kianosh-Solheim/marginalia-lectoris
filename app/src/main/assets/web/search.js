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

const wordsOf = s => foldText(s).match(/[\p{L}\p{N}]+/gu) || [];

/** The search options as one object: { whole, exact, variants } (a plain true/false is "whole words"). */
function findOpts(o) { return o && typeof o === 'object' ? { whole: !!o.whole, exact: !!o.exact, variants: o.variants || {} } : { whole: !!o, exact: false, variants: {} }; }
/** The options of the reader's or library's search state f. */
const optsOf = f => ({ whole: !!f.whole, exact: !!f.exact, variants: f.variants || {} });
const modeParam = o => (o.whole ? '&whole=1' : '') + (o.exact ? '&mode=exact' : '');

/**
 * A search, matched as on the tablet. Fuzzy: each word (or "quoted phrase") close to the others, in
 * any order, with the small misspellings the tablet found (variants). Exact: the whole search as one
 * sentence, word for word. Words match longer forms unless whole; one- and two-letter words only whole.
 */
function parseQuery(raw, opt) {
  const o = findOpts(opt);
  let segs;
  if (o.exact) { const t = wordsOf(raw.replace(/"/g, ' ')); segs = t.length ? [t] : []; }
  else {
    const ph = [];
    const rest = raw.replace(/"([^"]+)"/g, (_, p) => { const t = wordsOf(p); if (t.length) ph.push(t); return ' '; });
    segs = [...ph, ...wordsOf(rest).map(w => [w])];
  }
  const total = segs.reduce((a, s) => a + s.length, 0);
  const prefix = w => !o.whole && w.length > 2;
  const tokOk = (seg, j, tok) => {
    const t = seg[j], last = j === seg.length - 1;
    if (tok === t || (last && prefix(t) && tok.startsWith(t))) return true;
    if (seg.length === 1) for (const v of (o.variants[t] || [])) if (tok === v || (prefix(t) && tok.startsWith(v))) return true;
    return false;
  };
  return {
    empty: !segs.length,
    /** Ranges [start, end) in a forgiving text where the search matches. */
    ranges(n) {
      if (!segs.length) return [];
      const toks = [...n.matchAll(/[\p{L}\p{N}]+/gu)].map(m => [m[0], m.index]);
      const occ = segs.map(seg => {
        const l = [];
        for (let i = 0; i + seg.length <= toks.length; i++) {
          let ok = true;
          for (let j = 0; j < seg.length; j++) if (!tokOk(seg, j, toks[i + j][0])) { ok = false; break; }
          if (ok) l.push([i, i + seg.length - 1]);
        }
        return l;
      });
      if (occ.some(l => !l.length)) return [];
      let spans = [];
      if (segs.length === 1) spans = occ[0];
      else {
        const win = total + 10;
        let ai = 0; occ.forEach((l, k) => { if (l.length < occ[ai].length) ai = k; });
        for (const a of occ[ai]) {
          let lo = a[0], hi = a[1], ok = true;
          for (let k = 0; k < occ.length && ok; k++) {
            if (k === ai) continue;
            let best = occ[k][0];
            for (const x of occ[k]) if (Math.abs(x[0] - a[0]) < Math.abs(best[0] - a[0])) best = x;
            if (Math.abs(best[0] - a[0]) > win) ok = false;
            else { lo = Math.min(lo, best[0]); hi = Math.max(hi, best[1]); }
          }
          if (ok) spans.push([lo, hi]);
        }
      }
      spans.sort((x, y) => x[0] - y[0]);
      const out = []; let lastEnd = -1;
      for (const [x, y] of spans) {
        if (x <= lastEnd) continue;
        lastEnd = y;
        out.push([toks[x][1], toks[y][1] + toks[y][0].length]);
        if (out.length >= 500) break;
      }
      return out;
    },
  };
}

/** Fuzzy | Exact switch; onChange(exact). */
function modeSwitch(exact, onChange) {
  const mk = (label, v, title) => h('button', { class: 'btn quiet seg' + (exact === v ? ' on' : ''), title, onclick: () => { if (exact === v) return; exact = v; fz.classList.toggle('on', !v); ex.classList.toggle('on', v); onChange(v); } }, label);
  const fz = mk('Fuzzy', false, 'The words close together, in any order; small misspellings are found too');
  const ex = mk('Exact', true, 'The words in this order, as one sentence (case, accents and punctuation ignored)');
  return h('span', { class: 'segsw' }, fz, ex);
}
const modeHelp = exact => exact ? 'Exact: the words in this order, as one sentence. Case, accents, punctuation and words broken over a line are ignored.'
  : 'Fuzzy: the words close together, in any order; small misspellings are found too, and words find their longer forms (vold → volden). "Quote" a phrase to keep it together.';

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
    case 'done': return `Search: ready ✓ — all ${st.pagesTotal} pages searchable` + (st.ocrDone ? ` (${st.ocrDone} of them had no text layer and were read with OCR)` : '');
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
  const input = h('input', { type: 'search', placeholder: 'Search the text of every book, and your notes — words or a sentence…', value: params.get('q') || '', autofocus: true });
  const whole = h('input', { type: 'checkbox', checked: localGet('ml.findWhole', '0') === '1' });
  let exact = localGet('ml.findExact', '0') === '1';
  const help = h('div', { class: 'muted', style: { fontSize: '12px', margin: '4px 0 10px' } }, modeHelp(exact));
  const sw = modeSwitch(exact, v => { exact = v; localSet('ml.findExact', v ? '1' : '0'); help.textContent = modeHelp(v); run(); });
  const status = h('div', { class: 'muted mono', style: { fontSize: '12px', minHeight: '18px' } });
  const ixLine = h('div', { class: 'ixline', hidden: true });
  const out = h('div');
  main.append(h('h1', null, 'Search'),
    h('div', { class: 'row', style: { gap: '10px', alignItems: 'center' } }, input, sw, h('label', { class: 'row', style: { gap: '4px', fontSize: '13px', whiteSpace: 'nowrap' } }, whole, 'Whole words')),
    help,
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
    const o = { whole: whole.checked, exact };
    try { r = await api(`search?q=${encodeURIComponent(q)}${modeParam(o)}`); } catch (e) { fail(e); return; }
    if (my !== seq) return;
    const total = r.books.reduce((a, b) => a + b.count, 0);
    const ix = r.indexing;
    status.textContent = `Found on ${total} page${total === 1 ? '' : 's'} in ${r.books.length} book${r.books.length === 1 ? '' : 's'} · ${r.notes.length} note${r.notes.length === 1 ? '' : 's'}`;
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
      const link = hit => isPdf(d) ? `#/read/${d.id}?page=${hit.loc}&find=${encodeURIComponent(q)}${modeParam(o)}`
        : `#/read/${d.id}?href=${encodeURIComponent(hit.href)}&pos=${hit.loc}&find=${encodeURIComponent(q)}&occ=${hit.occurrence}${modeParam(o)}`;
      out.append(h('div', { class: 'tsbook' },
        h('div', { class: 'tshead' }, h('a', { href: `#/book/${d.id}` }, h('b', null, d.title)), h('span', { class: 'muted' }, ' ' + (d.displayAuthor || d.author || '')),
          h('span', { class: 'muted mono', style: { marginLeft: 'auto' } }, `${b.count} page${b.count === 1 ? '' : 's'}`)),
        ...b.hits.map(hit => h('a', { class: 'tshit', href: link(hit) },
          h('span', { class: 'tsloc mono' }, isPdf(d) ? 'p. ' + (hit.label || hit.loc) : (hit.label || 'pos. ' + hit.loc)), snippetEl(hit.snippet, hit.start, hit.end))),
        b.count > b.hits.length ? h('a', { class: 'muted', style: { fontSize: '12px' }, href: link(b.hits[0]) + '&panel=1' }, `Open the book to see all ${b.count} pages →`) : null));
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
  const f = ctx.find || (ctx.find = { q: '', whole: localGet('ml.findWhole', '0') === '1', exact: localGet('ml.findExact', '0') === '1', hits: [], notes: [], i: -1 });
  const input = h('input', { type: 'search', placeholder: 'Search in this book…', value: f.q });
  const whole = h('input', { type: 'checkbox', checked: f.whole });
  const info = h('div', { class: 'muted mono', style: { fontSize: '12px', margin: '6px 0' } });
  const list = h('div', { class: 'fhits' });
  const stLine = h('div', { class: 'ixline small' });
  const go = i => {
    if (!f.hits.length) return;
    f.i = (i + f.hits.length) % f.hits.length;
    draw();
    if (readerOpen && readerOpen.goToHit) readerOpen.goToHit(f.hits[f.i], f.q, optsOf(f));
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
      const r = await api(`doc/${ctx.d.id}/search?q=${encodeURIComponent(q)}${modeParam(f)}`, { timeout: 60000 });
      if (my !== seq) return;
      f.hits = r.hits; f.notes = r.notes; f.status = r.status; f.variants = r.variants || {};
    } catch (e) { fail(e); }
    f.busy = false;
    draw();
    // The book is still being read for search: look again in a moment (more matches come in).
    clearTimeout(again);
    if (f.status && f.status.state !== 'done' && f.status.state !== 'none') again = setTimeout(() => { if (document.body.contains(side) && f.q === q) run(true); }, 3000);
    if (readerOpen && readerOpen.markFind) readerOpen.markFind(f.q, optsOf(f));
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
    h('div', { class: 'row', style: { gap: '8px', marginTop: '4px', alignItems: 'center' } },
      modeSwitch(!!f.exact, v => { f.exact = v; localSet('ml.findExact', v ? '1' : '0'); run(); }),
      h('label', { class: 'row', style: { gap: '4px', fontSize: '12px' } }, whole, 'Whole words')),
    stLine, info, list);
  if (!f.status) api(`doc/${ctx.d.id}/search?q=`).then(r => { f.status = r.status; draw(); }).catch(() => {});
  draw();
  setTimeout(() => input.focus(), 0);
  return { run, go, input };
}

// ------------------------------------------------------------------ marking matches on the page

/** Marks matches in a PDF.js text layer: just the matching words, inside their spans. */
function markFindInTextLayer(textDiv, q, opt) {
  if (!textDiv) return;
  textDiv.querySelectorAll('span.fspan').forEach(s => { s.textContent = s.textContent; s.classList.remove('fspan'); });
  const query = q ? parseQuery(q, opt) : null;
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
function findRangesIn(doc, q, opt) {
  const query = parseQuery(q, opt);
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
