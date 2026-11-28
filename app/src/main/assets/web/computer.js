// What Marginalia Companion on a computer is doing (it tells the tablet every few seconds): a small panel at the
// bottom left while it works, with each job in its queue. Checked every 10 s; quiet when signed out or idle.
(function () {
  const ACTIVE = ['queued', 'transcribing', 'matching', 'sending', 'send', 'waiting'];
  let panel = null;
  function line(j) {
    let s = j.label || j.state;
    if (j.state === 'transcribing' || j.state === 'sending') s += ', ' + Math.floor((j.frac || 0) * 100) + '%';
    return s;
  }
  function render(st) {
    const jobs = (st.jobs || []).filter(j => ACTIVE.includes(j.state));
    const fresh = st.age != null && st.age < 180000;
    if (!fresh || !jobs.length) { if (panel) panel.hidden = true; return; }
    if (!panel) { panel = h('div', { class: 'uploads computer', style: { left: '16px', right: 'auto' } }); document.body.appendChild(panel); }
    clear(panel);
    panel.hidden = false;
    panel.appendChild(h('div', { class: 'uh' }, 'On ' + (st.computer || 'the computer') + (st.paused ? ' (paused)' : '')));
    jobs.forEach((j, i) => {
      const bar = h('div', { class: 'p' }, h('i', { style: { width: Math.round((j.frac || 0) * 100) + '%' } }));
      panel.appendChild(h('div', { class: 'ui' },
        h('div', { class: 'n' }, (i + 1) + '. ' + (j.title || '')),
        (j.state === 'transcribing' || j.state === 'sending') ? bar : null,
        h('div', { class: 's' }, line(j) + (j.msg && j.state !== 'transcribing' ? ' · ' + j.msg : (j.msg && j.msg.includes(' · ') ? ' · ' + j.msg.split(' · ')[1] : ''))))
      );
    });
  }
  async function poll() {
    try {
      const r = await fetch('/api/companion/status', { headers: { 'X-Marginalia': '1' }, credentials: 'same-origin' });
      if (r.ok) render(await r.json());
    } catch (_) { /* the tablet is away: try again */ }
    setTimeout(poll, document.hidden ? 30000 : 10000);
  }
  setTimeout(poll, 3000);
})();
