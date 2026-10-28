/* Morph: shapes that two slides share move, resize, turn and change colour from one slide to the
 * next; outlines turn into one another (a circle into a star) and the words two texts share travel
 * to their new place. Ported from DeckCraft (MIT OR Apache-2.0): crates/anim/src/morph.rs,
 * crates/geom/src/morph.rs and crates/render/src/morph_text.rs, drawn here on the slides' HTML. */
'use strict';

const SlideMorph = (() => {
  const { smooth, clampf } = SlideFX;
  const lerp = (a, b, t) => { const v = a + (b - a) * t; return Number.isFinite(v) ? v : (t < 0.5 ? a : b); };

  // ------------------------------------------------------------------ shapes on a slide

  const flipsOf = t => (t || '').match(/scale[XY]\(-1\)/g) || [];
  function infoOf(el, i) {
    const paras = [...el.querySelectorAll('[data-para]')];
    const text = paras.length ? paras.map(p => p.textContent).join('\n') : '';
    const svg = el.querySelector(':scope > svg');
    const fill = svg && svg.querySelector('path[fill]:not([fill="none"])');
    const img = el.querySelector('img');
    const base = el.style.transform || '';
    const rm = /rotate\(([-0-9.e]+)deg\)/.exec(base);
    return {
      el, i, id: el.dataset.spId, name: el.dataset.spName || '', kind: el.dataset.spKind || '', geom: el.dataset.spGeom || '',
      text, box: { x: parseFloat(el.style.left) || 0, y: parseFloat(el.style.top) || 0, w: parseFloat(el.style.width) || el.offsetWidth || 0, h: parseFloat(el.style.height) || el.offsetHeight || 0 },
      rot: rm ? parseFloat(rm[1]) : 0, flips: flipsOf(base), fill: fill ? fill.getAttribute('fill') : null, img: img ? img.getAttribute('src') : null,
      textEl: paras.length ? paras[0].parentElement : null, svg
    };
  }
  const topShapes = root => [...root.children].filter(e => e.dataset && e.dataset.spOrigin === 'slide').map(infoOf);

  /** Pairs (old, new) that Morph animates (morph_pairs): forced `!!name`, the same name, the same kind and text, the same kind and preset without text. */
  function pairs(a, b) {
    const out = [], usedA = new Set(), usedB = new Set();
    const passes = [
      (x, y) => x.name.startsWith('!!') && x.name === y.name,
      (x, y) => x.name !== '' && x.name === y.name,
      (x, y) => x.kind === y.kind && x.text.trim() !== '' && x.text === y.text,
      (x, y) => x.kind === y.kind && !!x.geom && x.geom === y.geom && !x.text.trim() && !y.text.trim()
    ];
    for (const pass of passes) for (const sa of a) {
      if (usedA.has(sa.i)) continue;
      const sb = b.find(y => !usedB.has(y.i) && pass(sa, y));
      if (sb) { usedA.add(sa.i); usedB.add(sb.i); out.push([sa, sb]); }
    }
    return out;
  }
  /** A box from `a` to `b` at `t`: position and size linearly, rotation the shortest way round. */
  function morphBox(a, ra, b, rb, t) {
    let d = ((rb - ra) % 360 + 360) % 360; if (d > 180) d -= 360;
    return { x: lerp(a.x, b.x, t), y: lerp(a.y, b.y, t), w: lerp(a.w, b.w, t), h: lerp(a.h, b.h, t), rot: ra + d * t };
  }

  // ------------------------------------------------------------------ outlines (geom/src/morph.rs)

  const P = (x, y) => ({ x, y });
  const plerp = (a, b, t) => P(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
  const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  function arcToCubics(x1, y1, rx, ry, phi, fa, fs, x2, y2) {
    // SVG arc → cubic curves (endpoint to centre parameterisation).
    if (rx === 0 || ry === 0) return [[P(x1 + (x2 - x1) / 3, y1 + (y2 - y1) / 3), P(x1 + 2 * (x2 - x1) / 3, y1 + 2 * (y2 - y1) / 3), P(x2, y2)]];
    const r = phi * Math.PI / 180, cs = Math.cos(r), sn = Math.sin(r);
    rx = Math.abs(rx); ry = Math.abs(ry);
    const dx = (x1 - x2) / 2, dy = (y1 - y2) / 2, x1p = cs * dx + sn * dy, y1p = -sn * dx + cs * dy;
    const lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
    if (lam > 1) { const k = Math.sqrt(lam); rx *= k; ry *= k; }
    const num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p, den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
    let co = Math.sqrt(Math.max(0, num / den)); if (fa === fs) co = -co;
    const cxp = co * rx * y1p / ry, cyp = -co * ry * x1p / rx;
    const cx = cs * cxp - sn * cyp + (x1 + x2) / 2, cy = sn * cxp + cs * cyp + (y1 + y2) / 2;
    const ang = (ux, uy, vx, vy) => { const a = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a; };
    const t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
    let dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
    if (!fs && dt > 0) dt -= 2 * Math.PI; else if (fs && dt < 0) dt += 2 * Math.PI;
    const segs = Math.max(1, Math.ceil(Math.abs(dt) / (Math.PI / 2))), d = dt / segs, k = 4 / 3 * Math.tan(d / 4);
    const pt = a => P(cx + rx * Math.cos(a) * cs - ry * Math.sin(a) * sn, cy + rx * Math.cos(a) * sn + ry * Math.sin(a) * cs);
    const der = a => P(-rx * Math.sin(a) * cs - ry * Math.cos(a) * sn, -rx * Math.sin(a) * sn + ry * Math.cos(a) * cs);
    const out = [];
    for (let i = 0; i < segs; i++) {
      const a0 = t1 + i * d, a1 = a0 + d, p0 = pt(a0), p1 = pt(a1), d0 = der(a0), d1 = der(a1);
      out.push([P(p0.x + k * d0.x, p0.y + k * d0.y), P(p1.x - k * d1.x, p1.y - k * d1.y), p1]);
    }
    return out;
  }
  /** Contours of SVG path data, every segment a cubic curve: [{closed, pts: [start, (h, h, anchor)…]}]. */
  function contours(d) {
    const toks = (d || '').match(/[a-zA-Z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?/g) || [];
    const out = [];
    let cur = null, pos = P(0, 0), start = P(0, 0), cmd = '', i = 0, lastCtl = null, lastQ = null;
    const keep = c => { if (c && (c.pts.length - 1) / 3 >= 1 && c.pts.every(p => Number.isFinite(p.x) && Number.isFinite(p.y))) out.push(c); };
    const pen = () => (cur || (cur = { closed: false, pts: [pos] }));
    const lineTo = to => { const c = pen(), f = c.pts[c.pts.length - 1]; c.pts.push(plerp(f, to, 1 / 3), plerp(f, to, 2 / 3), to); };
    const n = () => parseFloat(toks[i++]);
    while (i < toks.length) {
      if (/[a-zA-Z]/.test(toks[i])) cmd = toks[i++];
      if (!cmd) break;
      const rel = cmd === cmd.toLowerCase(), C = cmd.toUpperCase();
      const ox = rel ? pos.x : 0, oy = rel ? pos.y : 0;
      if (C === 'Z') { if (cur && cur.pts.length > 1) { const f = cur.pts[0], l = cur.pts[cur.pts.length - 1]; if (f.x !== l.x || f.y !== l.y) lineTo(f); cur.closed = true; keep(cur); } cur = null; pos = start; cmd = ''; continue; }
      if (i >= toks.length || /[a-zA-Z]/.test(toks[i])) { if (C !== 'Z') { cmd = ''; } continue; }
      let ctl = null, q = null;
      switch (C) {
        case 'M': { const p = P(ox + n(), oy + n()); if (cur) keep(cur); cur = { closed: false, pts: [p] }; pos = start = p; cmd = rel ? 'l' : 'L'; break; }
        case 'L': { const p = P(ox + n(), oy + n()); lineTo(p); pos = p; break; }
        case 'H': { const p = P(ox + n(), pos.y); lineTo(p); pos = p; break; }
        case 'V': { const p = P(pos.x, oy + n()); lineTo(p); pos = p; break; }
        case 'C': { const a = P(ox + n(), oy + n()), b = P(ox + n(), oy + n()), p = P(ox + n(), oy + n()); pen().pts.push(a, b, p); pos = p; ctl = b; break; }
        case 'S': { const a = lastCtl ? P(2 * pos.x - lastCtl.x, 2 * pos.y - lastCtl.y) : pos, b = P(ox + n(), oy + n()), p = P(ox + n(), oy + n()); pen().pts.push(a, b, p); pos = p; ctl = b; break; }
        case 'Q': { const c = P(ox + n(), oy + n()), p = P(ox + n(), oy + n()); pen().pts.push(plerp(pos, c, 2 / 3), plerp(p, c, 2 / 3), p); pos = p; q = c; break; }
        case 'T': { const c = lastQ ? P(2 * pos.x - lastQ.x, 2 * pos.y - lastQ.y) : pos, p = P(ox + n(), oy + n()); pen().pts.push(plerp(pos, c, 2 / 3), plerp(p, c, 2 / 3), p); pos = p; q = c; break; }
        case 'A': {
          const rx = n(), ry = n(), phi = n(), fa = n(), fs = n(), p = P(ox + n(), oy + n());
          for (const [a, b, e] of arcToCubics(pos.x, pos.y, rx, ry, phi, !!fa, !!fs, p.x, p.y)) pen().pts.push(a, b, e);
          pos = p; break;
        }
        default: i = toks.length;
      }
      lastCtl = ctl; lastQ = q;
    }
    if (cur) keep(cur);
    return out;
  }
  const curves = c => Math.floor((c.pts.length - 1) / 3);
  const curveLength = c => (dist(c[0], c[3]) + dist(c[0], c[1]) + dist(c[1], c[2]) + dist(c[2], c[3])) / 2;
  function splitCurve(c, n, out) {
    let [p0, p1, p2] = c; const p3 = c[3];
    for (let j = n; j >= 2; j--) {
      const t = 1 / j, ab = plerp(p0, p1, t), bc = plerp(p1, p2, t), cd = plerp(p2, p3, t), abc = plerp(ab, bc, t), bcd = plerp(bc, cd, t), m = plerp(abc, bcd, t);
      out.push(ab, abc, m); p0 = m; p1 = bcd; p2 = cd;
    }
    out.push(p1, p2, p3);
  }
  function refine(c, n) {
    const have = curves(c);
    if (have >= n || have === 0) return { closed: c.closed, pts: c.pts.slice() };
    const cs = []; for (let k = 0; k < have; k++) cs.push(c.pts.slice(3 * k, 3 * k + 4));
    const len = cs.map(q => curveLength(q) + 1e-9), parts = cs.map(() => 1);
    for (let r = 0; r < n - have; r++) { let best = 0; for (let k = 1; k < cs.length; k++) if (len[k] / parts[k] > len[best] / parts[best]) best = k; parts[best]++; }
    const out = [c.pts[0]];
    cs.forEach((q, k) => splitCurve(q, parts[k], out));
    return { closed: c.closed, pts: out };
  }
  function curvesFor(na, nb) {
    const gcd = (x, y) => (y === 0 ? x : gcd(y, x % y));
    const hi = Math.max(na, nb, 1);
    let n = na > 0 && nb > 0 ? (na / gcd(na, nb)) * nb : hi;
    if (n > 60) n = hi;
    return n * Math.max(1, Math.ceil(12 / n));
  }
  function bounds(pts) { let r = null; for (const p of pts) r = r ? { x0: Math.min(r.x0, p.x), y0: Math.min(r.y0, p.y), x1: Math.max(r.x1, p.x), y1: Math.max(r.y1, p.y) } : { x0: p.x, y0: p.y, x1: p.x, y1: p.y }; return r; }
  const centerOf = pts => { const b = bounds(pts); return b ? P((b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2) : P(0, 0); };
  function turn(pts, s, back) {
    const q = back ? pts.slice().reverse() : pts.slice();
    if (s === 0) return q;
    const body = q.slice(1), cut = Math.min(3 * s, body.length);
    return [...(cut >= 1 ? [body[cut - 1]] : []), ...body.slice(cut), ...body.slice(0, cut)];
  }
  function matchPair(a, b, back) {
    const bp = back ? turn(b.pts, 0, true) : b.pts.slice();
    const turnA = !b.closed && a.closed;
    const [fixed, free] = turnA ? [bp, a.pts] : [a.pts, bp];
    const fc = centerOf(fixed), gc = centerOf(free);
    const tries = turnA || b.closed ? Math.floor((free.length - 1) / 3) : 1;
    let best = free.slice(), bestCost = Infinity;
    const stepBy = Math.max(1, Math.ceil(tries / 128));
    for (let s = 0; s < Math.max(tries, 1); s += stepBy) {
      const q = turn(free, s, false);
      let cost = 0;
      for (let k = 0; k < q.length && k < fixed.length; k++) { const dx = (q[k].x - gc.x) - (fixed[k].x - fc.x), dy = (q[k].y - gc.y) - (fixed[k].y - fc.y); cost += dx * dx + dy * dy; if (cost >= bestCost) break; }
      if (cost < bestCost) { bestCost = cost; best = q; }
    }
    const [pa, pb] = turnA ? [best, bp] : [a.pts.slice(), best];
    return [{ closed: a.closed, pts: pa }, { closed: b.closed, pts: pb }, bestCost];
  }
  function place(c, from, to) {
    const p = centerOf(c.pts);
    if (!from || !to) return p;
    const fw = from.x1 - from.x0, fh = from.y1 - from.y0;
    const fx = fw > 0 ? (p.x - from.x0) / fw : 0.5, fy = fh > 0 ? (p.y - from.y0) / fh : 0.5;
    return P(to.x0 + (to.x1 - to.x0) * fx, to.y0 + (to.y1 - to.y0) * fy);
  }
  function align(a, b, ba, bb) {
    const prs = [];
    for (let k = 0; k < Math.max(a.length, b.length); k++) {
      const ca = a[k], cb = b[k], n = curvesFor(ca ? curves(ca) : 0, cb ? curves(cb) : 0);
      const point = (c, from, to, closed) => { const p = place(c, from, to); return { closed, pts: Array.from({ length: 1 + 3 * n }, () => p) }; };
      if (ca && cb) prs.push([refine(ca, n), refine(cb, n), false]);
      else if (ca) prs.push([refine(ca, n), point(ca, ba, bb, ca.closed), true]);
      else if (cb) prs.push([point(cb, bb, ba, cb.closed), refine(cb, n), true]);
    }
    let best = null;
    for (const back of [false, true]) {
      const xs = [], ys = []; let cost = 0;
      for (const [x, y, pt] of prs) {
        if (pt) { xs.push(x); ys.push(back ? { closed: y.closed, pts: turn(y.pts, 0, true) } : y); }
        else { const [mx, my, c] = matchPair(x, y, back); xs.push(mx); ys.push(my); cost += c; }
      }
      if (!best || cost < best[2]) best = [xs, ys, cost];
    }
    return best ? [best[0], best[1]] : [[], []];
  }
  const toD = cs => cs.map(c => { let s = `M${c.pts[0].x.toFixed(2)},${c.pts[0].y.toFixed(2)}`; for (let k = 1; k + 2 < c.pts.length; k += 3) s += `C${c.pts[k].x.toFixed(2)},${c.pts[k].y.toFixed(2)} ${c.pts[k + 1].x.toFixed(2)},${c.pts[k + 1].y.toFixed(2)} ${c.pts[k + 2].x.toFixed(2)},${c.pts[k + 2].y.toFixed(2)}`; return c.closed ? s + 'Z' : s; }).join('');

  /** Two SVGs' paths brought to the same structure once; `at(t, sa, sb)` gives each path's `d` at `t`. */
  function GeometryMorph(pathsA, pathsB) {
    const ca = pathsA.map(p => contours(p.getAttribute('d'))), cb = pathsB.map(p => contours(p.getAttribute('d')));
    const ba = bounds(ca.flat().flatMap(c => c.pts)), bb = bounds(cb.flat().flatMap(c => c.pts));
    const parts = [];
    for (let k = 0; k < Math.max(pathsA.length, pathsB.length); k++) { const [pa, pb] = align(ca[k] || [], cb[k] || [], ba, bb); parts.push({ a: pathsA[k] || null, b: pathsB[k] || null, ca: pa, cb: pb }); }
    return {
      parts,
      at(t, sa, sb) {
        return parts.map(part => toD(part.ca.map((x, i) => {
          const y = part.cb[i];
          return { closed: x.closed && y.closed, pts: x.pts.map((p, j) => plerp(P(p.x * sa[0], p.y * sa[1]), P(y.pts[j].x * sb[0], y.pts[j].y * sb[1]), t)) };
        })));
      }
    };
  }

  // ------------------------------------------------------------------ colours

  function parseColor(v) {
    if (!v) return null;
    let m = /^#([0-9a-f]{6})$/i.exec(v);
    if (m) return [parseInt(m[1].slice(0, 2), 16), parseInt(m[1].slice(2, 4), 16), parseInt(m[1].slice(4, 6), 16), 1];
    m = /^rgba?\(([^)]+)\)$/i.exec(v);
    if (m) { const p = m[1].split(',').map(s => parseFloat(s)); return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1]; }
    return null;
  }
  const mixColor = (a, b, t) => `rgba(${Math.round(lerp(a[0], b[0], t))}, ${Math.round(lerp(a[1], b[1], t))}, ${Math.round(lerp(a[2], b[2], t))}, ${lerp(a[3], b[3], t).toFixed(3)})`;

  // ------------------------------------------------------------------ text by words (render/src/morph_text.rs)

  /** Words (or characters) of a shape's text: {key, x, y, h, size, style} in slide px. */
  function tokens(info, root, chars) {
    const out = [];
    if (!info.textEl) return out;
    const rr = root.getBoundingClientRect(), k = (rr.width / root.offsetWidth) || 1;
    const walker = document.createTreeWalker(info.textEl, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      const s = node.nodeValue;
      const re = chars ? /\S/gu : /\S+/gu;
      let m;
      const cs = getComputedStyle(node.parentElement);
      const style = { fontFamily: cs.fontFamily, fontSize: cs.fontSize, fontWeight: cs.fontWeight, fontStyle: cs.fontStyle, color: cs.color, textDecoration: cs.textDecorationLine, letterSpacing: cs.letterSpacing, textShadow: cs.textShadow };
      while ((m = re.exec(s))) {
        const r = document.createRange();
        r.setStart(node, m.index); r.setEnd(node, m.index + m[0].length);
        const rect = r.getClientRects()[0];
        if (!rect || rect.width === 0) continue;
        out.push({ key: m[0], x: (rect.left - rr.left) / k, y: (rect.top - rr.top) / k, h: rect.height / k, size: parseFloat(cs.fontSize) || 16, style });
      }
    }
    return out;
  }
  function tokenEl(tok) {
    const s = document.createElement('span');
    Object.assign(s.style, tok.style, { position: 'absolute', left: '0', top: '0', whiteSpace: 'pre', lineHeight: tok.h + 'px', height: tok.h + 'px', transformOrigin: '0 0', pointerEvents: 'none', margin: '0', padding: '0' });
    s.textContent = tok.key;
    return s;
  }
  const sameLook = (a, b) => a.size === b.size && a.style.color === b.style.color && a.style.fontFamily === b.style.fontFamily && a.style.fontWeight === b.style.fontWeight && a.style.fontStyle === b.style.fontStyle;

  function TextMorph(layer, ia, rootA, ib, rootB, chars) {
    const ta = tokens(ia, rootA, chars), tb = tokens(ib, rootB, chars);
    if (!ta.length && !tb.length) return null;
    const used = new Set();
    const match = tb.map(t => { const i = ta.findIndex((x, k) => !used.has(k) && x.key === t.key); if (i >= 0) used.add(i); return i >= 0 ? i : null; });
    const shared = match.map((i, j) => (i === null ? null : [ta[i], tb[j]])).filter(Boolean);
    const n = shared.length;
    let s = 0, c = [0, 0];
    if (n) {
      s = shared.reduce((acc, [x, y]) => acc + (y.size / x.size), 0) / n;
      c = shared.reduce((acc, [x, y]) => [acc[0] + (y.x - x.x * s), acc[1] + (y.y - x.y * s)], [0, 0]).map(v => v / n);
    }
    const fit = n && Number.isFinite(s) && s > 0;
    const items = [];
    const movedA = new Set();
    match.forEach((i, j) => {
      if (i === null) return;
      movedA.add(i);
      const a = ta[i], b = tb[j], same = sameLook(a, b), ratio = a.size > 0 && b.size > 0 ? b.size / a.size : 1;
      const ea = tokenEl(a); layer.append(ea);
      const eb = same ? null : tokenEl(b); if (eb) layer.append(eb);
      items.push(t => {
        const x = lerp(a.x, b.x, t), y = lerp(a.y, b.y, t);
        ea.style.transform = `translate(${x}px, ${y}px) scale(${lerp(1, ratio, t)})`;
        ea.style.opacity = same ? 1 : 1 - t;
        if (eb) { eb.style.transform = `translate(${x}px, ${y}px) scale(${lerp(1 / ratio, 1, t)})`; eb.style.opacity = t; }
      });
    });
    ta.forEach((a, i) => {
      if (movedA.has(i)) return;
      const e = tokenEl(a); layer.append(e);
      items.push((t, box) => {
        let x, y, k = 1;
        if (fit) { k = lerp(1, s, t); x = a.x * k + c[0] * t; y = a.y * k + c[1] * t; }
        else { x = a.x + (box.x - ia.box.x); y = a.y + (box.y - ia.box.y); }
        e.style.transform = `translate(${x}px, ${y}px) scale(${k})`; e.style.opacity = 1 - t;
      });
    });
    tb.forEach((b, j) => {
      if (match[j] !== null) return;
      const e = tokenEl(b); layer.append(e);
      items.push((t, box) => {
        let x, y, k = 1;
        if (fit) { k = (1 - t) / s + t; x = b.x * k - c[0] * ((1 - t) / s); y = b.y * k - c[1] * ((1 - t) / s); }
        else { x = b.x + (box.x - ib.box.x); y = b.y + (box.y - ib.box.y); }
        e.style.transform = `translate(${x}px, ${y}px) scale(${k})`; e.style.opacity = t;
      });
    });
    return { draw: (t, box) => items.forEach(f => f(t, box)) };
  }

  // ------------------------------------------------------------------ the frame

  function cloneWithCanvas(el) {
    const c = el.cloneNode(true);
    const src = el.querySelectorAll('canvas'), dst = c.querySelectorAll('canvas');
    src.forEach((s, i) => { try { dst[i].getContext('2d').drawImage(s, 0, 0); } catch (_) {} });
    return c;
  }
  function backdrop(root) {
    const c = cloneWithCanvas(root);
    c.querySelectorAll('[data-sp-origin="slide"]').forEach(e => e.remove());
    Object.assign(c.style, { position: 'absolute', left: '0', top: '0', transform: 'none' });
    return c;
  }
  /** Places a clone of shape `info` on `box` (slide px, rotation in degrees). */
  function setBox(el, info, box, flips) {
    const cx = box.x + box.w / 2 - (info.box.x + info.box.w / 2), cy = box.y + box.h / 2 - (info.box.y + info.box.h / 2);
    const sx = info.box.w > 0 ? box.w / info.box.w : 1, sy = info.box.h > 0 ? box.h / info.box.h : 1;
    el.style.transformOrigin = '50% 50%';
    el.style.transform = `translate(${cx}px, ${cy}px) rotate(${box.rot}deg) scale(${Number.isFinite(sx) ? sx : 1}, ${Number.isFinite(sy) ? sy : 1}) ${flips.join(' ')}`;
  }

  /**
   * Builds the Morph from slide root `rootA` (shown) to `rootB` (both laid out at the same scale)
   * in `host`; returns {draw(t)} for linear progress t (eased here), or null when there is
   * nothing to morph. `option`: objects, words or characters. `hiddenB`: ids of shapes of the new
   * slide that start hidden (entrance animations).
   */
  function build(host, rootA, rootB, option, hiddenB) {
    if (!rootA || !rootB) return null;
    const a = topShapes(rootA), b = topShapes(rootB).filter(x => !(hiddenB && hiddenB.has(String(x.id))));
    const pr = pairs(a, b);
    const W = rootB.offsetWidth, H = rootB.offsetHeight;
    const frame = document.createElement('div');
    Object.assign(frame.style, { position: 'absolute', left: '0', top: '0', width: W + 'px', height: H + 'px', overflow: 'hidden', transformOrigin: '0 0', transform: rootB.style.transform || 'none', pointerEvents: 'none' });
    const backA = backdrop(rootA), backB = backdrop(rootB);
    frame.append(backA, backB);
    const shapesLayer = document.createElement('div');
    Object.assign(shapesLayer.style, { position: 'absolute', left: '0', top: '0', width: W + 'px', height: H + 'px' });
    const textLayer = document.createElement('div');
    Object.assign(textLayer.style, { position: 'absolute', left: '0', top: '0', width: W + 'px', height: H + 'px' });
    frame.append(shapesLayer, textLayer);
    host.append(frame);
    const byText = option === 'words' || option === 'characters', chars = option === 'characters';
    const draws = [];
    const add = (info, opacity) => { const c = cloneWithCanvas(info.el); shapesLayer.append(c); return c; };
    // Unpaired shapes of the old slide fade out in place.
    for (const sa of a) if (!pr.some(([x]) => x === sa)) { const c = add(sa); draws.push(s => { c.style.opacity = String(1 - s); }); }
    for (const sb of b) {
      const p = pr.find(([, y]) => y === sb);
      if (!p) { const c = add(sb); draws.push(s => { c.style.opacity = String(s); }); continue; }
      const sa = p[0];
      const sameText = sa.text.trim() === sb.text.trim();
      const outlined = k => k === 'shape' || k === 'picture' || k === 'connector';
      const reshape = sa.geom !== sb.geom && outlined(sa.kind) && outlined(sb.kind) && sameText && sa.svg && sb.svg;
      const sameBox = Math.abs(sa.box.w - sb.box.w) < 0.5 && Math.abs(sa.box.h - sb.box.h) < 0.5;
      const textSame = !sa.textEl || (sb.textEl && sa.textEl.innerHTML === sb.textEl.innerHTML);
      const rotated = sa.rot || sb.rot || sa.flips.length || sb.flips.length;
      const hasText = sa.text.trim() && sb.text.trim() && sa.kind !== 'group' && sa.kind !== 'table' && sb.kind !== 'group' && sb.kind !== 'table';
      const flipsAt = s => (s >= 0.5 ? sb.flips : sa.flips);
      if (reshape) {
        // The outline turns from the old geometry into the new one; the fill colour blends.
        const c = add(sb);
        const svg = c.querySelector(':scope > svg');
        const pathsB = [...sb.svg.querySelectorAll(':scope > path, :scope > g > path')], pathsA = [...sa.svg.querySelectorAll(':scope > path, :scope > g > path')];
        const live = [...svg.querySelectorAll(':scope > path, :scope > g > path')];
        const gm = GeometryMorph(pathsA, pathsB);
        // Paths only the old outline has are drawn too (shrinking away).
        while (live.length < gm.parts.length) { const src = gm.parts[live.length].a; const x = src.cloneNode(true); (live[0] ? live[0].parentNode : svg).append(x); live.push(x); }
        const fills = gm.parts.map(pt => [parseColor(pt.a && pt.a.getAttribute('fill')), parseColor(pt.b && pt.b.getAttribute('fill'))]);
        const strokes = gm.parts.map(pt => [parseColor(pt.a && pt.a.getAttribute('stroke')), parseColor(pt.b && pt.b.getAttribute('stroke'))]);
        const texts = c.querySelectorAll('[data-para]');
        draws.push(s => {
          const box = morphBox(sa.box, sa.rot, sb.box, sb.rot, s);
          // The SVG is drawn at the current size itself rather than scaled, so outlines keep their width.
          c.style.left = box.x + 'px'; c.style.top = box.y + 'px'; c.style.width = box.w + 'px'; c.style.height = box.h + 'px';
          c.style.transformOrigin = '50% 50%'; c.style.transform = `rotate(${box.rot}deg) ${flipsAt(s).join(' ')}`;
          svg.setAttribute('width', box.w); svg.setAttribute('height', box.h); svg.setAttribute('viewBox', `0 0 ${box.w} ${box.h}`);
          const ds = gm.at(s, [sa.box.w ? box.w / sa.box.w : 1, sa.box.h ? box.h / sa.box.h : 1], [sb.box.w ? box.w / sb.box.w : 1, sb.box.h ? box.h / sb.box.h : 1]);
          ds.forEach((d, k) => {
            const el = live[k]; if (!el) return;
            el.setAttribute('d', d);
            const [fa, fb] = fills[k], [sa2, sb2] = strokes[k];
            if (fa && fb) { el.setAttribute('fill', mixColor(fa, fb, s)); el.removeAttribute('fill-opacity'); }
            else if (fa || fb) { el.setAttribute('fill', mixColor(fa || fb, fa || fb, 0)); el.setAttribute('fill-opacity', String(fa ? 1 - s : s)); }
            if (sa2 && sb2) el.setAttribute('stroke', mixColor(sa2, sb2, s));
            else if (sa2 || sb2) { el.setAttribute('stroke', mixColor(sa2 || sb2, sa2 || sb2, 0)); el.setAttribute('stroke-opacity', String(sa2 ? 1 - s : s)); }
          });
          texts.forEach(t => { t.style.transform = ''; });
        });
        continue;
      }
      if (sameText && textSame && (sameBox || !hasText) && (sa.img === sb.img)) {
        // The same shape: it moves, resizes and turns; a changed fill colour blends (the old one underneath).
        const under = sa.fill !== sb.fill ? add(sa) : null;
        const c = add(sb);
        draws.push(s => {
          const box = morphBox(sa.box, sa.rot, sb.box, sb.rot, s);
          if (under) setBox(under, sa, box, flipsAt(s));
          setBox(c, sb, box, flipsAt(s)); c.style.opacity = under ? String(s) : '1';
        });
        continue;
      }
      // Otherwise the new shape fades in under the fading old one, both on the same moving box; texts
      // they share (or all text, with the Words or Characters option) travel word by word.
      const cb = add(sb), ca = add(sa);
      let tm = null;
      if ((byText || sameText) && hasText && !rotated) {
        tm = TextMorph(textLayer, sa, sa.el.closest('[data-slide-root]') || sa.el.parentElement, sb, sb.el.closest('[data-slide-root]') || sb.el.parentElement, chars);
        if (tm) { if (sa.textEl) ca.querySelector('[data-para]').parentElement.style.visibility = 'hidden'; if (sb.textEl) cb.querySelector('[data-para]').parentElement.style.visibility = 'hidden'; }
      }
      draws.push(s => {
        const box = morphBox(sa.box, sa.rot, sb.box, sb.rot, s);
        setBox(cb, sb, box, flipsAt(s)); setBox(ca, sa, box, flipsAt(s));
        cb.style.opacity = String(s); ca.style.opacity = String(1 - s);
        if (tm) tm.draw(s, box);
      });
    }
    const draw = t => {
      const s = smooth(clampf(t, 0, 1, 0));
      backB.style.opacity = String(s);
      draws.forEach(f => f(s));
    };
    draw(0);
    return { draw, dispose: () => frame.remove() };
  }

  return { build, pairs, contours, GeometryMorph, morphBox };
})();
