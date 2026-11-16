/* Marginalia Lectoris — the outline of a writing document: its headings in a panel on the left.
 * Click one to go there; drag one to move its whole section (the heading and everything under it,
 * up to the next heading of the same or a higher level). Each shows how many words it holds.
 */
function outlinePanel(editor, onToggle) {
  const el = h('aside', { class: 'woutline', hidden: true }), list = h('div', { class: 'wolist' });
  el.append(h('div', { class: 'wnhead' }, h('b', null, 'Outline'), h('span', { style: { flex: 1 } }), h('button', { class: 'btn quiet', title: 'Close the outline', onclick: () => api_.close() }, '×')), list);
  let timer = 0, dragFrom = -1, last = '';
  /** The top-level headings with where their sections start and end. */
  const sections = () => {
    const doc = editor.state.doc, heads = [], blocks = [];
    doc.forEach((node, offset) => { blocks.push({ node, from: offset, to: offset + node.nodeSize }); });
    blocks.forEach((b, i) => { if (b.node.type.name === 'heading') heads.push({ i, level: b.node.attrs.level, text: b.node.textContent.trim() || '(no title)', from: b.from }); });
    heads.forEach((hd, k) => {
      const next = heads.slice(k + 1).find(x => x.level <= hd.level);
      hd.to = next ? next.from : doc.content.size;
      hd.all = blocks.filter(b => b.from > hd.from && b.from < hd.to && b.node.type.name !== 'heading').reduce((n, b) => n + (b.node.textContent.trim().match(/\S+/g) || []).length, 0);
    });
    return heads;
  };
  const current = heads => { const pos = editor.state.selection.from; let cur = -1; heads.forEach((hd, k) => { if (hd.from <= pos) cur = k; }); return cur; };
  function draw(force) {
    if (el.hidden) return;
    const heads = sections(), cur = current(heads);
    const sig = JSON.stringify(heads.map(x => [x.level, x.text, x.all])) + cur;
    if (!force && sig === last) return; last = sig;
    clear(list);
    if (!heads.length) { list.append(h('div', { class: 'muted', style: { fontSize: '13px' } }, 'No headings yet. Give a line the style Heading 1, 2 or 3 and it appears here.')); return; }
    heads.forEach((hd, k) => {
      const row = h('div', { class: 'worow l' + hd.level + (k === cur ? ' on' : ''), draggable: 'true', title: 'Click to go there · drag to move the whole section' },
        h('span', { class: 'wot' }, hd.text), h('span', { class: 'woc', title: `${hd.all} words in this section, with its sub-sections` }, String(hd.all)));
      row.addEventListener('click', () => {
        editor.chain().setTextSelection(hd.from + 1).run();
        const dom = editor.view.nodeDOM(hd.from); if (dom && dom.scrollIntoView) dom.scrollIntoView({ block: 'start', behavior: 'smooth' });
        draw(true);
      });
      row.addEventListener('dragstart', e => { dragFrom = k; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', hd.text); row.classList.add('dragging'); });
      row.addEventListener('dragend', () => { dragFrom = -1; list.querySelectorAll('.over,.dragging').forEach(x => x.classList.remove('over', 'dragging')); });
      row.addEventListener('dragover', e => { if (dragFrom < 0 || dragFrom === k) return; e.preventDefault(); list.querySelectorAll('.over').forEach(x => x.classList.remove('over')); row.classList.add('over'); });
      row.addEventListener('drop', e => { if (dragFrom < 0) return; e.preventDefault(); const from = dragFrom; dragFrom = -1; move(heads, from, hd.from); });
      list.append(row);
    });
    const end = h('div', { class: 'worow end muted' }, 'Drop here to put it last');
    end.addEventListener('dragover', e => { if (dragFrom < 0) return; e.preventDefault(); list.querySelectorAll('.over').forEach(x => x.classList.remove('over')); end.classList.add('over'); });
    end.addEventListener('drop', e => { if (dragFrom < 0) return; e.preventDefault(); const from = dragFrom; dragFrom = -1; move(heads, from, editor.state.doc.content.size); });
    list.append(end);
  }
  /** Moves section [k] so that it starts where [target] is now. */
  function move(heads, k, target) {
    const s = heads[k]; if (!s) return;
    if (target >= s.from && target <= s.to) { draw(true); return; }             // onto itself or inside itself: nothing to do
    const tr = editor.state.tr, slice = editor.state.doc.slice(s.from, s.to);
    tr.delete(s.from, s.to);
    tr.insert(tr.mapping.map(target), slice.content);
    editor.view.dispatch(tr.scrollIntoView());
    draw(true);
  }
  const api_ = {
    el,
    isOpen: () => !el.hidden,
    open() { el.hidden = false; localSet('ml.woutlineOpen', '1'); last = ''; draw(true); onToggle && onToggle(true); },
    close() { el.hidden = true; localSet('ml.woutlineOpen', ''); onToggle && onToggle(false); },
    toggle() { if (el.hidden) api_.open(); else api_.close(); },
    refresh() { clearTimeout(timer); timer = setTimeout(() => draw(false), 250); },
  };
  return api_;
}
