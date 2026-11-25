// The book's audiobook in the web view: add the files (they are kept on the tablet), play them here, and share the
// place with the tablet's player.

function audiobookSection(d) {
  const box = h('div', { class: 'section abook' });
  let player = null;
  const load = async () => {
    let a; try { a = await api(`doc/${d.id}/audiobook`); } catch (e) { clear(box).append(h('p', { class: 'muted' }, e.message)); return; }
    if (player) player.stop();
    clear(box).append(h('h2', null, 'Audiobook', a.exists ? h('span', { class: 'count' }, clockOf(a.durationMs)) : null));
    const add = h('input', { type: 'file', multiple: true, accept: 'audio/*,.m4b,.mp3,.m4a,.opus,.ogg,.flac', style: { display: 'none' } });
    add.addEventListener('change', () => { const fs = [...add.files]; add.value = ''; if (fs.length) abUpload(d, fs, load); });
    const addBtn = h('button', { class: 'btn' + (a.exists ? '' : ' fill'), onclick: () => add.click() }, a.exists ? 'Add files…' : 'Add the audio files…');
    if (!a.exists) {
      box.append(h('p', { class: 'muted', style: { marginTop: 0 } }, 'One .m4b with chapters, or all the .mp3 files at once (they are put in name order). They are kept in the book’s folder on the tablet (one file as “Audiobook – title”, several in a folder of that name), and the tablet plays them with the screen off; you can listen here too, and the place is shared.'),
        h('div', { class: 'row' }, addBtn), add);
      return;
    }
    player = abPlayer(d, a);
    box.append(player.el, abSyncRow(d, load), h('div', { class: 'row', style: { marginTop: '8px' } }, addBtn, add,
      menuButton('Files ▾', a.files.map(f => [`${f.index + 1}. ${f.title || f.name}`, `${clockOf(f.durationMs)} · ${fmtSize(f.size)}`, () => confirmBox('Remove this file?', f.name, 'Remove', async () => { try { await api(`doc/${d.id}/audiobook/file/${f.index}`, { method: 'DELETE' }); load(); } catch (e) { fail(e); } })]), 'btn quiet'),
      h('button', { class: 'btn quiet', onclick: () => confirmBox('Remove the audiobook?', 'The audio files are deleted from the book’s folder on the tablet. The book and your notes stay.', 'Remove', async () => { try { await api(`doc/${d.id}/audiobook`, { method: 'DELETE' }); load(); } catch (e) { fail(e); } }) }, 'Remove')));
  };
  load();
  return box;
}

/** Uploads audio files one after another, with progress. */
function abUpload(d, files, done) {
  let cancelled = false, xhr = null;
  const status = h('div', { class: 'mono', style: { fontSize: '12px', margin: '8px 0' } });
  const bar = h('div', { class: 'pbar' }, h('i'));
  const close = modal((box, close) => {
    box.append(h('h2', null, 'Sending the audio to the tablet'), status, bar,
      h('p', { class: 'muted', style: { fontSize: '12px' } }, 'Keep this tab open until it is done. Large files take a while over Wi-Fi.'),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => { cancelled = true; if (xhr) xhr.abort(); close(); } }, 'Cancel')));
  }, () => { cancelled = true; if (xhr) xhr.abort(); });
  const total = files.reduce((a, f) => a + f.size, 0);
  let sent = 0;
  (async () => {
    for (let i = 0; i < files.length && !cancelled; i++) {
      const f = files[i];
      try {
        await new Promise((res, rej) => {
          xhr = new XMLHttpRequest();
          xhr.open('POST', `/api/doc/${d.id}/audiobook/file?name=${encodeURIComponent(f.name)}`);
          xhr.setRequestHeader('X-Marginalia', '1');
          xhr.upload.onprogress = e => { status.textContent = `${i + 1} of ${files.length}: ${f.name} — ${fmtSize(sent + e.loaded)} of ${fmtSize(total)}`; bar.firstChild.style.width = Math.round((sent + e.loaded) / total * 100) + '%'; };
          xhr.onload = () => { if (xhr.status < 300) res(); else { let m = 'HTTP ' + xhr.status; try { m = JSON.parse(xhr.responseText).error || m; } catch (_) {} rej(new Error(m)); } };
          xhr.onerror = () => rej(new Error('The connection to the tablet was lost.'));
          xhr.onabort = () => rej(new Error('Cancelled'));
          xhr.send(f);
        });
        sent += f.size;
      } catch (e) { if (!cancelled) { status.textContent = `${f.name}: ${e.message}`; } return; }
    }
    if (!cancelled) { close(); toast(`${files.length} file${files.length === 1 ? '' : 's'} added`); done(); }
  })();
}

