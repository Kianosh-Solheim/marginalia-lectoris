// Google Calendar and the calendar page: your Google events together with the course sessions; events made into
// sessions; sessions sent to Google Calendar; .ics files read into sessions. Every call to Google is made by the
// tablet, and only when a page asks (the calendar page when it opens or moves, or Refresh).

const gcal = { status: null, cache: null };
const p2 = n => String(n).padStart(2, '0');
const ymd = d => `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`;
const hm = d => `${p2(d.getHours())}:${p2(d.getMinutes())}`;
/** A local time as RFC 3339 with its offset (what Google expects). */
const isoLocal = d => { const z = -d.getTimezoneOffset(), a = Math.abs(z); return `${ymd(d)}T${hm(d)}:00${z >= 0 ? '+' : '-'}${p2(Math.floor(a / 60))}:${p2(a % 60)}`; };
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const dayStart = d => { const x = new Date(d); x.setHours(0, 0, 0, 0); return x; };
const weekStart = d => { const x = dayStart(d); x.setDate(x.getDate() - ((x.getDay() + 6) % 7)); return x; };
const localZone = () => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (_) { return ''; } };
/** An event's start/end as Dates (all-day dates at local midnight). */
const evDate = (v, allDay) => allDay ? new Date(v + 'T00:00:00') : new Date(v);

async function gcalStatus(fresh) {
  if (gcal.status && !fresh) return gcal.status;
  gcal.status = await api('gcal/status');
  return gcal.status;
}

// ------------------------------------------------------------------ session ↔ event times

/** "10:15–12:00", "10.15-12", "10–12", "10:15" → [start minutes, end minutes] (a single time lasts two hours). */
function sessionMinutes(t) {
  const m = String(t || '').match(/(\d{1,2})(?:[:.](\d{2}))?\s*(?:[–—-]|to|til)\s*(\d{1,2})(?:[:.](\d{2}))?/i);
  if (m) { const a = +m[1] * 60 + +(m[2] || 0), b = +m[3] * 60 + +(m[4] || 0); if (a < 24 * 60 && b <= 24 * 60 && b > a) return [a, b]; }
  const one = String(t || '').match(/(\d{1,2})[:.](\d{2})/);
  if (one) { const a = +one[1] * 60 + +one[2]; if (a < 24 * 60) return [a, Math.min(a + 120, 24 * 60 - 1)]; }
  return null;
}
/** A course session as a Google event (all day when it has no time). */
function sessionEvent(c, x) {
  const mins = sessionMinutes(x.time);
  const reads = (x.readings || []).map(r => '• ' + (r.text || r.chapter || '') + (r.pages ? ' (pp. ' + r.pages + ')' : '') + (r.required === 'optional' ? ' — optional' : '')).filter(s => s.length > 2);
  const description = [x.topic ? x.topic : '', x.note || '', reads.length ? 'Readings:\n' + reads.join('\n') : '', 'From Marginalia Lectoris · ' + [c.code, c.title].filter(Boolean).join(' ')].filter(Boolean).join('\n\n');
  const title = [c.code || c.title, x.kind && x.kind !== 'Other' ? x.kind.toLowerCase() : '', ].filter(Boolean).join(' ') + (x.topic ? ': ' + x.topic : '');
  const base = { title, description, location: x.room || '', session: `${c.id}/${x.id}`, timeZone: localZone() };
  if (!mins) return Object.assign(base, { allDay: true, start: x.date, end: ymd(addDays(new Date(x.date + 'T12:00:00'), 1)) });
  const d0 = new Date(x.date + 'T00:00:00');
  const at = m => { const d = new Date(d0); d.setMinutes(m); return isoLocal(d); };
  return Object.assign(base, { allDay: false, start: at(mins[0]), end: at(mins[1]) });
}
/** What session a Google (or .ics) event would make. */
function eventSession(e, c) {
  const s = evDate(e.start, e.allDay), en = e.end ? evDate(e.end, e.allDay) : null;
  const words = (e.title || '').toLowerCase();
  const kind = /eksamen|exam/.test(words) ? 'Exam' : /frist|deadline|innlevering|submission/.test(words) ? 'Deadline' : /seminar|gruppe/.test(words) ? 'Seminar'
    : /workshop|verksted/.test(words) ? 'Workshop' : /veiledning|supervision/.test(words) ? 'Supervision' : 'Lecture';
  let topic = (e.title || '').trim();
  if (c && c.code) topic = topic.replace(new RegExp('^\\s*' + c.code.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*[-–:,]?\\s*', 'i'), '');
  topic = topic.replace(/^(forelesning|lecture|seminar|gruppe\w*)\s*[-–:,]\s*/i, '');
  if (/^(forelesning|lecture|seminar|undervisning|teaching)?\s*((seminar)?grupp?e|group|gr\.)?\s*\d*[a-z]?$/i.test(topic.trim())) topic = '';
  return { id: cid(), date: ymd(s), time: e.allDay ? '' : hm(s) + (en ? '–' + hm(en) : ''), kind, room: e.location || '', topic, note: '', readings: [] };
}

// ------------------------------------------------------------------ .ics files

/** Reads an iCalendar file into events (repeating weekly/daily events are spread out, up to two years). */
function icsParse(text) {
  const lines = String(text).replace(/\r\n?/g, '\n').replace(/\n[ \t]/g, '').split('\n');
  const out = []; let cur = null;
  const unesc = v => v.replace(/\\n/gi, '\n').replace(/\\([,;\\])/g, '$1');
  const when = (v, params) => {
    const m = v.match(/^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?$/);
    if (!m) return null;
    if (!m[4] || /VALUE=DATE(?!-)/i.test(params)) return { allDay: true, d: new Date(+m[1], +m[2] - 1, +m[3]) };
    // Times with a TZID are taken as local time (the usual case: a timetable in your own time zone).
    return { allDay: false, d: m[7] ? new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0))) : new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)) };
  };
  for (const ln of lines) {
    if (ln === 'BEGIN:VEVENT') { cur = { ex: [] }; continue; }
    if (ln === 'END:VEVENT') { if (cur && cur.start) out.push(cur); cur = null; continue; }
    if (!cur) continue;
    const i = ln.indexOf(':'); if (i < 0) continue;
    const head = ln.slice(0, i), val = ln.slice(i + 1), name = head.split(';')[0].toUpperCase(), params = head.slice(name.length);
    if (name === 'SUMMARY') cur.title = unesc(val);
    else if (name === 'LOCATION') cur.location = unesc(val);
    else if (name === 'DESCRIPTION') cur.description = unesc(val);
    else if (name === 'UID') cur.uid = val;
    else if (name === 'STATUS') cur.status = val;
    else if (name === 'DTSTART') cur.start = when(val, params);
    else if (name === 'DTEND') cur.end = when(val, params);
    else if (name === 'RRULE') cur.rrule = val;
    else if (name === 'EXDATE') val.split(',').forEach(v => { const w = when(v, params); if (w) cur.ex.push(ymd(w.d)); });
  }
  const events = [];
  const push = (e, s, en) => events.push({ id: (e.uid || '') + '@' + ymd(s), title: e.title || '', location: e.location || '', description: e.description || '', allDay: e.start.allDay,
    start: e.start.allDay ? ymd(s) : s.toISOString(), end: en ? (e.start.allDay ? ymd(en) : en.toISOString()) : '' });
  for (const e of out) {
    if (/CANCELLED/i.test(e.status || '')) continue;
    const s0 = e.start.d, len = e.end ? e.end.d - s0 : 0;
    if (!e.rrule) { push(e, s0, e.end && e.end.d); continue; }
    const r = Object.fromEntries(e.rrule.split(';').map(kv => kv.split('=')));
    const step = r.FREQ === 'WEEKLY' ? 7 : r.FREQ === 'DAILY' ? 1 : 0;
    if (!step) { push(e, s0, e.end && e.end.d); continue; }
    const interval = +(r.INTERVAL || 1) * step, count = +(r.COUNT || 0);
    const until = r.UNTIL ? (when(r.UNTIL, '') || {}).d : addDays(s0, 730);
    for (let k = 0, d = new Date(s0); (!count || k < count) && d <= until && k < 400; k++, d = addDays(d, interval)) {
      if (!e.ex.includes(ymd(d))) push(e, d, len ? new Date(d.getTime() + len) : null);
    }
  }
  return events.sort((a, b) => String(a.start).localeCompare(String(b.start)));
}

