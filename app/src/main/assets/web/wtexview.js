/* Marginalia Lectoris — the LaTeX view of a writing document: the whole .tex file, edited directly,
 * with the visual view a switch away (Ctrl+Alt+X).
 *
 * Both views are the document. Going to LaTeX, the text is written as .tex (Pandoc); if nothing was
 * changed in the visual view since, the LaTeX comes back exactly as you left it. Going back, the body is
 * read again (Pandoc); LaTeX the editor has no form for stays as grey boxes, comments by their markers.
 * A preamble you changed is kept as you wrote it and used from then on.
 */

let cmLoading = null;
function ensureCodeMirror() {
  if (window.CodeMirror) return Promise.resolve();
  if (!cmLoading) cmLoading = new Promise((res, rej) => {
    document.head.append(h('link', { rel: 'stylesheet', href: 'lib/codemirror/cm.css' }));
    const s = h('script', { src: 'lib/codemirror/cm.min.js' });
    s.onload = () => res(); s.onerror = () => { cmLoading = null; rej(new Error('The LaTeX editor could not be loaded')); };
    document.head.append(s);
  });
  return cmLoading;
}

function hashText(s) { let x = 5381; for (let i = 0; i < s.length; i++) x = ((x << 5) + x + s.charCodeAt(i)) | 0; return (x >>> 0).toString(36) + ':' + s.length; }