const AB_SPEEDS = [0.8, 1, 1.1, 1.25, 1.5, 1.75, 2];

/** A player over the book's files as one timeline; the place is saved on the tablet. */
function abPlayer(d, a, hooks = {}) {
  const audio = new Audio(); audio.preload = 'metadata';
  let idx = -1, pendingSeek = null, lastSave = 0, stopped = false;
  const files = a.files, total = a.durationMs || 1, chapters = a.chapters || [];
  const global = () => (files[idx] ? files[idx].startMs : 0) + Math.round(audio.currentTime * 1000);
  const locate = ms => { let i = files.length - 1; while (i > 0 && files[i].startMs > ms) i--; return [Math.max(0, i), Math.max(0, ms - (files[Math.max(0, i)] || { startMs: 0 }).startMs)]; };
  const open = (i, offMs, play) => {
    if (i !== idx) { idx = i; audio.src = `/api/doc/${d.id}/audiobook/file/${i}`; pendingSeek = offMs / 1000; audio.load(); }
    else audio.currentTime = offMs / 1000;
    if (play) audio.play().catch(() => {});
  };
  audio.addEventListener('loadedmetadata', () => { if (pendingSeek != null) { audio.currentTime = pendingSeek; pendingSeek = null; } audio.playbackRate = speed; });
  audio.addEventListener('ended', () => { if (idx + 1 < files.length) open(idx + 1, 0, true); else draw(); });
  const save = force => {
    const now = Date.now(); if (!force && now - lastSave < 10000) return; lastSave = now;
    api(`doc/${d.id}/audiobook/position`, { method: 'PUT', json: { positionMs: global(), speed } }).then(r => { note.textContent = r.tabletPlaying ? 'The tablet is playing this book; its place is kept there.' : ''; }).catch(() => {});
  };
  let speed = a.speed || 1;
  const seek = ms => { const [i, o] = locate(Math.max(0, Math.min(total - 500, ms))); open(i, o, !audio.paused); setTimeout(() => { draw(); save(true); }, 300); };
  const chapterAt = ms => { let k = -1; chapters.forEach((c, j) => { if (c.startMs <= ms) k = j; }); return k; };
  // Play: where the reader is (hooks.beforePlay may give a place), else where it stopped.
  const playBtn = h('button', { class: 'btn fill', style: { minWidth: '80px' }, onclick: () => {
    if (!audio.paused) { audio.pause(); return; }
    const here = hooks.beforePlay ? hooks.beforePlay(idx < 0 ? (a.positionMs || 0) : global()) : null;
    if (here != null) open(...locate(here), true);
    else if (idx < 0) open(...locate(a.positionMs || 0), true); else audio.play();
  } }, 'Play');
  const title = h('b', { class: 'abtitle' });
  const time = h('span', { class: 'mono muted' });
  const line = h('div', { class: 'abline', title: 'Go to this place' }, h('i'));
  chapters.forEach(c => line.append(h('b', { style: { left: (c.startMs / total * 100) + '%' } })));
  line.addEventListener('click', e => { const r = line.getBoundingClientRect(); seek((e.clientX - r.left) / r.width * total); });
  const spd = h('button', { class: 'btn quiet', onclick: () => { speed = AB_SPEEDS[(AB_SPEEDS.findIndex(s => s >= speed - 0.01) + 1) % AB_SPEEDS.length]; audio.playbackRate = speed; draw(); save(true); } });
  const chap = h('select', { class: 'abchap', style: { width: 'auto', maxWidth: '260px' } }, chapters.map((c, j) => h('option', { value: j }, `${clockOf(c.startMs)} · ${c.title}`)));
  chap.addEventListener('change', () => seek(chapters[+chap.value].startMs));
  const note = h('div', { class: 'muted', style: { fontSize: '12px' } });
  const draw = () => {
    const g = idx < 0 ? (a.positionMs || 0) : global();
    line.firstChild.style.width = Math.min(100, g / total * 100) + '%';
    time.textContent = `${clockOf(g)} / ${clockOf(total)}`;
    const k = chapterAt(g); title.textContent = k >= 0 ? chapters[k].title : d.title;
    if (k >= 0 && chap.value !== String(k)) chap.value = String(k);
    playBtn.textContent = audio.paused ? 'Play' : 'Pause';
    spd.textContent = speed + '×';
  };
  // Leaving the book's page stops the sound (and saves the place).
  audio.addEventListener('timeupdate', () => { if (!el.isConnected) { api_stop(); return; } draw(); if (!audio.paused) save(false); if (hooks.onTime) hooks.onTime(global(), !audio.paused); });
  audio.addEventListener('play', draw); audio.addEventListener('pause', () => { draw(); if (!stopped) save(true); });
  const el = h('div', { class: 'abplayer' },
    h('div', { class: 'row', style: { alignItems: 'baseline', gap: '10px' } }, title, h('span', { style: { flex: 1 } }), time), line,
    h('div', { class: 'row', style: { gap: '6px', alignItems: 'center', flexWrap: 'wrap' } },
      h('button', { class: 'btn quiet', title: 'Previous chapter', onclick: () => { const g = idx < 0 ? a.positionMs : global(), k = chapterAt(g); seek(k >= 0 && g - chapters[k].startMs > 3000 ? chapters[k].startMs : (chapters[k - 1] || { startMs: 0 }).startMs); } }, '|◁'),
      h('button', { class: 'btn quiet', onclick: () => seek((idx < 0 ? a.positionMs : global()) - 30000) }, '−30'),
      playBtn,
      h('button', { class: 'btn quiet', onclick: () => seek((idx < 0 ? a.positionMs : global()) + 30000) }, '+30'),
      h('button', { class: 'btn quiet', title: 'Next chapter', onclick: () => { const k = chapterAt(idx < 0 ? a.positionMs : global()); if (chapters[k + 1]) seek(chapters[k + 1].startMs); } }, '▷|'),
      spd, chapters.length > 1 ? chap : null), note);
  const api_stop = () => { stopped = true; if (!audio.paused) { save(true); audio.pause(); } audio.removeAttribute('src'); };
  draw();
  return { el, stop: api_stop, playAt: ms => { const [i, o] = locate(Math.max(0, ms)); open(i, o, true); setTimeout(draw, 300); }, position: () => (idx < 0 ? (a.positionMs || 0) : global()) };
}

