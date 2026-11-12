/* Marginalia Lectoris — citations for the writing program.
 *
 * A citation is a small node in the text holding its sources (with page, prefix, suffix); the text
 * shown is made by citeproc-js (the engine Zotero uses) in the chosen CSL style. The bibliography is
 * a block that lists everything cited. The sources' data (CSL-JSON) is kept in the document, so it
 * works without the library and travels with the document.
 *
 * On export to Word and LibreOffice the citations and the bibliography are written as Zotero fields,
 * so the Zotero plug-in there can still refresh them or change the style.
 */

const ensureCiteproc = () => loadScript('vendor/citeproc.bundle.js').then(() => window.CSL);
const CITE_LOCATORS = [['page', 'page'], ['chapter', 'chapter'], ['section', 'section'], ['paragraph', 'paragraph'], ['line', 'line'], ['figure', 'figure'], ['note', 'note'], ['volume', 'volume'], ['column', 'column']];
const CITE_LOCALES = [['', 'The style’s own language'], ['en-US', 'English (US)'], ['en-GB', 'English (UK)'], ['nb-NO', 'Norsk bokmål'], ['nn-NO', 'Norsk nynorsk'], ['sv-SE', 'Svenska'], ['da-DK', 'Dansk'], ['de-DE', 'Deutsch'], ['fr-FR', 'Français'], ['es-ES', 'Español'], ['nl-NL', 'Nederlands'], ['it-IT', 'Italiano']];
const newCiteId = () => 'c' + Math.random().toString(36).slice(2, 10);
const xmlEsc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** citeproc's HTML → plain runs with formatting: [{ text, i, b, sc, sup, sub }]. */
function htmlSegments(html) {
  const out = [];
  const doc = new DOMParser().parseFromString('<div>' + (html || '') + '</div>', 'text/html');
  const walk = (node, f) => {
    if (node.nodeType === 3) { if (node.nodeValue) out.push(Object.assign({ text: node.nodeValue }, f)); return; }
    if (node.nodeType !== 1) return;
    const tag = node.tagName.toLowerCase(), st = (node.getAttribute('style') || '').toLowerCase();
    const g = Object.assign({}, f);
    if (tag === 'i' || tag === 'em' || /font-style:\s*italic/.test(st)) g.i = true;
    if (/font-style:\s*normal/.test(st)) g.i = false;
    if (tag === 'b' || tag === 'strong' || /font-weight:\s*bold/.test(st)) g.b = true;
    if (/small-caps/.test(st)) g.sc = true;
    if (tag === 'sup') g.sup = true;
    if (tag === 'sub') g.sub = true;
    const blockish = /csl-(left-margin|right-inline|indent|block)/.test(node.className || '');
    node.childNodes.forEach(c => walk(c, g));
    if (blockish && out.length && !/\s$/.test(out[out.length - 1].text)) out.push({ text: ' ' });
  };
  doc.body.firstChild.childNodes.forEach(c => walk(c, {}));
  return out;
}
const segText = segs => segs.map(s => s.text).join('');
function segInlines(segs) {
  return segs.flatMap(s => {
    let inl = textToInlines(s.text);
    if (s.sc) inl = [{ t: 'SmallCaps', c: inl }];
    if (s.sup) inl = [{ t: 'Superscript', c: inl }];
    if (s.sub) inl = [{ t: 'Subscript', c: inl }];
    if (s.b) inl = [{ t: 'Strong', c: inl }];
    if (s.i) inl = [{ t: 'Emph', c: inl }];
    return inl;
  });
}
function segRunsXml(segs) {
  return segs.map(s => {
    const pr = (s.i ? '<w:i/>' : '') + (s.b ? '<w:b/>' : '') + (s.sc ? '<w:smallCaps/>' : '') + (s.sup ? '<w:vertAlign w:val="superscript"/>' : s.sub ? '<w:vertAlign w:val="subscript"/>' : '');
    return `<w:r>${pr ? '<w:rPr>' + pr + '</w:rPr>' : ''}<w:t xml:space="preserve">${xmlEsc(s.text)}</w:t></w:r>`;
  }).join('');
}
function segOdt(segs) {
  return segs.map(s => s.i ? `<text:span text:style-name="Emphasis">${xmlEsc(s.text)}</text:span>` : s.b ? `<text:span text:style-name="Strong_20_Emphasis">${xmlEsc(s.text)}</text:span>` : xmlEsc(s.text)).join('');
}

