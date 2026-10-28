/* Presentation mode: the Presentations shelf, the presenter screen (on the tablet or in a
 * browser) and the full-screen audience screen that follows it. Slides are drawn here:
 * PPTX with pptx-renderer (Apache-2.0), PDF with pdf.js, ODP with a small renderer below.
 * Speaker notes, transitions, your notes and ink come from the tablet (/api/presentations). */
'use strict';

const DECK_RX = /\.(pptx|odp|pdf|ppt|key|pps|ppsx)$/i;
const deckCache = new Map(); // id → Promise<deck>

function isPresentationFile(f) { return /\.(pptx|odp|ppt|key|pps|ppsx)$/i.test(f.name); }

// ------------------------------------------------------------------ the shelf

async function showPresentations() {
  closeReader(); stopLive();
  const app = clear(document.getElementById('app'));
  app.appendChild(header());
  const main = h('main', { class: 'pres-shelf' });
  app.appendChild(main);
  const file = h('input', { type: 'file', multiple: true, accept: '.pptx,.odp,.pdf,.ppt,.key', hidden: true });
  file.addEventListener('change', () => { if (file.files.length) uploadDecks([...file.files]); file.value = ''; });
  main.append(h('div', { class: 'toolbar' },
    h('div', { class: 'label' }, 'Presentations'),
    h('button', { class: 'btn fill', onclick: () => file.click() }, '+ Add presentation'), file,
    h('div', { class: 'spacer' }),
    h('a', { class: 'btn', href: '#/audience', title: 'Open this on the computer connected to the projector' }, 'Audience screen')));
  main.append(h('p', { class: 'muted', style: { marginTop: 0 } },
    'PowerPoint (.pptx), LibreOffice (.odp) or PDF. Google Slides: File → Download → PowerPoint or PDF. ',
    'Drag files onto this page to add them. Present from the tablet (Presentations in the side menu), or open the presenter screen here.'));
  const list = h('div', { class: 'pres-list' }, h('div', { class: 'loading', style: { position: 'static', height: '120px' } }, h('div', { class: 'spin' })));
  main.append(list);
  let data;
  try { data = await api('presentations'); } catch (e) { fail(e); return; }
  clear(list);
  const decks = data.presentations || [];
  if (!decks.length) list.append(h('div', { class: 'empty' }, h('h2', null, 'No presentations yet'), h('div', { class: 'muted' }, 'Add a .pptx, .odp or PDF file.')));
  decks.forEach(p => {
    const thumb = h('div', { class: 'pres-thumb' });
    const card = h('div', { class: 'pres-card' },
      thumb,
      h('div', { class: 'pres-info' },
        h('div', { class: 't' }, p.title),
        h('div', { class: 'mono muted', style: { fontSize: '11px' } }, [p.type.toUpperCase(), p.slides ? `${p.slides} slides` : '', fmtSize(p.size), fmtDate(p.added)].filter(Boolean).join(' · ')),
        h('div', { class: 'row', style: { marginTop: '8px' } },
          h('a', { class: 'btn fill', href: `#/presenter/${encodeURIComponent(p.id)}` }, 'Present'),
          menuButton('More ▾', [
            ['Audience screen', 'Full screen, follows the presenter', () => { location.hash = '#/audience'; }],
            ['Download', p.fileName, () => download(`presentations/${encodeURIComponent(p.id)}/file?download=1`, 'Downloading…')],
            ['Rename…', '', () => renameDeck(p)],
            ['Delete…', '', () => confirmBox('Delete this presentation?', `“${p.title}”, your notes and pen marks on it are deleted from the tablet.`, 'Delete', async () => {
              try { await api(`presentations/${encodeURIComponent(p.id)}`, { method: 'DELETE' }); toast('Deleted'); showPresentations(); } catch (e) { fail(e); }
            })]
          ]))));
    onContext(card, () => [
      { label: 'Present', fn: () => { location.hash = `#/presenter/${encodeURIComponent(p.id)}`; } },
      { label: 'Audience screen', fn: () => { location.hash = '#/audience'; } },
      '-',
      { label: 'Download', fn: () => download(`presentations/${encodeURIComponent(p.id)}/file?download=1`, 'Downloading…') },
      { label: 'Rename…', fn: () => renameDeck(p) },
      { label: 'Delete…', danger: true, fn: () => confirmBox('Delete this presentation?', `“${p.title}” is deleted from the tablet.`, 'Delete', async () => {
        try { await api(`presentations/${encodeURIComponent(p.id)}`, { method: 'DELETE' }); showPresentations(); } catch (e) { fail(e); }
      }) }
    ]);
    list.append(card);
    // A first-slide preview, drawn when the card scrolls into view.
    const io = new IntersectionObserver(async ents => {
      if (!ents.some(e => e.isIntersecting)) return;
      io.disconnect();
      try { const deck = await openDeck(p.id); fitSlide(thumb, deck); await deck.render(0, thumb); } catch (_) { thumb.textContent = p.type.toUpperCase(); }
    });
    io.observe(thumb);
  });
}

function renameDeck(p) {
  modal((box, close) => {
    const inp = h('input', { value: p.title });
    const save = async () => { try { await api(`presentations/${encodeURIComponent(p.id)}`, { method: 'PATCH', json: { title: inp.value } }); close(); showPresentations(); } catch (e) { fail(e); } };
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') save(); });
    box.append(h('h2', null, 'Rename'), h('label', { class: 'fields' }, h('span', { class: 'label' }, 'Title'), inp),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save')));
  });
}

async function uploadDecks(files) {
  for (const f of files) {
    if (!DECK_RX.test(f.name)) { toast(`“${f.name}” is not a presentation`); continue; }
    toast(`Uploading ${f.name}…`, 0);
    try {
      const r = await fetch('/api/presentations?name=' + encodeURIComponent(f.name), { method: 'POST', body: f, credentials: 'same-origin', headers: { 'X-Marginalia': '1' } });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || 'Upload failed');
      toast(`Added “${j.title}”`);
    } catch (e) { fail(e); }
  }
  if (location.hash.startsWith('#/presentations')) showPresentations();
}

// ------------------------------------------------------------------ decks: one way to draw any slide

/** Sizes [el]'s height from its width and the deck's shape. */
function fitSlide(el, deck) { el.style.aspectRatio = `${1 / deck.aspect}`; }