// ------------------------------------------------------------------ syncing the audio with the text

/** The sync line under the player: how far it is, and the button. */
function abSyncRow(d, reload) {
  const row = h('div', { class: 'absync' }, h('span', { class: 'muted' }, 'Checking the sync…'));
  api(`doc/${d.id}/audiobook/sync-info`).then(s => {
    clear(row);
    if (s.exists) row.append(h('span', null, h('b', null, 'Synced with the text'), h('span', { class: 'muted' }, ` · ${Math.round(s.coverage * 100)}% of the sentences · ${new Date(s.created).toLocaleDateString()} · ${s.engine}`)),
      isEpub(d) ? h('button', { class: 'btn quiet', title: 'A copy of the EPUB with the audio and the sync built in (EPUB 3 Media Overlays), for other reading apps', onclick: () => abExportEpub(d) }, 'Export EPUB with audio…') : null,
      h('button', { class: 'btn quiet', onclick: () => abSyncDialog(d, reload) }, 'Sync again…'));
    else row.append(h('span', { class: 'muted' }, s.transcribedMs ? `Not synced yet · transcribed up to ${clockOf(s.transcribedMs)} (it continues where it stopped)` : 'Not synced with the text yet.'),
      h('button', { class: 'btn', onclick: () => abSyncDialog(d, reload) }, s.transcribedMs ? 'Continue syncing…' : 'Sync with the text…'));
  }).catch(e => { clear(row).append(h('span', { class: 'muted' }, e.message)); });
  return row;
}

