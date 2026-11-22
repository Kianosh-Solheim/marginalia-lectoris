/* Marginalia Lectoris — other words and other phrasings, from a right-click on selected text
 * (the writing program and its LaTeX view).
 *
 * Synonyms: at once from a thesaurus kept here (WordNet 3.1, grouped by meaning); online (Datamuse) and
 * in context (your AI) when you press for them. Phrasings: your AI, in formal British academic style,
 * labelled (shorter, more formal …), preferring your lexicon's words. Picking one replaces the selection;
 * the list stays open to try another (Ctrl+Z undoes).
 */

// ------------------------------------------------------------------ the thesaurus (WordNet)

let thesaurus = null, thesaurusLoading = null;
function loadThesaurus() {
  if (thesaurus) return Promise.resolve(thesaurus);
  if (!thesaurusLoading) thesaurusLoading = (async () => {
    const r = await fetch('lib/thesaurus.wn.gz');
    if (!r.ok) throw new Error('The thesaurus could not be loaded');
    let buf = new Uint8Array(await r.arrayBuffer());
    if (buf[0] === 0x1f && buf[1] === 0x8b) {
      const s = new Blob([buf]).stream().pipeThrough(new DecompressionStream('gzip'));
      buf = new Uint8Array(await new Response(s).arrayBuffer());
    }
    thesaurus = JSON.parse(new TextDecoder().decode(buf));
    return thesaurus;
  })().catch(e => { thesaurusLoading = null; throw e; });
  return thesaurusLoading;
}

