// ------------------------------------------------------------------ page numbers from a PDF → an EPUB
/* The same book as a PDF with the printed page numbers and as an EPUB without them. The PDF tells
   which words begin each printed page; the EPUB gets a page marker there (epub:type="pagebreak")
   and a page list, so notes, citations and "go to page N" give the printed pages. The rest of the
   EPUB is left as it is. Plan: claude/epub-page-numbers-from-pdf-plan.md in the project; this is
   step 1 (PDFs with a text layer). Everything happens in this browser. */

const PG_XHTML = 'http://www.w3.org/1999/xhtml';
const PG_OPS = 'http://www.idpf.org/2007/ops';
const PG_TOKEN = /[\p{L}\p{N}­]+/gu;
const pgWord = w => w.replace(/­/g, '').normalize('NFKC').toLowerCase();
const PG_ROMAN = /^[ivxlcdm]{1,7}$/i;

/** Joins a path inside the EPUB. */
function pgJoin(dir, href) {
  const parts = (dir + href).split('/'), out = [];
  for (const p of parts) { if (p === '..') out.pop(); else if (p !== '.' && p !== '') out.push(p); }
  return out.join('/');
}
/** The path from one file in the EPUB to another (for links in the nav document). */
function pgRelative(fromFile, toFile) {
  const a = fromFile.split('/').slice(0, -1), b = toFile.split('/');
  while (a.length && b.length > 1 && a[0] === b[0]) { a.shift(); b.shift(); }
  return '../'.repeat(a.length) + b.join('/');
}
function pgLev(a, b) {   // edit distance, for words that differ by a letter (a typo, an OCR slip)
  if (Math.abs(a.length - b.length) > 2) return 3;
  const d = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    let prev = d[0]; d[0] = i;
    for (let j = 1; j <= b.length; j++) { const t = d[j]; d[j] = Math.min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] === b[j - 1] ? 0 : 1)); prev = t; }
  }
  return d[b.length];
}
const pgSim = (a, b) => a === b ? 1 : (a && b && a.length >= 4 && pgLev(a, b) <= 1 ? 0.7 : 0);

// ------------------------------------------------------------------ text recognition (OCR)

let ocrWorker = null, ocrLangs = '', ocrLoading = null;
/** Tesseract, ready to read a scanned page in the given languages. Downloaded to the tablet once. */
async function ensureOcr(langs) {
  const key = langs.join('+');
  if (ocrWorker && ocrLangs === key) return ocrWorker;
  if (ocrLoading) await ocrLoading.catch(() => {});
  if (ocrWorker && ocrLangs === key) return ocrWorker;
  await ensureEngine('ocr', 'text recognition', 'Scanned pages are read with Tesseract, the open-source text recognition engine, with its Norwegian and English models (about 39 MB to download, 9 MB on the tablet, from the official tesseract.js packages). After that, pages are read offline, in this browser.', 'Reading scanned pages.');
  ocrLoading = (async () => {
    await loadScript('vendor/tesseract/tesseract.min.js');
    if (ocrWorker) { await ocrWorker.terminate().catch(() => {}); ocrWorker = null; }
    const base = location.origin + '/api/writing/ocr';
    ocrWorker = await Tesseract.createWorker(langs, 1 /* LSTM only */, {
      workerPath: new URL('vendor/tesseract/worker.min.js', location.href).href, corePath: base + '/tesseract-core-simd-lstm.wasm.js',
      langPath: base, gzip: true, workerBlobURL: false, cacheMethod: 'none', logger: () => {}, errorHandler: e => console.warn('OCR', e),
    }, { debug_file: '/dev/null' });   // Tesseract's own diagnostics go nowhere
    await ocrWorker.setParameters({ user_defined_dpi: '200' });   // pages are drawn at 200 dpi (no guessing, better reading)
    ocrLangs = key;
    return ocrWorker;
  })();
  try { return await ocrLoading; } finally { ocrLoading = null; }
}
/** One scanned PDF page read: its lines, placed as the PDF's own text would be (y up from the foot). */
async function pgOcrPage(page, H, langs) {
  const w = await ensureOcr(langs);
  const scale = 200 / 72;   // 200 dpi: enough for book text, quick to read
  const vp = page.getViewport({ scale });
  const cv = document.createElement('canvas'); cv.width = Math.ceil(vp.width); cv.height = Math.ceil(vp.height);
  const g = cv.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, cv.width, cv.height);
  await page.render({ canvasContext: g, viewport: vp }).promise;
  const { data } = await w.recognize(cv, {}, { blocks: true, text: false });
  const lines = [];
  const add = (ln, dy = 0) => {
    const t = (ln.text || '').replace(/\s+/g, ' ').trim();
    if (!t || (ln.confidence != null && ln.confidence < 30 && t.length < 4)) return;   // specks read as letters
    lines.push({ y: H - (ln.bbox.y1 + dy) / scale, h: (ln.bbox.y1 - ln.bbox.y0) / scale, text: t,
      words: (ln.words || []).map(x => ({ t: x.text, box: [x.bbox.x0 / scale, H - (x.bbox.y1 + dy) / scale, x.bbox.x1 / scale, H - (x.bbox.y0 + dy) / scale] })) });
  };
  for (const b of data.blocks || []) for (const par of b.paragraphs || []) for (const ln of par.lines || []) add(ln);
  // a page number alone in the margin is easily missed on a page read whole: read the top and the
  // bottom strip again, as one line of digits, when no number was found there
  const edge = Math.max(H * 0.08, 40), px = Math.round(edge * scale);
  const numberIn = zone => lines.some(l => (zone === 'head' ? l.y > H - edge : l.y < edge) && /(^|\s)\d{1,4}(\s|$)/.test(l.text));
  const strips = [['head', 0], ['foot', cv.height - px]].filter(([z]) => !numberIn(z));
  if (strips.length) {
    await w.setParameters({ tessedit_pageseg_mode: '7', tessedit_char_whitelist: '0123456789' });
    try {
      for (const [, top] of strips) {
        const r = await w.recognize(cv, { rectangle: { left: 0, top, width: cv.width, height: px } }, { blocks: true, text: false });
        for (const b of r.data.blocks || []) for (const par of b.paragraphs || []) for (const ln of par.lines || []) if (/^\s*\d{1,4}\s*$/.test(ln.text || '') && (ln.confidence == null || ln.confidence > 50)) add(ln);
      }
    } finally { await w.setParameters({ tessedit_pageseg_mode: '3', tessedit_char_whitelist: '', user_defined_dpi: '200' }); }
  }
  cv.width = cv.height = 0;
  return lines;
}