const AB_MODELS = {
  en_fast: { label: 'English · faster (Whisper base)', model: 'onnx-community/whisper-base', lang: 'english' },
  en_exact: { label: 'English · more exact (Whisper small)', model: 'onnx-community/whisper-small', lang: 'english' },
  no: { label: 'Norwegian (NB-Whisper small)', model: 'onnx-community/nb-whisper-small-ONNX', lang: 'norwegian' },
  other: { label: 'Other language (Whisper small, detected)', model: 'onnx-community/whisper-small', lang: '' },
};

/** Transcribes the audiobook with Whisper in this browser (resumable), lines it up with the book's text and saves the map. */
async function abSyncDialog(d, reload) {
  let a, prev;
  try { [a, prev] = await Promise.all([api(`doc/${d.id}/audiobook`), api(`doc/${d.id}/audiobook/transcript`)]); } catch (e) { fail(e); return; }
  const gpu = !!navigator.gpu;
  let choice = prev.modelKey || (/^(no|nb|nn)/i.test(d.language || '') ? 'no' : 'en_fast');
  let cancelled = false, running = false;
  const status = h('div', { class: 'mono', style: { fontSize: '12px', margin: '10px 0' } });
  const bar = h('div', { class: 'pbar' }, h('i'));
  const setP = (text, frac) => { status.textContent = text; if (frac != null) bar.firstChild.style.width = Math.round(frac * 100) + '%'; };
  const pick = h('select', { style: { width: 'auto' } }, Object.entries(AB_MODELS).map(([k, m]) => h('option', { value: k, selected: k === choice }, m.label)));
  pick.addEventListener('change', () => { choice = pick.value; });
  const go = h('button', { class: 'btn fill' }, prev.doneMs ? 'Continue' : 'Start');
  const restart = prev.doneMs ? h('label', { class: 'row', style: { gap: '6px', fontSize: '13px' } }, h('input', { type: 'checkbox' }), 'Start the transcription over') : null;
  const est = Math.round(a.durationMs / 60000);
  const close = modal((box, close) => {
    box.append(...[h('h2', null, 'Sync the audiobook with the text'),
      h('p', { class: 'muted', style: { marginTop: 0 } }, `Whisper listens to the audiobook (${clockOf(a.durationMs)}) in this browser, then each sentence of the book is matched with where it is read. The map is saved in the book’s folder; after that the tablet does not need this computer.`),
      h('p', { class: 'muted', style: { fontSize: '13px' } }, gpu ? 'Using the graphics card (WebGPU): expect roughly a tenth to a third of the listening time.'
        : `This browser cannot use the graphics card on this address, so Whisper runs on the processor: expect several hours for ${est} minutes of audio. It saves as it goes — you can close the tab and continue later, here or on another computer.`),
      h('div', { class: 'row', style: { gap: '8px', alignItems: 'center', flexWrap: 'wrap' } }, h('span', null, 'Speech model'), pick),
      prev.doneMs ? h('p', { class: 'muted', style: { fontSize: '13px' } }, `Already transcribed: ${clockOf(prev.doneMs)} of ${clockOf(a.durationMs)}.`) : null,
      restart, status, bar,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: () => { cancelled = true; close(); } }, running ? 'Stop (keeps what is done)' : 'Close'), go)].filter(Boolean));
  }, () => { cancelled = true; });
  go.addEventListener('click', async () => {
    if (running) return; running = true; go.disabled = true; pick.disabled = true;
    try {
      const m = AB_MODELS[choice];
      let tr = prev;
      if ((restart && restart.firstChild.checked) || (tr.modelKey && tr.modelKey !== choice) || !tr.segments) tr = { modelKey: choice, segments: [], doneMs: 0 };
      tr.modelKey = choice;
      if (tr.doneMs < a.durationMs - 1000) {
        const asr = await abWhisper(m.model, gpu, setP);
        for (const f of a.files) {
          for (let off = 0; off < f.durationMs; off += 300000) {
            if (cancelled) return;
            const g = f.startMs + off, len = Math.min(300000, f.durationMs - off);
            if (g + len <= tr.doneMs + 500) continue;
            setP(`Transcribing ${clockOf(g)} of ${clockOf(a.durationMs)}…`, g / a.durationMs);
            const buf = await (await fetch(`/api/doc/${d.id}/audiobook/pcm?file=${f.index}&from=${off}&len=${len}`, { credentials: 'same-origin', headers: { 'X-Marginalia': '1' } })).arrayBuffer();
            const i16 = new Int16Array(buf), pcm = new Float32Array(i16.length);
            for (let k = 0; k < i16.length; k++) pcm[k] = i16[k] / 32768;
            const opts = { return_timestamps: true, chunk_length_s: 30, stride_length_s: 5, task: 'transcribe' };
            if (m.lang) opts.language = m.lang;
            const res = await asr(pcm, opts);
            if (cancelled) return;
            (res.chunks || [{ timestamp: [0, len / 1000], text: res.text }]).forEach(c => {
              const t = (c.text || '').trim(); if (!t || /^\[.*\]$|^\(.*\)$/.test(t)) return;
              const [s0, s1] = c.timestamp || [0, null];
              tr.segments.push({ s: Math.round(g + (s0 || 0) * 1000), e: Math.round(g + ((s1 == null ? s0 + 2 : s1) || 0) * 1000), t });
            });
            tr.doneMs = g + len;
            await api(`doc/${d.id}/audiobook/transcript`, { method: 'PUT', json: tr, timeout: 120000 });
          }
        }
      }
      setP('Reading the book’s text on the tablet…', 1);
      const text = await api(`doc/${d.id}/audiobook/text`, { timeout: 600000 });
      setP('Matching the sentences with the audio…', 1);
      await new Promise(r => setTimeout(r, 30));
      const map = abAlign(text, tr.segments);
      map.engine = 'Whisper in the browser (' + (gpu ? 'WebGPU' : 'CPU') + ', ' + m.model.split('/')[1] + ')';
      map.language = m.lang || 'auto'; map.durationMs = a.durationMs;
      await api(`doc/${d.id}/audiobook/sync`, { method: 'POST', json: map, timeout: 120000 });
      close();
      toast(`Synced: ${Math.round(map.coverage * 100)}% of the sentences have a place in the audio`, 5000);
      reload();
    } catch (e) { console.error(e); setP('Stopped: ' + (e.message || e)); go.disabled = false; pick.disabled = false; running = false; }
  });
}

