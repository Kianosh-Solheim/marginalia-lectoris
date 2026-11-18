/* Marginalia Lectoris — the lexicon: words worth keeping, with what they mean and where they were found.
 * Kept on the tablet (lexicon.json). Words come in from the reader (select a word → "Lexicon +"), by hand,
 * or from the tablet. A definition can wait; "Define with AI" fills one in when asked.
 */
let lexCache = null;            // { at, words }
async function lexWords(fresh) {
  if (!fresh && lexCache && Date.now() - lexCache.at < 5000) return lexCache.words;
  const r = await api('lexicon'); lexCache = { at: Date.now(), words: r.words || [] };
  return lexCache.words;
}
const lexDrop = () => { lexCache = null; };
const lexTags = w => splitTags(w.tags || '');

/** The sentence around a selection (for the example): from the PDF text layer, an EPUB page or a field. */
function lexSentence(word) {
  const tryWin = win => {
    try {
      const s = win.getSelection(); if (!s || s.isCollapsed || !s.anchorNode) return '';
      let el = s.anchorNode.nodeType === 1 ? s.anchorNode : s.anchorNode.parentElement;
      while (el && el.parentElement && (el.textContent || '').length < 300 && !el.matches('.textLayer, p, li, blockquote, td')) el = el.parentElement;
      return (el && el.textContent) || '';
    } catch (_) { return ''; }
  };
  let text = tryWin(window);
  if (!text) document.querySelectorAll('iframe').forEach(f => { if (!text) { try { text = tryWin(f.contentWindow); } catch (_) {} } });
  text = text.replace(/\s+/g, ' ').trim(); if (!text) return '';
  const at = text.toLowerCase().indexOf(String(word).toLowerCase().trim()); if (at < 0) return '';
  const before = text.slice(0, at), m = [...before.matchAll(/[.!?…]["'’”)]?\s/g)].pop();
  const start = m ? m.index + m[0].length : Math.max(0, at - 200);
  const rest = text.slice(at + word.length), e = rest.search(/[.!?…]/);
  return text.slice(start, e < 0 ? Math.min(text.length, at + word.length + 200) : at + word.length + e + 1).trim().slice(0, 400);
}

/** The dialog for one word. [w] is a saved word, or the start of a new one ({ word, example, docId, source, page }). */
function lexEditDialog(w, onSaved) {
  const isNew = !w.id;
  modal((box, close) => {
    box.style.width = '620px';
    const word = h('input', { value: w.word || '' }), pos = h('input', { value: w.partOfSpeech || '', placeholder: 'noun, verb, …' });
    const def = h('textarea', { rows: 3, placeholder: 'Leave empty to define it later' }, w.definition || '');
    const ex = h('textarea', { rows: 2 }, w.example || ''), note = h('textarea', { rows: 2 }, w.note || ''), tags = h('input', { value: w.tags || '', placeholder: 'Separated by commas' });
    const msg = h('div', { class: 'muted', style: { fontSize: '13px', minHeight: '16px' } });
    const aiBtn = h('button', { class: 'btn', onclick: async () => {
      if (!word.value.trim()) return toast('Write the word first');
      aiBtn.disabled = true; aiBtn.textContent = 'Asking the AI…'; msg.textContent = '';
      try {
        const r = await api('lexicon/define', { method: 'POST', json: { word: word.value.trim(), example: ex.value.trim() } });
        def.value = r.definition || ''; if (!pos.value.trim()) pos.value = r.partOfSpeech || '';
        msg.textContent = 'The AI’s definition is filled in. Change it if you like, then save.';
      } catch (e) { msg.textContent = e.message || 'The AI could not define it.'; }
      aiBtn.disabled = false; aiBtn.textContent = '✦ Define with AI';
    } }, '✦ Define with AI');
    const save = async () => {
      const json = { word: word.value.trim(), partOfSpeech: pos.value.trim(), definition: def.value.trim(), example: ex.value.trim(), note: note.value.trim(), tags: tags.value.trim() };
      if (!json.word) return toast('The word is empty');
      try {
        let r;
        if (isNew) {
          r = await api('lexicon', { method: 'POST', json: Object.assign({ docId: w.docId || 0, source: w.source || '', page: w.page || '' }, json) });
          if (r.existed) { lexDrop(); close(); toast(`“${r.word}” is already in the lexicon`); return lexEditDialog(r, onSaved); }
        } else r = await api('lexicon/' + encodeURIComponent(w.id), { method: 'PATCH', json });
        lexDrop(); close(); toast(isNew ? `“${r.word}” added to the lexicon` : 'Saved'); onSaved && onSaved(r);
      } catch (e) { fail(e); }
    };
    const del = () => confirmBox(`Delete “${w.word}”?`, 'The word and what you wrote about it are removed from the lexicon.', 'Delete', async () => {
      try { await api('lexicon/' + encodeURIComponent(w.id), { method: 'DELETE' }); lexDrop(); close(); toast('Deleted'); onSaved && onSaved(null); } catch (e) { fail(e); }
    });
    [word, def, ex, note].forEach(x => x.addEventListener('keydown', e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); save(); } }));
    box.append(h('h2', null, isNew ? 'Add to the lexicon' : w.word),
      h('div', { class: 'fields' },
        h('div', { class: 'row', style: { flexDirection: 'row', gap: '8px' } },
          h('label', { style: { flex: 2 } }, h('span', { class: 'label' }, 'Word'), word),
          h('label', { style: { flex: 1 } }, h('span', { class: 'label' }, 'Part of speech'), pos)),
        h('div', { class: 'dfield' }, h('span', { class: 'label' }, 'Definition'), withMic(def)),
        h('div', { class: 'row', style: { flexDirection: 'row', gap: '8px', alignItems: 'center' } }, aiBtn, msg),
        h('label', null, h('span', { class: 'label' }, 'Example — the sentence it was found in'), ex),
        h('div', { class: 'dfield' }, h('span', { class: 'label' }, 'Your note'), withMic(note)),
        h('label', null, h('span', { class: 'label' }, 'Tags'), tags),
        w.source ? h('div', { class: 'muted mono', style: { fontSize: '11px' } }, 'From: ' + w.source + (w.page ? ', p. ' + w.page : '')) : null),
      h('div', { class: 'foot' },
        !isNew ? h('button', { class: 'btn quiet', style: { marginRight: 'auto', color: 'var(--red)' }, onclick: del }, 'Delete') : null,
        h('span', { class: 'muted', style: { fontSize: '12px', marginRight: '8px' } }, 'Ctrl+Enter saves'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, isNew ? 'Add' : 'Save')));
    if (isNew && !def.value) setTimeout(() => def.focus(), 30);
  });
}

