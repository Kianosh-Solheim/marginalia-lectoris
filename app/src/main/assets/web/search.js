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

/** The search options as one object: { whole, exact, variants, parsed } (a plain true/false is "whole words"). */
function findOpts(o) { return o && typeof o === 'object' ? { whole: !!o.whole, exact: !!o.exact, variants: o.variants || {}, parsed: o.parsed || null } : { whole: !!o, exact: false, variants: {}, parsed: null }; }
/** The options of the reader's or library's search state f. */
const optsOf = f => ({ whole: !!f.whole, exact: !!f.exact, variants: f.variants || {}, parsed: f.parsed || null });
const modeParam = o => (o.whole ? '&whole=1' : '') + (o.exact ? '&mode=exact' : (o.typos ? '&typos=1' : ''));
const usedVariants = r => r && r.variants && Object.keys(r.variants).length > 0;

// Word forms, as on the tablet: the ending is cut, and words starting with what is left match (vold → volden, voldelig).
const STEM_END = ['izations', 'isations', 'ization', 'isation', 'ations', 'ation', 'ingly', 'edly', 'ings', 'ing', 'ments', 'ment', 'ness',
  'ities', 'ity', 'ical', 'ics', 'tic', 'ic', 'cy', 'ists', 'isms', 'ist', 'ism', 'ives', 'ive', 'ally', 'ies', 'ied', 'ers', 'er', 'ed', 'es', 'ly', 's', 'y',
  'hetene', 'heten', 'heter', 'het', 'ingene', 'ingen', 'inger', 'inga', 'elige', 'elig', 'lige', 'lig', 'ende', 'ene', 'ane', 'en', 'et', 'ar', 'a', 'e'];
const STEM_KEEP = [['ences', 3], ['ence', 2], ['ently', 3], ['ents', 2], ['ent', 1], ['ances', 3], ['ance', 2], ['antly', 3], ['ants', 2], ['ant', 1]];
function stemWord(w) {
  if (w.length < 5 || /\d/.test(w)) return '';
  for (const [e, cut] of STEM_KEEP) if (w.endsWith(e) && w.length - cut >= 5) return w.slice(0, -cut);
  for (const e of STEM_END) if (w.endsWith(e) && w.length - e.length >= (e.length <= 2 && !['en', 'et', 'er', 'ar'].includes(e) ? 5 : 4)) return w.slice(0, -e.length);
  return '';
}

/** The search taken apart as on the tablet (until the tablet's own reading arrives with the results). */
function localParse(raw, o) {
  const rest = raw.replace(/(^|\s)(author|title|tag|year|type|forfatter|tittel|år):("[^"]*"|\S+)/gi, ' ');
  const items = [];
  for (const m of rest.matchAll(/(-?)"([^"]*)"|(\S+)/g)) {
    if (m[3] === undefined) { const t = wordsOf(m[2]); if (t.length) items.push([m[1] === '-' ? '-' : '+', t]); continue; }
    const w = m[3];
    if (w === 'OR' || w === 'ELLER' || w === '|') items.push(['OR', []]);
    else if (w.startsWith('-') && w.length > 1) { const t = wordsOf(w.slice(1)); if (t.length) items.push(['-', t]); }
    else wordsOf(w).forEach(t => items.push(['+', [t]]));
  }
  const exclude = items.filter(i => i[0] === '-').map(i => i[1]);
  const pos = items.filter(i => i[0] !== '-');
  let segs;
  if (o.exact) { const words = pos.filter(i => i[0] === '+').flatMap(i => i[1]); segs = words.length ? [[{ w: words, stem: '' }]] : []; }
  else {
    const raws = []; let join = false;
    for (const [k, w] of pos) { if (k === 'OR') { join = raws.length > 0; continue; } if (join) raws[raws.length - 1].push(w); else raws.push([w]); join = false; }
    segs = raws.map(alts => alts.map(w => ({ w, stem: w.length === 1 && !o.whole ? stemWord(w[0]) : '' })));
  }
  return { segs, exclude, variants: o.variants || {}, whole: o.whole, exact: o.exact };
}

/**
 * A search, matched as on the tablet. Fuzzy: each part (a word, a "phrase", words joined by OR, a synonym
 * group) close to the others, in any order, with word forms and the similar spellings the tablet found.
 * Exact: the whole search as one sentence, word for word. -word leaves a page out.
 */