// ------------------------------------------------------------------ the PDF

/** The PDF's pages: lines of text (without running heads and feet), words, and the printed page each one is. */
async function pgReadPdf(data, opts, progress) {
  pdfjsLib.GlobalWorkerOptions.workerSrc = 'lib/pdf.worker.min.js';
  const pdf = await pdfjsLib.getDocument({ data: new Uint8Array(data) }).promise;
  let labels = null; try { labels = await pdf.getPageLabels(); } catch (_) {}
  const pages = [];
  let ocrDone = 0, ocrTime = 0;
  for (let i = 1; i <= pdf.numPages; i++) {
    if (i % 5 === 1) progress(`Reading the PDF: page ${i} of ${pdf.numPages}`);
    const page = await pdf.getPage(i);
    const H = page.getViewport({ scale: 1 }).height;
    const tc = await page.getTextContent();
    const items = tc.items.filter(it => it.str && it.str.trim())
      .map(it => ({ s: it.str, x: it.transform[4], y: it.transform[5], w: it.width || 0, h: Math.abs(it.transform[3]) || it.height || 10 }));
    // lines: the items on one baseline, left to right
    items.sort((a, b) => (b.y - a.y) || (a.x - b.x));
    const lines = [];
    for (const it of items) {
      const ln = lines[lines.length - 1];
      if (ln && Math.abs(ln.y - it.y) < Math.max(2, Math.min(ln.h, it.h) * 0.5)) ln.items.push(it);
      else lines.push({ y: it.y, h: it.h, items: [it] });
    }
    lines.forEach(ln => {
      ln.items.sort((a, b) => a.x - b.x);
      let t = '', end = null;
      for (const it of ln.items) { if (end != null && it.x - end > it.h * 0.15 && !/\s$/.test(t)) t += ' '; t += it.s; end = it.x + it.w; }
      ln.text = t.replace(/\s+/g, ' ').trim();
    });
    // a page with no text of its own is a scan: read it (OCR)
    let ocr = false;
    if (((lines.map(l => l.text).join(' ').match(PG_TOKEN) || []).length < 5) && opts.ocr !== false) {
      const left = ocrDone ? Math.round(ocrTime / ocrDone * (pdf.numPages - i + 1) / 60000) : null;
      progress(`Reading scanned page ${i} of ${pdf.numPages} (text recognition)${left != null ? ` · about ${left < 1 ? 'a minute' : left + ' min'} left` : '…'}`);
      const t0 = performance.now();
      const read = await pgOcrPage(page, H, opts.ocrLangs || ['nor', 'eng']);
      ocrTime += performance.now() - t0; ocrDone++;
      if (read.length) { lines.length = 0; lines.push(...read); ocr = true; }
    }
    if (opts.stop && opts.stop.stop) { await pdf.destroy(); throw new Error('Stopped.'); }
    const edge = Math.max(H * 0.08, 40);   // running heads and feet sit near the edge, also on a short page
    lines.forEach(ln => { ln.zone = ln.y > H - edge ? 'head' : ln.y < edge ? 'foot' : ''; });
    pages.push({ n: i, H, lines, ocr, pdfLabel: labels ? labels[i - 1] || '' : '' });
    page.cleanup();
  }
  await pdf.destroy();

  // running heads and feet: lines at the top or bottom that come back, numbers aside, on many pages
  const shape = t => t.toLowerCase().replace(/[0-9]+/g, '#').replace(/\b[ivxlcdm]{1,7}\b/g, '#').trim();
  const seen = new Map();
  pages.forEach(p => p.lines.forEach(ln => { if (ln.zone) { const k = ln.zone + '|' + shape(ln.text); seen.set(k, (seen.get(k) || 0) + 1); } }));
  const often = Math.max(3, Math.round(pages.length * 0.08));
  pages.forEach(p => {
    p.folios = [];
    p.body = p.lines.filter(ln => {
      if (!ln.zone) return true;
      const bare = /^\s*(\d{1,4}|[ivxlcdm]{1,7})\s*$/i.test(ln.text);
      const running = bare || (seen.get(ln.zone + '|' + shape(ln.text)) || 0) >= often;
      if (!running) return true;
      const m = /^\s*(\d{1,4}|[ivxlcdm]{1,7})\b|\b(\d{1,4}|[ivxlcdm]{1,7})\s*$/i.exec(ln.text);
      if (m) p.folios.push(m[1] || m[2]);
      return false;
    });
    // words, joining a word broken over two lines with a hyphen
    let text = '';
    p.body.forEach((ln, k) => {
      const t = ln.text;
      if (k && /[\p{L}]-$/u.test(text) && /^\p{Ll}/u.test(t)) text = text.slice(0, -1) + t;
      else text += (text ? ' ' : '') + t;
    });
    p.words = (text.match(PG_TOKEN) || []).map(pgWord).filter(Boolean);
  });

  // the printed page of each PDF page
  let source = '';
  const given = opts.pdfPage && opts.printedPage;
  if (given) {
    const d = parseInt(opts.printedPage, 10) - parseInt(opts.pdfPage, 10);
    pages.forEach(p => { p.label = p.n + d >= 1 ? String(p.n + d) : ''; });
    source = `as you gave it: PDF page ${opts.pdfPage} is page ${opts.printedPage}`;
  } else if (labels && labels.some((l, i) => l && l !== String(i + 1))) {
    pages.forEach(p => { p.label = p.pdfLabel; });
    source = 'from the PDF\'s page labels';
  } else {
    // the number in the margins: each page's own number, kept where the numbers run in order
    // (a stray number is left out), and pages without one numbered between neighbours that agree
    const cand = pages.map(p => { const f = p.folios.find(x => /^\d+$/.test(x)); return f ? parseInt(f, 10) : null; });
    const idx = cand.map((c, i) => c == null ? -1 : i).filter(i => i >= 0);
    const best = idx.map(() => 1), from = idx.map(() => -1);
    for (let a = 0; a < idx.length; a++) for (let b = 0; b < a; b++) {
      const dc = cand[idx[a]] - cand[idx[b]], dn = idx[a] - idx[b];
      if (dc >= 1 && dc <= dn + 12 && best[b] + 1 > best[a]) { best[a] = best[b] + 1; from[a] = b; }
    }
    let end = best.indexOf(Math.max(0, ...best));
    const ok = new Map(); for (let a = end; a >= 0 && idx.length; a = from[a]) ok.set(idx[a], cand[idx[a]]);
    if (ok.size >= Math.max(3, pages.length * 0.15)) {
      const keys = [...ok.keys()].sort((a, b) => a - b);
      pages.forEach((p, i) => {
        if (ok.has(i)) { p.label = String(ok.get(i)); return; }
        let m = -1, q = -1; for (const k of keys) { if (k < i) m = k; else if (k > i && q < 0) q = k; }
        const roman = p.folios.find(f => PG_ROMAN.test(f));
        if (m >= 0 && q >= 0) p.label = ok.get(q) - ok.get(m) === q - m ? String(ok.get(m) + (i - m)) : '';   // a gap or an insert: unsure, left out
        else if (m >= 0) p.label = String(ok.get(m) + (i - m));
        else if (q >= 0) p.label = ok.get(q) - (q - i) >= 1 ? String(ok.get(q) - (q - i)) : roman ? roman.toLowerCase() : '';
        else p.label = '';
      });
      source = 'from the page numbers in the margins';
    } else if (labels) {
      pages.forEach(p => { p.label = p.pdfLabel; });
      source = 'from the PDF\'s own page numbers (no printed ones were found)';
    } else {
      pages.forEach(p => { p.label = String(p.n); });
      source = 'from the PDF\'s own page numbers (no printed ones were found)';
    }
  }
  return { pages, source, textPages: pages.filter(p => p.words.length > 5).length, ocrPages: pages.filter(p => p.ocr).length };
}

