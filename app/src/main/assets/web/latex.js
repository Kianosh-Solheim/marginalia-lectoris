/* Marginalia Lectoris — LaTeX: real TeX Live (BusyTeX, as WebAssembly) compiling in the browser.
 *
 * The engine and a collection of packages are downloaded once to the tablet, which serves them to the
 * browser; the browser compiles in a background worker. Packages that are not in the collection can be
 * fetched while compiling from a TeX Live server online (Settings → LaTeX).
 */

const LATEX_BASE = '/api/writing/latex/';
const LATEX_REMOTE_DEFAULT = 'https://texlive2026.texlyre.org';
const LATEX_COLLECTIONS = [
  ['basic', 'Basic', 'LaTeX itself, the usual fonts and the core packages. Anything else is fetched online while compiling.'],
  ['recommended', 'Recommended', 'Also the recommended packages (fontspec, polyglossia, microtype, booktabs, setspace …). The rest online.'],
  ['extra', 'Full', 'Also the extra packages (biblatex, csquotes and the many others), so almost everything works without internet.'],
];
const latex = { worker: null, ready: null, collection: null, busy: Promise.resolve(), progress: null };
const latexRemote = () => localGet('ml.texRemote', LATEX_REMOTE_DEFAULT);

/** The collection to download, or null when cancelled. */
function latexPickCollection(st, why) {
  return new Promise(res => {
    let answered = false;
    let pick = st.collection || 'basic';
    const rows = LATEX_COLLECTIONS.map(([k, label, what]) => {
      const r = h('input', { type: 'radio', name: 'texcol', value: k, checked: k === pick });
      r.addEventListener('change', () => { pick = k; });
      return h('label', { class: 'texcol' }, r, h('span', null, h('b', null, `${label} · ${st.sizes && st.sizes[k] ? st.sizes[k] + ' MB' : ''}`), h('span', { class: 'muted' }, what)));
    });
    modal((box, close) => box.append(h('h2', null, st.installed ? 'Change the LaTeX collection' : 'Download LaTeX?'),
      h('p', null, `${why ? why + ' ' : ''}TeX Live 2026 (BusyTeX) is downloaded once to the tablet and compiles in your browser. The tablet takes it from a 520 MB release and keeps only what you choose:`),
      h('div', { class: 'texcols' }, rows),
      h('p', { class: 'muted', style: { fontSize: '12px' } }, 'The browser loads the collection from the tablet when it compiles the first time after opening the page.'),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'),
        h('button', { class: 'btn fill', onclick: () => { answered = true; res(pick); close(); } }, 'Download'))), () => { if (!answered) res(null); });
  });
}

/** Makes sure LaTeX is on the tablet (offers the download once). [want] asks for a particular collection. */
async function ensureLatex(why, want) {
  let st = await api('writing/latex-status');
  if (st.installed && !want && !st.running) return st;
  if (!st.running) {
    const c = want || await latexPickCollection(st, why);
    if (!c) throw new Error('LaTeX is needed for this.');
    if (st.installed && c === st.collection) return st;
    st = await api('writing/latex-install', { method: 'POST', json: { collection: c } });
  }
  toast('Downloading LaTeX…', 0);
  while (st.running || !st.installed) {
    if (st.error) { toast(''); throw new Error('LaTeX download: ' + st.error); }
    await new Promise(r => setTimeout(r, 1500));
    st = await api('writing/latex-status');
    if (st.total > 0 && st.running) toast(`Downloading LaTeX… ${Math.round(st.done / 1048576)} of ${Math.round(st.total / 1048576)} MB (the tablet keeps only what is needed)`, 0);
    if (!st.running && !st.installed && !st.error) st.error = 'stopped';
  }
  toast('LaTeX is ready', 1500);
  latexStop();   // a new collection: the worker starts again with it
  return st;
}

function latexStop() { if (latex.worker) { try { latex.worker.terminate(); } catch (_) {} } latex.worker = null; latex.ready = null; latex.collection = null; }