// ------------------------------------------------------------------ events → sessions (Google or .ics)

/** Pick events to put in a course as sessions. [events]: Google or .ics events; [c]: the course (or null: choose one). */
async function eventsToSessionsDialog(events, c, onDone, source) {
  await coursesLoad();
  const all = [];
  courses.data.programmes.forEach(p => (p.semesters || []).forEach(s => (s.courses || []).forEach(x => all.push({ p, s, c: x }))));
  if (!all.length) { toast('Make a course first (Courses ▸ a programme ▸ a semester ▸ + Course)', 4000); return; }
  modal((box, close) => {
    box.classList.add('wide');
    const pick = h('select', null, all.map(a => h('option', { value: a.c.id, selected: c && a.c.id === c.id }, `${a.c.code ? a.c.code + ' — ' : ''}${a.c.title || ''} (${a.s.name})`)));
    const filter = h('input', { type: 'search', placeholder: 'Only events whose name contains…', style: { flex: 1 } });
    const course = () => all.find(a => a.c.id === pick.value).c;
    if (c && c.code && events.some(e => (e.title || '').toLowerCase().includes(c.code.toLowerCase()))) filter.value = c.code;
    const list = h('div', { class: 'calpick' });
    let rows = [];
    const draw = () => {
      const q = filter.value.trim().toLowerCase(), cc = course();
      const have = new Set((cc.sessions || []).map(x => x.date + ' ' + (x.time || '').slice(0, 5)));
      clear(list); rows = [];
      events.filter(e => !e.session && (!q || ((e.title || '') + ' ' + (e.location || '')).toLowerCase().includes(q))).forEach(e => {
        const x = eventSession(e, cc), dup = have.has(x.date + ' ' + x.time.slice(0, 5));
        const cb = h('input', { type: 'checkbox', checked: !dup });
        rows.push({ cb, e, x });
        list.append(h('label', { class: 'calpickrow' + (dup ? ' dup' : '') }, cb,
          h('span', { class: 'mono' }, courseDate(x.date)), h('span', { class: 'mono muted' }, x.time || 'all day'),
          h('span', null, e.title || '(no name)', e.location ? h('span', { class: 'muted' }, ' · ' + e.location) : null, dup ? h('span', { class: 'muted' }, ' · already in the outline') : null)));
      });
      if (!rows.length) list.append(h('p', { class: 'muted' }, 'No events match.'));
      count.textContent = `${rows.length} event${rows.length === 1 ? '' : 's'}`;
    };
    const count = h('span', { class: 'muted' });
    filter.addEventListener('input', draw); pick.addEventListener('change', draw);
    const allBox = h('button', { class: 'btn quiet', onclick: () => { const on = rows.some(r => !r.cb.checked); rows.forEach(r => { r.cb.checked = on; }); } }, 'Tick all / none');
    box.append(h('h2', null, 'Make sessions from ' + (source || 'calendar events')),
      h('div', { class: 'row', style: { gap: '8px', alignItems: 'center', flexWrap: 'wrap' } }, h('span', { class: 'muted' }, 'Into'), pick),
      h('div', { class: 'row', style: { gap: '8px', alignItems: 'center', margin: '8px 0' } }, filter, allBox, count), list,
      h('p', { class: 'muted', style: { fontSize: '12px' } }, 'The kind (lecture, seminar, exam…) is guessed from the name; change it afterwards with Edit.'),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: async () => {
        const chosen = rows.filter(r => r.cb.checked); if (!chosen.length) { toast('Tick some events'); return; }
        const cc = course();
        cc.sessions = (cc.sessions || []).concat(chosen.map(r => Object.assign(r.x, r.e.cal ? { gcal: { id: r.e.id, cal: r.e.cal, linked: true } } : {})));
        cc.sessions.sort((a, b) => ((a.date || '9999') + (a.time || '')).localeCompare((b.date || '9999') + (b.time || '')));
        close();
        if (await coursesSave()) { toast(`${chosen.length} session${chosen.length === 1 ? '' : 's'} put in ${cc.code || cc.title}`, 2500); if (onDone) onDone(cc); }
      } }, 'Put them in')));
    draw();
  });
}

