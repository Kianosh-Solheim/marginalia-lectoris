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
    box.append(player.el, h('div', { class: 'row', style: { marginTop: '8px' } }, addBtn, add,
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
function abPlayer(d, a) {
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
  const playBtn = h('button', { class: 'btn fill', style: { minWidth: '80px' }, onclick: () => { if (audio.paused) { if (idx < 0) open(...locate(a.positionMs || 0), true); else audio.play(); } else audio.pause(); } }, 'Play');
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
  audio.addEventListener('timeupdate', () => { if (!el.isConnected) { api_stop(); return; } draw(); if (!audio.paused) save(false); });
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
  return { el, stop: api_stop };
}