/** The worker, started with the collection on the tablet. [onPrint] gets the engine's messages while it loads. */
function latexWorker(collection, onPrint) {
  if (latex.ready && latex.collection === collection) return latex.ready;
  latexStop();
  latex.collection = collection;
  latex.ready = new Promise((resolve, reject) => {
    const w = new Worker(LATEX_BASE + 'busytex_worker.js');
    latex.worker = w;
    let timer = 0;
    const later = () => { clearTimeout(timer); timer = setTimeout(() => { reject(new Error('LaTeX did not start (no answer for two minutes)')); latexStop(); }, 120000); };
    later();
    w.onmessage = ({ data }) => {
      if (data.initialized) { clearTimeout(timer); w.onmessage = null; resolve(w); }
      else if (data.exception) { clearTimeout(timer); reject(new Error(data.exception)); latexStop(); }
      else if (data.print) { later(); if (latex.progress) latex.progress(data.print); }
    };
    w.onerror = e => { e.preventDefault(); clearTimeout(timer); reject(new Error('LaTeX could not start: ' + (e.message || 'the worker failed'))); latexStop(); };
    const pack = LATEX_BASE + `texlive-${collection}.js`;
    w.postMessage({
      busytex_js: LATEX_BASE + 'busytex.js', busytex_wasm: LATEX_BASE + 'busytex.wasm',
      biber_js: LATEX_BASE + 'biber.js', biber_wasm: LATEX_BASE + 'biber.wasm', biber_data: LATEX_BASE + 'biber.data',
      preload_data_packages_js: [pack], data_packages_js: [pack], texmf_local: [], preload: true,
    });
  });
  latex.progress = onPrint || null;
  return latex.ready;
}

// ------------------------------------------------------------------ packages from CTAN

/** The files of the packages fetched from CTAN, kept for this page (they go with every compile). */
const ctanCache = { list: null, files: new Map() };
async function ctanPackages(fresh) {
  if (!ctanCache.list || fresh) ctanCache.list = (await api('writing/latex-pkgs')).packages || [];
  return ctanCache.list;
}
async function ctanFiles() {
  let list; try { list = await ctanPackages(); } catch (_) { return []; }
  const out = [];
  for (const p of list) for (const f of p.files) {
    const k = p.name + '/' + f;
    if (!ctanCache.files.has(k)) {
      const r = await fetch(`/api/writing/latex-pkg-file?name=${encodeURIComponent(p.name)}&file=${encodeURIComponent(f)}`, { credentials: 'same-origin' });
      if (!r.ok) continue;
      ctanCache.files.set(k, new Uint8Array(await r.arrayBuffer()));
    }
    out.push({ path: f, content: ctanCache.files.get(k) });
  }
  return out;
}
/** Fetches a package from CTAN to the tablet (only when asked). */
async function ctanFetch(name) {
  toast(`Fetching ${name} from CTAN…`, 0);
  const r = await api('writing/latex-pkg', { method: 'POST', json: { name }, timeout: 120000 });
  ctanCache.list = r.packages || []; ctanCache.files.clear();
  const p = ctanCache.list.find(x => x.name === name);
  toast(p ? `${name} is ready (${p.files.length} files)` : `${name} fetched`, 2500);
  return p;
}
/** The CTAN package a missing file probably belongs to. */
function ctanGuess(file) {
  const stem = file.replace(/\.[^.]+$/, '');
  if (/\.(bbx|cbx|lbx|dbx)$/.test(file)) {
    if (/^chicago/.test(stem)) return 'biblatex-chicago';
    if (/^(apa|apa6)$/.test(stem)) return 'biblatex-' + stem;
    return 'biblatex-' + stem.split('-')[0];
  }
  if (/^cms-/.test(stem)) return 'biblatex-chicago';
  return stem;
}
/** The biblatex style that matches the citation style chosen in the writing program. */
function biblatexStyleFor(citing) {
  const l = String((citing && citing.styleLabel) || '');
  if (/chicago/i.test(l)) return /author[- ]?date/i.test(l) ? 'chicago-authordate' : 'chicago-notes';
  if (/apa|american psychological/i.test(l)) return 'apa';
  if (/\bmla\b|modern language/i.test(l)) return 'mla';
  return citing && citing.isNote ? 'verbose' : 'authoryear';
}