// The base form of a word (WordNet's "morphy"), and how to put another word in the same form.
const MORPH = {
  n: [['ses', 's'], ['xes', 'x'], ['zes', 'z'], ['ches', 'ch'], ['shes', 'sh'], ['men', 'man'], ['ies', 'y'], ['s', '']],
  v: [['ies', 'y'], ['es', 'e'], ['es', ''], ['ed', 'e'], ['ed', ''], ['ing', 'e'], ['ing', ''], ['s', '']],
  a: [['er', ''], ['est', ''], ['er', 'e'], ['est', 'e']],
};
const POS_NAME = { n: 'noun', v: 'verb', a: 'adjective', r: 'adverb' };
/** Base forms of [word] the thesaurus knows: [{ lemma, form }] — form is how the word was bent (plural, past …). */
function thesaurusLemmas(T, word) {
  const w = word.toLowerCase().trim();
  const out = [], seen = new Set();
  const add = (lemma, form) => { if (T.l[lemma] && !seen.has(lemma)) { seen.add(lemma); out.push({ lemma, form }); } };
  add(w, null);
  if (typeof iseToIze === 'function') { const z = iseToIze(w); if (z && z !== w) add(z, null); }
  for (const [pos, rules] of Object.entries(MORPH)) for (const [suf, rep] of rules) {
    if (!w.endsWith(suf) || w.length <= suf.length + 1) continue;
    const stem = w.slice(0, -suf.length) + rep;
    const form = pos === 'n' ? 'plural' : pos === 'a' ? (suf === 'er' ? 'comparative' : 'superlative') : suf === 'ing' ? 'ing' : /ed$/.test(suf) ? 'past' : 's';
    add(stem, { pos, form });
    // stopped → stop, running → run
    if (/(ed|ing)$/.test(suf) && /([b-df-hj-np-tv-z])\1$/.test(w.slice(0, -suf.length))) add(w.slice(0, -suf.length - 1), { pos, form });
  }
  return out;
}
/** [word] (a base form) bent the way the selected word was. */
function inflectLike(word, form) {
  if (!form) return word;
  const parts = word.split(' ');
  const bend = w => {
    const f = form.form;
    if (f === 'plural' || f === 's') return /(s|x|z|ch|sh)$/.test(w) ? w + 'es' : /[^aeiou]y$/.test(w) ? w.slice(0, -1) + 'ies' : w + 's';
    if (f === 'past') return /e$/.test(w) ? w + 'd' : /[^aeiou]y$/.test(w) ? w.slice(0, -1) + 'ied' : w + 'ed';
    if (f === 'ing') return /ie$/.test(w) ? w.slice(0, -2) + 'ying' : /[^e]e$/.test(w) ? w.slice(0, -1) + 'ing' : w + 'ing';
    if (f === 'comparative') return /e$/.test(w) ? w + 'r' : /[^aeiou]y$/.test(w) ? w.slice(0, -1) + 'ier' : w + 'er';
    if (f === 'superlative') return /e$/.test(w) ? w + 'st' : /[^aeiou]y$/.test(w) ? w.slice(0, -1) + 'iest' : w + 'est';
    return w;
  };
  // Verbs bend their first word (take part → taking part); nouns their last (point of view → points of view is rare: last).
  if (form.pos === 'v') parts[0] = bend(parts[0]); else parts[parts.length - 1] = bend(parts[parts.length - 1]);
  return parts.join(' ');
}
/** The meanings of [word], each with its other words: [{ pos, gloss, words }]. */
async function thesaurusLookup(word) {
  const T = await loadThesaurus();
  const out = [];
  const lower = word.toLowerCase();
  for (const { lemma, form } of thesaurusLemmas(T, word)) {
    for (const i of T.l[lemma]) {
      const [pos, gloss, words, similar] = T.s[i];
      if (form && form.pos !== pos && !(form.pos === 'a' && pos === 'a')) continue;
      let list = words.filter(w => w.toLowerCase() !== lemma);
      // Adjectives: also the closely similar ones.
      (similar || []).slice(0, 6).forEach(j => { const s = T.s[j]; if (s) list.push(...s[2].slice(0, 2)); });
      list = [...new Set(list)].filter(w => w.toLowerCase() !== lemma && w.toLowerCase() !== lower).slice(0, 14);
      if (!list.length) continue;
      out.push({ pos, gloss, words: list.map(w => ({ text: matchCase(inflectLike(w, form), word), base: w })) });
    }
  }
  return out.slice(0, 12);
}
/** Capitalised like the original (Important → Significant). */
function matchCase(w, like) {
  if (like && like === like.toUpperCase() && like.length > 1) return w.toUpperCase();
  if (like && like[0] === like[0].toUpperCase() && like[0] !== like[0].toLowerCase()) return w[0].toUpperCase() + w.slice(1);
  return w;
}

// ------------------------------------------------------------------ the menu and the list

/**
 * Right-click on a selection. t: { text, before, after, latex, replace(newText) → void }.
 * Returns false when there is nothing to offer (the browser's own menu then shows).
 */
function phraseMenu(e, t) {
  const text = (t.text || '').trim();
  if (!text || e.shiftKey) return false;
  e.preventDefault(); e.stopPropagation();
  const words = text.replace(/⟦\d+⟧/g, ' ').split(/\s+/).filter(Boolean).length;
  const items = [];
  if (words <= 3 && !/⟦\d+⟧/.test(text)) items.push({ label: words === 1 ? 'Synonyms' : 'Other words for this', hint: 'thesaurus, online, AI', fn: () => phrasePanel(e.clientX, e.clientY, t, 'synonyms') });
  if (words >= 2) items.push({ label: 'Other ways to phrase it', hint: 'AI', fn: () => phrasePanel(e.clientX, e.clientY, t, 'rephrase') });
  items.push('-',
    { label: 'Copy', fn: () => document.execCommand('copy') },
    { label: 'Cut', fn: () => document.execCommand('cut') },
    { label: 'The browser’s own menu', hint: 'Shift+right-click', disabled: true });
  openCtxMenu(e.clientX, e.clientY, items);
  return true;
}

