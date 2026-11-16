/* Marginalia Lectoris — dictation. A small microphone beside a text field: click, talk, click again.
 *
 * The browser may not use the computer's microphone on a plain-http page, so it is the TABLET that
 * listens (it is the one serving this page) and turns the speech into text with its offline Whisper.
 * The text arrives when you stop. While you talk, the bars follow the loudness the tablet hears.
 */
const dictation = { active: null };   // { key, field, ui, phase, level, seconds, gone }
const dictLang = () => (localGet('ml.dictLang', 'no') === 'en' ? 'en' : 'no');
const DICT_MIC = '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path fill="currentColor" d="M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V22h2v-3.08A7 7 0 0 0 19 12h-2Z"/></svg>';

/** Puts what was said into the field: at the cursor when the field has been used, else at the end. */
function dictInsert(field, said, oneLine) {
  let text = String(said || '').replace(/\s+/g, ' ').trim(); if (!text) return;
  if (oneLine) text = text.replace(/[.。]$/, '');
  const v = field.value, used = field._dictTouched;
  const a = used && field.selectionStart != null ? field.selectionStart : v.length, b = used && field.selectionEnd != null ? field.selectionEnd : v.length;
  const before = v.slice(0, a), after = v.slice(b);
  const lead = before && !/\s$/.test(before) ? ' ' : '', trail = after && !/^\s/.test(after) ? ' ' : '';
  field.value = before + lead + text + trail + after;
  const pos = (before + lead + text).length;
  field._dictTouched = true;
  try { field.focus(); field.setSelectionRange(pos, pos); } catch (_) {}
  field.dispatchEvent(new Event('input', { bubbles: true }));
}

function dictRelease() { const a = dictation.active; dictation.active = null; if (a && a.ui) a.ui.show(); }
async function dictCancel() { try { await api('dictation/cancel', { method: 'POST', json: {} }); } catch (_) {} }

async function dictPoll() {
  const a = dictation.active; if (!a) return;
  // The field went away (the dialog was closed): give up. A redraw gets a moment to bring it back.
  if (!a.field || !a.field.isConnected) { a.gone = (a.gone || 0) + 1; if (a.gone > 6) { dictRelease(); dictCancel(); return; } } else a.gone = 0;
  let st = null;
  try { st = await api('dictation'); } catch (e) { a.fails = (a.fails || 0) + 1; if (a.fails > 8) { dictRelease(); toast('Lost contact with the tablet; the dictation was dropped.'); return; } }
  if (dictation.active !== a) return;
  if (st) {
    a.fails = 0;
    if (st.phase === 'recording') { a.phase = 'rec'; a.level = st.level || 0; a.seconds = st.seconds || 0; }
    else if (st.phase === 'transcribing') a.phase = 'work';
    else if (st.phase === 'done') { const f = a.field, said = st.text; dictRelease(); dictCancel(); if (said && said.trim()) { if (f && f.isConnected) dictInsert(f, said, a.oneLine); } else toast('No words were heard.'); return; }
    else if (st.phase === 'error') { dictRelease(); dictCancel(); modal((box, close) => box.append(h('h2', null, 'The dictation did not work'), h('p', null, st.error || 'Unknown fault.'), h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: close }, 'Close')))); return; }
    else { dictRelease(); return; }   // idle: stopped from the tablet
    if (a.ui) a.ui.show();
  }
  setTimeout(dictPoll, a.phase === 'rec' ? 180 : 500);
}

/** Asks before fetching the speech model (about 180 MB, once), then shows the download. */
function dictModelDialog(lang, already) {
  modal((box, close) => {
    const name = lang === 'en' ? 'English' : 'Norwegian', line = h('p', { class: 'muted' }), bar = h('div', { class: 'dictbar' }, h('i'));
    const foot = h('div', { class: 'foot' });
    let stopped = false;
    const watch = async () => {
      if (stopped || !box.isConnected) return;
      try {
        const st = await api('dictation');
        if (st.models && st.models[lang]) { close(); toast('The speech model is on the tablet. Click the microphone to dictate.'); return; }
        if (st.phase === 'error') { line.textContent = st.error || 'The download failed.'; dictCancel(); clear(foot).append(h('button', { class: 'btn fill', onclick: close }, 'Close')); return; }
        line.textContent = `Downloading to the tablet… ${st.percent || 0}%`; bar.firstChild.style.width = (st.percent || 0) + '%';
      } catch (_) {}
      setTimeout(watch, 700);
    };
    const running = () => { clear(foot).append(h('button', { class: 'btn', onclick: () => { stopped = true; dictCancel(); close(); } }, 'Cancel the download'), h('button', { class: 'btn fill', onclick: close, title: 'The download goes on' }, 'Hide')); bar.hidden = false; watch(); };
    bar.hidden = true;
    box.append(h('h2', null, 'Download the speech model?'),
      h('p', null, `Dictation in ${name} needs a speech model on the tablet: about 180 MB, fetched once from huggingface.co. After that, dictation works without the internet.`), line, bar, foot);
    if (already) running();
    else foot.append(h('button', { class: 'btn', onclick: close }, 'Not now'), h('button', { class: 'btn fill', onclick: async () => { try { await api('dictation/model', { method: 'POST', json: { language: lang } }); running(); } catch (e) { fail(e); } } }, 'Download'));
  });
}