/** From the reader's selection bar. */
function lexAddFromReader(ctx, sel) {
  const word = sel.text.replace(/\s+/g, ' ').trim().replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '');
  lexEditDialog({ word, example: lexSentence(word), docId: ctx.d.id, source: ctx.d.title || '', page: sel.pageRef || '' });
}

/** The lexicon page. */
async function showLexicon() {
  const app = clear(document.getElementById('app'));
  state.tab = 'lexicon';
  app.appendChild(header());
  const main = h('main', { class: 'lxpage' }); app.appendChild(main);
  let words = [];
  try { words = await lexWords(true); } catch (e) { fail(e); }
  let q = '', only = localGet('ml.lxOnly', 'all'), sort = localGet('ml.lxSort', 'az'), tag = '';
  const search = h('input', { type: 'search', placeholder: 'Search words, definitions, notes, sources', style: { flex: '1 1 260px' } });
  const list = h('div', { class: 'lxlist' }), count = h('span', { class: 'muted mono', style: { fontSize: '12px' } });
  const tagSel = h('select');
  const reload = async () => { try { words = await lexWords(true); } catch (e) { fail(e); } draw(); };
  const draw = () => {
    const allTags = [...new Set(words.flatMap(lexTags))].sort((a, b) => a.localeCompare(b));
    clear(tagSel).append(h('option', { value: '' }, 'All tags'), ...allTags.map(t => h('option', { value: t, selected: t === tag }, '#' + t)));
    const terms = norm(q).split(/\s+/).filter(Boolean);
    let shown = words.filter(w => (only !== 'undef' || !w.definition) && (!tag || lexTags(w).includes(tag)) &&
      terms.every(t => norm([w.word, w.definition, w.note, w.tags, w.source, w.example].join(' ')).includes(t)));
    shown = sort === 'new' ? shown.sort((a, b) => b.added - a.added) : shown.sort((a, b) => a.word.localeCompare(b.word, undefined, { sensitivity: 'base' }));
    count.textContent = `${shown.length} of ${words.length} · ${words.filter(w => !w.definition).length} without a definition`;
    clear(list);
    if (!words.length) { list.append(h('div', { class: 'empty' }, h('h2', null, 'No words yet'), h('p', { class: 'muted' }, 'While reading, select a word and choose “Lexicon +” in the bar that appears. On the tablet: Define → “Save to lexicon”.'))); return; }
    if (!shown.length) { list.append(h('div', { class: 'muted', style: { padding: '16px 0' } }, 'Nothing matches.')); return; }
    let letter = '';
    shown.forEach(w => {
      const L = (w.word[0] || '').toUpperCase();
      if (sort === 'az' && L !== letter) { letter = L; list.append(h('div', { class: 'lxletter serif' }, L)); }
      list.append(h('div', { class: 'lxrow', onclick: () => lexEditDialog(w, reload) },
        h('div', { class: 'lxhead' }, h('b', { class: 'serif' }, w.word), w.partOfSpeech ? h('i', { class: 'serif muted' }, ' ' + w.partOfSpeech) : null),
        h('div', { class: 'lxdef serif' + (w.definition ? '' : ' none') }, w.definition || 'No definition yet — click to add one.'),
        w.example ? h('div', { class: 'lxex serif' }, '“' + w.example + '”') : null,
        h('div', { class: 'lxmeta mono muted' }, ...[
          w.source ? (w.docId ? h('a', { href: '#/book/' + w.docId, onclick: e => e.stopPropagation() }, w.source) : w.source) : null,
          w.page ? ', p. ' + w.page : null,
          lexTags(w).length ? '  ' + lexTags(w).map(t => '#' + t).join(' ') : null].filter(Boolean))));
    });
  };
  search.addEventListener('input', () => { q = search.value; draw(); });
  tagSel.addEventListener('change', () => { tag = tagSel.value; draw(); });
  const seg = (key, opts, get, set) => h('div', { class: 'mxtabs', style: { margin: 0 } }, opts.map(([v, label]) => h('button', { class: get() === v ? 'on' : '', onclick: e => { set(v); localSet(key, v); e.target.parentNode.querySelectorAll('button').forEach(b => b.classList.toggle('on', b === e.target)); draw(); } }, label)));
  main.append(
    h('div', { class: 'row', style: { marginBottom: '10px', alignItems: 'baseline' } }, h('h1', { style: { margin: 0 } }, 'Lexicon'), count, h('span', { style: { flex: 1 } }),
      menuButton('Export ▾', [['Obsidian', 'One note per word and a .base table (zip)', () => lexExport(words, 'base')], ['Markdown', 'All words in one note', () => lexExport(words, 'md')], ['CSV', 'For a spreadsheet', () => lexExport(words, 'csv')]]),
      h('button', { class: 'btn fill', onclick: () => lexEditDialog({ word: '' }, reload) }, '+ Add word')),
    h('div', { class: 'row', style: { gap: '8px', marginBottom: '12px', flexWrap: 'wrap' } }, search, tagSel,
      seg('ml.lxOnly', [['all', 'All'], ['undef', 'Without a definition']], () => only, v => { only = v; }),
      seg('ml.lxSort', [['az', 'A–Z'], ['new', 'Newest']], () => sort, v => { sort = v; })),
    list);
  draw();
  search.focus();
}

