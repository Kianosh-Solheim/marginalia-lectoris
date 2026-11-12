/* Marginalia Lectoris — the notes panel of the writing program.
 *
 * Your notes from the library sit beside the text. Drag one into the text (or press Insert) and you
 * are asked how it should go in: as a quotation, a block quotation, in your own words, or as a
 * citation only. Each comes with a citation of its book (or chapter) with the page filled in.
 */

const NOTE_MIME = 'application/x-ml-note';
const QUOTE_MARKS = { double: ['“', '”'], single: ['‘', '’'], guillemets: ['«', '»'] };
let wnotesCache = null;

/** The quotation marks to use: the last choice, else what fits the citation language. */
function quoteMarksFor(citing) {
  const saved = localGet('ml.wquoteMarks', '');
  if (QUOTE_MARKS[saved]) return saved;
  const l = (citing.lang || '').toLowerCase();
  return /^(nb|nn|no|fr|de-ch)/.test(l) ? 'guillemets' : /^en-gb/.test(l) ? 'single' : 'double';
}
const cleanQuote = s => String(s || '').replace(/­/g, '').replace(/(\w)-\s*\n\s*(\w)/g, '$1$2').replace(/\s+/g, ' ').trim();

/** Every note id cited in the document (also inside footnotes). */
function usedNoteIds(editor) {
  const ids = new Set();
  const walkJson = n => { if (!n) return; if (n.type === 'citation') (n.attrs.items || []).forEach(i => { if (i.noteId) ids.add(String(i.noteId)); }); (n.content || []).forEach(walkJson); };
  editor.state.doc.descendants(n => {
    if (n.type.name === 'citation') (n.attrs.items || []).forEach(i => { if (i.noteId) ids.add(String(i.noteId)); });
    if (n.type.name === 'footnote') { walkJson(n.attrs.content); return false; }
    return true;
  });
  return ids;
}