class Citing {
  constructor(T, settings, onChange) {
    this.T = T;
    this.onChange = onChange || (() => {});
    const s = settings || {};
    this.style = s.style || null;          // { name, id, title, cls }
    this.locale = s.locale || '';
    this.items = s.items || {};            // id → CSL-JSON
    this.uris = s.uris || {};              // id → Zotero URI
    this.html = new Map();                 // citationID → formatted HTML
    this.noteNo = new Map();               // citationID → note number (note styles)
    this.bib = [];                         // bibliography entries (HTML)
    this.views = new Set();
    this.engine = null; this.styleXml = null; this.styleCls = 'in-text'; this.locales = {};
    this.ready = this.load();
    const self = this;
    this.extensions = [this.citationNode(false), this.bibliographyNode()];
    this.noteExtensions = [this.citationNode(true)];
  }
  settings() { return { style: this.style, locale: this.locale, items: this.items, uris: this.uris }; }
  get isNote() { return this.styleCls === 'note'; }
  get styleLabel() { return this.style ? (this.style.title || this.style.name) : 'APA 7th'; }

  async load() {
    await ensureCiteproc();
    const name = (this.style && this.style.name) || 'apa';
    const st = await api('writing/style?name=' + encodeURIComponent(name));
    if (!this.style) this.style = { name: st.name, id: st.id, title: st.title, cls: st.class };
    this.styleXml = st.xml; this.styleCls = st.class; this.styleId = st.id; this.hasBib = st.hasBibliography;
    const lang = this.locale || st.defaultLocale || 'en-US';
    for (const l of new Set([lang, 'en-US'])) {
      if (this.locales[l]) continue;
      try { const r = await fetch('/api/writing/locale?lang=' + encodeURIComponent(l), { credentials: 'same-origin' }); if (r.ok) this.locales[l] = await r.text(); } catch (_) {}
    }
    const sys = {
      retrieveLocale: l => this.locales[l] || this.locales[l.split('-')[0]] || this.locales['en-US'],
      retrieveItem: id => this.items[id] || { id, type: 'document', title: '(missing source)' },
    };
    this.engine = new CSL.Engine(sys, this.styleXml, lang, true);
    this.lang = lang;
  }
  async setStyle(name, locale) {
    this.style = null; if (locale !== undefined) this.locale = locale;
    this.style = { name };
    const st = await api('writing/style?name=' + encodeURIComponent(name));
    this.style = { name: st.name, id: st.id, title: st.title, cls: st.class };
    this.ready = this.load();
    await this.ready;
    this.onChange();
  }