async function lexExport(words, kind) {
  if (!words.length) return toast('The lexicon is empty');
  const sorted = words.slice().sort((a, b) => a.word.localeCompare(b.word, undefined, { sensitivity: 'base' }));
  const y = s => JSON.stringify(String(s == null ? '' : s));
  const day = t => new Date(t).toISOString().slice(0, 10);
  if (kind === 'csv') {
    const q = v => '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"';
    const rows = [['Word', 'Part of speech', 'Definition', 'Example', 'Source', 'Page', 'Note', 'Tags', 'Added'], ...sorted.map(w => [w.word, w.partOfSpeech, w.definition, w.example, w.source, w.page, w.note, w.tags, day(w.added)])];
    return saveBlob(new Blob(['﻿' + rows.map(r => r.map(q).join(',')).join('\r\n') + '\r\n'], { type: 'text/csv;charset=utf-8' }), 'Lexicon.csv');
  }
  if (kind === 'md') {
    const L = ['---', 'tags:', '  - Lexicon', '---', '', '# Lexicon', ''];
    sorted.forEach(w => {
      L.push(`**${w.word}**${w.partOfSpeech ? ' *' + w.partOfSpeech + '*' : ''} — ${w.definition || '*(no definition yet)*'}`);
      if (w.example) L.push(`> ${w.example}${w.source ? ` — ${w.source}${w.page ? ', p. ' + w.page : ''}` : ''}`);
      L.push('');
    });
    return saveBlob(new Blob([L.join('\n')], { type: 'text/markdown;charset=utf-8' }), 'Lexicon.md');
  }
  const zip = new JSZip(), root = zip.folder('Lexicon'), taken = new Set();
  sorted.forEach(w => {
    let f = mdName(w.word), k = 2; while (taken.has(f.toLowerCase())) f = mdName(w.word) + ' (' + k++ + ')'; taken.add(f.toLowerCase());
    const L = ['---', 'word: ' + y(w.word), 'part_of_speech: ' + y(w.partOfSpeech), 'definition: ' + y(w.definition), 'source: ' + y(w.source ? '[[' + mdName('Notes on ' + w.source) + '|' + w.source.replace(/[|\]]/g, ' ') + ']]' : ''), 'page: ' + y(w.page), 'added: ' + day(w.added), 'tags:', '  - Lexicon', ...lexTags(w).map(t => '  - ' + y(t.replace(/\s+/g, '_'))), '---', '', '# ' + w.word, ''];
    if (w.partOfSpeech) L.push('*' + w.partOfSpeech + '*', '');
    L.push(w.definition || '*No definition yet.*', '');
    if (w.example) L.push('> ' + w.example, '>', `> — ${w.source || ''}${w.page ? ', p. ' + w.page : ''}`, '');
    if (w.note) L.push('## Note', '', w.note, '');
    root.file(f + '.md', L.join('\n'));
  });
  root.file('Lexicon.base', ['filters:', '  and:', '    - file.inFolder("Lexicon")', '    - file.ext == "md"', 'properties:', '  file.name:', '    displayName: Word',
    '  note.part_of_speech:', '    displayName: Part of speech', '  note.definition:', '    displayName: Definition', '  note.source:', '    displayName: Source', '  note.page:', '    displayName: Page', '  note.added:', '    displayName: Added',
    'views:', '  - type: table', '    name: Lexicon', '    order:', '      - file.name', '      - note.part_of_speech', '      - note.definition', '      - note.source', '      - note.page', '      - note.added', '    sort:', '      - property: file.name', '        direction: ASC'].join('\n') + '\n');
  saveBlob(await zip.generateAsync({ type: 'blob' }), 'Lexicon (Obsidian).zip');
  toast('Unpack the zip into your vault and open Lexicon.base');
}