/** The panel. Returns { el, open, close, toggle, isOpen, refresh, insertAt }. */
function notesPanel(editor, citing, onToggle) {
  const el = h('aside', { class: 'wnotes', hidden: true });
  const q = h('input', { type: 'search', placeholder: 'Search notes, quotes, books, tags…' });
  const bookSel = h('select', { title: 'Which book' });
  const tagSel = h('select', { title: 'Tag' });
  const count = h('div', { class: 'label' });
  const list = h('div', { class: 'wnlist' });
  const collapsed = new Set();
  let used = new Set(), timer = 0;
  el.append(
    h('div', { class: 'wnhead' }, h('b', null, 'Notes'), h('span', { style: { flex: 1 } }),
      h('button', { class: 'btn quiet', title: 'Load the notes again', onclick: () => load(true) }, '↻'),
      h('button', { class: 'btn quiet', title: 'Close the panel', onclick: () => api_.close() }, '×')),
    h('div', { class: 'wnhelp muted' }, 'Drag a note into the text, or press Insert to put it at the cursor.'),
    q, h('div', { class: 'row', style: { gap: '6px' } }, bookSel, tagSel), count, list);
  q.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(draw, 150); });
  bookSel.addEventListener('change', () => { localSet('ml.wnotesBook', bookSel.value); draw(); });
  tagSel.addEventListener('change', draw);

  async function load(force) {
    if (wnotesCache && !force) { fillFilters(); draw(); return; }
    clear(list).append(h('div', { class: 'muted' }, 'Loading your notes…'));
    try { wnotesCache = ((await api('highlights')).highlights || []); }
    catch (e) { clear(list).append(h('div', { class: 'muted' }, 'Could not load the notes: ' + e.message)); return; }
    fillFilters(); draw();
  }
  function citedDocs() {
    return new Set(Object.keys(citing.items).filter(k => /^ml:\d+/.test(k)).map(k => k.split(':')[1]));
  }
  function fillFilters() {
    const books = new Map();
    wnotesCache.forEach(n => { if (!books.has(n.documentId)) books.set(n.documentId, n.documentTitle || 'Untitled'); });
    const keep = bookSel.value || localGet('ml.wnotesBook', '');
    clear(bookSel).append(h('option', { value: '' }, 'All books'), h('option', { value: 'cited' }, 'Books cited in this text'),
      [...books.entries()].sort((a, b) => a[1].localeCompare(b[1])).map(([id, t]) => h('option', { value: String(id) }, t.length > 60 ? t.slice(0, 58) + '…' : t)));
    bookSel.value = [...bookSel.options].some(o => o.value === keep) ? keep : '';
    const tags = new Set(); wnotesCache.forEach(n => splitTags(n.tags).forEach(t => tags.add(t)));
    const kt = tagSel.value;
    clear(tagSel).append(h('option', { value: '' }, 'All tags'), [...tags].sort((a, b) => a.localeCompare(b)).map(t => h('option', { value: t }, '#' + t)));
    tagSel.value = [...tagSel.options].some(o => o.value === kt) ? kt : '';
  }
  function draw() {
    if (!wnotesCache || el.hidden) return;
    used = usedNoteIds(editor);
    const terms = norm(q.value.trim().replace(/^#/, '')).split(/\s+/).filter(Boolean);
    const book = bookSel.value, tag = tagSel.value, cited = book === 'cited' ? citedDocs() : null;
    const hits = wnotesCache.filter(n =>
      (!book || (cited ? cited.has(String(n.documentId)) : String(n.documentId) === book)) &&
      (!tag || splitTags(n.tags).includes(tag)) &&
      (!terms.length || terms.every(t => norm([n.title, n.highlightedText, n.userNotes, n.tags, n.documentTitle, n.documentAuthor, n.chapterTitle].join(' ')).includes(t))));
    count.textContent = `${hits.length} note${hits.length === 1 ? '' : 's'}` + (used.size ? ` · ${used.size} used in this text` : '');
    clear(list);
    if (!hits.length) { list.append(h('div', { class: 'muted', style: { padding: '12px 0' } }, cited && !cited.size ? 'Nothing from the library is cited yet.' : 'No notes match.')); return; }
    const groups = new Map();
    hits.forEach(n => { if (!groups.has(n.documentId)) groups.set(n.documentId, []); groups.get(n.documentId).push(n); });
    const ordered = [...groups.entries()].sort((a, b) => Math.max(...b[1].map(n => n.created)) - Math.max(...a[1].map(n => n.created)));
    let shown = 0;
    ordered.forEach(([docId, notes]) => {
      notes.sort((a, b) => a.pageNumber - b.pageNumber || a.created - b.created);
      const shut = collapsed.has(docId) && !terms.length;
      list.append(h('div', { class: 'wnbook', onclick: () => { if (collapsed.has(docId)) collapsed.delete(docId); else collapsed.add(docId); draw(); } },
        h('span', { class: 'wncaret' }, shut ? '▸' : '▾'), h('span', { class: 'wnbt' }, notes[0].documentTitle),
        h('span', { class: 'muted' }, ' · ' + notes.length)));
      if (shut) return;
      notes.forEach(n => { if (shown++ < 400) list.append(card(n)); });
    });
    if (shown > 400) list.append(h('div', { class: 'muted' }, `Showing 400 of ${shown}. Search or pick a book to narrow it down.`));
  }
  function card(n) {
    const isUsed = used.has(String(n.id));
    const quote = cleanQuote(n.highlightedText);
    const c = h('div', { class: 'wncard' + (isUsed ? ' used' : ''), draggable: 'true', title: 'Drag into the text' },
      n.title ? h('div', { class: 'wnt' }, n.title) : null,
      quote ? h('div', { class: 'wnq' }, quote) : null,
      n.userNotes ? h('div', { class: 'wnn' }, n.userNotes) : null,
      h('div', { class: 'wnfoot' },
        h('span', { class: 'muted' }, noteLocus(n, { fileType: n.fileType }) || ''),
        isUsed ? h('span', { class: 'wnused', title: 'This note is cited in the text' }, '✓ used') : null,
        h('span', { style: { flex: 1 } }),
        h('a', { class: 'btn quiet', href: `#/book/${n.documentId}`, target: '_blank', title: 'Open the book in a new tab', onclick: e => e.stopPropagation() }, '↗'),
        h('button', { class: 'btn quiet', title: 'Put it at the cursor', onclick: e => { e.stopPropagation(); insertNote(editor, citing, n, null); } }, 'Insert')));
    c.addEventListener('dragstart', e => {
      e.dataTransfer.setData(NOTE_MIME, String(n.id));
      e.dataTransfer.setData('text/plain', quote || n.userNotes || n.title || '');
      e.dataTransfer.effectAllowed = 'copy';
      c.classList.add('dragging');
    });
    c.addEventListener('dragend', () => c.classList.remove('dragging'));
    c.querySelector('.wnq') && c.querySelector('.wnq').addEventListener('click', e => { e.stopPropagation(); e.currentTarget.classList.toggle('open'); });
    return c;
  }
  const api_ = {
    el,
    isOpen: () => !el.hidden,
    open() { el.hidden = false; localSet('ml.wnotesOpen', '1'); load(false); onToggle && onToggle(true); setTimeout(() => q.focus(), 30); },
    close() { el.hidden = true; localSet('ml.wnotesOpen', ''); onToggle && onToggle(false); },
    toggle() { if (el.hidden) api_.open(); else api_.close(); },
    refresh() {
      clearTimeout(timer);
      timer = setTimeout(() => { if (el.hidden) return; const now = usedNoteIds(editor); if (now.size !== used.size || [...now].some(x => !used.has(x))) draw(); }, 500);
    },
    /** A drop on the editor: true when it was a note. */
    handleDrop(view, e) {
      const id = e.dataTransfer && e.dataTransfer.getData(NOTE_MIME);
      if (!id) return false;
      e.preventDefault();
      const n = (wnotesCache || []).find(x => String(x.id) === id);
      if (!n) return true;
      const p = view.posAtCoords({ left: e.clientX, top: e.clientY });
      insertNote(editor, citing, n, p ? p.pos : null);
      return true;
    },
  };
  return api_;
}

/** The "how should it go in?" dialog, then the insertion at [pos] (null: the cursor). */
async function insertNote(editor, citing, n, pos) {
  const quote = cleanQuote(n.highlightedText), own = (n.userNotes || '').trim();
  let src;
  try { await citing.ready; src = await api('writing/cite-note?id=' + encodeURIComponent(n.id)); }
  catch (e) { fail(e); return; }
  const s = src.source;
  citing.items[s.id] = s.csl; if (s.uri) citing.uris[s.id] = s.uri;
  const at = pos != null ? pos : editor.state.selection.to;
  const words = quote.split(/\s+/).filter(Boolean).length;
  const modes = [
    ['quote', 'Quotation in the text', 'The quoted words in quotation marks, with the citation', !!quote],
    ['block', 'Block quotation', 'The quote as its own indented paragraph (for longer quotes)', !!quote],
    ['own', 'In your own words', 'Write or edit the text; the citation follows it', true],
    ['cite', 'Citation only', 'Just the citation, e.g. after a sentence you have written', true],
  ];
  let mode = !quote ? (own ? 'own' : 'cite') : words > 40 ? 'block' : 'quote';
  let marks = quoteMarksFor(citing);

  modal((box, close) => {
    box.classList.add('wide');
    const choice = h('div', { class: 'wnmodes' });
    const text = h('textarea', { rows: 5, class: 'wntext' });
    const textLabel = h('div', { class: 'label' });
    const loc = h('input', { value: src.locator || '', placeholder: 'e.g. 276', style: { width: '110px' } });
    const lab = h('select', { style: { width: 'auto' } }, CITE_LOCATORS.map(([v, l]) => h('option', { value: v, selected: (src.label || 'page') === v }, l)));
    const omit = h('input', { type: 'checkbox' });
    const markSel = h('select', { title: 'Quotation marks', style: { width: 'auto' } }, Object.entries(QUOTE_MARKS).map(([k, [a, b]]) => h('option', { value: k, selected: k === marks }, `${a}…${b}`)));
    const preview = h('div', { class: 'cpreview' });
    const marksRow = h('label', { class: 'row', style: { gap: '6px', fontSize: '12px', alignItems: 'center' } }, 'Quotation marks', markSel);
    const textFor = m => m === 'own' ? (own || quote) : quote;
    let edited = {};
    const item = () => { const o = { id: s.id, noteId: n.id }; if (loc.value.trim()) { o.locator = loc.value.trim(); o.label = lab.value; } if (omit.checked) o.suppressAuthor = true; return o; };
    const drawModes = () => {
      clear(choice);
      modes.forEach(([k, l, d, ok], i) => choice.append(h('button', { class: 'wnmode' + (k === mode ? ' on' : ''), disabled: !ok, title: ok ? '' : 'This note has no quoted text', onclick: () => setMode(k) },
        h('b', null, `${i + 1}  ${l}`), h('div', { class: 'muted' }, d))));
    };
    const setMode = k => {
      if (text.dataset.mode) edited[text.dataset.mode] = text.value;
      mode = k; text.dataset.mode = k;
      text.value = edited[k] != null ? edited[k] : k === 'own' ? textFor('own') : k === 'cite' ? '' : textFor(k);
      text.hidden = textLabel.hidden = k === 'cite';
      marksRow.hidden = k !== 'quote';
      textLabel.textContent = k === 'own' ? 'Your text (edit it freely)' : k === 'block' ? 'The quotation (shorten with […] if you like)' : 'The quoted words (shorten with […] if you like)';
      drawModes(); drawPreview();
    };
    const drawPreview = async () => {
      clear(preview);
      try {
        const html = citing.engine.previewCitationCluster({ citationID: 'preview', citationItems: [citing.citeItem(item())], properties: { noteIndex: citing.isNote ? 1 : 0 } }, [], [], 'html');
        const cite = h('span', { class: citing.isNote ? 'muted' : '', html: citing.isNote ? ' [footnote: ' + html + ']' : html });
        const [a, b] = QUOTE_MARKS[markSel.value];
        const t = text.value.trim(), m = t.match(/^([\s\S]*?)([.!?]?)$/), body = m[1].length > 160 ? m[1].slice(0, 157) + '…' : m[1], stop = m[2];
        const lead = mode === 'quote' ? (citing.isNote ? a + body + stop + b : a + body + b + ' ') : mode === 'block' ? h('span', { class: 'wnbq' }, body + stop, ' ') : mode === 'own' ? (citing.isNote ? body + stop : body + ' ') : '';
        const tail = !citing.isNote && (mode === 'quote' || mode === 'own') ? stop : '';
        preview.append(h('span', { class: 'muted' }, 'Preview: '), lead, cite, tail);
      } catch (_) {}
    };
    [loc, lab, omit, markSel].forEach(x => { x.addEventListener('input', drawPreview); x.addEventListener('change', drawPreview); });
    text.addEventListener('input', drawPreview);
    const go = () => {
      const t = text.value.trim();
      if ((mode === 'quote' || mode === 'block' || mode === 'own') && !t) { toast('There is no text to insert'); return; }
      if (mode === 'quote') localSet('ml.wquoteMarks', markSel.value);
      const cit = { type: 'citation', attrs: { citationID: newCiteId(), items: [item()] } };
      // Full stop after the citation in author–date styles, before the note number in note styles.
      const split = str => { const m = str.match(/^([\s\S]*?)([.!?]?)$/); return [m[1], m[2]]; };
      let nodes, block = false;
      if (mode === 'cite') nodes = [cit];
      else if (mode === 'quote') {
        const [a, b] = QUOTE_MARKS[markSel.value];
        const [body, stop] = split(t);
        nodes = citing.isNote ? [{ type: 'text', text: a + body + (stop || '') + b }, cit] : [{ type: 'text', text: a + body + b + ' ' }, cit].concat(stop ? [{ type: 'text', text: stop }] : []);
      } else if (mode === 'own') {
        const [body, stop] = split(t);
        nodes = citing.isNote ? [{ type: 'text', text: body + stop }, cit] : [{ type: 'text', text: body + ' ' }, cit].concat(stop ? [{ type: 'text', text: stop }] : []);
      } else {
        block = true;
        nodes = [{ type: 'blockquote', content: [{ type: 'paragraph', content: [{ type: 'text', text: t + ' ' }, cit] }] }];
      }
      try {
        let where = Math.min(at, editor.state.doc.content.size);
        if (block) {
          // A block quotation goes after the paragraph it was dropped in.
          const $p = editor.state.doc.resolve(where);
          if ($p.parent.isTextblock && $p.depth > 0) where = $p.after($p.depth);
        } else {
          // Inline: a space before it when it lands right after a word.
          const before = editor.state.doc.textBetween(Math.max(0, where - 1), where, '\n', '\n');
          if (before && /\S/.test(before) && nodes[0].type === 'text') nodes[0] = Object.assign({}, nodes[0], { text: ' ' + nodes[0].text });
          else if (before && /\S/.test(before) && !citing.isNote) nodes.unshift({ type: 'text', text: ' ' });
        }
        const sizeBefore = editor.state.doc.content.size;
        editor.chain().focus().insertContentAt(where, nodes).run();
        if (block) {
          // The cursor goes into a paragraph after the quotation (a new one if a heading or the like follows).
          const bq = editor.state.doc.nodeAt(where);
          const after = where + (bq ? bq.nodeSize : 0);
          const next = editor.state.doc.nodeAt(after);
          if (!next || next.type.name !== 'paragraph' || next.content.size) editor.chain().insertContentAt(after, { type: 'paragraph' }, { updateSelection: false }).run();
          editor.commands.setTextSelection(after + 1);
        } else {
          const end = where + (editor.state.doc.content.size - sizeBefore);
          editor.commands.setTextSelection(Math.min(end, editor.state.doc.content.size));
        }
        citing.ensureBibliography(editor);
      } catch (e) { fail(e); return; }
      close(); citing.onChange(); citing.recompute(editor);
    };
    box.addEventListener('keydown', e => {
      if (e.target === text || e.target === loc) { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); go(); } return; }
      const k = +e.key; if (k >= 1 && k <= modes.length && modes[k - 1][3]) { e.preventDefault(); setMode(modes[k - 1][0]); }
      if (e.key === 'Enter') { e.preventDefault(); go(); }
    });
    box.append(h('h2', null, 'Insert note'),
      h('div', { class: 'wnsrc' }, h('b', null, s.label || ''), ' ', s.title, s.source === 'chapter' && s.container ? h('span', { class: 'muted' }, ' · in ' + s.container) : null),
      n.title ? h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Note: ' + n.title) : null,
      h('div', { class: 'label', style: { marginTop: '10px' } }, 'How should it go in?'), choice,
      textLabel, text,
      h('div', { class: 'row', style: { gap: '8px', flexWrap: 'wrap', alignItems: 'center', marginTop: '8px' } },
        lab, loc, h('label', { class: 'row', style: { gap: '4px', fontSize: '12px' } }, omit, 'Omit author (you name them in the text)'), marksRow),
      preview,
      h('div', { class: 'foot' }, h('span', { class: 'muted', style: { fontSize: '12px', flex: 1 } }, 'Keys 1–4 pick, Enter inserts (Ctrl+Enter in the text box).'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn fill', onclick: go }, 'Insert')));
    setMode(mode);
    setTimeout(() => (mode === 'own' ? text : box.querySelector('.wnmode.on') || text).focus(), 40);
  });
}
