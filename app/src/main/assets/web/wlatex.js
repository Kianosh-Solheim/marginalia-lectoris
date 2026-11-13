/* Marginalia Lectoris — LaTeX in the writing program: formulas, and citations as citation keys.
 *
 * Formulas are written as LaTeX and shown typeset (KaTeX); they go out as real maths to Word,
 * LibreOffice and LaTeX. For LaTeX and Markdown, citations can go out as keys (\autocite{key},
 * [@key]) with a .bib file; a .tex or .md file with such keys comes in as live citations.
 * The keys are Marginalia's own; where Zotero has another key for the same source, you are told.
 */

// ------------------------------------------------------------------ formulas

let katexLoading = null;
function ensureKatex() {
  if (!katexLoading) {
    document.head.append(h('link', { rel: 'stylesheet', href: 'vendor/katex/katex.min.css' }));
    katexLoading = loadScript('vendor/katex/katex.min.js').then(() => window.katex);
  }
  return katexLoading;
}
function renderMath(el, tex, display) {
  const plain = () => { el.textContent = display ? `$$${tex}$$` : `$${tex}$`; };
  if (!window.katex) { plain(); ensureKatex().then(() => renderMath(el, tex, display)).catch(() => {}); return; }
  try { window.katex.render(tex || '\\square', el, { displayMode: !!display, throwOnError: false, output: 'html' }); } catch (_) { plain(); }
}
function mathExtension(T, onEdit) {
  return T.Node.create({
    name: 'math', group: 'inline', inline: true, atom: true, selectable: true,
    addAttributes() { return { tex: { default: '' }, display: { default: false } }; },
    parseHTML() { return [{ tag: 'span[data-math]', getAttrs: el => ({ tex: el.getAttribute('data-math') || '', display: el.hasAttribute('data-display') }) }]; },
    renderHTML({ node }) { return ['span', Object.assign({ 'data-math': node.attrs.tex }, node.attrs.display ? { 'data-display': '' } : {}), node.attrs.display ? `$$${node.attrs.tex}$$` : `$${node.attrs.tex}$`]; },
    addNodeView() {
      return ({ node, getPos }) => {
        const dom = h('span', { class: 'wmath' + (node.attrs.display ? ' block' : ''), title: 'Formula — click to edit' });
        renderMath(dom, node.attrs.tex, node.attrs.display);
        dom.addEventListener('click', () => onEdit(typeof getPos === 'function' ? getPos() : null));
        return { dom, update: n => { if (n.type.name !== 'math') return false; dom.className = 'wmath' + (n.attrs.display ? ' block' : ''); renderMath(dom, n.attrs.tex, n.attrs.display); return true; } };
      };
    },
  });
}
/** Insert a formula at the cursor, or edit the one at [pos]. */
function mathDialog(editor, pos) {
  const node = pos != null ? editor.state.doc.nodeAt(pos) : null;
  const editing = node && node.type.name === 'math';
  const sel = editor.state.selection;
  const seed = editing ? node.attrs.tex : editor.state.doc.textBetween(sel.from, sel.to, ' ').replace(/^\$+|\$+$/g, '');
  modal((box, close) => {
    const ta = h('textarea', { rows: 4, class: 'wmathsrc', placeholder: 'LaTeX, e.g.  \\frac{a}{b} = \\sum_{i=1}^{n} x_i^2', spellcheck: 'false' }); ta.value = seed;
    const disp = h('input', { type: 'checkbox', checked: editing && node.attrs.display });
    const prev = h('div', { class: 'wmathprev' });
    const draw = () => renderMath(prev, ta.value.trim(), disp.checked);
    ta.addEventListener('input', draw); disp.addEventListener('change', draw);
    const go = () => {
      const tex = ta.value.trim();
      if (!tex) { toast('Write the formula first'); return; }
      const n = { type: 'math', attrs: { tex, display: disp.checked } };
      if (editing) editor.chain().focus().insertContentAt({ from: pos, to: pos + node.nodeSize }, n).run();
      else editor.chain().focus().insertContentAt({ from: sel.from, to: sel.to }, n).run();
      close();
    };
    ta.addEventListener('keydown', e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); go(); } });
    box.append(h('h2', null, 'Formula'), ta,
      h('label', { class: 'row', style: { gap: '6px', fontSize: '13px', margin: '6px 0' } }, disp, 'On its own line, centred (display formula)'),
      h('div', { class: 'label' }, 'Preview'), prev,
      h('div', { class: 'foot' },
        editing ? h('button', { class: 'btn quiet', onclick: () => { editor.chain().focus().deleteRange({ from: pos, to: pos + node.nodeSize }).run(); close(); } }, 'Remove') : null,
        h('span', { class: 'muted', style: { flex: 1, fontSize: '12px' } }, 'Ctrl+Enter inserts.'),
        h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: go }, editing ? 'Update' : 'Insert')));
    draw(); setTimeout(() => ta.focus(), 40);
  });
}

