/* Marginalia Lectoris — the tablet's screen in the browser. Android asks on the tablet before the screen
 * is shared. With "Marginalia remote control" switched on there (Accessibility), a click is a tap, a drag is
 * a swipe, holding is a long press, the wheel scrolls, and typing goes into the field that has the cursor.
 */
async function showScreen() {
  const app = clear(document.getElementById('app'));
  state.tab = 'screen';
  app.appendChild(header());
  const main = h('main', { class: 'scrpage' }); app.appendChild(main);
  const img = h('img', { class: 'scrimg', alt: 'The tablet’s screen', draggable: 'false' });
  const stage = h('div', { class: 'scrstage', tabindex: '0', title: 'Click: tap · drag: swipe · hold: long press · wheel: scroll · type to write' }, img);
  const status = h('div', { class: 'scrstatus muted' });
  const keys = h('div', { class: 'scrkeys' });
  const bar = h('div', { class: 'scrbar' });
  main.append(bar, h('div', { class: 'scrwrap' }, stage, h('aside', { class: 'scrside' }, status, keys)));
  let st = null, seq = -1, alive = true, failures = 0, url = null, frames = 0, t0 = performance.now();
  const leave = () => { alive = false; if (url) URL.revokeObjectURL(url); };
  window.addEventListener('hashchange', leave, { once: true });

  const post = (path, json) => api('screen/' + path, { method: 'POST', json: json || {} });
  const draw = () => {
    clear(bar).append(...[
      h('b', { class: 'serif', style: { fontSize: '18px' } }, 'Tablet screen'),
      h('span', { style: { flex: 1 } }),
      st && st.phase === 'on' ? h('button', { class: 'btn', onclick: async () => { try { st = await post('stop'); draw(); } catch (e) { fail(e); } } }, 'Stop sharing') : null,
      !st || st.phase !== 'on' ? h('button', { class: 'btn fill', onclick: start }, st && st.phase === 'waiting' ? 'Ask again' : 'Show the screen') : null,
    ].filter(Boolean));
    clear(status);
    if (!st) return;
    if (st.phase === 'off') status.append(...[h('p', null, 'The screen is not shared.'), h('p', null, 'Press “Show the screen”. The tablet then asks for permission (Android’s own question); answer it on the tablet.'), st.message ? h('p', { style: { color: 'var(--red)' } }, st.message) : null].filter(Boolean));
    else if (st.phase === 'waiting') status.append(h('p', null, h('b', null, 'Answer on the tablet.')), h('p', null, 'If Marginalia was not in front on the tablet, tap the notification “Show the screen in the browser?” there, then allow it.'));
    else status.append(...[h('p', null, st.control ? 'Shared. Click to tap, drag to swipe, hold for a long press, use the wheel to scroll, and type to write in the field that has the cursor on the tablet.' : 'Shared, view only.'),
      st.control ? null : h('p', null, 'To control it from here: on the tablet, Settings → Accessibility → Marginalia remote control → on. ',
        h('a', { href: 'javascript:void 0', onclick: () => post('accessibility').then(() => toast('Opened on the tablet, if Android allowed it')).catch(fail) }, 'Open that page on the tablet')),
      h('p', { class: 'mono', style: { fontSize: '11px' } }, `${st.width}×${st.height} · ${(frames / Math.max(1, (performance.now() - t0) / 1000)).toFixed(1)} pictures/s`)].filter(Boolean));
    clear(keys);
    if (st.phase === 'on' && st.control) {
      const k = (label, key, title) => h('button', { class: 'btn', title, onclick: () => send({ type: 'key', key }) }, label);
      keys.append(h('div', { class: 'label' }, 'Buttons'), h('div', { class: 'row', style: { gap: '4px', flexWrap: 'wrap' } },
        k('◁ Back', 'back', 'Back (Esc)'), k('○ Home', 'home', 'Home'), k('▢ Apps', 'recents', 'Recent apps'), k('Notifications', 'notifications', 'Pull down the notifications'), k('Quick settings', 'quick', '')),
        h('div', { class: 'label', style: { marginTop: '10px' } }, 'Text'));
      const tx = h('input', { placeholder: 'Send text to the tablet', style: { width: '100%', boxSizing: 'border-box' } });
      tx.addEventListener('keydown', e => { e.stopPropagation(); if (e.key === 'Enter' && tx.value) { send({ type: 'text', text: tx.value }); tx.value = ''; } });
      keys.append(tx, h('div', { class: 'muted', style: { fontSize: '12px', marginTop: '4px' } }, 'Or click the screen and type: letters go into the field that has the cursor on the tablet; Backspace and Enter work too.'));
    }
  };
  async function start() { try { st = await post('start'); draw(); poll(); } catch (e) { fail(e); } }
  async function send(o) { try { await post('input', o); } catch (e) { toast(e.message || 'The tablet did not take it'); } }

  // Pictures: asked for one after another; each answer waits until the screen has changed.
  let pulling = false;
  async function pull() {
    if (pulling) return; pulling = true;
    while (alive && st && st.phase === 'on') {
      try {
        const r = await fetch(`/api/screen/frame?after=${seq}`, { credentials: 'same-origin', headers: { 'X-Marginalia': '1' } });
        if (r.status === 409) { st = await r.json(); draw(); break; }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const n = +(r.headers.get('X-Seq') || 0);
        if (n !== seq) { seq = n; const b = await r.blob(); const u = URL.createObjectURL(b); img.src = u; if (url) setTimeout(((old) => () => URL.revokeObjectURL(old))(url), 1000); url = u; frames++; }
        failures = 0;
      } catch (e) { if (++failures > 5) { toast('Lost contact with the tablet'); break; } await new Promise(r => setTimeout(r, 600)); }
    }
    pulling = false;
  }
  async function poll() {
    if (!alive) return;
    try { st = await api('screen'); } catch (_) {}
    draw();
    if (st && st.phase === 'on') pull();
    setTimeout(poll, st && st.phase === 'waiting' ? 800 : 2500);
  }

  // Input: positions as fractions of the picture, so the tablet's own size does not matter here.
  const frac = e => { const r = img.getBoundingClientRect(); return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height]; };
  const inside = ([x, y]) => x >= 0 && x <= 1 && y >= 0 && y <= 1;
  let down = null, longTimer = 0;
  stage.addEventListener('pointerdown', e => {
    if (e.button !== 0 || !st || st.phase !== 'on' || !st.control) return;
    const p = frac(e); if (!inside(p)) return;
    e.preventDefault(); stage.focus();
    down = { p, t: performance.now(), moved: false, long: false };
    stage.setPointerCapture(e.pointerId);
    longTimer = setTimeout(() => { if (down && !down.moved) { down.long = true; send({ type: 'long', x: p[0], y: p[1] }); flash(e); } }, 600);
  });
  stage.addEventListener('pointermove', e => { if (!down) return; const p = frac(e); if (Math.hypot((p[0] - down.p[0]) * img.width, (p[1] - down.p[1]) * img.height) > 6) down.moved = true; });
  stage.addEventListener('pointerup', e => {
    clearTimeout(longTimer); if (!down) return; const d = down; down = null; if (d.long) return;
    const p = frac(e).map(v => Math.max(0, Math.min(1, v)));
    if (d.moved) send({ type: 'swipe', x: d.p[0], y: d.p[1], x2: p[0], y2: p[1], ms: Math.max(80, Math.min(1500, performance.now() - d.t)) });
    else { send({ type: 'tap', x: p[0], y: p[1] }); flash(e); }
  });
  let wheelAcc = 0, wheelT = 0, wheelAt = null;
  stage.addEventListener('wheel', e => {
    if (!st || st.phase !== 'on' || !st.control) return;
    e.preventDefault(); const p = frac(e); if (!inside(p)) return;
    wheelAcc += e.deltaY * (e.deltaMode ? 30 : 1); wheelAt = p;
    clearTimeout(wheelT);
    wheelT = setTimeout(() => {
      const dist = Math.max(-0.6, Math.min(0.6, -wheelAcc / 1200)); wheelAcc = 0;
      const y1 = Math.max(0.05, Math.min(0.95, wheelAt[1])), y2 = Math.max(0.02, Math.min(0.98, y1 + dist));
      send({ type: 'swipe', x: wheelAt[0], y: y1, x2: wheelAt[0], y2, ms: 300 });
    }, 120);
  }, { passive: false });
  // Typing while the screen has the focus
  let typed = '', typeT = 0;
  const flushType = () => { clearTimeout(typeT); if (typed) { send({ type: 'text', text: typed }); typed = ''; } };
  stage.addEventListener('keydown', e => {
    if (!st || st.phase !== 'on' || !st.control || e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.key.length === 1) { e.preventDefault(); typed += e.key; clearTimeout(typeT); typeT = setTimeout(flushType, 250); }
    else if (e.key === 'Backspace') { e.preventDefault(); flushType(); send({ type: 'key', key: 'backspace' }); }
    else if (e.key === 'Enter') { e.preventDefault(); flushType(); send({ type: 'key', key: 'enter' }); }
    else if (e.key === 'Escape') { e.preventDefault(); send({ type: 'key', key: 'back' }); }
  });
  stage.addEventListener('paste', e => { const t = e.clipboardData && e.clipboardData.getData('text'); if (t && st && st.control) { e.preventDefault(); send({ type: 'text', text: t }); } });
  const flash = e => { const r = stage.getBoundingClientRect(); const dot = h('i', { class: 'scrtap', style: { left: (e.clientX - r.left) + 'px', top: (e.clientY - r.top) + 'px' } }); stage.append(dot); setTimeout(() => dot.remove(), 450); };

  try { st = await api('screen'); } catch (e) { clear(status).append(h('p', null, 'This needs a newer version of the app on the tablet.')); }
  draw(); poll();
}
