/* Object animations (entrance, emphasis, exit, motion paths; click steps, With/After Previous,
 * paragraph builds), ported from DeckCraft (MIT OR Apache-2.0): crates/model/src/anim.rs,
 * crates/anim/src/{effects,timeline,path}.rs and crates/pptx/src/{maps.rs,read/timing.rs}.
 * The animation state of a shape is applied to the slide's HTML (pptx-renderer tags each shape
 * with data-sp-id and each paragraph with data-para). */
'use strict';

const SlideAnim = (() => {
  const { ease, smooth, bounceOut, clampf, xml } = SlideFX;
  const { attr, bool, kids, child } = xml;
  const PI = Math.PI, TAU = 2 * Math.PI;
  const fin = (v, d) => (Number.isFinite(v) ? v : d);

  // [id, label, class, preset id, default ms, options]
  const ANIMATIONS = [
    ['appear', 'Appear', 'entr', 1, 0, []], ['fade', 'Fade', 'entr', 10, 500, []], ['fly', 'Fly In', 'entr', 2, 500, ['b', 'lb', 'l', 'lt', 't', 'rt', 'r', 'rb']],
    ['float', 'Float In', 'entr', 42, 1000, ['u', 'd']], ['split', 'Split', 'entr', 16, 500, ['horzIn', 'horzOut', 'vertIn', 'vertOut']], ['wipe', 'Wipe', 'entr', 22, 500, ['b', 'l', 't', 'r']],
    ['shape', 'Shape', 'entr', 6, 2000, ['circleIn', 'circleOut', 'boxIn', 'boxOut', 'diamondIn', 'diamondOut', 'plusIn', 'plusOut']], ['wheel', 'Wheel', 'entr', 21, 2000, ['1', '2', '3', '4', '8']],
    ['randomBars', 'Random Bars', 'entr', 14, 500, ['horz', 'vert']], ['grow', 'Grow & Turn', 'entr', 31, 500, []], ['zoom', 'Zoom', 'entr', 53, 500, ['in', 'out']],
    ['swivel', 'Swivel', 'entr', 45, 2000, ['horz', 'vert']], ['bounce', 'Bounce', 'entr', 26, 2000, []], ['blinds', 'Blinds', 'entr', 3, 500, ['horz', 'vert']],
    ['box', 'Box', 'entr', 4, 500, ['in', 'out']], ['checkerboard', 'Checkerboard', 'entr', 5, 500, ['across', 'down']], ['circle', 'Circle', 'entr', 6, 500, ['in', 'out']],
    ['diamond', 'Diamond', 'entr', 8, 500, ['in', 'out']], ['dissolve', 'Dissolve In', 'entr', 9, 500, []], ['peek', 'Peek In', 'entr', 12, 500, ['b', 'l', 't', 'r']],
    ['strips', 'Strips', 'entr', 18, 500, ['lu', 'ru', 'ld', 'rd']], ['expand', 'Expand', 'entr', 55, 500, []], ['rise', 'Rise Up', 'entr', 37, 1000, []], ['spinner', 'Spinner', 'entr', 49, 1000, []],
    ['pulse', 'Pulse', 'emph', 26, 500, []], ['colorPulse', 'Color Pulse', 'emph', 27, 500, []], ['teeter', 'Teeter', 'emph', 32, 1000, []],
    ['spin', 'Spin', 'emph', 8, 2000, ['clockwise', 'counterClockwise']], ['growShrink', 'Grow/Shrink', 'emph', 6, 2000, ['both', 'horz', 'vert']],
    ['desaturate', 'Desaturate', 'emph', 35, 500, []], ['darken', 'Darken', 'emph', 25, 500, []], ['lighten', 'Lighten', 'emph', 30, 500, []], ['transparency', 'Transparency', 'emph', 9, 500, []],
    ['objectColor', 'Object Color', 'emph', 19, 2000, []], ['complementaryColor', 'Complementary Color', 'emph', 21, 500, []], ['lineColor', 'Line Color', 'emph', 7, 2000, []],
    ['fillColor', 'Fill Color', 'emph', 1, 2000, []], ['fontColor', 'Font Color', 'emph', 3, 2000, []], ['underline', 'Underline', 'emph', 18, 500, []], ['boldFlash', 'Bold Flash', 'emph', 10, 500, []],
    ['wave', 'Wave', 'emph', 34, 500, []],
    ['disappear', 'Disappear', 'exit', 1, 0, []], ['fadeOut', 'Fade', 'exit', 10, 500, []], ['flyOut', 'Fly Out', 'exit', 2, 500, ['b', 'lb', 'l', 'lt', 't', 'rt', 'r', 'rb']],
    ['floatOut', 'Float Out', 'exit', 42, 1000, ['u', 'd']], ['splitOut', 'Split', 'exit', 16, 500, ['horzIn', 'horzOut', 'vertIn', 'vertOut']], ['wipeOut', 'Wipe', 'exit', 22, 500, ['b', 'l', 't', 'r']],
    ['shapeOut', 'Shape', 'exit', 6, 2000, ['circleIn', 'circleOut']], ['wheelOut', 'Wheel', 'exit', 21, 2000, ['1', '2', '3', '4', '8']], ['randomBarsOut', 'Random Bars', 'exit', 14, 500, ['horz', 'vert']],
    ['shrinkTurn', 'Shrink & Turn', 'exit', 31, 500, []], ['zoomOut', 'Zoom', 'exit', 53, 500, ['in', 'out']], ['swivelOut', 'Swivel', 'exit', 45, 2000, ['horz', 'vert']],
    ['bounceOut', 'Bounce', 'exit', 26, 2000, []], ['dissolveOut', 'Dissolve Out', 'exit', 9, 500, []], ['blindsOut', 'Blinds', 'exit', 3, 500, ['horz', 'vert']],
    ['boxOut', 'Box', 'exit', 4, 500, ['in', 'out']], ['checkerboardOut', 'Checkerboard', 'exit', 5, 500, ['across', 'down']], ['diamondOut', 'Diamond', 'exit', 8, 500, ['in', 'out']],
    ['peekOut', 'Peek Out', 'exit', 12, 500, ['b', 'l', 't', 'r']], ['stripsOut', 'Strips', 'exit', 18, 500, ['lu', 'ru', 'ld', 'rd']], ['sinkDown', 'Sink Down', 'exit', 37, 1000, []],
    ['spinnerOut', 'Spinner', 'exit', 49, 1000, []], ['contract', 'Contract', 'exit', 55, 500, []],
    ['lines', 'Lines', 'path', 64, 2000, ['down', 'up', 'right', 'left']], ['arcs', 'Arcs', 'path', 37, 2000, ['down', 'up', 'right', 'left']], ['turns', 'Turns', 'path', 43, 2000, ['down', 'up', 'right', 'left']],
    ['shapes', 'Shapes', 'path', 1, 2000, ['circle', 'diamond', 'hexagon', 'triangle', 'square', 'trapezoid', 'octagon', 'parallelogram', 'pentagon']], ['loops', 'Loops', 'path', 61, 2000, []],
    ['diagonalDownRight', 'Diagonal Down Right', 'path', 49, 2000, []], ['diagonalUpRight', 'Diagonal Up Right', 'path', 56, 2000, []], ['zigzag', 'Zigzag', 'path', 38, 2000, []],
    ['sineWave', 'Sine Wave', 'path', 40, 2000, []], ['pathWave', 'Wave', 'path', 47, 2000, []], ['decayingWave', 'Decaying Wave', 'path', 60, 2000, []], ['heartbeat', 'Heartbeat', 'path', 45, 2000, []],
    ['sCurve1', 'S Curve 1', 'path', 59, 2000, []], ['sCurve2', 'S Curve 2', 'path', 39, 2000, []], ['stairsDown', 'Stairs Down', 'path', 62, 2000, []], ['bounceRight', 'Bounce Right', 'path', 54, 2000, []],
    ['bounceLeft', 'Bounce Left', 'path', 41, 2000, []], ['spiralRight', 'Spiral Right', 'path', 46, 2000, []], ['spiralLeft', 'Spiral Left', 'path', 55, 2000, []], ['spring', 'Spring', 'path', 53, 2000, []],
    ['funnel', 'Funnel', 'path', 52, 2000, []], ['swoosh', 'Swoosh', 'path', 30, 2000, []], ['star4', '4 Point Star', 'path', 16, 2000, []], ['star5', '5 Point Star', 'path', 5, 2000, []],
    ['star6', '6 Point Star', 'path', 11, 2000, []], ['star8', '8 Point Star', 'path', 17, 2000, []], ['heart', 'Heart', 'path', 9, 2000, []], ['crescentMoon', 'Crescent Moon', 'path', 6, 2000, []],
    ['teardrop', 'Teardrop', 'path', 18, 2000, []], ['football', 'Football', 'path', 12, 2000, []], ['plusPath', 'Plus', 'path', 32, 2000, []], ['rightTriangle', 'Right Triangle', 'path', 2, 2000, []],
    ['curvedSquare', 'Curved Square', 'path', 20, 2000, []], ['figure8Horizontal', 'Horizontal Figure 8', 'path', 26, 2000, []], ['figure8Vertical', 'Vertical Figure 8', 'path', 22, 2000, []],
    ['loopDeLoop', 'Loop de Loop', 'path', 24, 2000, []], ['bean', 'Bean', 'path', 31, 2000, []], ['peanut', 'Peanut', 'path', 27, 2000, []], ['neutron', 'Neutron', 'path', 29, 2000, []],
    ['customPath', 'Custom Path', 'path', 0, 2000, []], ['play', 'Play', 'mediacall', 1, 0, []], ['pause', 'Pause', 'mediacall', 2, 0, []], ['stop', 'Stop', 'mediacall', 3, 0, []]
  ];
  const DIR8 = [['b', 4], ['lb', 12], ['l', 8], ['lt', 9], ['t', 1], ['rt', 3], ['r', 2], ['rb', 6]];
  const DIR4 = [['b', 4], ['l', 8], ['t', 1], ['r', 2]];
  const SPLIT = [['horzIn', 21], ['horzOut', 42], ['vertIn', 26], ['vertOut', 37]];
  const INOUT = [['in', 16], ['out', 32]];
  const HV = [['horz', 10], ['vert', 5]];
  const WHEEL = [['1', 1], ['2', 2], ['3', 3], ['4', 4], ['8', 8]];
  const STRIPS = [['lu', 9], ['ru', 3], ['ld', 12], ['rd', 6]];
  const LINES = [['down', 4], ['up', 1], ['right', 2], ['left', 8]];
  const SUBTYPES = {
    fly: DIR8, flyOut: DIR8, peek: DIR4, wipe: DIR4, wipeOut: DIR4, strips: STRIPS, split: SPLIT, splitOut: SPLIT, zoom: INOUT, zoomOut: INOUT, box: INOUT, circle: INOUT,
    diamond: INOUT, randomBars: HV, randomBarsOut: HV, blinds: HV, swivel: HV, swivelOut: HV, blindsOut: HV, boxOut: INOUT, checkerboardOut: [['across', 10], ['down', 5]],
    diamondOut: INOUT, peekOut: DIR4, stripsOut: STRIPS, checkerboard: [['across', 10], ['down', 5]], wheel: WHEEL, wheelOut: WHEEL, float: [['u', 0], ['d', 4]], floatOut: [['u', 0], ['d', 4]],
    shape: [['circleIn', 16], ['circleOut', 32], ['boxIn', 16], ['boxOut', 32], ['diamondIn', 16], ['diamondOut', 32], ['plusIn', 16], ['plusOut', 32]],
    shapeOut: [['circleIn', 16], ['circleOut', 32]], lines: LINES, arcs: LINES, turns: LINES
  };
  const optionFromSubtype = (effect, sub) => { const m = SUBTYPES[effect]; const f = m && m.find(x => x[1] === sub); return f ? f[0] : ''; };
  const effectForPreset = (cls, preset) => { const a = ANIMATIONS.find(x => x[2] === cls && x[3] === preset && x[0] !== 'customPath'); return a ? a[0] : null; };
  const fallbackEffect = cls => ({ entr: 'fade', exit: 'fadeOut', emph: 'pulse', path: 'customPath', mediacall: 'play' })[cls] || 'fade';

  // ------------------------------------------------------------------ motion paths (path.rs, anim.rs preset_path)

  const f4 = v => v.toFixed(4);
  function presetPath(effect) {
    const poly = (n, closed, f) => { const [x0, y0] = f(0); let s = 'M 0 0'; for (let k = 1; k <= n; k++) { const [x, y] = f(k / n); s += ` L ${f4(x - x0)} ${f4(y - y0)}`; } return s + (closed ? ' Z' : '') + ' E'; };
    const pts = (p, closed) => { let s = 'M 0 0'; for (const [x, y] of p) s += ` L ${f4(x)} ${f4(y)}`; return s + (closed ? ' Z' : '') + ' E'; };
    const polar = (scale, r) => poly(96, true, t => { const a = -PI / 2 + TAU * t; const rr = r(a + PI / 2) * scale; return [rr * Math.cos(a), rr * Math.sin(a)]; });
    const star = (n, outer, inner) => { const p = [], start = [0, -outer]; for (let k = 1; k <= 2 * n; k++) { const a = -PI / 2 + PI * k / n, r = k % 2 === 1 ? inner : outer; p.push([r * Math.cos(a) - start[0], r * Math.sin(a) - start[1]]); } return pts(p, true); };
    const spiral = d => poly(120, false, t => { const r = 0.12 * (1 - t), a = d * 3 * TAU * t; return [d * (0.12 - r * Math.cos(a)), -r * Math.sin(a)]; });
    const bounce = d => poly(90, false, t => [d * 0.3 * t, 0.15 * t - 0.1 * (1 - t) * Math.abs(Math.sin(3 * PI * t))]);
    const sg = v => Math.sign(v) * Math.sqrt(Math.abs(v));
    switch (effect) {
      case 'diagonalDownRight': return pts([[0.2, 0.2]], false);
      case 'diagonalUpRight': return pts([[0.2, -0.2]], false);
      case 'zigzag': return pts([[0.05, 0.04], [0.1, 0], [0.15, 0.04], [0.2, 0], [0.25, 0.04], [0.3, 0]], false);
      case 'sineWave': return poly(64, false, t => [0.3 * t, 0.04 * Math.sin(2 * TAU * t)]);
      case 'pathWave': return poly(48, false, t => [0.3 * t, 0.06 * Math.sin(TAU * t)]);
      case 'decayingWave': return poly(96, false, t => [0.3 * t, 0.07 * Math.exp(-3 * t) * Math.sin(3 * TAU * t)]);
      case 'heartbeat': return pts([[0.08, 0], [0.1, -0.08], [0.13, 0.06], [0.15, 0], [0.25, 0]], false);
      case 'sCurve1': return poly(48, false, t => [0.2 * t, 0.1 - 0.1 * Math.cos(PI * t)]);
      case 'sCurve2': return poly(48, false, t => [0.1 - 0.1 * Math.cos(PI * t), 0.2 * t]);
      case 'stairsDown': return pts([[0.06, 0], [0.06, 0.06], [0.12, 0.06], [0.12, 0.12], [0.18, 0.12], [0.18, 0.18]], false);
      case 'bounceRight': return bounce(1);
      case 'bounceLeft': return bounce(-1);
      case 'spiralRight': return spiral(1);
      case 'spiralLeft': return spiral(-1);
      case 'spring': return poly(160, false, t => [0.3 * t + 0.03 * Math.cos(5 * TAU * t), 0.03 * Math.sin(5 * TAU * t)]);
      case 'funnel': return poly(160, false, t => { const r = 0.12 * (1 - t); return [r * Math.cos(3 * TAU * t), 0.25 * t + 0.03 * Math.sin(3 * TAU * t) * (1 - t)]; });
      case 'swoosh': return poly(48, false, t => [0.3 * t, -0.12 * Math.sin(PI * t) + 0.1 * t * t]);
      case 'star4': return star(4, 0.1, 0.04);
      case 'star5': return star(5, 0.1, 0.04);
      case 'star6': return star(6, 0.1, 0.05);
      case 'star8': return star(8, 0.1, 0.06);
      case 'heart': return poly(96, true, t => { const a = TAU * t; return [0.006 * 16 * Math.pow(Math.sin(a), 3), -0.006 * (13 * Math.cos(a) - 5 * Math.cos(2 * a) - 2 * Math.cos(3 * a) - Math.cos(4 * a))]; });
      case 'crescentMoon': {
        const p = [];
        for (let k = 1; k <= 32; k++) { const a = -PI / 2 - PI * k / 32; p.push([0.1 * Math.cos(a), 0.1 + 0.1 * Math.sin(a)]); }
        for (let k = 1; k <= 32; k++) { const a = PI / 2 - PI * k / 32; p.push([0.06 * Math.cos(a) - 0.03, 0.1 + 0.1 * Math.sin(a)]); }
        return pts(p, true);
      }
      case 'teardrop': return polar(0.1, a => 0.6 + 0.4 * Math.pow(Math.abs(Math.cos(a / 2)), 3));
      case 'football': return poly(64, true, t => { const a = TAU * t; return [0.12 * Math.sin(a), 0.06 * (1 - Math.cos(a)) * (1 - 0.3 * Math.abs(Math.cos(2 * a)))]; });
      case 'plusPath': return pts([[0.04, 0], [0.04, 0.04], [0.08, 0.04], [0.08, 0.08], [0.04, 0.08], [0.04, 0.12], [0, 0.12], [0, 0.08], [-0.04, 0.08], [-0.04, 0.04], [0, 0.04]], true);
      case 'rightTriangle': return pts([[0, 0.15], [0.15, 0.15]], true);
      case 'curvedSquare': return poly(96, true, t => { const a = -PI / 2 + TAU * t; return [0.1 * sg(Math.cos(a)), 0.1 + 0.1 * sg(Math.sin(a))]; });
      case 'figure8Horizontal': return poly(96, true, t => { const a = TAU * t; return [0.15 * Math.sin(a), 0.06 * Math.sin(2 * a)]; });
      case 'figure8Vertical': return poly(96, true, t => { const a = TAU * t; return [0.06 * Math.sin(2 * a), 0.15 * (1 - Math.cos(a))]; });
      case 'loopDeLoop': return poly(120, false, t => [0.3 * t - 0.05 * Math.sin(2 * TAU * t), -0.05 * (1 - Math.cos(2 * TAU * t))]);
      case 'bean': return polar(0.1, a => 0.75 + 0.25 * Math.cos(a) + 0.15 * Math.cos(2 * a));
      case 'peanut': return polar(0.1, a => 0.7 + 0.3 * Math.abs(Math.cos(2 * a)));
      case 'neutron': return polar(0.1, a => 0.35 + 0.65 * Math.abs(Math.cos(3 * a)));
      default: return null;
    }
  }
  function polygonPath(n, r, rot0) {
    let s = 'M 0 0';
    const c = [0, r], a0 = -PI / 2 + rot0, start = [c[0] + r * Math.cos(a0), c[1] + r * Math.sin(a0)];
    for (let k = 1; k <= n; k++) { const a = a0 + TAU * k / n; s += ` L ${f4(c[0] + r * Math.cos(a) - start[0])} ${f4(c[1] + r * Math.sin(a) - start[1])}`; }
    return s + ' E';
  }
  function defaultPath(effect, option) {
    const [sx, sy, swap] = option === 'up' ? [1, -1, false] : option === 'right' ? [1, 1, true] : option === 'left' ? [-1, 1, true] : [1, 1, false];
    const tr = p => p.map(([x, y]) => (swap ? [y * sx, x * sy] : [x * sx, y * sy]));
    const fmt = (head, p) => { let s = head; for (const [x, y] of p) s += ` ${f4(x)} ${f4(y)}`; return s + ' E'; };
    switch (effect) {
      case 'lines': return fmt('M 0 0 L', tr([[0, 0.25]]));
      case 'arcs': return fmt('M 0 0 C', tr([[0.1, 0.05], [0.1, 0.2], [0, 0.25]]));
      case 'turns': { const p = tr([[0, 0.12], [0, 0.2], [0.05, 0.25], [0.12, 0.25]]); let s = `M 0 0 L ${f4(p[0][0])} ${f4(p[0][1])} C`; for (const [x, y] of p.slice(1)) s += ` ${f4(x)} ${f4(y)}`; return s + ' E'; }
      case 'shapes':
        switch (option) {
          case 'diamond': return polygonPath(4, 0.1, 0); case 'hexagon': return polygonPath(6, 0.1, 0); case 'triangle': return polygonPath(3, 0.1, 0);
          case 'square': return polygonPath(4, 0.1, PI / 4); case 'octagon': return polygonPath(8, 0.1, 0); case 'pentagon': return polygonPath(5, 0.1, 0);
          case 'trapezoid': return 'M 0 0 L 0.1 0 L 0.15 0.15 L -0.05 0.15 Z E';
          case 'parallelogram': return 'M 0 0 L 0.15 0 L 0.1 0.15 L -0.05 0.15 Z E';
          default: return 'M 0 0 C 0.0552 0 0.1 0.0448 0.1 0.1 C 0.1 0.1552 0.0552 0.2 0 0.2 C -0.0552 0.2 -0.1 0.1552 -0.1 0.1 C -0.1 0.0448 -0.0552 0 0 0 Z E';
        }
      case 'loops': return 'M 0 0 C 0.08 -0.1 0.16 0 0.08 0.05 C 0 0.1 -0.08 0.1 -0.08 0 C -0.08 -0.1 0 -0.05 0 0 E';
      default: return presetPath(effect) || '';
    }
  }
  function tokenize(s) {
    const out = [], re = /([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)|([A-Za-z?])|[\s,]+|(.)/g;
    let m;
    while ((m = re.exec(s)) && out.length < 100000) {
      if (m[1] !== undefined) { const v = parseFloat(m[1]); out.push(Number.isFinite(v) ? { n: v } : { c: '?' }); }
      else if (m[2] !== undefined) out.push({ c: m[2] });
      else if (m[3] !== undefined) out.push({ c: m[3] });
    }
    return out;
  }
  function MotionPath(s) {
    const toks = tokenize(s || '');
    const pts = [];
    let cur = [0, 0], start = [0, 0], cmd = 'M', i = 0;
    const push = p => { if (pts.length < 20000) pts.push(p); };
    const num = k => (toks[k] && toks[k].n !== undefined ? toks[k].n : null);
    const STEPS = 16;
    outer: while (i < toks.length) {
      if (toks[i].c !== undefined) {
        cmd = toks[i].c; i++;
        if (cmd === 'Z' || cmd === 'z') { push(start); cur = start; continue; }
        if (cmd === 'E' || cmd === 'e') break;
        if ('MmLlCcQqHhVv'.includes(cmd)) continue;
        break;
      }
      const rel = cmd === cmd.toLowerCase(), base = rel ? cur : [0, 0], up = cmd.toUpperCase();
      const need = { M: 2, L: 2, C: 6, Q: 4, H: 1, V: 1 }[up];
      if (!need) break;
      const v = [];
      for (let k = 0; k < need; k++) { const x = num(i + k); if (x === null) break outer; v.push(x); }
      i += need;
      if (up === 'M') { cur = [base[0] + v[0], base[1] + v[1]]; start = cur; push(cur); cmd = rel ? 'l' : 'L'; }
      else if (up === 'L') { if (!pts.length) push(cur); cur = [base[0] + v[0], base[1] + v[1]]; push(cur); }
      else if (up === 'H' || up === 'V') { if (!pts.length) push(cur); cur = up === 'H' ? [base[0] + v[0], cur[1]] : [cur[0], base[1] + v[0]]; push(cur); }
      else if (up === 'C') {
        if (!pts.length) push(cur);
        const p0 = cur, p1 = [base[0] + v[0], base[1] + v[1]], p2 = [base[0] + v[2], base[1] + v[3]], p3 = [base[0] + v[4], base[1] + v[5]];
        for (let k = 1; k <= STEPS; k++) { const t = k / STEPS, u = 1 - t, a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t; push([a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]]); }
        cur = p3;
      } else if (up === 'Q') {
        if (!pts.length) push(cur);
        const p0 = cur, p1 = [base[0] + v[0], base[1] + v[1]], p2 = [base[0] + v[2], base[1] + v[3]];
        for (let k = 1; k <= STEPS; k++) { const t = k / STEPS, u = 1 - t; push([u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0], u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]]); }
        cur = p2;
      }
    }
    const points = pts.filter(p => Number.isFinite(p[0]) && Number.isFinite(p[1]));
    const cum = [];
    let acc = 0, prev = null;
    for (const p of points) { if (prev) { const d = Math.hypot(p[0] - prev[0], p[1] - prev[1]); if (Number.isFinite(d)) acc += d; } cum.push(acc); prev = p; }
    return {
      points,
      at(p) {
        const first = points[0] || [0, 0], len = cum.length ? cum[cum.length - 1] : 0;
        if (!(len > 0) || !Number.isFinite(len)) return first;
        const target = clampf(p, 0, 1, 0) * len;
        let lo = 0, hi = cum.length;
        while (lo < hi) { const mid = (lo + hi) >> 1; if (cum[mid] < target) lo = mid + 1; else hi = mid; }
        if (lo === 0) return first;
        if (lo >= points.length) return points[points.length - 1];
        const a = points[lo - 1], b = points[lo], seg = cum[lo] - cum[lo - 1], f = seg > 0 ? (target - cum[lo - 1]) / seg : 0;
        return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
      }
    };
  }

  // ------------------------------------------------------------------ states and effects (lib.rs, effects.rs)

  const identity = () => ({ visible: true, opacity: 1, ox: 0, oy: 0, sx: 1, sy: 1, rot: 0, clip: null, tint: null });
  const hidden = () => ({ ...identity(), visible: false });
  function compose(a, d) {
    let clip = a.clip;
    if (a.clip && d.clip) clip = [Math.max(a.clip[0], d.clip[0]), Math.max(a.clip[1], d.clip[1]), Math.min(a.clip[2], d.clip[2]), Math.min(a.clip[3], d.clip[3])];
    else if (d.clip) clip = d.clip;
    return { visible: a.visible && d.visible, opacity: a.opacity * d.opacity, ox: a.ox + d.ox, oy: a.oy + d.oy, sx: a.sx * d.sx, sy: a.sy * d.sy, rot: a.rot + d.rot, clip, tint: d.tint || a.tint };
  }
  function sanitize(s) {
    s.opacity = Math.min(1, Math.max(0, fin(s.opacity, 1)));
    s.ox = fin(s.ox, 0); s.oy = fin(s.oy, 0); s.sx = fin(s.sx, 1); s.sy = fin(s.sy, 1); s.rot = fin(s.rot, 0);
    if (s.clip) { const c = s.clip.map((v, i) => Math.min(1, Math.max(0, fin(v, i < 2 ? 0 : 1)))); s.clip = [c[0], c[1], Math.max(c[2], c[0]), Math.max(c[3], c[1])]; }
    return s;
  }
  const isIdentity = s => s.visible && s.opacity === 1 && !s.ox && !s.oy && s.sx === 1 && s.sy === 1 && !s.rot && !s.clip && !s.tint;
  const mix = (a, b, t) => { const m = (x, y) => Math.round(Math.min(255, Math.max(0, x + (y - x) * t))); return { r: m(a.r, b.r), g: m(a.g, b.g), b: m(a.b, b.b), a: m(a.a ?? 255, b.a ?? 255) }; };

  const EXIT_BASE = { disappear: 'appear', fadeOut: 'fade', flyOut: 'fly', floatOut: 'float', splitOut: 'split', wipeOut: 'wipe', shapeOut: 'shape', wheelOut: 'wheel', randomBarsOut: 'randomBars',
    shrinkTurn: 'grow', zoomOut: 'zoom', swivelOut: 'swivel', bounceOut: 'bounce', dissolveOut: 'dissolve', blindsOut: 'blinds', boxOut: 'box', checkerboardOut: 'checkerboard',
    diamondOut: 'diamond', peekOut: 'peek', stripsOut: 'strips', sinkDown: 'rise', spinnerOut: 'spinner', contract: 'expand' };
  const PERSISTS = ['spin', 'growShrink', 'desaturate', 'darken', 'lighten', 'transparency', 'objectColor', 'complementaryColor', 'lineColor', 'fillColor', 'fontColor'];
  const usesDefaultSmoothing = e => ['fly', 'float', 'rise', 'peek', 'lines', 'arcs', 'turns', 'shapes', 'loops', 'customPath'].includes(e) || presetPath(e) !== null;

  function Spec(a, xf, sw, sh) {
    const effect = a.cls === 'exit' ? (EXIT_BASE[a.effect] || a.effect) : a.effect;
    const path = a.cls === 'path' ? MotionPath(a.path && a.path.trim() ? a.path : defaultPath(a.effect, a.option)) : null;
    const s = { cls: a.cls, effect, option: a.effect === 'sinkDown' ? 'd' : a.option, accel: a.smoothStart, decel: a.smoothEnd, bounce: a.bounceEnd, amount: a.amount, color: a.color, path, xf, sw, sh };
    s.eased = p => (s.accel <= 0 && s.decel <= 0 && s.bounce <= 0 && usesDefaultSmoothing(s.effect) ? smooth(p) : ease(p, s.accel, s.decel, s.bounce));
    s.eval = (p, cur) => {
      p = clampf(p, 0, 1, 0);
      const st = s.cls === 'entr' ? entrance(s, p, false) : s.cls === 'exit' ? entrance(s, 1 - p, true) : s.cls === 'emph' ? emphasis(s, p, cur) : s.cls === 'path' ? motion(s, p) : identity();
      return sanitize(st);
    };
    s.endState = cur => {
      if (s.cls === 'exit') return hidden();
      if (s.cls === 'emph' && PERSISTS.includes(s.effect)) return sanitize(emphasis(s, 1, cur));
      if (s.cls === 'path') return sanitize(motion(s, 1));
      return identity();
    };
    return s;
  }
  function entrance(s, p, exit) {
    const e = s.eased(p), q = 1 - e, o = s.option || '';
    const { x, y, w, h } = s.xf, sw = s.sw, sh = s.sh;
    const st = identity();
    const centerClip = (f, horz, vert) => { f = Math.min(1, Math.max(0, f)) / 2; const [l, r] = horz ? [0.5 - f, 0.5 + f] : [0, 1]; const [t, b] = vert ? [0.5 - f, 0.5 + f] : [0, 1]; return [l, t, r, b]; };
    switch (s.effect) {
      case 'appear': st.visible = p > 0; break;
      case 'fade': case 'dissolve': case 'randomBars': case 'blinds': case 'checkerboard': case 'wheel': st.opacity = e; break;
      case 'fly': {
        const oo = o || 'b';
        if (oo.includes('l')) st.ox = -(x + w) * q;
        if (oo.includes('r')) st.ox = (sw - x) * q;
        if (oo.includes('t')) st.oy = -(y + h) * q;
        if (oo.includes('b')) st.oy = (sh - y) * q;
        break;
      }
      case 'float': case 'rise': { let d = o === 'd' ? -1 : 1; if (exit) d = -d; st.oy = d * 0.1 * sh * q; st.opacity = e; break; }
      case 'split': { const horz = o.startsWith('horz'); st.clip = horz ? centerClip(e, false, true) : centerClip(e, true, false); if (o.endsWith('In')) st.opacity = e; break; }
      case 'wipe': st.clip = o === 'l' ? [0, 0, e, 1] : o === 't' ? [0, 0, 1, e] : o === 'r' ? [1 - e, 0, 1, 1] : [0, 1 - e, 1, 1]; break;
      case 'shape': case 'circle': case 'box': case 'diamond': case 'expandClip': {
        const round = ['circle', 'diamond'].includes(s.effect) || o.startsWith('circle') || o.startsWith('diamond') || o.startsWith('plus');
        st.clip = centerClip(round ? Math.min(e * Math.SQRT2, 1) : e, true, true);
        if (o.endsWith('In') || o === 'in') st.opacity = e;
        break;
      }
      case 'grow': st.sx = e; st.sy = e; st.rot = -90 * q; st.opacity = e; break;
      case 'zoom': { const k = o === 'out' ? 1 + 3 * q : e; st.sx = k; st.sy = k; st.opacity = e; break; }
      case 'swivel': { const k = e * Math.cos(q * 2.5 * PI); if (o === 'vert') st.sy = k; else st.sx = k; st.opacity = Math.min(e, 1); break; }
      case 'bounce': { const b = bounceOut(p); st.oy = -(0.25 * sh) * (1 - b); st.ox = -0.1 * sw * (1 - p); st.opacity = Math.min(p * 4, 1); break; }
      case 'peek':
        if (o === 'l') { st.ox = -w * q; st.clip = [q, 0, 1, 1]; }
        else if (o === 'r') { st.ox = w * q; st.clip = [0, 0, e, 1]; }
        else if (o === 't') { st.oy = -h * q; st.clip = [0, q, 1, 1]; }
        else { st.oy = h * q; st.clip = [0, 0, 1, e]; }
        break;
      case 'strips': st.clip = o === 'ru' ? [0, q, e, 1] : o === 'ld' ? [q, 0, 1, e] : o === 'rd' ? [0, 0, e, e] : [q, q, 1, 1]; break;
      case 'expand': st.sx = 0.3 + 0.7 * e; st.opacity = e; break;
      case 'spinner': st.rot = 360 * q; st.sx = 0.5 + 0.5 * e; st.sy = 0.5 + 0.5 * e; st.opacity = e; break;
      default: st.opacity = e;
    }
    return st;
  }
  function emphasis(s, p, cur) {
    const e = s.eased(p), bump = Math.sin(p * PI), st = identity();
    const target = s.color || { r: 0xC0, g: 0, b: 0, a: 255 };
    switch (s.effect) {
      case 'pulse': st.sx = st.sy = 1 + 0.1 * bump; break;
      case 'colorPulse': st.tint = mix(cur, target, bump); break;
      case 'teeter': st.rot = 8 * Math.sin(p * 4 * PI) * (1 - p * 0.5); break;
      case 'spin': { const amt = Math.min(36000, Math.max(-36000, Number.isFinite(s.amount) ? s.amount : 360)); st.rot = (s.option === 'counterClockwise' ? -1 : 1) * amt * e; break; }
      case 'growShrink': {
        const amt = Math.min(100, Math.max(0, Number.isFinite(s.amount) && s.amount > 0 ? s.amount : 1.5)), k = 1 + (amt - 1) * e;
        if (s.option === 'horz') st.sx = k; else if (s.option === 'vert') st.sy = k; else { st.sx = k; st.sy = k; }
        break;
      }
      case 'desaturate': { const l = Math.round(0.299 * cur.r + 0.587 * cur.g + 0.114 * cur.b); st.tint = mix(cur, { r: l, g: l, b: l, a: cur.a }, e); break; }
      case 'darken': st.tint = mix(cur, { r: 0, g: 0, b: 0, a: cur.a }, 0.4 * e); break;
      case 'lighten': st.tint = mix(cur, { r: 255, g: 255, b: 255, a: cur.a }, 0.4 * e); break;
      case 'transparency': { const a = Math.min(1, Math.max(0, Number.isFinite(s.amount) ? s.amount : 0.5)); st.opacity = 1 - a * e; break; }
      case 'objectColor': case 'fillColor': case 'lineColor': case 'fontColor': st.tint = mix(cur, target, e); break;
      case 'complementaryColor': st.tint = mix(cur, { r: 255 - cur.r, g: 255 - cur.g, b: 255 - cur.b, a: cur.a }, e); break;
      case 'underline': case 'boldFlash': st.sx = st.sy = 1 + 0.04 * bump; if (s.effect === 'boldFlash') st.tint = mix(cur, { r: 0, g: 0, b: 0, a: cur.a }, 0.3 * bump); break;
      case 'wave': st.oy = -0.02 * s.sh * bump; st.sy = 1 + 0.05 * bump; break;
    }
    return st;
  }
  function motion(s, p) {
    const e = s.eased(p), [fx, fy] = s.path ? s.path.at(e) : [0, 0];
    return { ...identity(), ox: fx * s.sw, oy: fy * s.sh };
  }

  // ------------------------------------------------------------------ reading p:timing (read/timing.rs)

  function parseColor(e) {
    if (!e) return null;
    const c = kids(e).find(x => ['srgbClr', 'scrgbClr', 'prstClr', 'sysClr'].includes(x.localName));
    if (!c) return null;
    const hex = c.localName === 'sysClr' ? attr(c, 'lastClr') : attr(c, 'val');
    if (!hex || !/^[0-9a-f]{6}$/i.test(hex)) return null;
    return { r: parseInt(hex.slice(0, 2), 16), g: parseInt(hex.slice(2, 4), 16), b: parseInt(hex.slice(4, 6), 16), a: 255 };
  }
  const ms = (e, n) => { const v = (attr(e, n) || '').trim(); if (!v || v === 'indefinite') return null; const x = parseFloat(v); return Number.isFinite(x) ? Math.round(Math.min(86400000, Math.max(0, x))) : null; };
  const find = (e, n) => { if (!e) return null; for (const d of e.getElementsByTagNameNS('*', n)) return d; return null; };

  /** Paragraphs of each shape's text on the slide: spid → [{level, filled}] (for paragraph builds). */
  function slideParagraphs(doc) {
    const out = {};
    for (const sp of doc.getElementsByTagNameNS('*', 'sp')) {
      const c = find(sp, 'cNvPr'); if (!c) continue;
      const tx = kids(sp).find(x => x.localName === 'txBody'); if (!tx) continue;
      out[attr(c, 'id')] = kids(tx).filter(x => x.localName === 'p').map(p => {
        const ppr = child(p, 'pPr');
        let text = '';
        for (const t of p.getElementsByTagNameNS('*', 't')) text += t.textContent;
        return { level: +(attr(ppr, 'lvl') || 0), filled: text.trim().length > 0 };
      });
    }
    return out;
  }
  function buildRoles(paras) {
    const filled = paras.filter(p => p.filled);
    const top = filled.length ? Math.min(...filled.map(p => p.level)) : 0;
    let tops = 0, owner = null;
    return paras.map((p, i) => {
      if (p.level <= top && p.filled && tops < 1000) { tops++; owner = i; return { top: true }; }
      return owner === null ? { unowned: true } : { member: owner };
    });
  }

  /** The animations of a slide document, flattened as the Animation Pane lists them. */
  function parseTiming(doc) {
    const timing = [...doc.documentElement.children].find(e => e.localName === 'timing');
    const tn = timing && child(timing, 'tnLst');
    if (!tn) return [];
    const found = [];
    const collect = (e, trigger, depth) => {
      if (depth > 64 || found.length > 10000) return;
      for (const c of kids(e)) {
        if (c.localName === 'cTn' && attr(c, 'presetClass') !== null) { found.push({ ctn: c, trigger }); continue; }
        let trig = trigger;
        if (c.localName === 'seq') {
          const ctn = child(c, 'cTn');
          if (ctn && attr(ctn, 'nodeType') === 'interactiveSeq') { const sp = find(child(ctn, 'stCondLst'), 'spTgt'); trig = sp ? attr(sp, 'spid') : null; }
        }
        collect(c, trig, depth + 1);
      }
    };
    collect(tn, null, 0);
    const maxDur = (e, depth) => {
      if (depth > 64) return null;
      let best = null;
      for (const c of kids(e)) {
        if (c.localName === 'cTn') { const d = ms(c, 'dur'); if (d !== null && d > 1) best = best === null ? d : Math.max(best, d); }
        const d = maxDur(c, depth + 1); if (d !== null) best = best === null ? d : Math.max(best, d);
      }
      return best;
    };
    const bld = timing && child(timing, 'bldLst');
    const byPara = bld ? kids(bld).filter(p => p.localName === 'bldP' && attr(p, 'build') === 'p').map(p => [attr(p, 'spid'), attr(p, 'grpId') || '0']) : [];
    const paras = slideParagraphs(doc);
    const out = [];
    for (const { ctn: c, trigger } of found) {
      const cls = ['emph', 'exit', 'path', 'mediacall'].includes(attr(c, 'presetClass')) ? attr(c, 'presetClass') : 'entr';
      const preset = +(attr(c, 'presetID') || 0), sub = +(attr(c, 'presetSubtype') || 0);
      const tgt = find(c, 'spTgt'); if (!tgt) continue;
      const spid = attr(tgt, 'spid'); if (spid === null) continue;
      const prg = find(tgt, 'pRg');
      const paragraph = prg && attr(prg, 'st') !== null ? +attr(prg, 'st') : null;
      let effect = effectForPreset(cls, preset) || fallbackEffect(cls), option;
      if (cls === 'entr' && preset === 47) { effect = 'float'; option = 'd'; }
      else if (cls === 'exit' && preset === 47) { effect = 'floatOut'; option = 'd'; }
      else option = optionFromSubtype(effect, sub);
      const nt = attr(c, 'nodeType');
      const start = nt === 'clickEffect' ? 'click' : nt === 'afterEffect' ? 'after' : 'with';
      const cond = find(child(c, 'stCondLst'), 'cond');
      const a = {
        shape: spid, cls, effect, option, start, durationMs: maxDur(c, 0) ?? 0, delayMs: (cond && ms(cond, 'delay')) || 0, paragraph,
        trigger, autoReverse: bool(c, 'autoRev') === true, rewind: attr(c, 'fill') === 'remove',
        smoothStart: (+attr(c, 'accel') || 0) / 100000, smoothEnd: (+attr(c, 'decel') || 0) / 100000, bounceEnd: Math.min(1, Math.max(0, (+attr(c, 'bounceEnd') || 0) / 100000)),
        repeat: 1, path: null, amount: null, color: null, textBuild: 'asOne'
      };
      const rc = attr(c, 'repeatCount');
      if (rc === 'indefinite') a.repeat = Infinity; else if (rc !== null) { const v = parseFloat(rc); a.repeat = Number.isFinite(v) ? Math.min(10000, Math.max(1, Math.round(v / 1000))) : 1; }
      const mo = find(c, 'animMotion'); if (mo) a.path = attr(mo, 'path');
      const ro = find(c, 'animRot');
      if (ro) { const by = parseFloat(attr(ro, 'by')); a.amount = Number.isFinite(by) ? by / 60000 : null; if (a.effect === 'spin' && a.amount < 0) a.option = 'counterClockwise'; }
      else { const sc = find(c, 'animScale'), by = sc && child(sc, 'by'); if (by) { const x = parseFloat(attr(by, 'x')); a.amount = Number.isFinite(x) ? x / 100000 : null; } }
      const clr = find(c, 'animClr'); if (clr) a.color = parseColor(child(clr, 'to'));
      if (a.cls === 'emph' && a.effect === 'transparency') { const s = find(c, 'set'); const v = s && find(s, 'fltVal'); if (v) { const x = parseFloat(attr(v, 'val')); if (Number.isFinite(x)) a.amount = 1 - x; } }
      const grp = attr(c, 'grpId') || '0';
      out.push({ a, spid, grp, build: paragraph !== null && byPara.some(([s, g]) => s === spid && g === grp) });
    }
    // A paragraph build's effects become one by-paragraph animation when the timeline plays that back exactly.
    const anims = [];
    let i = 0;
    while (i < out.length) {
      let j = i + 1;
      if (out[i].build) while (j < out.length && out[j].build && out[j].spid === out[i].spid && out[j].grp === out[i].grp && out[j].a.trigger === out[i].a.trigger) j++;
      const run = out.slice(i, j), first = run[0];
      if (first.build && playsAsBuild(run.map(r => r.a), paras[first.spid] || [])) anims.push({ ...first.a, textBuild: 'byParagraph', paragraph: null });
      else run.forEach(r => anims.push(r.a));
      i = j;
    }
    return anims;
  }
  function playsAsBuild(effects, paras) {
    if (!paras.length) return false;
    const roles = buildRoles(paras);
    const expected = roles.map((r, i) => [i, r]).filter(([i, r]) => r.top || (r.member !== undefined && paras[i].filled));
    const first = effects[0];
    const settings = a => JSON.stringify({ ...a, start: 'click', paragraph: null });
    const base = settings(first);
    let matched = 0;
    for (let k = 0; k < expected.length; k++) {
      const [i, role] = expected[k], a = effects[k];
      if (!a) return false;
      let ok;
      if (role.top && k === 0) ok = true;
      else if (role.top && first.start === 'click') ok = a.start === 'click';
      else if (role.top) ok = a.start === 'after';
      else ok = a.start === 'with';
      if (a.paragraph !== i || !ok || settings(a) !== base) return false;
      matched = k + 1;
    }
    return matched === effects.length;
  }

  // ------------------------------------------------------------------ the timeline (timeline.rs)

  const STAGGER = 0.5;
  /** `shapes`: spid → {xf: {x, y, w, h} in slide px, paras: [{level, filled}], color: {r,g,b,a}}. */
  function Timeline(animations, shapes, sw, sh) {
    sw = Math.min(1e7, Math.max(1, fin(sw, 960))); sh = Math.min(1e7, Math.max(1, fin(sh, 540)));
    const effects = [], main = [], triggers = new Map(), groups = [], colors = new Map();
    const seqs = [['main', []]], seqIndex = new Map([['main', 0]]);
    animations.forEach((a, ai) => {
      const s = a.trigger ? 'trig:' + a.trigger : 'main';
      if (!seqIndex.has(s)) { seqs.push([s, []]); seqIndex.set(s, seqs.length - 1); }
      seqs[seqIndex.get(s)][1].push(ai);
    });
    const paraCache = new Map(), grouped = new Set();
    for (const [seq, members] of seqs) {
      const steps = [];
      let groupBegin = 0, maxEnd = 0;
      for (const ai of members) {
        const a = animations[ai], shape = shapes[a.shape];
        let paras;
        if (a.paragraph !== null && a.paragraph !== undefined) paras = [a.paragraph];
        else if (a.textBuild === 'byParagraph') {
          if (!paraCache.has(a.shape)) {
            const v = [];
            if (shape && shape.paras) buildRoles(shape.paras).forEach((role, i) => {
              if (role.top) v.push(i);
              else if (role.member !== undefined) { const key = a.shape + ':' + i + ':' + role.member; if (!grouped.has(key)) { grouped.add(key); groups.push([a.shape, i, role.member]); } }
            });
            if (!v.length) v.push(null);
            paraCache.set(a.shape, v);
          }
          paras = paraCache.get(a.shape);
        } else paras = [null];
        const xf0 = (shape && shape.xf) || { x: sw / 4, y: sh / 4, w: sw / 2, h: sh / 2 };
        const xf = { x: Math.min(1e7, Math.max(-1e7, fin(xf0.x, 0))), y: Math.min(1e7, Math.max(-1e7, fin(xf0.y, 0))), w: Math.min(1e7, Math.max(0, fin(xf0.w, sw / 2))), h: Math.min(1e7, Math.max(0, fin(xf0.h, sh / 2))) };
        const spec = Spec(a, xf, sw, sh);
        const dur = a.durationMs / 1000, delay = a.delayMs / 1000;
        const untilNextClick = a.repeat === Infinity;
        const iterations = untilNextClick || a.repeat <= 1 ? 1 : Math.min(a.repeat, 1e6);
        const span = dur * (a.autoReverse ? 2 : 1) * iterations;
        let firstStart = 0;
        paras.forEach((para, k) => {
          let mode = k === 0 ? a.start : a.start === 'click' ? 'click' : 'with';
          const firstOfSeq = steps.length === 0;
          if (firstOfSeq && seq !== 'main') mode = 'click';
          if (mode === 'click') { steps.push({ auto: false, duration: 0 }); groupBegin = 0; maxEnd = 0; }
          else if (mode === 'with') { if (firstOfSeq) steps.push({ auto: true, duration: 0 }); }
          else { if (firstOfSeq) steps.push({ auto: true, duration: 0 }); groupBegin = maxEnd; }
          const start = k > 0 && a.start !== 'click' ? firstStart + k * STAGGER : groupBegin + delay;
          if (k === 0) firstStart = start;
          const end = start + span;
          maxEnd = Math.max(maxEnd, end);
          const step = Math.max(0, steps.length - 1);
          const last = steps[steps.length - 1];
          if (last) last.duration = Math.max(last.duration, a.cls === 'mediacall' ? start : end);
          effects.push({ anim: ai, shape: a.shape, paragraph: para, sequence: seq, step, start, duration: dur, iterations, untilNextClick, autoReverse: a.autoReverse, rewind: a.rewind, span, cls: a.cls, spec });
        });
      }
      if (seq === 'main') main.push(...steps); else triggers.set(seq, steps);
    }
    for (const e of effects) if (!colors.has(e.shape)) colors.set(e.shape, (shapes[e.shape] && shapes[e.shape].color) || { r: 0x15, g: 0x60, b: 0x82, a: 255 });

    function status(e, step, t) {
      if (e.step < step) return { done: true };
      if (e.step > step) return null;
      t = fin(t, 0);
      if (t <= 0) return null;
      const local = t - e.start;
      if (local < 0) return null;
      const dur = e.duration;
      if (!(dur > 0) || !Number.isFinite(dur)) return { done: true };
      if (!e.untilNextClick && local >= e.span) return { done: true };
      const period = e.autoReverse ? 2 * dur : dur;
      let c = local % period; if (!Number.isFinite(c)) c = 0;
      const p = c > dur ? 2 - c / dur : c / dur;
      return { p: Math.min(1, Math.max(0, p)) };
    }
    return {
      steps: () => main.length,
      stepIsAuto: i => !!(main[i] && main[i].auto),
      stepDuration: i => (main[i] ? main[i].duration : 0),
      isEmpty: () => effects.length === 0,
      animatedShapes: () => [...new Set(effects.map(e => e.shape))],
      paragraphTargets(shape) {
        const v = effects.filter(e => e.shape === shape && e.paragraph !== null).map(e => e.paragraph);
        groups.filter(g => g[0] === shape && v.includes(g[2])).forEach(g => v.push(g[1]));
        return [...new Set(v)].sort((a, b) => a - b);
      },
      /** State when `step` main steps are done and step `step` has played for `t` seconds. */
      state(shape, para, step, t) {
        if (para !== null && para !== undefined) { const g = groups.find(x => x[0] === shape && x[1] === para); if (g) para = g[2]; } else para = null;
        const ordered = effects.filter(e => e.shape === shape && e.paragraph === para).sort((x, y) => x.anim - y.anim);
        const base = colors.get(shape) || { r: 0x15, g: 0x60, b: 0x82, a: 255 };
        let st = identity();
        if (ordered[0] && ordered[0].cls === 'entr') st.visible = false;
        for (const e of ordered) {
          if (e.sequence !== 'main') continue; // triggered sequences are not played here
          const cur = st.tint || base, revert = e.rewind || e.autoReverse;
          const s = status(e, step, t);
          if (!s) continue;
          if (!s.done) { if (e.cls === 'entr' || e.cls === 'exit') st.visible = true; st = compose(st, e.spec.eval(s.p, cur)); }
          else if (e.cls === 'entr') st.visible = !revert;
          else if (e.cls === 'exit') st.visible = revert;
          else if ((e.cls === 'emph' || e.cls === 'path') && !revert) st = compose(st, e.spec.endState(cur));
        }
        return sanitize(st);
      }
    };
  }

  // ------------------------------------------------------------------ the slide's HTML

  const shapeEls = root => root.querySelectorAll('[data-sp-origin="slide"], [data-sp-origin="slide"] [data-sp-id]');
  const shapeEl = (root, id) => { for (const el of shapeEls(root)) if (el.dataset.spId === String(id)) return el; return null; };
  /** Box of a shape element in slide px (from its own position, or measured for group members). */
  function boxOf(root, el) {
    if (el.dataset.spOrigin === 'slide') return { x: parseFloat(el.style.left) || 0, y: parseFloat(el.style.top) || 0, w: parseFloat(el.style.width) || el.offsetWidth, h: parseFloat(el.style.height) || el.offsetHeight };
    const k = root.getBoundingClientRect(), s = (k.width / root.offsetWidth) || 1, r = el.getBoundingClientRect();
    return { x: (r.left - k.left) / s, y: (r.top - k.top) / s, w: r.width / s, h: r.height / s };
  }
  function fillOf(el) {
    const p = el.querySelector(':scope > svg path[fill]:not([fill="none"])');
    const v = p && p.getAttribute('fill');
    const m = v && /^#([0-9a-f]{6})$/i.exec(v);
    return m ? { r: parseInt(m[1].slice(0, 2), 16), g: parseInt(m[1].slice(2, 4), 16), b: parseInt(m[1].slice(4, 6), 16), a: 255 } : null;
  }
  /** The timeline of slide `anims` as drawn in `root` (the slide root of pptx-renderer). */
  function timelineFor(anims, root, paras) {
    if (!anims || !anims.length || !root) return null;
    const shapes = {};
    for (const a of anims) {
      if (shapes[a.shape]) continue;
      const el = shapeEl(root, a.shape);
      shapes[a.shape] = { xf: el ? boxOf(root, el) : null, paras: (paras && paras[a.shape]) || [], color: el ? fillOf(el) : null };
    }
    return Timeline(anims, shapes, root.offsetWidth || 960, root.offsetHeight || 540);
  }
  function applyEl(el, st) {
    if (!el.dataset.fxBase) el.dataset.fxBase = JSON.stringify({ t: el.style.transform || '', o: el.style.opacity || '', v: el.style.visibility || '', c: el.style.clipPath || '', origin: el.style.transformOrigin || '' });
    const b = JSON.parse(el.dataset.fxBase);
    if (!st || isIdentity(st)) {
      el.style.transform = b.t; el.style.opacity = b.o; el.style.visibility = b.v; el.style.clipPath = b.c; el.style.transformOrigin = b.origin;
      restoreTint(el);
      return;
    }
    el.style.visibility = st.visible ? b.v : 'hidden';
    el.style.opacity = String(st.opacity * (b.o === '' ? 1 : +b.o));
    const tf = [];
    if (st.ox || st.oy) tf.push(`translate(${st.ox}px, ${st.oy}px)`);
    if (st.rot) tf.push(`rotate(${st.rot}deg)`);
    if (st.sx !== 1 || st.sy !== 1) tf.push(`scale(${st.sx}, ${st.sy})`);
    el.style.transformOrigin = b.origin || '50% 50%';
    el.style.transform = (tf.join(' ') + ' ' + b.t).trim();
    el.style.clipPath = st.clip ? `inset(${st.clip[1] * 100}% ${(1 - st.clip[2]) * 100}% ${(1 - st.clip[3]) * 100}% ${st.clip[0] * 100}%)` : b.c;
    if (st.tint) setTint(el, st.tint); else restoreTint(el);
  }
  function setTint(el, c) {
    const col = `rgb(${c.r}, ${c.g}, ${c.b})`;
    el.querySelectorAll(':scope > svg path').forEach(p => {
      const f = p.getAttribute('fill');
      if (!f || f === 'none' || f.startsWith('url(')) return;
      if (p.dataset.fxFill === undefined) p.dataset.fxFill = f;
      p.setAttribute('fill', col);
    });
    if (!el.querySelector(':scope > svg path[data-fx-fill]')) el.querySelectorAll('span').forEach(s => { if (s.dataset.fxColor === undefined) s.dataset.fxColor = s.style.color || ''; s.style.color = col; });
  }
  function restoreTint(el) {
    el.querySelectorAll('path[data-fx-fill]').forEach(p => { p.setAttribute('fill', p.dataset.fxFill); delete p.dataset.fxFill; });
    el.querySelectorAll('span[data-fx-color]').forEach(s => { s.style.color = s.dataset.fxColor; delete s.dataset.fxColor; });
  }
  /** Applies the timeline's state at (`step`, `t`) to the slide's shapes and paragraphs. */
  function apply(tl, root, step, t) {
    if (!tl || !root) return;
    for (const id of tl.animatedShapes()) {
      const el = shapeEl(root, id); if (!el) continue;
      applyEl(el, tl.state(id, null, step, t));
      const targets = tl.paragraphTargets(id);
      if (targets.length) el.querySelectorAll('[data-para]').forEach(p => { const k = +p.dataset.para; applyEl(p, targets.includes(k) ? tl.state(id, k, step, t) : null); });
    }
  }
  /** Shapes that the slide's first state hides (entrance animations): Morph leaves them out. */
  function hiddenAtStart(tl) {
    const out = new Set();
    if (!tl) return out;
    for (const id of tl.animatedShapes()) if (!tl.state(id, null, 0, 0).visible) out.add(String(id));
    return out;
  }

  return { ANIMATIONS, parseTiming, slideParagraphs, Timeline, timelineFor, apply, hiddenAtStart, MotionPath, defaultPath, presetPath, shapeEl, shapeEls, boxOf };
})();
