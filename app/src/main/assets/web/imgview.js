/* Marginalia Lectoris — a picture at full size: zoom with the wheel (or + and −), drag to move,
 * double-click to zoom in or out, ← and → (or the arrows) for the other pictures, caption below,
 * Save and Copy. [pics]: [{ url: string | () => Promise<string>, caption, name }], [at]: which first.
 */
function openImageViewer(pics, at, opts = {}) {
  if (!pics || !pics.length) return;
  document.querySelectorAll('.imgview').forEach(x => x.remove());
  let i = Math.max(0, Math.min(at || 0, pics.length - 1)), scale = 1, x = 0, y = 0, fit = 1, seq = 0;
  const img = h('img', { class: 'ivimg', alt: '', draggable: 'false' });
  const stage = h('div', { class: 'ivstage' }, img);
  const cap = h('div', { class: 'ivcap serif' }), count = h('span', { class: 'mono' }), pct = h('span', { class: 'mono muted' });
  const canCopy = !!(navigator.clipboard && window.ClipboardItem && window.isSecureContext);
  const prev = h('button', { class: 'ivnav prev', title: 'Previous picture (←)', onclick: () => go(-1) }, '‹');
  const next = h('button', { class: 'ivnav next', title: 'Next picture (→)', onclick: () => go(1) }, '›');
  const bar = h('div', { class: 'ivbar' }, count, pct, h('span', { style: { flex: 1 } }),
    h('button', { class: 'btn', title: 'Zoom out (−)', onclick: () => zoomBy(1 / 1.4) }, '−'),
    h('button', { class: 'btn', title: 'Whole picture (0)', onclick: () => reset() }, 'Fit'),
    h('button', { class: 'btn', title: 'Zoom in (+)', onclick: () => zoomBy(1.4) }, '+'),
    h('button', { class: 'btn', title: 'Save the picture', onclick: () => save() }, 'Save'),
    canCopy ? h('button', { class: 'btn', title: 'Copy the picture, to paste it elsewhere', onclick: () => copy() } , 'Copy') : null,
    h('button', { class: 'btn fill', title: 'Close (Esc)', onclick: () => close() }, 'Close'));
  const root = h('div', { class: 'imgview', role: 'dialog', 'aria-label': 'Picture', tabindex: '-1' }, bar, stage, pics.length > 1 ? prev : null, pics.length > 1 ? next : null, cap);
  if (!canCopy) bar.title = 'To copy the picture, right-click it and choose Copy Image.';
  const apply = () => { img.style.transform = `translate(${x}px, ${y}px) scale(${scale * fit})`; pct.textContent = Math.round(scale * fit * 100) + '%'; stage.classList.toggle('zoomed', scale > 1.01); };
  const fitNow = () => {
    const w = img.naturalWidth || 1, hh = img.naturalHeight || 1, r = stage.getBoundingClientRect();
    fit = Math.min((r.width - 40) / w, (r.height - 40) / hh, 1.5 * Math.max(1, 900 / Math.max(w, hh)));
    img.style.width = w + 'px'; img.style.height = hh + 'px';
    img.style.left = (r.width - w) / 2 + 'px'; img.style.top = (r.height - hh) / 2 + 'px';
  };
  const reset = () => { scale = 1; x = 0; y = 0; fitNow(); apply(); };
  // Zooms around a point of the stage (the mouse), so what is under it stays under it.
  const zoomAt = (f, cx, cy) => {
    const r = stage.getBoundingClientRect(), px = (cx == null ? r.width / 2 : cx - r.left) - r.width / 2, py = (cy == null ? r.height / 2 : cy - r.top) - r.height / 2;
    const ns = Math.max(1, Math.min(12, scale * f)), k = ns / scale;
    x = px - (px - x) * k; y = py - (py - y) * k; scale = ns;
    if (scale <= 1.001) { x = 0; y = 0; }
    apply();
  };
  const zoomBy = f => zoomAt(f);
  async function show() {
    const my = ++seq, p = pics[i];
    count.textContent = pics.length > 1 ? `${i + 1} of ${pics.length}` : '';
    clear(cap); cap.hidden = !p.caption; if (p.caption) cap.append(p.caption);
    img.style.opacity = '0.25';
    let src = p.url; try { if (typeof src === 'function') src = await src(); } catch (_) { src = null; }
    if (my !== seq) return;
    if (!src) { img.removeAttribute('src'); cap.hidden = false; clear(cap).append(h('span', { class: 'muted' }, 'This picture could not be loaded.')); return; }
    img.onload = () => { if (my === seq) { img.style.opacity = ''; reset(); } };
    img.src = src;
    p.src = src;
  }
  const go = by => { i = (i + by + pics.length) % pics.length; show(); };
  const fileName = p => (p.name || 'picture').replace(/[\\/:*?"<>|\u2018\u2019\u201c\u201d\u2013\u2014]+/g, ' ').replace(/\s+/g, ' ').trim() || 'picture';
  async function blobOf(p) { const r = await fetch(p.src); return r.blob(); }
  async function save() {
    const p = pics[i]; if (!p.src) return;
    try { const b = await blobOf(p); const ext = (b.type.split('/')[1] || 'png').replace('jpeg', 'jpg').replace('svg+xml', 'svg'); saveBlob(b, /\.\w{2,4}$/.test(fileName(p)) ? fileName(p) : fileName(p) + '.' + ext); } catch (e) { fail(e); }
  }
  async function copy() {
    const p = pics[i]; if (!p.src) return;
    try {
      // The clipboard takes PNG pictures; others are drawn into one first.
      let b = await blobOf(p);
      if (b.type !== 'image/png') { const c = document.createElement('canvas'); c.width = img.naturalWidth; c.height = img.naturalHeight; c.getContext('2d').drawImage(img, 0, 0); b = await new Promise(res => c.toBlob(res, 'image/png')); }
      await navigator.clipboard.write([new ClipboardItem({ 'image/png': b })]); toast('Picture copied');
    } catch (e) { toast('Could not copy it; right-click the picture and choose Copy Image'); }
  }
  // dragging and wheel
  let drag = null;
  stage.addEventListener('pointerdown', e => { if (e.button) return; drag = { px: e.clientX, py: e.clientY, x, y, moved: false }; stage.setPointerCapture(e.pointerId); });
  stage.addEventListener('pointermove', e => { if (!drag) return; const dx = e.clientX - drag.px, dy = e.clientY - drag.py; if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true; if (scale > 1.01) { x = drag.x + dx; y = drag.y + dy; apply(); } });
  stage.addEventListener('pointerup', e => {
    if (!drag) return; const d = drag; drag = null;
    // Not zoomed in: a sideways drag goes to the next or previous picture; a click beside the picture closes.
    if (scale <= 1.01 && d.moved && Math.abs(e.clientX - d.px) > 80 && pics.length > 1) go(e.clientX < d.px ? 1 : -1);
    else if (!d.moved && e.target === stage && scale <= 1.01) close();
  });
  stage.addEventListener('dblclick', e => { if (scale > 1.01) reset(); else zoomAt(2.5, e.clientX, e.clientY); });
  stage.addEventListener('wheel', e => { e.preventDefault(); zoomAt(Math.exp(-e.deltaY * (e.deltaMode ? 0.05 : 0.0015)), e.clientX, e.clientY); }, { passive: false });
  const onKey = e => {
    if (e.key === 'Escape') { e.preventDefault(); close(); }
    else if (e.key === 'ArrowRight' && pics.length > 1) { e.preventDefault(); go(1); }
    else if (e.key === 'ArrowLeft' && pics.length > 1) { e.preventDefault(); go(-1); }
    else if (e.key === '+' || e.key === '=') { e.preventDefault(); zoomBy(1.4); }
    else if (e.key === '-') { e.preventDefault(); zoomBy(1 / 1.4); }
    else if (e.key === '0') { e.preventDefault(); reset(); }
    else return;
    e.stopPropagation();
  };
  const onResize = () => { if (img.naturalWidth) { fitNow(); apply(); } };
  function close() { root.remove(); document.removeEventListener('keydown', onKey, true); window.removeEventListener('resize', onResize); if (opts.onClose) opts.onClose(); }
  document.addEventListener('keydown', onKey, true);
  window.addEventListener('resize', onResize);
  document.body.append(root);
  // The keys come here, not to the book page that was clicked.
  try { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); } catch (_) {}
  root.focus();
  show();
  return { close, update(list) { const cur = pics[i]; pics = list; const j = list.findIndex(p => p.key && cur && p.key === cur.key); if (j >= 0) i = j; count.textContent = pics.length > 1 ? `${i + 1} of ${pics.length}` : ''; if (pics.length > 1 && !prev.isConnected) root.append(prev, next); } };
}