/** The writing program's lexicon panel: your words at hand. Click to put one in at the cursor. */
function lexiconPanel(editor, onToggle) {
  const el = h('aside', { class: 'wlexicon', hidden: true });
  const q = h('input', { type: 'search', placeholder: 'Find a word or a meaning' });
  const list = h('div', { class: 'wlxlist' });
  let words = [];
  el.append(h('div', { class: 'wnhead' }, h('b', null, 'Lexicon'), h('span', { style: { flex: 1 } }),
      h('button', { class: 'btn quiet', title: 'Add a word', onclick: () => lexEditDialog({ word: (() => { const { from, to } = editor.state.selection; return to > from && to - from < 60 ? editor.state.doc.textBetween(from, to, ' ') : ''; })() }, () => load(true)) }, '+'),
      h('button', { class: 'btn quiet', title: 'Close the lexicon', onclick: () => api_.close() }, '×')), q, list);
  const draw = () => {
    clear(list);
    const terms = norm(q.value).split(/\s+/).filter(Boolean);
    const shown = words.filter(w => terms.every(t => norm(w.word + ' ' + w.definition + ' ' + w.tags).includes(t))).sort((a, b) => a.word.localeCompare(b.word, undefined, { sensitivity: 'base' }));
    if (!words.length) list.append(h('div', { class: 'muted', style: { fontSize: '13px' } }, 'No words yet. Add them while reading (select a word → “Lexicon +”) or with + above.'));
    shown.forEach(w => {
      const row = h('div', { class: 'wlxrow', title: 'Click to put it in at the cursor · double-click to open it' },
        h('b', { class: 'serif' }, w.word), w.partOfSpeech ? h('i', { class: 'muted serif' }, ' ' + w.partOfSpeech) : null,
        h('div', { class: 'wlxdef serif' + (w.definition ? '' : ' none') }, w.definition || 'No definition yet'));
      let timer = 0;
      row.addEventListener('mousedown', e => e.preventDefault());
      row.addEventListener('click', () => { clearTimeout(timer); timer = setTimeout(() => {
        const { from } = editor.state.selection, before = from > 1 ? editor.state.doc.textBetween(from - 1, from, ' ', ' ') : '';
        editor.chain().focus().insertContent((before && !/\s/.test(before) ? ' ' : '') + w.word).run();
      }, 220); });
      row.addEventListener('dblclick', () => { clearTimeout(timer); lexEditDialog(w, () => load(true)); });
      list.append(row);
    });
  };
  const load = async fresh => { try { words = await lexWords(fresh); } catch (e) { fail(e); } draw(); };
  q.addEventListener('input', draw);
  const api_ = {
    el,
    isOpen: () => !el.hidden,
    open() { el.hidden = false; localSet('ml.wlexOpen', '1'); load(true); onToggle && onToggle(true); },
    close() { el.hidden = true; localSet('ml.wlexOpen', ''); onToggle && onToggle(false); },
    toggle() { if (el.hidden) api_.open(); else api_.close(); },
  };
  return api_;
}
