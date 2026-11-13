/* Marginalia Lectoris — comments in the writing program.
 *
 * A comment is a mark on a stretch of text (it carries only the comment's id) plus the comment itself
 * — who, when, the text, replies, resolved or not — which is kept with the document's settings.
 * They show as balloons in the right margin, as in Word, and go to and from Word files as real
 * comments.
 */

const newCommentId = () => 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
const RESOLVED_TAG = '[Resolved] ';

function commentMarkExtension(T) {
  return T.Mark.create({
    name: 'comment', excludes: '', inclusive: false,
    addAttributes() { return { id: { default: '', parseHTML: el => el.getAttribute('data-cid'), renderHTML: a => ({ 'data-cid': a.id }) } }; },
    parseHTML() { return [{ tag: 'span[data-cid]' }]; },
    renderHTML({ HTMLAttributes }) { return ['span', T.mergeAttributes(HTMLAttributes, { class: 'cm' }), 0]; },
  });
}

/** Your name on comments: kept in this browser, asked for the first time. */
function commentAuthor(ask) {
  let a = localGet('ml.wauthor', '');
  if (!a || ask) {
    const v = prompt('Your name (shown on your comments)', a || '');
    if (v === null) return a || null;
    a = v.trim() || a; if (a) localSet('ml.wauthor', a);
  }
  return a || null;
}

/** For the export: which text node opens and which closes each comment. */
function commentBounds(doc, data) {
  const first = new Map(), last = new Map(), firstOf = {}, lastOf = {};
  const walk = n => {
    if (n.type === 'text') (n.marks || []).forEach(m => { if (m.type === 'comment' && data[m.attrs.id]) { if (!firstOf[m.attrs.id]) firstOf[m.attrs.id] = n; lastOf[m.attrs.id] = n; } });
    (n.content || []).forEach(walk);
  };
  walk(doc);
  let no = 0; const numbers = {};
  Object.keys(firstOf).forEach(id => {
    numbers[id] = [++no].concat((data[id].replies || []).map(() => ++no));
    first.set(firstOf[id], (first.get(firstOf[id]) || []).concat(id));
    last.set(lastOf[id], (last.get(lastOf[id]) || []).concat(id));
  });
  return { first, last, numbers, data };
}
const commentIso = t => new Date(t || Date.now()).toISOString().replace(/\.\d+Z$/, 'Z');
function commentTextInlines(text) {
  const out = [];
  String(text || '').split('\n').forEach((line, i) => { if (i) out.push({ t: 'LineBreak' }); out.push(...textToInlines(line)); });
  return out;
}
/** Pandoc inlines that open the comment [id] (and its replies, as further comments on the same words). */
function commentStartInlines(id, cx, format) {
  const c = cx.data[id], all = [c].concat(c.replies || []);
  return all.map((x, i) => {
    const text = (i === 0 && c.resolved ? RESOLVED_TAG : '') + (x.text || '');
    if (format === 'odt') return { t: 'RawInline', c: ['opendocument', `<office:annotation office:name="cm${cx.numbers[id][i]}"><dc:creator>${xmlEsc(x.author || '')}</dc:creator><dc:date>${commentIso(x.date).replace('Z', '')}</dc:date>${text.split('\n').map(l => `<text:p>${xmlEsc(l)}</text:p>`).join('')}</office:annotation>`] };
    return { t: 'Span', c: [['', ['comment-start'], [['id', String(cx.numbers[id][i])], ['author', x.author || ''], ['date', commentIso(x.date)]]], commentTextInlines(text)] };
  });
}
function commentEndInlines(id, cx, format) {
  return cx.numbers[id].map(n => format === 'odt'
    ? { t: 'RawInline', c: ['opendocument', `<office:annotation-end office:name="cm${n}"/>`] }
    : { t: 'Span', c: [['', ['comment-end'], [['id', String(n)]]], []] }).reverse();
}

/** The comments of one open document. [col] is the margin column they are drawn in. */
class Commenting {
  constructor(editor, data, col, onChange) {
    this.editor = editor; this.col = col; this.onChange = onChange || (() => {});
    this.data = data && typeof data === 'object' ? JSON.parse(JSON.stringify(data)) : {};
    this.showResolved = localGet('ml.wcmResolved', '') === '1';
    this.active = null; this.editing = null; this.timer = 0;
    this.css = h('style'); document.head.append(this.css);
    this.head = h('div', { class: 'wcmhead' }); this.list = h('div', { class: 'wcmlist' });
    col.append(this.head, this.list);
    // Comments whose words are gone (deleted in an earlier sitting) are dropped.
    const anchors = this.anchors();
    Object.keys(this.data).forEach(id => { if (!anchors.has(id)) delete this.data[id]; });
    this.onResize = () => this.renderSoon();
    window.addEventListener('resize', this.onResize);
    this.render();
  }
  destroy() { window.removeEventListener('resize', this.onResize); this.css.remove(); }
  settings() { return this.data; }
  replaceAll(data) { this.data = data || {}; this.active = this.editing = null; this.render(); }