// ------------------------------------------------------------------ the EPUB

/** The EPUB's text in reading order, every word with its place (file, text node, offset). */
async function pgReadEpub(data) {
  const zip = await JSZip.loadAsync(data);
  const cont = zip.file('META-INF/container.xml');
  if (!cont) throw new Error('This is not an EPUB (no META-INF/container.xml).');
  if (zip.file('META-INF/encryption.xml')) {
    const enc = await zip.file('META-INF/encryption.xml').async('string');
    if (/EncryptedData/.test(enc) && !/http:\/\/www\.idpf\.org\/2008\/embedding|http:\/\/ns\.adobe\.com\/pdf\/enc#RC/.test(enc)) throw new Error('This EPUB is copy-protected (DRM), so its text cannot be read.');
  }
  const opfPath = /full-path="([^"]+)"/.exec(await cont.async('string'))[1];
  const opfDir = opfPath.includes('/') ? opfPath.replace(/[^/]+$/, '') : '';
  const opfText = await zip.file(opfPath).async('string');
  const opf = new DOMParser().parseFromString(opfText, 'application/xml');
  const items = new Map();
  [...opf.getElementsByTagNameNS('*', 'item')].forEach(it => items.set(it.getAttribute('id'), {
    id: it.getAttribute('id'), href: it.getAttribute('href'), type: it.getAttribute('media-type') || '', props: it.getAttribute('properties') || '', el: it,
  }));
  // the book's text, in reading order (not the nav document: its contents list is not a page of the book)
  const spine = [...opf.getElementsByTagNameNS('*', 'itemref')].map(r => items.get(r.getAttribute('idref'))).filter(x => x && /html/.test(x.type) && !/\bnav\b/.test(x.props));
  const files = [], words = [];
  let existing = 0;
  for (const it of spine) {
    const path = pgJoin(opfDir, decodeURIComponent(it.href));
    const f = zip.file(path); if (!f) continue;
    const text = await f.async('string');
    let doc = new DOMParser().parseFromString(text, 'application/xhtml+xml'), html = false;
    if (doc.getElementsByTagName('parsererror').length) { doc = new DOMParser().parseFromString(text, 'text/html'); html = true; }
    // page markers already there (from another edition, or half done) are replaced
    [...doc.querySelectorAll('[role="doc-pagebreak"], span.pagenum')].concat([...doc.getElementsByTagName('*')].filter(e => /\bpagebreak\b/.test(e.getAttributeNS(PG_OPS, 'type') || e.getAttribute('epub:type') || '')))
      .forEach(e => { if (e.isConnected) { e.remove(); existing++; } });
    const fi = files.length;
    files.push({ path, doc, html, xmlDecl: /^\s*<\?xml/.test(text), changed: existing > 0 });
    const body = doc.getElementsByTagNameNS('*', 'body')[0] || doc.body || doc.documentElement;
    const tw = doc.createTreeWalker(body, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = tw.nextNode())) {
      const p = n.parentNode;
      if (p && p.closest && p.closest('script, style, head')) continue;
      const v = n.nodeValue;
      PG_TOKEN.lastIndex = 0;
      let m;
      while ((m = PG_TOKEN.exec(v))) { const w = pgWord(m[0]); if (w) words.push({ w, fi, node: n, off: m.index }); }
    }
  }
  const nav = [...items.values()].find(x => /\bnav\b/.test(x.props));
  const ncx = [...items.values()].find(x => x.type === 'application/x-dtbncx+xml');
  return { zip, opfPath, opfDir, opf, opfText, items, files, words, existing, version: (opf.documentElement.getAttribute('version') || '2.0'),
    navPath: nav ? pgJoin(opfDir, decodeURIComponent(nav.href)) : null, ncxPath: ncx ? pgJoin(opfDir, decodeURIComponent(ncx.href)) : null };
}

