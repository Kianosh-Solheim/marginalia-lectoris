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

const LATEX_DRIVERS = { pdflatex: 'pdftex_bibtex8', xelatex: 'xetex_bibtex8_dvipdfmx', lualatex: 'luahbtex_bibtex8' };

/**
 * Compiles. files: [{ path, content (string or Uint8Array) }]; main: the .tex to compile.
 * Resolves to { ok, pdf (Uint8Array or null), log, ms }. One compile at a time.
 */
function latexCompile({ files, main = 'main.tex', engine = 'pdflatex', bibtex = false, biber = false, makeindex = false, onPrint }) {
  const run = async () => {
    const st = await ensureLatex('LaTeX is needed to make this PDF.');
    const t0 = performance.now();
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
      h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('b', null, 'Packages online'), h('div', { class: 'muted' }, 'A TeX Live server that missing packages are fetched from while compiling (only then). In this browser.')), remote));
  };
  api('writing/latex-status').then(draw).catch(() => body.append(h('div', { class: 'muted' }, 'Could not ask the tablet.')));
  return sec;
}