  /** id → the first position of its words. */
  anchors() {
    const m = new Map();
    this.editor.state.doc.descendants((n, pos) => { if (n.isText) n.marks.forEach(k => { if (k.type.name === 'comment' && !m.has(k.attrs.id)) m.set(k.attrs.id, pos); }); });
    return m;
  }
  /** The comment the cursor stands in, if any. */
  atCursor() {
    const { $from, empty } = this.editor.state.selection;
    const marks = empty ? $from.marks() : (this.editor.state.doc.nodeAt($from.pos) || { marks: [] }).marks;
    const ids = marks.filter(k => k.type.name === 'comment' && this.data[k.attrs.id] && (this.showResolved || !this.data[k.attrs.id].resolved)).map(k => k.attrs.id);
    return ids.length ? ids[ids.length - 1] : null;
  }
  selectionChanged() {
    const id = this.atCursor();
    if (id && id !== this.active) { this.active = id; this.render(); }
  }

  add() {
    const { from, to, empty } = this.editor.state.selection;
    if (empty) { toast('Select the words to comment on first'); return; }
    const author = commentAuthor(false); if (!author) return;
    const id = newCommentId();
    this.data[id] = { author, date: Date.now(), text: '', resolved: false, replies: [] };
    this.editor.view.dispatch(this.editor.state.tr.addMark(from, to, this.editor.schema.marks.comment.create({ id })));
    this.active = id; this.editing = { id, what: 'text', fresh: true };
    this.onChange(); this.render();
  }
  remove(id) {
    const st = this.editor.state;
    this.editor.view.dispatch(st.tr.removeMark(0, st.doc.content.size, st.schema.marks.comment.create({ id })));
    delete this.data[id];
    if (this.active === id) this.active = null;
    if (this.editing && this.editing.id === id) this.editing = null;
    this.onChange(); this.render();
  }
  goTo(id) {
    const pos = this.anchors().get(id); if (pos == null) return;
    this.active = id;
    this.editor.chain().setTextSelection(pos).run();
    const dom = this.editor.view.dom.querySelector(`.cm[data-cid="${id}"]`);
    if (dom) { const r = dom.getBoundingClientRect(); if (r.top < 120 || r.bottom > innerHeight - 40) dom.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
    this.render();
  }

  renderSoon() { clearTimeout(this.timer); this.timer = setTimeout(() => this.render(), 120); }
  render() {
    const anchors = this.anchors();
    const all = Object.keys(this.data).filter(id => anchors.has(id)).sort((a, b) => anchors.get(a) - anchors.get(b));
    const open = all.filter(id => !this.data[id].resolved), resolved = all.length - open.length;
    const shown = this.showResolved ? all : open;
    this.col.hidden = !all.length;
    // The words of hidden (resolved) comments are not marked; the active one is marked more strongly.
    this.css.textContent = all.filter(id => !shown.includes(id)).map(id => `.wprose .cm[data-cid="${id}"]{background:none;border-bottom:0}`).join('') +
      (this.active && shown.includes(this.active) ? `.wprose .cm[data-cid="${this.active}"]{background:#fcd34d}` : '');
    // What is being typed survives a redraw.
    const typing = this.list.querySelector('textarea[data-focus]');
    if (typing && this.editing) this.editing.draft = typing.value;
    clear(this.head).append(...[
      h('span', null, `${open.length} comment${open.length === 1 ? '' : 's'}` + (resolved ? ` · ${resolved} resolved` : '')),
      resolved ? h('label', { class: 'wcmtoggle', title: 'Show the resolved comments too' },
        h('input', { type: 'checkbox', checked: this.showResolved, onchange: e => { this.showResolved = e.target.checked; localSet('ml.wcmResolved', this.showResolved ? '1' : ''); this.render(); } }), 'Show resolved') : null,
      h('button', { class: 'wcmlink', title: 'The name on your comments', onclick: () => { commentAuthor(true); this.render(); } }, localGet('ml.wauthor', '') ? 'as ' + localGet('ml.wauthor', '') : 'Set your name')].filter(Boolean));
    clear(this.list);
    const stack = innerWidth <= 1000;
    this.col.classList.toggle('stack', stack);
    const colTop = this.list.getBoundingClientRect().top;
    let bottom = 0;
    shown.forEach(id => {
      const el = this.balloon(id);
      this.list.append(el);
      if (stack) return;
      let y = 0;
      try { y = this.editor.view.coordsAtPos(anchors.get(id)).top - colTop - 4; } catch (_) {}
      y = Math.max(y, bottom);
      el.style.top = y + 'px';
      bottom = y + el.offsetHeight + 8;
    });
    this.list.style.minHeight = stack ? '' : bottom + 'px';
    const ta = this.list.querySelector('textarea[data-focus]');
    if (ta) { ta.focus({ preventScroll: true }); ta.selectionStart = ta.selectionEnd = ta.value.length; }
  }
  when(t) {
    const d = new Date(t), now = new Date();
    return d.toDateString() === now.toDateString() ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : d.toLocaleDateString([], { day: 'numeric', month: 'short', year: d.getFullYear() === now.getFullYear() ? undefined : 'numeric' });
  }
  /** A text box that saves on Enter (Shift+Enter: new line) and gives up on Escape. */
  box(value, placeholder, onSave, onCancel) {
    const ta = h('textarea', { rows: 2, placeholder, 'data-focus': '1' }); ta.value = this.editing && this.editing.draft != null ? this.editing.draft : value || '';
    const done = () => { const v = ta.value.trim(); this.editing = null; onSave(v); this.onChange(); this.render(); };
    const cancel = () => { this.editing = null; if (onCancel) onCancel(); this.render(); };
    ta.addEventListener('keydown', e => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); done(); }
      if (e.key === 'Escape') { e.preventDefault(); cancel(); }
    });
    ta.addEventListener('input', () => { ta.style.height = 'auto'; ta.style.height = ta.scrollHeight + 2 + 'px'; });
    return h('div', { class: 'wcmbox' }, ta, h('div', { class: 'row', style: { gap: '4px', justifyContent: 'flex-end' } },
      h('button', { class: 'btn quiet', onclick: cancel }, 'Cancel'), h('button', { class: 'btn fill', onclick: done }, 'Save')));
  }
  balloon(id) {
    const c = this.data[id], on = this.active === id, ed = this.editing && this.editing.id === id ? this.editing : null;
    const el = h('div', { class: 'wcm' + (on ? ' on' : '') + (c.resolved ? ' resolved' : ''), onclick: e => { if (e.target.closest('button, textarea, input')) return; if (!on) this.goTo(id); } });
    const entry = (x, i) => {
      const editingThis = ed && (i < 0 ? ed.what === 'text' : ed.what === 'reply' && ed.i === i);
      return h('div', { class: 'wcmentry' },
        h('div', { class: 'wcmwho' }, h('b', null, x.author || 'Unknown'), h('span', { class: 'muted' }, this.when(x.date)), h('span', { style: { flex: 1 } }),
          on && !editingThis ? h('button', { class: 'wcmlink', title: 'Change the text', onclick: () => { this.editing = i < 0 ? { id, what: 'text' } : { id, what: 'reply', i }; this.render(); } }, 'Edit') : null,
          on && i >= 0 && !editingThis ? h('button', { class: 'wcmlink', title: 'Delete this reply', onclick: () => { c.replies.splice(i, 1); this.onChange(); this.render(); } }, 'Delete') : null),
        editingThis
          ? this.box(x.text, i < 0 ? 'Write a comment…' : 'Reply…', v => { if (!v && i < 0 && ed.fresh) { this.editing = null; this.remove(id); return; } x.text = v; }, () => { if (i < 0 && ed.fresh && !x.text) this.remove(id); })
          : h('div', { class: 'wcmtext' }, x.text || h('i', { class: 'muted' }, '(empty)')));
    };
    el.append(entry(c, -1), ...(c.replies || []).map((r, i) => entry(r, i)));
    if (ed && ed.what === 'newreply') el.append(this.box('', 'Reply…', v => { if (v) c.replies.push({ author: commentAuthor(false) || 'Unknown', date: Date.now(), text: v }); }));
    else if (on && !ed) el.append(h('div', { class: 'wcmacts' },
      h('button', { class: 'wcmlink', onclick: () => { if (!commentAuthor(false)) return; this.editing = { id, what: 'newreply' }; this.render(); } }, 'Reply'),
      h('button', { class: 'wcmlink', title: c.resolved ? 'Open the comment again' : 'Mark as dealt with (it is hidden, not deleted)', onclick: () => { c.resolved = !c.resolved; if (c.resolved && !this.showResolved) this.active = null; this.onChange(); this.render(); } }, c.resolved ? 'Reopen' : 'Resolve'),
      h('button', { class: 'wcmlink', title: 'Delete the comment and its replies', onclick: () => { if (!(c.replies || []).length || confirm('Delete this comment and its replies?')) this.remove(id); } }, 'Delete')));
    return el;
  }
}
