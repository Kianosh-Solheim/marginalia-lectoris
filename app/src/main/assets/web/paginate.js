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
    // a chapter's first page often has no label of its own (no number was printed on it): the number between
    // neighbours that agree, or the one before the first numbered page
    const num = l => /^\d+$/.test(l || '') ? parseInt(l, 10) : null;
    for (let i = pages.length - 2; i >= 0; i--) {
      if (pages[i].label) continue;
      const next = num(pages[i + 1].label), before = i ? num(pages[i - 1].label) : null;
      if (next != null && next > 1 && (before == null ? !pages.slice(0, i).some(p => num(p.label) != null) : before === next - 2)) pages[i].label = String(next - 1);
    }
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
  const spine = [...opf.getElementsByTagNameNS('*', 'itemref')].map((r, i) => Object.assign({}, items.get(r.getAttribute('idref')), { spineIndex: i, idref: r.getAttribute('idref') }))
    .filter(x => x.href && /html/.test(x.type) && !/\bnav\b/.test(x.props));
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
    files.push({ path, doc, html, xmlDecl: /^\s*<\?xml/.test(text), changed: existing > 0, spineIndex: it.spineIndex, idref: it.idref });
    const body = doc.getElementsByTagNameNS('*', 'body')[0] || doc.body || doc.documentElement;
    const tw = doc.createTreeWalker(body, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = tw.nextNode())) {
      const p = n.parentNode;
      if (p && p.closest && p.closest('script, style, head')) continue;
      const v = n.nodeValue;
      PG_TOKEN.lastIndex = 0;
      let m;
      while ((m = PG_TOKEN.exec(v))) { const w = pgWord(m[0]); if (w) words.push({ w, fi, node: n, off: m.index, len: m[0].length }); }
    }
  }
  const nav = [...items.values()].find(x => /\bnav\b/.test(x.props));
  const ncx = [...items.values()].find(x => x.type === 'application/x-dtbncx+xml');
  return { zip, opfPath, opfDir, opf, opfText, items, files, words, existing, version: (opf.documentElement.getAttribute('version') || '2.0'),
    navPath: nav ? pgJoin(opfDir, decodeURIComponent(nav.href)) : null, ncxPath: ncx ? pgJoin(opfDir, decodeURIComponent(ncx.href)) : null };
}

// ------------------------------------------------------------------ lining the two up

/** Runs of n words found exactly once in both A (from a to a2) and B (from b to b2), the longest chain of them in
 *  the same order in both (longest increasing subsequence). → [[i in A, j in B]] */
function pgAnchors(A, a, a2, B, b, b2, n) {
  const key = (X, i) => X.slice(i, i + n).join(' ');
  const cB = new Map(), cA = new Map();
  for (let j = b; j + n <= b2; j++) { const k = key(B, j); cB.set(k, cB.has(k) ? -1 : j); }
  for (let i = a; i + n <= a2; i++) { const k = key(A, i); cA.set(k, cA.has(k) ? -1 : i); }
  const pairs = [];
  for (const [k, i] of cA) { if (i < 0) continue; const j = cB.get(k); if (j !== undefined && j >= 0) pairs.push([i, j]); }
  pairs.sort((x, y) => x[0] - y[0]);
  const tails = [], prev = new Array(pairs.length), tailIdx = [];
  pairs.forEach(([, j], i) => {
    let lo = 0, hi = tails.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (tails[mid] < j) lo = mid + 1; else hi = mid; }
    tails[lo] = j; tailIdx[lo] = i; prev[i] = lo ? tailIdx[lo - 1] : -1;
  });
  const chain = []; for (let i = tailIdx[tails.length - 1]; i !== undefined && i >= 0; i = prev[i]) chain.unshift(pairs[i]);
  return chain;
}

/** Every PDF word to its EPUB word: M[i] = j, or -1 where the PDF word is not in the EPUB (a running head the
 *  text recognition misread, specks on a blank page, a footnote); Q[i] how well the two words agree (0–1). */
