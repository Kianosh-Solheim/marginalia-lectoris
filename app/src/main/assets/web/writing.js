/* Marginalia Lectoris — the writing program (web).
 *
 * Documents live on the tablet in Readmark/Writing/<Title>/ (document.json is the master copy, in
 * the editor's own form; versions/ holds snapshots; media/ the pictures; a fresh <Title>.docx is
 * written beside it now and then and when you leave). The editor is TipTap (ProseMirror). Import and
 * export go through Pandoc (run in this browser; downloaded once to the tablet) via Pandoc's own
 * document tree, so footnotes, tables and pictures survive the trip.
 */

// ------------------------------------------------------------------ loading the editor and Pandoc

const loadedScripts = {};
function loadScript(src) {
  return loadedScripts[src] || (loadedScripts[src] = new Promise((ok, bad) => {
    const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = () => { delete loadedScripts[src]; bad(new Error('Could not load ' + src)); };
    document.head.append(s);
  }));
}
const ensureTiptap = () => loadScript('vendor/tiptap.bundle.js').then(() => window.Tiptap);

let pandocInstance = null, pandocLoading = null, pandocApiVersion = null;
/** Makes sure an engine (Pandoc, Typst) is on the tablet; offers the one-time download if it is not. */
async function ensureEngine(kind, name, what, why) {
  let st = await api(`writing/${kind}-status`);
  if (st.installed) return;
  const go = await new Promise(res => {
    let answered = false;
    modal((box, close) => box.append(h('h2', null, `Download ${name}?`),
      h('p', null, `${why ? why + ' ' : ''}${what} It is downloaded once and kept on the tablet.`),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn fill', onclick: () => { answered = true; res(true); close(); } }, 'Download'))), () => { if (!answered) res(false); });
  });
  if (!go) throw new Error(name + ' is needed for this.');
  st = await api(`writing/${kind}-install`, { method: 'POST' });
  toast(`Downloading ${name}…`, 0);
  while (!st.installed) {
    if (st.error) { toast(''); throw new Error(`${name} download: ` + st.error); }
    await new Promise(r => setTimeout(r, 1200));
    st = await api(`writing/${kind}-status`);
    if (st.total > 0) toast(`Downloading ${name}… ${Math.round(st.done / 1048576)} of ${Math.round(st.total / 1048576)} MB`, 0);
    if (!st.running && !st.installed && !st.error) st.error = 'stopped';
  }
  toast(name + ' is ready', 1500);
}
/** Pandoc, ready to convert. If it is not on the tablet yet, offers to download it (once). */
async function ensurePandoc(why) {
  if (pandocInstance) return pandocInstance;
  if (pandocLoading) return pandocLoading;
  await ensureEngine('pandoc', 'Pandoc', 'This needs Pandoc, the document converter (about 15 MB to download, 58 MB on the tablet, from the official pandoc-wasm package).', why);
  pandocLoading = (async () => {
    await loadScript('vendor/pandoc.bundle.js');
    const r = await fetch('/api/writing/pandoc.wasm', { credentials: 'same-origin' });
    if (!r.ok) throw new Error('Could not load Pandoc (' + r.status + ')');
    const inst = await PandocWasm.createPandocInstance(await r.arrayBuffer());
    // Which version of Pandoc's document tree it speaks.
    try { const t = await inst.convert({ from: 'markdown', to: 'json' }, 'x', {}); pandocApiVersion = JSON.parse(t.stdout)['pandoc-api-version']; } catch (_) { pandocApiVersion = [1, 23, 1]; }
    pandocInstance = inst;
    return inst;
  })();
  try { return await pandocLoading; } finally { pandocLoading = null; }
}

// ------------------------------------------------------------------ editor ⇄ Pandoc's document tree

const P = {
  attr: () => ['', [], []],
  str: t => ({ t: 'Str', c: t }),
};
const MARK_ORDER = ['link', 'bold', 'italic', 'underline', 'strike', 'superscript', 'subscript', 'code'];
const MARK_T = { bold: 'Strong', italic: 'Emph', underline: 'Underline', strike: 'Strikeout', superscript: 'Superscript', subscript: 'Subscript' };

function textToInlines(text) {
  const out = [];
  for (const part of text.split(/(\s+)/)) {
    if (!part) continue;
    if (/^\s+$/.test(part)) out.push({ t: 'Space' }); else out.push(P.str(part));
  }
  return out;
}
/** Joins neighbours with the same formatting (Strong[a] Strong[b] → Strong[a b]). */
function mergeInlines(list) {
  const out = [];
  for (const x of list) {
    const prev = out[out.length - 1];
    if (prev && prev.t === x.t && ['Strong', 'Emph', 'Underline', 'Strikeout', 'Superscript', 'Subscript'].includes(x.t)) { prev.c = mergeInlines(prev.c.concat(x.c)); continue; }
    if (prev && prev.t === 'Link' && x.t === 'Link' && prev.c[2][0] === x.c[2][0]) { prev.c[1] = mergeInlines(prev.c[1].concat(x.c[1])); continue; }
    out.push(x);
  }
  return out;
}
function pmInlinesToPandoc(nodes, ctx) {
  const out = [];
  for (const n of nodes || []) {
    if (n.type === 'text') {
      const marks = (n.marks || []).slice().sort((a, b) => MARK_ORDER.indexOf(a.type) - MARK_ORDER.indexOf(b.type));
      let inl = marks.some(m => m.type === 'code') ? [{ t: 'Code', c: [P.attr(), n.text] }] : textToInlines(n.text);
      for (let i = marks.length - 1; i >= 0; i--) {
        const m = marks[i];
        if (m.type === 'code') continue;
        if (m.type === 'link') inl = [{ t: 'Link', c: [P.attr(), inl, [m.attrs.href || '', m.attrs.title || '']] }];
        else if (MARK_T[m.type]) inl = [{ t: MARK_T[m.type], c: inl }];
      }
      const cx = ctx && ctx.cm;
      if (cx) (cx.first.get(n) || []).forEach(id => out.push(...commentStartInlines(id, cx, ctx.format)));
      out.push(...inl);
      if (cx) (cx.last.get(n) || []).forEach(id => out.push(...commentEndInlines(id, cx, ctx.format)));
    } else if (n.type === 'hardBreak') out.push({ t: 'LineBreak' });
    else if (n.type === 'image') out.push(imageToPandoc(n, ctx));
    else if (n.type === 'footnote') out.push({ t: 'Note', c: pmBlocksToPandoc((n.attrs.content && n.attrs.content.content) || [], Object.assign({}, ctx, { inNote: true })) });
    else if (n.type === 'citation' && ctx && ctx.citing) out.push(...(ctx.citeKeys ? citeKeyInlines(n.attrs, ctx) : ctx.citing.citationInlines(n.attrs, ctx.format, !!ctx.inNote)));
    else if (n.type === 'math') out.push({ t: 'Math', c: [{ t: n.attrs.display ? 'DisplayMath' : 'InlineMath' }, n.attrs.tex || ''] });
  }
  return mergeInlines(out);
}
function imageToPandoc(n, ctx) {
  const src = ctx && ctx.mapSrc ? ctx.mapSrc(n.attrs.src) : n.attrs.src;
  return { t: 'Image', c: [P.attr(), textToInlines(n.attrs.alt || ''), [src || '', n.attrs.title || '']] };
}
function pmBlocksToPandoc(nodes, ctx, tight) {
  const out = [];
  for (const n of nodes || []) {
    switch (n.type) {
      case 'paragraph': out.push({ t: tight ? 'Plain' : 'Para', c: pmInlinesToPandoc(n.content, ctx) }); break;
      case 'heading': out.push({ t: 'Header', c: [n.attrs.level || 1, P.attr(), pmInlinesToPandoc(n.content, ctx)] }); break;
      case 'blockquote': out.push({ t: 'BlockQuote', c: pmBlocksToPandoc(n.content, ctx) }); break;
      case 'bulletList': out.push({ t: 'BulletList', c: (n.content || []).map(li => pmBlocksToPandoc(li.content, ctx, (li.content || []).length === 1)) }); break;
      case 'orderedList': out.push({ t: 'OrderedList', c: [[n.attrs.start || 1, { t: 'Decimal' }, { t: 'Period' }], (n.content || []).map(li => pmBlocksToPandoc(li.content, ctx, (li.content || []).length === 1))] }); break;
      case 'codeBlock': out.push({ t: 'CodeBlock', c: [['', n.attrs.language ? [n.attrs.language] : [], []], (n.content || []).map(t => t.text).join('')] }); break;
      case 'horizontalRule': out.push({ t: 'HorizontalRule' }); break;
      case 'image': out.push({ t: 'Para', c: [imageToPandoc(n, ctx)] }); break;
      case 'table': out.push(tableToPandoc(n, ctx)); break;
      case 'bibliography': if (ctx && ctx.citeKeys) out.push({ t: 'Div', c: [['refs', [], []], []] }); else if (ctx && ctx.citing) out.push(...ctx.citing.bibliographyBlocks(ctx.format)); break;
      default: if (n.content) out.push(...pmBlocksToPandoc(n.content, ctx));
    }
  }
  return out;
}
function tableToPandoc(n, ctx) {
  const rows = n.content || [];
  const width = Math.max(1, ...rows.map(r => (r.content || []).reduce((a, c) => a + ((c.attrs && c.attrs.colspan) || 1), 0)));
  const cell = c => [P.attr(), { t: 'AlignDefault' }, (c.attrs && c.attrs.rowspan) || 1, (c.attrs && c.attrs.colspan) || 1, pmBlocksToPandoc(c.content, ctx, (c.content || []).length === 1)];
  const row = r => [P.attr(), (r.content || []).map(cell)];
  const isHead = r => (r.content || []).length && r.content.every(c => c.type === 'tableHeader');
  const head = rows.length && isHead(rows[0]) ? [rows[0]] : [];
  const body = rows.slice(head.length);
  return { t: 'Table', c: [P.attr(), [null, []], Array.from({ length: width }, () => [{ t: 'AlignDefault' }, { t: 'ColWidthDefault' }]),
    [P.attr(), head.map(row)], [[P.attr(), 0, [], body.map(row)]], [P.attr(), []]] };
}
function pmToPandoc(doc, title, ctx) {
  const meta = {};
  if (title) meta.title = { t: 'MetaInlines', c: textToInlines(title) };
  return { 'pandoc-api-version': pandocApiVersion || [1, 23, 1], meta, blocks: pmBlocksToPandoc(doc.content || [], ctx) };
}

// Pandoc → editor
function plainText(inls) {
  let s = '';
  for (const x of inls || []) {
    if (x.t === 'Str') s += x.c; else if (x.t === 'Space' || x.t === 'SoftBreak') s += ' ';
    else if (x.t === 'Code' || x.t === 'Math') s += x.c[1];
    else if (Array.isArray(x.c)) s += plainText(x.t === 'Link' || x.t === 'Image' ? x.c[1] : x.t === 'Span' || x.t === 'Quoted' || x.t === 'Cite' ? x.c[1] : x.c);
  }
  return s;
}
function pandocInlinesToPm(inls, marks, ctx) {
  const out = [];
  const pushText = (text, m) => {
    if (!text) return;
    if (ctx && ctx.cm) ctx.cm.justStarted = null;
    if (ctx && ctx.cm && ctx.cm.open.length) m = m.concat(ctx.cm.open.map(id => ({ type: 'comment', attrs: { id } })));
    const prev = out[out.length - 1];
    if (prev && prev.type === 'text' && JSON.stringify(prev.marks || []) === JSON.stringify(m)) prev.text += text;
    else out.push(m.length ? { type: 'text', text, marks: m } : { type: 'text', text });
  };
  const withMark = (m, mark) => m.some(x => x.type === mark.type) ? m : m.concat([mark]);
  for (const x of inls || []) {
    switch (x.t) {
      case 'Str': pushText(x.c, marks); break;
      case 'Space': case 'SoftBreak': pushText(' ', marks); break;
      case 'LineBreak': out.push({ type: 'hardBreak' }); break;
      case 'Emph': out.push(...pandocInlinesToPm(x.c, withMark(marks, { type: 'italic' }), ctx)); break;
      case 'Strong': out.push(...pandocInlinesToPm(x.c, withMark(marks, { type: 'bold' }), ctx)); break;
      case 'Underline': out.push(...pandocInlinesToPm(x.c, withMark(marks, { type: 'underline' }), ctx)); break;
      case 'Strikeout': out.push(...pandocInlinesToPm(x.c, withMark(marks, { type: 'strike' }), ctx)); break;
      case 'Superscript': out.push(...pandocInlinesToPm(x.c, withMark(marks, { type: 'superscript' }), ctx)); break;
      case 'Subscript': out.push(...pandocInlinesToPm(x.c, withMark(marks, { type: 'subscript' }), ctx)); break;
      case 'Span': {
        // Word comments and tracked changes (the changes are taken as accepted).
        const cls = x.c[0][1] || [], kv = Object.fromEntries(x.c[0][2] || []);
        if (cls.includes('comment-start')) {
          if (ctx && ctx.cm) {
            const id = newCommentId(); let text = plainText(x.c[1]).trim();
            const resolved = text.startsWith(RESOLVED_TAG.trim()); if (resolved) text = text.slice(RESOLVED_TAG.trim().length).trim();
            const entry = { author: kv.author || '', date: Date.parse(kv.date) || Date.now(), text };
            // A comment that starts on the very same spot as the one before it is a reply to it.
            if (ctx.cm.justStarted && ctx.cm.data[ctx.cm.justStarted]) { ctx.cm.ids[kv.id] = ctx.cm.justStarted; ctx.cm.data[ctx.cm.justStarted].replies.push(entry); break; }
            ctx.cm.ids[kv.id] = id; ctx.cm.open.push(id); ctx.cm.justStarted = id;
            ctx.cm.data[id] = Object.assign(entry, { resolved, replies: [] });
          }
          break;
        }
        if (cls.includes('comment-end')) { if (ctx && ctx.cm) ctx.cm.open = ctx.cm.open.filter(i => i !== ctx.cm.ids[kv.id]); break; }
        if (cls.includes('deletion')) break;
        out.push(...pandocInlinesToPm(x.c[1], marks, ctx)); break;
      }
      case 'SmallCaps': out.push(...pandocInlinesToPm(x.c, marks, ctx)); break;
      case 'Quoted': { const dq = x.c[0].t === 'DoubleQuote'; pushText(dq ? '“' : '‘', marks); out.push(...pandocInlinesToPm(x.c[1], marks, ctx)); pushText(dq ? '”' : '’', marks); break; }
      case 'Cite': if (ctx && ctx.keys && (x.c[0] || []).length) { out.push(citeToPm(x)); if (ctx.cm) ctx.cm.justStarted = null; } else out.push(...pandocInlinesToPm(x.c[1], marks, ctx)); break;
      case 'Code': pushText(x.c[1], withMark(marks, { type: 'code' })); break;
      case 'Math': out.push({ type: 'math', attrs: { tex: x.c[1], display: x.c[0].t === 'DisplayMath' } }); break;
      case 'Link': out.push(...pandocInlinesToPm(x.c[1], withMark(marks, { type: 'link', attrs: { href: x.c[2][0], target: '_blank' } }), ctx)); break;
      case 'Image': out.push({ type: 'image', attrs: { src: ctx && ctx.mapSrc ? ctx.mapSrc(x.c[2][0]) : x.c[2][0], alt: plainText(x.c[1]), title: x.c[2][1] || null } }); break;
      case 'Note': out.push({ type: 'footnote', attrs: { content: { type: 'doc', content: ensureBlocks(pandocBlocksToPm(x.c, ctx)) } } }); break;
      default: break;   // RawInline and the like
    }
  }
  // The text of a footnote cannot hold another footnote.
  return out;
}
const ensureBlocks = b => b.length ? b : [{ type: 'paragraph' }];
const para = (inls, ctx) => { const c = pandocInlinesToPm(inls, [], ctx); return c.length ? { type: 'paragraph', content: c } : { type: 'paragraph' }; };
function listItem(blocks, ctx) {
  const c = pandocBlocksToPm(blocks, ctx);
  if (!c.length || c[0].type !== 'paragraph') c.unshift({ type: 'paragraph' });
  return { type: 'listItem', content: c };
}
function pandocBlocksToPm(blocks, ctx) {
  const out = [];
  for (const b of blocks || []) {
    switch (b.t) {
      case 'Para': case 'Plain': out.push(para(b.c, ctx)); break;
      case 'Header': { const c = pandocInlinesToPm(b.c[2], [], ctx); out.push(c.length ? { type: 'heading', attrs: { level: Math.min(6, b.c[0]) }, content: c } : { type: 'heading', attrs: { level: Math.min(6, b.c[0]) } }); break; }
      case 'BlockQuote': out.push({ type: 'blockquote', content: ensureBlocks(pandocBlocksToPm(b.c, ctx)) }); break;
      case 'BulletList': out.push({ type: 'bulletList', content: b.c.map(item => listItem(item, ctx)) }); break;
      case 'OrderedList': out.push({ type: 'orderedList', attrs: { start: b.c[0][0] || 1 }, content: b.c[1].map(item => listItem(item, ctx)) }); break;
      case 'CodeBlock': out.push(b.c[1] ? { type: 'codeBlock', attrs: { language: b.c[0][1][0] || null }, content: [{ type: 'text', text: b.c[1] }] } : { type: 'codeBlock' }); break;
      case 'HorizontalRule': out.push({ type: 'horizontalRule' }); break;
      case 'LineBlock': { const c = []; b.c.forEach((line, i) => { if (i) c.push({ type: 'hardBreak' }); c.push(...pandocInlinesToPm(line, [], ctx)); }); out.push(c.length ? { type: 'paragraph', content: c } : { type: 'paragraph' }); break; }
      case 'DefinitionList': b.c.forEach(([term, defs]) => { out.push(para([{ t: 'Strong', c: term }], ctx)); defs.forEach(d => out.push(...pandocBlocksToPm(d, ctx))); }); break;
      case 'Div': out.push(...pandocBlocksToPm(b.c[1], ctx)); break;
      case 'Figure': { out.push(...pandocBlocksToPm(b.c[2], ctx)); const cap = b.c[1][1]; if (cap && cap.length) cap.forEach(cb => { if (cb.c) out.push(para([{ t: 'Emph', c: cb.t === 'Para' || cb.t === 'Plain' ? cb.c : [] }], ctx)); }); break; }
      case 'Table': out.push(tableToPm(b, ctx)); break;
      default: break;   // RawBlock and the like
    }
  }
  return out;
}
function tableToPm(b, ctx) {
  const [, , , thead, tbodies, tfoot] = b.c;
  const rows = [];
  const cellPm = (cell, head) => ({ type: head ? 'tableHeader' : 'tableCell', attrs: { colspan: cell[3] || 1, rowspan: cell[2] || 1 }, content: ensureBlocks(pandocBlocksToPm(cell[4], ctx)) });
  (thead[1] || []).forEach(r => rows.push({ type: 'tableRow', content: r[1].map(c => cellPm(c, true)) }));
  (tbodies || []).forEach(tb => { (tb[2] || []).concat(tb[3] || []).forEach(r => rows.push({ type: 'tableRow', content: r[1].map(c => cellPm(c, false)) })); });
  ((tfoot && tfoot[1]) || []).forEach(r => rows.push({ type: 'tableRow', content: r[1].map(c => cellPm(c, false)) }));
  return rows.length ? { type: 'table', content: rows.filter(r => r.content.length) } : { type: 'paragraph' };
}
function pandocToPm(ast, ctx) {
  return { type: 'doc', content: ensureBlocks(pandocBlocksToPm(ast.blocks, ctx)) };
}
function pandocTitle(ast) { const t = ast.meta && ast.meta.title; return t ? (t.t === 'MetaInlines' ? plainText(t.c) : t.t === 'MetaString' ? t.c : '') : ''; }

const IMPORT_FORMATS = { docx: 'docx', odt: 'odt', md: 'markdown', markdown: 'markdown', txt: 'markdown', html: 'html', htm: 'html', tex: 'latex', rtf: 'rtf', epub: 'epub' };

/**
 * Reads a DOCX/ODT/Markdown/HTML/LaTeX file into the editor's form. Pictures are sent to the
 * document's media folder on the tablet. Returns { content, title }.
 */
async function importFile(file, docId) {
  const ext = (file.name.split('.').pop() || '').toLowerCase();
  if (ext === 'doc') throw new Error('Old Word .doc files cannot be read here. Save it as .docx in Word or LibreOffice first.');
  const from = IMPORT_FORMATS[ext];
  if (!from) throw new Error('This kind of file cannot be imported: .' + ext);
  const pd = await ensurePandoc('Importing ' + file.name + '.');
  const name = 'input.' + ext;
  let r = null;
  // Word files: with their comments (tracked changes are taken as accepted).
  if (from === 'docx') { try { r = await pd.convert({ from, to: 'json', 'input-files': [name], 'extract-media': 'media', 'track-changes': 'all' }, null, { [name]: file }); } catch (_) { r = null; } }
  if (!r || !r.stdout) r = await pd.convert({ from, to: 'json', 'input-files': [name], 'extract-media': 'media' }, null, { [name]: file });
  if (!r.stdout) throw new Error('Pandoc could not read the file' + (r.stderr ? ': ' + r.stderr.slice(0, 300) : ''));
  const ast = JSON.parse(r.stdout);
  // Pictures: upload, and point to them on the tablet.
  const media = r.mediaFiles || {};
  const urls = {};
  for (const [path, blob] of Object.entries(media)) {
    const base = path.split('/').pop();
    try {
      const res = await fetch(`/api/writing/${encodeURIComponent(docId)}/media?name=${encodeURIComponent(base)}`, { method: 'POST', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/octet-stream' }, body: blob, credentials: 'same-origin' });
      if (res.ok) urls[path] = (await res.json()).url;
    } catch (_) {}
  }
  const mapSrc = src => {
    if (urls[src]) return urls[src];
    const hit = Object.keys(urls).find(k => k.endsWith('/' + src.replace(/^\.?\//, '')) || src.endsWith('/' + k) || k.split('/').pop() === src.split('/').pop());
    return hit ? urls[hit] : src;
  };
  const cm = { open: [], ids: {}, data: {} };
  // Citation keys (\cite{key}, [@key]) become live citations where the library or Zotero knows the key.
  const keyed = await resolveImportedKeys(pandocToPm(ast, { mapSrc, cm, keys: true }));
  return { content: keyed.content, title: pandocTitle(ast), comments: cm.data, sources: keyed.sources, unknown: keyed.unknown };
}

const WRITE_EXPORTS = [
  ['docx', 'Word (.docx)', 'docx', true],
  ['odt', 'LibreOffice (.odt)', 'odt', true],
  ['md', 'Markdown (.md)', 'markdown', false],
  ['html', 'Web page (.html)', 'html', false],
  ['tex', 'LaTeX (.tex)', 'latex', false],
  ['typ', 'Typst (.typ)', 'typst', false],
];
/** The document in another format, as a Blob. Pictures are sent along to Pandoc. */
async function exportBlob(doc, title, ext, docId, citing, comments, keyed, sink) {
  const [, , to, binary] = WRITE_EXPORTS.find(e => e[0] === ext);
  const pd = await ensurePandoc('Exporting to .' + ext + '.');
  const files = {}; const srcs = {};
  let k = 0;
  const collect = n => { if (n.type === 'image' && n.attrs && n.attrs.src) srcs[n.attrs.src] = true; (n.content || []).forEach(collect); if (n.type === 'footnote' && n.attrs.content) collect(n.attrs.content); };
  collect(doc);
  const map = {};
  for (const src of Object.keys(srcs)) {
    try {
      const r = await fetch(src, { credentials: 'same-origin' });
      if (!r.ok) continue;
      const blob = await r.blob();
      const extOf = (blob.type.split('/')[1] || 'png').replace('jpeg', 'jpg').replace('svg+xml', 'svg');
      const name = `media/img${++k}.${extOf}`;
      files[name] = blob; map[src] = name;
    } catch (_) {}
  }
  const cm = comments && (ext === 'docx' || ext === 'odt') && Object.keys(comments).length ? commentBounds(doc, comments) : null;
  // With citation keys (LaTeX, Markdown): Pandoc writes the citation commands and sets up the bibliography.
  let bibTitle = '';
  if (keyed && ext === 'tex') { const s = stripBibliography(doc); doc = s.doc; bibTitle = s.title; }
  const ast = pmToPandoc(doc, title, { mapSrc: src => map[src] || src, citing, format: ext, cm, citeKeys: keyed ? keyed.keys : null });
  const opts = { from: 'json', to, standalone: true };
  if (ext === 'typ') {
    ast.meta.papersize = { t: 'MetaString', c: 'a4' }; ast.meta.fontsize = { t: 'MetaString', c: '11pt' };
    const lang = ((citing && citing.lang) || '').slice(0, 2); if (lang) ast.meta.lang = { t: 'MetaString', c: lang };
  }
  if (sink) sink.files = files;
  if (keyed) {
    ast.meta.bibliography = { t: 'MetaString', c: keyed.bibName };
    if (bibTitle) ast.meta['biblio-title'] = { t: 'MetaInlines', c: textToInlines(bibTitle) };
    if (ext === 'tex') { opts['cite-method'] = keyed.method; if (keyed.method === 'biblatex') ast.meta.biblatexoptions = { t: 'MetaString', c: 'style=' + keyed.style }; else ast.meta['biblio-style'] = { t: 'MetaString', c: 'apalike' }; }
  }
  if (binary) opts['output-file'] = 'out.' + ext;
  const r = await pd.convert(opts, JSON.stringify(ast), files);
  if (binary) {
    const b = r.files && r.files['out.' + ext];
    if (!b) throw new Error('Pandoc could not write the file' + (r.stderr ? ': ' + r.stderr.slice(0, 300) : ''));
    let out = new Blob([b], { type: ext === 'docx' ? 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' : 'application/vnd.oasis.opendocument.text' });
    // Zotero's document preferences, so its plug-in in Word or LibreOffice takes the citations over.
    if (citing && JSON.stringify(doc).includes('"citation"')) { try { out = await citing.addZoteroPrefs(out, ext); } catch (e) { console.warn('Zotero preferences', e); } }
    return out;
  }
  return new Blob([r.stdout || ''], { type: ext === 'html' ? 'text/html' : 'text/plain' });
}
function saveBlob(blob, name) {
  const a = h('a', { href: URL.createObjectURL(blob), download: name });
  document.body.append(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
}
const safeFileName = t => (t || 'Untitled').replace(/[\\/:*?"<>|\u0000-\u001F]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 120) || 'Untitled';

// ------------------------------------------------------------------ the documents list

async function showWriting() {
  const app = clear(document.getElementById('app'));
  state.tab = 'writing';
  app.appendChild(header());
  const main = h('main', { class: 'writing-list' });
  app.appendChild(main);
  const list = h('div', { class: 'wdocs' }, h('div', { class: 'muted' }, 'Loading…'));
  const pandocLine = h('div', { class: 'muted mono', style: { fontSize: '12px' } });
  const fileIn = h('input', { type: 'file', accept: '.docx,.odt,.md,.markdown,.txt,.html,.htm,.tex,.rtf', multiple: true, style: { display: 'none' } });
  fileIn.addEventListener('change', async () => {
    const files = [...fileIn.files]; fileIn.value = '';
    for (const f of files) {
      try {
        const base = f.name.replace(/\.[^.]+$/, '');
        const created = await api('writing', { method: 'POST', json: { title: base } });
        toast('Importing ' + f.name + '…', 0);
        const r = await importFile(f, created.id);
        await api(`writing/${encodeURIComponent(created.id)}`, { method: 'PUT', json: { title: base, content: r.content, words: 0, settings: { comments: r.comments || {}, items: Object.fromEntries((r.sources || []).map(e => [e.id, e.csl])), uris: Object.fromEntries((r.sources || []).filter(e => e.uri).map(e => [e.id, e.uri])) } } });
        if ((r.unknown || []).length) toast(`${r.unknown.length} citation key${r.unknown.length === 1 ? '' : 's'} not found in the library or Zotero — left as text`, 5000);
        toast('Imported ' + f.name, 1500);
        if (files.length === 1) { location.hash = '#/write/' + encodeURIComponent(created.id); return; }
      } catch (e) { fail(e); }
    }
    draw();
  });
  main.append(
    h('div', { class: 'row', style: { alignItems: 'center', gap: '10px', flexWrap: 'wrap' } },
      h('h1', { style: { margin: 0, flex: 1 } }, 'Writing'),
      h('button', { class: 'btn fill', onclick: async () => {
        try { const d = await api('writing', { method: 'POST', json: { title: 'Untitled' } }); location.hash = '#/write/' + encodeURIComponent(d.id) + '?new=1'; } catch (e) { fail(e); }
      } }, '+ New document'),
      h('button', { class: 'btn', title: 'Word (.docx), LibreOffice (.odt), Markdown, HTML or LaTeX', onclick: () => fileIn.click() }, 'Import…'), fileIn),
    h('p', { class: 'muted', style: { fontSize: '13px' } }, 'Documents are kept on the tablet in Readmark/Writing/, with snapshots of earlier versions and a Word copy (.docx) written beside each one.'),
    pandocLine, list);
  api('writing/pandoc-status').then(st => {
    pandocLine.textContent = st.installed ? `Converter: Pandoc ${st.version} (on the tablet)` : 'Converter: not downloaded yet (needed for import and export; asked for when first used).';
  }).catch(() => {});
  const fmt = t => t ? new Date(t).toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
  async function draw() {
    let docs;
    try { docs = await api('writing'); } catch (e) { fail(e); return; }
    clear(list);
    if (!docs.length) { list.append(h('div', { class: 'empty' }, h('h2', null, 'No documents yet'), h('div', { class: 'muted' }, 'Start a new one, or import a Word, LibreOffice or Markdown file.'))); return; }
    docs.forEach(d => list.append(h('div', { class: 'wdoc' },
      h('a', { class: 'wt', href: '#/write/' + encodeURIComponent(d.id) }, d.title),
      h('span', { class: 'muted mono wm' }, `${d.words || 0} words · ${fmt(d.modified)}`),
      h('span', { class: 'wx' }, ...(d.exports || []).map(x => h('a', { class: 'chip', title: 'Download', href: `/api/writing/${encodeURIComponent(d.id)}/files/${encodeURIComponent(x)}` }, x.split('.').pop().toUpperCase()))),
      h('span', { class: 'wa' },
        h('button', { class: 'btn quiet', onclick: async () => {
          const t = prompt('New title', d.title); if (!t || t === d.title) return;
          try { await api(`writing/${encodeURIComponent(d.id)}/rename`, { method: 'POST', json: { title: t } }); draw(); } catch (e) { fail(e); }
        } }, 'Rename'),
        h('button', { class: 'btn quiet', onclick: () => confirmBox('Remove this document?', `“${d.title}” is moved to Readmark/_to_delete/Writing (nothing is erased).`, 'Move to _to_delete', async () => {
          try { await api(`writing/${encodeURIComponent(d.id)}`, { method: 'DELETE' }); draw(); } catch (e) { fail(e); }
        }) }, 'Delete')))));
  }
  draw();
}

// ------------------------------------------------------------------ the editor

/** A footnote: a small superscript number in the text; its own text (with formatting) is edited in a box. */
function footnoteExtension(T, onEdit) {
  return T.Node.create({
    name: 'footnote', group: 'inline', inline: true, atom: true, selectable: true,
    addAttributes() { return { content: { default: null, parseHTML: el => { try { return JSON.parse(el.getAttribute('data-content') || 'null'); } catch (_) { return null; } }, renderHTML: a => ({ 'data-content': JSON.stringify(a.content) }) } }; },
    parseHTML() { return [{ tag: 'sup[data-footnote]' }]; },
    renderHTML({ HTMLAttributes }) { return ['sup', T.mergeAttributes(HTMLAttributes, { 'data-footnote': '', class: 'fn' })]; },
    addNodeView() {
      return ({ node, getPos }) => {
        const dom = document.createElement('sup');
        dom.className = 'fn'; dom.contentEditable = 'false';
        dom.title = fnText(node.attrs.content).slice(0, 200) || 'Empty footnote — click to write it';
        dom.addEventListener('click', e => { e.preventDefault(); onEdit(getPos()); });
        return { dom, update: n => { if (n.type.name !== 'footnote') return false; dom.title = fnText(n.attrs.content).slice(0, 200); return true; } };
      };
    },
  });
}
function fnText(doc, citing) {
  let s = '';
  const walk = n => { if (!n) return; if (n.type === 'text') s += n.text; if (n.type === 'citation' && citing) s += citing.text(n.attrs); if (n.type === 'math') s += '$' + n.attrs.tex + '$'; if (n.type === 'paragraph' && s) s += ' '; (n.content || []).forEach(walk); };
  walk(doc);
  return s.trim();
}

function toolbarButton(label, title, run, isOn) {
  const b = h('button', { class: 'tb', title, onmousedown: e => e.preventDefault(), onclick: run }, label);
  b._on = isOn;
  return b;
}

async function showWriter(id, params) {
  const app = clear(document.getElementById('app'));
  state.writing = true;
  app.append(h('div', { class: 'muted', style: { padding: '40px' } }, 'Opening…'));
  let T, d;
  try { [T, d] = await Promise.all([ensureTiptap(), api('writing/' + encodeURIComponent(id))]); }
  catch (e) { clear(app).append(header(), h('main', null, h('div', { class: 'empty' }, h('h2', null, 'Could not open the document'), h('div', { class: 'muted' }, e.message)))); return; }
  if (!document.body.contains(app)) return;
  clear(app);
  let docId = d.id, baseModified = d.modified, dirty = false, saving = false, lastExportedJson = null, saveTimer = 0, exportTimer = 0, lastTyping = Date.now();
  let title = d.title || 'Untitled';
  // Citations: the style, the sources' data and the formatted text (see cite.js).
  let recomputeTimer = 0;
  const citing = new Citing(T, d.settings, () => { dirty = true; scheduleSave(); styleBtn.textContent = '❝ Style: ' + shortStyle(); recomputeSoon(); });
  const shortStyle = () => { const t = citing.styleLabel; return t.length > 28 ? t.slice(0, 26) + '…' : t; };
  const recomputeSoon = () => { clearTimeout(recomputeTimer); recomputeTimer = setTimeout(() => citing.recompute(editor).then(refresh), 300); };

  // ----- layout
  const titleIn = h('input', { class: 'wtitle', value: title, placeholder: 'Title', title: 'Title (also the file name)' });
  const status = h('span', { class: 'wstatus mono' }, 'Saved');
  const words = h('span', { class: 'wwords mono' });
  const bar = h('div', { class: 'wtoolbar' });
  const page = h('div', { class: 'wpage' });
  const fnList = h('div', { class: 'wfootnotes' });
  const banner = h('div', { class: 'wbanner', hidden: true });
  const exportMenu = h('div', { class: 'wmenu', hidden: true });
  const fileIn = h('input', { type: 'file', accept: '.docx,.odt,.md,.markdown,.txt,.html,.htm,.tex,.rtf', style: { display: 'none' } });
  const imgIn = h('input', { type: 'file', accept: 'image/*', multiple: true, style: { display: 'none' } });
  const cmCol = h('div', { class: 'wcmcol', hidden: true });
  const wbody = h('div', { class: 'wbody' }, h('div', { class: 'wscroll' }, h('div', { class: 'wsheetrow' }, h('div', { class: 'wsheet' }, page, fnList), cmCol)));
  let commenting = null;   // the comments (wcomments.js)
  let notesP = null;   // the notes panel (wnotes.js)
  app.append(
    h('div', { class: 'wtop' },
      h('a', { class: 'btn quiet', href: '#/writing', title: 'All documents' }, '← Writing'),
      titleIn, status, words,
      h('button', { class: 'btn quiet', title: 'Earlier versions of this document', onclick: () => showVersions() }, 'Versions'),
      h('button', { class: 'btn quiet', title: 'Keep the text as it is now as a named version', onclick: () => saveVersion() }, 'Save version'),
      h('button', { class: 'btn quiet', title: 'Replace the text with a Word, LibreOffice, Markdown, HTML or LaTeX file', onclick: () => fileIn.click() }, 'Import…'),
      h('span', { class: 'wmenuwrap' }, h('button', { class: 'btn', onclick: e => { e.stopPropagation(); exportMenu.hidden = !exportMenu.hidden; } }, 'Export ▾'), exportMenu),
      fileIn, imgIn),
    bar, banner,
    wbody);
  document.addEventListener('click', () => { exportMenu.hidden = true; });

  // ----- footnote editing
  function editFootnote(pos) {
    const node = editor.state.doc.nodeAt(pos);
    if (!node || node.type.name !== 'footnote') return;
    modal((box, close) => {
      const host = h('div', { class: 'wfnedit' });
      box.append(h('h2', null, 'Footnote'), host, h('div', { class: 'foot' },
        h('button', { class: 'btn quiet', title: 'Cite a source in the footnote', onclick: () => citing.insert(fe) }, '❝ Cite'),
        h('button', { class: 'btn quiet', onclick: () => { editor.chain().focus().setNodeSelection(pos).deleteSelection().run(); close(); } }, 'Remove footnote'),
        h('span', { style: { flex: 1 } }),
        h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn fill', onclick: () => { const c = fe.getJSON(); editor.chain().setNodeSelection(pos).updateAttributes('footnote', { content: c }).setTextSelection(pos + 1).focus().run(); close(); } }, 'Done')));
      const fe = new T.Editor({ element: host, extensions: [T.StarterKit.configure({ heading: false, codeBlock: false, horizontalRule: false, blockquote: false }), T.Underline, T.Link.configure({ openOnClick: false }), T.Superscript, T.Subscript, T.Placeholder.configure({ placeholder: 'Write the footnote…' }), mathExtension(T, p => mathDialog(fe, p)), ...citing.noteExtensions],
        content: node.attrs.content || { type: 'doc', content: [{ type: 'paragraph' }] } });
      setTimeout(() => fe.commands.focus('end'), 50);
    }, () => {});
  }

  // ----- the editor
  const uploadImage = async file => {
    const r = await fetch(`/api/writing/${encodeURIComponent(docId)}/media?name=${encodeURIComponent(file.name || 'image.png')}`, { method: 'POST', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/octet-stream' }, body: file, credentials: 'same-origin' });
    if (!r.ok) throw new Error('Could not store the picture');
    return (await r.json()).url;
  };
  const insertImages = async (files, pos) => {
    for (const f of files) {
      try { const url = await uploadImage(f); const ch = editor.chain().focus(); if (pos != null) ch.insertContentAt(pos, { type: 'image', attrs: { src: url, alt: f.name } }).run(); else ch.setImage({ src: url, alt: f.name }).run(); }
      catch (e) { fail(e); }
    }
  };
  const editor = new T.Editor({
    element: page,
    extensions: [
      T.StarterKit.configure({ heading: { levels: [1, 2, 3, 4, 5, 6] } }),
      T.Underline, T.Superscript, T.Subscript,
      T.Link.configure({ openOnClick: false, autolink: true }),
      T.Image.configure({ inline: true, allowBase64: false }),
      T.Table.configure({ resizable: false }), T.TableRow, T.TableHeader, T.TableCell,
      T.Placeholder.configure({ placeholder: 'Start writing…' }),
      T.CharacterCount,
      footnoteExtension(T, editFootnote),
      commentMarkExtension(T),
      mathExtension(T, pos => mathDialog(editor, pos)),
      ...citing.extensions,
    ],
    content: d.content,
    editorProps: {
      attributes: { class: 'wprose', spellcheck: 'true' },
      handlePaste: (view, e) => { const fs = [...(e.clipboardData && e.clipboardData.files || [])].filter(f => /^image\//.test(f.type)); if (!fs.length) return false; insertImages(fs); return true; },
      handleDrop: (view, e) => {
        if (notesP && notesP.handleDrop(view, e)) return true;
        const fs = [...(e.dataTransfer && e.dataTransfer.files || [])].filter(f => /^image\//.test(f.type));
        if (!fs.length) return false;
        const p = view.posAtCoords({ left: e.clientX, top: e.clientY });
        insertImages(fs, p ? p.pos : null); return true;
      },
    },
    onUpdate: () => { dirty = true; lastTyping = Date.now(); status.textContent = 'Editing…'; scheduleSave(); refresh(); recomputeSoon(); if (notesP) notesP.refresh(); },
    onSelectionUpdate: () => { refreshBar(); if (commenting) commenting.selectionChanged(); },
  });

  // ----- toolbar
  const chain = () => editor.chain().focus();
  const styleSel = h('select', { class: 'wstyle', title: 'Paragraph style' },
    [['p', 'Normal text'], ['h1', 'Heading 1'], ['h2', 'Heading 2'], ['h3', 'Heading 3'], ['h4', 'Heading 4'], ['quote', 'Quotation'], ['code', 'Code']].map(([v, l]) => h('option', { value: v }, l)));
  styleSel.addEventListener('change', () => {
    const v = styleSel.value, c = chain();
    if (editor.isActive('blockquote') && v !== 'quote') c.lift('blockquote');
    if (v === 'p') c.setParagraph(); else if (v[0] === 'h') c.setHeading({ level: +v[1] }); else if (v === 'quote') c.setParagraph().setBlockquote(); else if (v === 'code') c.setCodeBlock();
    c.run();
  });
  const tableMenu = h('div', { class: 'wmenu', hidden: true });
  const tableItems = [
    ['Insert table (3 × 3)', () => chain().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()],
    ['Add row below', () => chain().addRowAfter().run()], ['Add row above', () => chain().addRowBefore().run()],
    ['Add column right', () => chain().addColumnAfter().run()], ['Add column left', () => chain().addColumnBefore().run()],
    ['Delete row', () => chain().deleteRow().run()], ['Delete column', () => chain().deleteColumn().run()],
    ['Header row on/off', () => chain().toggleHeaderRow().run()], ['Merge or split cells', () => chain().mergeOrSplit().run()],
    ['Delete table', () => chain().deleteTable().run()],
  ];
  tableItems.forEach(([l, f]) => tableMenu.append(h('button', { onmousedown: e => e.preventDefault(), onclick: () => { tableMenu.hidden = true; f(); } }, l)));
  const styleBtn = h('button', { class: 'tb wstylebtn', title: 'Citation style and language', onmousedown: e => e.preventDefault(), onclick: () => citing.pickStyle() }, '❝ Style: …');
  const linksBtn = toolbarButton('⇄ Links → citations', 'Turn the Zotero links of an imported text (from Google Docs) into live citations', () => citing.convertLinks(editor));
  linksBtn.hidden = true;
  const btns = [
    toolbarButton('↶', 'Undo (Ctrl+Z)', () => chain().undo().run()),
    toolbarButton('↷', 'Redo (Ctrl+Y)', () => chain().redo().run()),
    styleSel,
    toolbarButton(h('b', null, 'B'), 'Bold (Ctrl+B)', () => chain().toggleBold().run(), () => editor.isActive('bold')),
    toolbarButton(h('i', null, 'I'), 'Italic (Ctrl+I)', () => chain().toggleItalic().run(), () => editor.isActive('italic')),
    toolbarButton(h('u', null, 'U'), 'Underline (Ctrl+U)', () => chain().toggleUnderline().run(), () => editor.isActive('underline')),
    toolbarButton(h('s', null, 'S'), 'Strikethrough', () => chain().toggleStrike().run(), () => editor.isActive('strike')),
    toolbarButton(h('span', null, 'x', h('sup', null, '2')), 'Superscript', () => chain().toggleSuperscript().run(), () => editor.isActive('superscript')),
    toolbarButton(h('span', null, 'x', h('sub', null, '2')), 'Subscript', () => chain().toggleSubscript().run(), () => editor.isActive('subscript')),
    toolbarButton('🔗', 'Link', () => {
      const prev = editor.getAttributes('link').href || '';
      const url = prompt('Link address (empty to remove)', prev);
      if (url === null) return;
      if (!url) chain().extendMarkRange('link').unsetLink().run(); else chain().extendMarkRange('link').setLink({ href: url }).run();
    }, () => editor.isActive('link')),
    toolbarButton('• List', 'Bulleted list', () => chain().toggleBulletList().run(), () => editor.isActive('bulletList')),
    toolbarButton('1. List', 'Numbered list', () => chain().toggleOrderedList().run(), () => editor.isActive('orderedList')),
    toolbarButton('⇤', 'Less indent (list)', () => chain().liftListItem('listItem').run()),
    toolbarButton('⇥', 'More indent (list)', () => chain().sinkListItem('listItem').run()),
    h('span', { class: 'wmenuwrap' }, toolbarButton('▦ Table ▾', 'Tables', e => { e.stopPropagation(); tableMenu.hidden = !tableMenu.hidden; }, () => editor.isActive('table')), tableMenu),
    toolbarButton('🖼 Picture', 'Insert a picture (or paste or drop one into the text)', () => imgIn.click()),
    toolbarButton('¹ Footnote', 'Insert a footnote at the cursor', () => {
      const pos = editor.state.selection.to;
      chain().insertContentAt(pos, { type: 'footnote', attrs: { content: { type: 'doc', content: [{ type: 'paragraph' }] } } }).setTextSelection(pos + 1).run();
      setTimeout(() => editFootnote(pos), 30);
    }),
    toolbarButton('∑ Formula', 'Insert a formula written as LaTeX (Ctrl+Alt+E)', () => mathDialog(editor, null)),
    toolbarButton('―', 'Horizontal line', () => chain().setHorizontalRule().run()),
    toolbarButton('❝ Cite', 'Insert a citation from your library or Zotero (Ctrl+Shift+C)', () => citing.insert(editor)),
    toolbarButton('💬 Comment', 'Comment on the selected words (Ctrl+Alt+M)', () => commenting.add()),
    linksBtn,
    toolbarButton('▤ Notes', 'Your notes beside the text: drag one in to quote or cite it (Ctrl+Alt+N)', () => notesP.toggle(), () => notesP && notesP.isOpen()),
    toolbarButton('Bibliography', 'Insert the bibliography here (it lists everything you cite)', () => citing.insertBibliography(editor)),
    styleBtn,
    toolbarButton('Tx', 'Clear formatting', () => chain().unsetAllMarks().clearNodes().run()),
  ];
  bar.append(...btns);
  commenting = new Commenting(editor, (d.settings || {}).comments, cmCol, () => { dirty = true; scheduleSave(); });
  notesP = notesPanel(editor, citing, () => { placeNotes(); refreshBar(); commenting.renderSoon(); });
  wbody.append(notesP.el);
  const placeNotes = () => { const t = (app.querySelector('.wtop') || {}).offsetHeight + bar.offsetHeight || 90; notesP.el.style.top = t + 'px'; notesP.el.style.height = `calc(100vh - ${t}px)`; };
  window.addEventListener('resize', placeNotes);
  if (localGet('ml.wnotesOpen', '')) notesP.open();
  document.addEventListener('click', () => { tableMenu.hidden = true; });
  function refreshBar() {
    btns.forEach(b => { if (b._on) b.classList.toggle('on', !!b._on()); });
    styleSel.value = editor.isActive('codeBlock') ? 'code' : editor.isActive('blockquote') ? 'quote' : [1, 2, 3, 4].find(l => editor.isActive('heading', { level: l })) ? 'h' + [1, 2, 3, 4].find(l => editor.isActive('heading', { level: l })) : 'p';
  }
  function refresh() {
    words.textContent = `${editor.storage.characterCount.words()} words`;
    // The footnotes, numbered, under the text.
    clear(fnList);
    const notes = [];
    editor.state.doc.descendants((n, pos) => { if (n.type.name === 'footnote' || (n.type.name === 'citation' && citing.isNote)) notes.push([n, pos]); });
    if (notes.length) fnList.append(h('div', { class: 'wfnhead' }, 'Footnotes'), ...notes.map(([n, pos], i) => n.type.name === 'citation'
      ? h('div', { class: 'wfn', onclick: () => citing.edit(editor, pos, n.attrs) }, h('span', { class: 'wfnno' }, String(i + 1)), h('span', { html: citing.html.get(n.attrs.citationID) || xmlEsc(citing.fallback(n.attrs)) }))
      : h('div', { class: 'wfn', onclick: () => editFootnote(pos) }, h('span', { class: 'wfnno' }, String(i + 1)), h('span', null, fnText(n.attrs.content, citing) || h('i', { class: 'muted' }, 'empty — click to write')))));
    commenting.renderSoon();
    const nLinks = zoteroLinkRanges(editor.state.doc).length;
    linksBtn.hidden = !nLinks; if (nLinks) linksBtn.textContent = `⇄ Links → citations (${nLinks})`;
    refreshBar();
  }
  refresh();
  citing.ready.then(() => { styleBtn.textContent = '❝ Style: ' + shortStyle(); return citing.recompute(editor); }).then(refresh).catch(e => { styleBtn.textContent = '❝ Style: (not loaded)'; console.warn(e); });

  imgIn.addEventListener('change', () => { const fs = [...imgIn.files]; imgIn.value = ''; insertImages(fs); });

  // ----- saving
  const payload = (extra = {}) => Object.assign({ title, content: editor.getJSON(), words: editor.storage.characterCount.words(), baseModified, settings: Object.assign({}, citing.settings(), { comments: commenting.settings() }) }, extra);
  async function save(extra) {
    if (saving) { scheduleSave(); return; }
    saving = true; clearTimeout(saveTimer);
    const wasDirty = dirty; dirty = false;
    try {
      const r = await fetch(`/api/writing/${encodeURIComponent(docId)}`, { method: 'PUT', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/json' }, body: JSON.stringify(payload(extra)), credentials: 'same-origin' });
      if (r.status === 401) { showLogin(); return; }
      if (r.status === 409) {
        dirty = true;
        banner.hidden = false; clear(banner).append('This document was changed elsewhere (another browser or tab) since it was opened here. ',
          h('button', { class: 'btn', onclick: () => route() }, 'Load theirs'), ' ',
          h('button', { class: 'btn', onclick: () => { banner.hidden = true; save(Object.assign({}, extra, { force: true })); } }, 'Keep mine'));
        status.textContent = 'Not saved'; return;
      }
      if (!r.ok) throw new Error('Could not save (' + r.status + ')');
      baseModified = (await r.json()).modified;
      status.textContent = dirty ? 'Editing…' : 'Saved';
    } catch (e) { dirty = dirty || wasDirty; status.textContent = 'Not saved — retrying'; setTimeout(scheduleSave, 4000); }
    finally { saving = false; }
  }
  function scheduleSave() { clearTimeout(saveTimer); saveTimer = setTimeout(() => save(), 1200); scheduleExport(); }

  // A Word copy beside the document: written when the writing pauses (not more than every two minutes).
  let lastExport = 0;
  function scheduleExport() {
    clearTimeout(exportTimer);
    exportTimer = setTimeout(() => writeDocx(false), Math.max(20000, 120000 - (Date.now() - lastExport)));
  }
  async function writeDocx(force) {
    if (!pandocInstance && !force) {
      // Only when Pandoc is already on the tablet (no download prompt while writing).
      try { const st = await api('writing/pandoc-status'); if (!st.installed) return; } catch (_) { return; }
    }
    if (Date.now() - lastTyping < 8000 && !force) { scheduleExport(); return; }
    const json = JSON.stringify(editor.getJSON()) + title;
    if (json === lastExportedJson && !force) return;
    try {
      const blob = await exportBlob(editor.getJSON(), title, 'docx', docId, citing, commenting.settings());
      const r = await fetch(`/api/writing/${encodeURIComponent(docId)}/export?name=${encodeURIComponent(safeFileName(title) + '.docx')}`, { method: 'POST', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/octet-stream' }, body: blob, credentials: 'same-origin' });
      if (r.ok) { lastExportedJson = json; lastExport = Date.now(); }
    } catch (e) { console.warn('Word copy', e); }
  }
  titleIn.addEventListener('input', () => { title = titleIn.value.trim() || 'Untitled'; dirty = true; scheduleSave(); });
  titleIn.addEventListener('change', async () => {
    // The folder follows the title.
    try { await save(); const r = await api(`writing/${encodeURIComponent(docId)}/rename`, { method: 'POST', json: { title } }); if (r.id && r.id !== docId) { docId = r.id; baseModified = r.modified; history.replaceState(null, '', '#/write/' + encodeURIComponent(docId)); } } catch (e) { fail(e); }
  });
  const onKey = e => {
    if ((e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); save().then(() => writeDocx(true)); toast('Saved', 1000); }
    if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'C' || e.key === 'c')) { e.preventDefault(); citing.insert(editor); }
    if (e.ctrlKey && e.altKey && (e.key === 'e' || e.key === 'E' || e.code === 'KeyE')) { e.preventDefault(); mathDialog(editor, null); }
    if (e.ctrlKey && e.altKey && (e.key === 'm' || e.key === 'M' || e.code === 'KeyM')) { e.preventDefault(); commenting.add(); }
    if (e.ctrlKey && e.altKey && (e.key === 'n' || e.key === 'N' || e.code === 'KeyN')) { e.preventDefault(); notesP.toggle(); }
  };
  document.addEventListener('keydown', onKey);
  const onHide = () => { if (document.visibilityState === 'hidden' && dirty) save(); };
  document.addEventListener('visibilitychange', onHide);
  const onUnload = e => { if (dirty) { save(); e.preventDefault(); e.returnValue = ''; } };
  window.addEventListener('beforeunload', onUnload);
  window.writingLeave = () => {
    document.removeEventListener('keydown', onKey); document.removeEventListener('visibilitychange', onHide); window.removeEventListener('beforeunload', onUnload); window.removeEventListener('resize', placeNotes); commenting.destroy();
    clearTimeout(saveTimer); clearTimeout(exportTimer);
    const p = dirty ? save() : Promise.resolve();
    p.then(() => writeDocx(false)).finally(() => editor.destroy());
  };

  // ----- versions
  async function saveVersion() {
    const label = prompt('Name of this version (optional)', '');
    if (label === null) return;
    dirty = true; await save({ snapshotNew: true, label: label || 'saved' });
    toast('Version saved', 1200);
  }
  async function showVersions() {
    let vs;
    try { await save(); vs = await api(`writing/${encodeURIComponent(docId)}/versions`); } catch (e) { fail(e); return; }
    modal((box, close) => {
      const preview = h('div', { class: 'wverprev' }, h('div', { class: 'muted' }, 'Choose a version to see it.'));
      let viewer = null, chosen = null;
      const list = h('div', { class: 'wverlist' });
      if (!vs.length) list.append(h('div', { class: 'muted' }, 'No earlier versions yet. A snapshot is kept every ten minutes while you write, and when you press “Save version”.'));
      vs.forEach(v => list.append(h('button', { class: 'wver', onclick: async e => {
        list.querySelectorAll('.wver').forEach(b => b.classList.remove('on')); e.currentTarget.classList.add('on');
        try {
          chosen = await api(`writing/${encodeURIComponent(docId)}/versions/${encodeURIComponent(v.name)}`);
          if (viewer) viewer.destroy();
          clear(preview);
          viewer = new T.Editor({ element: preview, editable: false, extensions: [T.StarterKit, T.Underline, T.Superscript, T.Subscript, T.Link, T.Image.configure({ inline: true }), T.Table, T.TableRow, T.TableHeader, T.TableCell, footnoteExtension(T, () => {}), commentMarkExtension(T), mathExtension(T, () => {}), ...new Citing(T, citing.settings()).extensions], content: chosen.content });
          restore.disabled = false;
        } catch (er) { fail(er); }
      } }, h('b', null, new Date(v.time).toLocaleString()), v.label ? h('span', null, ' · ' + v.label) : null)));
      const restore = h('button', { class: 'btn fill', disabled: true, onclick: async () => {
        if (!chosen) return;
        dirty = true; await save({ snapshot: true, label: 'before restoring' });
        editor.commands.setContent(chosen.content, true);
        dirty = true; await save();
        close(); toast('Version restored (the text before it was kept as a version)', 2500);
      } }, 'Restore this version');
      box.classList.add('wide');
      box.append(h('h2', null, 'Versions'), h('div', { class: 'wvers' }, list, preview), h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Close'), restore));
    }, () => {});
  }

  // ----- import into this document
  fileIn.addEventListener('change', async () => {
    const f = fileIn.files[0]; fileIn.value = '';
    if (!f) return;
    confirmBox('Replace the text?', `The text is replaced by “${f.name}”. The text as it is now is kept as a version first.`, 'Import', async () => {
      try {
        dirty = true; await save({ snapshot: true, label: 'before import' });
        toast('Importing ' + f.name + '…', 0);
        const r = await importFile(f, docId);
        editor.commands.setContent(r.content, true);
        commenting.replaceAll(r.comments || {});
        (r.sources || []).forEach(e => { citing.items[e.id] = e.csl; if (e.uri) citing.uris[e.id] = e.uri; });
        if ((r.sources || []).length) { citing.ensureBibliography(editor); citing.recompute(editor); }
        if (r.title && (title === 'Untitled' || !title)) { title = r.title; titleIn.value = title; }
        dirty = true; await save();
        toast((r.unknown || []).length ? `Imported. ${r.unknown.length} citation key${r.unknown.length === 1 ? '' : 's'} not found in the library or Zotero (left as text): ${r.unknown.slice(0, 6).join(', ')}` : 'Imported', (r.unknown || []).length ? 7000 : 1500);
      } catch (e) { fail(e); }
    });
  });

  // ----- export
  const putOnTablet = (blob, name) => fetch(`/api/writing/${encodeURIComponent(docId)}/export?name=${encodeURIComponent(name)}`, { method: 'POST', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/octet-stream' }, body: blob, credentials: 'same-origin' }).catch(() => {});
  async function runExport(ext, label, keyed) {
    try {
      await save();
      toast(`Writing ${label}…`, 0);
      const name = safeFileName(title) + '.' + ext;
      if (ext === 'pdf') {
        // PDF: Pandoc writes Typst, and Typst sets the pages.
        const sink = {};
        const src = await (await exportBlob(editor.getJSON(), title, 'typ', docId, citing, commenting.settings(), null, sink)).text();
        toast('Setting the pages…', 0);
        const pdf = await typstPdf(src, sink.files || {});
        saveBlob(pdf, name); putOnTablet(pdf, name);
      } else if (ext === 'zip') {
        // An Overleaf project: main.tex, the .bib file and the pictures in one zip (New Project ▸ Upload Project).
        const sink = {};
        const tex = await exportBlob(editor.getJSON(), title, 'tex', docId, citing, commenting.settings(), keyed, sink);
        const zip = new JSZip();
        zip.file('main.tex', await tex.text());
        for (const [n, b] of Object.entries(sink.files || {})) zip.file(n, await b.arrayBuffer());
        if (keyed) zip.file(keyed.bibName, await bibFile(citing, keyed.keys, keyed.method === 'natbib'));
        const out = await zip.generateAsync({ type: 'blob', compression: 'DEFLATE' });
        saveBlob(out, name); putOnTablet(out, name);
        toast('', 1); return;
      } else if (ext !== 'bib') {
        const blob = await exportBlob(editor.getJSON(), title, ext, docId, citing, commenting.settings(), keyed);
        saveBlob(blob, name); putOnTablet(blob, name);   // a copy beside the document on the tablet too
      }
      if (keyed) {
        const bib = new Blob([await bibFile(citing, keyed.keys, keyed.method === 'natbib')], { type: 'text/plain' });
        setTimeout(() => saveBlob(bib, keyed.bibName), 400); putOnTablet(bib, keyed.bibName);
      }
      toast('', 1);
    } catch (e) { fail(e); }
  }
  /** LaTeX, Markdown and .bib: how the citations go out, and what the citation keys are. */
  async function keyedExport(ext, label) {
    const doc = editor.getJSON();
    let info;
    try { info = await resolveCiteKeys(citing, doc); } catch (e) { fail(e); return; }
    const n = Object.keys(info.keys).length;
    if (!n && ext !== 'bib') { runExport(ext, label, null); return; }
    const tex = ext === 'tex' || ext === 'zip';
    if (!n) { toast('Nothing is cited in this text yet'); return; }
    const bibName = ext === 'zip' ? 'references.bib' : safeFileName(title).replace(/\s+/g, '-') + '.bib';
    modal((box, close) => {
      const last = localGet('ml.wciteMethod.' + ext, ext === 'md' ? 'keys' : 'biblatex');
      const choices = tex ? [['biblatex', 'biblatex — \\autocite{key}', 'With \\printbibliography; compile with biber.'], ['natbib', 'natbib — \\citep{key}', 'With \\bibliography{…}; compile with bibtex.'], ['text', 'Formatted text', 'The citations as they look here, no keys.']]
        : ext === 'md' ? [['keys', 'Citation keys — [@key, p. 275]', 'For Pandoc, Quarto, Obsidian and Zettlr, with the .bib file.'], ['text', 'Formatted text', 'The citations as they look here, no keys.']] : [['biblatex', 'BibLaTeX', 'For biblatex and biber.'], ['natbib', 'BibTeX', 'For natbib and bibtex.']];
      let method = choices.some(c => c[0] === last) ? last : choices[0][0];
      const list = h('div', { class: 'wnmodes' });
      const style = h('input', { value: localGet('ml.wbiblatexStyle', /apa|american psychological/i.test(citing.styleLabel) ? 'apa' : citing.isNote ? 'verbose' : 'authoryear'), style: { width: '160px' } });
      const styleRow = h('label', { class: 'row', style: { gap: '6px', fontSize: '13px', alignItems: 'center', marginBottom: '8px' } }, 'biblatex style', style, h('span', { class: 'muted' }, 'e.g. apa, authoryear, verbose, chicago-authordate'));
      const draw = () => {
        clear(list); choices.forEach(([k, l, d]) => list.append(h('button', { class: 'wnmode' + (k === method ? ' on' : ''), onclick: () => { method = k; draw(); } }, h('b', null, l), h('div', { class: 'muted' }, d))));
        styleRow.hidden = !(tex && method === 'biblatex');
        notes.hidden = method === 'text';
      };
      const notes = h('div', { class: 'wkeynotes' });
      notes.append(h('div', { class: 'muted', style: { fontSize: '13px' } }, `${n} source${n === 1 ? '' : 's'} cited. The keys are the citation keys in Marginalia; the file ${bibName} goes with it.` + (ext === 'zip' ? ' In Overleaf: New Project ▸ Upload Project, and choose the zip.' : '')));
      if (info.differ.length) notes.append(h('div', { class: 'wkeywarn' }, h('b', null, `${info.differ.length} key${info.differ.length === 1 ? ' differs' : 's differ'} from Zotero’s`), ' — Marginalia’s is used:',
        h('table', null, h('tr', null, h('th', null, 'Source'), h('th', null, 'Marginalia'), h('th', null, 'Zotero')), info.differ.map(d => h('tr', null, h('td', null, d.label), h('td', { class: 'mono' }, d.mine), h('td', { class: 'mono' }, d.zotero))))));
      else notes.append(h('div', { class: 'muted', style: { fontSize: '13px' } }, '✓ No key differs from the one in Zotero.'));
      if (info.made.length) notes.append(h('div', { class: 'wkeywarn' }, h('b', null, `${info.made.length} without a key`), ' — one was made from author and year:',
        h('table', null, info.made.map(d => h('tr', null, h('td', null, d.label), h('td', { class: 'mono' }, d.key))))));
      box.classList.add('wide');
      box.append(h('h2', null, label), ext === 'bib' ? null : h('div', { class: 'label' }, 'Citations'), list, styleRow, notes,
        h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: () => {
          localSet('ml.wciteMethod.' + ext, method); if (tex && method === 'biblatex') localSet('ml.wbiblatexStyle', style.value.trim() || 'authoryear');
          close();
          runExport(ext, label, method === 'text' ? null : { keys: info.keys, method: method === 'keys' ? 'biblatex' : method, style: style.value.trim() || 'authoryear', bibName });
        } }, 'Export')));
      draw();
    });
  }
  [['pdf', 'PDF (.pdf)']].concat(WRITE_EXPORTS, [['zip', 'Overleaf project (.zip)'], ['bib', 'Bibliography file (.bib)']]).forEach(([ext, label]) => exportMenu.append(h('button', { onclick: () => {
    exportMenu.hidden = true;
    if (ext === 'tex' || ext === 'md' || ext === 'bib' || ext === 'zip') keyedExport(ext, label); else runExport(ext, label, null);
  } }, label)));

  if (params && params.get('new')) { titleIn.focus(); titleIn.select(); } else editor.commands.focus('start');
}
