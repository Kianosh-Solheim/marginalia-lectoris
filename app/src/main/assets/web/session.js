/* Marginalia Lectoris — a course session's own page (#/course/<course>/session/<session>):
 * the lecture slides, a note for each slide, pen marks on the slides, a recording made with the
 * tablet's microphone (slide changes are marked in it) and its transcript, slide by slide.
 *
 * The slides are a presentation from Presentations or a PDF from the library (the session keeps only a
 * reference, in courses.json). Notes, ink and recordings are files in the session's folder on the tablet
 * (<library>/Courses/<code>/<session>/), so they reach Obsidian through Syncthing.
 */
'use strict';

const sessPath = (cid, sid, tail) => `courses/session/${encodeURIComponent(cid)}/${encodeURIComponent(sid)}${tail ? '/' + tail : ''}`;
const sessDeckCache = new Map();

/** The slides of a session as a deck (see present.js): a presentation, or a PDF from the library. */
async function sessionDeck(sl) {
  if (sl.kind === 'deck') return openDeck(sl.id);
  const key = 'doc:' + sl.id;
  if (sessDeckCache.has(key)) return sessDeckCache.get(key);
  const p = (async () => {
    const r = await fetch(`/api/doc/${sl.id}/file`, { credentials: 'same-origin' });
    if (!r.ok) throw new Error('Could not load the PDF from the library.');
    const deck = await pdfDeck(await r.arrayBuffer());
    deck.meta = { title: sl.title || 'Slides' };
    deck.slideInfo = () => ({});
    deck.hidden = () => false;
    return deck;
  })();
  sessDeckCache.set(key, p);
  p.catch(() => sessDeckCache.delete(key));
  return p;
}

/** Which slide was showing at [ms] in a recording, from its timeline's start and slide events. */
function slideAt(events, ms) {
  let s = 0;
  for (const e of events) { if (e.t > ms) break; if (e.type === 'start' || e.type === 'slide') s = e.slide || s; }
  return s;
}