// ------------------------------------------------------------------ lining the two up

/** Where each printed page begins in the EPUB's words. → [{ label, pdfPage, e, conf, pdfStart, epubStart }] */
function pgAlign(pdf, epub) {
  const P = [], starts = [];
  pdf.pages.forEach(p => { if (p.label && p.words.length) starts.push({ label: p.label, pdfPage: p.n, pi: P.length }); P.push(...p.words); });
  const E = epub.words.map(x => x.w);
  if (!P.length) throw new Error('No text could be read from the PDF, not even by text recognition.');
  if (!E.length) throw new Error('The EPUB has no text.');
  // anchors: runs of six words found exactly once in both texts
  const N = 6, key = (A, i) => A.slice(i, i + N).join(' ');
  const cE = new Map(), cP = new Map();
  for (let i = 0; i + N <= E.length; i++) { const k = key(E, i); const v = cE.get(k); cE.set(k, v === undefined ? i : -1); }
  for (let i = 0; i + N <= P.length; i++) { const k = key(P, i); const v = cP.get(k); cP.set(k, v === undefined ? i : -1); }
  const pairs = [];
  for (const [k, pi] of cP) { if (pi < 0) continue; const ei = cE.get(k); if (ei !== undefined && ei >= 0) pairs.push([pi, ei]); }
  pairs.sort((a, b) => a[0] - b[0]);
  // the longest chain of anchors in the same order in both (longest increasing subsequence)
  const tails = [], prev = new Array(pairs.length), tailIdx = [];
  pairs.forEach(([, e], i) => {
    let lo = 0, hi = tails.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (tails[mid] < e) lo = mid + 1; else hi = mid; }
    tails[lo] = e; tailIdx[lo] = i; prev[i] = lo ? tailIdx[lo - 1] : -1;
  });
  const chain = []; for (let i = tailIdx[tails.length - 1]; i !== undefined && i >= 0; i = prev[i]) chain.unshift(pairs[i]);
  const cover = chain.length * N / Math.min(P.length, E.length);
  if (chain.length < 3 || cover < 0.05) throw new Error('The PDF and the EPUB do not seem to hold the same text (too little of it matches). Are they the same edition?');

  const out = []; let last = -1;
  for (const s of starts) {
    // the anchors around the page's first word, and where it should be between them
    let lo = 0, hi = chain.length - 1, a = -1;
    while (lo <= hi) { const mid = (lo + hi) >> 1; if (chain[mid][0] <= s.pi) { a = mid; lo = mid + 1; } else hi = mid - 1; }
    const A = a >= 0 ? chain[a] : null, B = a + 1 < chain.length ? chain[a + 1] : null;
    let est = A ? A[1] + (s.pi - A[0]) : B ? B[1] - (B[0] - s.pi) : 0;
    const min = Math.max(last + 1, A ? A[1] : 0), max = Math.min(E.length - 1, B ? B[1] : E.length - 1);
    est = Math.max(min, Math.min(max, est));
    // the best match of the page's first eight words, near the estimate
    const L = Math.min(8, P.length - s.pi);
    let best = est, bestScore = -1;
    for (let e = Math.max(min, est - 60); e <= Math.min(max, est + 60); e++) {
      let sc = 0; for (let j = 0; j < L; j++) sc += pgSim(P[s.pi + j], E[e + j]) * (j < 3 ? 1.5 : 1);
      sc -= Math.abs(e - est) * 0.004;
      if (sc > bestScore) { bestScore = sc; best = e; }
    }
    const full = L ? (Math.min(3, L) * 1.5 + Math.max(0, L - 3)) : 1;
    const conf = Math.max(0, Math.min(1, bestScore / full));
    out.push({ label: s.label, pdfPage: s.pdfPage, e: best, conf,
      pdfStart: P.slice(s.pi, s.pi + 10).join(' '), epubStart: E.slice(best, best + 10).join(' ') });
    last = best;
  }
  return { placements: out, anchors: chain.length, cover };
}