/** The list beside the text: synonyms or phrasings. Picking one replaces the selection; the list stays open. */
async function phrasePanel(x, y, t, kind) {
  document.querySelectorAll('.phrasepop').forEach(p => p.remove());
  const word = t.text.trim();
  const pop = h('div', { class: 'phrasepop', tabindex: '-1' });
  const body = h('div', { class: 'phrasebody' });
  const close = () => { pop.remove(); document.removeEventListener('mousedown', outside, true); document.removeEventListener('keydown', esc, true); };
  const outside = ev => { if (!pop.contains(ev.target)) close(); };
  const esc = ev => { if (ev.key === 'Escape') { ev.stopPropagation(); close(); } };
  const shown = word.length > 60 ? word.slice(0, 58) + '…' : word;
  const title = h('span', { class: 'phrasefor' }, `“${shown.replace(/⟦\d+⟧/g, '…')}”`);
  pop.append(h('div', { class: 'phrasehead' }, h('b', null, kind === 'synonyms' ? 'Other words' : 'Other phrasings'), title,
    h('span', { style: { flex: 1 } }), h('button', { class: 'btn quiet', title: 'Close (Esc)', onclick: close }, '×')), body);
  document.body.append(pop);
  pop.style.left = Math.max(8, Math.min(x, window.innerWidth - 440)) + 'px';
  pop.style.top = Math.max(8, Math.min(y + 12, window.innerHeight - 200)) + 'px';
  setTimeout(() => { document.addEventListener('mousedown', outside, true); document.addEventListener('keydown', esc, true); }, 0);
  // Picking: replace, and keep the list for the next try.
  let current = word;
  const pick = text => {
    try { t.replace(text); current = text; title.textContent = `“${(text.length > 60 ? text.slice(0, 58) + '…' : text).replace(/⟦\d+⟧/g, '…')}”`; }
    catch (e2) { fail(e2); }
  };
  const chip = (text, note) => h('button', { class: 'phrasechip', title: note ? note + ' — click to use' : 'Click to use', onclick: () => pick(text) }, text, note ? h('span', { class: 'phrasenote' }, note) : null);
  const sec = (label, ...kids) => h('div', { class: 'phrasesec' }, h('div', { class: 'phraselabel' }, label), ...kids);
  let lex = [];
  try { lex = await lexWords(); } catch (_) {}

  if (kind === 'synonyms') {
    // Your lexicon first: words you keep for this one ("Use instead of").
    const lw = word.toLowerCase();
    const mine = lex.filter(w => String(w.insteadOf || '').toLowerCase().split(/[,;]+/).map(s => s.trim()).filter(Boolean).some(s => s === lw || lw.startsWith(s) && lw.length - s.length <= 3));
    if (mine.length) body.append(sec('From your lexicon', h('div', { class: 'phrasechips' }, mine.map(w => chip(matchCase(w.word, word), w.definition ? w.definition.slice(0, 60) : '')))));
    const thes = h('div', null, h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Looking in the thesaurus…'));
    body.append(sec('Thesaurus', thes));
    const online = h('div');
    const ai = h('div');
    const btns = h('div', { class: 'phrasebtns' },
      h('button', { class: 'btn', title: 'Words that mean much the same, from Datamuse (online, free)', onclick: async ev => {
        ev.target.disabled = true; clear(online).append(h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Asking Datamuse…'));
        try {
          const lc = (t.before || '').trim().split(/\s+/).pop() || '', rc = (t.after || '').trim().split(/\s+/)[0] || '';
          const q = encodeURIComponent(word);
          const [a, b] = await Promise.all([
            fetch(`https://api.datamuse.com/words?rel_syn=${q}&max=30`).then(r => r.json()),
            fetch(`https://api.datamuse.com/words?ml=${q}&max=30${lc ? '&lc=' + encodeURIComponent(lc.replace(/[^\p{L}'-]/gu, '')) : ''}${rc ? '&rc=' + encodeURIComponent(rc.replace(/[^\p{L}'-]/gu, '')) : ''}`).then(r => r.json()),
          ]);
          const seen = new Set([word.toLowerCase()]);
          const list = [...a, ...b].map(x => x.word).filter(w => w && !seen.has(w.toLowerCase()) && seen.add(w.toLowerCase())).slice(0, 36);
          clear(online).append(sec('Online (Datamuse)', list.length ? h('div', { class: 'phrasechips' }, list.map(w => chip(matchCase(w, word)))) : h('div', { class: 'muted' }, 'Nothing found.')));
        } catch (e2) { clear(online).append(h('div', { class: 'muted' }, 'Datamuse could not be reached (no internet?).')); }
      } }, 'More online'),
      h('button', { class: 'btn', title: 'Words that fit this sentence, from your AI (Settings → AI)', onclick: async ev => {
        ev.target.disabled = true; clear(ai).append(h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Asking your AI…'));
        try {
          const r = await api('rephrase', { method: 'POST', json: { kind: 'synonyms', text: word, before: t.before, after: t.after, latex: !!t.latex, lexicon: lex.map(w => w.word) }, timeout: 90000 });
          const list = (r.synonyms || []).filter(s => s && s.text);
          clear(ai).append(sec('In this sentence (AI)', list.length ? h('div', { class: 'phrasechips' }, list.map(s => chip(matchCase(s.text, word), s.note))) : h('div', { class: 'muted' }, 'No suggestions.')));
        } catch (e2) { clear(ai).append(h('div', { class: 'muted' }, String(e2.message || e2))); ev.target.disabled = false; }
      } }, 'In context (AI)'));
    btns.append(h('button', { class: 'btn quiet', title: 'Put the original back', onclick: () => pick(word) }, 'Original'));
    body.append(btns, online, ai);
    try {
      const senses = await thesaurusLookup(word);
      clear(thes);
      if (!senses.length) thes.append(h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Not in the thesaurus. Try online or AI.'));
      senses.forEach(sn => thes.append(h('div', { class: 'phrasesense' },
        h('div', { class: 'phrasegloss' }, h('i', null, POS_NAME[sn.pos] || sn.pos), ' · ', sn.gloss),
        h('div', { class: 'phrasechips' }, sn.words.map(w => chip(w.text))))));
    } catch (e2) { clear(thes).append(h('div', { class: 'muted' }, String(e2.message || e2))); }
  } else {
    // Phrasings: the AI, labelled; "More" asks again for different ones.
    const list = h('div', { class: 'phraselist' }, h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Asking your AI…'));
    const seen = [];
    const more = h('button', { class: 'btn', disabled: true }, 'More');
    const ask = async () => {
      more.disabled = true;
      try {
        const r = await api('rephrase', { method: 'POST', json: { kind: 'rephrase', text: word, before: t.before, after: t.after, latex: !!t.latex, lexicon: lex.map(w => w.word), avoid: seen }, timeout: 120000 });
        if (!seen.length) clear(list);
        const vs = (r.variants || []).filter(v => v && v.text);
        if (!vs.length && !seen.length) list.append(h('div', { class: 'muted' }, 'No suggestions.'));
        vs.forEach(v => {
          seen.push(v.text);
          list.append(h('button', { class: 'phrasevar', title: 'Click to use', onclick: () => pick(v.text) },
            h('span', { class: 'phraselabel' }, v.label || 'Alternative'), h('span', { class: 'phrasetext' }, v.text.replace(/⟦(\d+)⟧/g, '[cit.]'))));
        });
      } catch (e2) { if (!seen.length) clear(list); list.append(h('div', { class: 'muted' }, String(e2.message || e2))); }
      more.disabled = false;
    };
    more.onclick = ask;
    body.append(list, h('div', { class: 'phrasebtns' }, more, h('button', { class: 'btn quiet', title: 'Put the original back', onclick: () => pick(word) }, 'Original')));
    ask();
  }
}