function pgWordMap(P, E) {
  const M = new Int32Array(P.length).fill(-1), Q = new Float32Array(P.length);
  // between two pairs of words already lined up: the best alignment, word by word (Needleman–Wunsch)
  const fill = (a, a2, b, b2, depth) => {   // P[a..a2) against E[b..b2)
    const n = a2 - a, m = b2 - b;
    if (n <= 0 || m <= 0) return;
    if (n * m > 400000) {
      // too long to compare word by word: find shorter runs that agree, and fill between them
      const N = depth === 0 ? 4 : 3;
      const chain = depth < 2 ? pgAnchors(P, a, a2, E, b, b2, N) : [];
      if (chain.length) {
        let pa = a, pb = b;
        for (const [i, j] of chain) {
          if (i < pa || j < pb) continue;
          fill(pa, i, pb, j, depth + 1);
          for (let k = 0; k < N && i + k < a2 && j + k < b2; k++) if (M[i + k] < 0 && (k === 0 || i + k >= pa)) { M[i + k] = j + k; Q[i + k] = 1; }
          pa = i + N; pb = j + N;
        }
        fill(pa, a2, pb, b2, depth + 1);
        return;
      }
      // nothing agrees at all: spread the one over the other, as a guess
      for (let i = a; i < a2; i++) { M[i] = b + Math.min(m - 1, Math.floor((i - a) * m / n)); Q[i] = 0.1; }
      return;
    }
    const G = 0.7, W = m + 1;
    const D = new Float32Array((n + 1) * W), T = new Uint8Array((n + 1) * W);   // T: 1 = pair, 2 = PDF word left over, 3 = EPUB word left over
    for (let i = 1; i <= n; i++) { D[i * W] = i * G; T[i * W] = 2; }
    for (let j = 1; j <= m; j++) { D[j] = j * G; T[j] = 3; }
    for (let i = 1; i <= n; i++) for (let j = 1; j <= m; j++) {
      const d = D[(i - 1) * W + j - 1] + 1 - pgSim(P[a + i - 1], E[b + j - 1]);
      const u = D[(i - 1) * W + j] + G, l = D[i * W + j - 1] + G;
      if (d <= u && d <= l) { D[i * W + j] = d; T[i * W + j] = 1; }
      else if (u <= l) { D[i * W + j] = u; T[i * W + j] = 2; }
      else { D[i * W + j] = l; T[i * W + j] = 3; }
    }
    for (let i = n, j = m; i > 0 || j > 0;) {
      const t = T[i * W + j];
      if (t === 1) { M[a + i - 1] = b + j - 1; Q[a + i - 1] = Math.max(0.3, pgSim(P[a + i - 1], E[b + j - 1])); i--; j--; }
      else if (t === 2) i--; else j--;
    }
  };
  const chain = pgAnchors(P, 0, P.length, E, 0, E.length, 6);
  let pa = 0, pb = 0;
  for (const [i, j] of chain) {
    if (i < pa || j < pb) {   // overlaps the run before: only the words past it
      if (i + 6 <= pa || j + 6 <= pb || i - pa !== j - pb) continue;
    } else fill(pa, i, pb, j, 0);
    for (let k = Math.max(0, pa - i); k < 6; k++) { M[i + k] = j + k; Q[i + k] = 1; }
    pa = i + 6; pb = j + 6;
  }
  fill(pa, P.length, pb, E.length, 0);
  return { M, Q, anchors: chain.length, cover: chain.length ? Math.min(1, [...M].filter(x => x >= 0).length / Math.min(P.length, E.length)) : 0 };
}

/** Where each printed page begins in the EPUB's words. → [{ label, pdfPage, e, conf, blank, pdfStart, epubStart }]
 *  A page begins right after the EPUB word that the last PDF word before it was lined up with. A page with no
 *  words of the EPUB on it (a blank page, or one with only specks or a picture) begins where the next one does. */
function pgAlign(pdf, epub) {
  const P = [], starts = [];
  pdf.pages.forEach(p => { if (p.label) starts.push({ label: p.label, pdfPage: p.n, pi: P.length, n: p.words.length }); P.push(...p.words); });
  const E = epub.words.map(x => x.w);
  if (!P.length) throw new Error('No text could be read from the PDF, not even by text recognition.');
  if (!E.length) throw new Error('The EPUB has no text.');
  const { M, Q, anchors, cover } = pgWordMap(P, E);
  if (anchors < 3 || cover < 0.05) throw new Error('The PDF and the EPUB do not seem to hold the same text (too little of it matches). Are they the same edition?');

  // the EPUB word after the last one lined up before each PDF word
  const after = new Int32Array(P.length + 1);
  let cur = 0;
  for (let i = 0; i <= P.length; i++) { after[i] = cur; if (i < P.length && M[i] >= 0) cur = M[i] + 1; }
  // the last page with words of the EPUB on it: blank pages after it (endpapers) are left out
  let lastText = -1;
  starts.forEach((s, k) => { for (let i = s.pi; i < s.pi + s.n; i++) if (M[i] >= 0) { lastText = k; break; } });

  const out = [];
  starts.forEach((s, k) => {
    if (k > lastText) return;
    const lined = []; for (let i = s.pi; i < s.pi + s.n && lined.length < 8; i++) if (M[i] >= 0) lined.push(i);
    let e = Math.min(after[s.pi], E.length - 1), conf, blank = false;
    if (!lined.length) {
      // blank, or only specks or a picture: sure when the text runs on unbroken in the EPUB from the page before
      // to the page after; when EPUB words are left over in between, this page may hold them, unread
      blank = true;
      let nx = s.pi + s.n; while (nx < P.length && M[nx] < 0) nx++;
      conf = nx >= P.length || M[nx] === after[s.pi] ? 1 : 0.5;
    } else {
      conf = lined.reduce((t, i) => t + Q[i], 0) / lined.length;
      // EPUB words between the page before and this page's first word that no PDF word matched: a note number
      // or the end of a paragraph the PDF lost go with the page before, a heading with this page; unsure, ask
      const first = M[lined[0]];
      if (first > e) {
        const blk = i => { const n = epub.words[i] && epub.words[i].node.parentNode; return n && n.closest ? n.closest('p, li, h1, h2, h3, h4, h5, h6, blockquote, td, dd, figcaption, div') : null; };
        const left = E.slice(e, first);
        const sameBefore = e > 0 && blk(e) === blk(e - 1), sameAfter = blk(first - 1) === blk(first);
        if (left.every(w => /^\d{1,3}$/.test(w)) && e > 0) { e = first; conf = Math.min(conf, 0.9); }
        else if (sameBefore && !sameAfter) { e = first; conf = Math.min(conf, 0.7); }
        else if (!(!sameBefore && sameAfter)) conf = Math.min(conf, 0.6);
      }
    }
    out.push({ label: s.label, pdfPage: s.pdfPage, e, conf, blank,
      pdfStart: blank ? (s.n ? '(no text of the book: blank, specks or a picture)' : '(blank page)') : lined.map(i => P[i]).slice(0, 10).join(' '),
      epubStart: E.slice(e, e + 10).join(' ') });
  });
  return { placements: out, anchors, cover };
}