// ------------------------------------------------------------------ writing the EPUB

/** The EPUB with a page marker where each page begins, and a page list. → Blob */
async function pgWrite(epub, placements, opts) {
  const byNode = new Map();
  for (const pl of placements) {
    const w = epub.words[pl.e]; if (!w) continue;
    const list = byNode.get(w.node) || []; list.push({ pl, w }); byNode.set(w.node, list);
  }
  const used = new Set();
  const marks = [];   // [{ label, fi, id }] in reading order
  for (const [node, list] of byNode) {
    list.sort((a, b) => b.w.off - a.w.off);   // from the end of the text node, so earlier offsets stay right
    const f = epub.files[list[0].w.fi], doc = f.doc;
    for (const { pl, w } of list) {
      let id = 'page-' + String(pl.label).replace(/[^\w.-]/g, '_'); let k = 1;
      while (used.has(f.path + '#' + id) || doc.querySelector(`[id="${id}"]`)) id = 'page-' + String(pl.label).replace(/[^\w.-]/g, '_') + '-' + (++k);
      used.add(f.path + '#' + id);
      const mk = doc.createElementNS(PG_XHTML, 'span');
      mk.setAttribute('id', id);
      mk.setAttributeNS(PG_OPS, 'epub:type', 'pagebreak');
      mk.setAttribute('role', 'doc-pagebreak');
      mk.setAttribute('aria-label', pl.label);
      mk.setAttribute('title', pl.label);
      if (opts.showPages) {
        mk.setAttribute('class', 'pagenum');
        mk.setAttribute('style', 'font-size:.62em;font-weight:600;color:#6b6b6b;white-space:nowrap;padding:0 .25em;margin:0 .15em;border:1px solid #b9b9b9;border-radius:.3em;vertical-align:.15em;font-style:normal;text-indent:0');
        mk.textContent = (opts.label || '') + pl.label;
      }
      // a page that begins with a heading: the marker goes before the heading, not inside it
      const head = node.parentNode && node.parentNode.closest && node.parentNode.closest('h1, h2, h3, h4, h5, h6');
      if (head && w.off === 0) head.parentNode.insertBefore(mk, head);
      else if (w.off === 0) node.parentNode.insertBefore(mk, node);
      else node.parentNode.insertBefore(mk, node.splitText(w.off));
      f.changed = true;
      marks.push({ label: pl.label, fi: w.fi, id, e: pl.e });
    }
  }
  marks.sort((a, b) => a.e - b.e);

  const zip = epub.zip, outFiles = new Map();
  const ser = new XMLSerializer();
  for (const f of epub.files) {
    if (!f.changed) continue;
    let s = ser.serializeToString(f.doc);
    if (f.html || f.xmlDecl || !/^<\?xml/.test(s)) s = '<?xml version="1.0" encoding="utf-8"?>\n' + s.replace(/^<\?xml[^>]*\?>\s*/, '');
    outFiles.set(f.path, s);
  }

  // the page list in the nav document (made, for an EPUB 2, from its NCX)
  let navPath = epub.navPath, opfText = null;
  const listItems = navFile => marks.map(m => `<li><a href="${pgRelative(navFile, epub.files[m.fi].path)}#${m.id}">${wcEsc(m.label)}</a></li>`).join('');
  if (navPath) {
    const src = outFiles.get(navPath) || await zip.file(navPath).async('string');
    const nd = new DOMParser().parseFromString(src, 'application/xhtml+xml');
    [...nd.getElementsByTagNameNS('*', 'nav')].filter(n => /page-list/.test(n.getAttributeNS(PG_OPS, 'type') || n.getAttribute('epub:type') || '')).forEach(n => n.remove());
    const frag = new DOMParser().parseFromString(`<nav xmlns="${PG_XHTML}" xmlns:epub="${PG_OPS}" epub:type="page-list" id="page-list" hidden="hidden"><h2>Pages</h2><ol>${listItems(navPath)}</ol></nav>`, 'application/xhtml+xml');
    const body = nd.getElementsByTagNameNS('*', 'body')[0];
    body.appendChild(nd.importNode(frag.documentElement, true));
    outFiles.set(navPath, '<?xml version="1.0" encoding="utf-8"?>\n' + ser.serializeToString(nd).replace(/^<\?xml[^>]*\?>\s*/, ''));
  } else {
    // EPUB 2: a nav document with the contents (from the NCX) and the pages, and the package made EPUB 3
    navPath = pgJoin(epub.opfDir, 'ml-nav.xhtml');
    let toc = '';
    if (epub.ncxPath && zip.file(epub.ncxPath)) {
      const ncx = new DOMParser().parseFromString(await zip.file(epub.ncxPath).async('string'), 'application/xml');
      const ncxDir = epub.ncxPath.includes('/') ? epub.ncxPath.replace(/[^/]+$/, '') : '';
      const walk = el => {
        const pts = [...el.children].filter(c => c.localName === 'navPoint');
        if (!pts.length) return '';
        return '<ol>' + pts.map(pt => {
          const label = (pt.getElementsByTagNameNS('*', 'text')[0] || {}).textContent || '';
          const srcAttr = (pt.getElementsByTagNameNS('*', 'content')[0] || { getAttribute: () => '' }).getAttribute('src') || '';
          const [file, frag] = srcAttr.split('#');
          const href = pgRelative(navPath, pgJoin(ncxDir, decodeURIComponent(file))) + (frag ? '#' + frag : '');
          return `<li><a href="${wcEsc(href)}">${wcEsc(label.trim() || 'Section')}</a>${walk(pt)}</li>`;
        }).join('') + '</ol>';
      };
      const map = ncx.getElementsByTagNameNS('*', 'navMap')[0];
      toc = map ? walk(map) : '';
    }
    if (!toc) toc = `<ol>${epub.files.map((f, i) => `<li><a href="${pgRelative(navPath, f.path)}">Part ${i + 1}</a></li>`).join('')}</ol>`;
    outFiles.set(navPath, `<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n<html xmlns="${PG_XHTML}" xmlns:epub="${PG_OPS}"><head><meta charset="utf-8"/><title>Contents</title></head><body>`
      + `<nav epub:type="toc" id="toc"><h1>Contents</h1>${toc}</nav><nav epub:type="page-list" id="page-list" hidden="hidden"><h2>Pages</h2><ol>${listItems(navPath)}</ol></nav></body></html>`);
    const opf = epub.opf, pkg = opf.documentElement, OPF = pkg.namespaceURI;
    pkg.setAttribute('version', '3.0');
    const manifest = opf.getElementsByTagNameNS('*', 'manifest')[0];
    const it = opf.createElementNS(OPF, 'item');
    it.setAttribute('id', 'ml-nav'); it.setAttribute('href', pgRelative(epub.opfPath, navPath)); it.setAttribute('media-type', 'application/xhtml+xml'); it.setAttribute('properties', 'nav');
    manifest.appendChild(it);
    const meta = opf.getElementsByTagNameNS('*', 'metadata')[0];
    // EPUB 3 wants a modified date, and has no opf:role / opf:file-as attributes
    [...meta.getElementsByTagNameNS('*', '*')].forEach(el => [...el.attributes].forEach(a => { if (a.namespaceURI === OPF && a.localName !== 'id') el.removeAttributeNode(a); }));
    if (![...meta.getElementsByTagNameNS('*', 'meta')].some(m => m.getAttribute('property') === 'dcterms:modified')) {
      const mod = opf.createElementNS(OPF, 'meta'); mod.setAttribute('property', 'dcterms:modified');
      mod.textContent = new Date().toISOString().replace(/\.\d+Z$/, 'Z'); meta.appendChild(mod);
    }
    opfText = '<?xml version="1.0" encoding="utf-8"?>\n' + ser.serializeToString(opf).replace(/^<\?xml[^>]*\?>\s*/, '');
  }
  if (opfText) outFiles.set(epub.opfPath, opfText);

  // the new EPUB: mimetype first and uncompressed, everything else as it was or as changed
  const out = new JSZip();
  out.file('mimetype', 'application/epub+zip', { compression: 'STORE' });
  const names = []; zip.forEach((p, f) => { if (!f.dir && p !== 'mimetype') names.push(p); });
  for (const p of names) out.file(p, outFiles.has(p) ? outFiles.get(p) : await zip.file(p).async('uint8array'));
  for (const [p, s] of outFiles) if (!names.includes(p)) out.file(p, s);
  return out.generateAsync({ type: 'blob', mimeType: 'application/epub+zip', compression: 'DEFLATE', compressionOptions: { level: 6 } });
}