async function openDeck(id) {
  if (deckCache.has(id)) return deckCache.get(id);
  const p = (async () => {
    const meta = await api(`presentations/${encodeURIComponent(id)}`);
    const buf = await (await fetch(`/api/presentations/${encodeURIComponent(id)}/file`, { credentials: 'same-origin' })).arrayBuffer();
    const deck = meta.type === 'pdf' ? await pdfDeck(buf) : meta.type === 'odp' ? await odpDeck(buf) : await pptxDeck(buf);
    deck.meta = meta;
    const slides = (meta.info && meta.info.slides) || [];
    deck.slideInfo = i => slides[i] || {};
    deck.hidden = i => !!(slides[i] && slides[i].hidden);
    return deck;
  })();
  deckCache.set(id, p);
  p.catch(() => deckCache.delete(id));
  return p;
}

/** Transitions, animations and paragraphs of each slide of a PPTX, in show order (see slidefx.js). */
async function pptxFx(buf) {
  const zip = await JSZip.loadAsync(buf);
  const parse = async name => { const f = zip.file(name); return f ? new DOMParser().parseFromString(await f.async('string'), 'application/xml') : null; };
  const relsOf = async path => {
    const dir = path.replace(/\/[^/]*$/, ''), doc = await parse(`${dir}/_rels/${path.split('/').pop()}.rels`), out = {};
    if (!doc) return out;
    for (const r of doc.getElementsByTagNameNS('*', 'Relationship')) {
      const t = r.getAttribute('Target') || '';
      const parts = [];
      (t.startsWith('/') ? t.slice(1) : `${dir}/${t}`).split('/').forEach(seg => { if (seg === '..') parts.pop(); else if (seg && seg !== '.') parts.push(seg); });
      out[r.getAttribute('Id')] = parts.join('/');
    }
    return out;
  };
  const pres = await parse('ppt/presentation.xml');
  if (!pres) return [];
  const rels = await relsOf('ppt/presentation.xml');
  const out = [];
  for (const id of pres.getElementsByTagNameNS('*', 'sldId')) {
    const rid = [...id.attributes].find(a => a.localName === 'id' && a.namespaceURI && a.namespaceURI.includes('relationships'));
    const doc = rid && rels[rid.value] ? await parse(rels[rid.value]) : null;
    if (!doc) { out.push({}); continue; }
    let tr = null, anims = [], paras = {};
    try { tr = SlideFX.slideTransition(doc); } catch (_) {}
    try { anims = SlideAnim.parseTiming(doc); paras = SlideAnim.slideParagraphs(doc); } catch (_) {}
    out.push({ tr, anims, paras });
  }
  return out;
}

async function pptxDeck(buf) {
  const fx = await pptxFx(buf).catch(() => []);
  const mod = await import('./lib/pptx-renderer/pptx-renderer.browser.es.js');
  const host = h('div', { style: { position: 'fixed', left: '-20000px', top: '0', width: '960px', height: '540px', overflow: 'hidden' }, 'aria-hidden': 'true' });
  document.body.append(host);
  const viewer = await mod.PptxViewer.open(buf, host, { renderMode: 'slide', fitMode: 'none', zipLimits: mod.RECOMMENDED_ZIP_LIMITS, lazyMedia: true, lazySlides: true });
  const pd = viewer.presentationData;
  return {
    count: viewer.slideCount,
    fx: i => fx[i] || {},
    aspect: pd && pd.width ? pd.height / pd.width : 9 / 16,
    async render(i, el) {
      if (el._handle) { try { el._handle.dispose(); } catch (_) {} el._handle = null; }
      clear(el);
      const w = el.clientWidth || 960;
      const handle = viewer.renderSlideToContainer(i, el, w / viewer.slideWidth);
      el._handle = handle;
      if (handle && handle.ready) await handle.ready.catch(() => {});
    },
    destroy() { try { viewer.destroy(); } catch (_) {} host.remove(); }
  };
}

async function pdfDeck(buf) {
  pdfjsLib.GlobalWorkerOptions.workerSrc = 'lib/pdf.worker.min.js';
  const pdf = await pdfjsLib.getDocument({ data: new Uint8Array(buf) }).promise;
  const first = await pdf.getPage(1);
  const vp = first.getViewport({ scale: 1 });
  return {
    count: pdf.numPages,
    aspect: vp.height / vp.width,
    async render(i, el) {
      const page = await pdf.getPage(i + 1);
      const w = el.clientWidth || 960;
      const v = page.getViewport({ scale: (w / page.getViewport({ scale: 1 }).width) * (window.devicePixelRatio || 1) });
      const c = h('canvas', { width: Math.round(v.width), height: Math.round(v.height), style: { width: '100%', height: '100%', display: 'block' } });
      await page.render({ canvasContext: c.getContext('2d'), viewport: v }).promise;
      clear(el).append(c);
    },
    destroy() { try { pdf.destroy(); } catch (_) {} }
  };
}

// ----- ODP (OpenDocument) — text, images, shapes and backgrounds; a simplified rendering

const ODF = {
  draw: 'urn:oasis:names:tc:opendocument:xmlns:drawing:1.0',
  svg: 'urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0',
  text: 'urn:oasis:names:tc:opendocument:xmlns:text:1.0',
  style: 'urn:oasis:names:tc:opendocument:xmlns:style:1.0',
  fo: 'urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0',
  pres: 'urn:oasis:names:tc:opendocument:xmlns:presentation:1.0',
  xlink: 'http://www.w3.org/1999/xlink',
  office: 'urn:oasis:names:tc:opendocument:xmlns:office:1.0'
};
function odfLen(v) { // → cm
  if (!v) return 0;
  const m = /([-0-9.]+)\s*(cm|mm|in|pt|px)?/.exec(v); if (!m) return 0;
  const n = parseFloat(m[1]);
  return { mm: n / 10, in: n * 2.54, pt: n * 2.54 / 72, px: n * 2.54 / 96 }[m[2]] ?? n;
}

