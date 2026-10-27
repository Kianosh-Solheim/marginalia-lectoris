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

async function pptxDeck(buf) {
  const mod = await import('./lib/pptx-renderer/pptx-renderer.browser.es.js');
  const host = h('div', { style: { position: 'fixed', left: '-20000px', top: '0', width: '960px', height: '540px', overflow: 'hidden' }, 'aria-hidden': 'true' });
  document.body.append(host);
  const viewer = await mod.PptxViewer.open(buf, host, { renderMode: 'slide', fitMode: 'none', zipLimits: mod.RECOMMENDED_ZIP_LIMITS, lazyMedia: true, lazySlides: true });
  const pd = viewer.presentationData;
  return {
    count: viewer.slideCount,
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

// ------------------------------------------------------------------ transitions

function transitionOf(info) {
  const t = (info && info.transition) || null;
  if (!t || !t.type) return null;
  const ms = Math.max(150, Math.min(3000, t.ms || 600));
  const ty = t.type;
  const map = { fade: 'fade', fadeBlack: 'fadeBlack', dissolve: 'fade', random: 'fade', randomBar: 'wipe', push: 'push', wipe: 'wipe', cover: 'cover', pull: 'uncover', uncover: 'uncover',
    split: 'split', zoom: 'zoom', newsflash: 'zoom', circle: 'circle', diamond: 'circle', plus: 'circle', wedge: 'circle', wheel: 'circle', strips: 'wipe', blinds: 'wipe', checker: 'fade', comb: 'wipe',
    cut: 'cut', none: 'cut' };
  let kind = map[ty] || 'fade'; // p14/p15 effects (vortex, morph, prism…) fall back to a fade
  if (ty === 'fade' && t.dir === 'thruBlk') kind = 'fadeBlack';
  return { kind, ms, dir: t.dir || '' };
}

/** Animates [incoming] over [outgoing] (both absolutely placed in the same stage). */
async function runTransition(tr, incoming, outgoing, forward = true) {
  if (!tr || tr.kind === 'cut' || !outgoing || !incoming.animate) return;
  const d = tr.dir || 'l', ms = tr.ms;
  const vec = { l: [-1, 0], r: [1, 0], u: [0, -1], d: [0, 1], lu: [-1, -1], ru: [1, -1], ld: [-1, 1], rd: [1, 1] }[d] || [-1, 0];
  const [dx, dy] = forward ? vec : [-vec[0], -vec[1]];
  const from = `translate(${-dx * 100}%, ${-dy * 100}%)`, to = `translate(${dx * 100}%, ${dy * 100}%)`;
  const opts = { duration: ms, easing: 'ease-in-out', fill: 'both' };
  const clip = { l: 'inset(0 0 0 100%)', r: 'inset(0 100% 0 0)', u: 'inset(100% 0 0 0)', d: 'inset(0 0 100% 0)' }[d] || 'inset(0 0 0 100%)';
  let a = [];
  switch (tr.kind) {
    case 'fade': a = [incoming.animate([{ opacity: 0 }, { opacity: 1 }], opts)]; break;
    case 'fadeBlack':
      a = [outgoing.animate([{ opacity: 1 }, { opacity: 0 }], { ...opts, duration: ms / 2 }),
        incoming.animate([{ opacity: 0 }, { opacity: 0, offset: 0.5 }, { opacity: 1 }], opts)]; break;
    case 'push': a = [incoming.animate([{ transform: from }, { transform: 'none' }], opts), outgoing.animate([{ transform: 'none' }, { transform: to }], opts)]; break;
    case 'cover': a = [incoming.animate([{ transform: from }, { transform: 'none' }], opts)]; break;
    case 'uncover': outgoing.style.zIndex = 3; a = [outgoing.animate([{ transform: 'none' }, { transform: to }], opts)]; break;
    case 'wipe': a = [incoming.animate([{ clipPath: clip }, { clipPath: 'inset(0 0 0 0)' }], opts)]; break;
    case 'split': a = [incoming.animate([{ clipPath: (d === 'vert' ? 'inset(0 50% 0 50%)' : 'inset(50% 0 50% 0)') }, { clipPath: 'inset(0 0 0 0)' }], opts)]; break;
    case 'zoom': a = [incoming.animate([{ transform: 'scale(0.6)', opacity: 0 }, { transform: 'none', opacity: 1 }], opts)]; break;
    case 'circle': a = [incoming.animate([{ clipPath: 'circle(0% at 50% 50%)' }, { clipPath: 'circle(75% at 50% 50%)' }], opts)]; break;
  }
  await Promise.all(a.map(x => x.finished.catch(() => {})));
  outgoing.style.zIndex = '';
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
  let deck;
  try { deck = await openDeck(id); } catch (e) { clear(root).append(h('div', { class: 'empty' }, h('h2', null, 'Could not open the presentation'), h('div', { class: 'muted' }, e.message || String(e)), h('a', { href: '#/presentations' }, 'Back'))); return; }
  const meta = deck.meta;
  const myNotes = Object.assign({}, meta.myNotes || {});
  const ink = Object.assign({}, meta.ink || {});
  let cur = Math.max(0, Math.min(deck.count - 1, (+params.get('slide') || 1) - 1));
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
        h('button', { class: 'btn p-nav', onclick: () => go(-1), title: 'Previous (←)' }, '▲ Previous'),
        h('div', { class: 'label', style: { marginTop: '10px' } }, 'Next slide'), nextBox,
        h('button', { class: 'btn fill p-nav', onclick: () => go(1), title: 'Next (→, space)' }, 'Next ▼'))));

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
    const send = () => { lastSent = Date.now(); api('live', { method: 'POST', json: { id, slide: cur, black, ink: showInk ? (ink[cur + 1] || []) : [], laser } }).then(r => showStatus(r)).catch(() => showStatus(null)); };
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

  async function show(i, dir) {
    cur = i;
    counter.textContent = `${cur + 1} / ${deck.count}`;
    const info = deck.slideInfo(cur);
    clear(notesEl).append(...(info.notes ? info.notes.split('\n').map(l => h('p', null, l)) : [h('div', { class: 'muted' }, deck.meta.type === 'pdf' ? 'PDF slides have no speaker notes.' : 'No speaker notes on this slide.')]));
    mine.value = myNotes[cur + 1] || '';
    mineOpen = false; drawMine();
    inkL.set(ink[cur + 1] || []);
    if (laser) { laser = null; laserDot.hidden = true; }
    sendLive(0);
    await deck.render(cur, curBox).catch(err => { curBox.textContent = 'This slide could not be drawn: ' + (err.message || err); });
    let n = cur + 1; while (n < deck.count && deck.hidden(n)) n++;
    if (n < deck.count) deck.render(n, nextBox).catch(() => {}); else clear(nextBox).append(h('div', { class: 'p-end' }, 'End of the presentation'));
  }
  function go(step) {
    let i = cur + step;
    while (i >= 0 && i < deck.count && deck.hidden(i)) i += step;
    if (i < 0 || i >= deck.count) return;
    if (!startedAt) startedAt = Date.now();
    show(i, step);
  }
  presenterKeys = e => {
    if (e.target && (e.target.tagName === 'TEXTAREA' || e.target.tagName === 'INPUT')) return;
    if (['ArrowRight', 'PageDown', ' ', 'ArrowDown', 'Enter'].includes(e.key)) { e.preventDefault(); go(1); }
    else if (['ArrowLeft', 'PageUp', 'ArrowUp', 'Backspace'].includes(e.key)) { e.preventDefault(); go(-1); }
    else if (e.key === 'b' || e.key === 'B' || e.key === '.') blackBtn.click();
    else if (e.key === 'Home') show(0); else if (e.key === 'End') show(deck.count - 1);
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
  new ResizeObserver(() => { clearTimeout(curBox._rt); curBox._rt = setTimeout(() => { deck.render(cur, curBox).catch(() => {}); }, 200); }).observe(curBox);
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
  window.addEventListener('resize', () => { place(); if (deck && layer) deck.render(shown, layer).catch(() => {}); });

  async function apply(l) {
    if (!l || !l.id) { wait.hidden = false; return; }
    if (l.id !== deckId) {
      deckId = l.id; shown = -1;
      try { deck = await openDeck(l.id); } catch (e) { wait.hidden = false; wait.querySelector('p').textContent = 'Could not open the presentation: ' + (e.message || e); return; }
      clear(stage); layer = null; place();
    }
    wait.hidden = true;
    blackout.hidden = !l.black;
    if (l.slide !== shown) {
      const next = h('div', { class: 'aud-layer' });
      stage.append(next);
      await deck.render(l.slide, next).catch(err => { next.textContent = 'This slide could not be drawn: ' + (err.message || err); });
      const tr = transitionOf(deck.slideInfo(l.slide));
      const forward = l.slide > shown;
      const old = layer;
      layer = next;
      if (old) { await runTransition(tr, next, old, forward); old.remove(); }
      shown = l.slide;
      if (!inkL) { inkL = inkLayer(() => {}); inkL.el.classList.add('aud-ink'); }
      stage.append(inkL.el, laserDot);
    }
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
