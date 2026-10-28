/* Slide transitions, Morph and object animations, ported to JavaScript from DeckCraft
 * (https://github.com/storytold/deckcraft, MIT OR Apache-2.0, see lib/deckcraft/LICENSE-MIT):
 * crates/anim (transition.rs, transition_fx.rs, transition_geom.rs, easing.rs, mesh.rs,
 * morph.rs, effects.rs, timeline.rs, path.rs), crates/geom/src/morph.rs and the PPTX
 * mappings in crates/pptx (maps.rs, read/timing.rs).
 *
 * A transition frame is a list of layers drawn over black: each maps part of a source image
 * (old slide, new slide, their backgrounds or contents alone, black or white) onto a quad in
 * slide-unit space (0..1, y down), with an opacity per corner. Here they are drawn with WebGL
 * from pictures of the two slides. */
'use strict';

const SlideFX = (() => {
  const PI = Math.PI;
  const clampf = (v, lo, hi, nan) => (Number.isNaN(v) ? nan : Math.min(hi, Math.max(lo, v)));
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
  const fin = (v, d) => (Number.isFinite(v) ? v : d);
  const lerp = (a, b, t) => { const v = a + (b - a) * t; return Number.isFinite(v) ? v : (t < 0.5 ? a : b); };

  // ------------------------------------------------------------------ easing (easing.rs)

  function ease(p, accel, decel, bounce) {
    p = clampf(p, 0, 1, 0);
    let a = clampf(accel, 0, 1, 0), d = clampf(decel, 0, 1, 0);
    if (a + d > 1) { const s = a + d; a /= s; d /= s; }
    const v = 2 / (2 - a - d);
    let y;
    if (a > 0 && p < a) y = v * p * p / (2 * a);
    else if (d > 0 && p > 1 - d) { const q = 1 - p; y = 1 - v * q * q / (2 * d); }
    else y = v * a / 2 + v * (p - a);
    const b = clampf(bounce, 0, 1, 0);
    if (b > 0) {
      const hit = 1 - b;
      if (p < hit) { const q = hit > 0 ? p / hit : 1; y = q * q; }
      else { const s = (p - hit) / b; y = 1 - bounceHops(s) * 0.25; }
    }
    return clampf(y, -1, 2, 0);
  }
  function bounceHops(s) {
    s = clampf(s, 0, 1, 1);
    const hops = 3, k = Math.min(Math.floor(s * hops), hops - 1), local = s * hops - k;
    return Math.pow(0.5, k) * Math.sin(local * PI);
  }
  function bounceOut(p) {
    p = clampf(p, 0, 1, 0);
    const n = 7.5625, d = 2.75;
    let y;
    if (p < 1 / d) y = n * p * p;
    else if (p < 2 / d) { const q = p - 1.5 / d; y = n * q * q + 0.75; }
    else if (p < 2.5 / d) { const q = p - 2.25 / d; y = n * q * q + 0.9375; }
    else { const q = p - 2.625 / d; y = n * q * q + 0.984375; }
    return Math.min(y, 1);
  }
  const smooth = p => { p = clampf(p, 0, 1, 0); return p * p * (3 - 2 * p); };

  // ------------------------------------------------------------------ geometry (transition_geom.rs)

  const ss = smooth;
  const q2 = x => ease(x, 0.5, 0.5, 0);
  const powInout = (x, n) => { x = clamp(x, 0, 1); return x < 0.5 ? 0.5 * Math.pow(2 * x, n) : 1 - 0.5 * Math.pow(2 - 2 * x, n); };
  const ein = x => { x = clamp(x, 0, 1); return x * x; };
  const eout = x => { x = 1 - clamp(x, 0, 1); return 1 - x * x; };
  const win = (t, a, b) => (b <= a ? (t >= b ? 1 : 0) : clamp((t - a) / (b - a), 0, 1));

  const M64 = (1n << 64n) - 1n;
  function hash64(x) {
    x = (x + 0x9E3779B97F4A7C15n) & M64;
    x = ((x ^ (x >> 30n)) * 0xBF58476D1CE4E5B9n) & M64;
    x = ((x ^ (x >> 27n)) * 0x94D049BB133111EBn) & M64;
    return x ^ (x >> 31n);
  }
  const rndCache = new Map();
  /** Deterministic pseudo-random value in [0, 1), the same as DeckCraft's for the same `i`. */
  function rnd(i) {
    let v = rndCache.get(i);
    if (v === undefined) { v = Number(hash64(BigInt(i) & M64) >> 11n) / 9007199254740992; rndCache.set(i, v); }
    return v;
  }

  const FULL = [0, 0, 1, 1];
  const corners = r => [[r[0], r[1]], [r[2], r[1]], [r[2], r[3]], [r[0], r[3]]];
  const offset = (r, dx, dy) => [r[0] + dx, r[1] + dy, r[2] + dx, r[3] + dy];
  const center = r => [(r[0] + r[2]) / 2, (r[1] + r[3]) / 2];
  const scaleRectAbout = (r, s, c) => [c[0] + (r[0] - c[0]) * s, c[1] + (r[1] - c[1]) * s, c[0] + (r[2] - c[0]) * s, c[1] + (r[3] - c[1]) * s];
  const scaleRect = (r, s) => scaleRectAbout(r, s, center(r));

  // Layers: {source, src, quad, uv, alpha, fade}
  const L = {
    rect: (source, src, dst, alpha) => ({ source, src, quad: corners(dst), uv: corners(src), alpha, fade: [1, 1, 1, 1] }),
    full: (source, alpha) => L.rect(source, FULL, FULL, alpha),
    quad: (source, src, quad, alpha) => ({ source, src, quad, uv: corners(src), alpha, fade: [1, 1, 1, 1] }),
    inplace: (source, dst, alpha) => L.rect(source, dst, dst, alpha),
    triUv(source, p, uv, fade, alpha) {
      const xs = [uv[0][0], uv[1][0], uv[2][0]], ys = [uv[0][1], uv[1][1], uv[2][1]];
      return { source, src: [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)], quad: [p[0], p[1], p[2], p[2]], uv: [uv[0], uv[1], uv[2], uv[2]], alpha, fade: [fade[0], fade[1], fade[2], fade[2]] };
    },
    faded: (l, fade) => ({ ...l, fade }),
    shade: (l, k) => ({ ...l, source: 'Black', alpha: l.alpha * clamp(k, 0, 1) })
  };
  function sanitized(l) {
    const f = v => (Number.isFinite(v) ? clamp(v, -1e6, 1e6) : 0);
    return {
      source: l.source,
      src: l.src.map(v => clamp(f(v), 0, 1)),
      quad: l.quad.map(c => [f(c[0]), f(c[1])]),
      uv: l.uv.map(c => [clamp(f(c[0]), 0, 1), clamp(f(c[1]), 0, 1)]),
      alpha: clampf(l.alpha, 0, 1, 0),
      fade: l.fade.map(a => clampf(a, 0, 1, 0))
    };
  }

  const moved = (source, dx, dy, alpha) => L.rect(source, FULL, offset(FULL, dx, dy), alpha);
  const scaled = (source, s, cx, cy, dx, dy, alpha) => L.rect(source, FULL, offset(scaleRectAbout(FULL, s, [cx, cy]), dx, dy), alpha);
  function rot(p, c, deg, a) {
    const r = deg * PI / 180, s = Math.sin(r), co = Math.cos(r);
    const x = (p[0] - c[0]) * a, y = p[1] - c[1];
    return [c[0] + (x * co - y * s) / a, c[1] + x * s + y * co];
  }
  const rotQuad = (q, c, deg, a) => q.map(p => rot(p, c, deg, a));
  function dir(opt, def) {
    const d = [0, 0];
    if (opt.includes('l')) d[0] = -1;
    if (opt.includes('r')) d[0] = 1;
    if (opt.includes('u')) d[1] = -1;
    if (opt.includes('d')) d[1] = 1;
    return d[0] === 0 && d[1] === 0 ? def : d;
  }
  function softRect(source, src, dst, f, alpha) {
    const v = [L.rect(source, src, dst, alpha)];
    if (f <= 1e-6) return v;
    const [l, t, r, b] = dst, fx = f, fy = f * 16 / 9;
    const uvAt = (x, y) => [r > l ? src[0] + (clamp(x, l, r) - l) / (r - l) * (src[2] - src[0]) : src[0], b > t ? src[1] + (clamp(y, t, b) - t) / (b - t) * (src[3] - src[1]) : src[1]];
    const q = (pts, fade) => {
      const uv = pts.map(p => uvAt(p[0], p[1]));
      const xs = uv.map(p => p[0]), ys = uv.map(p => p[1]);
      v.push({ source, src: [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)], quad: pts, uv, alpha, fade });
    };
    q([[l, t - fy], [r, t - fy], [r, t], [l, t]], [0, 0, 1, 1]);
    q([[l, b], [r, b], [r, b + fy], [l, b + fy]], [1, 1, 0, 0]);
    q([[l - fx, t], [l, t], [l, b], [l - fx, b]], [0, 1, 1, 0]);
    q([[r, t], [r + fx, t], [r + fx, b], [r, b]], [1, 0, 0, 1]);
    q([[l - fx, t - fy], [l, t - fy], [l, t], [l - fx, t]], [0, 0, 1, 0]);
    q([[r, t - fy], [r + fx, t - fy], [r + fx, t], [r, t]], [0, 0, 0, 1]);
    q([[r, b], [r + fx, b], [r + fx, b + fy], [r, b + fy]], [1, 0, 0, 0]);
    q([[l - fx, b], [l, b], [l, b + fy], [l - fx, b + fy]], [0, 1, 0, 0]);
    return v;
  }
  function field(source, n, m, f) {
    n = clamp(n, 1, 256); m = clamp(m, 1, 256);
    const vals = new Float64Array((n + 1) * (m + 1));
    for (let j = 0; j <= m; j++) for (let i = 0; i <= n; i++) { const v = f(i / n, j / m); vals[j * (n + 1) + i] = Number.isFinite(v) ? clamp(v, 0, 1) : 0; }
    const at = (i, j) => vals[j * (n + 1) + i] || 0;
    const out = [];
    for (let j = 0; j < m; j++) {
      const y0 = j / m, y1 = (j + 1) / m;
      let run = null;
      for (let i = 0; i <= n; i++) {
        const full = i < n && at(i, j) >= 1 && at(i + 1, j) >= 1 && at(i, j + 1) >= 1 && at(i + 1, j + 1) >= 1;
        if (full) { if (run === null) run = i; continue; }
        if (run !== null) { out.push(L.inplace(source, [run / n, y0, i / n, y1], 1)); run = null; }
        if (i === n) break;
        const c = [at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j + 1)];
        if (c.every(v => v <= 0)) continue;
        out.push(L.faded(L.inplace(source, [i / n, y0, (i + 1) / n, y1], 1), c));
      }
    }
    return out;
  }
  const edge = (c, p, w) => (w <= 1e-9 ? (p <= c ? 1 : 0) : clamp((c - p) / w + 0.5, 0, 1));
  function boundary(ang, a) {
    const dx = Math.sin(ang) / a, dy = -Math.cos(ang);
    const m = Math.max(Math.abs(dx) / 0.5, Math.abs(dy) / 0.5, 1e-9);
    return [0.5 + dx / m, 0.5 + dy / m];
  }
  function fan(source, a, steps, f) {
    const n = clamp(steps, 8, 720);
    let angles = [];
    for (let k = 0; k <= n; k++) angles.push(2 * PI * k / n);
    for (const c of [[1, 0], [1, 1], [0, 1], [0, 0]]) { const ang = Math.atan2((c[0] - 0.5) * a, -(c[1] - 0.5)); angles.push(((ang % (2 * PI)) + 2 * PI) % (2 * PI)); }
    angles.sort((x, y) => x - y);
    angles = angles.filter((x, i) => i === 0 || Math.abs(x - angles[i - 1]) >= 1e-9);
    const out = [], c = [0.5, 0.5];
    for (let i = 0; i + 1 < angles.length; i++) {
      const x = angles[i], y = angles[i + 1];
      const fx = clamp(f(x), 0, 1), fy = clamp(f(y), 0, 1);
      if (fx <= 0 && fy <= 0) continue;
      const bx = boundary(x, a), by = boundary(y, a);
      out.push(L.triUv(source, [c, bx, by], [c, bx, by], [(fx + fy) / 2, fx, fy], 1));
    }
    return out;
  }
  function clipPoly(poly, n, d) {
    const out = [], side = p => n[0] * p[0] + n[1] * p[1] - d;
    for (let i = 0; i < poly.length; i++) {
      const p = poly[i], q = poly[(i + 1) % poly.length];
      const sp = side(p), sq = side(q);
      if (sp <= 0) out.push(p);
      if ((sp < 0 && sq > 0) || (sp > 0 && sq < 0)) { const k = sp / (sp - sq); out.push([p[0] + (q[0] - p[0]) * k, p[1] + (q[1] - p[1]) * k]); }
    }
    return out;
  }
  function polyLayers(source, poly, alpha, map) {
    if (!poly.length) return [];
    const first = poly[0], out = [];
    for (let i = 1; i + 1 < poly.length; i++) out.push(L.triUv(source, [map(first), map(poly[i]), map(poly[i + 1])], [first, poly[i], poly[i + 1]], [1, 1, 1], alpha));
    return out;
  }

  // 3-D: x right and y down in slide heights (the slide spans x ∈ [−a/2, a/2], y ∈ [−½, ½] at z = 0), z away from the viewer.
  const v3 = (x, y, z) => ({ x, y, z });
  const add3 = (p, o) => v3(p.x + o.x, p.y + o.y, p.z + o.z);
  const sub3 = (p, o) => v3(p.x - o.x, p.y - o.y, p.z - o.z);
  function yaw(p0, c, deg) { const r = deg * PI / 180, s = Math.sin(r), co = Math.cos(r), p = sub3(p0, c); return add3(v3(p.x * co - p.z * s, p.y, p.x * s + p.z * co), c); }
  function pitch(p0, c, deg) { const r = deg * PI / 180, s = Math.sin(r), co = Math.cos(r), p = sub3(p0, c); return add3(v3(p.x, p.y * co + p.z * s, -p.y * s + p.z * co), c); }
  function roll(p0, c, deg) { const r = deg * PI / 180, s = Math.sin(r), co = Math.cos(r), p = sub3(p0, c); return add3(v3(p.x * co - p.y * s, p.x * s + p.y * co, p.z), c); }
  function Cam(a, d) {
    return {
      a, d,
      p(v) { const k = d / Math.max(d + v.z, d * 0.02); return [v.x * k / a + 0.5, v.y * k + 0.5]; },
      rect(r) { return corners(r).map(p => v3((p[0] - 0.5) * a, p[1] - 0.5, 0)); },
      unit(x, y) { return v3((x - 0.5) * a, y - 0.5, 0); },
      quad(c) { return c.map(v => this.p(v)); }
    };
  }
  function area(q) { let s = 0; for (let i = 0; i < 4; i++) { const a = q[i], b = q[(i + 1) % 4]; s += a[0] * b[1] - b[0] * a[1]; } return s / 2; }
  function card(cam, source, src, c, alpha, shade, back) {
    const q = cam.quad(c);
    if (!q.every(p => Number.isFinite(p[0]) && Number.isFinite(p[1]))) return [];
    const front = area(q) > 0;
    if (!front && !back) return [];
    const l = L.quad(source, src, q, alpha), v = [l];
    if (shade > 0.001) v.push(L.shade(l, shade));
    return v;
  }
  function facingShade(c, k) {
    const u = sub3(c[1], c[0]), w = sub3(c[3], c[0]);
    const n = v3(u.y * w.z - u.z * w.y, u.z * w.x - u.x * w.z, u.x * w.y - u.y * w.x);
    const len = Math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z);
    if (len < 1e-12) return 0;
    return clamp(k * (1 - Math.abs(n.z / len)), 0, 1);
  }

  // ------------------------------------------------------------------ the transitions (model/anim.rs, transition_fx.rs)

  /** [id, label, category, default duration ms, effect options] */
  const TRANSITIONS = [
    ['none', 'None', 'Subtle', 0, []], ['morph', 'Morph', 'Subtle', 2000, ['objects', 'words', 'characters']],
    ['fade', 'Fade', 'Subtle', 700, ['smoothly', 'throughBlack']], ['push', 'Push', 'Subtle', 1000, ['u', 'd', 'l', 'r']],
    ['wipe', 'Wipe', 'Subtle', 1000, ['r', 'l', 'u', 'd', 'ru', 'lu', 'rd', 'ld']], ['split', 'Split', 'Subtle', 1500, ['vertOut', 'vertIn', 'horzOut', 'horzIn']],
    ['reveal', 'Reveal', 'Subtle', 2000, ['smoothlyLeft', 'smoothlyRight', 'blackLeft', 'blackRight']], ['cut', 'Cut', 'Subtle', 0, ['', 'throughBlack']],
    ['randomBar', 'Random Bars', 'Subtle', 1000, ['vert', 'horz']], ['shape', 'Shape', 'Subtle', 2000, ['circle', 'diamond', 'plus', 'in', 'out']],
    ['uncover', 'Uncover', 'Subtle', 1000, ['l', 'r', 'u', 'd', 'lu', 'ru', 'ld', 'rd']], ['cover', 'Cover', 'Subtle', 1000, ['l', 'r', 'u', 'd', 'lu', 'ru', 'ld', 'rd']],
    ['flash', 'Flash', 'Subtle', 1000, []], ['fallOver', 'Fall Over', 'Exciting', 2000, ['l', 'r']], ['drape', 'Drape', 'Exciting', 2000, ['l', 'r']],
    ['curtains', 'Curtains', 'Exciting', 2000, []], ['wind', 'Wind', 'Exciting', 2000, ['l', 'r']], ['prestige', 'Prestige', 'Exciting', 2000, []],
    ['fracture', 'Fracture', 'Exciting', 2000, []], ['crush', 'Crush', 'Exciting', 2000, []], ['peelOff', 'Peel Off', 'Exciting', 1500, ['l', 'r']],
    ['pageCurlDouble', 'Page Curl', 'Exciting', 1500, ['l', 'r', 'singleL', 'singleR']], ['airplane', 'Airplane', 'Exciting', 2000, ['l', 'r']],
    ['origami', 'Origami', 'Exciting', 2000, ['l', 'r']], ['dissolve', 'Dissolve', 'Exciting', 1200, []], ['checker', 'Checkerboard', 'Exciting', 1500, ['horz', 'vert']],
    ['blinds', 'Blinds', 'Exciting', 1600, ['vert', 'horz']], ['clock', 'Clock', 'Exciting', 2000, ['clockwise', 'counterClockwise', 'wedge']],
    ['ripple', 'Ripple', 'Exciting', 1400, ['center', 'lu', 'ru', 'ld', 'rd']], ['honeycomb', 'Honeycomb', 'Exciting', 3000, []],
    ['glitter', 'Glitter', 'Exciting', 2500, ['l', 'r', 'u', 'd', 'hexL', 'hexR', 'hexU', 'hexD']], ['vortex', 'Vortex', 'Exciting', 3000, ['l', 'r', 'u', 'd']],
    ['shred', 'Shred', 'Exciting', 2000, ['stripsIn', 'stripsOut', 'particlesIn', 'particlesOut']], ['switch', 'Switch', 'Exciting', 1250, ['l', 'r']],
    ['flip', 'Flip', 'Exciting', 1250, ['l', 'r']], ['gallery', 'Gallery', 'Exciting', 1500, ['l', 'r']], ['cube', 'Cube', 'Exciting', 1250, ['l', 'r', 'u', 'd']],
    ['doors', 'Doors', 'Exciting', 1400, ['vert', 'horz']], ['box', 'Box', 'Exciting', 1250, ['l', 'r', 'u', 'd']], ['comb', 'Comb', 'Exciting', 1000, ['horz', 'vert']],
    ['zoom', 'Zoom', 'Exciting', 1000, ['in', 'out', 'rotate']], ['random', 'Random', 'Exciting', 2000, []],
    ['pan', 'Pan', 'Dynamic Content', 1000, ['u', 'd', 'l', 'r']], ['ferris', 'Ferris Wheel', 'Dynamic Content', 2000, ['l', 'r']],
    ['conveyor', 'Conveyor', 'Dynamic Content', 2000, ['l', 'r']], ['rotate', 'Rotate', 'Dynamic Content', 2000, ['l', 'r', 'u', 'd']],
    ['window', 'Window', 'Dynamic Content', 1250, ['vert', 'horz']], ['orbit', 'Orbit', 'Dynamic Content', 2000, ['l', 'r', 'u', 'd']],
    ['flythrough', 'Fly Through', 'Dynamic Content', 1000, ['in', 'out', 'inBounce', 'outBounce']]
  ];
  const RANDOM_KINDS = ['fade', 'push', 'wipe', 'split', 'reveal', 'randomBar', 'shape', 'uncover', 'cover', 'fallOver', 'drape', 'curtains', 'wind', 'prestige', 'fracture',
    'crush', 'peelOff', 'pageCurlDouble', 'airplane', 'origami', 'dissolve', 'checker', 'blinds', 'clock', 'ripple', 'honeycomb', 'glitter', 'vortex', 'shred', 'switch',
    'flip', 'gallery', 'cube', 'doors', 'box', 'comb', 'zoom', 'pan', 'ferris', 'conveyor', 'rotate', 'window', 'orbit', 'flythrough'];

  function layers(kind, o, t, a, depth) {
    switch (kind) {
      case 'none': return [L.full('New', 1)];
      case 'cut': return o === 'throughBlack' && t < 0.5 ? [L.full('Black', 1)] : [L.full('New', 1)];
      case 'fade': if (o === 'throughBlack') return throughBlack(t, 0.5, 0.5); return [L.full('Old', 1), L.full('New', ss(t))];
      case 'morph': return [L.full('Old', 1), L.full('New', ss(t))];
      case 'push': { const [dx, dy] = dir(o, [-1, 0]), s = q2(t); return [moved('Old', dx * s, dy * s, 1), moved('New', dx * (s - 1), dy * (s - 1), 1)]; }
      case 'uncover': { const [dx, dy] = dir(o, [-1, 0]); const s = o.length === 2 ? ein(win(t, 0.05, 0.35)) : powInout(t, 3); return [L.full('New', 1), moved('Old', dx * s, dy * s, 1)]; }
      case 'cover': { const [dx, dy] = dir(o, [-1, 0]); const s = o.length === 2 ? 1 - Math.pow(1 - win(t, 0.07, 0.95), 3) : powInout(t, 4); return [L.full('Old', 1), moved('New', dx * (s - 1), dy * (s - 1), 1)]; }
      case 'wipe': return wipe(o, t);
      case 'split': return split(o, t);
      case 'reveal': return reveal(o, t);
      case 'randomBar': return randomBars(o === 'vert', t);
      case 'shape': return shape(o, t, a);
      case 'flash': return flash(t);
      case 'dissolve': return dissolve(t);
      case 'checker': return checker(o === 'vert', t, a);
      case 'blinds': return blinds(o === 'vert', t, a);
      case 'clock': return clock(o, t, a);
      case 'ripple': return ripple(o, t, a);
      case 'comb': return comb(o === 'vert', t);
      case 'zoom': return zoom(o, t, a);
      case 'pan': return pan(o, t);
      case 'fallOver': return fallOver(o === 'r', t, a);
      case 'drape': return drape(o === 'r', t, a);
      case 'curtains': return curtains(t);
      case 'wind': return wind(o === 'r', t, a);
      case 'prestige': return prestige(t, a);
      case 'fracture': return fracture(t, a);
      case 'crush': return crush(t, a);
      case 'peelOff': return peelOff(o === 'r', t);
      case 'pageCurlDouble': return pageCurl(o, t);
      case 'airplane': return airplane(o === 'r', t);
      case 'origami': return origami(o === 'r', t);
      case 'honeycomb': return honeycomb(t, a);
      case 'glitter': return glitter(o, t, a);
      case 'vortex': return vortex(o, t, a);
      case 'shred': return shred(o, t, a);
      case 'switch': return switchFx(o === 'r', t, a);
      case 'flip': return flip(o === 'r', t, a);
      case 'gallery': return gallery(o === 'r', t, a);
      case 'conveyor': return conveyor(o === 'r', t, a);
      case 'cube': case 'box': case 'rotate': case 'orbit': return prism(kind, o, t, a);
      case 'doors': case 'window': return doors(kind === 'window', o === 'horz', t, a);
      case 'ferris': return ferris(o === 'r', t, a);
      case 'flythrough': return flythrough(o, t);
      case 'random':
        if (depth === 0) {
          let h = 0x51EDn;
          for (const b of new TextEncoder().encode(o)) h = hash64(h ^ BigInt(b));
          const k = RANDOM_KINDS[Number(h % BigInt(RANDOM_KINDS.length))] || 'fade';
          const info = TRANSITIONS.find(x => x[0] === k);
          return layers(k, (info && info[4][0]) || '', t, a, depth + 1);
        }
        return [L.full('Old', 1), L.full('New', ss(t))];
      default: return [L.full('Old', 1), L.full('New', ss(t))];
    }
  }
  function throughBlack(t, out, inn) {
    const v = [L.full('Black', 1)];
    if (t < out) v.push(L.full('Old', 1 - ss(win(t, 0, out))));
    if (t > inn) v.push(L.full('New', ss(win(t, inn, 1))));
    return v;
  }
  const WIPE_SOFT = 0.8;
  const wipePos = t => -WIPE_SOFT / 2 + (1 + WIPE_SOFT) * 1.05 * t;
  function wipe(o, t) {
    const c = wipePos(t);
    const p = { l: (x) => 1 - x, u: (x, y) => 1 - y, d: (x, y) => y, lu: (x, y) => (2 - x - y) / 2, ru: (x, y) => (1 + x - y) / 2, ld: (x, y) => (1 - x + y) / 2, rd: (x, y) => (x + y) / 2 }[o] || (x => x);
    const diag = o.length === 2;
    const [n, m] = diag ? [32, 18] : (o === 'u' || o === 'd') ? [1, 36] : [48, 1];
    return [L.full('Old', 1), ...field('New', n, m, (x, y) => edge(c, p(x, y), WIPE_SOFT))];
  }
  function split(o, t) {
    const horz = o.startsWith('horz'), inward = o.endsWith('In'), c = wipePos(t);
    const p = (x, y) => { const d = horz ? Math.abs(y - 0.5) * 2 : Math.abs(x - 0.5) * 2; return inward ? 1 - d : d; };
    const [n, m] = horz ? [1, 36] : [48, 1];
    return [L.full('Old', 1), ...field('New', n, m, (x, y) => edge(c, p(x, y), WIPE_SOFT))];
  }
  function reveal(o, t) {
    const dx = o.endsWith('Right') ? 1 : -1;
    if (o.startsWith('black')) {
      const v = [L.full('Black', 1)];
      if (t < 0.4) { const u = win(t, 0, 0.38); v.push(moved('Old', dx * 0.12 * ein(u), 0, 1 - ss(u))); }
      if (t > 0.55) { const u = win(t, 0.55, 1); v.push(moved('New', -dx * 0.12 * (1 - eout(u)), 0, ss(u))); }
      return v;
    }
    const v = [L.full('OldBack', 1), L.full('NewBack', ss(t))];
    if (t < 0.5) { const u = win(t, 0, 0.45); v.push(moved('OldContent', dx * 0.12 * ein(u), 0, 1 - ss(u))); }
    if (t > 0.45) { const u = win(t, 0.47, 0.95); v.push(moved('NewContent', -dx * 0.12 * (1 - eout(u)), 0, ss(u))); }
    return v;
  }
  function randomBars(vert, t) {
    const n = 160, w = 0.2, v = [L.full('Old', 1)];
    for (let i = 0; i < n; i++) {
      const start = rnd(i * 7 + 17) * (1 - w), k = win(t, start, start + w);
      if (k <= 0) continue;
      const a = i / n, b = (i + 1) / n;
      v.push(L.inplace('New', vert ? [a, 0, b, 1] : [0, a, 1, b], k));
    }
    return v;
  }
  function shape(o, t, a) {
    if (o === 'in') { const s = 1 - win(t, 0, 0.5); const v = [L.full('New', 1)]; if (s > 0) v.push(...softRect('Old', FULL, scaleRect(FULL, s), 0.012, 1)); return v; }
    if (o === 'out') { const s = win(t, 0.5, 1); const v = [L.full('Old', 1)]; if (s > 0) v.push(...softRect('New', FULL, scaleRect(FULL, s), 0.012, 1)); return v; }
    const soft = 0.18, c = -soft / 2 + (1 + soft) * t;
    let f;
    if (o === 'diamond') f = (x, y) => edge(c, Math.abs(x - 0.5) + Math.abs(y - 0.5), soft);
    else if (o === 'plus') f = (x, y) => edge(c, Math.min(Math.abs(x - 0.5), Math.abs(y - 0.5)) * 2, soft);
    else { const rmax = Math.sqrt(Math.pow(0.5 * a, 2) + 0.25); f = (x, y) => edge(c, Math.sqrt(Math.pow((x - 0.5) * a, 2) + Math.pow(y - 0.5, 2)) / rmax, soft); }
    return [L.full('Old', 1), ...field('New', 48, 27, f)];
  }
  function flash(t) {
    const white = t < 0.1 ? t / 0.1 : t < 0.47 ? 1 : 1 - win(t, 0.47, 0.88);
    return [L.full(t < 0.2 ? 'Old' : 'New', 1), L.full('White', white)];
  }
  function dissolve(t) {
    const n = 48, m = 27, v = [L.full('Old', 1)];
    for (let r = 0; r < m; r++) for (let c = 0; c < n; c++) if (t > rnd(r * n + c + 991) * 0.97) v.push(L.inplace('New', [c / n, r / m, (c + 1) / n, (r + 1) / m], 1));
    return v;
  }
  function flipTile(cam, cell, k, verticalAxis, out) {
    if (k <= 0) { out.push(L.inplace('Old', cell, 1)); return; }
    if (k >= 1) { out.push(L.inplace('New', cell, 1)); return; }
    const ang = 180 * k, cc = center(cell), c3 = cam.unit(cc[0], cc[1]);
    const [src, deg] = k < 0.5 ? ['Old', ang] : ['New', ang - 180];
    const pts = cam.rect(cell).map(p => (verticalAxis ? yaw(p, c3, deg) : pitch(p, c3, deg)));
    out.push(...card(cam, src, cell, pts, 1, facingShade(pts, 0.6), true));
  }
  function checker(vert, t, a) {
    const n = 10, m = 8, cam = Cam(a, 3), v = [L.full('Black', 1)];
    for (let r = 0; r < m; r++) for (let c = 0; c < n; c++) {
      const cell = [c / n, r / m, (c + 1) / n, (r + 1) / m];
      const across = vert ? r / m : c / n;
      const start = across * 0.55 + rnd(r * n + c + 5) * 0.12;
      flipTile(cam, cell, q2(win(t, start, start + 0.3)), !vert, v);
    }
    return v;
  }
  function blinds(vert, t, a) {
    const n = 12, cam = Cam(a, 3), v = [L.full('Black', 1)];
    for (let i = 0; i < n; i++) {
      const s = i / n, e = (i + 1) / n, cell = vert ? [s, 0, e, 1] : [0, s, 1, e];
      const start = 0.1 + 0.3 * i / n;
      flipTile(cam, cell, q2(win(t, start, start + 0.4)), vert, v);
    }
    return v;
  }
  function clock(o, t, a) {
    const soft = 0.12, c = -soft / 2 + (1 + soft) * t;
    const f = ang => {
      let p;
      if (o === 'counterClockwise') p = (2 * PI - ang) / (2 * PI);
      else if (o === 'wedge') p = (ang > PI ? 2 * PI - ang : ang) / PI;
      else p = ang / (2 * PI);
      return edge(c, p, soft);
    };
    return [L.full('Old', 1), ...fan('New', a, 240, f)];
  }
  function ripple(o, t, a) {
    const origin = { lu: [1, 1], ru: [0, 1], ld: [1, 0], rd: [0, 0] }[o] || [0.5, 0.5];
    const far = [[0, 0], [1, 0], [0, 1], [1, 1]].map(p => Math.sqrt(Math.pow((p[0] - origin[0]) * a, 2) + Math.pow(p[1] - origin[1], 2))).reduce((x, y) => Math.max(x, y), 0);
    const soft = 0.16, c = -soft / 2 + (1 + soft) * t;
    const dist = (x, y) => Math.sqrt(Math.pow((x - origin[0]) * a, 2) + Math.pow(y - origin[1], 2)) / Math.max(far, 1e-6);
    const v = [L.full('Old', 1), ...field('New', 48, 27, (x, y) => edge(c, dist(x, y), soft))];
    v.push(...field('Black', 48, 27, (x, y) => {
      const d = dist(x, y), inside = edge(c, d, soft), w = Math.abs(Math.sin((d - c) * 30));
      return 0.18 * inside * (1 - w) * (1 - t);
    }));
    return v;
  }
  function comb(vert, t) {
    const n = vert ? 10 : 7, v = [L.full('New', 1)];
    for (let i = 0; i < n; i++) {
      const a = i / n, b = (i + 1) / n;
      const d = (n - 1 - i) % 2 === 0 ? 1 : -1;
      const start = 0.06 + 0.05 * (n - 1 - i) * 7 / n;
      const s = ein(win(t, start, start + 0.55));
      if (s >= 1) continue;
      const band = vert ? [a, 0, b, 1] : [0, a, 1, b];
      v.push(L.rect('Old', band, vert ? offset(band, 0, d * s) : offset(band, d * s, 0), 1));
    }
    return v;
  }
  function zoom(o, t, a) {
    if (o === 'rotate') {
      const u = win(t, 0.1, 0.98), s = 0.1 + 0.9 * u, ang = 360 * (1 - u);
      const q = rotQuad(corners(scaleRect(FULL, s)), [0.5, 0.5], ang, a);
      return [L.full('Old', 1), L.quad('New', FULL, q, 0.35 + 0.65 * ss(win(t, 0, 0.45)))];
    }
    if (o === 'out') {
      const s = eout(win(t, 0, 0.8));
      return [scaled('New', 1.9 - 0.9 * s, 0.5, 0.5, 0, 0, 1), scaled('Old', 1 - 0.5 * eout(t), 0.5, 0.5, 0, 0, 1 - ss(win(t, 0, 0.95)))];
    }
    return [L.full('Black', 1), scaled('Old', 1 + 0.3 * ss(t), 0.5, 0.5, 0, 0, 1 - ss(win(t, 0, 0.9))), scaled('New', 0.5 + 0.5 * ss(win(t, 0.15, 0.95)), 0.5, 0.5, 0, 0, ss(win(t, 0, 0.85)))];
  }
  function fallOver(right, t, a) {
    const cam = Cam(a, 2.2), u = ein(win(t, 0, 0.88)), ang = 92 * u, pivot = v3(0, 0.5, 0), side = right ? 1 : -1;
    const pts = cam.rect(FULL).map(p => roll(pitch(p, pivot, ang), pivot, side * 6 * u));
    const v = [L.full('New', 1)];
    if (u < 1) v.push(...card(cam, 'Old', FULL, pts, 1, facingShade(pts, 0.4), true));
    return v;
  }
  function drape(right, t, a) {
    const cam = Cam(a, 2.2), u = q2(win(t, 0.25, 0.62));
    const wob = 4 * (1 - win(t, 0.6, 1)) * Math.sin(win(t, 0.6, 1) * PI * 2);
    const ang = 90 * (1 - u) + wob * u, pivot = v3(0, -0.5, 0), side = right ? -1 : 1;
    const pts = cam.rect(FULL).map(p => roll(pitch(p, pivot, -ang), pivot, side * 3 * (1 - u)));
    const v = [L.full('Old', 1)];
    if (u > 0) v.push(...card(cam, 'New', FULL, pts, 1, facingShade(pts, 0.5), true));
    return v;
  }
  function curtains(t) {
    const open = q2(win(t, 0, 0.35)), lift = ein(win(t, 0.62, 0.9));
    const wTop = 0.5 - 0.22 * open, wBot = 0.5 - 0.12 * open, up = 0.15 * lift, sw = 0.75 * lift;
    const left = [[-sw, -up], [wTop - sw, -up], [wBot - 0.6 * sw, 1 - up], [-0.6 * sw, 1 - up]];
    const right = [[1 - wTop + sw, -up], [1 + sw, -up], [1 + 0.6 * sw, 1 - up], [1 - wBot + 0.6 * sw, 1 - up]];
    return [L.full('New', 1), L.quad('Old', [0, 0, 0.5, 1], left, 1), L.quad('Old', [0.5, 0, 1, 1], right, 1)];
  }
  function wind(right, t, a) {
    const cam = Cam(a, 2.2), u = ein(win(t, 0.12, 0.8)), side = right ? 1 : -1, pivot = v3(0, 0, 0);
    const pts = cam.rect(FULL).map(p => { const w = yaw(pitch(p, v3(0, -0.5, 0), -25 * u), pivot, -side * 20 * u); return v3(w.x + side * 1.6 * u * a / 2, w.y - 0.8 * u, w.z + 0.5 * u); });
    const v = [L.full('New', 1)];
    if (u < 1) v.push(...card(cam, 'Old', FULL, pts, 1, 0, true));
    return v;
  }
  function prestige(t, a) {
    const cam = Cam(a, 2.2), lift = q2(win(t, 0.4, 0.62)), fly = ein(win(t, 0.5, 0.85)), pivot = v3(0, -0.5, 0);
    const pts = cam.rect(FULL).map(p => { const w = roll(pitch(p, pivot, -40 * lift), pivot, -12 * fly); return v3(w.x - 0.4 * fly * a / 2, w.y - 1.3 * fly, w.z - 0.2 * fly); });
    const v = [L.full('New', 1)];
    if (fly < 1) v.push(...card(cam, 'Old', FULL, pts, 1, facingShade(pts, 0.3), true));
    return v;
  }
  function fracture(t, a) {
    const v = [L.full('New', 1)];
    const crack = ss(win(t, 0.06, 0.2));
    if (crack <= 0) { v.push(L.full('Old', 1)); return v; }
    const n = 36, rings = [0, 0.3, 0.6, 1], c = [0.5, 0.5];
    for (let i = 0; i < n; i++) {
      const a0 = 2 * PI * (i + 0.35 * rnd(i)) / n;
      const a1 = 2 * PI * (i + 1 + 0.35 * rnd((i + 1) % n)) / n;
      for (let r = 0; r < 3; r++) {
        const r0 = rings[r], r1 = r === 2 ? 1.6 : rings[r + 1];
        const p = (ang, rr) => { const b = boundary(ang, a); return [c[0] + (b[0] - c[0]) * rr, c[1] + (b[1] - c[1]) * rr]; };
        const pts = [p(a0, r0), p(a1, r0), p(a1, r1), p(a0, r1)];
        const id = i * 3 + r;
        const mid = [(pts[0][0] + pts[1][0] + pts[2][0] + pts[3][0]) / 4, (pts[0][1] + pts[1][1] + pts[2][1] + pts[3][1]) / 4];
        const start = 0.3 + 0.28 * clamp(mid[1], 0, 1) + 0.1 * rnd(id + 300);
        const k = win(t, start, start + 0.35);
        const gap = 0.015 * crack;
        const out = [(mid[0] - c[0]) * gap * 4, (mid[1] - c[1]) * gap * 4 + 1.4 * k * k];
        const ang = (rnd(id + 7) - 0.5) * 50 * k;
        const q = pts.map(pp => rot([mid[0] + (pp[0] - mid[0]) * (1 - 0.06 * crack) + out[0], mid[1] + (pp[1] - mid[1]) * (1 - 0.06 * crack) + out[1]], [mid[0] + out[0], mid[1] + out[1]], ang, a));
        const uv = pts.map(pp => [clamp(pp[0], 0, 1), clamp(pp[1], 0, 1)]);
        if (k < 1) {
          const l = { source: 'Old', src: FULL, quad: q, uv, alpha: 1, fade: [1, 1, 1, 1] };
          v.push(l, L.shade(l, 0.1 * crack * rnd(id + 9) + 0.2 * k));
        }
      }
    }
    return v;
  }
  function crush(t, a) {
    const v = [L.full('New', 1)];
    const k = q2(win(t, 0.08, 0.55)), drop = ein(win(t, 0.65, 1));
    const size = 1 - 0.45 * k, cx = 0.5 - 0.1 * k + 0.05 * drop, cy = 0.5 + 0.05 * k + 0.8 * drop;
    const n = 40, c = [0.5, 0.5];
    for (let i = 0; i < n; i++) {
      const a0 = 2 * PI * i / n, a1 = 2 * PI * (i + 1) / n;
      const j = (ang, idx) => {
        const b = boundary(ang, a);
        const dx = (b[0] - 0.5) * a, dy = b[1] - 0.5, len = Math.max(Math.sqrt(dx * dx + dy * dy), 1e-9), r = 0.45;
        const round = [0.5 + dx / len * r / a, 0.5 + dy / len * r];
        const jag = 1 - 0.18 * k * rnd(idx % n + 77);
        const p = [b[0] + (round[0] - b[0]) * k, b[1] + (round[1] - b[1]) * k];
        return [cx + (p[0] - c[0]) * size * jag, cy + (p[1] - c[1]) * size * jag];
      };
      const l = L.triUv('Old', [[cx, cy], j(a0, i), j(a1, i + 1)], [c, boundary(a0, a), boundary(a1, a)], [1, 1, 1], 1);
      v.push(l, L.shade(l, 0.35 * k * rnd(i + 13)));
    }
    return v;
  }
  function peelOff(right, t) {
    const u = ss(win(t, 0.2, 0.95));
    const [cx, cy] = right ? [0, 1] : [1, 1];
    const nd = right ? [1, -1] : [-1, -1], len = Math.hypot(nd[0], nd[1]), n = [nd[0] / len, nd[1] / len];
    const span = Math.SQRT2, d0 = n[0] * cx + n[1] * cy, fold = d0 + span * 1.25 * u;
    const v = [L.full('New', 1)];
    const full = corners(FULL);
    v.push(...polyLayers('Old', clipPoly(full, [-n[0], -n[1]], -fold), 1, p => p));
    const refl = p => { const d = n[0] * p[0] + n[1] * p[1] - fold; return [p[0] - 2 * d * n[0], p[1] - 2 * d * n[1]]; };
    for (const l of polyLayers('Old', clipPoly(full, n, fold), 1, refl)) v.push(l, L.shade(l, 0.25));
    return v;
  }
  function pageCurl(o, t) {
    const single = o.startsWith('single'), right = o.endsWith('r') || o.endsWith('R');
    const v = [L.full('New', 1)];
    const rollFx = (ar, k, towardLeft) => {
      const w = ar[2] - ar[0], r = 0.06;
      if (towardLeft) {
        const e = ar[2] - (w + r) * k;
        const keep = [ar[0], ar[1], Math.max(e, ar[0]), ar[3]];
        if (keep[2] > keep[0]) v.push(L.inplace('Old', keep, 1));
        const band = [Math.max(e, ar[0]), ar[1], Math.max(Math.min(e + r, ar[2]), ar[0]), ar[3]];
        if (band[2] > band[0] && k < 1) { const l = L.rect('Old', [band[0], ar[1], ar[2], ar[3]], band, 1); v.push(l, L.faded(L.shade(l, 0.3), [0.2, 1, 1, 0.2])); }
      } else {
        const e = ar[0] + (w + r) * k;
        const keep = [Math.min(e, ar[2]), ar[1], ar[2], ar[3]];
        if (keep[2] > keep[0]) v.push(L.inplace('Old', keep, 1));
        const band = [Math.min(Math.max(e - r, ar[0]), ar[2]), ar[1], Math.min(e, ar[2]), ar[3]];
        if (band[2] > band[0] && k < 1) { const l = L.rect('Old', [ar[0], ar[1], band[2], ar[3]], band, 1); v.push(l, L.faded(L.shade(l, 0.3), [1, 0.2, 0.2, 1])); }
      }
    };
    if (single) rollFx(FULL, q2(win(t, 0.25, 0.85)), !right);
    else {
      const k1 = q2(win(t, 0.1, 0.6)), k2 = q2(win(t, 0.45, 0.85));
      if (right) { rollFx([0, 0, 0.5, 1], k1, false); rollFx([0.5, 0, 1, 1], k2, false); }
      else { rollFx([0.5, 0, 1, 1], k1, true); rollFx([0, 0, 0.5, 1], k2, true); }
    }
    return v;
  }
  function airplane(right, t) {
    const fold = q2(win(t, 0, 0.3)), fly = ein(win(t, 0.82, 0.95));
    const v = [L.full('New', 1)];
    if (fly >= 1) return v;
    const mx = p => (right ? [1 - p[0], p[1]] : p);
    const drift = 0.04 * win(t, 0.3, 0.82);
    const pose = p0 => { const p = [p0[0] + drift, p0[1] + drift], c = [0.75, 0.65], k = 1 - 0.85 * fly; return [c[0] + (p[0] - c[0]) * k - 0.4 * fly, c[1] + (p[1] - c[1]) * k - 1 * fly]; };
    const dart = [[0.42, 0.28], [1.02, 0.66], [1, 0.72], [0.46, 1.08]], flat = [[0, 0], [1, 0], [1, 1], [0, 1]];
    const pts = [0, 1, 2, 3].map(i => mx(pose([flat[i][0] + (dart[i][0] - flat[i][0]) * fold, flat[i][1] + (dart[i][1] - flat[i][1]) * fold])));
    const uv = flat.map(mx);
    const l1 = L.triUv('Old', [pts[0], pts[1], pts[2]], [uv[0], uv[1], uv[2]], [1, 1, 1], 1);
    const l2 = L.triUv('Old', [pts[0], pts[2], pts[3]], [uv[0], uv[2], uv[3]], [1, 1, 1], 1);
    v.push(l1, L.shade(l1, 0.1 * fold), l2, L.shade(l2, 0.3 * fold));
    return v;
  }
  function origami(right, t) {
    const fold = q2(win(t, 0, 0.2)), fly = ein(win(t, 0.2, 0.85)), side = right ? 1 : -1;
    const v = [L.full('New', 1)];
    if (win(t, 0.2, 0.92) >= 1) return v;
    const size = 1 - 0.35 * fold, cx = 0.47 + side * 0.65 * fly, cy = 0.42 + 0.05 * fly, flap = Math.sin(t * 18) * 0.08 * fold;
    const body = p => [cx + (p[0] - 0.5) * size, cy + (p[1] - 0.5) * size];
    const shp = [[[0, 0], [-0.45 * side, -0.25 - flap]], [[1, 0], [0.15 * side, -0.4 + flap]], [[1, 1], [0.45 * side, 0.05]], [[0, 1], [-0.1 * side, 0.2]]];
    const pts = shp.map(([fl, bd]) => body([fl[0] + (0.5 + bd[0] - fl[0]) * fold, fl[1] + (0.5 + bd[1] - fl[1]) * fold]));
    const uv = shp.map(([fl]) => fl);
    const l1 = L.triUv('Old', [pts[0], pts[1], pts[2]], [uv[0], uv[1], uv[2]], [1, 1, 1], 1);
    const l2 = L.triUv('Old', [pts[0], pts[2], pts[3]], [uv[0], uv[2], uv[3]], [1, 1, 1], 1);
    v.push(l1, L.shade(l1, 0.15 * fold), l2, L.shade(l2, 0.3 * fold));
    return v;
  }
  function honeycomb(t, a) {
    const cols = 11, w = 1 / cols, rh = w * a * 0.5;
    const rows = Math.max(Math.ceil(1 / (rh * 0.866) - 1 / 3), 1) + 1;
    const v = [L.full('Black', 1)];
    const seam = ss(win(t, 0, 0.12)) * (1 - ss(win(t, 0.85, 1)));
    const hex = (cx, cy, s) => [0, 1, 2, 3, 4, 5].map(k => { const ang = PI / 3 * k + PI / 6; return [cx + Math.cos(ang) * s * w * 0.577, cy + Math.sin(ang) * s * rh * 0.577 / 0.5 * 0.5]; });
    for (let r = 0; r < rows; r++) for (let c = 0; c <= cols; c++) {
      const cx = (c + (r % 2 === 1 ? 0.5 : 0)) * w, cy = r * rh * 0.866, id = r * 64 + c;
      const srcHex = hex(cx, cy, 1);
      const outStart = 0.1 + 0.2 * rnd(id + 1), outK = ein(win(t, outStart, outStart + 0.12));
      const inStart = 0.38 + 0.42 * rnd(id + 3), inK = eout(win(t, inStart, inStart + 0.1));
      const gap = 1 - 0.1 * seam;
      const cell = (src, s, alpha) => {
        const dst = hex(cx, cy, s);
        for (let k = 0; k < 6; k++) v.push(L.triUv(src, [[cx, cy], dst[k], dst[(k + 1) % 6]], [[cx, cy], srcHex[k], srcHex[(k + 1) % 6]], [1, 1, 1], alpha));
      };
      if (t < 0.5 && outK < 1) cell('Old', gap * (1 + 3 * outK), 1 - outK * outK);
      if (inK > 0) cell('New', gap * (0.3 + 0.7 * inK), 1);
    }
    return v;
  }
  function glitter(o, t, a) {
    const hexes = o.startsWith('hex'), d = o.replace(/^(hex)+/, '').toLowerCase();
    const [n, m] = hexes ? [28, 16] : [40, 22];
    const cam = Cam(a, 3), v = [L.full('Old', 1)];
    for (let r = 0; r < m; r++) for (let c = 0; c < n; c++) {
      const cell = [c / n, r / m, (c + 1) / n, (r + 1) / m], cc = center(cell);
      const grad = d === 'r' ? 1 - cc[0] : d === 'u' ? cc[1] : d === 'd' ? 1 - cc[1] : cc[0];
      const start = 0.75 * grad * 0.85 + rnd(r * n + c + 77) * 0.12;
      const k = win(t, start, start + 0.25);
      if (k <= 0) continue;
      v.push(L.inplace('Black', cell, 1));
      flipTile(cam, cell, k, true, v);
    }
    return v;
  }
  function vortex(o, t, a) {
    const [dx, dy] = dir(o, [-1, 0]), n = 48, m = 27, v = [L.full('Black', 1)];
    for (let r = 0; r < m; r++) for (let c = 0; c < n; c++) {
      const id = r * n + c, cell = [c / n, r / m, (c + 1) / n, (r + 1) / m], cc = center(cell);
      const lead = clamp(0.5 + (cc[0] - 0.5) * dx + (cc[1] - 0.5) * dy, 0, 1);
      const go = 0.4 * lead + 0.08 * rnd(id + 31), ko = win(t, go, go + 0.35);
      const end = 0.62 + 0.3 * (1 - lead) + 0.06 * rnd(id + 41), kn = 1 - win(t, end - 0.35, end);
      const piece = (src, k) => {
        const swirl = 2 * PI * (rnd(id + 5) - 0.5) * k;
        const off = [dx * k * 0.8 + Math.sin(swirl) * 0.15 * k, dy * k * 0.8 - Math.abs(Math.cos(swirl)) * 0.12 * k];
        const r2 = offset(scaleRect(cell, 0.8 - 0.5 * k), off[0], off[1]);
        v.push(L.quad(src, cell, rotQuad(corners(r2), center(r2), rnd(id) * 360 * k, a), 1 - k * k));
      };
      if (kn < 1) piece('New', kn);
      if (ko > 0 && ko < 1 && kn >= 1) piece('Old', ko);
      else if (ko <= 0) v.push(L.inplace('Old', cell, 1));
    }
    return v;
  }
  function shred(o, t, a) {
    const particles = o.startsWith('particles') || o.startsWith('rect'), out = o.endsWith('Out');
    const cam = Cam(a, 2.5), v = [L.full('Black', 1)];
    const [src, k] = t < 0.55 ? ['Old', q2(win(t, 0.05, 0.55))] : ['New', 1 - q2(win(t, 0.55, 0.95))];
    if (particles) {
      const n = 64, m = 36;
      for (let r = 0; r < m; r++) for (let c = 0; c < n; c++) {
        const id = r * n + c, cell = [c / n, r / m, (c + 1) / n, (r + 1) / m], cc = center(cell);
        const z = (out ? -0.6 : 1.5) * k * (0.5 + rnd(id));
        const jitter = [(rnd(id + 1) - 0.5) * 0.4 * k, (rnd(id + 2) - 0.5) * 0.4 * k];
        const p3 = cam.unit(cc[0] + jitter[0], cc[1] + jitter[1]);
        const pc = cam.p(v3(p3.x, p3.y, z));
        const s = 0.8 * (1 - 0.3 * k) * cam.d / Math.max(cam.d + z, 0.1);
        const l = L.rect(src, cell, offset(scaleRect(cell, s), pc[0] - cc[0], pc[1] - cc[1]), 1 - 0.3 * k * rnd(id + 5));
        v.push(l, L.shade(l, 0.4 * k));
      }
    } else {
      const n = 30;
      for (let i = 0; i < n; i++) {
        const id = i, x0 = i / n, x1 = (i + 1) / n, cell = [x0, 0, x1, 1];
        const z = (out ? -0.3 : 1) * k * k * (0.6 + rnd(id + 11));
        const tilt = (rnd(id + 12) - 0.5) * 30 * k, drop = (rnd(id + 13) - 0.3) * 0.25 * k, inset = 0.004 * k;
        const w3 = cam.rect([x0 + inset, 0, x1 - inset, 1]), c3 = cam.unit((x0 + x1) / 2, 0.5);
        const pts = w3.map(p => { const q = yaw(roll(p, c3, tilt * 0.2), c3, tilt); return v3(q.x, q.y + drop, q.z + z); });
        v.push(...card(cam, src, cell, pts, 1, 0.3 * k, true));
      }
    }
    return v;
  }
  function switchFx(right, t, a) {
    const cam = Cam(a, 2.5), side = right ? -1 : 1, u = (t + ss(t)) / 2, k = Math.sin(u * PI), shift = 0.5 * k * a;
    const old = cam.rect(FULL).map(p => { const q = yaw(p, v3(0, 0, 0), side * 14 * k); return v3(q.x - side * shift, q.y, q.z + 0.12 * k); });
    const nw = cam.rect(FULL).map(p => { const q = yaw(p, v3(0, 0, 0), -side * 14 * k); return v3(q.x + side * shift, q.y, q.z + 0.12 * k); });
    const o = card(cam, 'Old', FULL, old, 1, facingShade(old, 0.3), true);
    const n = card(cam, 'New', FULL, nw, 1, facingShade(nw, 0.3), true);
    return [L.full('Black', 1), ...(u < 0.5 ? [...n, ...o] : [...o, ...n])];
  }
  function flip(right, t, a) {
    const cam = Cam(a, 2.5), s = q2(t), side = right ? 1 : -1, deg = 180 * s * side, back = 0.6 * Math.sin(s * PI), c = v3(0, 0, 0);
    const pts = cam.rect(FULL).map(p => { const q = yaw(p, c, deg); return v3(q.x, q.y, q.z + back); });
    const v = [L.full('Black', 1)];
    if (s < 0.5) v.push(...card(cam, 'Old', FULL, pts, 1, facingShade(pts, 0.5), true));
    else { const m = [pts[1], pts[0], pts[3], pts[2]]; v.push(...card(cam, 'New', FULL, m, 1, facingShade(m, 0.5), true)); }
    return v;
  }
  function gallery(right, t, a) {
    const cam = Cam(a, 2.5), side = right ? 1 : -1;
    const k = ss(win(t, 0, 0.35)) * (1 - ss(win(t, 0.7, 1))), s = q2(win(t, 0.3, 0.8)), gap = 0.15, back = 0.7 * k, yw = -side * 22 * k;
    const v = [L.full('Black', 1)];
    for (const [src, shift] of [['Old', side * s], ['New', side * (s - 1)]]) {
      const x0 = shift * (a + gap);
      const pts = cam.rect(FULL).map(p => v3(p.x + x0, p.y, p.z + back)).map(p => yaw(p, v3(0, 0, back), yw));
      const refl = [pts[3], pts[2], v3(pts[2].x, pts[2].y + 0.25, pts[2].z), v3(pts[3].x, pts[3].y + 0.25, pts[3].z)];
      v.push({ source: src, src: [0, 0.75, 1, 1], quad: cam.quad(refl), uv: [[0, 1], [1, 1], [1, 0.75], [0, 0.75]], alpha: 0.3 * k, fade: [1, 1, 0, 0] });
      v.push(...card(cam, src, FULL, pts, 1, 0, true));
    }
    return v;
  }
  function conveyor(right, t, a) {
    const cam = Cam(a, 2.5), side = right ? 1 : -1;
    const k = ss(win(t, 0, 0.35)) * (1 - ss(win(t, 0.65, 1))), s = q2(win(t, 0.33, 0.75)), back = 0.45 * k, yw = -side * 18 * k;
    const v = [L.full('OldBack', 1), L.full('NewBack', ss(t))];
    for (const [src, shift] of [['OldContent', side * s], ['NewContent', side * (s - 1)]]) {
      const x0 = shift * a * 1.05;
      const pts = cam.rect(FULL).map(p => v3(p.x + x0, p.y, p.z + back)).map(p => yaw(p, v3(0, 0, back), yw));
      v.push(...card(cam, src, FULL, pts, 1, 0, true));
    }
    return v;
  }
  function prism(kind, o, t, a) {
    const [dx, dy] = dir(o, [-1, 0]);
    const vertical = dy !== 0 && dx === 0, content = kind === 'rotate' || kind === 'orbit', inverted = kind === 'box' || kind === 'orbit';
    const cam = Cam(a, 2.5), s = q2(t), half = vertical ? 0.5 : a / 2;
    const c = v3(0, 0, inverted ? -half : half);
    const sign = vertical ? -dy : dx, inv = inverted ? -1 : 1;
    const deg = 90 * s * sign * inv;
    const turn = (p, base) => (vertical ? pitch(p, c, base + deg) : yaw(p, c, base + deg));
    const sideBase = -90 * sign * inv;
    const [os, ns] = content ? ['OldContent', 'NewContent'] : ['Old', 'New'];
    let old = cam.rect(FULL).map(p => turn(p, 0)), nw = cam.rect(FULL).map(p => turn(p, sideBase));
    const near = [...old, ...nw].map(p => p.z).reduce((x, y) => Math.min(x, y), Infinity);
    const lift = inverted ? 0 : -Math.min(near, 0) - Math.max(near, 0);
    const back = (inverted ? 0.5 : 0.15) * Math.sin(s * PI);
    const push = q => q.map(p => v3(p.x, p.y, p.z + lift + back));
    old = push(old); nw = push(nw);
    const v = content ? [L.full('OldBack', 1), L.full('NewBack', ss(t))] : [L.full('Black', 1)];
    v.push(...card(cam, os, FULL, old, 1, content ? 0 : facingShade(old, 0.6), false));
    v.push(...card(cam, ns, FULL, nw, 1, content ? 0 : facingShade(nw, 0.6), false));
    return v;
  }
  function doors(window_, horz, t, a) {
    const cam = Cam(a, 2.2), open = ein(win(t, 0.05, 0.68)), come = q2(win(t, 0, 0.72));
    const [os, ns] = window_ ? ['OldContent', 'NewContent'] : ['Old', 'New'];
    const v = window_ ? [L.full('OldBack', 1), L.full('NewBack', ss(t))] : [L.full('Black', 1)];
    const halves = horz ? [[[0, 0, 1, 0.5], -1], [[0, 0.5, 1, 1], 1]] : [[[0, 0, 0.5, 1], -1], [[0.5, 0, 1, 1], 1]];
    if (open < 1) {
      for (const [r, sgn] of halves) {
        const hinge = horz ? cam.unit(0.5, sgn < 0 ? 0 : 1) : cam.unit(sgn < 0 ? 0 : 1, 0.5);
        const ang = 30 * open, slide = 0.55 * open;
        const pts = cam.rect(r).map(p => { const q = horz ? pitch(p, hinge, sgn * ang) : yaw(p, hinge, -sgn * ang); return horz ? v3(q.x, q.y + sgn * slide, q.z) : v3(q.x + sgn * slide * a, q.y, q.z); });
        v.push(...card(cam, os, r, pts, window_ ? 1 - ss(win(t, 0.2, 0.6)) : 1, window_ ? 0 : 0.7 * open, true));
      }
    }
    v.push(scaled(ns, 0.5 + 0.5 * come, 0.5, 0.5, 0, 0, window_ ? ss(t) : ss(win(t, 0.05, 0.62))));
    return v;
  }
  function ferris(right, t, a) {
    const side = right ? -1 : 1, v = [L.full('OldBack', 1), L.full('NewBack', ss(t))];
    const out = ein(win(t, 0, 0.5));
    if (out < 1) {
      const dx = -side * 0.45 * Math.pow(out, 1.5), dy = 0.9 * out;
      v.push(L.quad('OldContent', FULL, rotQuad(corners(offset(FULL, dx, dy)), [0.5 + dx, 0.5 + dy], side * -15 * out, a), 1));
    }
    const inn = q2(win(t, 0.5, 1));
    if (inn > 0) {
      const k = 1 - inn, dx = -side * 0.5 * Math.pow(k, 1.3), dy = -0.75 * k;
      const r = offset(scaleRect(FULL, 1 - 0.25 * k), dx, dy);
      v.push(L.quad('NewContent', FULL, rotQuad(corners(r), center(r), side * -18 * k, a), 1));
    }
    return v;
  }
  function pan(o, t) {
    const [dx, dy] = dir(o, [-1, 0]), s = ease(t, 0.2, 0.8, 0);
    return [L.full('OldBack', 1), L.full('NewBack', s), moved('OldContent', dx * s, dy * s, 1), moved('NewContent', dx * (s - 1), dy * (s - 1), 1)];
  }
  function flythrough(o, t) {
    const bounce = o.endsWith('Bounce'), out = o.startsWith('out'), s = q2(t);
    const b = bounce ? 0.08 * Math.sin(win(t, 0.6, 1) * PI) : 0;
    const oldA = 1 - ss(win(t, 0, 0.6)), newA = ss(win(t, 0.3, 0.95));
    const [os, ns] = out ? [1 - 0.5 * s, 1.8 - 0.8 * s - b] : [1 + 1.5 * s, 0.4 + 0.6 * s + b];
    return [L.full('OldBack', 1), L.full('NewBack', ss(t)), scaled('NewContent', ns, 0.5, 0.5, 0, 0, newA), scaled('OldContent', os, 0.5, 0.5, 0, 0, oldA)];
  }

  /** Layers of transition `kind` with `option` at linear progress `t` on a slide `aspect` wide per unit of height. */
  function transitionFrame(kind, option, t, aspect) {
    t = clampf(t, 0, 1, 0);
    const a = Number.isFinite(aspect) && aspect > 0.01 ? Math.min(aspect, 100) : 16 / 9;
    const v = t >= 1 ? [L.full('New', 1)] : t <= 0 ? [L.full('Old', 1)] : layers(kind, option || '', t, a, 0);
    return v.map(sanitized).filter(l => l.alpha > 0 && l.fade.some(x => x > 0));
  }
  function usesSlideParts(kind, option) {
    if (['pan', 'ferris', 'conveyor', 'rotate', 'window', 'orbit', 'flythrough'].includes(kind)) return true;
    return kind === 'reveal' && !String(option || '').startsWith('black');
  }

  // ------------------------------------------------------------------ meshes (mesh.rs)

  const GRID = 8;
  function squareToQuad(q) {
    const [[x0, y0], [x1, y1], [x2, y2], [x3, y3]] = q;
    const sx = x0 - x1 + x2 - x3, sy = y0 - y1 + y2 - y3;
    const dx1 = x1 - x2, dx2 = x3 - x2, dy1 = y1 - y2, dy2 = y3 - y2;
    const den = dx1 * dy2 - dx2 * dy1;
    if (!Number.isFinite(den) || Math.abs(den) < 1e-12) return null;
    const g = (sx * dy2 - dx2 * sy) / den, h = (dx1 * sy - sx * dy1) / den;
    const m = [x1 - x0 + g * x1, x3 - x0 + h * x3, x0, y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h];
    return m.every(Number.isFinite) ? m : null;
  }
  function applyH(m, u, v) {
    let w = m[6] * u + m[7] * v + 1;
    if (Math.abs(w) < 1e-9) w = w < 0 ? -1e-9 : 1e-9;
    return [(m[0] * u + m[1] * v + m[2]) / w, (m[3] * u + m[4] * v + m[5]) / w];
  }
  /** Appends a layer's triangles to `out` as (x, y, u, v, alpha) per vertex. */
  function layerMesh(l, out) {
    const q = l.quad, uv = l.uv, fa = l.fade.map(f => f * l.alpha);
    const vert = (p, t, a) => out.push(p[0], p[1], t[0], t[1], a);
    if (q[3][0] === q[2][0] && q[3][1] === q[2][1] && uv[3][0] === uv[2][0] && uv[3][1] === uv[2][1]) {
      for (let k = 0; k < 3; k++) vert(q[k], uv[k], fa[k]);
      return;
    }
    const e1 = [q[1][0] - q[0][0], q[1][1] - q[0][1]], e2 = [q[2][0] - q[3][0], q[2][1] - q[3][1]];
    const affine = Math.abs(e1[0] - e2[0]) < 1e-9 && Math.abs(e1[1] - e2[1]) < 1e-9;
    const h = affine ? null : squareToQuad(q);
    if (!h) { for (const k of [0, 1, 2, 0, 2, 3]) vert(q[k], uv[k], fa[k]); return; }
    let span = 0;
    for (const p of q) for (const r of q) span = Math.max(span, Math.abs(p[0] - r[0]), Math.abs(p[1] - r[1]));
    const n = clamp(Math.ceil(span * 2 * GRID), 1, GRID);
    const pts = [];
    for (let j = 0; j <= n; j++) {
      const b = j / n;
      for (let i = 0; i <= n; i++) {
        const a = i / n;
        const top = [uv[0][0] + (uv[1][0] - uv[0][0]) * a, uv[0][1] + (uv[1][1] - uv[0][1]) * a];
        const bot = [uv[3][0] + (uv[2][0] - uv[3][0]) * a, uv[3][1] + (uv[2][1] - uv[3][1]) * a];
        const at = fa[0] + (fa[1] - fa[0]) * a, ab = fa[3] + (fa[2] - fa[3]) * a;
        pts.push([applyH(h, a, b), [top[0] + (bot[0] - top[0]) * b, top[1] + (bot[1] - top[1]) * b], at + (ab - at) * b]);
      }
    }
    const w = n + 1;
    for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
      const p = j * w + i;
      for (const k of [p, p + 1, p + w + 1, p, p + w + 1, p + w]) vert(pts[k][0], pts[k][1], pts[k][2]);
    }
  }

  // ------------------------------------------------------------------ WebGL compositor

  const VS = `attribute vec2 p; attribute vec2 uv; attribute float a; varying vec2 vUv; varying float vA;
    void main() { vUv = uv; vA = a; gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0); }`;
  const FS = `precision mediump float; varying vec2 vUv; varying float vA; uniform sampler2D tex; uniform vec4 solid; uniform float useSolid;
    void main() { vec4 c = useSolid > 0.5 ? solid : texture2D(tex, vUv); gl_FragColor = c * vA; }`;

  /** A canvas that draws transition frames from pictures of the slides ({Old, New, OldBack…}: canvases or images). */
  function Compositor(canvas) {
    const gl = canvas.getContext('webgl', { premultipliedAlpha: true, alpha: false, antialias: false, preserveDrawingBuffer: false });
    if (!gl) return null;
    const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s; };
    const prog = gl.createProgram();
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, FS)); gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return null;
    gl.useProgram(prog);
    const buf = gl.createBuffer();
    const locP = gl.getAttribLocation(prog, 'p'), locUv = gl.getAttribLocation(prog, 'uv'), locA = gl.getAttribLocation(prog, 'a');
    const uSolid = gl.getUniformLocation(prog, 'solid'), uUse = gl.getUniformLocation(prog, 'useSolid');
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.enableVertexAttribArray(locP); gl.enableVertexAttribArray(locUv); gl.enableVertexAttribArray(locA);
    gl.vertexAttribPointer(locP, 2, gl.FLOAT, false, 20, 0);
    gl.vertexAttribPointer(locUv, 2, gl.FLOAT, false, 20, 8);
    gl.vertexAttribPointer(locA, 1, gl.FLOAT, false, 20, 16);
    gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
    const textures = new Map();
    return {
      setSources(src) {
        textures.forEach(t => gl.deleteTexture(t)); textures.clear();
        for (const [k, img] of Object.entries(src)) {
          if (!img) continue;
          const t = gl.createTexture();
          gl.bindTexture(gl.TEXTURE_2D, t);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
          try { gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img); } catch (_) { gl.deleteTexture(t); continue; }
          textures.set(k, t);
        }
      },
      draw(layerList) {
        gl.viewport(0, 0, canvas.width, canvas.height);
        gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
        for (const l0 of layerList) {
          let source = l0.source;
          if (!textures.has(source) && source !== 'Black' && source !== 'White') {
            // Without the parts: a background draws its whole slide, content draws nothing.
            if (source === 'OldBack') source = 'Old'; else if (source === 'NewBack') source = 'New'; else continue;
            if (!textures.has(source)) { if (source === 'Old') source = 'Black'; else continue; }
          }
          const v = [];
          layerMesh(l0, v);
          if (!v.length) continue;
          if (source === 'Black' || source === 'White') { const c = source === 'White' ? 1 : 0; gl.uniform4f(uSolid, c, c, c, 1); gl.uniform1f(uUse, 1); }
          else { gl.uniform1f(uUse, 0); gl.bindTexture(gl.TEXTURE_2D, textures.get(source)); }
          gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(v), gl.STREAM_DRAW);
          gl.drawArrays(gl.TRIANGLES, 0, v.length / 5);
        }
      },
      dispose() { textures.forEach(t => gl.deleteTexture(t)); textures.clear(); const ext = gl.getExtension('WEBGL_lose_context'); if (ext) ext.loseContext(); }
    };
  }

  // ------------------------------------------------------------------ reading transitions from PPTX (maps.rs, read/timing.rs)

  const INVX_LEFT = ['wind', 'airplane', 'origami'];
  const SIMPLE = { dissolve: 'dissolve', random: 'random', honeycomb: 'honeycomb', flash: 'flash' };
  const attr = (e, n) => { if (!e) return null; for (const a of e.attributes) if (a.localName === n) return a.value; return null; };
  const bool = (e, n) => { const v = attr(e, n); return v === null ? null : (v === '1' || v === 'true'); };
  const kids = e => [...(e ? e.children : [])];
  const child = (e, n) => kids(e).find(c => c.localName === n) || null;

  function transitionFromXml(e) {
    const d = def => attr(e, 'dir') || def;
    let r;
    switch (e.localName) {
      case 'fade': r = ['fade', bool(e, 'thruBlk') ? 'throughBlack' : 'smoothly']; break;
      case 'cut': r = ['cut', bool(e, 'thruBlk') ? 'throughBlack' : '']; break;
      case 'push': r = ['push', d('l')]; break;
      case 'wipe': r = ['wipe', d('l')]; break;
      case 'strips': r = ['wipe', d('lu')]; break;
      case 'split': r = ['split', (attr(e, 'orient') || 'horz') + ((attr(e, 'dir') || 'out') === 'in' ? 'In' : 'Out')]; break;
      case 'randomBar': r = ['randomBar', d('horz')]; break;
      case 'circle': r = ['shape', 'circle']; break;
      case 'diamond': r = ['shape', 'diamond']; break;
      case 'plus': r = ['shape', 'plus']; break;
      case 'zoom': r = ['shape', attr(e, 'dir') === 'in' ? 'in' : 'out']; break;
      case 'warp': r = ['zoom', attr(e, 'dir') === 'in' ? 'in' : 'out']; break;
      case 'newsflash': r = ['zoom', 'rotate']; break;
      case 'pull': r = ['uncover', d('l')]; break;
      case 'cover': r = ['cover', d('l')]; break;
      case 'checker': r = ['checker', d('horz')]; break;
      case 'blinds': r = ['blinds', d('horz')]; break;
      case 'comb': r = ['comb', d('horz')]; break;
      case 'wheel': r = ['clock', 'clockwise']; break;
      case 'wheelReverse': r = ['clock', 'counterClockwise']; break;
      case 'wedge': r = ['clock', 'wedge']; break;
      case 'vortex': r = ['vortex', d('l')]; break;
      case 'switch': r = ['switch', d('r')]; break;
      case 'flip': r = ['flip', d('r')]; break;
      case 'ripple': r = ['ripple', d('center')]; break;
      case 'doors': r = ['doors', d('horz')]; break;
      case 'window': r = ['window', d('horz')]; break;
      case 'ferris': r = ['ferris', d('l')]; break;
      case 'gallery': r = ['gallery', d('l')]; break;
      case 'conveyor': r = ['conveyor', d('l')]; break;
      case 'pan': r = ['pan', d('l')]; break;
      case 'glitter': { const x = d('l'); r = attr(e, 'pattern') === 'hexagon' ? ['glitter', 'hex' + x.toUpperCase()] : ['glitter', x]; break; }
      case 'shred': { const p = ['rectangle', 'crush'].includes(attr(e, 'pattern')); r = ['shred', (p ? 'particles' : 'strips') + (attr(e, 'dir') === 'out' ? 'Out' : 'In')]; break; }
      case 'reveal': r = ['reveal', (bool(e, 'thruBlk') ? 'black' : 'smoothly') + (attr(e, 'dir') === 'r' ? 'Right' : 'Left')]; break;
      case 'flythrough': r = ['flythrough', (attr(e, 'dir') === 'out' ? 'out' : 'in') + (bool(e, 'hasBounce') ? 'Bounce' : '')]; break;
      case 'prism': {
        const content = bool(e, 'isContent'), inv = bool(e, 'isInverted');
        r = [content ? (inv ? 'orbit' : 'rotate') : (inv ? 'box' : 'cube'), d('l')]; break;
      }
      case 'prstTrans': {
        const prst = attr(e, 'prst') || 'fallOver', inv = bool(e, 'invX') === true;
        if (prst === 'pageCurlSingle') { r = ['pageCurlDouble', inv ? 'singleR' : 'singleL']; break; }
        if (!TRANSITIONS.some(t => t[0] === prst)) return null;
        const left = INVX_LEFT.includes(prst) ? inv : !inv;
        r = [prst, left ? 'l' : 'r']; break;
      }
      case 'morph': { const o = attr(e, 'option'); r = ['morph', o === 'byWord' ? 'words' : o === 'byChar' ? 'characters' : 'objects']; break; }
      default: if (!SIMPLE[e.localName]) return null; r = [SIMPLE[e.localName], ''];
    }
    const info = TRANSITIONS.find(t => t[0] === r[0]);
    if (info && !info[4].length) r[1] = '';
    return r;
  }
  const msAttr = (e, n) => { const v = (attr(e, n) || '').trim(); if (!v || v === 'indefinite') return null; const x = parseFloat(v); return Number.isFinite(x) ? Math.round(clamp(x, 0, 86400000)) : null; };

  /** The transition of a slide document: {kind, option, ms, advClick, autoMs} or null. */
  function slideTransition(doc) {
    const root = doc.documentElement;
    // The newest description comes first: mc:AlternateContent / mc:Choice, then the plain one.
    let t = null;
    for (const el of root.getElementsByTagNameNS('*', 'transition')) { if (el.parentNode === root || (el.parentNode && el.parentNode.localName === 'Choice')) { t = el; break; } }
    if (!t) for (const el of root.getElementsByTagNameNS('*', 'transition')) { t = el; break; }
    if (!t) return null;
    const effect = kids(t).find(e => e.localName !== 'sndAc' && e.localName !== 'extLst');
    const tr = { kind: 'none', option: '', advClick: bool(t, 'advClick') !== false, autoMs: msAttr(t, 'advTm') };
    const m = effect ? transitionFromXml(effect) : null;
    if (m) { tr.kind = m[0]; tr.option = m[1]; } else if (effect) tr.kind = effect.localName;
    const info = TRANSITIONS.find(x => x[0] === tr.kind);
    const defDur = info ? info[3] : 1000;
    const spd = attr(t, 'spd');
    tr.ms = msAttr(t, 'dur') ?? (spd === 'med' ? 750 : spd === 'slow' ? Math.max(defDur, 1000) : 500);
    if (tr.kind === 'none' && tr.advClick && tr.autoMs == null) return null;
    return tr;
  }

  return {
    ease, smooth, bounceOut, clamp, clampf, lerp, fin, rnd, hash64,
    TRANSITIONS, transitionFrame, usesSlideParts, layerMesh, Compositor,
    transitionFromXml, slideTransition, xml: { attr, bool, kids, child, msAttr }
  };
})();