// ------------------------------------------------------------------ writing the EPUB

/** The EPUB with a page marker where each page begins, and a page list. → Blob */
async function pgWrite(epub, placements, opts) {
  const byNode = new Map();
  for (const pl of placements) {
    if (pl.skip) continue;   // left out in the check
    const w = epub.words[pl.e]; if (!w) continue;
    const list = byNode.get(w.node) || []; list.push({ pl, w }); byNode.set(w.node, list);
  }
  const used = new Set();
  const marks = [];   // [{ label, fi, id }] in reading order
  placements.forEach((pl, k) => { pl._k = k; });
  for (const [node, list] of byNode) {
    // from the end of the text node, so earlier offsets stay right; pages that begin at the same word (a blank
    // page and the one after it) go in as a row, the last first, each before the one put in before it
    list.sort((a, b) => (b.w.off - a.w.off) || (b.pl._k - a.pl._k));
    const f = epub.files[list[0].w.fi], doc = f.doc;
    let prevMk = null, prevOff = -1;
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
      if (prevMk && prevOff === w.off) prevMk.parentNode.insertBefore(mk, prevMk);
      else if (head && w.off === 0) head.parentNode.insertBefore(mk, head);
      else if (w.off === 0) node.parentNode.insertBefore(mk, node);
      else node.parentNode.insertBefore(mk, node.splitText(w.off));
      prevMk = mk; prevOff = w.off;
      f.changed = true;
      marks.push({ label: pl.label, fi: w.fi, id, e: pl.e, k: pl._k });
    }
  }
  marks.sort((a, b) => (a.e - b.e) || (a.k - b.k));
  epub.marks = marks;   // for the page map

  const zip = epub.zip, outFiles = new Map();
  const ser = new XMLSerializer();
  if (!epub.navPath) {
    // an EPUB 2 becomes an EPUB 3: table-cell alignment from old XHTML is no longer allowed, so it becomes a style
    for (const f of epub.files) for (const el of [...f.doc.querySelectorAll('[valign]')]) {
      el.setAttribute('style', ((el.getAttribute('style') || '').replace(/;?\s*$/, '') + (el.getAttribute('style') ? '; ' : '') + 'vertical-align: ' + el.getAttribute('valign')).trim());
      el.removeAttribute('valign'); f.changed = true;
    }
  }
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

// ------------------------------------------------------------------ notes in the EPUB, moved with it

/** Where a note stands in the EPUB as read (before markers are put in): a live Range — markers put in
    later move it along — found by its EPUB place (CFI), or else by its quote. */
