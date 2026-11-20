/* Marginalia Lectoris — "Take notes here": highlights made in the tablet's reader open here, one after
 * another, in the note dialog (title, note, tags, [[links]], dictation). The tablet saves each highlight at
 * once, so nothing is lost if this page closes; it then writes its notes itself again.
 */
const relay = { on: false, client: null, seen: new Set(), queue: [], open: null, loop: 0, titleTimer: 0, baseTitle: '' };

function relaySet(on) {
  if (EMBEDDED) { try { parent.postMessage({ ml: 'relay', on }, location.origin); } catch (_) {} return; }
  on ? relayStart() : relayStop(true);
}
async function relayStart() {
  if (relay.on) return;
  relay.client = relay.client || ('c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7));
  try { await api('relay/claim', { method: 'POST', json: { client: relay.client } }); } catch (e) { return fail(e); }
  relay.on = true; relay.seen = new Set(); relay.queue = [];
  try { sessionStorage.setItem('ml.relay', '1'); } catch (_) {}
  relayPill(); toast('Highlights made on the tablet now open here', 3000);
  const my = ++relay.loop; relayPoll(my);
}
async function relayStop(tell) {
  if (!relay.on) return;
  relay.on = false; relay.loop++;
  try { sessionStorage.removeItem('ml.relay'); } catch (_) {}
  relayPill(); relayFlash(false);
  if (tell) { try { await api('relay/release', { method: 'POST', json: { client: relay.client } }); } catch (_) {} toast('Notes are written on the tablet again'); }
}
async function relayPoll(my) {
  let fails = 0;
  while (relay.on && my === relay.loop) {
    try {
      const r = await api(`relay?client=${relay.client}&wait=20000&seen=${[...relay.seen].join(',')}`);
      if (my !== relay.loop) return;
      fails = 0;
      if (!r.active) { relayStop(false); toast(r.taken ? 'Another browser takes the notes now' : 'Switched off on the tablet', 4000); return; }
      // Each note comes with its round: a note continued on the next page comes again, with the rest joined on.
      (r.items || []).forEach(it => {
        const id = typeof it === 'object' ? it.id : it, key = typeof it === 'object' ? `${it.id}:${it.round}` : String(it);
        if (!relay.seen.has(key)) { relay.seen.add(key); if (!relay.queue.includes(id) && relay.open !== id) relay.queue.push(id); }
      });
      relayNext();
    } catch (e) {
      if (++fails > 4) { relayStop(false); toast('Lost contact with the tablet; it writes its notes itself again', 5000); return; }
      await new Promise(r => setTimeout(r, 1500));
    }
  }
}
/** Opens the next highlight that waits, unless one is open. */
async function relayNext() {
  relayPill();
  if (relay.open || !relay.queue.length) { relayFlash(relay.queue.length > 0 || !!relay.open); return; }
  const id = relay.queue.shift();
  let n; try { n = await api(`highlight/${id}`); } catch (_) { api('relay/done', { method: 'POST', json: { id } }).catch(() => {}); return relayNext(); }
  relay.open = id; relayFlash(true);
  const finish = () => {
    if (relay.open !== id) return;
    relay.open = null;
    api('relay/done', { method: 'POST', json: { id } }).catch(() => {});
    tagCache = null;
    setTimeout(relayNext, 150);
  };
  const more = relay.queue.length;
  const where = [n.documentTitle, n.printedPageLabel ? 'p. ' + n.printedPageLabel : (n.pageNumber ? 'PDF p. ' + n.pageNumber : ''), n.chapterTitle].filter(Boolean).join(' · ');
  editNote(n, () => finish(), {
    heading: 'From the tablet', sub: where + (more ? ` — ${more} more waiting` : ''), cancelLabel: more ? 'Skip' : 'Leave as it is', focusTitle: true,
    onClose: finish,
    onContinue: async () => {
      await api('relay/continue', { method: 'POST', json: { id } });
      toast('Select the rest of the quotation on the tablet — the note comes back here with it', 5000);
    },
  });
}
/** The small switch in the corner while it is on, with how many wait. */
function relayPill() {
  let el = document.getElementById('relaypill');
  if (!relay.on) { if (el) el.remove(); return; }
  if (!el) { el = h('button', { id: 'relaypill', class: 'relaypill', title: 'Highlights made on the tablet open here. Click to stop.', onclick: () => confirmBox('Stop taking the notes here?', 'Highlights made on the tablet then open in the tablet’s own note dialog again.', 'Stop', () => relayStop(true)) }); document.body.append(el); }
  const waiting = relay.queue.length + (relay.open ? 1 : 0);
  el.textContent = '● Notes from the tablet' + (waiting ? ` · ${waiting}` : '');
  el.classList.toggle('busy', waiting > 0);
}
/** The tab's title blinks while a note waits and the window is not looked at. */
function relayFlash(on) {
  clearInterval(relay.titleTimer);
  if (relay.baseTitle) document.title = relay.baseTitle;
  if (!on || !document.hidden) return;
  relay.baseTitle = document.title; let k = 0;
  relay.titleTimer = setInterval(() => { if (!document.hidden) { relayFlash(false); return; } document.title = (k++ % 2 ? '✎ New note — ' : '') + relay.baseTitle; }, 1000);
}
document.addEventListener('visibilitychange', () => { if (!document.hidden) relayFlash(false); });
window.addEventListener('pagehide', () => {
  if (!relay.on) return;
  try { fetch('/api/relay/release', { method: 'POST', keepalive: true, credentials: 'same-origin', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/json' }, body: JSON.stringify({ client: relay.client }) }); } catch (_) {}
});
// The tabs' frames ask the page around them, which holds the switch.
window.addEventListener('message', e => { if (e.origin === location.origin && e.data && e.data.ml === 'relay' && !EMBEDDED) relaySet(!!e.data.on); });
// After a reload it comes back on, if it was on.
setTimeout(() => { try { if (!EMBEDDED && sessionStorage.getItem('ml.relay') === '1') relayStart(); } catch (_) {} }, 800);