  // ----- the nodes
  citationNode(inNote) {
    const self = this, T = this.T;
    return T.Node.create({
      name: 'citation', group: 'inline', inline: true, atom: true, selectable: true,
      addAttributes() {
        return {
          citationID: { default: null, parseHTML: el => el.getAttribute('data-id'), renderHTML: a => ({ 'data-id': a.citationID }) },
          items: { default: [], parseHTML: el => { try { return JSON.parse(el.getAttribute('data-items') || '[]'); } catch (_) { return []; } }, renderHTML: a => ({ 'data-items': JSON.stringify(a.items || []) }) },
        };
      },
      parseHTML() { return [{ tag: 'span[data-citation]' }]; },
      renderHTML({ HTMLAttributes }) { return ['span', T.mergeAttributes(HTMLAttributes, { 'data-citation': '' })]; },
      addNodeView() {
        return ({ node, getPos, editor }) => {
          const dom = document.createElement('span');
          dom.contentEditable = 'false';
          const v = { node, dom, inNote, render() {
            const id = v.node.attrs.citationID;
            const html = self.html.get(id);
            if (self.isNote && !inNote) { dom.className = 'cite fn citenote'; dom.innerHTML = ''; dom.title = html ? new DOMParser().parseFromString(html, 'text/html').body.textContent : 'Citation'; }
            else { dom.className = 'cite'; dom.innerHTML = html || xmlEsc(self.fallback(v.node.attrs)); dom.title = 'Citation — click to change'; }
          } };
          dom.addEventListener('click', e => { e.preventDefault(); if (typeof getPos === 'function') self.edit(editor, getPos(), v.node.attrs); });
          self.views.add(v); v.render();
          return { dom, update: n => { if (n.type.name !== 'citation') return false; v.node = n; v.render(); return true; }, destroy: () => self.views.delete(v), ignoreMutation: () => true };
        };
      },
    });
  }
  bibliographyNode() {
    const self = this, T = this.T;
    return T.Node.create({
      name: 'bibliography', group: 'block', atom: true, selectable: true,
      parseHTML() { return [{ tag: 'div[data-bibliography]' }]; },
      renderHTML({ HTMLAttributes }) { return ['div', T.mergeAttributes(HTMLAttributes, { 'data-bibliography': '' })]; },
      addNodeView() {
        return () => {
          const dom = document.createElement('div');
          dom.contentEditable = 'false';
          const v = { bib: true, dom, render() {
            dom.className = 'wbib' + (self.bibParams && self.bibParams.hangingindent ? ' hanging' : '');
            dom.innerHTML = self.bib.length ? self.bib.join('') : '<div class="muted">The bibliography appears here when you cite something.</div>';
            dom.title = 'Bibliography (made from your citations)';
          } };
          self.views.add(v); v.render();
          return { dom, destroy: () => self.views.delete(v), ignoreMutation: () => true };
        };
      },
    });
  }
  fallback(a) {
    return '(' + (a.items || []).map(i => { const c = this.items[i.id]; const n = c && (c.author || c.editor || [])[0]; return (n ? (n.family || n.literal) : (c && c.title) || '?') + (i.locator ? ', ' + i.locator : ''); }).join('; ') + ')';
  }