/**
 * The control for one field: a language switch (NO / EN) and the microphone.
 * [opt.oneLine]: for titles (no full stop at the end). [opt.key]: a name for the place, so that
 * a field which is redrawn while you talk (comments) still gets the text.
 */
function dictMic(field, opt = {}) {
  const lang = h('button', { type: 'button', class: 'dlang', title: 'The language you dictate in (remembered)' });
  const btn = h('button', { type: 'button', class: 'dmic' });
  const el = h('span', { class: 'dictctl' + (opt.cls ? ' ' + opt.cls : '') }, lang, btn);
  field.addEventListener('focus', () => { field._dictTouched = true; });
  const mine = () => dictation.active && dictation.active.ui === ui;
  const ui = {
    el,
    show() {
      const a = mine() ? dictation.active : null;
      lang.textContent = dictLang().toUpperCase(); lang.hidden = !!a;
      el.classList.toggle('dlive', !!a);
      btn.className = 'dmic' + (a ? (a.phase === 'work' ? ' dwork' : ' dtalk') : '');
      if (!a) { btn.innerHTML = DICT_MIC; btn.title = 'Dictate: the tablet listens and writes what you say'; return; }
      if (a.phase === 'work') { btn.innerHTML = '<span class="dspin"></span><span class="dtime">Writing…</span>'; btn.title = 'The tablet is turning what you said into text'; return; }
      if (!btn.querySelector('.dbars')) btn.innerHTML = '<span class="dstop"></span><span class="dbars"><i></i><i></i><i></i><i></i><i></i></span><span class="dtime"></span>';
      btn.title = 'Stop — the text is written when you stop';
      const lv = Math.max(0, Math.min(1, a.level || 0));
      btn.querySelectorAll('.dbars i').forEach((b, i) => { b.style.height = (4 + lv * 16 * (0.45 + 0.55 * Math.abs(Math.sin((a.seconds * 7 + i * 1.9 + Date.now() / 140))))) + 'px'; });
      const s = a.seconds || 0; btn.querySelector('.dtime').textContent = Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
    },
  };
  lang.addEventListener('click', e => { e.preventDefault(); e.stopPropagation(); localSet('ml.dictLang', dictLang() === 'en' ? 'no' : 'en'); document.querySelectorAll('.dlang').forEach(x => { x.textContent = dictLang().toUpperCase(); }); });
  btn.addEventListener('mousedown', e => e.preventDefault());   // keep the cursor where it is in the field
  btn.addEventListener('click', async e => {
    e.preventDefault(); e.stopPropagation();
    if (mine()) { if (dictation.active.phase === 'rec') { dictation.active.phase = 'work'; ui.show(); try { await api('dictation/stop', { method: 'POST', json: {} }); } catch (er) { fail(er); } } return; }
    if (dictation.active) { toast('Another dictation is running. Stop it first.'); return; }
    const l = dictLang();
    let st; try { st = await api('dictation'); } catch (er) { return fail(er); }
    if (!st.available) return toast('Speech recognition is not part of this build of the app.');
    if (st.phase === 'downloading') return dictModelDialog(st.language || l, true);
    if (!st.permission) return modal((box, close) => box.append(h('h2', null, 'The tablet has not allowed the microphone yet'), h('p', null, 'On the tablet: open a note, tap the microphone beside the title or the note, and allow the app to record sound. After that the microphone buttons here work.'), h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: close }, 'Close'))));
    if (!st.models || !st.models[l]) return dictModelDialog(l, false);
    if (st.phase !== 'idle') await dictCancel();   // something left over from before
    try { await api('dictation/start', { method: 'POST', json: { language: l } }); } catch (er) { return fail(er); }
    dictation.active = { key: opt.key || null, field, ui, phase: 'rec', level: 0, seconds: 0, oneLine: !!opt.oneLine };
    ui.show(); dictPoll();
  });
  // A field that was redrawn while its dictation runs takes it over.
  const a = dictation.active;
  if (a && opt.key && a.key === opt.key) { a.field = field; a.ui = ui; a.gone = 0; }
  ui.show();
  return el;
}
/** The field with its microphone at the right-hand side. */
function withMic(field, opt = {}) {
  const area = field.tagName === 'TEXTAREA';
  return h('div', { class: 'dictwrap' + (area ? ' area' : '') }, field, dictMic(field, opt));
}
// Leaving the page while the tablet listens: tell it to stop listening.
window.addEventListener('pagehide', () => { if (dictation.active) { try { fetch('/api/dictation/cancel', { method: 'POST', keepalive: true, credentials: 'same-origin', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/json' }, body: '{}' }); } catch (_) {} } });