function parseQuery(raw, opt) {
  const o = findOpts(opt);
  const P = o.parsed && !!o.parsed.exact === o.exact ? o.parsed : localParse(raw, o);
  const segs = (P.segs || []).map(alts => alts.map(a => ({ w: a.w, stem: a.stem || '' })));
  const exclude = P.exclude || [];
  const variants = Object.assign({}, P.variants || {}, o.variants || {});
  const whole = o.whole;
  const total = segs.reduce((a, s) => a + Math.max(...s.map(x => x.w.length)), 0);
  const prefix = w => !whole && w.length > 2;
  const wordOk = (a, j, tok, single) => {
    const t = a.w[j], last = j === a.w.length - 1;
    if (tok === t || (last && prefix(t) && tok.startsWith(t))) return true;
    if (a.w.length === 1 && a.stem && tok.startsWith(a.stem)) return true;
    if (single && a.w.length === 1) for (const v of (variants[t] || [])) if (tok === v || (prefix(t) && tok.startsWith(v))) return true;
    return false;
  };
  const occOf = (seg, words) => {
    const l = [], single = seg.length === 1;
    for (let i = 0; i < words.length; i++) for (const a of seg) {
      if (i + a.w.length > words.length) continue;
      let ok = true;
      for (let j = 0; j < a.w.length; j++) if (!wordOk(a, j, words[i + j], single)) { ok = false; break; }
      if (ok) { l.push([i, i + a.w.length - 1]); break; }
    }
    return l;
  };
  const excluded = words => exclude.some(w => { for (let i = 0; i + w.length <= words.length; i++) if (w.every((x, j) => words[i + j] === x || (j === w.length - 1 && prefix(x) && words[i + j].startsWith(x)))) return true; return false; });
  return {
    empty: !segs.length,
    /** Ranges [start, end) in a forgiving text where the search matches. */
    ranges(n) {
      if (!segs.length) return [];
      const toks = [...n.matchAll(/[\p{L}\p{N}]+/gu)].map(m => [m[0], m.index]);
      const words = toks.map(t => t[0]);
      if (exclude.length && excluded(words)) return [];
      const occ = segs.map(sg => occOf(sg, words));
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
  const fz = mk('Fuzzy', false, 'The words close together, in any order');
  const ex = mk('Exact', true, 'The words in this order, as one sentence (case, accents and punctuation ignored)');
  return h('span', { class: 'segsw' }, fz, ex);
}
const modeHelp = exact => exact ? 'Exact: the words in this order, as one sentence. Case, accents, punctuation and words broken over a line are ignored.'
  : 'Fuzzy: the words close together, in any order; words find their longer forms (vold → volden). "Quote" a phrase to keep it together. Similar spellings: tick Typos (tried by itself when nothing is found).';

/** What the tablet's search indexer is doing, in words. */
function indexingText(ix) {
  if (!ix || !ix.running) return '';
  if (ix.phase === 'update') return `Updating the search index for better matching: ${ix.page} of ${ix.pages} pages (searches may miss some results until it is done)`;
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

// ------------------------------------------------------------------ AI search

const AI_METHODS = [
  ['guided', 'Guided', 'The AI chosen in Settings → AI turns your question into the words authors would use (Norwegian and English), the normal search finds passages with them, and the AI picks the best and says why. Only those passages are sent to the AI.'],
  ['gemini', 'Meaning · Gemini', 'Compares the meaning of your question with every page, so passages count even when no words match. Needs the Gemini meaning index (Settings → Search); your question is sent to Google.'],
  ['local', 'Meaning · offline', 'The same, with a small model on the tablet: nothing leaves the tablet. Needs the offline model and its index (Settings → Search).'],
];
/** A short exact phrase from a passage, to find and mark it in the book. */
function phraseOf(r) {
  const t = r.end > r.start ? r.snippet.slice(r.start, r.end) : r.snippet.replace(/^…/, '');
  const w = wordsOf(t).slice(0, r.end > r.start ? 12 : 7);
  return w.length >= 2 ? '"' + w.join(' ') + '"' : '';
}
const aiLink = (d, r) => {
  const ph = phraseOf(r); const f = ph ? `&find=${encodeURIComponent(ph)}&mode=exact` : '';
  return isPdf(d) ? `#/read/${d.id}?page=${r.loc}${f}` : `#/read/${d.id}?href=${encodeURIComponent(r.href)}&pos=${r.loc}${f}&occ=0`;
};
function aiIndexText(st) {
  if (!st) return '';
  const g = st.gemini, l = st.local, p = st.progress;
  const parts = [`Gemini meaning index: ${g.done} of ${g.total} pages`, l.model ? `offline index: ${l.done} of ${l.total} pages` : 'offline model: not downloaded'];
  if (p.running) parts.push(`now ${p.src === 'local' ? 'offline' : 'Gemini'}: ${p.done} of ${p.total} – ${p.note}`);
  else if (p.error) parts.push('last error: ' + p.error);
  return parts.join(' · ');
}
/** One AI result: book, page, passage, and why it fits. */
function aiResultEl(r, onNote) {
  const d = r.doc;
  return h('a', { class: 'tshit aihit', href: aiLink(d, r) },
    h('div', null, h('b', null, d.title), h('span', { class: 'muted' }, ' · ' + (d.displayAuthor || d.author || '') + ' · ' + (isPdf(d) ? 'p. ' + (r.label || r.loc) : (r.label || 'pos. ' + r.loc))),
      h('span', { class: 'mono aiscore', title: 'How well it fits' }, Math.round(r.score * 100) + '%')),
    r.why ? h('div', { class: 'aiwhy' }, '✦ ' + r.why) : null,
    snippetEl(r.snippet, r.start, r.end),
    h('button', { class: 'btn quiet hnote', title: 'Make a note from this passage', onclick: e => { e.preventDefault(); e.stopPropagation(); onNote(d, r); } }, '+ Note'));
}

// ------------------------------------------------------------------ the library's text

// ------------------------------------------------------------------ shared helpers

/** The search help: what can be typed, with examples. */
function searchHelpEl() {
  const row = (code, text) => h('tr', null, h('td', { class: 'mono' }, code), h('td', null, text));
  return h('div', { class: 'shelp' },
    h('b', null, 'How to search'),
    h('table', null,
      row('vold makt', 'Fuzzy: both words close together, in any order. Other word forms count too (vold → volden, voldelig; violence → violent).'),
      row('"radical right"', 'Quotes: these words together, in this order.'),
      row('Exact', 'The whole search as one sentence, word for word.'),
      row('-left', 'Leave out pages with this word (or -"a phrase").'),
      row('far right OR populism', 'Either one. Write OR in capitals.'),
      row('author:Tilly', 'Only books by this author. Also title:…, tag:Master, type:book / type:article.'),
      row('year:2010-2020', 'Only books from these years (or year:2017).'),
      row('Typos', 'Also similar spellings and OCR mistakes (tried by itself when nothing is found).'),
      row('+ Synonym', 'Make words mean the same in every search: far right = radical right = extreme right.'),
      row('Whole words', 'Words do not match longer words or other forms.')),
    h('div', { class: 'muted', style: { fontSize: '12px', marginTop: '4px' } }, 'Case, accents, punctuation and words broken over a line are always ignored.'));
}

/** Inserts text into a search box at the cursor. */
function insertAtCursor(input, text, select) {
  const a = input.selectionStart ?? input.value.length, b = input.selectionEnd ?? a;
  const sel = input.value.slice(a, b);
  const before = input.value.slice(0, a), after = input.value.slice(b);
  const pad = before && !/\s$/.test(before) ? ' ' : '';
  let ins = typeof text === 'function' ? text(sel) : text;
  input.value = before + pad + ins + after;
  const pos = (before + pad).length + (select != null ? select : ins.length);
  input.focus(); input.setSelectionRange(pos, pos);
  input.dispatchEvent(new Event('input'));
}

/** The words of a search without its operators (for a synonym group's first term). */
const plainTerms = q => q.replace(/(^|\s)(author|title|tag|year|type|forfatter|tittel|år):("[^"]*"|\S+)/gi, ' ').replace(/(^|\s)-("[^"]*"|\S+)/g, ' ').replace(/\bOR\b/g, ',').replace(/"/g, '').replace(/\s+/g, ' ').trim();

/** "+ Synonym": make terms mean the same in every search (kept on the tablet; edited in Settings too). */
function synonymDialog(q, onSaved) {
  modal(async (box, close) => {
    let groups = [];
    try { groups = await api('search-synonyms'); } catch (_) {}
    const first = plainTerms(q).split(',')[0].trim();
    const terms = h('input', { value: first ? first + ', ' : '', placeholder: 'far right, radical right, extreme right' });
    const list = h('div', { class: 'syngroups' });
    const draw = () => {
      clear(list);
      const words = wordsOf(first);
      const rel = groups.filter(g => g.some(t => wordsOf(t).some(w => words.includes(w))));
      (rel.length ? rel : groups).slice(0, 30).forEach(g => list.append(h('div', { class: 'row', style: { gap: '6px', alignItems: 'center' } },
        h('span', { style: { flex: 1 } }, g.join(' = ')),
        h('button', { class: 'btn quiet', title: 'Use these terms', onclick: () => { terms.value = g.join(', '); groups = groups.filter(x => x !== g); draw(); } }, 'Edit'),
        h('button', { class: 'btn quiet', onclick: async () => { groups = groups.filter(x => x !== g); try { groups = await api('search-synonyms', { method: 'PUT', json: groups }); } catch (e) { fail(e); } draw(); onSaved && onSaved(); } }, 'Remove'))));
      if (!groups.length) list.append(h('div', { class: 'muted' }, 'No synonym groups yet.'));
    };
    const save = async () => {
      const g = terms.value.split(/[,=;\n]/).map(t => t.trim()).filter(Boolean);
      if (g.length < 2) { toast('Write at least two terms, separated by commas'); return; }
      try { groups = await api('search-synonyms', { method: 'PUT', json: [...groups, g] }); toast('Synonyms saved'); close(); onSaved && onSaved(); } catch (e) { fail(e); }
    };
    terms.addEventListener('keydown', e => { if (e.key === 'Enter') save(); });
    box.append(h('h2', null, 'Synonyms'),
      h('p', { class: 'muted' }, 'Terms that should mean the same in every Fuzzy search. Searching for one also finds the others. Separate them with commas.'),
      h('div', { class: 'fields' }, h('label', null, h('span', { class: 'label' }, 'Terms'), terms)),
      h('div', { class: 'label', style: { margin: '12px 0 4px' } }, 'Your groups'), list,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save')));
    draw();
    terms.focus(); terms.setSelectionRange(terms.value.length, terms.value.length);
  });
}

/** The sentence around a match in a passage (for a note made from a search result). */
function sentenceOf(snippet, start, end) {
  const t = snippet.replace(/^…/, ' ').replace(/…$/, ' ');
  let a = 0, b = t.length;
  const before = t.slice(0, start); const m = [...before.matchAll(/[.!?]["”’)]?\s/g)].pop(); if (m) a = m.index + m[0].length;
  const after = t.slice(end); const n = after.match(/[.!?]["”’)]?(\s|$)/); if (n) b = end + n.index + n[0].trimEnd().length;
  return t.slice(a, b).trim();
}

/** "+ Note" on a search result: a note with the sentence and its page, after a small dialog. */
function noteFromHit(d, hit, onSaved) {
  modal((box, close) => {
    let color = '#FFEB3B';
    const quote = h('textarea', { rows: 4 }, sentenceOf(hit.snippet, hit.start, hit.end));
    const t = h('input', { placeholder: 'Title (optional)' }), body = h('textarea', { rows: 4, placeholder: 'Your own words (optional)' }), g = tagInput('', true);
    const where = isPdf(d) ? 'p. ' + (hit.label || hit.loc) + (hit.label && hit.label !== String(hit.loc) ? ` (PDF ${hit.loc})` : '') : (hit.label || 'pos. ' + hit.loc);
    const save = async () => {
      const text = quote.value.trim();
      if (!text) { toast('The quote is empty'); return; }
      const json = { text, title: t.value, userNotes: body.value, tags: g.value, colorHex: color };
      if (isPdf(d)) Object.assign(json, { page: hit.loc, printedLabel: hit.label || '' }); else Object.assign(json, { position: hit.loc, href: hit.href || '', pageLabel: '' });
      try { await api(`doc/${d.id}/highlights`, { method: 'POST', json }); tagCache = null; close(); toast('Note saved'); onSaved && onSaved(); } catch (e) { fail(e); }
    };
    body.addEventListener('keydown', e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) save(); });
    box.append(h('h2', null, 'New note from the search'),
      h('div', { class: 'muted mono', style: { fontSize: '12px', marginBottom: '6px' } }, d.title + ' · ' + where),
      h('div', { class: 'fields' },
        h('label', null, h('span', { class: 'label' }, 'Quote (you can shorten it)'), quote),
        h('label', null, h('span', { class: 'label' }, 'Title'), t),
        h('label', null, h('span', { class: 'label' }, 'Note'), body),
        h('div', { class: 'tagwrap' }, h('span', { class: 'label' }, 'Tags'), g._widget),
        h('div', null, h('div', { class: 'label', style: { marginBottom: '4px' } }, 'Colour'), swatches(color, c => { color = c; }))),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save note')));
  });
}

/** What the tablet made of the search, in words: synonyms and word forms used. */
function queryInfoText(P) {
  if (!P) return '';
  const parts = [];
  (P.synonyms || []).forEach(g => parts.push(g.join(' = ')));
  const st = Object.entries(P.stems || {}); if (st.length) parts.push('word forms: ' + st.map(([w, s]) => `${w} → ${s}…`).join(', '));
  if ((P.exclude || []).length) parts.push('leaving out: ' + P.exclude.map(w => w.join(' ')).join(', '));
  const f = P.filters || {};
  const fl = [f.author && 'author ' + f.author, f.title && 'title ' + f.title, f.tag && '#' + f.tag, f.type && (f.type === 'BOOK' ? 'books' : 'articles'),
    (f.yearFrom || f.yearTo) && 'years ' + (f.yearFrom || '…') + '–' + (f.yearTo || '…'), f.hasNotes && 'with notes'].filter(Boolean);
  if (fl.length) parts.push('only ' + fl.join(', '));
  return parts.length ? 'Also: ' + parts.join(' · ') : '';
}

/** Recent and saved searches (kept on the tablet). */
const savedApi = (action, entry) => api('search-saved', { method: 'POST', json: { action, entry } });

// ------------------------------------------------------------------ the library's text

async function showTextSearch(params) {
  const app = clear(document.getElementById('app'));
  state.tab = 'search';
  app.appendChild(header());
  const main = h('main', { class: 'tsearch' });
  app.appendChild(main);
  const input = h('input', { type: 'search', placeholder: 'Search the text of every book, and your notes — words or a sentence…', value: params.get('q') || '', autofocus: true });
  const whole = h('input', { type: 'checkbox', checked: localGet('ml.findWhole', '0') === '1' });
  const typos = h('input', { type: 'checkbox', checked: localGet('ml.findTypos', '0') === '1' });
  const typosLbl = h('label', { class: 'row', title: 'Also find similar spellings (and OCR mistakes). Without this, they are only tried when nothing else is found.', style: { gap: '4px', fontSize: '13px', whiteSpace: 'nowrap' } }, typos, 'Typos');
  let exact = params.has('mode') ? params.get('mode') === 'exact' : localGet('ml.findExact', '0') === '1';
  const help = h('div', { class: 'muted', style: { fontSize: '12px', margin: '4px 0 6px' } }, modeHelp(exact));
  const sw = modeSwitch(exact, v => { exact = v; localSet('ml.findExact', v ? '1' : '0'); help.textContent = modeHelp(v); typosLbl.hidden = v; run(); });
  typosLbl.hidden = exact;

  // Filters (also typed: author:, tag:, year:, type:)
  let F = {}; try { F = JSON.parse(localGet('ml.searchFilters', '{}')) || {}; } catch (_) {}
  const saveF = () => { localSet('ml.searchFilters', JSON.stringify(F)); run(); };
  if (!state.docs.length) { try { await loadLibrary(); } catch (_) {} }
  const counts = {}; state.docs.forEach(d => splitTags(d.tags).forEach(t => { const k = t.toLowerCase(); counts[k] = counts[k] || [t, 0]; counts[k][1]++; }));
  const tagSel = h('select', { title: 'Only books with this tag' }, h('option', { value: '' }, 'All tags'),
    Object.values(counts).sort((a, b) => a[0].localeCompare(b[0])).map(([t, n]) => h('option', { value: t, selected: (F.tag || '') === t }, `#${t} (${n})`)));
  tagSel.addEventListener('change', () => { F.tag = tagSel.value; saveF(); });
  const typeBtns = [['', 'All'], ['BOOK', 'Books'], ['ARTICLE', 'Articles']].map(([v, l]) => h('button', { class: 'btn quiet seg' + ((F.type || '') === v ? ' on' : ''), onclick: e => {
    F.type = v; typeBtns.forEach(b => b.classList.toggle('on', b === e.currentTarget)); saveF(); } }, l));
  const yFrom = h('input', { type: 'number', placeholder: 'from', value: F.yfrom || '', class: 'yr', title: 'Year from' });
  const yTo = h('input', { type: 'number', placeholder: 'to', value: F.yto || '', class: 'yr', title: 'Year to' });
  [yFrom, yTo].forEach(x => x.addEventListener('change', () => { F.yfrom = yFrom.value; F.yto = yTo.value; saveF(); }));
  const hasNotes = h('input', { type: 'checkbox', checked: !!F.notes });
  hasNotes.addEventListener('change', () => { F.notes = hasNotes.checked; saveF(); });
  const clearF = h('button', { class: 'btn quiet', onclick: () => { F = {}; tagSel.value = ''; yFrom.value = ''; yTo.value = ''; hasNotes.checked = false; typeBtns.forEach((b, i) => b.classList.toggle('on', i === 0)); saveF(); } }, 'Clear filters');
  const filterParams = () => (F.tag ? '&ftag=' + encodeURIComponent(F.tag) : '') + (F.type ? '&ftype=' + F.type : '') + (F.yfrom ? '&yfrom=' + F.yfrom : '') + (F.yto ? '&yto=' + F.yto : '') + (F.notes ? '&fnotes=1' : '');

  // Sorting and view
  let sort = localGet('ml.searchSort', 'relevance'), view = localGet('ml.searchView', 'results'), kwSort = localGet('ml.kwicSort', 'book');
  const sortSel = h('select', { title: 'Order of the books' }, [['relevance', 'Most matches'], ['title', 'Title'], ['author', 'Author'], ['year', 'Year (newest)'], ['yearold', 'Year (oldest)'], ['lastread', 'Last read']]
    .map(([v, l]) => h('option', { value: v, selected: sort === v }, l)));
  sortSel.addEventListener('change', () => { sort = sortSel.value; localSet('ml.searchSort', sort); if (last) draw(last); });
  const viewBtns = [['results', 'Results'], ['kwic', 'Concordance']].map(([v, l]) => h('button', { class: 'btn quiet seg' + (view === v ? ' on' : ''), title: v === 'kwic' ? 'Every match lined up with the search term in the middle, to compare how it is used' : 'Matches grouped by book', onclick: e => {
    if (view === v) return; view = v; localSet('ml.searchView', v); viewBtns.forEach(b => b.classList.toggle('on', b === e.currentTarget)); kwSortSel.hidden = v !== 'kwic'; run(); } }, l));
  const kwSortSel = h('select', { title: 'Order of the lines', hidden: view !== 'kwic' }, [['book', 'By book and page'], ['after', 'By the word after'], ['before', 'By the word before']].map(([v, l]) => h('option', { value: v, selected: kwSort === v }, l)));
  kwSortSel.addEventListener('change', () => { kwSort = kwSortSel.value; localSet('ml.kwicSort', kwSort); if (last) draw(last); });

  // Operators: buttons that write them, and the help
  const helpBox = searchHelpEl(); helpBox.hidden = localGet('ml.searchHelp', '0') !== '1';
  const opBtn = (label, title, fn) => h('button', { class: 'chip', title, onclick: fn }, label);
  const ops = h('div', { class: 'row sops' },
    opBtn('“ ” Phrase', 'Words together, in this order', () => insertAtCursor(input, sel => `"${sel}"`, input.selectionStart === input.selectionEnd ? 1 : undefined)),
    opBtn('− Leave out', 'Leave out pages with a word: -word', () => insertAtCursor(input, '-', 1)),
    opBtn('OR', 'Either word: word OR word', () => insertAtCursor(input, 'OR ', 3)),
    opBtn('author:', 'Only books by an author', () => insertAtCursor(input, 'author:', 7)),
    opBtn('title:', 'Only books whose title has these words (use quotes for several words)', () => insertAtCursor(input, 'title:', 6)),
    opBtn('tag:', 'Only books with a tag', () => insertAtCursor(input, 'tag:', 4)),
    opBtn('year:', 'Only books from a year or years: year:2010-2020', () => insertAtCursor(input, 'year:', 5)),
    h('span', { style: { flex: 1 } }),
    opBtn('+ Synonym', 'Make terms mean the same in every search (far right = radical right)', () => synonymDialog(input.value, () => run())),
    opBtn('☆ Save search', 'Keep this search (with its mode and filters) at the top', () => saveSearch()),
    opBtn('? How to search', 'What can be typed in the search', e => { helpBox.hidden = !helpBox.hidden; localSet('ml.searchHelp', helpBox.hidden ? '0' : '1'); }));

  // Recent and saved searches
  const savedRow = h('div', { class: 'ssaved' });
  let saved = { recent: [], pinned: [] };
  const entryNow = () => ({ q: input.value.trim(), mode: exact ? 'exact' : 'fuzzy', whole: whole.checked, typos: typos.checked, filters: F });
  const applyEntry = e => {
    if (e.mode === 'ai') { input.value = e.q; aiMethod = e.method || 'guided'; aiMethodBtns.forEach((b, i) => b.classList.toggle('on', AI_METHODS[i][0] === aiMethod)); setAi(true); return; }
    if (ai) setAi(false);
    input.value = e.q; exact = e.mode === 'exact'; localSet('ml.findExact', exact ? '1' : '0');
    sw.querySelectorAll('.seg').forEach((b, i) => b.classList.toggle('on', i === (exact ? 1 : 0))); help.textContent = modeHelp(exact); typosLbl.hidden = exact;
    whole.checked = !!e.whole; typos.checked = !!e.typos; F = Object.assign({}, e.filters || {});
    tagSel.value = F.tag || ''; yFrom.value = F.yfrom || ''; yTo.value = F.yto || ''; hasNotes.checked = !!F.notes;
    typeBtns.forEach((b, i) => b.classList.toggle('on', ['', 'BOOK', 'ARTICLE'][i] === (F.type || '')));
    localSet('ml.searchFilters', JSON.stringify(F)); run();
  };
  const label = e => (e.mode === 'ai' ? '✦ ' : '') + e.q + (e.mode === 'exact' ? ' (exact)' : '') + (e.filters && (e.filters.tag || e.filters.type || e.filters.yfrom || e.filters.yto || e.filters.notes) ? ' ⧩' : '');
  const drawSaved = () => {
    clear(savedRow);
    if (saved.pinned.length) savedRow.append(h('span', { class: 'label' }, 'Saved'), ...saved.pinned.map(e => h('span', { class: 'schip pinned' },
      h('button', { onclick: () => applyEntry(e), title: 'Search again' }, '★ ' + label(e)),
      h('button', { class: 'x', title: 'Remove from saved', onclick: async () => { try { saved = await savedApi('unpin', e); drawSaved(); } catch (er) { fail(er); } } }, '×'))));
    if (saved.recent.length) savedRow.append(h('span', { class: 'label' }, 'Recent'), ...saved.recent.slice(0, 8).map(e => h('span', { class: 'schip' },
      h('button', { onclick: () => applyEntry(e), title: 'Search again' }, label(e)),
      h('button', { class: 'x', title: 'Remove', onclick: async () => { try { saved = await savedApi('remove', e); drawSaved(); } catch (er) { fail(er); } } }, '×'))),
      h('button', { class: 'btn quiet', style: { fontSize: '11px' }, onclick: async () => { try { saved = await savedApi('clear'); drawSaved(); } catch (er) { fail(er); } } }, 'Clear recent'));
    savedRow.hidden = !saved.pinned.length && !saved.recent.length;
  };
  api('search-saved').then(r => { saved = r; drawSaved(); }).catch(() => {});
  let recentTimer = 0;
  const addRecent = () => { clearTimeout(recentTimer); const e = entryNow(); if (e.q.length < 2) return; savedApi('add', e).then(r => { saved = r; drawSaved(); }).catch(() => {}); };
  const saveSearch = async () => { const e = entryNow(); if (!e.q) { toast('Type a search first'); return; } try { saved = await savedApi('pin', e); drawSaved(); toast('Search saved'); } catch (er) { fail(er); } };

  const status = h('div', { class: 'muted mono', style: { fontSize: '12px', minHeight: '18px' } });
  const qinfo = h('div', { class: 'muted', style: { fontSize: '12px' } });
  const ixLine = h('div', { class: 'ixline', hidden: true });
  const out = h('div');

  // AI search: a mode of its own; it runs only when "Ask AI" is pressed.
  let ai = localGet('ml.aiMode', '0') === '1', aiMethod = localGet('ml.aiMethod', 'guided');
  const aiDesc = h('div', { class: 'muted', style: { fontSize: '12px' } });
  const aiIx = h('div', { class: 'muted mono', style: { fontSize: '11px' } });
  const aiMethodBtns = AI_METHODS.map(([k, l]) => h('button', { class: 'btn quiet seg' + (aiMethod === k ? ' on' : ''), onclick: e => {
    aiMethod = k; localSet('ml.aiMethod', k); aiMethodBtns.forEach(b => b.classList.toggle('on', b === e.currentTarget)); aiDesc.textContent = AI_METHODS.find(m => m[0] === k)[2]; } }, l));
  aiDesc.textContent = AI_METHODS.find(m => m[0] === aiMethod)?.[2] || '';
  const askBtn = h('button', { class: 'btn fill', onclick: () => runAi() }, '✦ Ask AI');
  const aiBox = h('div', { class: 'aibox' },
    h('div', { class: 'row', style: { gap: '8px', alignItems: 'center', flexWrap: 'wrap' } }, h('span', { class: 'segsw' }, ...aiMethodBtns), askBtn,
      h('a', { href: '#/settings', class: 'muted', style: { fontSize: '12px', marginLeft: 'auto' }, onclick: () => setTimeout(() => { const el = document.getElementById('set-search'); if (el) el.scrollIntoView(); }, 400) }, 'Set up meaning indexes →')),
    aiDesc, aiIx);
  const aiToggle = h('button', { class: 'btn quiet seg' + (ai ? ' on' : ''), title: 'Search by what passages are about, with AI (runs only when you press Ask AI)', onclick: () => setAi(!ai) }, '✦ AI');
  const normalOnly = [];
  function setAi(v) {
    ai = v; localSet('ml.aiMode', v ? '1' : '0'); aiToggle.classList.toggle('on', v);
    aiBox.hidden = !v; normalOnly.forEach(el => { el.hidden = v; });
    input.placeholder = v ? 'Describe what you are looking for, e.g. arguments that repression leads to radicalisation — then press Ask AI' : 'Search the text of every book, and your notes — words or a sentence…';
    clear(out); status.textContent = ''; qinfo.textContent = '';
    if (v) api('ai-status').then(st => { aiIx.textContent = aiIndexText(st); }).catch(() => {}); else run();
  }
  let aiSeq = 0;
  async function runAi() {
    const q = input.value.trim();
    if (!q) { toast('Describe what you are looking for first'); return; }
    const my = ++aiSeq;
    clear(out); qinfo.textContent = '';
    status.textContent = aiMethod === 'guided' ? 'Asking the AI for search words, searching, and asking it to pick the best passages… (10–40 s)' : 'Comparing meanings…';
    askBtn.disabled = true;
    let r;
    try { r = await api(`ai-search?q=${encodeURIComponent(q)}&method=${aiMethod}${filterParams()}`, { timeout: 180000 }); }
    catch (e) { if (my === aiSeq) { status.textContent = ''; fail(e); } askBtn.disabled = false; return; }
    askBtn.disabled = false;
    if (my !== aiSeq) return;
    status.textContent = `${r.results.length} passage${r.results.length === 1 ? '' : 's'}` + (r.note ? ' · ' + r.note : '');
    qinfo.textContent = r.terms && r.terms.length ? 'Searched for: ' + r.terms.join(' · ') : '';
    r.results.forEach(x => out.append(aiResultEl(x, (d, hit) => noteFromHit(d, hit))));
    savedApi('add', { q, mode: 'ai', method: aiMethod, filters: F }).then(x => { saved = x; drawSaved(); }).catch(() => {});
  }
  const wholeLbl = h('label', { class: 'row', style: { gap: '4px', fontSize: '13px', whiteSpace: 'nowrap' } }, whole, 'Whole words');
  const sortLbl = h('span', { class: 'label' }, 'Sort'), viewSw = h('span', { class: 'segsw' }, ...viewBtns);
  normalOnly.push(sw, wholeLbl, typosLbl, help, ops, sortLbl, sortSel, viewSw, kwSortSel, ixLine);
  main.append(h('h1', null, 'Search'),
    h('div', { class: 'row', style: { gap: '10px', alignItems: 'center' } }, input, sw, h('span', { class: 'segsw' }, aiToggle), wholeLbl, typosLbl),
    help, ops, helpBox, aiBox,
    h('div', { class: 'row sfilters' }, h('span', { class: 'label' }, 'Only'), tagSel, h('span', { class: 'segsw' }, ...typeBtns),
      h('span', { class: 'row', style: { gap: '4px', alignItems: 'center' } }, 'Year', yFrom, '–', yTo),
      h('label', { class: 'row', style: { gap: '4px', fontSize: '13px' } }, hasNotes, 'With notes'), clearF,
      h('span', { style: { flex: 1 } }), sortLbl, sortSel, viewSw, kwSortSel),
    savedRow, ixLine, status, qinfo, out);
  let seq = 0, timer = 0, again = 0, ixTimer = 0, last = null, lastO = null, lastQ = '';
  const showIx = ix => { ixLine.textContent = indexingText(ix); ixLine.hidden = !ixLine.textContent; };
  const pollIx = async () => {
    if (!document.body.contains(main)) return;
    try { const r = await api('search-status'); showIx(r.indexing); if (r.indexing.running) ixTimer = setTimeout(pollIx, 2500); } catch (_) {}
  };
  pollIx();

  const yearOf = d => parseInt(d.year, 10) || 0;
  const sorters = {
    relevance: (a, b) => b.count - a.count,
    title: (a, b) => (a.doc.title || '').localeCompare(b.doc.title || ''),
    author: (a, b) => (a.doc.author || '').localeCompare(b.doc.author || '') || (a.doc.title || '').localeCompare(b.doc.title || ''),
    year: (a, b) => yearOf(b.doc) - yearOf(a.doc) || b.count - a.count,
    yearold: (a, b) => (yearOf(a.doc) || 9999) - (yearOf(b.doc) || 9999) || b.count - a.count,
    lastread: (a, b) => (b.doc.lastRead || 0) - (a.doc.lastRead || 0),
  };
  const linkFor = (d, hit) => isPdf(d) ? `#/read/${d.id}?page=${hit.loc}&find=${encodeURIComponent(lastQ)}${modeParam(lastO)}`
    : `#/read/${d.id}?href=${encodeURIComponent(hit.href)}&pos=${hit.loc}&find=${encodeURIComponent(lastQ)}&occ=${hit.occurrence}${modeParam(lastO)}`;
  const noteBtn = (d, hit) => h('button', { class: 'btn quiet hnote', title: 'Make a note from this passage', onclick: e => { e.preventDefault(); e.stopPropagation(); noteFromHit(d, hit, () => run(true)); } }, '+ Note');
  const where = (d, hit) => isPdf(d) ? 'p. ' + (hit.label || hit.loc) : (hit.label || 'pos. ' + hit.loc);

  function draw(r) {
    clear(out);
    const books = r.books.slice().sort(sorters[sort] || sorters.relevance);
    if (view === 'kwic') {
      const lines = [];
      books.forEach(b => b.hits.forEach(hit => {
        const t = hit.snippet.replace(/^…/, '').replace(/…$/, ''); const off = hit.snippet.startsWith('…') ? 1 : 0;
        lines.push({ b, hit, left: t.slice(0, hit.start - off), kw: t.slice(hit.start - off, hit.end - off), right: t.slice(hit.end - off) });
      }));
      const firstWord = s => (wordsOf(s)[0] || '');
      const lastWord = s => { const w = wordsOf(s); return w[w.length - 1] || ''; };
      if (kwSort === 'after') lines.sort((x, y) => firstWord(x.right).localeCompare(firstWord(y.right)));
      if (kwSort === 'before') lines.sort((x, y) => lastWord(x.left).localeCompare(lastWord(y.left)));
      const tb = h('div', { class: 'kwic' });
      lines.forEach(l => tb.append(h('a', { class: 'kwrow', href: linkFor(l.b.doc, l.hit), onclick: addRecent },
        h('span', { class: 'kwl' }, l.left.slice(-90)), h('span', { class: 'kwk' }, l.kw), h('span', { class: 'kwr' }, l.right.slice(0, 90)),
        h('span', { class: 'kws mono', title: l.b.doc.title }, (l.b.doc.author || '').split(/[;,]/)[0] + ' · ' + where(l.b.doc, l.hit)),
        noteBtn(l.b.doc, l.hit))));
      out.append(h('div', { class: 'muted', style: { fontSize: '12px', margin: '6px 0' } }, `${lines.length} lines (up to 40 per book). Click a line to open it in the book.`), tb);
    } else {
      if (r.notes.length) {
        out.append(h('div', { class: 'tsbook' }, h('div', { class: 'tshead' }, h('b', null, 'Your notes'), h('span', { class: 'muted mono' }, r.notes.length)),
          ...r.notes.slice(0, 30).map(n => h('a', { class: 'tshit', href: `#/read/${n.docId}?hl=${n.id}`, onclick: addRecent },
            h('div', null, h('b', null, n.title || 'Note'), h('span', { class: 'muted' }, ' · ' + n.docTitle + (n.label ? ' · p. ' + n.label : ''))), snippetEl(n.snippet, n.start, n.end)))));
      }
      books.forEach(b => {
        const d = b.doc;
        out.append(h('div', { class: 'tsbook' },
          h('div', { class: 'tshead' }, h('a', { href: `#/book/${d.id}` }, h('b', null, d.title)), h('span', { class: 'muted' }, ' ' + (d.displayAuthor || d.author || '') + (d.year ? ' · ' + d.year : '')),
            h('span', { class: 'muted mono', style: { marginLeft: 'auto' } }, `${b.count} page${b.count === 1 ? '' : 's'}`)),
          ...b.hits.map(hit => h('a', { class: 'tshit', href: linkFor(d, hit), onclick: addRecent },
            h('span', { class: 'tsloc mono' }, where(d, hit)), snippetEl(hit.snippet, hit.start, hit.end), noteBtn(d, hit))),
          b.count > b.hits.length && b.hits.length ? h('a', { class: 'muted', style: { fontSize: '12px', display: 'block', padding: '6px 12px' }, href: linkFor(d, b.hits[0]) + '&panel=1', onclick: addRecent }, `Open the book to see all ${b.count} pages →`) : null));
      });
    }
    if (!r.books.length && !r.notes.length) out.append(h('div', { class: 'empty' }, h('h2', null, 'Nothing found'),
      h('div', { class: 'muted' }, r.indexing && r.indexing.running ? 'Some books are still being read for search; try again in a little while.' : 'Try fewer or shorter words, Fuzzy instead of Exact, or fewer filters.')));
  }

  const run = async quiet => {
    if (ai) return;
    const q = input.value.trim();
    history.replaceState(null, '', '#/search' + (q ? '?q=' + encodeURIComponent(q) : ''));
    if (!q) { clear(out); status.textContent = ''; qinfo.textContent = ''; return; }
    const my = ++seq;
    if (quiet !== true) status.textContent = 'Searching…';
    let r;
    const o = { whole: whole.checked, exact, typos: typos.checked };
    try { r = await api(`search?q=${encodeURIComponent(q)}${modeParam(o)}${filterParams()}${view === 'kwic' ? '&per=40' : ''}`); } catch (e) { fail(e); return; }
    if (my !== seq) return;
    if (usedVariants(r)) o.typos = true;   // similar spellings were used: the book opens with them too
    last = r; lastO = o; lastQ = q;
    const total = r.books.reduce((a, b) => a + b.count, 0);
    const ix = r.indexing;
    status.textContent = `Found on ${total} page${total === 1 ? '' : 's'} in ${r.books.length} book${r.books.length === 1 ? '' : 's'} · ${r.notes.length} note${r.notes.length === 1 ? '' : 's'}` +
      (usedVariants(r) ? ` · including similar spellings (${Object.entries(r.variants).map(([k, v]) => k + ' → ' + v.slice(0, 4).join(', ')).join('; ')})` : '');
    qinfo.textContent = queryInfoText(r.query);
    showIx(ix);
    // While books are still being read, search again now and then: more results come in.
    clearTimeout(again); if (ix && ix.running) again = setTimeout(() => { if (document.body.contains(main)) run(true); }, 5000);
    draw(r);
    // A search that stays on screen for a while counts as a recent search.
    clearTimeout(recentTimer); if (quiet !== true && (r.books.length || r.notes.length)) recentTimer = setTimeout(addRecent, 4000);
  };
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(run, 450); });
  input.addEventListener('keydown', e => { if (e.key === 'Enter') { clearTimeout(timer); if (ai) runAi(); else run().then(addRecent); } });
  whole.addEventListener('change', () => { localSet('ml.findWhole', whole.checked ? '1' : '0'); run(); });
  typos.addEventListener('change', () => { localSet('ml.findTypos', typos.checked ? '1' : '0'); run(); });
  input.focus();
  setAi(ai && !params.get('q'));
  if (input.value && !ai) run();
}

// ------------------------------------------------------------------ in the open book

/** The reader's search panel. ctx.find = { q, whole, hits, i }. */
function readerSearchPanel(ctx, side) {
  const f = ctx.find || (ctx.find = { q: '', whole: localGet('ml.findWhole', '0') === '1', exact: localGet('ml.findExact', '0') === '1', typos: localGet('ml.findTypos', '0') === '1', hits: [], notes: [], i: -1 });
  const input = h('input', { type: 'search', placeholder: 'Search in this book…', value: f.q });
  const whole = h('input', { type: 'checkbox', checked: f.whole });
  const typos = h('input', { type: 'checkbox', checked: !!f.typos });
  const typosLbl = h('label', { class: 'row', title: 'Also find similar spellings (and OCR mistakes)', style: { gap: '4px', fontSize: '12px' } }, typos, 'Typos');
  typosLbl.hidden = !!f.exact;
  // Only the chapter being read (the reader tells which hits are in it).
  const chap = h('input', { type: 'checkbox', checked: !!f.chapter });
  const chapName = h('span', { class: 'muted', style: { fontSize: '11px' } });
  const chapLbl = h('label', { class: 'row', title: 'Only matches in the chapter you are reading', style: { gap: '4px', fontSize: '12px' } }, chap, 'This chapter only', chapName);
  const applyScope = () => {
    const all = f.all || f.hits || [];
    const range = f.chapter && ctx.chapterRange ? ctx.chapterRange() : null;
    chapName.textContent = range && range.label ? '(' + range.label + ')' : '';
    f.hits = range ? all.filter(range.test) : all;
  };
  chap.addEventListener('change', () => { f.chapter = chap.checked; applyScope(); f.i = -1; draw(); });
  const qinfo = h('div', { class: 'muted', style: { fontSize: '11px' } });
  const helpBox = searchHelpEl(); helpBox.hidden = true;
  // AI search in this book (only when the button is pressed).
  const aiOut = h('div', { class: 'aiinbook' });
  const aiSel = h('select', { title: 'How the AI searches' }, AI_METHODS.map(([k, l]) => h('option', { value: k, selected: localGet('ml.aiMethod', 'guided') === k }, l)));
  aiSel.addEventListener('change', () => localSet('ml.aiMethod', aiSel.value));
  const drawAi = () => {
    clear(aiOut);
    if (!f.ai) return;
    aiOut.append(h('div', { class: 'label', style: { marginTop: '8px' } }, `✦ AI: ${f.ai.results.length} passage${f.ai.results.length === 1 ? '' : 's'}` + (f.ai.note ? ' · ' + f.ai.note : '')),
      ...f.ai.results.map(r => h('div', { class: 'fhit', onclick: () => { if (readerOpen && readerOpen.goToHit) readerOpen.goToHit({ loc: r.loc, href: r.href, occurrence: 0 }, phraseOf(r), { exact: true }); } },
        h('span', { class: 'tsloc mono' }, (isPdf(ctx.d) ? 'p. ' + (r.label || r.loc) : (r.label || 'pos. ' + r.loc)) + ' · ' + Math.round(r.score * 100) + '%'),
        r.why ? h('div', { class: 'aiwhy' }, '✦ ' + r.why) : null, snippetEl(r.snippet, r.start, r.end),
        h('button', { class: 'btn quiet hnote', onclick: e => { e.stopPropagation(); noteFromHit(ctx.d, r, () => { if (ctx.refreshNotes) ctx.refreshNotes(); }); } }, '+ Note'))),
      h('button', { class: 'btn quiet', style: { fontSize: '11px' }, onclick: () => { f.ai = null; drawAi(); } }, 'Hide AI results'));
  };
  const askAi = async () => {
    const q = input.value.trim(); if (!q) { toast('Describe what you are looking for first'); return; }
    clear(aiOut); aiOut.append(h('div', { class: 'muted', style: { fontSize: '12px' } }, '✦ Asking the AI… (10–40 s)'));
    try { f.ai = await api(`ai-search?q=${encodeURIComponent(q)}&method=${aiSel.value}&doc=${ctx.d.id}`, { timeout: 180000 }); } catch (e) { clear(aiOut); fail(e); return; }
    drawAi();
  };
  const aiRow = h('div', { class: 'row', style: { gap: '6px', marginTop: '4px', alignItems: 'center' } }, h('button', { class: 'btn quiet', title: 'Find passages in this book by what they are about (AI; runs only now)', onclick: askAi }, '✦ Ask AI'), aiSel);
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
      (f.status && f.status.state !== 'done' ? ' so far' : '') + (!f.busy && usedVariants(f) ? ' · including similar spellings' : '')) : '';
    stLine.textContent = bookIndexText(f.status);
    stLine.classList.toggle('busy', !!f.status && f.status.state !== 'done');
    clear(list);
    f.hits.slice(0, 500).forEach((hit, k) => list.append(h('div', { class: 'fhit' + (k === f.i ? ' cur' : ''), onclick: () => go(k) },
      h('span', { class: 'tsloc mono' }, isPdf(ctx.d) ? 'p. ' + (hit.label || hit.loc) : (hit.label || 'pos. ' + hit.loc)), snippetEl(hit.snippet, hit.start, hit.end),
      h('button', { class: 'btn quiet hnote', title: 'Make a note from this passage', onclick: e => { e.stopPropagation(); noteFromHit(ctx.d, hit, () => { if (ctx.refreshNotes) ctx.refreshNotes(); }); } }, '+ Note'))));
    if (f.hits.length > 500) list.append(h('div', { class: 'muted' }, `… and ${f.hits.length - 500} more`));
    if (f.notes.length) {
      list.append(h('div', { class: 'label', style: { marginTop: '10px' } }, 'In your notes'));
      f.notes.forEach(n => list.append(h('div', { class: 'fhit', onclick: () => { const nn = ctx.notes.find(x => x.id === n.id); if (nn && readerOpen) readerOpen.goToNote(nn); } },
        h('b', null, n.title || 'Note'), ' ', snippetEl(n.snippet, n.start, n.end))));
    }
    const cur = list.querySelector('.fhit.cur'); if (cur) cur.scrollIntoView({ block: 'nearest' });
    qinfo.textContent = f.q ? queryInfoText(f.parsed) : '';
    renderHitMap(ctx, f, go);
  };
  let timer = 0, seq = 0, again = 0;
  const run = async quiet => {
    const q = input.value.trim();
    const same = quiet === true && q === f.q;
    f.q = q; f.whole = whole.checked; f.typos = typos.checked; if (!same) f.i = -1;
    if (!q) { f.hits = []; f.all = []; f.notes = []; if (readerOpen && readerOpen.clearFind) readerOpen.clearFind(); draw(); return; }
    const my = ++seq; if (!same) { f.busy = true; draw(); }
    try {
      const r = await api(`doc/${ctx.d.id}/search?q=${encodeURIComponent(q)}${modeParam(f)}`, { timeout: 60000 });
      if (my !== seq) return;
      f.all = r.hits; f.notes = r.notes; f.status = r.status; f.variants = r.variants || {}; f.parsed = r.query || null;
      applyScope();
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
    if (e.key === 'Enter') {
      e.preventDefault(); clearTimeout(timer);
      if (f.q !== input.value.trim()) run().then(() => { go(0); if (f.q.length > 1) savedApi('add', { q: f.q, mode: f.exact ? 'exact' : 'fuzzy', whole: !!f.whole, typos: !!f.typos, filters: {} }).catch(() => {}); });
      else go(f.i + (e.shiftKey ? -1 : 1));
    }
    e.stopPropagation();
  });
  whole.addEventListener('change', () => { localSet('ml.findWhole', whole.checked ? '1' : '0'); run(); });
  typos.addEventListener('change', () => { localSet('ml.findTypos', typos.checked ? '1' : '0'); run(); });
  side.append(h('h3', null, 'Search in this book'),
    h('div', { class: 'row', style: { gap: '4px' } }, input,
      h('button', { class: 'btn quiet', title: 'Previous (Shift+Enter)', onclick: () => go(f.i - 1) }, '↑'),
      h('button', { class: 'btn quiet', title: 'Next (Enter)', onclick: () => go(f.i + 1) }, '↓')),
    h('div', { class: 'row', style: { gap: '8px', marginTop: '4px', alignItems: 'center' } },
      modeSwitch(!!f.exact, v => { f.exact = v; localSet('ml.findExact', v ? '1' : '0'); typosLbl.hidden = v; run(); }),
      h('label', { class: 'row', style: { gap: '4px', fontSize: '12px' } }, whole, 'Whole words'), typosLbl),
    h('div', { class: 'row', style: { gap: '8px', marginTop: '2px', alignItems: 'center' } }, ctx.chapterRange ? chapLbl : null,
      h('span', { style: { flex: 1 } }), h('button', { class: 'btn quiet', style: { fontSize: '11px' }, onclick: () => { helpBox.hidden = !helpBox.hidden; } }, '? How to search')),
    helpBox, aiRow, aiOut, stLine, info, qinfo, list);
  if (!f.status) api(`doc/${ctx.d.id}/search?q=`).then(r => { f.status = r.status; draw(); }).catch(() => {});
  applyScope();
  draw(); drawAi();
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


/**
 * The hit map: small marks along the reader's page slider where the matches are, so clusters show.
 * The reader gives ctx.hitMap = { el, total } (positions 1…total).
 */
function renderHitMap(ctx, f, go) {
  const hm = ctx.hitMap;
  if (!hm || !hm.el) return;
  clear(hm.el);
  if (!f.q || !f.hits || !f.hits.length || !hm.total) { hm.el.hidden = true; return; }
  hm.el.hidden = false;
  const bins = new Map(), N = 160;
  f.hits.forEach((hit, k) => {
    const b = Math.min(N - 1, Math.floor(((hit.loc - 1) / Math.max(1, hm.total - 1)) * N));
    if (!bins.has(b)) bins.set(b, { first: k, n: 0, cur: false });
    const e = bins.get(b); e.n++; if (k === f.i) e.cur = true;
  });
  const max = Math.max(...[...bins.values()].map(e => e.n));
  bins.forEach((e, b) => hm.el.append(h('span', {
    class: 'hmt' + (e.cur ? ' cur' : ''), title: `${e.n} match${e.n === 1 ? '' : 'es'}`,
    style: { left: (b / N * 100) + '%', width: (100 / N) + '%', height: (35 + 65 * e.n / max) + '%' },
    onclick: ev => { ev.stopPropagation(); go(e.first); },
  })));
}

/** Settings → Search: synonym groups and saved searches. */
function searchSettingsSection() {
  const sec = h('section', { class: 'set-group', id: 'set-search' }, h('h2', null, 'Search'));
  const groupsEl = h('div', { class: 'syngroups', style: { maxHeight: 'none' } });
  const savedEl = h('div', { class: 'ssaved' });
  let groups = [];
  const put = async g => { try { groups = await api('search-synonyms', { method: 'PUT', json: g }); toast('Saved on the tablet', 1400); } catch (e) { fail(e); } draw(); };
  const draw = () => {
    clear(groupsEl);
    groups.forEach((g, i) => {
      const inp = h('input', { value: g.join(', '), style: { flex: 1 } });
      inp.addEventListener('change', () => { const c = groups.slice(); c[i] = inp.value.split(/[,=;]/).map(t => t.trim()).filter(Boolean); put(c.filter(x => x.length > 1)); });
      groupsEl.append(h('div', { class: 'row', style: { gap: '6px', alignItems: 'center' } }, inp,
        h('button', { class: 'btn quiet', onclick: () => put(groups.filter((_, k) => k !== i)) }, 'Remove')));
    });
    if (!groups.length) groupsEl.append(h('div', { class: 'muted' }, 'No synonym groups yet. Add one here or with “+ Synonym” on the Search page.'));
  };
  const add = h('input', { placeholder: 'far right, radical right, extreme right', style: { flex: 1 } });
  const addIt = () => { const g = add.value.split(/[,=;]/).map(t => t.trim()).filter(Boolean); if (g.length < 2) { toast('Write at least two terms, separated by commas'); return; } add.value = ''; put([...groups, g]); };
  add.addEventListener('keydown', e => { if (e.key === 'Enter') addIt(); });
  const drawSaved = r => {
    clear(savedEl);
    (r.pinned || []).forEach(e => savedEl.append(h('span', { class: 'schip pinned' }, h('button', { onclick: () => { location.hash = '#/search?q=' + encodeURIComponent(e.q) + (e.mode === 'exact' ? '&mode=exact' : ''); } }, '★ ' + e.q),
      h('button', { class: 'x', onclick: async () => { try { drawSaved(await savedApi('unpin', e)); } catch (er) { fail(er); } } }, '×'))));
    if (!(r.pinned || []).length) savedEl.append(h('span', { class: 'muted' }, 'No saved searches. Use “☆ Save search” on the Search page.'));
    if ((r.recent || []).length) savedEl.append(h('button', { class: 'btn quiet', onclick: async () => { try { drawSaved(await savedApi('clear')); toast('Recent searches cleared'); } catch (er) { fail(er); } } }, `Clear ${r.recent.length} recent searches`));
  };
  sec.append(
    h('div', { class: 'set-row' }, h('div', null, h('b', null, 'Synonyms'), h('div', { class: 'muted', style: { fontSize: '13px' } },
      'Terms that mean the same in every Fuzzy search: searching for one also finds the others. One group per line, terms separated by commas.'))),
    groupsEl, h('div', { class: 'row', style: { gap: '6px', margin: '8px 0 18px' } }, add, h('button', { class: 'btn', onclick: addIt }, 'Add group')),
    h('div', { class: 'set-row' }, h('div', null, h('b', null, 'Saved searches'))), savedEl, aiSettingsEl());
  api('search-synonyms').then(g => { groups = g; draw(); }).catch(() => {});
  api('search-saved').then(drawSaved).catch(() => {});
  return sec;
}

/** Settings → Search → AI search: the meaning indexes (Gemini and offline). */
function aiSettingsEl() {
  const box = h('div', { class: 'aiset' });
  let timer = 0;
  const post = async json => { try { draw(await api('ai-index', { method: 'POST', json })); } catch (e) { fail(e); } };
  const draw = st => {
    clear(box);
    const p = st.progress, dl = st.download;
    const pct = (a, b) => b ? Math.floor(a * 100 / b) + '%' : '0%';
    const g = st.gemini, l = st.local;
    const gRun = p.running && p.src === 'gemini', lRun = p.running && p.src === 'local';
    box.append(
      h('div', { class: 'set-row' }, h('div', null, h('b', null, 'AI search'), h('div', { class: 'muted', style: { fontSize: '13px' } },
        `Guided AI search uses ${st.provider} (Settings → AI) and needs nothing more. The two meaning searches need an index of the whole library, built once here (and updated when you press the button again).`))),
      h('div', { class: 'aiidx' },
        h('b', null, 'Meaning · Gemini'), h('div', { class: 'muted', style: { fontSize: '12px' } },
          'Sends the text of every page to Google once to get its meaning. Gemini’s free quota is limited, so a big library can take hours (it waits and continues by itself); with a paid key it is quick and costs about one or two dollars for the whole library.' + (st.hasGeminiKey ? '' : ' Add a Gemini API key in Settings → AI first.')),
        h('div', { class: 'mono', style: { fontSize: '12px' } }, `${g.done} of ${g.total} pages (${pct(g.done, g.total)})` + (gRun ? ` · ${p.note}` : '')),
        h('div', { class: 'row', style: { gap: '6px' } },
          gRun ? h('button', { class: 'btn', onclick: () => post({ action: 'stop' }) }, 'Stop')
            : h('button', { class: 'btn', disabled: !st.hasGeminiKey || p.running, onclick: () => post({ action: 'start', src: 'gemini' }) }, g.done ? 'Update index' : 'Build index'),
          g.done ? h('button', { class: 'btn quiet', onclick: () => { if (confirm('Delete the Gemini meaning index?')) post({ action: 'delete', src: 'gemini' }); } }, 'Delete index') : null)),
      h('div', { class: 'aiidx' },
        h('b', null, 'Meaning · offline'), h('div', { class: 'muted', style: { fontSize: '12px' } },
          'A small multilingual model (multilingual-e5-small, about 135 MB to download) runs on the tablet: nothing leaves it. Building the index is slow on an e-ink tablet (likely a few hours for the whole library; it runs in the background and can be stopped and continued).'),
        dl.running ? h('div', { class: 'mono', style: { fontSize: '12px' } }, `Downloading the model: ${Math.round(dl.done / 1048576)} of ${Math.round(dl.total / 1048576)} MB`) : null,
        dl.error ? h('div', { class: 'mono', style: { fontSize: '12px', color: '#b91c1c' } }, 'Download: ' + dl.error) : null,
        l.model ? h('div', { class: 'mono', style: { fontSize: '12px' } }, `${l.done} of ${l.total} pages (${pct(l.done, l.total)})` + (lRun ? ` · ${p.note}` : '')) : null,
        h('div', { class: 'row', style: { gap: '6px' } },
          !l.model ? h('button', { class: 'btn', disabled: dl.running, onclick: () => post({ action: 'download' }) }, 'Download the model (135 MB)')
            : lRun ? h('button', { class: 'btn', onclick: () => post({ action: 'stop' }) }, 'Stop')
              : h('button', { class: 'btn', disabled: p.running, onclick: () => post({ action: 'start', src: 'local' }) }, l.done ? 'Update index' : 'Build index'),
          l.model ? h('button', { class: 'btn quiet', onclick: () => { if (confirm('Delete the offline model and its index?')) post({ action: 'delete', src: 'local', model: true }); } }, 'Delete model and index') : null)),
      p.error && !p.running ? h('div', { class: 'mono', style: { fontSize: '12px', color: '#b91c1c' } }, 'Last error: ' + p.error) : '');
    clearTimeout(timer);
    if ((p.running || dl.running) && document.body.contains(box)) timer = setTimeout(() => api('ai-status').then(draw).catch(() => {}), 2500);
  };
  api('ai-status').then(draw).catch(() => box.append(h('div', { class: 'muted' }, 'AI search status not available.')));
  return box;
}