  // ----- formatting every citation (in order, so "ibid." and the like come out right)
  /** All citations in the document in reading order: { attrs, noteIndex, inNote }. */
  collect(editor) {
    const out = []; let note = 0; const isNote = this.isNote;
    const walkJson = (n, f) => { if (!n) return; if (n.type === 'citation') f(n.attrs); (n.content || []).forEach(c => walkJson(c, f)); };
    editor.state.doc.descendants(n => {
      if (n.type.name === 'footnote') { note++; walkJson(n.attrs.content, a => out.push({ attrs: a, noteIndex: note, inNote: true })); return false; }
      if (n.type.name === 'citation') { if (isNote) note++; out.push({ attrs: n.attrs, noteIndex: isNote ? note : 0, inNote: false }); }
      return true;
    });
    return out;
  }
  /** Gives every citation its own id (pasted copies share one). */
  fixIds(editor) {
    const seen = new Set(); const fixes = [];
    editor.state.doc.descendants((n, pos) => { if (n.type.name === 'citation') { const id = n.attrs.citationID; if (!id || seen.has(id)) fixes.push(pos); else seen.add(id); } });
    if (!fixes.length) return;
    const tr = editor.state.tr;
    fixes.forEach(pos => { const n = tr.doc.nodeAt(pos); tr.setNodeMarkup(pos, null, Object.assign({}, n.attrs, { citationID: newCiteId() })); });
    tr.setMeta('addToHistory', false);
    editor.view.dispatch(tr);
  }
  async recompute(editor) {
    try { await this.ready; } catch (e) { console.warn('Citation style', e); return; }
    if (!this.engine || !editor || editor.isDestroyed) return;
    this.fixIds(editor);
    const cits = this.collect(editor).filter(c => c.attrs.items && c.attrs.items.length && c.attrs.citationID);
    const data = cits.map(c => ({ citationID: c.attrs.citationID, citationItems: c.attrs.items.map(i => this.citeItem(i)), properties: { noteIndex: c.noteIndex } }));
    this.html.clear(); this.noteNo.clear();
    try {
      const res = this.engine.rebuildProcessorState(data, 'html');
      res.forEach(([id, ni, html]) => { this.html.set(id, html); this.noteNo.set(id, ni); });
      const bib = this.hasBib && data.length ? this.engine.makeBibliography() : null;
      this.bib = bib ? bib[1] : []; this.bibParams = bib ? bib[0] : null;
    } catch (e) { console.warn('citeproc', e); }
    this.views.forEach(v => v.render());
  }
  citeItem(i) {
    const o = { id: i.id };
    if (i.locator) { o.locator = i.locator; o.label = i.label || 'page'; }
    if (i.prefix) o.prefix = i.prefix;
    if (i.suffix) o.suffix = i.suffix;
    if (i.suppressAuthor) o['suppress-author'] = true;
    return o;
  }
  /** The citation's text (plain), e.g. for the footnotes list. */
  text(attrs) { const h = this.html.get(attrs.citationID); return h ? new DOMParser().parseFromString(h, 'text/html').body.textContent : this.fallback(attrs); }