/** The whole job: read both, line them up. → { pdf, epub, align } (write with pgWrite after looking) */
async function pgPaginate(pdfData, epubData, opts, progress = () => {}) {
  progress('Reading the EPUB…');
  const epub = await pgReadEpub(epubData);
  // scanned pages are read in the book's language (both models when it is neither or unknown)
  const lang = ((epub.opf.getElementsByTagNameNS('*', 'language')[0] || {}).textContent || '').trim().toLowerCase();
  const ocrLangs = /^(nb|nn|no)\b/.test(lang) ? ['nor'] : /^en\b/.test(lang) ? ['eng'] : ['nor', 'eng'];
  progress('Reading the PDF…');
  const pdf = await pgReadPdf(pdfData, Object.assign({ ocrLangs }, opts), progress);
  progress('Lining up the texts…');
  const align = pgAlign(pdf, epub);
  return { pdf, epub, align };
}

// ------------------------------------------------------------------ the dialog

/** The two kinds of conversion, as tabs at the top of either dialog. */
function convertTabs(active, close) {
  const tab = (key, label, open) => h('button', { class: 'chip' + (active === key ? ' on' : ''), onclick: () => { if (active !== key) { close(); open(); } } }, label);
  return h('div', { class: 'wc-tabs' }, tab('web', 'Web pages → EPUB or PDF', () => convertDialog()), tab('pages', 'Page numbers from a PDF → EPUB', () => paginateDialog()));
}