/** Choose a .ics file and make sessions from it. */
function icsImport(c, onDone) {
  const inp = h('input', { type: 'file', accept: '.ics,text/calendar', style: { display: 'none' } });
  inp.addEventListener('change', async () => {
    const f = inp.files[0]; inp.remove(); if (!f) return;
    let evs; try { evs = icsParse(await f.text()); } catch (e) { fail(e); return; }
    if (!evs.length) { toast('No events in that file'); return; }
    eventsToSessionsDialog(evs, c, onDone, f.name);
  });
  document.body.append(inp); inp.click();
}

// ------------------------------------------------------------------ sessions → Google Calendar

/** Sends a course's dated sessions to Google Calendar (new ones made, ones sent before updated). */
async function sendSessionsToGoogle(c, onDone) {
  let st; try { st = await gcalStatus(true); } catch (e) { fail(e); return; }
  if (!st.connected) { confirmBox('Connect Google Calendar', 'Sign in with Google in Settings first.', 'Open Settings', () => { location.hash = '#/settings'; setTimeout(() => { const el = document.getElementById('set-gcal'); if (el) el.scrollIntoView(); }, 600); }); return; }
  if (!st.canWrite) { toast('The Google sign-in can only read. Sign in again in Settings and allow editing.', 5000); return; }
  const dated = (c.sessions || []).filter(x => /^\d{4}-\d{2}-\d{2}$/.test(x.date || ''));
  if (!dated.length) { toast('No sessions with dates'); return; }
  modal((box, close) => {
    const fresh = dated.filter(x => !x.gcal), sent = dated.filter(x => x.gcal && !x.gcal.linked), linked = dated.filter(x => x.gcal && x.gcal.linked);
    const upd = h('input', { type: 'checkbox', checked: true });
    const prog = h('div', { class: 'muted' });
    const go = h('button', { class: 'btn fill', onclick: async () => {
      go.disabled = true;
      const todo = fresh.concat(upd.checked ? sent : []);
      let n = 0, bad = 0;
      for (const x of todo) {
        prog.textContent = `Sending ${++n} of ${todo.length}…`;
        try {
          const ev = sessionEvent(c, x);
          const r = await api('gcal/event', { method: 'POST', json: Object.assign(ev, x.gcal ? { id: x.gcal.id, cal: x.gcal.cal } : {}), timeout: 60000 });
          x.gcal = { id: r.id, cal: r.cal, link: r.link };
        } catch (e) { bad++; prog.textContent = e.message; if (/sign-in|Connect|Turn on/.test(e.message)) break; }
      }
      await coursesSave();
      close(); toast(bad ? `${n - bad} sent, ${bad} failed` : `${n} session${n === 1 ? '' : 's'} in Google Calendar`, 4000);
      if (onDone) onDone();
    } }, 'Send');
    box.append(...[h('h2', null, 'Send the sessions to Google Calendar'),
      h('p', null, `${fresh.length} new session${fresh.length === 1 ? '' : 's'} will be made as events in “${st.writeToName || st.writeTo || 'your calendar'}”.`),
      sent.length ? h('label', { class: 'row', style: { gap: '6px' } }, upd, `Also update the ${sent.length} sent before (time, room, topic and readings)`) : null,
      linked.length ? h('p', { class: 'muted', style: { fontSize: '13px' } }, `${linked.length} came from Google Calendar and are left as they are.`) : null,
      h('p', { class: 'muted', style: { fontSize: '13px' } }, 'Each event gets the topic, the room and the readings. Sessions without a time become all-day events.'),
      prog, h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), go)].filter(Boolean));
    if (!fresh.length && !sent.length) go.disabled = true;
  });
}

/** When a session that was sent to Google is deleted: offer to delete its event too. */
async function sessionGoogleDelete(x) {
  if (!x.gcal || x.gcal.linked) return;
  try { await api('gcal/event?cal=' + encodeURIComponent(x.gcal.cal || '') + '&id=' + encodeURIComponent(x.gcal.id), { method: 'DELETE' }); toast('Removed from Google Calendar too'); }
  catch (e) { toast('Could not remove it from Google Calendar: ' + e.message, 4000); }
}

// ------------------------------------------------------------------ the course page: “In your calendar”

/** The course's events in Google Calendar (from the last fetch; Refresh fetches the semester). */
function courseCalendarSection(c, s, save) {
  const sec = h('section', { class: 'crssec' });
  const body = h('div', { class: 'calcourse' });
  const draw = async (fetch) => {
    let st; try { st = await gcalStatus(); } catch (_) { st = { connected: false }; }
    const tools = h('span', { class: 'row', style: { gap: '6px' } },
      h('button', { class: 'btn quiet', title: 'Fetch the lectures and your seminar group from the TP timetable (tp.educloud.no)', onclick: () => tpDialog(c, s, () => save()) }, 'From the timetable (TP)…'),
      h('button', { class: 'btn quiet', title: 'Read a calendar file (.ics) — e.g. the timetable export — into sessions', onclick: () => icsImport(c, () => save()) }, 'Import .ics…'),
      st.connected ? h('button', { class: 'btn quiet', title: 'Make Google Calendar events of the sessions with dates', onclick: () => sendSessionsToGoogle(c, () => save()) }, 'Send sessions to Google') : null,
      st.connected ? h('button', { class: 'btn quiet', onclick: () => draw(true) }, 'Refresh') : null);
    clear(sec).append(h('div', { class: 'row', style: { alignItems: 'center', gap: '8px' } }, h('h2', null, 'In your calendar'), h('span', { style: { flex: 1 } }), tools), body);
    clear(body);
    if (!st.connected) { body.append(h('p', { class: 'muted' }, 'Connect Google Calendar in ', h('a', { href: '#/settings' }, 'Settings'), ' to see this course’s events here and to send its sessions there. A timetable file (.ics) can be imported without it.')); return; }
    let r;
    try {
      if (fetch) {
        const from = s.start ? new Date(s.start + 'T00:00:00') : addDays(new Date(), -120), to = s.end ? addDays(new Date(s.end + 'T00:00:00'), 1) : addDays(new Date(), 180);
        body.append(h('p', { class: 'muted' }, 'Fetching from Google Calendar…'));
        r = await api(`gcal/events?from=${encodeURIComponent(isoLocal(from))}&to=${encodeURIComponent(isoLocal(to))}`, { timeout: 90000 });
      } else r = await api('gcal/events?cached=1');
    } catch (e) { clear(body).append(h('p', { class: 'muted' }, e.message)); return; }
    clear(body);
    const key = String(c.code || '').toLowerCase();
    const mine = (r.events || []).filter(e => e.session ? e.session.startsWith(c.id + '/') : key && ((e.title || '') + ' ' + (e.description || '')).toLowerCase().includes(key));
    const fetched = r.fetched ? new Date(r.fetched).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' }) : 'never';
    const outside = mine.filter(e => !e.session && !(c.sessions || []).some(x => x.gcal && x.gcal.id === e.id));
    body.append(h('div', { class: 'muted', style: { fontSize: '12px', marginBottom: '6px' } },
      key ? `Events mentioning ${c.code}, or sent from this course · fetched ${fetched}` : `Give the course a code to find its events · fetched ${fetched}`,
      outside.length ? h('button', { class: 'btn quiet', style: { marginLeft: '8px' }, onclick: () => eventsToSessionsDialog(outside, c, () => save(), 'Google Calendar') }, `Make sessions of ${outside.length}…`) : null));
    if (!mine.length) { body.append(h('p', { class: 'muted' }, r.fetched ? 'None found.' : 'Press Refresh to fetch this semester from Google Calendar.')); return; }
    mine.slice(0, 60).forEach(e => body.append(calEventRow(e, () => draw(false))));
  };
  draw(false);
  return sec;
}