const LATEX_DRIVERS = { pdflatex: 'pdftex_bibtex8', xelatex: 'xetex_bibtex8_dvipdfmx', lualatex: 'luahbtex_bibtex8' };

/**
 * Compiles. files: [{ path, content (string or Uint8Array) }]; main: the .tex to compile.
 * Resolves to { ok, pdf (Uint8Array or null), log, ms }. One compile at a time.
 */
function latexCompile({ files, main = 'main.tex', engine = 'pdflatex', bibtex = false, biber = false, makeindex = false, onPrint }) {
  const run = async () => {
    const st = await ensureLatex('LaTeX is needed to make this PDF.');
    const t0 = performance.now();
    const extra = await ctanFiles();
    const have = new Set(files.map(f => f.path));
    files = files.concat(extra.filter(f => !have.has(f.path)));
    const w = await latexWorker(st.collection, onPrint);
    latex.progress = onPrint || null;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { reject(new Error('LaTeX took more than five minutes; it was stopped')); latexStop(); }, 300000);
      w.onmessage = ({ data }) => {
        if (data.print) { if (onPrint) onPrint(data.print); }
        else if (data.pdf !== undefined) {
          clearTimeout(timer);
          resolve({ ok: data.exit_code === 0 && !!data.pdf, pdf: data.pdf || null, log: data.log || '', logs: data.logs || [], ms: Math.round(performance.now() - t0) });
        } else if (data.exception) { clearTimeout(timer); reject(new Error(data.exception)); }
      };
      w.postMessage({
        files: files.map(f => ({ path: f.path, contents: f.content })), main_tex_path: main,
        bibtex: bibtex || null, biber: biber || null, makeindex: makeindex || null, rerun: null, verbose: 'silent',
        driver: LATEX_DRIVERS[engine] || LATEX_DRIVERS.pdflatex, data_packages_js: null, remote_endpoint: latexRemote() || '', shell_escape: false,
      });
    });
  };
  const p = latex.busy.then(run, run);
  latex.busy = p.catch(() => {});
  return p;
}