/** The first few real words of a line of LaTeX or of text (for finding the same place in the other view). */
function placeWords(s, n = 5) {
  return String(s || '').replace(/\\[a-zA-Z@]+\*?/g, ' ').replace(/[{}\[\]$%&#^_~\\]/g, ' ')
    .split(/[^\p{L}\p{N}]+/u).filter(w => w.length >= 3).slice(0, n);
}
function wordsRegex(words) { return words.length ? new RegExp(words.map(w => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.{1,40}?'), 'iu') : null; }

/**
 * o: { row (the element holding the sheet), sheet, editor, wrap (the page, for its class),
 *      exportTex: async () => ({ tex, back }), importTex: async (tex, back) => void (puts the text in the editor),
 *      state, saveState(state), onMode(mode) }
 */
function texView(o) {
  const st = Object.assign({ mode: 'visual', src: '', base: '', back: null, preamble: null, genPreamble: '' }, o.state || {});
  const host = h('div', { class: 'wtexcode', hidden: true });
  const info = h('div', { class: 'wtexinfo muted' });
  const resetPre = h('button', { class: 'btn quiet', hidden: true, title: 'Use the preamble made from the ⚙ LaTeX settings again', onclick: () => resetPreamble() }, 'Reset the preamble');
  const head = h('div', { class: 'wtexhead' }, h('b', null, 'LaTeX'), info, h('span', { style: { flex: 1 } }), resetPre,
    h('button', { class: 'btn quiet', title: 'Back to the visual view (Ctrl+Alt+X)', onclick: () => api_.toVisual() }, 'Visual ⇄'));
  const area = h('div', { class: 'wtexarea' });
  host.append(head, area);
  o.row.insertBefore(host, o.sheet.nextSibling);
  let cm = null, busy = false, saveTimer = 0, lastToggle = 0;
  const save = () => o.saveState(Object.assign({}, st));
  const docHash = () => hashText(JSON.stringify(o.editor.getJSON()));
  const say = (t) => { info.textContent = t; };

  async function makeEditor() {
    await ensureCodeMirror();
    if (cm) return;
    cm = CodeMirror(area, {
      value: '', mode: 'stex', lineNumbers: true, lineWrapping: true, matchBrackets: true, autoCloseBrackets: true,
      indentUnit: 2, tabSize: 2, viewportMargin: 30,
      extraKeys: {
        'Ctrl-S': () => { flush(); toast('Saved', 900); }, 'Cmd-S': () => { flush(); toast('Saved', 900); },
        'Ctrl-Alt-Enter': () => o.onCompile && o.onCompile(),
        'Ctrl-/': c => toggleComment(c), 'Cmd-/': c => toggleComment(c),
      },
    });
    cm.on('change', () => {
      st.src = cm.getValue();
      clearTimeout(saveTimer); saveTimer = setTimeout(save, 800);
      o.onChange && o.onChange();
    });
  }
  const flush = () => { clearTimeout(saveTimer); if (cm) st.src = cm.getValue(); save(); };
  /** % before the chosen lines (or away again). */
  function toggleComment(c) {
    const from = c.getCursor('from').line, to = c.getCursor('to').line;
    const lines = []; for (let i = from; i <= to; i++) lines.push(c.getLine(i));
    const all = lines.every(l => /^\s*%/.test(l) || !l.trim());
    c.operation(() => lines.forEach((l, k) => {
      const i = from + k;
      if (all) { const m = /^(\s*)% ?/.exec(l); if (m) c.replaceRange(m[1], { line: i, ch: 0 }, { line: i, ch: m[0].length }); }
      else if (l.trim()) c.replaceRange('% ', { line: i, ch: 0 });
    }));
  }
  function resetPreamble() {
    if (!cm) return;
    const parts = texSplit(cm.getValue());
    st.preamble = null;
    cm.setValue(st.genPreamble + parts.body + parts.end);
    resetPre.hidden = true; save();
    toast('The preamble is made from the ⚙ settings again', 2000);
  }

  /** The line in the LaTeX that matches where the cursor is in the visual view. */
  function lineForEditorPlace(text) {
    const ed = o.editor, $from = ed.state.selection.$from;
    const block = $from.depth ? $from.node(1) : null;
    const words = placeWords(block ? block.textContent : '');
    const lines = text.split('\n');
    const start = Math.max(0, lines.findIndex(l => /\\begin\{document\}/.test(l)));
    const re = wordsRegex(words.slice(0, 4)) || null;
    if (re) for (let i = start; i < lines.length; i++) if (re.test(lines[i])) return i;
    if (words.length) { const one = wordsRegex(words.slice(0, 2)); for (let i = start; i < lines.length; i++) if (one.test(lines[i])) return i; }
    // Otherwise: as far down the file as the cursor is down the text.
    const ratio = ed.state.doc.content.size ? $from.pos / ed.state.doc.content.size : 0;
    return Math.round(start + ratio * (lines.length - start));
  }
  /** The place in the visual view that matches a line of the LaTeX. */
  function editorPlaceForLine(text, line) {
    const lines = text.split('\n');
    let words = [];
    for (let i = line; i < Math.min(lines.length, line + 6) && words.length < 2; i++) words = placeWords(lines[i]);
    const re = wordsRegex(words.slice(0, 4)), re2 = wordsRegex(words.slice(0, 2));
    let found = null;
    o.editor.state.doc.descendants((n, pos) => {
      if (found != null || !n.isTextblock) return found == null;
      const t = n.textContent;
      if ((re && re.test(t)) || (re2 && re2.test(t))) found = pos + 1;
      return false;
    });
    return found;
  }

  async function toLatex(opts) {
    if (busy || st.mode === 'latex' && !(opts && opts.force)) return;
    busy = true;
    try {
      say('writing the LaTeX…');
      await makeEditor();
      const x = await o.exportTex();
      const parts = texSplit(x.tex);
      st.genPreamble = parts.preamble;
      st.back = x.back;
      const h0 = docHash();
      // Unchanged in the visual view since: the LaTeX exactly as it was left.
      const text = st.src && st.base === h0 ? st.src : (st.preamble != null ? st.preamble : parts.preamble) + parts.body + parts.end;
      st.src = text; st.base = h0; st.mode = 'latex'; st.synced = hashText(text);
      const line = lineForEditorPlace(text);
      enter();
      cm.setValue(text); cm.clearHistory();
      cm.refresh(); cm.focus();
      cm.setCursor({ line, ch: 0 }); cm.scrollIntoView({ line, ch: 0 }, 160);
      resetPre.hidden = st.preamble == null;
      say(st.preamble != null ? 'your own preamble' : 'preamble from ⚙');
      save();
    } catch (e) { fail(e); say(''); } finally { busy = false; }
  }
  async function toVisual() {
    if (busy || st.mode !== 'latex') return;
    busy = true;
    try {
      const text = cm.getValue();
      const line = cm.getCursor().line;
      if (hashText(text) !== st.synced) {
        say('reading the LaTeX…');
        const parts = texSplit(text);
        st.preamble = parts.preamble.trim() !== (st.genPreamble || '').trim() ? parts.preamble : null;
        await o.importTex(text, st.back);
      }
      st.mode = 'visual'; st.src = text; st.base = docHash(); st.synced = hashText(text);
      leave();
      const pos = editorPlaceForLine(text, line);
      if (pos != null) { try { o.editor.chain().focus().setTextSelection(pos).scrollIntoView().run(); } catch (_) { o.editor.commands.focus(); } }
      else o.editor.commands.focus();
      say(''); save();
    } catch (e) {
      fail(new Error('The LaTeX could not be read back: ' + (e.message || e) + ' — it stays in the LaTeX view.'));
      say('');
    } finally { busy = false; }
  }
  function enter() { host.hidden = false; o.sheet.hidden = true; o.wrap.classList.add('texmode'); o.onMode && o.onMode('latex'); }
  function leave() { host.hidden = true; o.sheet.hidden = false; o.wrap.classList.remove('texmode'); o.onMode && o.onMode('visual'); }

  const api_ = {
    get mode() { return st.mode; },
    toLatex, toVisual,
    toggle() {
      // One press is one switch (the key can reach both the LaTeX editor and the page).
      const now = Date.now(); if (busy || now - lastToggle < 600) return; lastToggle = now;
      return st.mode === 'latex' ? toVisual() : toLatex();
    },
    text: () => (cm && st.mode === 'latex' ? cm.getValue() : null),
    /** Opens the LaTeX view at a line (from an error in the PDF panel). */
    async gotoLine(n) {
      if (st.mode !== 'latex') await toLatex();
      if (!cm) return;
      const l = Math.max(0, n - 1);
      cm.focus(); cm.setCursor({ line: l, ch: 0 }); cm.scrollIntoView({ line: l, ch: 0 }, 160);
      cm.addLineClass(l, 'background', 'wtexhit'); setTimeout(() => cm && cm.removeLineClass(l, 'background', 'wtexhit'), 2500);
    },
    state: () => Object.assign({}, st),
    flush,
  };
  // Opened in the LaTeX view last time: open there again, with the text as it was.
  if (st.mode === 'latex' && st.src) {
    st.mode = 'visual';
    setTimeout(async () => {
      await makeEditor();
      const keep = st.src;
      st.mode = 'latex';
      try { const x = await o.exportTex(); st.genPreamble = texSplit(x.tex).preamble; st.back = x.back; } catch (_) {}
      enter(); cm.setValue(keep); cm.clearHistory(); cm.refresh();
      resetPre.hidden = st.preamble == null; say(st.preamble != null ? 'your own preamble' : 'preamble from ⚙');
    }, 50);
  }
  return api_;
}