// ------------------------------------------------------------------ citation keys

const KEY_LOCATOR = { page: ['p.', 'pp.'], chapter: ['chap.', 'chaps.'], section: ['sec.', 'secs.'], paragraph: ['para.', 'paras.'], line: ['l.', 'll.'], figure: ['fig.', 'figs.'], note: ['n.', 'nn.'], volume: ['vol.', 'vols.'], column: ['col.', 'cols.'] };
const cleanKey = k => String(k || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^A-Za-z0-9_:.+-]/g, '');

/** Every source cited in the text (footnotes too), in order. */
function citedIds(doc) {
  const ids = [];
  const walk = n => {
    if (!n) return;
    if (n.type === 'citation') (n.attrs.items || []).forEach(i => { if (!ids.includes(i.id)) ids.push(i.id); });
    if (n.type === 'footnote') walk(n.attrs.content);
    (n.content || []).forEach(walk);
  };
  walk(doc);
  return ids;
}
/**
 * The key of every cited source: { keys: id → key, differ: [{ label, mine, zotero }], made: [{ label, key }] }.
 * [differ] lists sources whose key in Marginalia is not the one Zotero has; [made] those with no key
 * anywhere, which get one from author and year.
 */
async function resolveCiteKeys(citing, doc) {
  const ids = citedIds(doc);
  const out = { keys: {}, differ: [], made: [] };
  if (!ids.length) return out;
  const r = await api('writing/citekeys', { method: 'POST', json: { ids } });
  const used = new Set();
  const labelOf = id => { const c = citing.items[id] || {}; const a = (c.author || c.editor || [])[0] || {}; const y = c.issued && c.issued['date-parts'] && c.issued['date-parts'][0] ? c.issued['date-parts'][0][0] : ''; return [a.family || a.literal || '', y, (c.title || '').slice(0, 50)].filter(Boolean).join(' · '); };
  ids.forEach(id => {
    const x = r[id] || {};
    let key = cleanKey(x.key), made = false;
    if (!key) {
      const c = citing.items[id] || {}; const a = (c.author || c.editor || [])[0] || {};
      const y = c.issued && c.issued['date-parts'] && c.issued['date-parts'][0] ? c.issued['date-parts'][0][0] : '';
      key = cleanKey(((a.family || a.literal || 'anon').split(/\s+/).pop() + (y || 'nd')).toLowerCase()) || 'ref'; made = true;
    }
    let k = key, n = 0;
    while (used.has(k)) k = key + String.fromCharCode(97 + n++);
    used.add(k); out.keys[id] = k;
    if (made) out.made.push({ label: labelOf(id), key: k });
    else if (x.zoteroKey && cleanKey(x.zoteroKey) !== key) out.differ.push({ label: labelOf(id), mine: key, zotero: x.zoteroKey });
  });
  return out;
}
/** A citation as Pandoc's own Cite element (Pandoc writes \autocite, \citep or [@key] from it). */
function citeKeyInlines(attrs, ctx) {
  const cites = (attrs.items || []).filter(i => ctx.citeKeys[i.id]).map(i => {
    let suffix = '';
    if (i.locator) { const many = /[-–,&]/.test(i.locator); const t = KEY_LOCATOR[i.label || 'page'] || KEY_LOCATOR.page; suffix = `, ${t[many ? 1 : 0]} ${i.locator}`; }
    if (i.suffix) suffix += (suffix ? ' ' : ', ') + i.suffix;
    return { citationId: ctx.citeKeys[i.id], citationPrefix: i.prefix ? textToInlines(i.prefix) : [], citationSuffix: suffix ? textToInlines(suffix) : [],
      citationMode: { t: i.suppressAuthor ? 'SuppressAuthor' : 'NormalCitation' }, citationNoteNum: 0, citationHash: 0 };
  });
  if (!cites.length) return textToInlines(ctx.citing.text(attrs));
  return [{ t: 'Cite', c: [cites, textToInlines(ctx.citing.text(attrs))] }];
}
/** The .bib file of the cited sources, made by Pandoc from their data. */
async function bibFile(citing, keys, bibtex) {
  const refs = Object.entries(keys).filter(([id]) => citing.items[id]).map(([id, key]) => { const c = Object.assign({}, citing.items[id], { id: key }); delete c['citation-key']; return c; });
  if (!refs.length) return '';
  const pd = await ensurePandoc('Writing the .bib file.');
  const r = await pd.convert({ from: 'csljson', to: bibtex ? 'bibtex' : 'biblatex' }, JSON.stringify(refs), {});
  if (!r.stdout) throw new Error('Pandoc could not write the .bib file' + (r.stderr ? ': ' + r.stderr.slice(0, 300) : ''));
  return r.stdout;
}
/**
 * For an export with keys: the text without its bibliography block (and the heading right above
 * it, whose words become the title of the list LaTeX prints itself).
 */