let abAsr = {};
async function abWhisper(model, gpu, setP) {
  const key = model + (gpu ? ':gpu' : ':cpu');
  if (!abAsr[key]) {
    setP('Loading Whisper…', 0);
    const { pipeline, env } = await import(TRANSFORMERS);
    env.allowLocalModels = false;
    const files = {};
    abAsr[key] = pipeline('automatic-speech-recognition', model, {
      device: gpu ? 'webgpu' : 'wasm',
      dtype: gpu ? { encoder_model: 'fp16', decoder_model_merged: 'q4' } : { encoder_model: 'q8', decoder_model_merged: 'q8' },
      progress_callback: p => {
        if (p.status === 'progress' && p.file) {
          files[p.file] = [p.loaded || 0, p.total || 0];
          const v = Object.values(files), got = v.reduce((x, y) => x + y[0], 0), all = v.reduce((x, y) => x + y[1], 0);
          setP(`Downloading the speech model (once): ${Math.round(got / 1e6)} of ${Math.round(all / 1e6)} MB`, all ? got / all : null);
        }
      }
    });
    abAsr[key].catch(() => { delete abAsr[key]; });
  }
  return abAsr[key];
}

// ------------------------------------------------------------------ alignment: book sentences ⇄ transcript times

const abNorm = w => String(w).toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^\p{L}\p{N}]+/gu, '');
const AB_ABBR = new Set(['mr', 'mrs', 'ms', 'dr', 'st', 'prof', 'eg', 'ie', 'etc', 'vs', 'cf', 'nr', 'no', 'ca', 'bl', 'mfl', 'osv', 'dvs', 'fx', 'jf', 'kap', 'jr', 'sr', 'vol', 'ed', 'pp', 'p', 'ch', 'fig']);