  // ----- inserting and changing a citation
  edit(editor, pos, attrs) { this.dialog(editor, pos, attrs); }
  insert(editor) { this.dialog(editor, null, null); }
  dialog(editor, pos, attrs, inNoteEditor) {
    const self = this;
    let chosen = (attrs && attrs.items || []).map(i => Object.assign({}, i));
    modal((box, close) => {
      box.classList.add('wide');
      const q = h('input', { type: 'search', placeholder: 'Search your library and Zotero: author, title, year…' });
      const results = h('div', { class: 'cresults' }, h('div', { class: 'muted' }, 'Type to search.'));
      const sel = h('div', { class: 'cchosen' });
      const preview = h('div', { class: 'cpreview' });
      let seq = 0, timer = 0;
      const search = async () => {
        const my = ++seq; const s = q.value.trim();
        if (s.length < 2) { clear(results).append(h('div', { class: 'muted' }, 'Type to search.')); return; }
        let r; try { r = await api('writing/cite-search?q=' + encodeURIComponent(s)); } catch (e) { fail(e); return; }
        if (my !== seq) return;
        clear(results);
        if (!r.length) results.append(h('div', { class: 'muted' }, 'Nothing found in the library or Zotero.'));
        r.forEach(x => results.append(h('button', { class: 'cres', onclick: () => {
          self.items[x.id] = x.csl; if (x.uri) self.uris[x.id] = x.uri;
          if (!chosen.some(c => c.id === x.id)) chosen.push({ id: x.id, locator: '', label: 'page', prefix: '', suffix: '' });
          drawChosen(); q.select();
        } }, h('span', { class: 'cbadge ' + x.source }, x.source === 'zotero' ? 'Zotero' : x.source === 'chapter' ? 'Chapter' : 'Library'),
          h('b', null, x.label || '—'), ' ', h('span', null, x.title), x.container ? h('span', { class: 'muted' }, ' · ' + x.container) : null)));
      };
      q.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(search, 250); });
      q.addEventListener('keydown', e => { if (e.key === 'Enter') { const first = results.querySelector('.cres'); if (first) first.click(); } });
      const drawPreview = async () => {
        clear(preview);
        if (!chosen.length) return;
        try {
          await self.ready;
          const cit = { citationID: 'preview', citationItems: chosen.map(i => self.citeItem(i)), properties: { noteIndex: self.isNote ? 1 : 0 } };
          const html = self.engine.previewCitationCluster(cit, [], [], 'html');
          preview.append(h('span', { class: 'muted' }, 'Preview: '), h('span', { html }));
        } catch (_) {}
      };
      const drawChosen = () => {
        clear(sel);
        if (!chosen.length) sel.append(h('div', { class: 'muted' }, 'No sources chosen yet. Pick one or more from the search.'));
        chosen.forEach((c, k) => {
          const it = self.items[c.id] || {};
          const n = (it.author || it.editor || [])[0];
          const loc = h('input', { value: c.locator || '', placeholder: 'e.g. 276 or 12–15', style: { width: '120px' } });
          const lab = h('select', null, CITE_LOCATORS.map(([v, l]) => h('option', { value: v, selected: (c.label || 'page') === v }, l)));
          const pre = h('input', { value: c.prefix || '', placeholder: 'before, e.g. see', style: { width: '120px' } });
          const suf = h('input', { value: c.suffix || '', placeholder: 'after', style: { width: '120px' } });
          const sup = h('input', { type: 'checkbox', checked: !!c.suppressAuthor });
          const upd = () => { c.locator = loc.value.trim(); c.label = lab.value; c.prefix = pre.value; c.suffix = suf.value; c.suppressAuthor = sup.checked; drawPreview(); };
          [loc, lab, pre, suf, sup].forEach(x => x.addEventListener('input', upd));
          sup.addEventListener('change', upd);
          sel.append(h('div', { class: 'cchose' },
            h('div', { class: 'ctitle' }, h('b', null, n ? (n.family || n.literal) : ''), ' ', it.issued && it.issued['date-parts'] ? '(' + it.issued['date-parts'][0][0] + ') ' : '', it.title || c.id),
            h('div', { class: 'row', style: { gap: '6px', flexWrap: 'wrap', alignItems: 'center' } }, lab, loc, pre, suf,
              h('label', { class: 'row', style: { gap: '4px', fontSize: '12px' } }, sup, 'Omit author'),
              k > 0 ? h('button', { class: 'btn quiet', title: 'Move up', onclick: () => { chosen.splice(k - 1, 0, chosen.splice(k, 1)[0]); drawChosen(); } }, '↑') : null,
              h('button', { class: 'btn quiet', onclick: () => { chosen.splice(k, 1); drawChosen(); } }, 'Remove'))));
        });
        drawPreview();
      };
      const done = () => {
        if (!chosen.length) { toast('Choose at least one source'); return; }
        const items = chosen.map(c => { const o = { id: c.id }; ['locator', 'label', 'prefix', 'suffix', 'noteId'].forEach(k => { if (c[k]) o[k] = c[k]; }); if (c.suppressAuthor) o.suppressAuthor = true; return o; });
        if (pos != null) editor.chain().setNodeSelection(pos).updateAttributes('citation', { items }).setTextSelection(pos + 1).focus().run();
        else {
          const at = editor.state.selection.to;
          editor.chain().focus().insertContentAt(at, { type: 'citation', attrs: { citationID: newCiteId(), items } }).setTextSelection(at + 1).run();
          self.ensureBibliography(editor);
        }
        close(); self.onChange(); self.recompute(editor);
      };
      box.append(h('h2', null, pos != null ? 'Change citation' : 'Insert citation'),
        h('div', { class: 'muted', style: { fontSize: '12px', marginBottom: '6px' } }, 'Style: ' + self.styleLabel + ' — sources come from your library (books, articles, chapters of edited volumes) and your Zotero library.'),
        q, results, h('div', { class: 'label', style: { marginTop: '10px' } }, 'In this citation'), sel, preview,
        h('div', { class: 'foot' },
          pos != null ? h('button', { class: 'btn quiet', onclick: () => { editor.chain().setNodeSelection(pos).deleteSelection().focus().run(); close(); self.recompute(editor); } }, 'Remove citation') : null,
          h('span', { style: { flex: 1 } }),
          h('button', { class: 'btn', onclick: close }, 'Cancel'),
          h('button', { class: 'btn fill', onclick: done }, pos != null ? 'Update' : 'Insert')));
      drawChosen();
      setTimeout(() => q.focus(), 30);
    });
  }
  /** A bibliography at the end (with a heading) the first time something is cited. */
  ensureBibliography(editor) {
    let has = false;
    editor.state.doc.descendants(n => { if (n.type.name === 'bibliography') has = true; return !has; });
    if (has || !this.hasBib) return;
    const end = editor.state.doc.content.size;
    const head = /^(nb|nn|no)/.test(this.lang || '') ? 'Litteratur' : 'References';
    editor.chain().insertContentAt(end, [{ type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: head }] }, { type: 'bibliography' }], { updateSelection: false }).run();
  }
  insertBibliography(editor) {
    let has = false;
    editor.state.doc.descendants(n => { if (n.type.name === 'bibliography') has = true; return !has; });
    if (has) { toast('There is already a bibliography in the document'); return; }
    editor.chain().focus().insertContent({ type: 'bibliography' }).run();
    this.recompute(editor);
  }

  // ----- choosing the style
  async pickStyle() {
    const self = this;
    let index = null;
    modal(async (box, close) => {
      box.classList.add('wide');
      const q = h('input', { type: 'search', placeholder: 'Search 10,000+ styles: APA, Chicago, Harvard, a journal name…' });
      const list = h('div', { class: 'cstyles' }, h('div', { class: 'muted' }, 'Loading the list of styles…'));
      const loc = h('select', null, CITE_LOCALES.map(([v, l]) => h('option', { value: v, selected: (self.locale || '') === v }, l)));
      let recent = []; try { recent = JSON.parse(localGet('ml.cslRecent', '[]')) || []; } catch (_) {}
      const choose = async (name, title) => {
        try {
          toast('Loading the style…', 0);
          await self.setStyle(name, loc.value);
          recent = [{ name, title }, ...recent.filter(r => r.name !== name)].slice(0, 8); localSet('ml.cslRecent', JSON.stringify(recent));
          toast('Style: ' + (self.style.title || name), 1500); close();
        } catch (e) { fail(e); }
      };
      const draw = () => {
        clear(list);
        const s = q.value.trim().toLowerCase();
        if (!s && recent.length) { list.append(h('div', { class: 'label' }, 'Recently used')); recent.forEach(r => list.append(h('button', { class: 'cres', onclick: () => choose(r.name, r.title) }, h('b', null, r.title || r.name)))); }
        if (!index) return;
        const popular = ['apa', 'chicago-author-date', 'chicago-notes-bibliography', 'chicago-note-bibliography', 'chicago-shortened-notes-bibliography', 'harvard-cite-them-right', 'modern-language-association', 'american-political-science-association', 'vancouver', 'ieee', 'norsk-apa-manual', 'nature'];
        const hits = s ? index.filter(x => (x.title + ' ' + x.name).toLowerCase().includes(s)).slice(0, 80) : popular.map(p => index.find(x => x.name === p)).filter(Boolean);
        if (!s) list.append(h('div', { class: 'label' }, 'Common styles'));
        hits.forEach(x => list.append(h('button', { class: 'cres' + (self.style && self.style.name === x.name ? ' on' : ''), onclick: () => choose(x.name, x.title) },
          h('b', null, x.title), ' ', h('span', { class: 'muted mono', style: { fontSize: '11px' } }, x.name + (x.format ? ' · ' + x.format : '')))));
        if (s && !hits.length) list.append(h('div', { class: 'muted' }, 'No style matches.'));
      };
      q.addEventListener('input', draw);
      box.append(h('h2', null, 'Citation style'),
        h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Now: ' + self.styleLabel + '. Styles come from the Zotero style repository and are kept on the tablet once used.'),
        q, list, h('div', { class: 'row', style: { gap: '8px', alignItems: 'center', marginTop: '8px' } }, h('span', { class: 'label' }, 'Language of citations'), loc,
          h('button', { class: 'btn quiet', onclick: async () => { try { await self.setStyle(self.style ? self.style.name : 'apa', loc.value); toast('Language changed', 1200); } catch (e) { fail(e); } } }, 'Apply')),
        h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Close')));
      draw();
      try {
        const raw = await api('writing/styles-index', { timeout: 60000 });
        index = (Array.isArray(raw) ? raw : raw.styles || []).map(x => ({ name: x.name || (x.href || '').split('/').pop(), title: x.title || x.name, format: x.categories && x.categories.format || '' })).filter(x => x.name);
        draw();
      } catch (e) { clear(list).append(h('div', { class: 'muted' }, 'Could not load the list of styles: ' + e.message)); }
    });
  }

  // ----- export
  /** The Zotero field data of a citation. */
  fieldJson(attrs, noteIndex) {
    const text = this.text(attrs);
    return JSON.stringify({
      citationID: attrs.citationID,
      properties: { formattedCitation: this.html.get(attrs.citationID) || text, plainCitation: text, noteIndex: noteIndex || 0 },
      citationItems: attrs.items.map(i => {
        const o = Object.assign({ id: i.id }, this.citeItem(i));
        if (this.uris[i.id]) o.uris = [this.uris[i.id]];
        o.itemData = this.items[i.id] || { id: i.id, type: 'document', title: i.id };
        return o;
      }),
      schema: 'https://github.com/citation-style-language/schema/raw/master/csl-citation.json',
    });
  }
  /** Pandoc inlines for a citation, in the format being written. */
  citationInlines(attrs, format, inNote) {
    const segs = htmlSegments(this.html.get(attrs.citationID) || xmlEsc(this.fallback(attrs)));
    const ni = this.noteNo.get(attrs.citationID) || 0;
    let inl;
    if (format === 'docx') {
      const json = this.fieldJson(attrs, ni);
      inl = [{ t: 'RawInline', c: ['openxml', `<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> ADDIN ZOTERO_ITEM CSL_CITATION ${xmlEsc(json)} </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>${segRunsXml(segs)}<w:r><w:fldChar w:fldCharType="end"/></w:r>`] }];
    } else if (format === 'odt') {
      const name = xmlEsc(`ZOTERO_ITEM CSL_CITATION ${this.fieldJson(attrs, ni)} RND${Math.random().toString(36).slice(2, 12)}`);
      inl = [{ t: 'RawInline', c: ['opendocument', `<text:reference-mark-start text:name="${name}"/>${segOdt(segs)}<text:reference-mark-end text:name="${name}"/>`] }];
    } else inl = segInlines(segs);
    // In a note style a citation in the text becomes a footnote of its own.
    return this.isNote && !inNote ? [{ t: 'Note', c: [{ t: 'Para', c: inl }] }] : inl;
  }
  /** Pandoc blocks for the bibliography. */
  bibliographyBlocks(format) {
    const entries = this.bib.map(htmlSegments);
    if (!entries.length) return [];
    const code = '{"uncited":[],"omitted":[],"custom":[]}';
    if (format === 'docx') {
      const p = (inner, first, last) => `<w:p><w:pPr><w:pStyle w:val="Bibliography"/>${this.bibParams && this.bibParams.hangingindent ? '<w:ind w:left="720" w:hanging="720"/>' : ''}</w:pPr>${first ? `<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> ADDIN ZOTERO_BIBL ${xmlEsc(code)} CSL_BIBLIOGRAPHY </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>` : ''}${inner}${last ? '<w:r><w:fldChar w:fldCharType="end"/></w:r>' : ''}</w:p>`;
      return [{ t: 'RawBlock', c: ['openxml', entries.map((e, k) => p(segRunsXml(e), k === 0, k === entries.length - 1)).join('')] }];
    }
    if (format === 'odt') {
      const name = xmlEsc(`ZOTERO_BIBL ${code} CSL_BIBLIOGRAPHY RND${Math.random().toString(36).slice(2, 12)}`);
      return [{ t: 'RawBlock', c: ['opendocument', `<text:section text:name="${name}">${entries.map(e => `<text:p text:style-name="Text_20_body">${segOdt(e)}</text:p>`).join('')}</text:section>`] }];
    }
    return entries.map(e => ({ t: 'Para', c: segInlines(e) }));
  }
  /** Zotero's document preferences (style and field type), written into the Word/LibreOffice file. */
  prefsData(fieldType) {
    const id = (this.style && this.style.id) || this.styleId || 'http://www.zotero.org/styles/apa';
    return `<data data-version="3" zotero-version="7.0"><session id="${Math.random().toString(36).slice(2, 10)}"/><style id="${xmlEsc(id)}" locale="${xmlEsc(this.lang || 'en-US')}" hasBibliography="${this.hasBib ? 1 : 0}" bibliographyStyleHasBeenSet="1"/><prefs><pref name="fieldType" value="${fieldType}"/></prefs></data>`;
  }
  async addZoteroPrefs(blob, ext) {
    if (!window.JSZip) return blob;
    const zip = await JSZip.loadAsync(blob);
    const chunks = this.prefsData(ext === 'docx' ? 'Field' : 'ReferenceMark').match(/[\s\S]{1,240}/g);
    if (ext === 'docx') {
      let custom = zip.file('docProps/custom.xml') ? await zip.file('docProps/custom.xml').async('string') : null;
      if (!custom) {
        custom = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"></Properties>';
        let ct = await zip.file('[Content_Types].xml').async('string');
        if (!ct.includes('/docProps/custom.xml')) ct = ct.replace('</Types>', '<Override PartName="/docProps/custom.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/></Types>');
        zip.file('[Content_Types].xml', ct);
        let rels = await zip.file('_rels/.rels').async('string');
        if (!rels.includes('docProps/custom.xml')) rels = rels.replace('</Relationships>', '<Relationship Id="rIdZoteroCustom" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties" Target="docProps/custom.xml"/></Relationships>');
        zip.file('_rels/.rels', rels);
      }
      custom = custom.replace(/<property[^>]*name="ZOTERO_PREF_\d+"[\s\S]*?<\/property>/g, '');
      let pid = Math.max(1, ...[...custom.matchAll(/pid="(\d+)"/g)].map(m => +m[1]));
      const props = chunks.map((c, i) => `<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="${++pid}" name="ZOTERO_PREF_${i + 1}"><vt:lpwstr>${xmlEsc(c)}</vt:lpwstr></property>`).join('');
      custom = custom.replace(/<\/Properties>\s*$/, props + '</Properties>').replace(/<Properties([^>]*)\/>/, `<Properties$1>${props}</Properties>`);
      zip.file('docProps/custom.xml', custom);
    } else {
      let meta = zip.file('meta.xml') ? await zip.file('meta.xml').async('string') : null;
      if (meta) {
        const props = chunks.map((c, i) => `<meta:user-defined meta:name="ZOTERO_PREF_${i + 1}">${xmlEsc(c)}</meta:user-defined>`).join('');
        meta = meta.includes('</office:meta>') ? meta.replace('</office:meta>', props + '</office:meta>') : meta.replace(/<office:meta\s*\/>/, `<office:meta>${props}</office:meta>`);
        zip.file('meta.xml', meta);
      }
      // LibreOffice wants "mimetype" first and uncompressed.
      const mt = zip.file('mimetype'); if (mt) zip.file('mimetype', await mt.async('string'), { compression: 'STORE' });
    }
    return zip.generateAsync({ type: 'blob', mimeType: blob.type, compression: 'DEFLATE' });
  }
}