function stripBibliography(doc) {
  let title = '';
  const content = [];
  (doc.content || []).forEach(n => {
    if (n.type === 'bibliography') {
      const prev = content[content.length - 1];
      if (prev && prev.type === 'heading') { title = (prev.content || []).map(t => t.text || '').join(''); content.pop(); }
      return;
    }
    content.push(n);
  });
  return { doc: Object.assign({}, doc, { content }), title };
}

/** A Pandoc citation suffix (", pp. 33-35 and passim") → { locator, label, suffix }. */
function parseKeySuffix(text) {
  const t = String(text || '').replace(/^[,\s]+/, '').trim();
  if (!t) return {};
  const m = t.match(/^(pp?\.|chaps?\.|ch\.|secs?\.|paras?\.|§+|vols?\.|figs?\.|nn?\.|ll?\.|cols?\.)?\s*((?:\d|[ivxlc]+\b)[\divxlc–—,&\s-]*)(.*)$/i);
  if (!m || !m[2].trim()) return { suffix: t };
  const k = (m[1] || 'p.').toLowerCase();
  const label = k.startsWith('chap') || k === 'ch.' ? 'chapter' : k.startsWith('sec') || k.startsWith('§') ? 'section' : k.startsWith('para') ? 'paragraph' : k.startsWith('vol') ? 'volume' : k.startsWith('fig') ? 'figure' : k.startsWith('n') ? 'note' : k.startsWith('l') ? 'line' : k.startsWith('col') ? 'column' : 'page';
  const o = { locator: m[2].trim().replace(/[,\s]+$/, '').replace(/\s*[-–—]+\s*/g, '–'), label };
  const rest = m[3].replace(/^[,\s]+/, '').trim(); if (rest) o.suffix = rest;
  return o;
}
/** Pandoc's Cite (from a .tex or .md file) → a citation node whose sources are still keys ("key:tilly2017"). */
function citeToPm(x) {
  const items = (x.c[0] || []).map(c => {
    const o = Object.assign({ id: 'key:' + c.citationId }, parseKeySuffix(plainText(c.citationSuffix)));
    const pre = plainText(c.citationPrefix).trim(); if (pre) o.prefix = pre;
    if (c.citationMode && c.citationMode.t === 'SuppressAuthor') o.suppressAuthor = true;
    return o;
  });
  return { type: 'citation', attrs: { citationID: newCiteId(), items, keyText: plainText(x.c[1]) || '[' + items.map(i => '@' + i.id.slice(4)).join('; ') + ']' } };
}
/**
 * After an import: finds the sources of the keys in the library and Zotero. Citations whose keys
 * are all known become live; the others stay as text. Returns { content, sources, unknown }.
 */