function calEventRow(e, redraw) {
  const s = evDate(e.start, e.allDay), en = e.end ? evDate(e.end, e.allDay) : null;
  return h('div', { class: 'calrow', onclick: () => calEventDialog(e, redraw) },
    h('span', { class: 'calchip', style: { background: e.color || 'var(--soft)' } }),
    h('span', { class: 'mono' }, courseDate(ymd(s))), h('span', { class: 'mono muted' }, e.allDay ? 'all day' : hm(s) + (en ? '–' + hm(en) : '')),
    h('span', null, e.title || '(no name)', e.location ? h('span', { class: 'muted' }, ' · ' + e.location) : null, e.session ? h('span', { class: 'calmark', title: 'Sent from a course' }, 'from course') : null));
}

// ------------------------------------------------------------------ settings

function gcalSettingsSection() {
  const sec = h('section', { class: 'set-group', id: 'set-gcal' }, h('h2', null, 'Google Calendar'));
  const row = (label, help, ...control) => h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('div', null, label), help ? h('div', { class: 'muted set-help' }, help) : null), h('div', { class: 'set-control' }, ...control.filter(Boolean)));
  let waiting = null;
  const draw = async (st, list) => {
    gcal.status = st;
    if (waiting && st.connected) { clearInterval(waiting); waiting = null; }
    clear(sec).append(h('h2', null, 'Google Calendar'));
    if (!st.configured) {
      const id = h('input', { placeholder: 'Client ID (…apps.googleusercontent.com)', autocomplete: 'off', spellcheck: 'false' });
      const secret = h('input', { type: 'password', placeholder: 'Client secret', autocomplete: 'off', spellcheck: 'false' });
      sec.append(row('Your Google client', null,
        h('ol', { class: 'gcalsteps' },
          h('li', null, 'At ', h('a', { href: 'https://console.cloud.google.com/projectcreate', target: '_blank', rel: 'noopener' }, 'console.cloud.google.com'), ', make a project (any name).'),
          h('li', null, 'APIs & Services ▸ Library: find “Google Calendar API” and press Enable.'),
          h('li', null, 'Google Auth Platform (OAuth consent screen): Get started, choose External, give an app name and your address. Under Audience, press ', h('b', null, 'Publish app'), ' so it is “In production” — in “Testing”, Google signs you out every 7 days.'),
          h('li', null, 'Clients ▸ Create client ▸ type ', h('b', null, 'Desktop app'), '. Copy the client ID and the client secret into the boxes below.')),
        h('div', { class: 'col', style: { gap: '6px', display: 'flex', flexDirection: 'column' } }, id, secret,
          h('div', { class: 'row' }, h('button', { class: 'btn fill', onclick: async () => { try { draw(await api('gcal/client', { method: 'POST', json: { id: id.value, secret: secret.value } })); toast('Saved on the tablet'); } catch (e) { fail(e); } } }, 'Save'))),
        h('div', { class: 'muted set-help', style: { marginTop: '6px' } }, 'Kept encrypted on the tablet with your other keys, never in backups. Your own client means Google’s “unverified app” warning appears once: choose Advanced ▸ Go to (your app).')));
      return;
    }
    if (!st.connected) {
      const answer = h('input', { placeholder: 'http://127.0.0.1:…/?state=…&code=…', autocomplete: 'off', spellcheck: 'false', style: { flex: 1 } });
      let state = '';
      const finish = async () => { if (!answer.value.trim()) { toast('Paste the address first'); return; } try { draw(await api('gcal/finish', { method: 'POST', json: { answer: answer.value.trim(), state }, timeout: 60000 }), true); toast('Google Calendar connected'); } catch (e) { fail(e); } };
      answer.addEventListener('keydown', e => { if (e.key === 'Enter') finish(); });
      const paste = h('div', { class: 'gcalpaste', style: { display: 'none' } },
        h('p', { class: 'muted set-help' }, 'After you allow access, Google sends you to a page at http://127.0.0.1… On the tablet that page finishes the sign-in by itself. On a computer it does not load: copy its whole address from the address bar and paste it here.'),
        h('div', { class: 'row' }, answer, h('button', { class: 'btn fill', onclick: finish }, 'Finish')));
      sec.append(row('Sign in', `Client ••••${st.clientTail} saved. The app asks Google to see and edit your calendar events.`,
        h('div', { class: 'row' }, h('button', { class: 'btn fill', onclick: async () => {
          try {
            const r = await api('gcal/auth', { method: 'POST' }); state = r.state;
            window.open(r.url, '_blank', 'noopener'); paste.style.display = '';
            if (waiting) clearInterval(waiting);
            let n = 0; waiting = setInterval(async () => { if (++n > 100 || !document.body.contains(sec)) { clearInterval(waiting); waiting = null; return; } try { const s2 = await api('gcal/status'); if (s2.connected) { draw(s2, true); toast('Google Calendar connected'); } } catch (_) {} }, 3000);
          } catch (e) { fail(e); }
        } }, 'Sign in with Google'), h('button', { class: 'btn', onclick: () => confirmBox('Remove the client?', 'The client ID and secret are removed from the tablet.', 'Remove', async () => { try { draw(await api('gcal/client', { method: 'DELETE' })); } catch (e) { fail(e); } }) }, 'Remove the client')), paste));
      return;
    }
    const cals = h('div', { class: 'gcalcals' }, h('span', { class: 'muted' }, 'Fetching your calendars…'));
    sec.append(row('Account', null, h('div', { class: 'mono set-key-state ok' }, `Connected as ${st.email || '(unknown)'} · ${st.canWrite ? 'read and write' : 'read only'}`),
      h('div', { class: 'row', style: { marginTop: '6px' } }, h('a', { class: 'btn', href: '#/calendar' }, 'Open the calendar'),
        h('button', { class: 'btn', onclick: () => confirmBox('Disconnect Google Calendar?', 'The sign-in is withdrawn at Google and removed from the tablet. Events already in Google stay there; sessions keep their link.', 'Disconnect', async () => { try { draw(await api('gcal/auth', { method: 'DELETE' })); } catch (e) { fail(e); } }) }, 'Disconnect'))),
      row('Calendars', 'Ticked ones are shown on the calendar page and on the courses. New events from course sessions go to the one marked “write here”.', cals));
    // Listing the calendars asks Google, so it waits for the button (or follows straight after signing in).
    if (!list) { clear(cals).append(h('span', { class: 'muted' }, `${st.shown.length} shown${st.writeToName ? ' · writing to ' + st.writeToName : ''} `), h('button', { class: 'btn', onclick: () => draw(st, true) }, 'Choose calendars…')); return; }
    try {
      const r = await api('gcal/calendars', { timeout: 60000 });
      clear(cals);
      const shown = new Set(r.shown), boxes = [];
      let writeTo = r.writeTo;
      r.calendars.forEach(c => {
        const cb = h('input', { type: 'checkbox', checked: shown.has(c.id) });
        const w = h('input', { type: 'radio', name: 'gcalw', checked: writeTo === c.id, disabled: !c.canWrite });
        w.addEventListener('change', () => { writeTo = c.id; persist(); }); cb.addEventListener('change', persist);
        boxes.push({ c, cb });
        cals.append(h('div', { class: 'gcalcal' }, h('label', { class: 'row', style: { gap: '6px', flex: 1 } }, cb, h('span', { class: 'calchip', style: { background: c.color } }), c.name + (c.primary ? ' (main)' : '')),
          h('label', { class: 'row muted', style: { gap: '4px', fontSize: '12px' }, title: c.canWrite ? '' : 'You can only read this calendar' }, w, 'write here')));
      });
      async function persist() {
        const names = {}; r.calendars.forEach(c => { names[c.id] = { name: c.name, color: c.color, canWrite: c.canWrite }; });
        try { gcal.status = await api('gcal/calendars', { method: 'PUT', json: { shown: boxes.filter(b => b.cb.checked).map(b => b.c.id), writeTo, names } }); toast('Saved'); } catch (e) { fail(e); }
      }
      // Remember the names and colours (the event lists use them).
      { const names = {}; r.calendars.forEach(c => { names[c.id] = { name: c.name, color: c.color, canWrite: c.canWrite }; }); api('gcal/calendars', { method: 'PUT', json: { shown: r.shown, writeTo, names } }).catch(() => {}); }
    } catch (e) { clear(cals).append(h('span', { class: 'muted' }, e.message)); }
  };
  api('gcal/status').then(draw).catch(e => sec.append(h('div', { class: 'muted' }, e.message)));
  return sec;
}