async function showSession(courseId, sessionId, params) {
  const eink = params.get('eink') === '1', tablet = params.get('tablet') === '1';
  const app = clear(document.getElementById('app'));
  state.tab = 'courses';
  if (!tablet) app.appendChild(header());
  const main = h('main', { class: 'crspage sesspage' + (eink ? ' eink' : '') + (tablet ? ' tablet' : '') });
  app.appendChild(main);
  main.append(h('div', { class: 'loading', style: { position: 'static', height: '160px' } }, h('div', { class: 'spin' })));
  let docs = [], data;
  try { await coursesLoad(true); docs = await loadLibrary(); data = await api(sessPath(courseId, sessionId)); }
  catch (e) { clear(main).append(h('div', { class: 'empty' }, h('h2', null, 'Could not open the session'), h('p', { class: 'muted' }, e.message || String(e)), h('a', { href: `#/course/${encodeURIComponent(courseId)}` }, 'Back to the course'))); return; }
  const f = courseFind(courseId);
  const x = f && (f.c.sessions || []).find(y => y.id === sessionId);
  if (!x) { clear(main).append(h('div', { class: 'empty' }, h('h2', null, 'Session not found'), h('a', { href: '#/courses' }, 'All courses'))); return; }
  const { p, s, c } = f;
  const byId = new Map(docs.map(d => [d.id, d]));
  const P = tail => sessPath(courseId, sessionId, tail);
  const hashBase = `#/course/${encodeURIComponent(courseId)}/session/${encodeURIComponent(sessionId)}`;
  clear(main);

  // ---------------------------------------------------------------- the head
  if (!tablet) main.append(h('div', { class: 'crscrumbs' }, h('a', { href: '#/courses' }, 'Courses'), ' › ', h('a', { href: `#/courses/${p.id}` }, p.name), ' › ',
    h('a', { href: `#/courses/${p.id}/${s.id}` }, s.name), ' › ', h('a', { href: `#/course/${encodeURIComponent(c.id)}` }, c.code || c.title)));
  const readings = (x.readings || []).map(r => {
    const d = byId.get(r.docId);
    const page = +(String(r.pages || '').match(/\d+/) || [])[0] || 0;
    const label = (d ? d.title : (r.text || 'a reading')).slice(0, 70) + (r.pages ? ' · pp. ' + r.pages : '');
    return d ? h('a', { class: 'crschip', href: `#/read/${d.id}?${page ? `pp=${page}&page=${page}&pos=${page}&` : ''}course=${encodeURIComponent(c.id)}&reading=${encodeURIComponent(r.id)}`, title: 'Read it' }, label)
      : h('span', { class: 'crschip muted' }, label);
  });
  main.append(h('div', { class: 'crshead sesshead' },
    h('div', null,
      h('div', { class: 'crskind' }, [c.code, x.kind || 'Lecture', courseDate(x.date) || (x.week ? 'Week ' + x.week : ''), x.time, x.room, x.lecturer].filter(Boolean).join(' · ')),
      h('h1', null, x.topic || x.kind || 'Session'),
      readings.length ? h('div', { class: 'crschips' }, readings) : null),
    h('span', { style: { flex: 1 } }),
    x.link && /^https?:\/\//.test(x.link) ? h('a', { class: 'btn quiet', href: x.link, target: '_blank', rel: 'noopener', title: x.link }, 'Session page ↗') : null,
    h('button', { class: 'btn quiet', title: 'Edit the session (date, topic, room…)', onclick: () => sessionDialog(c, x, () => coursesSave().then(ok => ok && route())) }, 'Edit'),
    tablet ? null : h('a', { class: 'btn quiet', href: `#/course/${encodeURIComponent(c.id)}` }, '← Course')));
  if (x.note) main.append(h('p', { class: 'crsdesc' }, x.note));

  // ---------------------------------------------------------------- state
  const notes = Object.assign({}, data.notes || {});
  const ink = Object.assign({}, data.ink || {});
  let deck = null, cur = 0, count = 0;
  let recState = data;            // the last answer from the tablet (recording, recordings, engines)
  let lastPosted = 0;

  const body = h('div', { class: 'sessbody' });
  const left = h('div', { class: 'sessleft' });
  const right = h('div', { class: 'sessright' });
  body.append(left, right);
  main.append(body);

  // ---------------------------------------------------------------- the slides
  const stage = h('div', { class: 'sessslide' });
  const stageWrap = h('div', { class: 'sessstage' }, stage);
  const inkL = inkLayer((strokes, done) => {
    if (!count) return;
    ink[cur + 1] = strokes;
    if (done) { clearTimeout(inkTimer); const slide = cur + 1; inkTimer = setTimeout(() => api(P('ink'), { method: 'PUT', json: { slide, strokes: ink[slide] || [] } }).then(markThumbs).catch(fail), 500); }
  });
  let inkTimer = 0;
  if (eink) inkL.color('#111111');
  stageWrap.append(inkL.el);
  const counter = h('span', { class: 'mono sesscount' });
  const tools = h('span', { class: 'row sesstools' });
  let tool = 'off';
  const drawTools = () => {
    const tb = (t, label, title) => h('button', { class: 'btn' + (tool === t ? ' fill' : ''), title, onclick: () => { tool = tool === t ? 'off' : t; inkL.tool(tool); drawTools(); } }, label);
    clear(tools).append(tb('pen', '✎ Pen', 'Write or draw on the slide'), tb('hl', '▮ Marker', 'Highlight on the slide'), tb('erase', '⌫ Eraser', 'Erase strokes'),
      h('button', { class: 'btn quiet', title: 'Clear this slide’s pen marks', onclick: () => inkL.clear() }, 'Clear'),
      ...(eink ? [] : ['#e11d48', '#2563eb', '#16a34a', '#111111'].map(cv => h('button', { class: 'swatch', style: { background: cv }, title: 'Ink colour', onclick: () => inkL.color(cv) }))));
  };
  drawTools();
  const thumbs = h('div', { class: 'sessthumbs' });
  const slideBar = h('div', { class: 'sessnav' },
    h('button', { class: 'btn', title: 'Previous slide (←)', onclick: () => go(cur - 1) }, '‹'),
    counter,
    h('button', { class: 'btn fill', title: 'Next slide (→)', onclick: () => go(cur + 1) }, '›'),
    h('span', { style: { flex: 1 } }), tools,
    menuButton('Slides ▾', [
      ['Change the slides…', 'another presentation or PDF', () => pickSlides(c, x, () => route())],
      x.slides && x.slides.kind === 'deck' ? ['Open in presentation mode', 'present them from here', () => { location.hash = `#/presenter/${encodeURIComponent(x.slides.id)}`; }] : null,
      ['Remove the slides', 'notes and recordings stay', () => confirmBox('Remove the slides from the session?', 'The presentation or PDF itself stays, and so do your notes, pen marks and recordings.', 'Remove', () => { delete x.slides; coursesSave().then(ok => ok && route()); })]
    ].filter(Boolean), 'btn quiet'));

  async function drawSlides() {
    if (!x.slides) {
      left.append(h('div', { class: 'sessnoslides' },
        h('h2', null, 'No slides yet'),
        h('p', { class: 'muted' }, 'Add the lecture slides: a presentation (PowerPoint, LibreOffice or PDF), or a PDF already in your library. Then you can write a note for each slide, draw on them, and the recording follows along.'),
        h('button', { class: 'btn fill', onclick: () => pickSlides(c, x, () => route()) }, 'Add the slides…')));
      return;
    }
    left.append(h('div', { class: 'sesstitle muted' }, (x.slides.kind === 'deck' ? 'Presentation: ' : 'PDF: ') + (x.slides.title || '')), stageWrap, slideBar, thumbs);
    stage.append(h('div', { class: 'loading', style: { position: 'static', height: '100%' } }, h('div', { class: 'spin' })));
    try { deck = await sessionDeck(x.slides); }
    catch (e) { clear(stage).append(h('div', { class: 'empty' }, h('h2', null, 'Could not open the slides'), h('p', { class: 'muted' }, e.message || String(e)), h('button', { class: 'btn', onclick: () => pickSlides(c, x, () => route()) }, 'Choose others…'))); return; }
    count = deck.count;
    fitSlide(stage, deck); fitSlide(stageWrap, deck);
    // The thumbnails: drawn as they scroll into view.
    for (let i = 0; i < count; i++) {
      const box = h('div', { class: 'sessthumbimg' }); fitSlide(box, deck);
      const b = h('button', { class: 'sessthumb', title: 'Slide ' + (i + 1), onclick: () => go(i) }, box, h('span', { class: 'n mono' }, String(i + 1)), h('span', { class: 'marks mono' }));
      thumbs.append(b);
      const io = new IntersectionObserver(async ents => {
        if (!ents.some(e => e.isIntersecting)) return;
        io.disconnect();
        try { await deck.render(i, box); } catch (_) { box.textContent = String(i + 1); }
      }, { root: thumbs });
      io.observe(box);
    }
    markThumbs();
    const start = Math.max(1, Math.min(count, +params.get('slide') || 1)) - 1;
    go(start, true);
  }

  function markThumbs() {
    [...thumbs.children].forEach((b, i) => {
      b.classList.toggle('on', i === cur);
      const m = [];
      if ((notes[i + 1] || '').trim()) m.push('✎');
      if ((ink[i + 1] || []).length) m.push('~');
      b.querySelector('.marks').textContent = m.join(' ');
    });
  }

  let renderSeq = 0;
  async function go(i, first) {
    if (!count) return;
    i = Math.max(0, Math.min(count - 1, i));
    if (i === cur && !first) return;
    flushNote();
    cur = i;
    counter.textContent = `${cur + 1} / ${count}`;
    history.replaceState(null, '', `${hashBase}?${new URLSearchParams(Object.assign(Object.fromEntries(params), { slide: cur + 1 })).toString()}`);
    inkL.set(ink[cur + 1] || []);
    markThumbs();
    const t = thumbs.children[cur]; if (t) t.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    drawNote();
    followTranscript();
    postSlide();
    const seq = ++renderSeq;
    try { await deck.render(cur, stage); } catch (e) { if (seq === renderSeq) clear(stage).append(h('div', { class: 'muted', style: { padding: '20px' } }, 'Could not draw this slide.')); }
  }

  function postSlide() {
    if (!count || lastPosted === cur + 1) return;
    lastPosted = cur + 1;
    api(P('slide'), { method: 'POST', json: { slide: cur + 1 } }).catch(() => { lastPosted = 0; });
  }

  // ---------------------------------------------------------------- the panel: notes, transcript, recording
  let tab = params.get('tab') || 'notes';
  const tabsEl = h('div', { class: 'sesstabs' });
  const panes = { notes: h('div', { class: 'sesspane' }), transcript: h('div', { class: 'sesspane' }), recording: h('div', { class: 'sesspane' }) };
  const drawTabs = () => {
    clear(tabsEl).append(...[['notes', 'Notes'], ['transcript', 'Transcript'], ['recording', recState.recording ? '● Recording' : 'Recording']].map(([k, l]) =>
      h('button', { class: 'chip' + (tab === k ? ' on' : '') + (k === 'recording' && recState.recording ? ' rec' : ''), onclick: () => { tab = k; drawTabs(); } }, l)));
    Object.entries(panes).forEach(([k, el]) => { el.hidden = k !== tab; });
    if (tab === 'transcript') loadTranscript();
  };
  right.append(tabsEl, ...Object.values(panes));

  // Notes
  const noteHead = h('div', { class: 'label' });
  const speaker = h('details', { class: 'sessspeaker', hidden: true }, h('summary', null, 'Speaker notes in the slides'), h('div'));
  const mine = h('textarea', { class: 'sessnote', rows: 10, placeholder: 'Your note on this slide… (saved as its own note in the session’s folder, ready for Obsidian)' });
  const saved = h('span', { class: 'muted mono', style: { fontSize: '11px' } });
  const allNotes = h('div', { class: 'sessallnotes' });
  let noteTimer = 0, noteDirty = false;
  const saveNote = () => {
    clearTimeout(noteTimer); if (!noteDirty) return; noteDirty = false;
    const slide = cur + 1, text = notes[slide] || '';
    saved.textContent = 'Saving…';
    api(P('notes'), { method: 'PUT', json: { slide, text } }).then(() => { saved.textContent = 'Saved'; markThumbs(); drawAllNotes(); }).catch(e => { saved.textContent = ''; fail(e); });
  };
  const flushNote = () => { if (noteDirty) saveNote(); };
  mine.addEventListener('input', () => { if (!count) return; notes[cur + 1] = mine.value; noteDirty = true; saved.textContent = '…'; clearTimeout(noteTimer); noteTimer = setTimeout(saveNote, 900); });
  mine.addEventListener('blur', flushNote);
  function drawNote() {
    noteHead.textContent = count ? `Your note on slide ${cur + 1}` : 'Notes';
    mine.disabled = !count;
    mine.value = count ? (notes[cur + 1] || '') : '';
    mine.placeholder = count ? 'Your note on this slide… (saved as its own note in the session’s folder, ready for Obsidian)' : 'Add the slides to write a note for each slide.';
    saved.textContent = '';
    const sp = deck && deck.slideInfo ? (deck.slideInfo(cur).notes || '').trim() : '';
    speaker.hidden = !sp;
    clear(speaker.lastChild).append(...sp.split('\n').map(l => h('p', null, l)));
  }
  function drawAllNotes() {
    const list = Object.keys(notes).map(Number).filter(n => (notes[n] || '').trim()).sort((a, b) => a - b);
    clear(allNotes);
    if (!list.length) return;
    allNotes.append(h('div', { class: 'label', style: { marginTop: '14px' } }, `All notes (${list.length})`));
    list.forEach(n => allNotes.append(h('button', { class: 'sessnoteline' + (n === cur + 1 ? ' on' : ''), onclick: () => go(n - 1) },
      h('b', { class: 'mono' }, String(n)), ' ', notes[n].trim().split('\n')[0].slice(0, 140))));
  }
  panes.notes.append(h('div', { class: 'row', style: { justifyContent: 'space-between', alignItems: 'baseline' } }, noteHead, saved), speaker, mine, allNotes,
    h('p', { class: 'muted', style: { fontSize: '11px', wordBreak: 'break-all' } }, data.folder ? 'Saved in ' + data.folder : 'Notes, pen marks and recordings are saved in the session’s own folder under Courses in your library.'));

  // Transcript
  const trAudio = h('audio', { controls: true, preload: 'none', style: { width: '100%' } });
  const trPick = h('select', { style: { width: 'auto' } });
  const follow = h('input', { type: 'checkbox', checked: true });
  const trOut = h('div', { class: 'transcript sesstranscript' });
  let tr = null; // { id, events, segs }
  const transcribed = () => (recState.recordings || []).filter(r => r.transcribed).sort((a, b) => b.started - a.started);
  trPick.addEventListener('change', () => { tr = null; loadTranscript(); });
  async function loadTranscript() {
    const list = transcribed();
    clear(trPick).append(...list.map(r => h('option', { value: r.id, selected: tr ? tr.id === r.id : false }, new Date(r.started).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) + ' · ' + clockOf(r.durationMs))));
    trPick.hidden = list.length < 2;
    if (!list.length) {
      clear(trOut).append(h('p', { class: 'muted' }, (recState.recordings || []).length ? 'No transcript yet. Transcribe a recording under Recording.' : 'Record the lecture under Recording; once transcribed, what was said shows here slide by slide.'));
      trAudio.hidden = true; return;
    }
    const id = trPick.value || list[0].id;
    if (tr && tr.id === id) { followTranscript(); return; }
    trAudio.hidden = false;
    clear(trOut).append(h('p', { class: 'muted' }, 'Loading the transcript…'));
    try {
      const rp = t => P(`recordings/${encodeURIComponent(id)}${t ? '/' + t : ''}`);
      const [tl, tj] = await Promise.all([api(rp('timeline')), api(rp('transcript'))]);
      const events = (tl.events || []).filter(e => e.type === 'start' || e.type === 'slide').sort((a, b) => a.t - b.t);
      tr = { id, events, segs: (tj.segments || []).slice().sort((a, b) => a.start - b.start) };
      trAudio.src = '/api/' + rp('audio');
      drawTranscript();
    } catch (e) { clear(trOut).append(h('p', { class: 'warn' }, 'Could not load the transcript: ' + e.message)); }
  }
  function drawTranscript() {
    clear(trOut);
    let last = -1, sec = null;
    tr.segs.forEach(sg => {
      const sl = slideAt(tr.events, sg.start);
      if (sl !== last || !sec) {
        last = sl;
        sec = h('div', { class: 'sesstrsec', 'data-slide': sl });
        sec.append(h('h4', null,
          h('a', { href: 'javascript:void 0', title: 'Play from here', onclick: () => { trAudio.currentTime = sg.start / 1000; trAudio.play(); } }, '▶ ' + clockOf(sg.start)), ' ',
          sl ? h('a', { href: 'javascript:void 0', title: 'Show the slide', onclick: () => go(sl - 1) }, 'Slide ' + sl) : 'Start'));
        trOut.append(sec);
      }
      sec.append(h('p', { onclick: () => { trAudio.currentTime = sg.start / 1000; trAudio.play(); }, title: clockOf(sg.start) }, sg.text));
    });
    if (!tr.segs.length) trOut.append(h('p', { class: 'muted' }, 'No speech was found in this recording.'));
    followTranscript();
  }
  function followTranscript() {
    if (!tr || tab !== 'transcript') return;
    let first = null;
    trOut.querySelectorAll('.sesstrsec').forEach(el => { const on = +el.dataset.slide === cur + 1; el.classList.toggle('on', on); if (on && !first) first = el; });
    if (first && !trAudio.paused) return;
    if (first) first.scrollIntoView({ block: 'nearest' });
  }
  // While the audio plays, the slides follow it.
  trAudio.addEventListener('timeupdate', () => {
    if (!tr || !follow.checked || !count) return;
    const sl = slideAt(tr.events, trAudio.currentTime * 1000);
    if (sl && sl - 1 !== cur) go(sl - 1);
  });
  panes.transcript.append(h('div', { class: 'row', style: { gap: '8px', alignItems: 'center', flexWrap: 'wrap' } }, trPick,
    h('label', { class: 'row', style: { gap: '4px', fontSize: '12px' } }, follow, 'Slides follow the audio')), trAudio, trOut);

  // Recording
  const recBox = h('div');
  const listBox = h('div');
  function drawRecording() {
    const r = recState.recording;
    clear(recBox);
    if (r) {
      recBox.append(h('div', { class: 'sessrecnow' },
        h('span', { class: 'dot' + (r.paused ? ' paused' : '') }), h('b', { class: 'mono' }, (r.paused ? 'Paused ' : 'Recording ') + clockOf(r.elapsedMs)),
        h('span', { class: 'muted' }, count ? ` · slide changes are marked (now ${r.slide || cur + 1})` : ''),
        h('span', { style: { flex: 1 } }),
        tablet ? h('span', { class: 'muted' }, 'Pause and stop in the bar above') : h('span', { class: 'row', style: { gap: '6px' } },
          h('button', { class: 'btn', onclick: () => record(r.paused ? 'resume' : 'pause') }, r.paused ? 'Resume' : 'Pause'),
          h('button', { class: 'btn fill', onclick: () => record('stop') }, 'Stop'))));
    } else if (recState.busyElsewhere) {
      recBox.append(h('p', { class: 'muted' }, 'The tablet is recording something else just now.'));
    } else {
      recBox.append(h('p', { class: 'muted', style: { marginTop: 0 } }, 'Records with the tablet’s microphone. Each slide change is marked in the recording, so the transcript is split up by slide.'),
        tablet ? h('p', null, h('b', null, 'Press Record in the bar above.')) : h('button', { class: 'btn fill', onclick: () => record('start') }, '● Record on the tablet'),
        !tablet && recState.mic === false ? h('p', { class: 'muted', style: { fontSize: '12px' } }, 'The first time, open this session on the tablet (Courses ▸ the course ▸ the session) and press Record there, so Android can ask for the microphone.') : null);
    }
    if (recState.error) recBox.append(h('div', { class: 'warn' }, recState.error));
    drawList();
  }
  async function record(action) {
    try { await api(P('record'), { method: 'POST', json: { action, slide: count ? cur + 1 : 0 } }); lastPosted = action === 'start' ? cur + 1 : lastPosted; setTimeout(poll, 600); }
    catch (e) { fail(e); }
  }
  function drawList() {
    const list = (recState.recordings || []).slice().sort((a, b) => b.started - a.started);
    clear(listBox);
    if (!list.length) return;
    listBox.append(h('div', { class: 'label', style: { marginTop: '14px' } }, `Recordings (${list.length})`));
    list.forEach(r => {
      const rp = t => P(`recordings/${encodeURIComponent(r.id)}${t ? '/' + t : ''}`);
      const reload = () => setTimeout(poll, 500);
      const engines = [
        { label: 'On this computer', hint: 'Whisper in this browser', fn: () => transcribeHere(null, r, reload, rp) },
        recState.offline ? { label: 'On the tablet', hint: 'offline, slow', fn: () => askLanguage(r, lang => api(rp('transcribe'), { method: 'POST', json: { engine: 'tablet', language: lang } }).then(() => { toast('Transcribing on the tablet'); reload(); }).catch(fail)) } : null,
        ...(recState.online || []).map(o => ({ label: 'With ' + o.label, hint: 'audio is sent to ' + o.label, fn: () => askLanguage(r, lang => api(rp('transcribe'), { method: 'POST', json: { engine: 'online', provider: o.id, language: lang } }).then(() => { toast('Sent to ' + o.label); reload(); }).catch(fail)) }))
      ].filter(Boolean);
      const lang = { no: 'Norwegian', en: 'English' }[r.language] || '';
      listBox.append(h('div', { class: 'rec' },
        h('div', { class: 'rec-head' }, h('b', null, new Date(r.started).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })),
          h('span', { class: 'mono muted' }, [clockOf(r.durationMs), fmtSize(r.size), r.transcribed ? 'transcribed' + (lang ? ' · ' + lang : '') : ''].filter(Boolean).join(' · '))),
        h('audio', { controls: true, preload: 'none', src: '/api/' + rp('audio') }),
        r.status ? h('div', { class: 'rec-status' }, r.status) : null,
        r.error ? h('div', { class: 'warn' }, r.error) : null,
        h('div', { class: 'row' },
          r.transcribed ? h('button', { class: 'btn', onclick: () => { tab = 'transcript'; tr = null; drawTabs(); setTimeout(() => { trPick.value = r.id; tr = null; loadTranscript(); }, 0); } }, 'Transcript') : null,
          menuButton(r.transcribed ? 'Transcribe again ▾' : 'Transcribe ▾', engines.map(e => [e.label, e.hint, e.fn]), r.transcribed ? 'btn' : 'btn fill'),
          menuButton('Download ▾', [
            ['Audio (.m4a)', '', () => download(rp('audio') + '?download=1', 'Downloading…')],
            r.transcribed ? ['Transcript (.md)', 'speech under each slide', () => download(rp('transcript') + '?format=md&download=1', 'Downloading…')] : null
          ].filter(Boolean)),
          h('button', { class: 'btn quiet', onclick: () => confirmBox('Delete this recording?', 'The audio, its slide timeline and the transcript are deleted from the session’s folder.', 'Delete', async () => {
            try { await api(rp(), { method: 'DELETE' }); toast('Recording deleted'); if (tr && tr.id === r.id) tr = null; poll(); } catch (e) { fail(e); }
          }) }, 'Delete'))));
    });
  }
  panes.recording.append(recBox, listBox);

  // ---------------------------------------------------------------- keeping in step with the tablet
  let sig = '';
  async function poll() {
    if (!main.isConnected) return;
    try { recState = await api(P()); } catch (_) { return; }
    // Notes changed elsewhere (another page, or the file in Obsidian): taken in unless you are typing.
    Object.entries(recState.notes || {}).forEach(([k, v]) => { if (!(noteDirty && +k === cur + 1)) notes[k] = v; });
    Object.keys(notes).forEach(k => { if (!(k in (recState.notes || {})) && !(noteDirty && +k === cur + 1)) delete notes[k]; });
    if (!noteDirty && document.activeElement !== mine && count && mine.value !== (notes[cur + 1] || '')) mine.value = notes[cur + 1] || '';
    if (recState.recording && recState.recording.slide !== cur + 1) { lastPosted = 0; postSlide(); }
    const now = JSON.stringify([recState.recording && [recState.recording.paused], (recState.recordings || []).map(r => [r.id, r.transcribed, r.status, r.error]), recState.error, recState.busyElsewhere]);
    if (now !== sig) { sig = now; drawTabs(); drawRecording(); }
    else if (recState.recording) { const b = recBox.querySelector('.sessrecnow b'); if (b) b.textContent = (recState.recording.paused ? 'Paused ' : 'Recording ') + clockOf(recState.recording.elapsedMs); }
    markThumbs(); drawAllNotes();
  }
  const loop = async () => {
    while (main.isConnected) {
      await new Promise(r => setTimeout(r, recState.recording || (recState.recordings || []).some(r => r.status) ? 2000 : 6000));
      await poll();
    }
  };

  // Keys: ← → (and Page Up / Down) move between slides, unless you are typing.
  const keys = e => {
    if (!main.isConnected) { document.removeEventListener('keydown', keys); return; }
    if (e.target.closest && e.target.closest('textarea, input, select, [contenteditable]')) return;
    if (document.querySelector('#modal-root .scrim')) return;
    if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') { e.preventDefault(); go(cur + 1); }
    else if (e.key === 'ArrowLeft' || e.key === 'PageUp') { e.preventDefault(); go(cur - 1); }
  };
  document.addEventListener('keydown', keys);
  // Swipes on the slide (when no pen is chosen).
  let sx = null;
  stageWrap.addEventListener('pointerdown', e => { if (tool === 'off') sx = e.clientX; });
  stageWrap.addEventListener('pointerup', e => { if (sx == null) return; const dx = e.clientX - sx; sx = null; if (Math.abs(dx) > 60) go(cur + (dx < 0 ? 1 : -1)); });

  drawTabs(); drawNote(); drawAllNotes(); drawRecording();
  sig = JSON.stringify([recState.recording && [recState.recording.paused], (recState.recordings || []).map(r => [r.id, r.transcribed, r.status, r.error]), recState.error, recState.busyElsewhere]);
  await drawSlides();
  loop();
}