/** The book as tokens (with where each lies) and sentences (token ranges). */
function abBookTokens(text) {
  const toks = [], sents = [];
  text.units.forEach((u, ui) => {
    const start = toks.length;
    if (text.kind === 'pdf') u.words.forEach((w, i) => toks.push({ raw: w, n: abNorm(w), u: ui, i }));
    else { const re = /\S+/g; let m; while ((m = re.exec(u.text))) toks.push({ raw: m[0], n: abNorm(m[0]), u: ui, s: m.index, e: m.index + m[0].length }); }
    let s0 = start;
    for (let k = start; k < toks.length; k++) {
      const raw = toks[k].raw, next = toks[k + 1];
      const end = k === toks.length - 1 || (/[.!?…]["'”’»)\]]*$/.test(raw) && !AB_ABBR.has(abNorm(raw)) && raw.length > 1 &&
        (!next || next.u !== ui || /^["'“‘«(\[]?[\p{Lu}\p{N}]/u.test(next.raw)));
      if (end) { sents.push([s0, k]); s0 = k + 1; }
    }
    if (s0 < toks.length) sents.push([s0, toks.length - 1]);
  });
  return { toks, sents };
}

/** Transcript words with a time each (spread over their segment by length). */
function abTranscriptTokens(segments) {
  const out = [];
  segments.forEach(sg => {
    const words = sg.t.split(/\s+/).filter(Boolean); const total = words.reduce((x, w) => x + w.length + 1, 0) || 1;
    let at = 0;
    words.forEach(w => { const n = abNorm(w); if (n) out.push({ n, t: Math.round(sg.s + (sg.e - sg.s) * at / total) }); at += w.length + 1; });
  });
  return out;
}

/** Longest increasing subsequence (by .b) of pairs already ordered by .t. */
function abLis(pairs) {
  const tails = [], prevIdx = new Array(pairs.length), tailIdx = [];
  pairs.forEach((p, i) => {
    let lo = 0, hi = tails.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (tails[mid] < p.b) lo = mid + 1; else hi = mid; }
    tails[lo] = p.b; tailIdx[lo] = i; prevIdx[i] = lo > 0 ? tailIdx[lo - 1] : -1;
  });
  const out = []; let k = tailIdx[tails.length - 1];
  while (k != null && k >= 0) { out.push(pairs[k]); k = prevIdx[k]; }
  return out.reverse();
}

/**
 * Lines the book up with the transcript: unique three-word sequences found in both, in the same order, are the
 * anchors; between anchors the words are matched one by one; the rest of the time is spread over the words in
 * between when the pace is plausible. A sentence's time is when its first word is read.
 */
function abAlign(text, segments) {
  const { toks, sents } = abBookTokens(text);
  const bIdx = []; toks.forEach((t, i) => { if (t.n) bIdx.push(i); });
  const B = bIdx.map(i => toks[i].n), T = abTranscriptTokens(segments), Tn = T.map(x => x.n);
  const K = 3;
  const grams = (arr) => { const m = new Map(); for (let i = 0; i + K <= arr.length; i++) { const g = arr.slice(i, i + K).join(' '); const v = m.get(g); if (v === undefined) m.set(g, i); else if (v !== -1) m.set(g, -1); } return m; };
  const bg = grams(B), tg = grams(Tn);
  const pairs = [];
  tg.forEach((ti, g) => { if (ti < 0) return; const bi = bg.get(g); if (bi != null && bi >= 0) pairs.push({ b: bi, t: ti }); });
  pairs.sort((x, y) => x.t - y.t);
  const anchors = abLis(pairs);
  // Matched words: book index (in B) → transcript index.
  const match = new Int32Array(B.length).fill(-1);
  const take = (b, t) => { if (match[b] < 0) match[b] = t; };
  anchors.forEach(p => { for (let k = 0; k < K; k++) take(p.b + k, p.t + k); });
  // Extend around the anchors while the words agree.
  anchors.forEach(p => {
    for (let b = p.b + K, t = p.t + K; b < B.length && t < Tn.length && match[b] < 0 && B[b] === Tn[t]; b++, t++) match[b] = t;
    for (let b = p.b - 1, t = p.t - 1; b >= 0 && t >= 0 && match[b] < 0 && B[b] === Tn[t]; b--, t--) match[b] = t;
  });
  // Between matched words: a small word-by-word alignment when the gap is short on both sides.
  let lastB = -1, lastT = -1;
  const fill = (b0, b1, t0, t1) => {
    const nb = b1 - b0, nt = t1 - t0; if (nb <= 0 || nt <= 0 || nb * nt > 250000) return;
    const L = Array.from({ length: nb + 1 }, () => new Uint16Array(nt + 1));
    for (let i = nb - 1; i >= 0; i--) for (let j = nt - 1; j >= 0; j--) L[i][j] = B[b0 + i] === Tn[t0 + j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
    for (let i = 0, j = 0; i < nb && j < nt;) { if (B[b0 + i] === Tn[t0 + j]) { match[b0 + i] = t0 + j; i++; j++; } else if (L[i + 1][j] >= L[i][j + 1]) i++; else j++; }
  };
  for (let b = 0; b <= B.length; b++) {
    if (b === B.length || match[b] >= 0) {
      const t = b === B.length ? Tn.length : match[b];
      if (t > lastT) fill(lastB + 1, b, lastT + 1, t);
      if (b < B.length) { lastB = b; lastT = match[b]; }
    }
  }
  // A time for every book word: matched, or spread between neighbours when the pace is plausible (0.12–1.5 s a word;
  // faster means the audio skipped that text, slower means something else is read there).
  const time = new Float64Array(B.length).fill(NaN);
  let prev = -1;
  for (let b = 0; b <= B.length; b++) {
    if (b < B.length && match[b] < 0) continue;
    if (b < B.length) time[b] = T[match[b]].t;
    if (prev >= 0 && b < B.length && b - prev > 1) {
      const t0 = time[prev], t1 = time[b], n = b - prev;
      if (t1 > t0 && (t1 - t0) <= Math.max(15000, n * 1500) && (t1 - t0) >= n * 120) for (let k = 1; k < n; k++) time[prev + k] = t0 + (t1 - t0) * k / n;
    }
    prev = b;
  }
  // Sentences: when the first timed word is read (moved back by the words before it), in order.
  const pos = new Int32Array(toks.length).fill(-1); bIdx.forEach((ti, bi) => { pos[ti] = bi; });
  const out = []; let lastT2 = -1, timed = 0, counted = 0;
  sents.forEach(([s0, s1]) => {
    let first = -1, before = 0;
    for (let k = s0; k <= s1; k++) { const bi = pos[k]; if (bi < 0) continue; if (!isNaN(time[bi])) { first = bi; break; } before++; }
    const words = s1 - s0 + 1; if (words >= 2) counted++;
    if (first < 0) return;
    let t = Math.round(time[first] - before * 350);
    if (t <= lastT2) { if (time[first] <= lastT2) return; t = lastT2 + 1; }
    lastT2 = t; if (words >= 2) timed++;
    const u = text.units[toks[s0].u];
    const l = text.kind === 'pdf' ? { p: u.page, w: [toks[s0].i, toks[s1].u === toks[s0].u ? toks[s1].i : u.words.length - 1] } : { c: u.c, p: u.p, h: u.h || '', s: toks[s0].s, e: toks[s1].e };
    // The sentence's own text (the reader finds it on the page by it): exact for EPUB, the words joined for PDF.
    const x = (text.kind === 'pdf' || toks[s1].u !== toks[s0].u ? toks.slice(s0, s1 + 1).map(z => z.raw).join(' ') : u.text.slice(toks[s0].s, toks[s1].e)).slice(0, 400);
    out.push({ t, l, x });
  });
  return { version: 1, kind: text.kind, created: Date.now(), coverage: counted ? timed / counted : 0, anchors: anchors.length, sentences: out };
}

// ------------------------------------------------------------------ in the reader: listen while the text follows

/** The audiobook in the web reader: a player under the reader's bar; with a sync map, the sentence is marked and followed. */
async function abReaderAttach(ctx, viewer, d, bar, root) {
  let a; try { a = await api(`doc/${d.id}/audiobook`); } catch (_) { return; }
  if (!a.exists) return;
  let sents = [];
  try { const m = await api(`doc/${d.id}/audiobook/sync`, { timeout: 60000 }); sents = m.sentences || []; } catch (_) {}
  const norm = x => String(x).toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  const nsents = sents.map(x => norm(x.x));
  const indexAt = ms => { let lo = 0, hi = sents.length - 1, ans = -1; while (lo <= hi) { const mid = (lo + hi) >> 1; if (sents[mid].t <= ms) { ans = mid; lo = mid + 1; } else hi = mid - 1; } return ans; };
  const find = (q, page, near) => {
    const nq = norm(q); if (nq.length < 4) return -1;
    const head = nq.slice(0, 30); let best = -1, score = Infinity;
    sents.forEach((x, i) => {
      if (page && x.l.w && x.l.p !== page) return;
      const ns = nsents[i]; if (!ns) return;
      const at = nq.includes(ns.slice(0, 30)) ? nq.indexOf(ns.slice(0, 30)) : ns.includes(head) ? 0 : -1;
      if (at < 0) return;
      const sc = at * 100000 + Math.abs(i - near); if (sc < score) { score = sc; best = i; }
    });
    return best;
  };
  let last = -1;
  const player = abPlayer(d, a, {
    onTime: (ms, playing) => {
      if (!sents.length) return;
      const i = indexAt(ms);
      if (i !== last) { last = i; viewer.audioMark(sents[i] || null, playing); }
    },
    // Whispersync: Play starts where you are reading, unless the sentence it stopped at is on the page in view.
    beforePlay: ms => {
      if (!sents.length || !viewer.audioHere) return null;
      const here = viewer.audioHere(), cur = indexAt(ms);
      if (here.page) {
        if (cur >= 0 && sents[cur].l.p === here.page) return null;
        const i = sents.findIndex(x => x.l.p === here.page); return i >= 0 ? sents[i].t : null;
      }
      if (here.href) {
        const idx = []; sents.forEach((x, i) => { if (String(x.l.h || '').split('/').pop() === here.href) idx.push(i); });
        if (!idx.length) return null;
        const i = idx[Math.floor(Math.min(1, Math.max(0, here.progression)) * (idx.length - 1))];
        if (cur >= 0 && String(sents[cur].l.h || '').split('/').pop() === here.href && Math.abs(i - cur) < 25) return null;
        return sents[i].t;
      }
      return null;
    }
  });
  const host = h('div', { class: 'rabook', hidden: localGet('ml.abar', '0') !== '1' }, player.el,
    sents.length ? null : h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Not synced with the text yet — on the book’s page, “Sync with the text…” makes the text follow the audio.'));
  const btn = h('button', { class: 'btn' + (host.hidden ? '' : ' fill'), title: 'The audiobook: listen while the text follows', onclick: () => { host.hidden = !host.hidden; btn.classList.toggle('fill', !host.hidden); localSet('ml.abar', host.hidden ? '0' : '1'); } }, 'Listen');
  bar.insertBefore(btn, bar.querySelector('.rsep'));
  root.insertBefore(host, bar.nextSibling);
  if (sents.length) ctx.audio = {
    playFromText: (text, page) => {
      const i = find(text, page, Math.max(0, last));
      if (i < 0) { toast('That passage is not in the audiobook’s sync map'); return; }
      host.hidden = false; btn.classList.add('fill');
      player.playAt(sents[i].t);
    }
  };
}

/** Builds “<Title> – with audio.epub” in the book's folder on the tablet, then offers it for download. */
function abExportEpub(d) {
  confirmBox('Export the EPUB with the audio built in?',
    'A copy of the EPUB is made in the book’s folder on the tablet, with the audio files and the sync inside (EPUB 3 Media Overlays) — other reading apps can then read it aloud with the text following. Your EPUB is not changed. The copy is as large as the book and the audio together.',
    'Export', async () => {
      toast('Building the EPUB on the tablet… (large audiobooks take a minute or two)', 0);
      try {
        const r = await api(`doc/${d.id}/audiobook/export-epub`, { method: 'POST', timeout: 1800000 });
        toast(`Saved in the book’s folder: ${r.name} (${fmtSize(r.size)}) · ${r.placed} of ${r.sentences} sentences marked`, 6000);
        download(`doc/${d.id}/files?rel=${encodeURIComponent(r.name)}&download=1`, 'Downloading…');
      } catch (e) { fail(e); }
    });
}