// ------------------------------------------------------------------ the calendar page

const calState = { view: localGet('ml.calview', 'week'), at: new Date() };

async function showCalendar(parts) {
  const app = clear(document.getElementById('app'));
  app.appendChild(header());
  const main = h('main', { class: 'calpage' }); app.appendChild(main);
  document.title = 'Calendar · Marginalia Lectoris';
  let st;
  try { [st] = await Promise.all([gcalStatus(true), coursesLoad(true)]); } catch (e) { fail(e); return; }
  const range = () => {
    if (calState.view === 'month') { const first = new Date(calState.at.getFullYear(), calState.at.getMonth(), 1); const from = weekStart(first); return [from, addDays(from, 42)]; }
    const from = weekStart(calState.at); return [from, addDays(from, 7)];
  };
  const title = h('h1');
  const status = h('span', { class: 'muted mono', style: { fontSize: '12px' } });
  const grid = h('div');
  const move = n => { const d = new Date(calState.at); if (calState.view === 'month') d.setMonth(d.getMonth() + n, 1); else d.setDate(d.getDate() + 7 * n); calState.at = d; load(true); };
  const viewBtn = v => h('button', { class: 'btn' + (calState.view === v ? ' fill' : ''), onclick: () => { calState.view = v; localSet('ml.calview', v); showCalendar(parts); } }, v === 'week' ? 'Week' : 'Month');
  main.append(h('div', { class: 'caltool' }, title, h('span', { style: { flex: 1 } }), status,
    h('button', { class: 'btn', onclick: () => move(-1) }, '‹'), h('button', { class: 'btn', onclick: () => { calState.at = new Date(); load(true); } }, 'Today'), h('button', { class: 'btn', onclick: () => move(1) }, '›'),
    viewBtn('week'), viewBtn('month'),
    st.connected && st.canWrite ? h('button', { class: 'btn', onclick: () => calEventEdit(null, () => load(true)) }, '+ Event') : null,
    st.connected ? h('button', { class: 'btn', onclick: () => load(true) }, 'Refresh') : null), grid);
  if (!st.connected) main.insertBefore(h('div', { class: 'calnote muted' }, 'Showing course sessions only. Connect Google Calendar in ', h('a', { href: '#/settings' }, 'Settings'), ' to see your events here too.'), grid);

  let events = [];
  async function load(fetch) {
    const [from, to] = range();
    title.textContent = calState.view === 'month' ? calState.at.toLocaleDateString('en-GB', { month: 'long', year: 'numeric' })
      : `${from.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} – ${addDays(to, -1).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })} · week ${isoWeek(from)}`;
    if (st.connected && fetch) {
      status.textContent = 'Fetching…';
      try {
        const r = await api(`gcal/events?from=${encodeURIComponent(isoLocal(from))}&to=${encodeURIComponent(isoLocal(to))}`, { timeout: 90000 });
        events = r.events || [];
        status.textContent = 'fetched ' + new Date(r.fetched).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }) + (r.errors && r.errors.length ? ' · ' + r.errors.join('; ') : '');
      } catch (e) { status.textContent = e.message; events = []; }
    }
    draw(from, to);
  }
  function items(from, to) {
    // Course sessions (sessions sent to Google show once: as the session).
    const out = [], sentIds = new Set();
    courses.data.programmes.forEach(p => (p.semesters || []).forEach(s => (s.courses || []).forEach(c => (c.sessions || []).forEach(x => {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(x.date || '')) return;
      if (x.gcal) sentIds.add(x.gcal.id);
      const m = sessionMinutes(x.time), d = new Date(x.date + 'T00:00:00');
      if (d < from || d >= to) return;
      const s0 = new Date(d); if (m) s0.setMinutes(m[0]);
      const e0 = m ? new Date(d.getTime() + m[1] * 60000) : null;
      out.push({ kind: 'session', c, x, day: x.date, start: s0, end: e0, allDay: !m, title: `${c.code || c.title} · ${x.kind || 'Lecture'}${x.topic ? ': ' + x.topic : ''}`, sub: x.room || '' });
    }))));
    events.forEach(e => {
      if (sentIds.has(e.id) || (e.session && courseFind(e.session.split('/')[0]))) return;
      const s = evDate(e.start, e.allDay), en = e.end ? evDate(e.end, e.allDay) : null;
      // All-day events spanning several days appear on each day.
      const days = e.allDay && en ? Math.max(1, Math.round((dayStart(en) - dayStart(s)) / 864e5)) : 1;
      for (let k = 0; k < days; k++) { const d = addDays(dayStart(s), k); if (d >= from && d < to) out.push({ kind: 'event', e, day: ymd(d), start: s, end: en, allDay: e.allDay, title: e.title || '(no name)', sub: e.location || '' }); }
    });
    return out.sort((a, b) => (b.allDay - a.allDay) || (a.start - b.start));
  }
  function chip(it) {
    const open = () => it.kind === 'session' ? (location.hash = `#/course/${it.c.id}`) : calEventDialog(it.e, () => load(true));
    return h('div', { class: 'calitem ' + it.kind + (it.allDay ? ' allday' : ''), style: it.kind === 'event' ? { borderLeftColor: it.e.color || 'var(--soft)' } : null, onclick: open, title: [it.title, it.sub].filter(Boolean).join(' · ') },
      it.allDay ? null : h('span', { class: 'mono caltime' }, hm(it.start) + (it.end && calState.view === 'week' ? '–' + hm(it.end) : '')),
      h('span', { class: 'caltitle' }, it.title), it.sub && calState.view === 'week' ? h('span', { class: 'muted calsub' }, it.sub) : null,
      it.kind === 'session' && calState.view === 'week' && (it.x.readings || []).length ? h('span', { class: 'muted calsub' }, `${(it.x.readings || []).filter(r => r.status === 'done').length}/${it.x.readings.length} readings done`) : null);
  }
  function draw(from, to) {
    const all = items(from, to), today = ymd(new Date());
    const byDay = {}; all.forEach(it => { (byDay[it.day] = byDay[it.day] || []).push(it); });
    clear(grid);
    if (calState.view === 'week') {
      grid.className = 'calweek';
      for (let k = 0; k < 7; k++) {
        const d = addDays(from, k), key = ymd(d);
        grid.append(h('div', { class: 'calday' + (key === today ? ' today' : '') },
          h('div', { class: 'calhead' }, h('b', null, d.toLocaleDateString('en-GB', { weekday: 'short' })), ' ', d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })),
          ...(byDay[key] || []).map(chip)));
      }
    } else {
      grid.className = 'calmonth';
      ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].forEach(n => grid.append(h('div', { class: 'calwd muted' }, n)));
      for (let k = 0; k < 42; k++) {
        const d = addDays(from, k), key = ymd(d), list = byDay[key] || [];
        grid.append(h('div', { class: 'calcell' + (key === today ? ' today' : '') + (d.getMonth() !== calState.at.getMonth() ? ' other' : ''), ondblclick: () => { calState.view = 'week'; calState.at = d; localSet('ml.calview', 'week'); showCalendar(parts); } },
          h('div', { class: 'calnum' }, String(d.getDate())), ...list.slice(0, 4).map(chip), list.length > 4 ? h('div', { class: 'muted calmore', onclick: () => { calState.view = 'week'; calState.at = d; showCalendar(parts); } }, `+${list.length - 4} more`) : null));
      }
    }
  }
  load(true);
}