function paginateDialog() {
  const opts = {
    showPages: localGet('ml.pg.show', '0') === '1', label: localGet('ml.wc.label', 'p. '),
    toLibrary: localGet('ml.wc.lib', '1') === '1', download: localGet('ml.wc.dl', '0') === '1',
    category: 'BOOK', pdfPage: '', printedPage: '',
  };
  let pdfFile = null, epubFile = null, result = null, stop = { stop: false };
  modal((box, close) => {
    box.style.width = '760px';
    const status = h('div', { class: 'muted wc-status' });
    const out = h('div', { class: 'pg-result' });
    const go = h('button', { class: 'btn fill', disabled: true }, 'Find the pages');
    const save = h('button', { class: 'btn fill', hidden: true }, 'Save the EPUB');
    const slots = h('div', { class: 'pg-slots' });
    const pick = (accept, set) => { const i = h('input', { type: 'file', accept, hidden: true }); i.addEventListener('change', () => { if (i.files[0]) set(i.files[0]); i.value = ''; }); return i; };
    const pdfPick = pick('.pdf,application/pdf', f => { pdfFile = f; result = null; draw(); });
    const epubPick = pick('.epub,application/epub+zip', f => { epubFile = f; result = null; draw(); });
    const addFiles = fs => {
      for (const f of fs) { if (/\.pdf$/i.test(f.name)) pdfFile = f; else if (/\.epub$/i.test(f.name)) epubFile = f; }
      if (!fs.some(f => /\.(pdf|epub)$/i.test(f.name))) toast('Choose a PDF and an EPUB.');
      result = null; draw();
    };
    const optsBox = h('div');
    const chip = (label, on, fn, tip) => h('button', { class: 'chip' + (on() ? ' on' : ''), title: tip || null, onclick: () => { fn(); draw(); } }, label);
    const num = (val, set, ph) => { const i = h('input', { value: val, placeholder: ph, style: { width: '56px' }, inputmode: 'numeric' }); i.addEventListener('input', () => { set(i.value.trim()); result = null; drawResult(); }); return i; };
    const draw = () => {
      clear(slots).append(
        h('div', { class: 'pg-slot' + (pdfFile ? ' on' : ''), onclick: () => pdfPick.click() }, h('b', null, 'PDF with page numbers'), h('span', null, pdfFile ? pdfFile.name : 'Choose, or drop here')),
        h('div', { class: 'pg-slot' + (epubFile ? ' on' : ''), onclick: () => epubPick.click() }, h('b', null, 'EPUB of the same book'), h('span', null, epubFile ? epubFile.name : 'Choose, or drop here')));
      clear(optsBox).append(
        h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'Printed pages'),
          h('span', { class: 'muted', style: { fontSize: '12px' } }, 'Found in the PDF. If they come out wrong: PDF page'), num(opts.pdfPage, v => { opts.pdfPage = v; }, '12'),
          h('span', { class: 'muted', style: { fontSize: '12px' } }, 'is printed page'), num(opts.printedPage, v => { opts.printedPage = v; }, '1')),
        h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'In the text'),
          chip('Show page numbers', () => opts.showPages, () => { opts.showPages = !opts.showPages; localSet('ml.pg.show', opts.showPages ? '1' : '0'); }, 'Off: the pages are marked invisibly; the reader still knows them (go to page, notes, citations)'),
          opts.showPages ? (() => { const i = h('input', { value: opts.label, style: { width: '60px' } }); i.addEventListener('input', () => { opts.label = i.value; localSet('ml.wc.label', i.value); }); return i; })() : null),
        h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'Then'),
          chip('Add to the library', () => opts.toLibrary, () => { opts.toLibrary = !opts.toLibrary; localSet('ml.wc.lib', opts.toLibrary ? '1' : '0'); }),
          h('span', { class: 'spacer' }),
          chip('Download', () => opts.download, () => { opts.download = !opts.download; localSet('ml.wc.dl', opts.download ? '1' : '0'); })));
      go.disabled = !(pdfFile && epubFile);
      drawResult();
    };
    const drawResult = () => {
      clear(out);
      save.hidden = !result;
      go.textContent = result ? 'Find again' : 'Find the pages';
      if (!result) return;
      const pl = result.align.placements;
      const sure = pl.filter(p => p.conf >= 0.75), unsure = pl.filter(p => p.conf >= 0.4 && p.conf < 0.75), lost = pl.filter(p => p.conf < 0.4);
      out.append(
        h('div', { class: 'pg-sum' },
          h('b', null, `${pl.length} printed pages${pl.length ? ` (pp. ${pl[0].label}–${pl[pl.length - 1].label})` : ''}`),
          h('span', null, ` · numbers ${result.pdf.source}`),
          h('div', null, `${sure.length} placed with confidence · ${unsure.length} uncertain · ${lost.length} not found (put where they most likely begin)`),
          result.epub.existing ? h('div', { class: 'muted' }, `The EPUB had ${result.epub.existing} page markers already; they are replaced.`) : null,
          result.pdf.ocrPages ? h('div', { class: 'muted' }, `${result.pdf.ocrPages} scanned ${result.pdf.ocrPages === 1 ? 'page was' : 'pages were'} read with text recognition (OCR).`) : null),
        unsure.length + lost.length ? h('div', { class: 'pg-list' }, [...unsure, ...lost].sort((a, b) => a.e - b.e).slice(0, 60).map(p =>
          h('div', { class: 'pg-item' + (p.conf < 0.4 ? ' lost' : '') }, h('b', null, 'p. ' + p.label),
            h('span', null, 'PDF: ', h('i', null, p.pdfStart)), h('span', null, 'EPUB: ', h('i', null, p.epubStart))))) : null);
    };
    go.addEventListener('click', async () => {
      go.disabled = true; result = null; drawResult();
      try {
        const [pd, ed] = await Promise.all([pdfFile.arrayBuffer(), epubFile.arrayBuffer()]);
        stop = { stop: false };
        result = await pgPaginate(pd, ed, Object.assign({ stop }, opts), m => { status.textContent = m; });
        status.textContent = '';
      } catch (e) { console.error(e); status.textContent = e.message || String(e); }
      go.disabled = false; drawResult();
    });
    save.addEventListener('click', async () => {
      save.disabled = true;
      try {
        status.textContent = 'Writing the EPUB…';
        const blob = await pgWrite(result.epub, result.align.placements, opts);
        const name = epubFile.name.replace(/\.epub$/i, '') + ' (with page numbers).epub';
        const file = new File([blob], name, { type: 'application/epub+zip' });
        if (opts.download) { const a = h('a', { href: URL.createObjectURL(file), download: name }); document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 60000); }
        if (opts.toLibrary) { status.textContent = 'Adding to the library…'; await wcToLibrary([file], opts.category); }
        toast(`Made “${name}” with ${result.align.placements.length} printed pages`, 4000);
        close();
      } catch (e) { console.error(e); status.textContent = 'Could not write the EPUB: ' + (e.message || e); save.disabled = false; }
    });
    box.append(h('h2', null, 'Convert books'), convertTabs('pages', close),
      h('p', { class: 'muted', style: { fontSize: '13px' } }, 'The same book as a PDF with its printed page numbers and as an EPUB without them: the EPUB gets the printed page numbers, so notes, citations and “go to page” follow the book. Both must be the same edition. Scanned PDFs are read with text recognition (OCR), a few seconds a page.'),
      pdfPick, epubPick, slots, optsBox, status, out,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), go, save));
    draw();
    wcDropTarget = addFiles;
  }, () => { wcDropTarget = null; stop.stop = true; });   // closing stops a reading in progress
}