/** What went wrong, from a LaTeX log: the first error with its line, and missing files. */
function latexProblems(log) {
  const out = [];
  const lines = String(log || '').split('\n');
  lines.forEach((l, i) => {
    if (/^! /.test(l)) {
      const at = lines.slice(i, i + 12).map(x => /^l\.(\d+)/.exec(x)).find(Boolean);
      out.push({ text: l.slice(2).trim(), line: at ? +at[1] : null });
    }
  });
  const missing = [...new Set([...String(log || '').matchAll(/File `([^']+)' not found/g)].map(m => m[1]))];
  return { errors: out, missing };
}

// ------------------------------------------------------------------ a test

const LATEX_TEST = String.raw`\documentclass[11pt]{article}
\usepackage[T1]{fontenc}
\usepackage[utf8]{inputenc}
\usepackage{lmodern}
\usepackage[margin=2.5cm]{geometry}
\usepackage{amsmath}
\usepackage[style=authoryear,backend=biber]{biblatex}
\addbibresource{test.bib}
\title{Marginalia Lectoris: a test of \LaTeX}
\author{The compiler in your browser}
\begin{document}
\maketitle
\section{Text}
Norwegian letters work: æ, ø, å — Bergen, Høyesterett, Tromsø. Quotations and dashes too: ‘single’, “double”, 1914–1918.

\section{Mathematics}
\begin{equation}
  \hat{\beta} = (X^\top X)^{-1} X^\top y
\end{equation}

\section{Citations}
As \textcite{rawls1971} argues, justice is the first virtue of social institutions \parencite[3]{rawls1971}.

\printbibliography
\end{document}
`;
const LATEX_TEST_BIB = `@book{rawls1971,
  author = {Rawls, John},
  title = {A Theory of Justice},
  year = {1971},
  publisher = {Harvard University Press},
  address = {Cambridge, MA}
}
`;

/** A test from Settings or the palette: compiles a small document with biblatex and shows the PDF. */
async function latexTest() {
  let st;
  try { st = await ensureLatex('To test LaTeX,'); } catch (e) { fail(e); return; }
  const status = h('div', { class: 'mono muted', style: { fontSize: '12px', minHeight: '18px' } }, 'Starting…');
  const view = h('div', { class: 'texview' });
  let url = null;
  modal((box, close) => {
    box.classList.add('wide');
    box.append(h('h2', null, 'LaTeX test'),
      h('p', { class: 'muted', style: { fontSize: '13px' } }, `${st.version} · collection: ${(LATEX_COLLECTIONS.find(c => c[0] === st.collection) || [, st.collection])[1]} · packages online: ${latexRemote() || 'off'}`),
      status, view, h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: close }, 'Close')));
  }, () => { if (url) URL.revokeObjectURL(url); });
  const t0 = performance.now();
  const tick = setInterval(() => { if (!status.dataset.done) status.dataset.sec = Math.round((performance.now() - t0) / 1000); }, 500);
  const say = s => { status.textContent = `${s}  (${Math.round((performance.now() - t0) / 1000)} s)`; };
  try {
    const r = await latexCompile({ files: [{ path: 'main.tex', content: LATEX_TEST }, { path: 'test.bib', content: LATEX_TEST_BIB }], engine: 'pdflatex', biber: true,
      onPrint: m => { if (/Downloading data|Preparing/.test(m)) say('Loading TeX Live from the tablet… ' + m.replace(/^.*\(/, '(')); else if (/^\$ |pdflatex|biber/.test(m)) say('Compiling…'); } });
    clearInterval(tick); status.dataset.done = '1';
    const pr = latexProblems(r.log);
    if (r.pdf && r.pdf.length) {
      url = URL.createObjectURL(new Blob([r.pdf], { type: 'application/pdf' }));
      status.textContent = `${r.ok ? 'Done' : 'Done, with problems'} in ${(r.ms / 1000).toFixed(1)} s (the first time includes loading TeX Live).`;
      view.append(h('iframe', { src: url, class: 'texpdf', title: 'The test PDF' }));
    } else status.textContent = `No PDF was made (${(r.ms / 1000).toFixed(1)} s).`;
    if (pr.missing.length) view.prepend(h('div', { class: 'texwarn' }, `Missing: ${pr.missing.join(', ')}. These packages are not in the collection and could not be fetched online. `,
      st.collection !== 'extra' ? h('button', { class: 'btn quiet', onclick: async () => { try { await ensureLatex(null, 'extra'); toast('Full collection ready — run the test again', 3000); } catch (e) { fail(e); } } }, 'Download the Full collection') : null));
    else if (pr.errors.length && !r.ok) view.prepend(h('div', { class: 'texwarn' }, pr.errors.map(e => (e.line ? `Line ${e.line}: ` : '') + e.text).join(' · ')));
    const logBox = h('pre', { class: 'texlog', hidden: true }, r.log || (r.logs || []).map(l => [l.cmd, l.stdout, l.stderr, l.log].filter(Boolean).join('\n')).join('\n\n') || '(no log)');
    view.append(h('button', { class: 'btn quiet', style: { fontSize: '12px' }, onclick: () => { logBox.hidden = !logBox.hidden; } }, 'Show the log'), logBox);
  } catch (e) {
    clearInterval(tick); status.dataset.done = '1';
    status.textContent = 'LaTeX failed: ' + (e.message || e);
  }
}

// ------------------------------------------------------------------ settings

function latexSettingsSection() {
  const sec = h('section', { class: 'set-group', id: 'set-latex' }, h('h2', null, 'LaTeX'));
  const body = h('div');
  sec.append(h('p', { class: 'muted', style: { fontSize: '13px' } }, 'Real LaTeX (TeX Live 2026) for PDFs from your writing. It is kept on the tablet and compiles in your browser.'), body);
  const ctanList = h('div', { class: 'ctanlist' });
  const ctanIn = h('input', { placeholder: 'CTAN name, e.g. biblatex-chicago', spellcheck: 'false', style: { maxWidth: '240px' } });
  const ctanAdd = async () => { const n = ctanIn.value.trim(); if (!n) return; try { await ctanFetch(n); ctanIn.value = ''; drawCtan(); } catch (e) { fail(e); } };
  ctanIn.addEventListener('keydown', e => { if (e.key === 'Enter') ctanAdd(); });
  const drawCtan = async () => {
    clear(ctanList);
    let list = []; try { list = await ctanPackages(true); } catch (_) {}
    if (!list.length) ctanList.append(h('div', { class: 'muted', style: { fontSize: '12px' } }, 'None yet.'));
    list.forEach(p => ctanList.append(h('div', { class: 'row', style: { gap: '8px', alignItems: 'center', fontSize: '13px' } },
      h('b', null, p.name), h('span', { class: 'muted mono', style: { fontSize: '11px' } }, `${p.version ? 'v' + p.version + ' · ' : ''}${p.files.length} files · ${Math.max(1, Math.round(p.bytes / 1024))} KB`),
      h('button', { class: 'btn quiet', onclick: async () => { try { ctanCache.list = (await api('writing/latex-pkg?name=' + encodeURIComponent(p.name), { method: 'DELETE' })).packages; ctanCache.files.clear(); drawCtan(); } catch (e) { fail(e); } } }, 'Remove'))));
  };
  const ctanRow = h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('b', null, 'Packages from CTAN'), h('div', { class: 'muted' }, 'Packages that are not in the collection, such as biblatex-chicago. Fetched when you press, kept on the tablet, and used in every compile.')),
    h('div', null, ctanList, h('div', { class: 'row', style: { gap: '6px', marginTop: '6px' } }, ctanIn, h('button', { class: 'btn', onclick: ctanAdd }, 'Fetch'))));
  drawCtan();
  const draw = st => {
    clear(body);
    const col = LATEX_COLLECTIONS.find(c => c[0] === st.collection);
    const remote = h('input', { value: latexRemote(), placeholder: 'Empty: never fetch packages online', spellcheck: 'false', style: { maxWidth: '320px' } });
    remote.addEventListener('change', () => { localSet('ml.texRemote', remote.value.trim()); toast(remote.value.trim() ? 'Missing packages are fetched from there' : 'Packages are not fetched online', 1800); });
    body.append(
      h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('b', null, 'On the tablet'), h('div', { class: 'muted' }, st.installed ? `${st.version}` : 'Not downloaded yet.')),
        h('div', { class: 'row', style: { gap: '8px', alignItems: 'center', flexWrap: 'wrap' } },
          h('span', null, st.running ? `Downloading (${st.installing})… ${Math.round(st.done / 1048576)} of ${Math.round((st.total || 0) / 1048576)} MB` : col ? `${col[1]} collection (${st.sizes[col[0]]} MB)` : '—'),
          h('button', { class: 'btn', disabled: st.running, onclick: async () => { try { draw(await ensureLatex()); } catch (e) { if (!/needed/.test(e.message)) fail(e); } } }, st.installed ? 'Change collection…' : 'Download…'),
          st.installed ? h('button', { class: 'btn', onclick: latexTest }, 'Compile a test') : null)),
      ctanRow,
      h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('b', null, 'Packages online'), h('div', { class: 'muted' }, 'A TeX Live server that missing packages are fetched from while compiling (only then). In this browser.')), remote));
  };
  api('writing/latex-status').then(draw).catch(() => body.append(h('div', { class: 'muted' }, 'Could not ask the tablet.')));
  return sec;
}

// ------------------------------------------------------------------ the preview beside the writing

const TEX_DEFAULTS = { cls: 'article', engine: 'pdflatex', fontsize: '12pt', papersize: 'a4', margin: '2.5cm', style: '', preamble: '', clsFile: null, auto: true };

/** Pandoc's LaTeX options from the document's LaTeX settings. */
function texMeta(set) {
  const s = Object.assign({}, TEX_DEFAULTS, set || {});
  const str = c => ({ t: 'MetaString', c });
  const meta = {};
  meta.documentclass = str(s.clsFile && s.clsFile.name ? s.clsFile.name.replace(/\.cls$/i, '') : s.cls);
  if (s.fontsize) meta.fontsize = str(s.fontsize);
  if (s.papersize) meta.papersize = str(s.papersize);
  if (s.margin) meta.geometry = { t: 'MetaList', c: [str('margin=' + s.margin)] };
  if (s.preamble && s.preamble.trim()) meta['header-includes'] = { t: 'MetaList', c: [{ t: 'MetaBlocks', c: [{ t: 'RawBlock', c: ['latex', s.preamble] }] }] };
  return meta;
}

/**
 * The LaTeX panel: the document set by LaTeX, beside the text. [source]() gives
 * { files, main, biber, tex } for the document as it is now. Settings are kept with the document.
 */
function latexPanel(source, settings, onSettings, onToggle) {
  let set = Object.assign({}, TEX_DEFAULTS, settings || {});
  const el = h('aside', { class: 'wtexpanel', hidden: true });
  const status = h('span', { class: 'mono muted wtexstat' }, '');
  const pages = h('div', { class: 'wtexpages' });
  const problems = h('div', { class: 'wtexprob', hidden: true });
  const logBox = h('pre', { class: 'texlog', hidden: true });
  let toolRow = null;
  let lastPdf = null, lastTex = '', timer = 0, running = false, again = false, width = +localGet('ml.wtexWidth', '0') || 0;
  const grip = h('div', { class: 'wtexgrip', title: 'Drag to make the panel wider or narrower' });
  grip.addEventListener('pointerdown', e => {
    e.preventDefault(); grip.setPointerCapture(e.pointerId);
    const x0 = e.clientX, w0 = el.offsetWidth;
    const move = ev => { width = Math.max(280, Math.min(window.innerWidth * 0.75, w0 + (x0 - ev.clientX))); el.style.width = width + 'px'; };
    const up = () => { grip.removeEventListener('pointermove', move); grip.removeEventListener('pointerup', up); localSet('ml.wtexWidth', String(Math.round(width))); redrawSoon(); };
    grip.addEventListener('pointermove', move); grip.addEventListener('pointerup', up);
  });
  if (width) el.style.width = width + 'px';
  const autoCb = h('input', { type: 'checkbox', checked: set.auto !== false, title: 'Set the pages again a moment after you stop typing' });
  autoCb.addEventListener('change', () => { set.auto = autoCb.checked; onSettings(set); });
  el.append(grip,
    h('div', { class: 'wnhead' }, h('b', null, 'LaTeX'), status, h('span', { style: { flex: 1 } }),
      h('label', { class: 'row', style: { gap: '3px', fontSize: '11.5px' }, title: 'Set the pages again a moment after you stop typing' }, autoCb, 'Live'),
      h('button', { class: 'btn quiet', title: 'Set the pages now (Ctrl+Alt+Enter)', onclick: () => api_.compile() }, '⟳'),
      h('button', { class: 'btn quiet', title: 'LaTeX settings for this document', onclick: () => settingsDialog() }, '⚙'),
      h('button', { class: 'btn quiet', title: 'Save the PDF', onclick: () => api_.savePdf() }, '⤓'),
      h('button', { class: 'btn quiet', title: 'Close', onclick: () => api_.close() }, '×')),
    problems, pages,
    h('div', { class: 'row', style: { gap: '6px', marginTop: '6px' } },
      h('button', { class: 'btn quiet', style: { fontSize: '11.5px' }, onclick: () => { logBox.hidden = !logBox.hidden; } }, 'Log'),
      h('button', { class: 'btn quiet', style: { fontSize: '11.5px' }, onclick: () => { if (lastTex) saveBlob(new Blob([lastTex], { type: 'text/plain' }), 'main.tex'); } }, 'The .tex'),
      toolRow = h('span', { class: 'row', style: { gap: '6px' } })),
    logBox);

  let shownDoc = null, redrawTimer = 0;
  const redrawSoon = () => { clearTimeout(redrawTimer); redrawTimer = setTimeout(() => { if (lastPdf) show(lastPdf); }, 200); };
  /** Draws the pages; the new ones replace the old at once, at the same place. */
  async function show(bytes) {
    pdfjsLib.GlobalWorkerOptions.workerSrc = 'lib/pdf.worker.min.js';
    const pdf = await pdfjsLib.getDocument({ data: bytes.slice(0) }).promise;
    const w = Math.max(200, pages.clientWidth - 4), dpr = Math.min(2, window.devicePixelRatio || 1);
    const fresh = h('div', { class: 'wtexset' });
    for (let i = 1; i <= pdf.numPages; i++) {
      const page = await pdf.getPage(i);
      const vp0 = page.getViewport({ scale: 1 }), scale = w / vp0.width, vp = page.getViewport({ scale: scale * dpr });
      const c = h('canvas', { class: 'wtexpage' }); c.width = Math.round(vp.width); c.height = Math.round(vp.height);
      c.style.width = Math.round(vp.width / dpr) + 'px'; c.style.height = Math.round(vp.height / dpr) + 'px';
      await page.render({ canvasContext: c.getContext('2d'), viewport: vp }).promise;
      fresh.append(c, h('div', { class: 'wtexpno mono muted' }, `${i} / ${pdf.numPages}`));
    }
    const top = el.scrollTop;
    clear(pages); pages.append(fresh); el.scrollTop = top;
    if (shownDoc) { try { shownDoc.destroy(); } catch (_) {} }
    shownDoc = pdf;
  }
  function showProblems(r, tex) {
    const pr = latexProblems(r.log);
    clear(problems);
    const lines = tex.split('\n');
    const items = [];
    const missing = r.missingFirst || pr.missing;
    if (missing.length) {
      const guesses = [...new Set(missing.map(ctanGuess))];
      items.push(h('div', null, h('b', null, 'Missing: '), missing.join(', '), '. Not in your LaTeX collection, and not found online. ',
        ...guesses.map(g => h('button', { class: 'btn quiet', title: 'Fetch it from CTAN to the tablet; it is used in every compile from then on', onclick: () => ctanFetch(g).then(() => api_.compile()).catch(fail) }, `Fetch ${g} from CTAN`)),
        latex.collection !== 'extra' ? h('button', { class: 'btn quiet', onclick: () => ensureLatex(null, 'extra').then(() => api_.compile()).catch(fail) }, 'Get the Full collection') : null));
    }
    pr.errors.slice(0, 4).forEach(e => items.push(h('div', null, h('b', null, e.line ? `Line ${e.line}: ` : ''), e.text,
      e.line && lines[e.line - 1] ? h('code', { class: 'wtexline' }, lines[e.line - 1].slice(0, 140)) : null)));
    if (r.styleNote) items.unshift(h('div', null, r.styleNote));
    problems.hidden = !items.length;
    problems.append(...items);
  }
  async function compile() {
    if (running) { again = true; return; }
    running = true; again = false;
    const t0 = performance.now();
    status.textContent = 'setting…';
    el.classList.add('busy');
    try {
      const onPrint = m => { if (/Downloading data|Preparing/.test(m)) status.textContent = 'loading TeX Live ' + m.replace(/^.*\(/, '('); };
      let src = await source(set);
      let r = await latexCompile({ files: src.files, main: 'main.tex', engine: set.engine, biber: src.biber, onPrint });
      // A biblatex style that is not in TeX Live here (biblatex-apa, for one): shown with authoryear instead.
      const style = latexProblems(r.log).missing.find(f => /\.(bbx|cbx)$/.test(f));
      if (style && src.biber) {
        src = await source(Object.assign({}, set, { style: 'authoryear' }));
        r = await latexCompile({ files: src.files, main: 'main.tex', engine: set.engine, biber: true, onPrint });
        r.styleNote = `The biblatex style “${style.replace(/\.\w+$/, '')}” is not on the tablet yet, so the citations are shown in authoryear for now.`;
        r.missingFirst = [style];
      }
      lastTex = src.tex;
      logBox.textContent = r.log || (r.logs || []).map(l => [l.cmd, l.stdout, l.stderr].filter(Boolean).join('\n')).join('\n\n');
      showProblems(r, src.tex);
      if (r.pdf && r.pdf.length) { lastPdf = r.pdf; await show(r.pdf); }
      status.textContent = (r.ok ? '' : 'with errors · ') + ((performance.now() - t0) / 1000).toFixed(1) + ' s';
    } catch (e) {
      status.textContent = 'failed';
      clear(problems); problems.hidden = false; problems.append(h('div', null, String(e.message || e)));
    } finally {
      running = false; el.classList.remove('busy');
      if (again && !el.hidden) setTimeout(compile, 100);
    }
  }
  function settingsDialog() {
    modal((box, close) => {
      const sel = (v, opts) => h('select', null, opts.map(([k, l]) => h('option', { value: k, selected: k === v }, l)));
      const cls = sel(set.cls, [['article', 'article — essays, papers'], ['report', 'report — theses with chapters'], ['book', 'book'], ['memoir', 'memoir'], ['scrartcl', 'KOMA scrartcl'], ['scrreprt', 'KOMA scrreprt']]);
      const engine = sel(set.engine, [['pdflatex', 'pdfLaTeX — quickest'], ['xelatex', 'XeLaTeX — system-style fonts (fontspec)'], ['lualatex', 'LuaLaTeX']]);
      const size = sel(set.fontsize, [['10pt', '10 pt'], ['11pt', '11 pt'], ['12pt', '12 pt']]);
      const paper = sel(set.papersize, [['a4', 'A4'], ['letter', 'US Letter']]);
      const margin = h('input', { value: set.margin, style: { width: '90px' } });
      const style = h('input', { value: set.style, placeholder: 'as when exporting', style: { width: '170px' } });
      const pre = h('textarea', { rows: 6, class: 'mono', style: { width: '100%', fontSize: '12px' }, placeholder: '\\usepackage{setspace}\n\\onehalfspacing' }, set.preamble || '');
      let clsFile = set.clsFile;
      const clsName = h('span', { class: 'mono muted' }, clsFile ? clsFile.name : 'none');
      const clsIn = h('input', { type: 'file', accept: '.cls', style: { display: 'none' } });
      clsIn.addEventListener('change', async () => { const f = clsIn.files[0]; if (!f) return; clsFile = { name: f.name, content: await f.text() }; clsName.textContent = f.name; });
      const row = (label, el, sub) => h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('b', null, label), sub ? h('div', { class: 'muted' }, sub) : null), el);
      box.classList.add('wide');
      box.append(h('h2', null, 'LaTeX for this document'),
        row('Document class', cls), row('Engine', engine), row('Font size', size), row('Paper', paper), row('Margins', margin, 'e.g. 2.5cm or 1in'),
        row('biblatex style', style, 'e.g. apa, authoryear, verbose, chicago-authordate'),
        row('Your own class (.cls)', h('div', { class: 'row', style: { gap: '8px', alignItems: 'center' } }, clsName, h('button', { class: 'btn', onclick: () => clsIn.click() }, 'Choose…'), clsFile ? h('button', { class: 'btn quiet', onclick: () => { clsFile = null; clsName.textContent = 'none'; } }, 'Remove') : null, clsIn), 'A template from a journal or the university; it is used instead of the class above.'),
        h('div', { class: 'label', style: { marginTop: '10px' } }, 'In the preamble'), pre,
        h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: () => {
          set = Object.assign({}, set, { cls: cls.value, engine: engine.value, fontsize: size.value, papersize: paper.value, margin: margin.value.trim(), style: style.value.trim(), preamble: pre.value, clsFile });
          onSettings(set); close(); api_.compile();
        } }, 'Save')));
    });
  }
  const api_ = {
    el,
    addTool(label, title, run) { toolRow.append(h('button', { class: 'btn quiet', style: { fontSize: '11.5px' }, title, onclick: run }, label)); },
    isOpen: () => !el.hidden,
    settings: () => set,
    open() { el.hidden = false; localSet('ml.wtexOpen', '1'); onToggle && onToggle(true); compile(); },
    close() { el.hidden = true; localSet('ml.wtexOpen', ''); onToggle && onToggle(false); },
    toggle() { if (el.hidden) api_.open(); else api_.close(); },
    compile: () => { clearTimeout(timer); return compile(); },
    /** The text changed: set the pages again when typing has paused. */
    changed() { if (el.hidden || set.auto === false) return; clearTimeout(timer); timer = setTimeout(compile, 2500); },
    async savePdf(name) {
      if (!lastPdf) await compile();
      if (!lastPdf) { toast('There is no PDF yet'); return null; }
      const blob = new Blob([lastPdf], { type: 'application/pdf' });
      saveBlob(blob, name || 'document.pdf');
      return blob;
    },
  };
  return api_;
}