const isoWeek = d => { const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate())); const n = (t.getUTCDay() + 6) % 7; t.setUTCDate(t.getUTCDate() - n + 3); const y = new Date(Date.UTC(t.getUTCFullYear(), 0, 4)); return 1 + Math.round(((t - y) / 864e5 - 3 + ((y.getUTCDay() + 6) % 7)) / 7); };

/** One Google event: details, open in Google, make a session of it, edit or delete it. */
function calEventDialog(e, redraw) {
  const s = evDate(e.start, e.allDay), en = e.end ? evDate(e.end, e.allDay) : null;
  const canWrite = gcal.status && gcal.status.canWrite && e.canWrite !== false;
  modal((box, close) => {
    box.append(...[h('h2', null, e.title || '(no name)'),
      h('p', { class: 'mono' }, s.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }) + (e.allDay ? ' · all day' : ' · ' + hm(s) + (en ? '–' + hm(en) : ''))),
      e.location ? h('p', null, e.location) : null, e.calName ? h('p', { class: 'muted' }, h('span', { class: 'calchip', style: { background: e.color } }), ' ', e.calName) : null,
      e.description ? h('div', { class: 'caldesc' }, e.description.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')) : null,
      h('div', { class: 'foot' },
        e.link ? h('a', { class: 'btn quiet', href: e.link, target: '_blank', rel: 'noopener' }, 'Open in Google Calendar') : null,
        canWrite ? h('button', { class: 'btn quiet', onclick: () => confirmBox('Delete the event?', `“${e.title}” is deleted in Google Calendar.`, 'Delete', async () => { try { await api('gcal/event?cal=' + encodeURIComponent(e.cal) + '&id=' + encodeURIComponent(e.id), { method: 'DELETE' }); close(); redraw(); } catch (er) { fail(er); } }) }, 'Delete') : null,
        canWrite ? h('button', { class: 'btn quiet', onclick: () => { close(); calEventEdit(e, redraw); } }, 'Edit') : null,
        h('span', { style: { flex: 1 } }),
        h('button', { class: 'btn fill', onclick: () => { close(); eventsToSessionsDialog([e], null, redraw, 'this event'); } }, 'Make it a course session…'))].filter(Boolean));
  });
}

