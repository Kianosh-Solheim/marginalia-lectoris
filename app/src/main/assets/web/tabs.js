/* Marginalia Lectoris — tabs: several things open at once in the web view, two of them side by side.
 *
 * With tabs on, the page becomes a frame around the app: a strip of tabs, and under it the open
 * tabs, each a full copy of the app in its own frame (so each keeps its place, selection and
 * panels while you look at another). A tab can hold the library, a book or article, the notes
 * view or a writing document. Notes can be dragged from one tab into the text in another.
 */

const TAB_MIME = 'application/x-ml-tab';
let workspace = null;

const tabsOn = () => !EMBEDDED && localGet('ml.tabs', '') === '1';
function tabLabelFor(hash) {
  const p = hash.replace(/^#\/?/, '').split('?')[0].split('/');
  return p[0] === 'notes' ? 'Notes' : p[0] === 'writing' ? 'Writing' : p[0] === 'matrix' ? 'Matrix' : p[0] === 'lexicon' ? 'Lexicon' : p[0] === 'screen' ? 'Tablet screen' : p[0] === 'write' ? 'Document' : p[0] === 'read' ? 'Reading' : p[0] === 'book' ? 'Book'
    : p[0] === 'search' ? 'Search' : p[0] === 'settings' ? 'Settings' : p[0] === 'zotero' ? 'Zotero' : 'Library';
}

/** The frame around the app: tab strips, panes and a divider. */
function showWorkspace(firstHash) {
  const app = clear(document.getElementById('app'));
  document.body.classList.add('wsbody');
  let saved = {}; try { saved = JSON.parse(localGet('ml.tabsState', '{}')); } catch (_) {}
  const W = workspace = { tabs: [], active: [null, null], split: !!saved.split, ratio: saved.ratio || 0.5, seq: 1 };
  (saved.tabs || []).forEach(t => W.tabs.push({ id: W.seq++, hash: t.hash || '#/', title: t.title || tabLabelFor(t.hash || '#/'), pane: t.pane ? 1 : 0, frame: null }));
  const panes = [0, 1].map(i => ({ strip: h('div', { class: 'wsstrip' }), frames: h('div', { class: 'wsframes' }), el: null }));
  panes.forEach((p, i) => { p.el = h('div', { class: 'wspane' }, p.strip, p.frames); });
  const divider = h('div', { class: 'wsdivider', title: 'Drag to change the widths' });
  const root = h('div', { class: 'ws' }, panes[0].el, divider, panes[1].el);
  app.append(root);

  const save = () => localSet('ml.tabsState', JSON.stringify({ split: W.split, ratio: W.ratio, tabs: W.tabs.map(t => ({ hash: t.hash, title: t.title, pane: t.pane })), active: W.active.map(id => W.tabs.findIndex(t => t.id === id)) }));
  const inPane = i => W.tabs.filter(t => t.pane === i);
  const frameOf = t => {
    if (!t.frame) {
      t.frame = h('iframe', { class: 'wsframe', src: location.pathname + '?tab=' + t.id + t.hash, title: t.title });
      panes[t.pane].frames.append(t.frame);
    } else if (t.frame.parentNode !== panes[t.pane].frames) panes[t.pane].frames.append(t.frame);   // (moving a frame reloads it)
    return t.frame;
  };
  function activate(id) {
    const t = W.tabs.find(x => x.id === id); if (!t) return;
    W.active[t.pane] = id; draw(); save();
  }
  function add(hash, pane, quiet) {
    if (!W.split) pane = 0;
    const t = { id: W.seq++, hash: hash || '#/', title: tabLabelFor(hash || '#/'), pane: pane ? 1 : 0, frame: null };
    W.tabs.push(t);
    if (!quiet) W.active[t.pane] = t.id;
    draw(); save();
    return t;
  }
  function close(id) {
    const i = W.tabs.findIndex(x => x.id === id); if (i < 0) return;
    const t = W.tabs[i]; if (t.frame) t.frame.remove();
    W.tabs.splice(i, 1);
    if (W.active[t.pane] === id) { const rest = inPane(t.pane); W.active[t.pane] = rest.length ? rest[Math.min(rest.length - 1, Math.max(0, i - 1))].id : null; }
    if (!W.tabs.length) add('#/', 0);
    if (W.split && !inPane(1).length) setSplit(false); else { draw(); save(); }
  }
  function move(id, pane, before) {
    const t = W.tabs.find(x => x.id === id); if (!t) return;
    const from = t.pane;
    W.tabs.splice(W.tabs.indexOf(t), 1);
    const at = before != null ? W.tabs.findIndex(x => x.id === before) : -1;
    if (at >= 0) W.tabs.splice(at, 0, t); else W.tabs.push(t);
    if (from !== pane) {
      t.pane = pane; if (t.frame) { t.frame.remove(); t.frame = null; }
      if (W.active[from] === id) { const rest = inPane(from); W.active[from] = rest.length ? rest[rest.length - 1].id : null; }
      W.active[pane] = id;
      if (!inPane(0).length) { W.tabs.forEach(x => { x.pane = 0; if (x.frame) { x.frame.remove(); x.frame = null; } }); W.active = [id, null]; W.split = false; }
    }
    draw(); save();
  }
  function setSplit(on) {
    if (on === W.split) return;
    W.split = on;
    if (on) {
      // The active tab stays on the left; a second one opens on the right (another open tab if there is one).
      const other = inPane(0).filter(t => t.id !== W.active[0]).pop();
      if (other) { other.pane = 1; if (other.frame) { other.frame.remove(); other.frame = null; } W.active[1] = other.id; }
      else { const t = { id: W.seq++, hash: '#/', title: 'Library', pane: 1, frame: null }; W.tabs.push(t); W.active[1] = t.id; }
    } else {
      W.tabs.forEach(t => { if (t.pane === 1) { t.pane = 0; if (t.frame) { t.frame.remove(); t.frame = null; } } });
      W.active[1] = null; if (!W.active[0] && W.tabs.length) W.active[0] = W.tabs[0].id;
    }
    draw(); save();
  }
  function draw() {
    root.classList.toggle('split', W.split);
    panes[0].el.style.flex = W.split ? `0 0 calc(${(W.ratio * 100).toFixed(2)}% - 3px)` : '1 1 auto';
    panes.forEach((p, i) => {
      clear(p.strip);
      if (i === 1 && !W.split) return;
      const tabs = inPane(i);
      if (!W.active[i] || !tabs.some(t => t.id === W.active[i])) W.active[i] = tabs.length ? tabs[0].id : null;
      tabs.forEach(t => {
        const el = h('div', { class: 'wstab' + (t.id === W.active[i] ? ' on' : ''), draggable: 'true', title: t.title },
          h('span', { class: 'wstt' }, t.title),
          h('button', { class: 'wsx', title: 'Close this tab', onclick: e => { e.stopPropagation(); close(t.id); } }, '×'));
        el.addEventListener('click', () => activate(t.id));
        el.addEventListener('auxclick', e => { if (e.button === 1) { e.preventDefault(); close(t.id); } });
        el.addEventListener('dragstart', e => { e.dataTransfer.setData(TAB_MIME, String(t.id)); e.dataTransfer.effectAllowed = 'move'; root.classList.add('dragging'); });
        el.addEventListener('dragend', () => root.classList.remove('dragging'));
        // Something dragged over a tab (a note, say) brings that tab to the front, so it can be dropped inside it.
        let spring = 0;
        el.addEventListener('dragenter', e => { if ([...e.dataTransfer.types].includes(TAB_MIME)) return; clearTimeout(spring); spring = setTimeout(() => activate(t.id), 450); });
        el.addEventListener('dragleave', () => clearTimeout(spring));
        el.addEventListener('dragover', e => { if ([...e.dataTransfer.types].includes(TAB_MIME)) { e.preventDefault(); el.classList.add('over'); } });
        el.addEventListener('dragleave', () => el.classList.remove('over'));
        el.addEventListener('drop', e => { const id = +e.dataTransfer.getData(TAB_MIME); el.classList.remove('over'); if (id && id !== t.id) { e.preventDefault(); e.stopPropagation(); move(id, i, t.id); } });
        p.strip.append(el);
      });
      p.strip.append(h('button', { class: 'wsadd', title: 'New tab (opens the library)', onclick: () => add('#/', i) }, '+'), h('span', { class: 'wsfill' }));
      p.strip.ondragover = e => { if ([...e.dataTransfer.types].includes(TAB_MIME)) e.preventDefault(); };
      p.strip.ondrop = e => { const id = +e.dataTransfer.getData(TAB_MIME); if (id) { e.preventDefault(); move(id, i, null); } };
      if (i === (W.split ? 1 : 0)) p.strip.append(
        h('button', { class: 'wsbtn' + (W.split ? ' on' : ''), title: W.split ? 'Back to one pane' : 'Two panes side by side', onclick: () => setSplit(!W.split) }, W.split ? '▣ One pane' : '◫ Side by side'),
        h('button', { class: 'wsbtn', title: 'Leave tabs: back to the ordinary single view', onclick: () => { const t = W.tabs.find(x => x.id === W.active[0]); localSet('ml.tabs', ''); location.href = location.pathname + (t ? t.hash : '#/'); location.reload(); } }, 'Close tabs'));
      // Only the tab in front is shown; the others stay loaded behind it.
      tabs.forEach(t => { if (t.id === W.active[i]) frameOf(t).classList.remove('hidden'); else if (t.frame) t.frame.classList.add('hidden'); });
    });
  }
  // The divider between the panes.
  divider.addEventListener('pointerdown', e => {
    e.preventDefault(); root.classList.add('dragging'); divider.setPointerCapture(e.pointerId);
    const moveIt = ev => { const r = root.getBoundingClientRect(); W.ratio = Math.min(0.8, Math.max(0.2, (ev.clientX - r.left) / r.width)); panes[0].el.style.flex = `0 0 calc(${(W.ratio * 100).toFixed(2)}% - 3px)`; };
    const up = () => { root.classList.remove('dragging'); divider.removeEventListener('pointermove', moveIt); divider.removeEventListener('pointerup', up); save(); };
    divider.addEventListener('pointermove', moveIt); divider.addEventListener('pointerup', up);
  });
  // What the tabs tell the frame: where they are now (for the label), and "open this in a new tab".
  window.addEventListener('message', e => {
    if (e.origin !== location.origin || !e.data || !e.data.ml) return;
    const t = W.tabs.find(x => x.frame && x.frame.contentWindow === e.source); if (!t) return;
    if (e.data.ml === 'route') { t.hash = e.data.hash || '#/'; t.title = e.data.title || tabLabelFor(t.hash); if (t.frame) t.frame.title = t.title; draw(); save(); }
    if (e.data.ml === 'open') {
      // "To the side": into the other pane, which is opened for it if there is only one.
      if (e.data.side && !W.split) { W.split = true; const n = { id: W.seq++, hash: e.data.hash || '#/', title: tabLabelFor(e.data.hash || '#/'), pane: 1, frame: null }; W.tabs.push(n); W.active[1] = n.id; draw(); save(); }
      else add(e.data.hash, e.data.side ? 1 - t.pane : t.pane);
    }
  });

  if (!W.tabs.length) add(firstHash && firstHash !== '#' ? firstHash : '#/', 0);
  else {
    (saved.active || []).forEach((at, i) => { if (W.tabs[at] && W.tabs[at].pane === i) W.active[i] = W.tabs[at].id; });
    if (firstHash && firstHash.length > 2 && !W.tabs.some(t => t.hash === firstHash)) add(firstHash, 0);
    if (W.split && !inPane(1).length) W.split = false;
    draw();
  }
}

// ------------------------------------------------------------------ inside a tab

/** Tells the frame where this tab is, with a name for its label. */
function tabReport() {
  if (!EMBEDDED) return;
  const hash = location.hash || '#/', p = hash.replace(/^#\/?/, '').split('?')[0].split('/');
  const say = title => { try { parent.postMessage({ ml: 'route', hash, title }, location.origin); } catch (_) {} };
  say(tabLabelFor(hash));
  if ((p[0] === 'read' || p[0] === 'book') && p[1]) loadLibrary().then(list => { const d = list.find(x => x.id === +p[1]); if (d && location.hash === hash) say(d.title); }).catch(() => {});
  if (p[0] === 'write') setTimeout(() => { const t = document.querySelector('.wtitle'); if (t && location.hash === hash) say((t.value || t.textContent || '').trim() || 'Document'); }, 1500);
}
if (typeof EMBEDDED !== 'undefined' && EMBEDDED) {
  // Ctrl+click (or a middle click on a link) opens what was clicked in a new tab; with Alt as well, in the other pane.
  document.addEventListener('click', e => {
    const a = e.target.closest && e.target.closest('a[href^="#/"]');
    if (a && (e.ctrlKey || e.metaKey)) { e.preventDefault(); e.stopPropagation(); parent.postMessage({ ml: 'open', hash: a.getAttribute('href'), side: e.altKey }, location.origin); return; }
    if (e.ctrlKey || e.metaKey) { window.tabOpenUntil = Date.now() + 500; window.tabOpenSide = e.altKey; }
  }, true);
  document.addEventListener('auxclick', e => {
    const a = e.target.closest && e.target.closest('a[href^="#/"]');
    if (a && e.button === 1) { e.preventDefault(); parent.postMessage({ ml: 'open', hash: a.getAttribute('href') }, location.origin); }
  }, true);
}