async function odpDeck(buf) {
  const zip = await JSZip.loadAsync(buf);
  const parse = async name => { const f = zip.file(name); return f ? new DOMParser().parseFromString(await f.async('string'), 'application/xml') : null; };
  const content = await parse('content.xml'), stylesDoc = await parse('styles.xml');
  const styles = new Map();
  [stylesDoc, content].forEach(doc => doc && [...doc.getElementsByTagNameNS(ODF.style, 'style'), ...doc.getElementsByTagNameNS(ODF.style, 'page-layout')]
    .forEach(s => styles.set((s.getAttributeNS(ODF.style, 'family') || 'pl') + ':' + s.getAttributeNS(ODF.style, 'name'), s)));
  const prop = (family, name, tag, ns, attr, depth = 0) => {
    const s = styles.get(family + ':' + name); if (!s || depth > 8) return null;
    for (const p of s.getElementsByTagNameNS(ODF.style, tag)) { const v = p.getAttributeNS(ns, attr); if (v) return v; }
    const parent = s.getAttributeNS(ODF.style, 'parent-style-name');
    return parent ? prop(family, parent, tag, ns, attr, depth + 1) : null;
  };
  // Page size from the first master page's layout.
  const masters = stylesDoc ? [...stylesDoc.getElementsByTagNameNS(ODF.style, 'master-page')] : [];
  let W = 28, H = 15.75;
  const m0 = masters[0];
  if (m0) {
    const pl = styles.get('pl:' + m0.getAttributeNS(ODF.style, 'page-layout-name'));
    const pp = pl && pl.getElementsByTagNameNS(ODF.style, 'page-layout-properties')[0];
    if (pp) { W = odfLen(pp.getAttributeNS(ODF.fo, 'page-width')) || W; H = odfLen(pp.getAttributeNS(ODF.fo, 'page-height')) || H; }
  }
  const pages = content ? [...content.getElementsByTagNameNS(ODF.draw, 'page')] : [];
  const images = new Map();
  const img = async href => {
    if (!href || /^https?:/.test(href)) return href;
    const path = href.replace(/^\.\//, '');
    if (!images.has(path)) { const f = zip.file(path); images.set(path, f ? URL.createObjectURL(new Blob([await f.async('uint8array')])) : ''); }
    return images.get(path);
  };
  const bgOf = async (family, name) => {
    const fill = prop(family, name, 'drawing-page-properties', ODF.draw, 'fill') || prop(family, name, 'graphic-properties', ODF.draw, 'fill');
    const color = prop(family, name, 'drawing-page-properties', ODF.draw, 'fill-color');
    if (fill === 'solid' && color) return color;
    return fill === 'none' ? null : color;
  };
  async function drawShapes(parentEl, out, scale, isMaster) {
    for (const n of parentEl.children) {
      if (n.namespaceURI !== ODF.draw) continue;
      if (n.getAttributeNS(ODF.pres, 'placeholder') === 'true') continue;
      // On a master page, frames with a presentation class are the layout's placeholders ("Click to edit…").
      if (isMaster && n.getAttributeNS(ODF.pres, 'class')) continue;
      if (n.localName === 'g') { await drawShapes(n, out, scale, isMaster); continue; }
      const x = odfLen(n.getAttributeNS(ODF.svg, 'x')), y = odfLen(n.getAttributeNS(ODF.svg, 'y'));
      const w = odfLen(n.getAttributeNS(ODF.svg, 'width')), hh = odfLen(n.getAttributeNS(ODF.svg, 'height'));
      const box = h('div', { class: 'odp-shape', style: { left: x * scale + 'px', top: y * scale + 'px', width: w * scale + 'px', height: hh * scale + 'px' } });
      const gs = n.getAttributeNS(ODF.draw, 'style-name') || n.getAttributeNS(ODF.pres, 'style-name');
      const fam = n.getAttributeNS(ODF.pres, 'style-name') ? 'presentation' : 'graphic';
      const fill = prop(fam, gs, 'graphic-properties', ODF.draw, 'fill'), fillColor = prop(fam, gs, 'graphic-properties', ODF.draw, 'fill-color');
      const stroke = prop(fam, gs, 'graphic-properties', ODF.draw, 'stroke'), strokeColor = prop(fam, gs, 'graphic-properties', ODF.svg, 'stroke-color');
      if (n.localName === 'custom-shape' || n.localName === 'rect' || n.localName === 'ellipse') {
        if (fill !== 'none') box.style.background = fillColor || (n.localName === 'custom-shape' ? '#729fcf' : '');
        if (stroke !== 'none') box.style.border = `${Math.max(1, scale * 0.03)}px solid ${strokeColor || '#3465a4'}`;
        const geo = n.getElementsByTagNameNS(ODF.draw, 'enhanced-geometry')[0];
        const type = geo ? geo.getAttributeNS(ODF.draw, 'type') : '';
        if (n.localName === 'ellipse' || type === 'ellipse') box.style.borderRadius = '50%';
        if (type === 'round-rectangle') box.style.borderRadius = scale * 0.3 + 'px';
      } else if (n.localName === 'line') {
        const x1 = odfLen(n.getAttributeNS(ODF.svg, 'x1')), y1 = odfLen(n.getAttributeNS(ODF.svg, 'y1')), x2 = odfLen(n.getAttributeNS(ODF.svg, 'x2')), y2 = odfLen(n.getAttributeNS(ODF.svg, 'y2'));
        const len = Math.hypot(x2 - x1, y2 - y1) * scale, ang = Math.atan2(y2 - y1, x2 - x1);
        out.append(h('div', { class: 'odp-shape', style: { left: x1 * scale + 'px', top: y1 * scale + 'px', width: len + 'px', height: '0', borderTop: `${Math.max(1, scale * 0.03)}px solid ${strokeColor || '#000'}`, transformOrigin: '0 0', transform: `rotate(${ang}rad)` } }));
        continue;
      }
      const image = n.getElementsByTagNameNS(ODF.draw, 'image')[0];
      if (image) {
        const src = await img(image.getAttributeNS(ODF.xlink, 'href'));
        if (src) box.append(h('img', { src, style: { width: '100%', height: '100%', objectFit: 'fill' } }));
      }
      const tb = n.localName === 'frame' ? n.getElementsByTagNameNS(ODF.draw, 'text-box')[0] : n;
      const paras = tb ? [...tb.getElementsByTagNameNS(ODF.text, 'p'), ...tb.getElementsByTagNameNS(ODF.text, 'h')] : [];
      if (paras.length && !image) {
        const va = prop(fam, gs, 'graphic-properties', ODF.draw, 'textarea-vertical-align');
        const inner = h('div', { class: 'odp-text', style: { justifyContent: va === 'middle' ? 'center' : va === 'bottom' ? 'flex-end' : 'flex-start' } });
        const presClass = n.getAttributeNS(ODF.pres, 'class');
        const baseSize = presClass === 'title' ? 0.044 * H : 0.03 * H;
        for (const p of paras) {
          if (p.parentNode && p.parentNode.localName === 'list-item' && p.previousElementSibling) { /* nested paragraphs inside a list item */ }
          const ps = p.getAttributeNS(ODF.text, 'style-name');
          const align = prop('paragraph', ps, 'paragraph-properties', ODF.fo, 'text-align') || prop(fam, gs, 'paragraph-properties', ODF.fo, 'text-align');
          const inList = !!p.closest && p.parentNode && p.parentNode.localName === 'list-item';
          const pe = h('div', { style: { textAlign: { end: 'right', center: 'center', justify: 'justify' }[align] || 'left' } });
          if (inList) pe.style.paddingLeft = '1.1em', pe.style.textIndent = '-0.8em';
          if (inList) pe.append('• ');
          const run = (node, style) => {
            node.childNodes.forEach(c => {
              if (c.nodeType === 3) {
                const span = h('span', null, c.nodeValue);
                const fs = prop('text', style, 'text-properties', ODF.fo, 'font-size') || prop('paragraph', ps, 'text-properties', ODF.fo, 'font-size') || prop(fam, gs, 'text-properties', ODF.fo, 'font-size');
                span.style.fontSize = (fs ? odfLen(fs) : baseSize) * scale + 'px';
                const col = prop('text', style, 'text-properties', ODF.fo, 'color') || prop('paragraph', ps, 'text-properties', ODF.fo, 'color') || prop(fam, gs, 'text-properties', ODF.fo, 'color');
                if (col) span.style.color = col;
                const wgt = prop('text', style, 'text-properties', ODF.fo, 'font-weight') || prop(fam, gs, 'text-properties', ODF.fo, 'font-weight');
                if (wgt === 'bold' || presClass === 'title') span.style.fontWeight = '700';
                if ((prop('text', style, 'text-properties', ODF.fo, 'font-style') || '') === 'italic') span.style.fontStyle = 'italic';
                const fam2 = prop('text', style, 'text-properties', ODF.style, 'font-name') || prop(fam, gs, 'text-properties', ODF.style, 'font-name');
                if (fam2) span.style.fontFamily = `"${fam2}", sans-serif`;
                pe.append(span);
              } else if (c.nodeType === 1) {
                if (c.localName === 'line-break') pe.append(h('br'));
                else if (c.localName === 's') pe.append(' '.repeat(+c.getAttributeNS(ODF.text, 'c') || 1));
                else if (c.localName === 'tab') pe.append(' ');
                else run(c, c.getAttributeNS(ODF.text, 'style-name') || style);
              }
            });
          };
          run(p, null);
          if (!pe.textContent.trim()) pe.append(' ');
          inner.append(pe);
        }
        box.append(inner);
      }
      out.append(box);
    }
  }
  return {
    count: pages.length,
    aspect: H / W,
    async render(i, el) {
      const pg = pages[i]; if (!pg) return;
      const w = el.clientWidth || 960, scale = w / W;
      const stage = h('div', { class: 'odp-page', style: { width: w + 'px', height: H * scale + 'px', background: '#fff', fontSize: 0.03 * H * scale + 'px' } });
      const master = masters.find(m => m.getAttributeNS(ODF.style, 'name') === pg.getAttributeNS(ODF.draw, 'master-page-name')) || m0;
      const mbg = master ? await bgOf('drawing-page', master.getAttributeNS(ODF.draw, 'style-name')) : null;
      const pbg = await bgOf('drawing-page', pg.getAttributeNS(ODF.draw, 'style-name'));
      if (pbg || mbg) stage.style.background = pbg || mbg;
      if (master) await drawShapes(master, stage, scale, true);
      await drawShapes(pg, stage, scale, false);
      clear(el).append(stage);
    },
    destroy() { images.forEach(u => { try { URL.revokeObjectURL(u); } catch (_) {} }); }
  };
}

// ------------------------------------------------------------------ transitions, Morph and animations (slidefx.js, slidemorph.js, slideanim.js)

/** The transition into slide `i`: {kind, option, ms} with DeckCraft's kinds and options, or null. */
function transitionOf(deck, i) {
  const own = deck.fx && deck.fx(i).tr;
  if (own) return own.kind === 'none' ? null : { kind: own.kind, option: own.option || '', ms: Math.max(0, Math.min(10000, own.ms || 0)) };
  const t = (deck.slideInfo(i) || {}).transition;
  if (!t || !t.type) return null;
  const d = t.dir || '';
  const m = { fade: ['fade', 'smoothly'], fadeBlack: ['fade', 'throughBlack'], push: ['push', d || 'l'], wipe: ['wipe', d || 'r'], cover: ['cover', d || 'l'], uncover: ['uncover', d || 'l'],
    split: ['split', 'vertOut'], zoom: ['zoom', 'in'], circle: ['shape', 'circle'], cut: ['cut', ''], dissolve: ['dissolve', ''] }[t.type] || ['fade', 'smoothly'];
  return { kind: m[0], option: m[1], ms: Math.max(150, Math.min(3000, t.ms || 600)) };
}
const slideRoot = el => (el && (el.querySelector('[data-slide-root]') || null));
/** The animation timeline of slide `i` as drawn in `el` (null without animations). */
function timelineOf(deck, i, el) {
  const f = deck.fx ? deck.fx(i) : null;
  if (!f || !f.anims || !f.anims.length) return null;
  try { return SlideAnim.timelineFor(f.anims, slideRoot(el), f.paras); } catch (_) { return null; }
}
/** Click steps of slide `i`: {n, first} — first is 1 when step 1 starts by itself as the slide appears. */
function stepsOf(deck, i) {
  deck._steps = deck._steps || new Map();
  if (!deck._steps.has(i)) {
    let v = { n: 0, first: 0 };
    const f = deck.fx ? deck.fx(i) : null;
    if (f && f.anims && f.anims.length) { try { const shapes = {}; for (const [k, v] of Object.entries(f.paras || {})) shapes[k] = { paras: v }; const t = SlideAnim.Timeline(f.anims, shapes, 960, 540); v = { n: t.steps(), first: t.steps() && t.stepIsAuto(0) ? 1 : 0 }; } catch (_) {} }
    deck._steps.set(i, v);
  }
  return deck._steps.get(i);
}

/** A picture of a drawn slide (`el`, the size of the stage) for the transition compositor. */
async function slidePicture(el, w, h, filter) {
  if (!el) return null;
  const c = el.querySelector(':scope > canvas');
  if (c && !slideRoot(el)) return c; // PDF pages are pictures already
  if (!window.htmlToImage) return null;
  const pr = Math.min(2, window.devicePixelRatio || 1, 2560 / Math.max(1, w));
  try {
    return await htmlToImage.toCanvas(el, { width: w, height: h, pixelRatio: pr, skipFonts: true, cacheBust: false, filter,
      style: { position: 'relative', left: '0', top: '0', right: 'auto', bottom: 'auto', width: w + 'px', height: h + 'px', opacity: '1', visibility: 'visible' } });
  } catch (_) { return null; }
}
/** Backgrounds and contents of slide `i` drawn apart (for Pan, Conveyor, Rotate… and Reveal). */
async function slideParts(deck, i, w, h, build) {
  const tmp = h_('div', { position: 'fixed', left: '-30000px', top: '0', width: w + 'px', height: h + 'px', overflow: 'hidden', background: 'transparent' });
  document.body.append(tmp);
  try {
    await deck.render(i, tmp);
    const root = slideRoot(tmp);
    if (!root) return null;
    const tl = timelineOf(deck, i, tmp);
    if (tl) SlideAnim.apply(tl, root, build, 0);
    const back = await slidePicture(tmp, w, h, n => !(n.dataset && n.dataset.spOrigin === 'slide'));
    root.style.background = 'none'; root.style.backgroundColor = 'transparent';
    const content = await slidePicture(tmp, w, h, n => !(n.dataset && (n.dataset.spOrigin === 'master' || n.dataset.spOrigin === 'layout')));
    return back && content ? { back, content } : null;
  } finally { tmp.remove(); }
}
function h_(tag, style) { const e = document.createElement(tag); Object.assign(e.style, style); return e; }
const nextFrame = () => new Promise(r => requestAnimationFrame(r));
/** Runs `draw(t)` for `ms` milliseconds (t linear 0..1); `stop.skip` jumps to the end. */
async function animateFor(ms, draw, stop) {
  const t0 = performance.now();
  for (;;) {
    const t = ms > 0 ? (performance.now() - t0) / ms : 1;
    if (t >= 1 || (stop && stop.skip)) { draw(1); return; }
    draw(t);
    await nextFrame();
  }
}

/**
 * Plays the transition into `next` (an .aud-layer, drawn and hidden) over `old` (shown, or null
 * for black) inside `stage`; afterwards `next` is shown.
 */
async function playTransition(deck, tr, i, prevIndex, old, next, stage, stop) {
  const show = () => { next.style.opacity = ''; next.style.visibility = ''; };
  if (!tr || !tr.ms || tr.kind === 'none' || (tr.kind === 'cut' && tr.option !== 'throughBlack')) { show(); return; }
  const w = stage.clientWidth, hh = stage.clientHeight;
  if (tr.kind === 'morph' && old && slideRoot(old) && slideRoot(next)) {
    const host = h('div', { class: 'aud-layer aud-fx' });
    stage.append(host);
    let m = null;
    try { m = SlideMorph.build(host, slideRoot(old), slideRoot(next), tr.option, SlideAnim.hiddenAtStart(timelineOf(deck, i, next))); } catch (e) { console.warn('Morph', e); }
    if (m) {
      old.style.visibility = 'hidden';
      await animateFor(tr.ms, t => m.draw(t), stop);
      show(); m.dispose(); host.remove(); old.style.visibility = '';
      return;
    }
    host.remove();
    tr = { kind: 'fade', option: 'smoothly', ms: tr.ms };
  }
  const canvas = h('canvas', { class: 'aud-fx' });
  const pr = Math.min(2, window.devicePixelRatio || 1);
  canvas.width = Math.round(w * pr); canvas.height = Math.round(hh * pr);
  Object.assign(canvas.style, { position: 'absolute', left: '0', top: '0', width: w + 'px', height: hh + 'px', zIndex: 4 });
  const comp = SlideFX.Compositor(canvas);
  const src = { New: await slidePicture(next, w, hh), Old: old ? await slidePicture(old, w, hh) : null };
  if (!comp || !src.New) { show(); return; }
  if (SlideFX.usesSlideParts(tr.kind, tr.option) && deck.fx) {
    const [po, pn] = await Promise.all([prevIndex >= 0 ? slideParts(deck, prevIndex, w, hh, Infinity) : null, slideParts(deck, i, w, hh, 0)]);
    if (po) { src.OldBack = po.back; src.OldContent = po.content; }
    if (pn) { src.NewBack = pn.back; src.NewContent = pn.content; if (!po) src.OldContent = h('canvas', { width: 1, height: 1 }); }
  }
  comp.setSources(src);
  stage.append(canvas);
  const aspect = w / Math.max(1, hh);
  await animateFor(tr.ms, t => comp.draw(SlideFX.transitionFrame(tr.kind, tr.option, t, aspect)), stop);
  show();
  await nextFrame();
  canvas.remove(); comp.dispose();
}

// ------------------------------------------------------------------ ink

function inkLayer(onStroke) {
  const c = h('canvas', { class: 'ink' });
  const ctx = c.getContext('2d');
  let strokes = [], cur = null, tool = 'off', color = '#e11d48';
  const size = () => {
    const r = c.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
    if (c.width !== Math.round(r.width * dpr) || c.height !== Math.round(r.height * dpr)) { c.width = Math.round(r.width * dpr); c.height = Math.round(r.height * dpr); }
  };
  const draw = () => {
    size(); ctx.clearRect(0, 0, c.width, c.height);
    [...strokes, ...(cur ? [cur] : [])].forEach(s => {
      ctx.strokeStyle = s.c; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      ctx.globalAlpha = s.hl ? 0.35 : 1;
      const p = s.p;
      for (let i = 1; i < p.length; i++) {
        ctx.lineWidth = (s.w * (0.5 + (p[i][2] || 0.5))) * c.width / 1000;
        ctx.beginPath(); ctx.moveTo(p[i - 1][0] * c.width, p[i - 1][1] * c.height); ctx.lineTo(p[i][0] * c.width, p[i][1] * c.height); ctx.stroke();
      }
      ctx.globalAlpha = 1;
    });
  };
  const pt = e => { const r = c.getBoundingClientRect(); return [+((e.clientX - r.left) / r.width).toFixed(4), +((e.clientY - r.top) / r.height).toFixed(4), +(e.pressure || 0.5).toFixed(2)]; };
  c.addEventListener('pointerdown', e => {
    if (tool === 'off') return;
    e.preventDefault(); c.setPointerCapture(e.pointerId);
    if (tool === 'erase') { eraseAt(pt(e)); return; }
    cur = { c: color, w: tool === 'hl' ? 14 : 3, hl: tool === 'hl', p: [pt(e)] }; draw();
  });
  c.addEventListener('pointermove', e => {
    if (tool === 'off' || !(e.buttons & 1)) return;
    if (tool === 'erase') { eraseAt(pt(e)); return; }
    if (!cur) return;
    (e.getCoalescedEvents ? e.getCoalescedEvents() : [e]).forEach(ev => cur.p.push(pt(ev)));
    draw(); onStroke(strokes.concat([cur]), false);
  });
  const end = () => { if (cur && cur.p.length > 1) { strokes.push(cur); onStroke(strokes, true); } cur = null; draw(); };
  c.addEventListener('pointerup', end); c.addEventListener('pointercancel', end);
  const eraseAt = ([x, y]) => {
    const before = strokes.length;
    strokes = strokes.filter(s => !s.p.some(q => Math.hypot(q[0] - x, q[1] - y) < 0.02));
    if (strokes.length !== before) { draw(); onStroke(strokes, true); }
  };
  new ResizeObserver(draw).observe(c);
  return {
    el: c,
    set(list) { strokes = (list || []).slice(); cur = null; draw(); },
    get() { return strokes; },
    tool(t) { tool = t; c.classList.toggle('active', t !== 'off'); },
    color(v) { color = v; },
    clear() { strokes = []; draw(); onStroke(strokes, true); }
  };
}

// ------------------------------------------------------------------ the presenter screen

let livePoll = null;
function stopLive() { if (livePoll) { livePoll.stop = true; livePoll = null; } document.body.classList.remove('presenting'); document.removeEventListener('keydown', presenterKeys); }
let presenterKeys = () => {};

async function showPresenter(id, params) {
  closeReader(); stopLive();
  const eink = params.get('eink') === '1';
  const app = clear(document.getElementById('app'));
  document.body.classList.add('presenting');
  const root = h('div', { class: 'presenter' + (eink ? ' eink' : '') }, h('div', { class: 'loading', style: { position: 'static', height: '200px' } }, h('div', { class: 'spin' })));
  app.append(root);
  // Size the screen to what is actually visible (some Android WebViews report a taller layout viewport).
  const fitRoot = () => {
    if (!root.isConnected) { window.removeEventListener('resize', fitRoot); return; }
    const vv = window.visualViewport;
    root.style.height = Math.floor(Math.min(vv ? vv.height : Infinity, window.innerHeight, document.documentElement.clientHeight || Infinity)) + 'px';
  };
  fitRoot(); window.addEventListener('resize', fitRoot);
  if (window.visualViewport) window.visualViewport.addEventListener('resize', fitRoot);
  let deck;
  try { deck = await openDeck(id); } catch (e) { clear(root).append(h('div', { class: 'empty' }, h('h2', null, 'Could not open the presentation'), h('div', { class: 'muted' }, e.message || String(e)), h('a', { href: '#/presentations' }, 'Back'))); return; }
  const meta = deck.meta;
  const myNotes = Object.assign({}, meta.myNotes || {});
  const ink = Object.assign({}, meta.ink || {});
  let cur = Math.max(0, Math.min(deck.count - 1, (+params.get('slide') || 1) - 1));
  let build = stepsOf(deck, cur).first, ptl = null;
  let black = false, startedAt = 0, showInk = true;

  const curBox = h('div', { class: 'p-cur' });
  const nextBox = h('div', { class: 'p-next' });
  fitSlide(curBox, deck); fitSlide(nextBox, deck);
  const curWrap = h('div', { class: 'p-curwrap' }, curBox);
  const inkL = inkLayer((strokes, done) => {
    ink[cur + 1] = strokes;
    sendLive(done ? 0 : 120);
    if (done) saveInk();
  });
  curWrap.append(inkL.el);
  // Laser pointer: a red dot on the slide that the audience screen shows too (not saved).
  const laserDot = h('div', { class: 'laser', hidden: true });
  const laserPad = h('div', { class: 'laser-pad' });
  curWrap.append(laserPad, laserDot);
  let laser = null, laserHide = 0;
  const setLaser = pos => {
    laser = pos;
    laserDot.hidden = !pos;
    if (pos) { laserDot.style.left = pos.x * 100 + '%'; laserDot.style.top = pos.y * 100 + '%'; }
    sendLive(pos ? 40 : 0);
  };
  const laserAt = e => { const r = laserPad.getBoundingClientRect(); return { x: +Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)).toFixed(4), y: +Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)).toFixed(4) }; };
  laserPad.addEventListener('pointermove', e => { clearTimeout(laserHide); setLaser(laserAt(e)); });
  laserPad.addEventListener('pointerdown', e => { e.preventDefault(); clearTimeout(laserHide); setLaser(laserAt(e)); });
  const laserOut = () => { clearTimeout(laserHide); laserHide = setTimeout(() => setLaser(null), 1200); };
  laserPad.addEventListener('pointerleave', laserOut); laserPad.addEventListener('pointerup', e => { if (e.pointerType !== 'mouse') laserOut(); });
  const notesEl = h('div', { class: 'p-notes' });
  const mine = h('textarea', { class: 'p-mine', placeholder: 'Your notes for this slide (saved in the presentation’s folder)…' });
  // Your own note on this slide: behind a button, open by itself when the slide already has one.
  let mineOpen = false;
  const mineBox = h('div', { class: 'p-col p-minebox', hidden: true }, h('div', { class: 'label' }, 'Your note on this slide'), mine);
  const mineBtn = h('button', { class: 'btn', onclick: () => { mineOpen = !mineBox.hidden ? false : true; drawMine(); if (mineOpen) mine.focus(); } }, '+ My note');
  const drawMine = () => {
    const has = !!(myNotes[cur + 1] || '').trim();
    mineBox.hidden = !(mineOpen || has);
    mineBtn.textContent = mineBox.hidden ? '+ My note' : (has ? 'My note ✓' : 'Hide my note');
  };
  const counter = h('span', { class: 'mono' });
  const clock = h('button', { class: 'btn quiet mono', title: 'Time since the first slide change · click to reset', onclick: () => { startedAt = Date.now(); } }, '0:00');
  const status = h('span', { class: 'p-status mono' });
  const blackBtn = h('button', { class: 'btn', title: 'Black screen (B)', onclick: () => { black = !black; blackBtn.classList.toggle('fill', black); sendLive(0); } }, 'Black');
  const tools = h('span', { class: 'row', style: { gap: '4px' } });
  let tool = 'off';
  const toolBtn = (t, label, title) => h('button', { class: 'btn' + (tool === t ? ' fill' : ''), title, onclick: () => {
    tool = tool === t ? 'off' : t; inkL.tool(tool === 'laser' ? 'off' : tool); laserPad.classList.toggle('active', tool === 'laser'); if (tool !== 'laser') setLaser(null); drawTools(); } }, label);
  const drawTools = () => clear(tools).append(
    toolBtn('laser', '● Laser', 'A laser pointer the audience sees (hover or touch the slide)'),
    toolBtn('pen', '✎ Pen', 'Draw on the slide'), toolBtn('hl', '▮ Marker', 'Highlight on the slide'), toolBtn('erase', '⌫ Eraser', 'Erase strokes'),
    h('button', { class: 'btn quiet', title: 'Clear this slide’s ink', onclick: () => inkL.clear() }, 'Clear'),
    ...['#e11d48', '#2563eb', '#16a34a', '#111111'].map(cv => h('button', { class: 'swatch', style: { background: cv }, title: 'Ink colour', onclick: () => inkL.color(cv) })),
    h('label', { class: 'row', style: { gap: '4px', fontSize: '12px' } }, (() => { const cb = h('input', { type: 'checkbox', checked: true }); cb.addEventListener('change', () => { showInk = cb.checked; sendLive(0); }); return cb; })(), 'Show ink to the audience'));
  drawTools();

  const bar = h('div', { class: 'p-bar' },
    h('a', { class: 'btn', href: '#/presentations', title: 'Close' }, '←'),
    h('b', { class: 'p-title' }, meta.title),
    counter, clock, status,
    h('span', { class: 'spacer' }),
    blackBtn);
  clear(root).append(bar,
    h('div', { class: 'p-body' },
      h('div', { class: 'p-left' },
        curWrap,
        h('div', { class: 'p-tools' }, tools),
        h('div', { class: 'p-notesrow' },
          h('div', { class: 'p-col' }, h('div', { class: 'row', style: { justifyContent: 'space-between' } }, h('div', { class: 'label' }, 'Speaker notes'), mineBtn), notesEl),
          mineBox)),
      h('div', { class: 'p-right' },
        h('div', { class: 'label' }, 'Next slide'), nextBox,
        h('div', { class: 'p-navs' },
          h('button', { class: 'btn p-nav p-prev', onclick: () => go(-1), title: 'Previous (←)' }, '‹ Previous'),
          h('button', { class: 'btn fill p-nav p-nextbtn', onclick: () => go(1), title: 'Next (→, space)' }, 'Next ›')))));

  let mineTimer = 0;
  mine.addEventListener('input', () => {
    myNotes[cur + 1] = mine.value; clearTimeout(mineTimer);
    const slide = cur + 1, text = mine.value;
    mineTimer = setTimeout(() => api(`presentations/${encodeURIComponent(id)}/notes`, { method: 'PUT', json: { slide, text } }).catch(fail), 800);
  });
  let inkTimer = 0;
  const saveInk = () => { clearTimeout(inkTimer); const slide = cur + 1, strokes = ink[slide] || []; inkTimer = setTimeout(() => api(`presentations/${encodeURIComponent(id)}/ink`, { method: 'PUT', json: { slide, strokes } }).catch(fail), 500); };

  let liveTimer = 0, lastSent = 0;
  function sendLive(delay) {
    clearTimeout(liveTimer);
    const send = () => { lastSent = Date.now(); api('live', { method: 'POST', json: { id, slide: cur, build, black, ink: showInk ? (ink[cur + 1] || []) : [], laser } }).then(r => showStatus(r)).catch(() => showStatus(null)); };
    if (!delay) send(); else if (Date.now() - lastSent > delay) send(); else liveTimer = setTimeout(send, delay);
  }
  function showStatus(r) {
    if (!r) { status.textContent = '⚠ Not connected to the tablet'; status.className = 'p-status mono bad'; return; }
    status.textContent = r.audiences ? `● ${r.audiences} audience screen${r.audiences === 1 ? '' : 's'}` : '○ No audience screen yet';
    status.className = 'p-status mono' + (r.audiences ? ' ok' : '');
    status.title = r.audiences ? '' : 'On the projector computer, open ' + ((r.addresses || [])[0] || {}).url + '/#/audience';
  }
  // Ask the tablet now and then how many audience screens follow.
  const statusLoop = async () => { while (document.body.contains(root)) { try { showStatus(await api('live')); } catch (_) { showStatus(null); } await new Promise(r => setTimeout(r, 5000)); } };

  const drawCounter = () => {
    const st = stepsOf(deck, cur);
    counter.textContent = `${cur + 1} / ${deck.count}` + (st.n ? ` · ▸ ${build}/${st.n}` : '');
    counter.title = st.n ? 'Slide · animation steps shown (Next plays the next one)' : '';
  };
  /** The current slide as the audience sees it: animations up to the current click done. */
  const drawState = () => { if (ptl) SlideAnim.apply(ptl, slideRoot(curBox), build, 0); };
  async function renderCur() {
    await deck.render(cur, curBox).catch(err => { curBox.textContent = 'This slide could not be drawn: ' + (err.message || err); });
    ptl = timelineOf(deck, cur, curBox);
    drawState();
  }
  async function show(i, atEnd) {
    cur = i;
    const st = stepsOf(deck, cur);
    build = atEnd ? st.n : st.first;
    drawCounter();
    const info = deck.slideInfo(cur);
    clear(notesEl).append(...(info.notes ? info.notes.split('\n').map(l => h('p', null, l)) : [h('div', { class: 'muted' }, deck.meta.type === 'pdf' ? 'PDF slides have no speaker notes.' : 'No speaker notes on this slide.')]));
    mine.value = myNotes[cur + 1] || '';
    mineOpen = false; drawMine();
    inkL.set(ink[cur + 1] || []);
    if (laser) { laser = null; laserDot.hidden = true; }
    sendLive(0);
    await renderCur();
    let n = cur + 1; while (n < deck.count && deck.hidden(n)) n++;
    if (n < deck.count) deck.render(n, nextBox).catch(() => {}); else clear(nextBox).append(h('div', { class: 'p-end' }, 'End of the presentation'));
  }
  function go(step) {
    if (!startedAt) startedAt = Date.now();
    // Animations first: Next plays the slide's next click step, Previous takes one back.
    const st = stepsOf(deck, cur);
    if (step > 0 && build < st.n) { build++; drawCounter(); drawState(); sendLive(0); return; }
    if (step < 0 && build > st.first) { build--; drawCounter(); drawState(); sendLive(0); return; }
    let i = cur + step;
    while (i >= 0 && i < deck.count && deck.hidden(i)) i += step;
    if (i < 0 || i >= deck.count) return;
    show(i, step < 0);
  }
  presenterKeys = e => {
    if (e.target && (e.target.tagName === 'TEXTAREA' || e.target.tagName === 'INPUT')) return;
    if (['ArrowRight', 'PageDown', ' ', 'ArrowDown', 'Enter'].includes(e.key)) { e.preventDefault(); go(1); }
    else if (['ArrowLeft', 'PageUp', 'ArrowUp', 'Backspace'].includes(e.key)) { e.preventDefault(); go(-1); }
    else if (e.key === 'b' || e.key === 'B' || e.key === '.') blackBtn.click();
    else if (e.key === 'Home') show(0); else if (e.key === 'End') show(deck.count - 1, true);
  };
  document.addEventListener('keydown', presenterKeys);
  // Swipe on the slide (when no pen is in use) to move on.
  let sx = 0, sy = 0;
  curWrap.addEventListener('pointerdown', e => { if (tool === 'off') { sx = e.clientX; sy = e.clientY; } });
  // (with the laser or pen on, touches draw or point instead of turning pages)
  curWrap.addEventListener('pointerup', e => {
    if (tool !== 'off') return;
    const dx = e.clientX - sx, dy = e.clientY - sy;
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) go(dx < 0 ? 1 : -1);
    else if (Math.abs(dx) < 8 && Math.abs(dy) < 8) { const r = curWrap.getBoundingClientRect(); go(e.clientX - r.left > r.width / 3 ? 1 : -1); }
  });
  new ResizeObserver(() => { clearTimeout(curBox._rt); curBox._rt = setTimeout(() => { renderCur(); }, 200); }).observe(curBox);
  setInterval(() => { if (startedAt) { const s = Math.floor((Date.now() - startedAt) / 1000); clock.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; } }, 1000);
  show(cur);
  statusLoop();
}