async function resolveImportedKeys(content) {
  const keys = new Set();
  const scan = n => { if (!n) return; if (n.type === 'citation') (n.attrs.items || []).forEach(i => { if (String(i.id).startsWith('key:')) keys.add(i.id.slice(4)); }); if (n.type === 'footnote') scan(n.attrs.content); (n.content || []).forEach(scan); };
  scan(content);
  if (!keys.size) return { content, sources: [], unknown: [] };
  let found = {};
  try { found = await api('writing/citekey-lookup', { method: 'POST', json: { keys: [...keys] } }); } catch (_) {}
  const sources = {}, unknown = new Set();
  const fix = n => {
    if (!n) return n;
    if (n.type === 'citation' && (n.attrs.items || []).some(i => String(i.id).startsWith('key:'))) {
      const items = n.attrs.items.map(i => { const e = found[i.id.slice(4)]; if (!e) { unknown.add(i.id.slice(4)); return null; } sources[e.id] = e; return Object.assign({}, i, { id: e.id }); });
      if (items.some(i => !i)) return { type: 'text', text: n.attrs.keyText };
      return { type: 'citation', attrs: { citationID: n.attrs.citationID, items } };
    }
    const o = Object.assign({}, n);
    if (n.type === 'footnote' && n.attrs.content) o.attrs = Object.assign({}, n.attrs, { content: fix(n.attrs.content) });
    if (n.content) o.content = n.content.map(fix);
    return o;
  };
  return { content: fix(content), sources: Object.values(sources), unknown: [...unknown] };
}

// ------------------------------------------------------------------ PDF with Typst

const TYPST_FONTS = ['LibertinusSerif-Regular.otf', 'LibertinusSerif-Bold.otf', 'LibertinusSerif-Italic.otf', 'LibertinusSerif-BoldItalic.otf', 'NewCMMath-Regular.otf', 'DejaVuSansMono.ttf', 'DejaVuSansMono-Bold.ttf'];
let typstCompiler = null, typstLoading = null;
/** Typst, ready to set pages. The engine is downloaded to the tablet once; its fonts come with the app. */
async function ensureTypst() {
  if (typstCompiler) return typstCompiler;
  if (typstLoading) return typstLoading;
  await ensureEngine('typst', 'the PDF engine', 'A PDF is set with Typst, a typesetter in the family of LaTeX (about 9 MB to download, 28 MB on the tablet, from the official typst.ts package). After that, PDFs are made offline.', 'Making a PDF.');
  typstLoading = (async () => {
    await loadScript('vendor/typst.bundle.js');
    const r = await fetch('/api/writing/typst.wasm', { credentials: 'same-origin' });
    if (!r.ok) throw new Error('Could not load the PDF engine (' + r.status + ')');
    const wasm = await r.arrayBuffer();
    const c = TypstTs.createTypstCompiler();
    await c.init({ getModule: () => wasm, beforeBuild: [TypstTs.loadFonts(TYPST_FONTS.map(f => new URL('vendor/typst-fonts/' + f, location.href).href), { assets: false })] });
    typstCompiler = c;
    return c;
  })();
  try { return await typstLoading; } finally { typstLoading = null; }
}
/** Typst source (as Pandoc wrote it) and its pictures → a PDF. */
async function typstPdf(source, files) {
  const c = await ensureTypst();
  try { c.resetShadow(); } catch (_) {}
  c.addSource('/main.typ', source);
  for (const [name, blob] of Object.entries(files || {})) c.mapShadow('/' + name, new Uint8Array(await blob.arrayBuffer()));
  const r = await c.compile({ mainFilePath: '/main.typ', format: 1, diagnostics: 'unix' });
  if (!r || !r.result) {
    const why = ((r && r.diagnostics) || []).map(d => typeof d === 'string' ? d : (d.message || JSON.stringify(d))).slice(0, 3).join(' · ');
    throw new Error('The PDF could not be made' + (why ? ': ' + why.slice(0, 400) : ''));
  }
  return new Blob([r.result], { type: 'application/pdf' });
}