function pgNoteRange(epub, note) {
  const qWords = ((note.highlightedText || '').match(PG_TOKEN) || []).map(pgWord).filter(Boolean);
  const fits = r => { const t = (r.toString().match(PG_TOKEN) || []).map(pgWord); return !qWords.length || t.slice(0, 3).join(' ') === qWords.slice(0, 3).join(' '); };
  if (note.epubCfi && typeof ePub !== 'undefined' && ePub.CFI) {
    try {
      const cfi = new ePub.CFI(note.epubCfi);
      const fi = epub.files.findIndex(x => x.spineIndex === cfi.spinePos);
      if (fi >= 0) { const r = cfi.toRange(epub.files[fi].doc); if (r && !r.collapsed && fits(r)) return { range: r, fi, how: 'place' }; }
    } catch (_) {}
  }
  if (qWords.length < 2) return null;
  const E = epub.words, head = qWords.slice(0, Math.min(8, qWords.length));
  let s = -1;
  search: for (let i = 0; i + head.length <= E.length; i++) { for (let j = 0; j < head.length; j++) if (E[i + j].w !== head[j]) continue search; s = i; break; }
  if (s < 0) return null;
  let e = Math.min(E.length - 1, s + qWords.length - 1);
  const lastW = qWords[qWords.length - 1];
  for (let k = Math.max(s, e - 6); k <= Math.min(E.length - 1, e + 6); k++) if (E[k].w === lastW) { e = k; break; }
  while (e > s && E[e].fi !== E[s].fi) e--;   // a quote does not run over into the next file
  const a = E[s], b = E[e];
  const r = a.node.ownerDocument.createRange(); r.setStart(a.node, a.off); r.setEnd(b.node, b.off + b.len);
  return { range: r, fi: a.fi, how: 'quote' };
}
/** After the markers are in: the note's new EPUB place (CFI) and its printed page(s), "45" or "45–46". */
function pgNoteAfter(epub, nr) {
  const f = epub.files[nr.fi], doc = f.doc, range = nr.range;
  const label = m => m.getAttribute('aria-label') || m.getAttribute('title') || '';
  let first = '', last = '';
  const inFile = [...doc.querySelectorAll('[role="doc-pagebreak"]')];
  for (const m of inFile) {
    let cp; try { cp = range.comparePoint(m, 0); } catch (_) { continue; }
    if (cp < 0) first = label(m);
    else if (cp === 0) {
      // a marker at the very start of the note: the note begins on that page (and the place begins after it)
      const pre = doc.createRange(); pre.setStart(range.startContainer, range.startOffset); pre.setEndBefore(m);
      if (!pre.toString().trim()) { first = label(m); range.setStartAfter(m); } else last = label(m);
    }
  }
  for (let k = nr.fi - 1; k >= 0 && !first; k--) {   // no marker before it in its file: the last of an earlier one
    const ms = epub.files[k].doc.querySelectorAll('[role="doc-pagebreak"]');
    if (ms.length) first = label(ms[ms.length - 1]);
  }
  let cfi = '';
  try { cfi = new ePub.CFI(range, `/6/${(f.spineIndex + 1) * 2}[${f.idref}]`).toString(); } catch (_) {}
  return { cfi, label: first && last && last !== first ? `${first}–${last}` : (last || first) };
}
/** The page map kept with the item: printed page ↔ PDF page ↔ place in the EPUB (and the words read on scanned pages). */
function pgPageMap(result, src) {
  return {
    version: 1, made: new Date().toISOString(), pdf: src.pdf, epub: src.epub, source: result.pdf.source,
    pages: (result.epub.marks || []).map(m => {
      const p = result.align.placements.find(q => q.label === m.label && !q.skip);
      return { label: m.label, pdfPage: p ? p.pdfPage : null, href: result.epub.files[m.fi].path + '#' + m.id, hand: !!(p && p.hand) };
    }),
    ocr: result.pdf.pages.filter(p => p.ocr).map(p => ({ pdfPage: p.n, words: p.lines.flatMap(l => (l.words || []).map(w => [w.t, ...w.box.map(v => Math.round(v * 10) / 10)])) })),
  };
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
    category: 'BOOK', pdfPage: '', printedPage: '', epubMode: localGet('ml.pg.mode', 'replace'),
  };
  // A file is a File (chosen or dropped) or one of an item's files: { lib: true, docId, rel, name, title, main }
  const bytesOf = async f => {
    if (!f.lib) return f.arrayBuffer();
    const r = await fetch(`/api/doc/${f.docId}/files?rel=${encodeURIComponent(f.rel)}`, { credentials: 'same-origin' });
    if (!r.ok) throw new Error(`Could not fetch “${f.name}” from the tablet (${r.status}).`);
    return r.arrayBuffer();
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
    // ---- from the library: a book's own PDF and EPUB
    const libBox = h('div', { class: 'pg-lib' });
    const libQ = h('input', { placeholder: 'Find a book in the library…', type: 'search' });
    const libHits = h('div', { class: 'pg-hits' });
    const pickItem = async d => {
      clear(libHits); libQ.value = d.title;
      try {
        const fl = await api(`doc/${d.id}/files`);
        const files = (fl.files || []).filter(x => !x.dir && x.readable);
        const pick = kind => files.filter(x => x.kind === kind).sort((a, b) => b.main - a.main)[0];
        const pf = pick('PDF'), ef = pick('EPUB');
        if (pf) pdfFile = { lib: true, docId: d.id, rel: pf.rel, name: pf.name, title: d.title, main: pf.main };
        if (ef) epubFile = { lib: true, docId: d.id, rel: ef.rel, name: ef.name, title: d.title, main: ef.main };
        if (!pf || !ef) toast(`“${d.title}” has ${pf ? 'no EPUB' : ef ? 'no PDF' : 'neither a PDF nor an EPUB'}: choose ${!pf && !ef ? 'them' : 'it'} below.`, 4000);
      } catch (e) { toast(e.message || String(e)); }
      result = null; draw();
    };
    libQ.addEventListener('input', () => {
      const q = libQ.value.trim().toLowerCase();
      clear(libHits);
      if (q.length < 2) return;
      const docs = ((typeof state !== 'undefined' && state.docs) || []).filter(d => !/^(FOLDER|ZOTERO)$/i.test(d.fileType || '') && (d.title || '').toLowerCase().includes(q)).slice(0, 8);
      libHits.append(...(docs.length ? docs.map(d => h('div', { class: 'pg-hit', onclick: () => pickItem(d) }, h('b', null, d.title), h('span', { class: 'muted' }, [d.author, d.fileType].filter(Boolean).join(' · '))))
        : [h('div', { class: 'pg-hit muted' }, 'No book by that name.')]));
    });
    libBox.append(h('span', { class: 'label' }, 'From the library'), h('div', { class: 'pg-libq' }, libQ, libHits));
    const chip = (label, on, fn, tip) => h('button', { class: 'chip' + (on() ? ' on' : ''), title: tip || null, onclick: () => { fn(); draw(); } }, label);
    const num = (val, set, ph) => { const i = h('input', { value: val, placeholder: ph, style: { width: '56px' }, inputmode: 'numeric' }); i.addEventListener('input', () => { set(i.value.trim()); result = null; drawResult(); }); return i; };
    const draw = () => {
      clear(slots).append(
        h('div', { class: 'pg-slot' + (pdfFile ? ' on' : ''), onclick: () => pdfPick.click() }, h('b', null, 'PDF with page numbers'), h('span', null, pdfFile ? (pdfFile.lib ? `${pdfFile.name} · in the library` : pdfFile.name) : 'Choose, or drop here')),
        h('div', { class: 'pg-slot' + (epubFile ? ' on' : ''), onclick: () => epubPick.click() }, h('b', null, 'EPUB of the same book'), h('span', null, epubFile ? (epubFile.lib ? `${epubFile.name} · in the library` : epubFile.name) : 'Choose, or drop here')));
      clear(optsBox).append(
        h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'Printed pages'),
          h('span', { class: 'muted', style: { fontSize: '12px' } }, 'Found in the PDF. If they come out wrong: PDF page'), num(opts.pdfPage, v => { opts.pdfPage = v; }, '12'),
          h('span', { class: 'muted', style: { fontSize: '12px' } }, 'is printed page'), num(opts.printedPage, v => { opts.printedPage = v; }, '1')),
        h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'In the text'),
          chip('Show page numbers', () => opts.showPages, () => { opts.showPages = !opts.showPages; localSet('ml.pg.show', opts.showPages ? '1' : '0'); }, 'Off: the pages are marked invisibly; the reader still knows them (go to page, notes, citations)'),
          opts.showPages ? (() => { const i = h('input', { value: opts.label, style: { width: '60px' } }); i.addEventListener('input', () => { opts.label = i.value; localSet('ml.wc.label', i.value); }); return i; })() : null),
        epubFile && epubFile.lib
          ? h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'Then'),
            chip('Replace the EPUB in the book', () => opts.epubMode === 'replace', () => { opts.epubMode = 'replace'; localSet('ml.pg.mode', 'replace'); }, 'The new EPUB takes the old one\'s place (the old one is kept in _to_delete); its notes move with it and get their printed pages'),
            chip('Add it as another edition', () => opts.epubMode === 'edition', () => { opts.epubMode = 'edition'; localSet('ml.pg.mode', 'edition'); }, 'The old EPUB and its notes stay as they are; the new one is added to the book beside it'),
            h('span', { class: 'spacer' }),
            chip('Download', () => opts.download, () => { opts.download = !opts.download; localSet('ml.wc.dl', opts.download ? '1' : '0'); }))
          : h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'Then'),
            chip('Add to the library', () => opts.toLibrary, () => { opts.toLibrary = !opts.toLibrary; localSet('ml.wc.lib', opts.toLibrary ? '1' : '0'); }),
            h('span', { class: 'spacer' }),
            chip('Download', () => opts.download, () => { opts.download = !opts.download; localSet('ml.wc.dl', opts.download ? '1' : '0'); })));
      save.textContent = epubFile && epubFile.lib ? (opts.epubMode === 'replace' ? 'Replace the EPUB' : 'Add the EPUB') : 'Save the EPUB';
      go.disabled = !(pdfFile && epubFile);
      drawResult();
    };
    // ---- check and correct: every page, or the uncertain ones; a page opens beside its PDF page
    let filter = 'unsure', openAt = null;
    const stateOf = p => p.skip ? 'out' : p.hand ? 'hand' : p.blank && p.conf >= 0.75 ? 'blank' : p.conf >= 0.75 ? 'sure' : p.conf >= 0.4 ? 'unsure' : 'lost';
    const MARK = { sure: ['✓', 'Placed with confidence'], unsure: ['?', 'Uncertain'], lost: ['!', 'Not found: put where it most likely begins'], hand: ['✎', 'Checked by hand'], out: ['–', 'Left out'], blank: ['○', 'Blank page: its marker goes just before the next page'] };
    const shown = () => result.align.placements.filter(p => filter === 'all' || ['unsure', 'lost'].includes(stateOf(p)) || p.touched);
    const wordText = w => w.node.nodeValue.substr(w.off, w.len);
    const pdfStartLines = p => { const pg = result.pdf.pages[p.pdfPage - 1]; return pg ? pg.body.slice(0, 2).map(l => l.text).join(' ') : p.pdfStart; };
    const renderPdf = async (n, canvas, width) => {
      if (!result.view) result.view = pdfjsLib.getDocument({ data: result.pdfBytes.slice() }).promise;
      const doc = await result.view, page = await doc.getPage(n);
      const vp0 = page.getViewport({ scale: 1 }), dpr = window.devicePixelRatio || 1;
      const vp = page.getViewport({ scale: width / vp0.width * dpr });
      canvas.width = Math.round(vp.width); canvas.height = Math.round(vp.height); canvas.style.width = width + 'px';
      const g = canvas.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, canvas.width, canvas.height);
      await page.render({ canvasContext: g, viewport: vp }).promise;
    };
    const move = (p, e) => {
      const W = result.epub.words; e = Math.max(0, Math.min(W.length - 1, e));
      p.e = e; p.hand = true; p.touched = true; p.skip = false; p.conf = 1;
      p.epubStart = W.slice(e, e + 10).map(x => x.w).join(' ');
      drawResult();
    };
    const review = idx => {
      const list = shown(), p = list[idx]; if (!p) { openAt = null; return drawResult(); }
      const all = result.align.placements, at = all.indexOf(p), W = result.epub.words;
      const others = new Map();   // the other pages' markers, by word, in page order (a blank page shares its word)
      all.forEach(q => { if (q !== p && !q.skip) others.set(q.e, (others.get(q.e) || []).concat(q)); });
      const prev = all.slice(0, at).reverse().find(q => !q.skip), next = all.slice(at + 1).find(q => !q.skip);
      const canvas = h('canvas', { class: 'pg-pdf' });
      renderPdf(p.pdfPage, canvas, 330).catch(e => console.warn(e));
      // the EPUB's text around the marker, every word a place to put it
      const from = Math.max(0, p.e - 60), to = Math.min(W.length, p.e + 140);
      const text = h('div', { class: 'pg-text' });
      const blockOf = n => n.parentNode && n.parentNode.closest ? n.parentNode.closest('p, li, h1, h2, h3, h4, h5, h6, blockquote, td, dd, figcaption, div') : null;
      let lastBlock = null, here = null;
      for (let i = from; i < to; i++) {
        const w = W[i], blk = blockOf(w.node);
        if (i > from && W[i - 1].fi !== w.fi) text.append(h('div', { class: 'pg-sect' }, 'next section'));
        else if (i > from && blk !== lastBlock) text.append(h('br'), h('span', { class: 'pg-para' }));
        lastBlock = blk;
        const pins = (others.get(i) || []).slice();
        if (i === p.e && !p.skip) pins.push(p);
        pins.sort((a, b) => all.indexOf(a) - all.indexOf(b)).forEach(q => {
          if (q === p) { here = h('span', { class: 'pg-pin' }, 'p. ' + p.label); text.append(here); }
          else text.append(h('span', { class: 'pg-pin other' }, 'p. ' + q.label));
        });
        text.append(h('span', { class: 'pg-w', title: 'Page ' + p.label + ' begins here', onclick: () => move(p, i) }, wordText(w)));
        // what stands between this word and the next, as written (punctuation, spaces)
        const nx = W[i + 1];
        const gap = nx && nx.node === w.node ? w.node.nodeValue.slice(w.off + w.len, nx.off) : (w.node.nodeValue.slice(w.off + w.len).replace(/\s+$/, '') + ' ');
        text.append(gap.replace(/\s+/g, ' ') || (nx && nx.node !== w.node ? ' ' : ''));
      }
      const warn = !p.skip && ((prev && p.e < prev.e) ? `This puts p. ${p.label} before the marker of p. ${prev.label}.` : (next && p.e > next.e) ? `This puts p. ${p.label} after the marker of p. ${next.label}.` : '');
      const [icon, what] = MARK[stateOf(p)];
      out.append(h('div', { class: 'pg-review' },
        h('div', { class: 'pg-rhead' },
          h('b', null, `p. ${p.label}`), h('span', { class: 'muted' }, ` · PDF page ${p.pdfPage} · ${icon} ${what}`),
          h('span', { class: 'spacer' }),
          h('button', { class: 'chip', disabled: idx === 0, onclick: () => { openAt = idx - 1; drawResult(); } }, '← Previous'),
          h('button', { class: 'chip', disabled: idx >= list.length - 1, onclick: () => { openAt = idx + 1; drawResult(); } }, 'Next →'),
          h('button', { class: 'chip', onclick: () => { openAt = null; drawResult(); } }, 'Back to the list')),
        h('div', { class: 'pg-cols' },
          h('div', { class: 'pg-left' }, canvas),
          h('div', { class: 'pg-right' },
            h('div', { class: 'pg-begins' }, h('span', { class: 'muted' }, 'The PDF page begins: '), h('i', null, pdfStartLines(p))),
            text,
            warn ? h('div', { class: 'pg-warn' }, warn) : null,
            h('div', { class: 'pg-acts' },
              h('span', { class: 'muted' }, 'Click the word the page begins with, or:'),
              h('button', { class: 'chip', disabled: p.skip, onclick: () => move(p, p.e - 1) }, '◀ One word'),
              h('button', { class: 'chip', disabled: p.skip, onclick: () => move(p, p.e + 1) }, 'One word ▶'),
              p.skip ? h('button', { class: 'chip', onclick: () => { p.skip = false; p.touched = true; drawResult(); } }, 'Put this page back')
                : h('button', { class: 'chip', onclick: () => { p.skip = true; p.touched = true; drawResult(); } }, 'Leave this page out'),
              p.hand ? null : h('button', { class: 'chip', disabled: p.skip, onclick: () => { p.hand = true; p.touched = true; p.conf = 1; drawResult(); } }, 'It is right'))))));
      if (here) requestAnimationFrame(() => { text.scrollTop = Math.max(0, here.offsetTop - text.offsetTop - 80); });
    };
    const drawResult = () => {
      clear(out);
      save.hidden = !result;
      go.textContent = result ? 'Find again' : 'Find the pages';
      box.style.width = result ? '1000px' : '760px';
      if (!result) return;
      const pl = result.align.placements, count = k => pl.filter(p => stateOf(p) === k).length;
      out.append(
        h('div', { class: 'pg-sum' },
          h('b', null, `${pl.length} printed pages${pl.length ? ` (pp. ${pl[0].label}–${pl[pl.length - 1].label})` : ''}`),
          h('span', null, ` · numbers ${result.pdf.source}`),
          h('div', null, [`${count('sure')} placed with confidence`, count('blank') ? `${count('blank')} blank` : '', `${count('unsure')} uncertain`, `${count('lost')} not found`, count('hand') ? `${count('hand')} checked by hand` : '', count('out') ? `${count('out')} left out` : ''].filter(Boolean).join(' · ')),
          result.epub.existing ? h('div', { class: 'muted' }, `The EPUB had ${result.epub.existing} page markers already; they are replaced.`) : null,
          result.pdf.ocrPages ? h('div', { class: 'muted' }, `${result.pdf.ocrPages} scanned ${result.pdf.ocrPages === 1 ? 'page was' : 'pages were'} read with text recognition (OCR).`) : null));
      if (openAt != null) return review(openAt);
      const list = shown();
      out.append(h('div', { class: 'pg-filter' },
        h('button', { class: 'chip' + (filter === 'unsure' ? ' on' : ''), onclick: () => { filter = 'unsure'; drawResult(); } }, `To check (${count('unsure') + count('lost')})`),
        h('button', { class: 'chip' + (filter === 'all' ? ' on' : ''), onclick: () => { filter = 'all'; drawResult(); } }, `All pages (${pl.length})`),
        h('span', { class: 'muted', style: { fontSize: '12px' } }, 'Click a page to see it beside the PDF and move its marker.')));
      if (!list.length) { out.append(h('div', { class: 'pg-none muted' }, 'Nothing left to check. Save the EPUB, or look through all pages.')); return; }
      out.append(h('div', { class: 'pg-list' }, list.map((p, i) => {
        const [icon, what] = MARK[stateOf(p)];
        return h('div', { class: 'pg-item ' + stateOf(p), onclick: () => { openAt = i; drawResult(); } },
          h('b', null, h('span', { class: 'pg-mark', title: what }, icon), ' p. ' + p.label),
          h('span', null, 'PDF: ', h('i', null, p.pdfStart)), h('span', null, 'EPUB: ', h('i', null, p.skip ? '(left out)' : p.epubStart)));
      })));
    };
    go.addEventListener('click', async () => {
      go.disabled = true; result = null; drawResult();
      try {
        status.textContent = 'Fetching the files…';
        const [pd, ed] = await Promise.all([bytesOf(pdfFile), bytesOf(epubFile)]);
        stop = { stop: false }; filter = 'unsure'; openAt = null;
        const pdfBytes = new Uint8Array(pd.slice(0));   // pdf.js may take the buffer it reads; this copy shows the pages in the check
        result = await pgPaginate(pd, ed, Object.assign({ stop }, opts), m => { status.textContent = m; });
        result.pdfBytes = pdfBytes;
        status.textContent = '';
      } catch (e) { console.error(e); status.textContent = e.message || String(e); }
      go.disabled = false; drawResult();
    });
    save.addEventListener('click', async () => {
      save.disabled = true;
      try {
        const lib = epubFile.lib ? epubFile : null, replacing = lib && opts.epubMode === 'replace';
        // the notes made in this EPUB, found before the markers go in (their places then move along)
        let notes = [], placed = [];
        if (replacing) {
          status.textContent = 'Finding the notes…';
          notes = (await api(`doc/${lib.docId}/highlights`)).filter(n => n.file ? n.file === lib.name : lib.main);
          placed = notes.map(n => ({ n, r: pgNoteRange(result.epub, n) }));
        }
        status.textContent = 'Writing the EPUB…';
        const blob = await pgWrite(result.epub, result.align.placements, opts);
        const kept = result.align.placements.filter(p => !p.skip).length;
        const name = lib ? lib.name : epubFile.name.replace(/\.epub$/i, '') + ' (with page numbers).epub';
        const file = new File([blob], replacing ? name : name.replace(/\.epub$/i, '') + (lib ? ' (with page numbers).epub' : '.epub'), { type: 'application/epub+zip' });
        if (opts.download) { const a = h('a', { href: URL.createObjectURL(file), download: file.name }); document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 60000); }
        let msg = `Made “${file.name}” with ${kept} printed pages`;
        if (replacing) {
          const moved = placed.filter(x => x.r).map(x => Object.assign({ n: x.n, how: x.r.how }, pgNoteAfter(result.epub, x.r)));
          status.textContent = 'Replacing the EPUB in the book…';
          const r = await fetch(`/api/doc/${lib.docId}/files?op=replace&rel=${encodeURIComponent(lib.rel)}`, { method: 'POST', credentials: 'same-origin', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/octet-stream' }, body: file });
          if (!r.ok) { let e = {}; try { e = await r.json(); } catch (_) {} throw new Error(e.error || 'The tablet said ' + r.status); }
          status.textContent = 'Moving the notes…';
          let done = 0;
          for (const m of moved) {
            const body = { auto: true };
            if (m.cfi) body.epubCfi = m.cfi;
            if (m.label) body.printedPageLabel = m.label;
            try { await api(`highlight/${m.n.id}`, { method: 'PATCH', json: body }); done++; } catch (e) { console.warn('note', m.n.id, e); }
          }
          try {
            await fetch(`/api/doc/${lib.docId}/pagemap`, { method: 'PUT', credentials: 'same-origin', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/json' },
              body: JSON.stringify(pgPageMap(result, { pdf: pdfFile.lib ? { rel: pdfFile.rel, name: pdfFile.name } : { name: pdfFile.name }, epub: { rel: lib.rel, name: lib.name } })) });
          } catch (e) { console.warn('page map', e); }
          const lost = notes.length - moved.length;
          msg = `Replaced the EPUB in “${lib.title}” (${kept} printed pages)` + (notes.length ? `; ${done} of ${notes.length} notes moved${lost ? `, ${lost} not found in the new file (their places are kept)` : ''}` : '');
        } else if (lib) {
          status.textContent = 'Adding the EPUB to the book…';
          const r = await fetch(`/api/doc/${lib.docId}/files?op=upload&name=${encodeURIComponent(file.name)}`, { method: 'POST', credentials: 'same-origin', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/octet-stream' }, body: file });
          if (!r.ok) throw new Error('The tablet said ' + r.status);
          msg = `Added “${file.name}” to “${lib.title}” as another edition`;
        } else if (opts.toLibrary) { status.textContent = 'Adding to the library…'; await wcToLibrary([file], opts.category); }
        if (typeof refreshAfterUpload === 'function' && lib) refreshAfterUpload();
        toast(msg, 6000);
        close();
      } catch (e) { console.error(e); status.textContent = 'Could not save the EPUB: ' + (e.message || e); save.disabled = false; }
    });
    box.append(h('h2', null, 'Convert books'), convertTabs('pages', close),
      h('p', { class: 'muted', style: { fontSize: '13px' } }, 'The same book as a PDF with its printed page numbers and as an EPUB without them: the EPUB gets the printed page numbers, so notes, citations and “go to page” follow the book. Both must be the same edition. Scanned PDFs are read with text recognition (OCR), a few seconds a page.'),
      pdfPick, epubPick, libBox, slots, optsBox, status, out,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), go, save));
    draw();
    wcDropTarget = addFiles;
  }, () => { wcDropTarget = null; stop.stop = true; if (result && result.view) result.view.then(d => d.destroy()).catch(() => {}); });   // closing stops a reading in progress
}