/** Make or change a Google event. */
function calEventEdit(e, redraw) {
  const s = e ? evDate(e.start, e.allDay) : (() => { const d = new Date(); d.setMinutes(0, 0, 0); d.setHours(d.getHours() + 1); return d; })();
  const en = e && e.end ? evDate(e.end, e.allDay) : new Date(s.getTime() + 3600000);
  courseForm(e ? 'Edit the event' : 'New event in Google Calendar', [
    { key: 'title', label: 'Name', value: e ? e.title : '', wide: true },
    { key: 'date', label: 'Date', type: 'date', value: ymd(s) },
    { key: 'from', label: 'From', type: 'time', value: e && e.allDay ? '' : hm(s) },
    { key: 'to', label: 'To', type: 'time', value: e && e.allDay ? '' : hm(en) },
    { key: 'location', label: 'Place', value: e ? e.location : '' },
    { key: 'description', label: 'Notes', type: 'textarea', rows: 3, value: e ? (e.description || '').replace(/<[^>]+>/g, '') : '', wide: true },
  ], v => {
    if (!v.date) { toast('Give a date'); return false; }
    const allDay = !v.from;
    const at = t => { const d = new Date(v.date + 'T00:00:00'); const [H, M] = t.split(':'); d.setHours(+H, +M); return isoLocal(d); };
    const body = { id: e ? e.id : '', cal: e ? e.cal : '', title: v.title, location: v.location, description: v.description, allDay, timeZone: localZone(),
      start: allDay ? v.date : at(v.from), end: allDay ? ymd(addDays(new Date(v.date + 'T12:00:00'), 1)) : at(v.to && v.to > v.from ? v.to : v.from) };
    api('gcal/event', { method: 'POST', json: body, timeout: 60000 }).then(() => { toast(e ? 'Event changed' : 'Event made'); redraw(); }).catch(fail);
  });
}

// ------------------------------------------------------------------ the TP timetable (tp.educloud.no)

