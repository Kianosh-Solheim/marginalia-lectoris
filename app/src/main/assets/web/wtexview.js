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
  const splitBtn = h('button', { class: 'btn quiet', onclick: () => api_.toggleSplit() }, 'Side by side');
  const head = h('div', { class: 'wtexhead' }, h('b', null, 'LaTeX'), info, h('span', { style: { flex: 1 } }), resetPre, splitBtn,
    h('button', { class: 'btn quiet', title: 'Back to the visual view (Ctrl+Alt+X)', onclick: () => api_.toVisual() }, 'Visual ⇄'));
  const area = h('div', { class: 'wtexarea' });
  host.append(head, area);
  o.row.insertBefore(host, o.sheet.nextSibling);
  let cm = null, busy = false, saveTimer = 0, lastToggle = 0, lastFocus = 'visual';
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
    // Right-click on selected LaTeX: other words, other phrasings.
    cm.getWrapperElement().addEventListener('contextmenu', e => {
      if (!cm.somethingSelected()) return;
      let from = cm.getCursor('from'), to = cm.getCursor('to');
      const line = cm.getLine(from.line);
      phraseMenu(e, { text: cm.getSelection(), before: line.slice(0, from.ch), after: cm.getLine(to.line).slice(to.ch), latex: true,
        replace: t2 => { cm.replaceRange(t2, from, to); const end = cm.posFromIndex(cm.indexFromPos(from) + t2.length); cm.setSelection(from, end); to = end; } });
    });
    cm.on('focus', () => { lastFocus = 'latex'; });
    cm.on('cursorActivity', () => { o.onCursor && o.onCursor(); });
    o.editor.on('focus', () => { lastFocus = 'visual'; });
    cm.on('change', () => {
      if (applying) return;
      st.src = cm.getValue();
      if (st.mode === 'split') { pending = 'latex'; schedule(); }
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

  /** The LaTeX from the visual view as it is now (your own preamble kept). */
  async function freshTex() {
    const x = await o.exportTex();
    const parts = texSplit(x.tex);
    st.genPreamble = parts.preamble; st.back = x.back;
    return (st.preamble != null ? st.preamble : parts.preamble) + parts.body + parts.end;
  }
  /** Reads the LaTeX into the visual view, if it changed since the two were last the same. */
  async function readBack(text) {
    if (hashText(text) === st.synced) return false;
    const parts = texSplit(text);
    st.preamble = parts.preamble.trim() !== (st.genPreamble || '').trim() ? parts.preamble : null;
    applying = true;
    try { await o.importTex(text, Object.assign({}, st.back, { keyRev: Object.assign({}, st.back && st.back.keyRev, st.citeKeys) })); } finally { applying = false; }
    return true;
  }
  const markSynced = text => { st.src = text; st.base = docHash(); st.synced = hashText(text); };
  const preSay = () => { resetPre.hidden = st.preamble == null; say(st.preamble != null ? 'your own preamble' : 'preamble from ⚙'); };
  function setText(text, keepView) {
    applying = true;
    try {
      if (keepView) { const sc = cm.getScrollInfo(), cur = cm.getCursor(); cm.setValue(text); cm.scrollTo(sc.left, sc.top); cm.setCursor({ line: Math.min(cur.line, cm.lineCount() - 1), ch: 0 }, null, { scroll: false }); }
      else { cm.setValue(text); cm.clearHistory(); }
    } finally { applying = false; }
  }

  /** To the LaTeX view (mode 'latex': LaTeX only; 'split': both side by side). */
  async function toLatex(mode) {
    mode = mode === 'split' ? 'split' : 'latex';
    if (busy || st.mode === mode) return;
    busy = true;
    try {
      await makeEditor();
      if (st.mode === 'split' || st.mode === 'latex') {
        // Between the two LaTeX layouts: bring the other side up to date first.
        await syncNow();
        st.mode = mode; show(); cm.refresh(); save(); return;
      }
      say('writing the LaTeX…');
      const h0 = docHash();
      // Unchanged in the visual view since: the LaTeX exactly as it was left.
      const text = st.src && st.base === h0 ? (await freshTex(), st.src) : await freshTex();
      markSynced(text);
      st.mode = mode;
      const line = lineForEditorPlace(text);
      show();
      setText(text);
      cm.refresh(); if (mode === 'latex') cm.focus();
      cm.setCursor({ line, ch: 0 }, null, { scroll: false }); cm.scrollIntoView({ line, ch: 0 }, 160);
      preSay(); save();
    } catch (e) { fail(e); say(''); } finally { busy = false; }
  }
  async function toVisual() {
    if (busy || st.mode === 'visual') return;
    busy = true;
    clearTimeout(syncTimer); pending = null;
    try {
      const text = cm.getValue();
      const line = cm.getCursor().line;
      say('reading the LaTeX…');
      await readBack(text);
      markSynced(text);
      st.mode = 'visual';
      show();
      const pos = editorPlaceForLine(text, line);
      if (pos != null) { try { o.editor.chain().focus().setTextSelection(pos).scrollIntoView().run(); } catch (_) { o.editor.commands.focus(); } }
      else o.editor.commands.focus();
      say(''); save();
    } catch (e) {
      fail(new Error('The LaTeX could not be read back: ' + (e.message || e) + ' — it stays in the LaTeX view.'));
      say('');
    } finally { busy = false; }
  }
  function show() {
    const m = st.mode;
    host.hidden = m === 'visual';
    o.sheet.hidden = m === 'latex';
    o.wrap.classList.toggle('texmode', m === 'latex');
    o.wrap.classList.toggle('texsplit', m === 'split');
    splitBtn.textContent = m === 'split' ? 'LaTeX only' : 'Side by side';
    splitBtn.title = m === 'split' ? 'Only the LaTeX (Ctrl+Alt+Shift+X)' : 'The visual view and the LaTeX side by side (Ctrl+Alt+Shift+X)';
    o.onMode && o.onMode(m);
  }

  // ---- side by side: the side you write in leads; the other follows when you pause.
  let pending = null, syncTimer = 0, applying = false;
  const schedule = () => { clearTimeout(syncTimer); syncTimer = setTimeout(() => syncNow(), 1500); };
  async function syncNow() {
    clearTimeout(syncTimer);
    const what = pending; pending = null;
    if (!what || !cm) return;
    if (syncing) { pending = what; schedule(); return; }
    syncing = true;
    try {
      if (what === 'visual') {
        say('following the visual view…');
        const text = await freshTex();
        if (pending === 'latex') return;          // you began writing in the LaTeX meanwhile
        setText(text, true);
        markSynced(text);
      } else {
        say('following the LaTeX…');
        const text = cm.getValue();
        const sc = document.scrollingElement ? document.scrollingElement.scrollTop : 0;
        const inner = o.sheet.closest('.wscroll'), st2 = inner ? inner.scrollTop : 0;
        await readBack(text);
        markSynced(text);
        if (document.scrollingElement) document.scrollingElement.scrollTop = sc; if (inner) inner.scrollTop = st2;
      }
      preSay(); save();
    } catch (e) {
      say(what === 'latex' ? 'the LaTeX could not be read: ' + String(e.message || e).slice(0, 120) : 'could not write the LaTeX');
    } finally { syncing = false; if (pending) schedule(); }
  }
  let syncing = false;

  // ---- the toolbar in the LaTeX: the same buttons, as LaTeX.
  const LTX_ESC = { '\\': '\\textbackslash{}', '&': '\\&', '%': '\\%', '$': '\\$', '#': '\\#', '_': '\\_', '{': '\\{', '}': '\\}', '~': '\\textasciitilde{}', '^': '\\textasciicircum{}' };
  const esc = t => String(t).replace(/[\\&%$#_{}~^]/g, c => LTX_ESC[c]);
  const MARK_CMD = { bold: 'textbf', italic: 'emph', underline: 'underline', strike: 'st', superscript: 'textsuperscript', subscript: 'textsubscript' };
  /** The editor's content (a few nodes) as LaTeX. */
  function pmToTex(nodes, keys) {
    const inl = n => {
      if (n.type === 'text') {
        let t = esc(n.text);
        (n.marks || []).forEach(m => { if (MARK_CMD[m.type]) t = `\\${MARK_CMD[m.type]}{${t}}`; else if (m.type === 'link' && m.attrs && m.attrs.href) t = `\\href{${m.attrs.href}}{${t}}`; });
        return t;
      }
      if (n.type === 'citation') return latexCiteText(n.attrs, keys);
      if (n.type === 'footnote') return '\\footnote{' + ((n.attrs.content && n.attrs.content.content) || []).map(blk).join(' ').trim() + '}';
      if (n.type === 'math') return n.attrs.display ? `\\[${n.attrs.tex}\\]` : `$${n.attrs.tex}$`;
      if (n.type === 'rawTex') return n.attrs.tex;
      if (n.type === 'hardBreak') return '\\\\\n';
      return (n.content || []).map(inl).join('');
    };
    const blk = n => {
      if (n.type === 'paragraph') return (n.content || []).map(inl).join('');
      if (n.type === 'heading') return `\\${['section', 'section', 'subsection', 'subsubsection', 'paragraph'][n.attrs.level] || 'paragraph'}{${(n.content || []).map(inl).join('')}}`;
      if (n.type === 'blockquote') return '\\begin{quote}\n' + (n.content || []).map(blk).join('\n\n') + '\n\\end{quote}';
      if (n.type === 'rawTexBlock') return n.attrs.tex;
      if (n.isBlock || n.content && n.content.some(c => c.type === 'paragraph')) return (n.content || []).map(blk).join('\n\n');
      return inl(n);
    };
    const blocks = nodes.some(n => ['paragraph', 'heading', 'blockquote', 'rawTexBlock'].includes(n.type));
    return blocks ? nodes.map(blk).join('\n\n') : nodes.map(inl).join('');
  }
  /** Citation keys for the sources cited in [nodes] (keys made up for sources without one are remembered). */
  async function keysFor(nodes) {
    const info = await resolveCiteKeys(o.citing, { type: 'doc', content: [{ type: 'paragraph', content: nodes.filter(n => !n.isBlock) }].concat(nodes.filter(n => n.type !== 'text' && n.type !== 'citation')) });
    st.citeKeys = st.citeKeys || {};
    Object.entries(info.keys).forEach(([id, k]) => { st.citeKeys[k] = id; });
    return info.keys;
  }
  /** Wraps the selection in \\cmd{…}, or puts \\cmd{} at the cursor with the cursor inside. */
  function wrap(before, after) {
    const sel = cm.getSelection();
    if (sel) { cm.replaceSelection(before + sel + after, 'around'); }
    else { const c = cm.getCursor(); cm.replaceRange(before + after, c); cm.setCursor({ line: c.line, ch: c.ch + before.length }); }
    cm.focus();
  }
  function insert(text, block) {
    const c = cm.getCursor();
    if (block) { const line = cm.getLine(c.line); text = (line.trim() ? '\n\n' : '') + text + '\n'; if (line.trim()) cm.setCursor({ line: c.line, ch: line.length }); }
    cm.replaceSelection(text, 'end'); cm.focus();
  }
  /** The chosen lines, as a list environment (or back to plain lines). */
  function listify(env) {
    const a = cm.getCursor('from').line, b = cm.getCursor('to').line;
    const lines = []; for (let i = a; i <= b; i++) lines.push(cm.getLine(i));
    const out = ['\\begin{' + env + '}'].concat(lines.filter(l => l.trim()).map(l => '  \\item ' + l.replace(/^\s*(\\item\s*)?/, ''))).concat(['\\end{' + env + '}']);
    cm.replaceRange(out.join('\n'), { line: a, ch: 0 }, { line: b, ch: cm.getLine(b).length }); cm.focus();
  }
  function heading(v) {
    const a = cm.getCursor('from').line, b = cm.getCursor('to').line;
    if (v === 'quote') { const t = cm.getRange({ line: a, ch: 0 }, { line: b, ch: cm.getLine(b).length }); cm.replaceRange('\\begin{quote}\n' + t + '\n\\end{quote}', { line: a, ch: 0 }, { line: b, ch: cm.getLine(b).length }); cm.focus(); return; }
    if (v === 'code') { const t = cm.getRange({ line: a, ch: 0 }, { line: b, ch: cm.getLine(b).length }); cm.replaceRange('\\begin{verbatim}\n' + t + '\n\\end{verbatim}', { line: a, ch: 0 }, { line: b, ch: cm.getLine(b).length }); cm.focus(); return; }
    const cmd = { h1: 'section', h2: 'subsection', h3: 'subsubsection', h4: 'paragraph' }[v];
    cm.operation(() => { for (let i = a; i <= b; i++) {
      const l = cm.getLine(i); if (!l.trim()) continue;
      const m = /^(\s*)\\(?:sub)*section\*?\{(.*)\}\s*$|^(\s*)\\paragraph\*?\{(.*)\}\s*$/.exec(l);
      const inner = m ? (m[2] != null ? m[2] : m[4]) : l.trim();
      cm.replaceRange(cmd ? `\\${cmd}{${inner}}` : inner, { line: i, ch: 0 }, { line: i, ch: l.length });
    } });
    cm.focus();
  }
  const COMMANDS = {
    undo: () => cm.undo(), redo: () => cm.redo(),
    bold: () => wrap('\\textbf{', '}'), italic: () => wrap('\\emph{', '}'), underline: () => wrap('\\underline{', '}'), strike: () => wrap('\\st{', '}'),
    sup: () => wrap('\\textsuperscript{', '}'), sub: () => wrap('\\textsubscript{', '}'),
    link: () => { const url = prompt('Link address'); if (url) wrap(`\\href{${url}}{`, '}'); },
    ul: () => listify('itemize'), ol: () => listify('enumerate'),
    indent: () => { cm.indentSelection('add'); cm.focus(); }, outdent: () => { cm.indentSelection('subtract'); cm.focus(); },
    table: () => insert('\\begin{table}[htbp]\n  \\centering\n  \\begin{tabular}{lll}\n    \\hline\n    A & B & C \\\\\n    \\hline\n    1 & 2 & 3 \\\\\n    \\hline\n  \\end{tabular}\n  \\caption{}\n\\end{table}', true),
    footnote: () => wrap('\\footnote{', '}'), formula: () => wrap('$', '$'),
    hr: () => insert('\\noindent\\rule{\\linewidth}{0.4pt}', true),
    bibliography: () => insert('\\printbibliography', true),
    clear: () => { const s = cm.getSelection(); if (s) cm.replaceSelection(s.replace(/\\(textbf|emph|textit|underline|st|sout|textsuperscript|textsubscript|textsc)\{([^{}]*)\}/g, '$2'), 'around'); cm.focus(); },
    heading: v => heading(v),
    cite: () => o.citing.dialog(o.editor, null, null, false, async items => { const n = [{ type: 'citation', attrs: { items } }]; const keys = await keysFor(n); insert(latexCiteText({ items }, keys)); }),
  };

  const api_ = {
    /** Does the toolbar act on the LaTeX now (the LaTeX view, or side by side with the LaTeX last written in)? */
    latexActive: () => !!cm && (st.mode === 'latex' || (st.mode === 'split' && lastFocus === 'latex')),
    cmd: (name, arg) => { if (cm && COMMANDS[name]) COMMANDS[name](arg); },
    /** Puts the editor's content (a note being inserted, say) into the LaTeX at the cursor. */
    async insertPm(nodes, block) { const keys = await keysFor(nodes); insert(pmToTex(nodes, keys), block); },
    citeKeys: () => Object.assign({}, st.citeKeys),
    /** For the outline, in the LaTeX view: the lines, where the cursor is, going to a line, moving lines. */
    outline: () => (cm && st.mode === 'latex' ? {
      lines: cm.getValue().split('\n'), cursorLine: cm.getCursor().line,
      goto: line => { cm.focus(); cm.setCursor({ line, ch: 0 }); cm.scrollTo(null, cm.heightAtLine(line, 'local') - 20); },
      moveLines: (from, to, target) => {
        const text = cm.getRange({ line: from, ch: 0 }, { line: to, ch: 0 });
        cm.operation(() => {
          if (target > from) { cm.replaceRange(text, { line: target, ch: 0 }); cm.replaceRange('', { line: from, ch: 0 }, { line: to, ch: 0 }); }
          else { cm.replaceRange('', { line: from, ch: 0 }, { line: to, ch: 0 }); cm.replaceRange(text, { line: target, ch: 0 }); }
        });
        const at = target > from ? target - (to - from) : target;
        cm.setCursor({ line: at, ch: 0 }); cm.scrollIntoView({ line: at, ch: 0 }, 120);
      },
    } : null),
    get mode() { return st.mode; },
    toLatex: () => toLatex('latex'), toVisual, toSplit: () => toLatex('split'),
    toggle() {
      // One press is one switch (the key can reach both the LaTeX editor and the page).
      const now = Date.now(); if (busy || now - lastToggle < 600) return; lastToggle = now;
      return st.mode === 'visual' ? toLatex('latex') : toVisual();
    },
    toggleSplit() {
      const now = Date.now(); if (busy || now - lastToggle < 600) return; lastToggle = now;
      return st.mode === 'split' ? toLatex('latex') : toLatex('split');
    },
    /** The visual text changed (side by side: the LaTeX follows). */
    visualChanged() { if (st.mode !== 'split' || applying) return; pending = 'visual'; schedule(); },
    /** The LaTeX to typeset: what is written there, unless the visual side has changes the LaTeX has not followed yet. */
    text: () => (cm && (st.mode === 'latex' || (st.mode === 'split' && pending !== 'visual')) ? cm.getValue() : null),
    /** Opens the LaTeX view at a line (from an error in the PDF panel). */
    async gotoLine(n) {
      if (st.mode === 'visual') await toLatex('latex');
      if (!cm) return;
      const l = Math.max(0, n - 1);
      cm.focus(); cm.setCursor({ line: l, ch: 0 }); cm.scrollIntoView({ line: l, ch: 0 }, 160);
      cm.addLineClass(l, 'background', 'wtexhit'); setTimeout(() => cm && cm.removeLineClass(l, 'background', 'wtexhit'), 2500);
    },
    state: () => Object.assign({}, st),
    flush,
  };
  // Opened in a LaTeX layout last time: open there again, with the text as it was.
  if ((st.mode === 'latex' || st.mode === 'split') && st.src) {
    const want = st.mode;
    st.mode = 'visual';
    setTimeout(async () => {
      await makeEditor();
      const keep = st.src;
      try { await freshTex(); } catch (_) {}
      st.mode = want; show(); setText(keep); cm.refresh(); preSay();
    }, 50);
  }
  return api_;
}