// ------------------------------------------------------------------ choosing the slides

/** A presentation from Presentations, a PDF from the library, or a new file (added to Presentations). */
function pickSlides(c, x, done) {
  modal(async (box, close) => {
    box.classList.add('wide');
    const set = sl => { x.slides = sl; close(); coursesSave().then(ok => { if (ok) { toast('Slides added to the session'); done(); } }); };
    const file = h('input', { type: 'file', accept: '.pptx,.odp,.pdf', hidden: true });
    file.addEventListener('change', async () => {
      const f0 = file.files[0]; file.value = ''; if (!f0) return;
      toast(`Uploading ${f0.name}…`, 0);
      try {
        const r = await fetch('/api/presentations?name=' + encodeURIComponent(f0.name), { method: 'POST', body: f0, credentials: 'same-origin', headers: { 'X-Marginalia': '1' } });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.error || 'Upload failed');
        toast(`Added “${j.title}” to Presentations`);
        set({ kind: 'deck', id: j.id, title: j.title });
      } catch (e) { fail(e); }
    });
    const decks = h('div', { class: 'sesspick' }, h('p', { class: 'muted' }, 'Reading the presentations…'));
    const q = h('input', { type: 'search', placeholder: 'Search the library’s PDFs: title or author…' });
    const pdfs = h('div', { class: 'sesspick' });
    box.append(h('h2', null, 'The slides for this session'),
      h('div', { class: 'row', style: { gap: '8px', alignItems: 'center' } }, h('button', { class: 'btn fill', onclick: () => file.click() }, 'Upload a file…'), file,
        h('span', { class: 'muted', style: { fontSize: '12px' } }, 'PowerPoint (.pptx), LibreOffice (.odp) or PDF — it is added to Presentations too. Google Slides: File → Download → PowerPoint or PDF.')),
      h('div', { class: 'label', style: { marginTop: '12px' } }, 'From Presentations'), decks,
      h('div', { class: 'label', style: { marginTop: '12px' } }, 'A PDF from the library'), q, pdfs,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel')));
    try {
      const list = (await api('presentations')).presentations || [];
      clear(decks);
      if (!list.length) decks.append(h('p', { class: 'muted' }, 'No presentations yet.'));
      list.forEach(d => decks.append(h('button', { class: 'crsres' + (x.slides && x.slides.kind === 'deck' && x.slides.id === d.id ? ' on' : ''), onclick: () => set({ kind: 'deck', id: d.id, title: d.title }) },
        h('b', null, d.title), ' ', h('span', { class: 'muted' }, [d.type.toUpperCase(), d.slides ? d.slides + ' slides' : '', fmtDate(d.added)].filter(Boolean).join(' · ')))));
    } catch (e) { clear(decks).append(h('p', { class: 'warn' }, e.message)); }
    let docs = [];
    try { docs = (await loadLibrary()).filter(d => String(d.fileType || '').toUpperCase() === 'PDF'); } catch (_) {}
    const search = () => {
      const terms = norm(q.value).split(/\s+/).filter(Boolean);
      clear(pdfs);
      const hits = (terms.length ? docs.filter(d => terms.every(t => norm([d.title, d.author, d.year, d.tags || ''].join(' ')).includes(t))) : docs.slice().sort((a, b) => (b.addedAt || b.id) - (a.addedAt || a.id))).slice(0, 12);
      hits.forEach(d => pdfs.append(h('button', { class: 'crsres', onclick: () => set({ kind: 'doc', id: d.id, title: d.title }) }, h('b', null, d.title), ' ', h('span', { class: 'muted' }, [d.author, d.year].filter(Boolean).join(' · ')))));
      if (!hits.length) pdfs.append(h('p', { class: 'muted' }, terms.length ? 'No PDF matches.' : 'No PDFs in the library.'));
    };
    q.addEventListener('input', search);
    search();
  });
}