/** Which seminar group an event belongs to ("Gruppe 2", "Group 3", "Gr. 1A", "Seminargruppe 4"), or ''. */
function tpGroup(e) {
  const t = [e.title, e.description, e.location].join(' \n ');
  const m = t.match(/\b(?:seminar\s*)?(?:grupp?e|group|gr\.)\s*[:#-]?\s*([0-9]{1,3}[a-z]?)\b/i) || t.match(/\bseminar\s+([0-9]{1,3}[a-z]?)\b/i);
  return m ? m[1].toUpperCase() : '';
}
const tpIsSeminar = e => /seminar|gruppe|group|kollokvie|øving|exercise|workshop/i.test((e.title || '') + ' ' + (e.description || ''));
const WD = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/** Fetch the course's TP timetable: all lectures, and the seminars of the group you choose (remembered on the course). */
async function tpDialog(c, sem, onDone) {
  modal((box, close) => {
    box.classList.add('wide');
    const link = h('input', { type: 'url', placeholder: 'https://tp.educloud.no/uib/app/schedule?…&course=' + (c.code || 'CODE') + '%C2%A41', value: c.tpUrl || '', style: { flex: 1 } });
    const semSel = h('select', { style: { width: 'auto' } }, h('option', { value: '' }, '— semester —'));
    const out = h('div');
    const msg = h('p', { class: 'muted', style: { fontSize: '13px' } }, 'Paste the address of the course in TP (as in the browser), or just the course code. TP exports the current and the next semester.');
    const guessSem = () => { // from the app's semester dates: autumn → yyh, spring → yyv
      const d = sem && (sem.start || sem.end); if (!d) return '';
      const y = d.slice(2, 4), m = +d.slice(5, 7); return y + (m >= 7 ? 'h' : 'v');
    };
    const loadSems = async () => {
      if (!link.value.trim()) return;
      try {
        const r = await api('courses/tp-semesters', { method: 'POST', json: { url: link.value.trim() }, timeout: 30000 });
        const now = (r.semesters.find(x => x.standard) || {}).id || '';
        const want = c.tpSem || guessSem() || (r.linkSem && now && r.linkSem < now ? now : r.linkSem) || now;
        const list = r.semesters.length ? r.semesters : ['26h', '27v', '27h'].map(id => ({ id, name: '' }));
        clear(semSel).append(h('option', { value: '' }, '— semester —'), ...list.map(x => h('option', { value: x.id, selected: x.id === want || (!want && x.standard) }, x.id + (x.name ? ' · ' + x.name : '') + (x.standard ? ' (now)' : ''))));
        if (want && !list.some(x => x.id === want)) semSel.append(h('option', { value: want, selected: true }, want + ' (from the link)'));
        msg.textContent = `${r.code} (teaching term ${r.termin}). Choose the semester and press Fetch.`;
      } catch (e) { msg.textContent = e.message; }
    };
    link.addEventListener('change', loadSems);
    const go = h('button', { class: 'btn fill', onclick: async () => {
      if (!link.value.trim()) { toast('Paste the TP address first'); return; }
      if (!semSel.value) { toast('Choose the semester'); return; }
      go.disabled = true; clear(out).append(h('p', { class: 'muted' }, 'Fetching from TP…'));
      try {
        const r = await api('courses/tp-fetch', { method: 'POST', json: { url: link.value.trim(), sem: semSel.value }, timeout: 60000 });
        c.tpUrl = link.value.trim(); c.tpSem = r.sem;
        showChoice(icsParse(r.ics), r);
      } catch (e) { clear(out).append(h('p', { class: 'muted' }, e.message)); }
      go.disabled = false;
    } }, 'Fetch');
    // Older semesters: TP's own CSV download (Last ned ▸ CSV on the TP page), read here.
    const csvIn = h('input', { type: 'file', accept: '.csv,text/csv', style: { display: 'none' } });
    csvIn.addEventListener('change', async () => {
      const f = csvIn.files[0]; csvIn.value = ''; if (!f) return;
      try {
        const buf = await f.arrayBuffer();
        let text; try { text = new TextDecoder('utf-8', { fatal: true }).decode(buf); } catch (_) { text = new TextDecoder('windows-1252').decode(buf); }
        const r = tpCsvParse(text);
        if (!r.events.length) { toast('No sessions in that file'); return; }
        c.tpSem = r.sem || c.tpSem;
        showChoice(r.events, { code: r.code || c.code || '', sem: r.sem || '' });
      } catch (e) { fail(e); }
    });
    const csvBtn = h('button', { class: 'btn quiet', title: 'For an older semester: on the TP page, download the timetable as CSV and choose the file here', onclick: () => csvIn.click() }, 'CSV file from TP…');
    box.append(csvIn, h('h2', null, 'From the timetable (TP)'), h('div', { class: 'row', style: { gap: '8px', alignItems: 'center' } }, link, semSel, go, csvBtn), msg, out,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Close')));
    if (link.value) loadSems();

    function showChoice(evs, r) {
      clear(out);
      // Seminars with a group number group by it; seminars without one by their weekday, time and room.
      const groups = new Map(), common = [];
      evs.forEach(e => {
        const g = e.group ? '' : tpGroup(e);
        let key = e.group || (g ? 'Group ' + g : '');
        if (!key && tpIsSeminar(e) && !e.allDay) { const d = new Date(e.start); key = `${WD[d.getDay()]} ${hm(d)}${e.location ? ' · ' + e.location : ''}`; }
        if (key) { if (!groups.has(key)) groups.set(key, []); groups.get(key).push(e); } else common.push(e);
      });
      const keys = [...groups.keys()].sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
      const summary = list => { const by = {}; list.forEach(e => { const d = new Date(e.start); const k = e.allDay ? 'all day' : `${WD[d.getDay()]} ${hm(d)}`; by[k] = (by[k] || 0) + 1; });
        const locs = [...new Set(list.map(e => e.location).filter(Boolean))].slice(0, 2).join(', ');
        return Object.entries(by).sort((a, b) => b[1] - a[1]).slice(0, 2).map(([k, n]) => `${k} ×${n}`).join(', ') + (locs ? ' · ' + locs : ''); };
      const lect = h('input', { type: 'checkbox', checked: true });
      out.append(h('label', { class: 'row', style: { gap: '8px', margin: '8px 0' } }, lect, h('b', null, `Lectures and everything for the whole course (${common.length})`), h('span', { class: 'muted' }, summary(common))));
      let chosen = c.tpGroup && groups.has(c.tpGroup) ? c.tpGroup : '';
      if (keys.length) {
        out.append(h('p', { style: { margin: '10px 0 4px' } }, h('b', null, 'Which seminar group are you in?')));
        const opt = (key, label, sub) => { const r0 = h('input', { type: 'radio', name: 'tpg', checked: chosen === key }); r0.addEventListener('change', () => { chosen = key; }); return h('label', { class: 'calpickrow', style: { gridTemplateColumns: '18px 140px 1fr' } }, r0, h('b', null, label), h('span', { class: 'muted' }, sub)); };
        const listBox = h('div', { class: 'calpick' }, ...keys.map(k => opt(k, k, `${groups.get(k).length} sessions · ${summary(groups.get(k))}`)), opt('', 'None', 'Add no seminars'));
        out.append(listBox);
      } else out.append(h('p', { class: 'muted' }, 'TP lists no seminar groups for this course.'));
      out.append(h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: async () => {
        const pick = (lect.checked ? common : []).concat(chosen ? groups.get(chosen) : []);
        if (!pick.length) { toast('Nothing chosen'); return; }
        c.tpGroup = chosen;
        await coursesSave();
        close();
        eventsToSessionsDialog(pick, c, onDone, `TP ${r.code} ${r.sem}` + (chosen ? ' · ' + chosen : ''));
      } }, 'Next: check the sessions')));
    }
  });
}

/** TP's CSV download (semicolon separated; any semester): rows → events, seminar groups from the party columns. */
function tpCsvParse(text) {
  const rows = []; let row = [], cell = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (q) { if (ch === '"') { if (text[i + 1] === '"') { cell += '"'; i++; } else q = false; } else cell += ch; }
    else if (ch === '"') q = true;
    else if (ch === ';') { row.push(cell); cell = ''; }
    else if (ch === '\n' || ch === '\r') { if (ch === '\r' && text[i + 1] === '\n') i++; row.push(cell); rows.push(row); row = []; cell = ''; }
    else cell += ch;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  const head = (rows.shift() || []).map(x => x.trim().replace(/^﻿/, ''));
  const col = n => head.indexOf(n);
  if (col('dtstart') < 0) throw new Error('This is not a timetable CSV from TP (no dtstart column).');
  const get = (r, n) => { const i = col(n); return i < 0 ? '' : String(r[i] || '').trim(); };
  const when = v => { const m = v.match(/(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})/); return m ? new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]) : null; };
  let code = '', sem = '';
  const events = [];
  rows.forEach(r => {
    if (r.length < 5) return;
    const s = when(get(r, 'dtstart')); if (!s) return;
    const e = when(get(r, 'dtend'));
    code = code || get(r, 'courseid'); sem = sem || get(r, 'semesterid');
    const title = (get(r, 'summary_en') || get(r, 'summary') || get(r, 'teaching-title') || get(r, 'teaching-method-name')).replace(/\s+/g, ' ').trim();
    const method = get(r, 'teaching-method-name') || get(r, 'teaching-method');
    const isGroup = /^j/i.test(get(r, 'isparty')) && get(r, 'party');
    events.push({ id: (get(r, 'eventid') || get(r, 'id') + '+' + get(r, 'weeknr')), title: `${get(r, 'courseid')} ${title}`.trim(), location: get(r, 'room'),
      description: [method && method !== title ? method : '', get(r, 'staffnames') || get(r, 'staffs')].filter(Boolean).join(' · '),
      allDay: false, start: s.toISOString(), end: e ? e.toISOString() : '', group: isGroup ? (title || 'Group ' + get(r, 'party')) : '' });
  });
  events.sort((a, b) => a.start.localeCompare(b.start));
  return { events, code, sem };
}