// ------------------------------------------------------------------ the audience screen

async function showAudience() {
  closeReader(); stopLive();
  document.body.classList.add('presenting');
  const app = clear(document.getElementById('app'));
  const stage = h('div', { class: 'aud-stage' });
  const blackout = h('div', { class: 'aud-black', hidden: true });
  const wait = h('div', { class: 'aud-wait' },
    h('h1', null, 'Audience screen'),
    h('p', null, 'This screen follows the presenter: start presenting on the tablet (Presentations in the side menu) or in another browser.'),
    h('button', { class: 'btn fill', onclick: () => goFull() }, 'Full screen'),
    h('p', { class: 'muted', style: { fontSize: '12px' } }, 'Press F for full screen, Esc to leave it.'));
  const root = h('div', { class: 'audience' }, stage, blackout, wait);
  app.append(root);
  const goFull = () => { const el = document.documentElement; (el.requestFullscreen || el.webkitRequestFullscreen || (() => {})).call(el); };
  const onKey = e => { if (e.key === 'f' || e.key === 'F') goFull(); };
  document.addEventListener('keydown', onKey);
  let hideT = 0;
  root.addEventListener('mousemove', () => { root.classList.add('cursor'); clearTimeout(hideT); hideT = setTimeout(() => root.classList.remove('cursor'), 2000); });

  const poll = { stop: false };
  livePoll = poll;
  let deck = null, deckId = '', shown = -1, layer = null, inkL = null, version = -1;
  const laserDot = h('div', { class: 'laser aud-laser', hidden: true });
  const place = () => {
    if (!deck) return;
    const W = window.innerWidth, H = window.innerHeight, a = deck.aspect;
    const w = Math.min(W, H / a), hh = w * a;
    Object.assign(stage.style, { width: w + 'px', height: hh + 'px', left: (W - w) / 2 + 'px', top: (H - hh) / 2 + 'px' });
  };
  let tl = null, built = 0, stepRun = null;
  const stateNow = () => { if (tl && layer) SlideAnim.apply(tl, slideRoot(layer), built, 0); };
  window.addEventListener('resize', () => { place(); if (deck && layer) deck.render(shown, layer).then(() => { tl = timelineOf(deck, shown, layer); stateNow(); }).catch(() => {}); });

  /** Plays click step `k` of the shown slide (its effects, then the state they leave). */
  function playStep(k) {
    const run = { skip: false };
    stepRun = run;
    const root = slideRoot(layer), dur = tl ? tl.stepDuration(k) : 0;
    const done = animateFor(dur * 1000, t => { if (tl && root) SlideAnim.apply(tl, root, k, Math.min(t, 1) * dur + (t >= 1 ? 1e-6 : 0)); }, run)
      .then(() => { if (stepRun === run) { stepRun = null; stateNow(); } });
    return done;
  }
  async function syncBuild(target) {
    if (stepRun) { stepRun.skip = true; await nextFrame(); }
    if (!tl) { built = target; return; }
    target = Math.max(0, Math.min(tl.steps(), target));
    if (target === built + 1) { const k = built; built = target; playStep(k); }
    else { built = target; stateNow(); }
  }

  async function apply(l) {
    if (!l || !l.id) { wait.hidden = false; return; }
    if (l.id !== deckId) {
      deckId = l.id; shown = -1;
      try { deck = await openDeck(l.id); } catch (e) { wait.hidden = false; wait.querySelector('p').textContent = 'Could not open the presentation: ' + (e.message || e); return; }
      clear(stage); layer = null; tl = null; place();
    }
    wait.hidden = true;
    blackout.hidden = !l.black;
    const build = typeof l.build === 'number' ? l.build : Infinity;
    if (l.slide !== shown) {
      if (stepRun) { stepRun.skip = true; await nextFrame(); }
      const next = h('div', { class: 'aud-layer', style: { opacity: '0' } });
      stage.append(next);
      await deck.render(l.slide, next).catch(err => { next.textContent = 'This slide could not be drawn: ' + (err.message || err); });
      const ntl = timelineOf(deck, l.slide, next);
      // Forward (as in a slide show) the slide comes in with its transition, before its first click;
      // backward or jumping it is shown as it was left.
      const forward = l.slide === shown + 1 || (shown >= 0 && l.slide > shown && deck.hidden && [...Array(l.slide - shown - 1)].every((_, k) => deck.hidden(shown + 1 + k)));
      const fresh = forward || shown < 0;
      const startAt = fresh ? 0 : build;
      if (ntl) SlideAnim.apply(ntl, slideRoot(next), Math.min(startAt, ntl.steps()), 0);
      const old = layer, prev = shown;
      try { await playTransition(deck, fresh ? transitionOf(deck, l.slide) : null, l.slide, prev, old, next, stage, null); } catch (e) { console.warn('transition', e); next.style.opacity = ''; }
      if (old) old.remove();
      layer = next; shown = l.slide; tl = ntl; built = startAt;
      if (!inkL) { inkL = inkLayer(() => {}); inkL.el.classList.add('aud-ink'); }
      stage.append(inkL.el, laserDot);
    }
    if (build !== built) await syncBuild(build === Infinity ? (tl ? tl.steps() : 0) : build);
    if (inkL) inkL.set(l.ink || []);
    laserDot.hidden = !l.laser;
    if (l.laser) { laserDot.style.left = l.laser.x * 100 + '%'; laserDot.style.top = l.laser.y * 100 + '%'; }
  }

  (async () => {
    try { const l = await api('live?role=audience'); version = l.version; await apply(l); } catch (_) {}
    while (!poll.stop) {
      try {
        const l = await api(`live?role=audience&since=${version}`, { timeout: 40000 });
        if (poll.stop) break;
        if (l.version !== version) { version = l.version; await apply(l); }
      } catch (_) { await new Promise(r => setTimeout(r, 2000)); }
    }
    document.removeEventListener('keydown', onKey);
  })();
}
