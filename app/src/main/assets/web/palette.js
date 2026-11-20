/* Marginalia Lectoris — the command palette (Ctrl+K) and the list of keyboard shortcuts (?).
 *
 * One box to get anywhere: type a few letters of a book, an article, a note or a writing document
 * to open it, or of an action ("dark", "tabs", "settings", "new document") to do it.
 */

let paletteData = null;   // { books, notes, docs } fetched when the palette first opens
async function paletteLoad(force) {
  if (paletteData && !force) return paletteData;
  const [books, notes, docs] = await Promise.all([
    loadLibrary().catch(() => []),
    (notesCache && notesCache.list ? Promise.resolve({ highlights: notesCache.list }) : api('highlights')).catch(() => ({ highlights: [] })),
    api('writing').catch(() => [])]);
  paletteData = { books: books || [], notes: notes.highlights || [], docs: Array.isArray(docs) ? docs : [] };
  return paletteData;
}
/** What can be done from anywhere. [words] are extra words that find it. */
function paletteActions() {
  const go = hash => () => { location.hash = hash; };
  const theme = t => () => { try { localStorage.setItem('ml.theme', t); } catch (_) {} applyTheme(); if (typeof route === 'function' && !workspace && document.querySelector('header.top')) route(); };
  const list = [
    ['Library', 'Go to', go('#/'), 'home all books articles'], ['Books', 'Go to', go('#/books'), ''], ['Articles', 'Go to', go('#/articles'), ''],
    ['Take notes here (from the tablet)', 'Switch', () => relaySet(!relay.on), 'relay companion tablet highlight note dialog computer'],
    ['Tablet screen', 'Go to', go('#/screen'), 'remote control mirror see tablet display'],
    ['Search in the texts', 'Search', () => { if (window.paletteTextMode) window.paletteTextMode(); }, 'find full text words inside books articles pdf ctrl+f', true],
    ['Notes', 'Go to', go('#/notes'), 'vault highlights'], ['Writing', 'Go to', go('#/writing'), 'documents editor'], ['Lexicon', 'Go to', go('#/lexicon'), 'words vocabulary definitions dictionary glossary'], ['Literature matrices', 'Go to', go('#/matrix'), 'matrix table compare sources synthesis'], ['Search in the texts', 'Go to', go('#/search'), 'full text find'],
    ['Zotero', 'Go to', go('#/zotero'), ''], ['Presentations', 'Go to', go('#/presentations'), 'slides'], ['Settings', 'Go to', go('#/settings'), 'preferences backup ai'],
    ['New writing document', 'Do', async () => { try { const d = await api('writing', { method: 'POST', json: { title: 'Untitled' } }); location.hash = '#/write/' + encodeURIComponent(d.id) + '?new=1'; } catch (e) { fail(e); } }, 'create write'],
    ['Colours: dark', 'Do', theme('dark'), 'theme night mode'], ['Colours: light', 'Do', theme('light'), 'theme day mode'], ['Colours: follow the computer', 'Do', theme('auto'), 'theme auto'],
    ['Convert web pages to EPUB or PDF', 'Do', () => convertDialog(), 'html singlefile sage oxford cambridge idunn chapter ebook epub pdf page numbers'],
    ['Keyboard shortcuts', 'Help', () => shortcutsDialog(), 'keys help'],
    ['Sign out', 'Do', async () => { try { await api('logout', { method: 'POST' }); } catch (_) {} showLogin(); }, 'log out'],
  ];
  if (!EMBEDDED && !workspace) list.push(['Tabs: open several things side by side', 'Do', () => { localSet('ml.tabs', '1'); showWorkspace(location.hash || '#/'); }, 'split panes workspace']);
  if (EMBEDDED) list.push(['Open the library in a new tab', 'Do', () => parent.postMessage({ ml: 'open', hash: '#/' }, location.origin), 'tab'],
    ['Open the notes to the side', 'Do', () => parent.postMessage({ ml: 'open', hash: '#/notes', side: true }, location.origin), 'split tab'],
    ['Open writing to the side', 'Do', () => parent.postMessage({ ml: 'open', hash: '#/writing', side: true }, location.origin), 'split tab']);
  // Things that belong to what is open (the reader and the writer add theirs here).
  (window.paletteContext || []).forEach(a => list.unshift(a));
  return list.map(([title, kind, run, words, keepOpen]) => ({ title, kind, run, keepOpen: !!keepOpen, hay: norm(title + ' ' + (words || '')) }));
}
/** Opens [hash] in a new tab, or in the other pane; switches the tabs on when they are off. */
function paletteOpenTab(hash, side) {
  if (EMBEDDED) { parent.postMessage({ ml: 'open', hash, side: !!side }, location.origin); return; }
  let st = {}; try { st = JSON.parse(localGet('ml.tabsState', '{}')) || {}; } catch (_) {}
  const tabs = Array.isArray(st.tabs) ? st.tabs : [];
  if (!tabs.length) tabs.push({ hash: location.hash || '#/', pane: 0 });
  tabs.push({ hash, pane: side ? 1 : 0 });
  const active = Array.isArray(st.active) ? st.active.slice(0, 2) : [0, -1];
  active[side ? 1 : 0] = tabs.length - 1; if (!(active[0] >= 0)) active[0] = 0;
  localSet('ml.tabsState', JSON.stringify({ split: !!side || !!st.split, ratio: st.ratio || 0.5, tabs, active }));
  localSet('ml.tabs', '1'); showWorkspace(location.hash || '#/');
}
const paletteBib = new Map();   // doc id → its details (for citations), fetched when it is highlighted
function paletteCite(d, what) {
  const det = paletteBib.get(d.id), bib = (det && det.bib) || null;
  const st = bib && ((bib.styles || []).find(x => x.id === bib.defaultStyle) || (bib.styles || [])[0]);
  return st ? plainRef(what === 'ref' ? st.reference : st.inText) : null;
}
function openPalette() {
  if (document.querySelector('.palette')) return;
  const back = h('div', { class: 'palette' }), box = h('div', { class: 'palbox' });
  const PLACE = 'Open a book, a note, a document — or type what you want to do…';
  const input = h('input', { type: 'search', placeholder: PLACE, spellcheck: 'false' });
  const list = h('div', { class: 'pallist' }), side = h('div', { class: 'palside' }), foot = h('div', { class: 'palfoot muted' });
  let items = [], sel = 0, data = { books: [], notes: [], docs: [] };
  let preview = localGet('ml.palPreview', '') === '1', sub = null, bibTimer = 0;   // sub: { of, query, sel } while the actions of one item are listed
  const close = () => { back.remove(); document.removeEventListener('keydown', onKey, true); clearTimeout(bibTimer); };
  const actions = paletteActions();
  const score = (hay, title, terms) => { if (!terms.every(t => hay.includes(t))) return -1; const tl = norm(title); return (tl.startsWith(terms[0]) ? 6 : 0) + (terms.every(t => tl.includes(t)) ? 3 : 0) + Math.max(0, 2 - tl.length / 60); };
  const abstractOf = d => String(d.summary || (d.bib && d.bib.fields && (d.bib.fields.ABSTRACT || d.bib.fields.abstract)) || '').replace(/\s+/g, ' ').trim();
  const whoOf = d => d.displayAuthor || d.author || '';
  const copy = (text, what, none) => { if (text) copyText(text, what); else toast(none); };
  const ready = async (d, then) => { if (!paletteBib.has(d.id)) { try { paletteBib.set(d.id, await api('doc/' + d.id)); } catch (e) { return fail(e); } } then(); };
  /** What can be done with a book or an article: [label, key, what happens, hint]. */
  const bookActs = d => [
    canRead(d) ? ['Open in the reader', 'Enter', () => { location.hash = `#/read/${d.id}`; }] : null,
    ['Open its book page', canRead(d) ? 'Ctrl+Enter' : 'Enter', () => { location.hash = `#/book/${d.id}`; }, 'Details, files, citation'],
    ['Go to its notes', 'Ctrl+G', () => { location.hash = `#/notes/b/${d.id}`; }, d.highlights ? `${d.highlights} note${d.highlights === 1 ? '' : 's'}` : 'No notes yet'],
    ['Copy the in-text citation', 'Ctrl+D', () => ready(d, () => copy(paletteCite(d, 'intext'), 'Citation copied', 'This source has no citation yet')), paletteCite(d, 'intext') || ''],
    ['Copy the full reference', 'Ctrl+B', () => ready(d, () => copy(paletteCite(d, 'ref'), 'Reference copied', 'This source has no reference yet')), (paletteCite(d, 'ref') || '').slice(0, 70)],
    ['Copy the citekey', 'Ctrl+Y', () => copy(d.citekey, 'Citekey copied', 'This source has no citekey'), d.citekey || ''],
    ['Open in a new tab', 'Ctrl+Shift+Enter', () => paletteOpenTab(canRead(d) ? `#/read/${d.id}` : `#/book/${d.id}`, false)],
    ['Open in the other pane', 'Alt+Enter', () => paletteOpenTab(canRead(d) ? `#/read/${d.id}` : `#/book/${d.id}`, true), 'Side by side'],
  ].filter(Boolean);
  const noteActs = n => { const d = data.books.find(x => x.id === n.documentId); const at = d && canRead(d) ? `#/read/${d.id}` + (isPdf(d) ? `?page=${String(n.pdfPages || n.pageNumber).split(/[–-]/)[0]}&hl=${n.id}` : '') : (d ? `#/book/${d.id}` : null); return [
    ['Open the note', 'Enter', () => { location.hash = '#/notes/n/' + n.id; }],
    at ? ['Open the source at this place', 'Ctrl+Enter', () => { location.hash = at; }, n.documentTitle || ''] : null,
    ['Go to the notes of its source', 'Ctrl+G', () => { location.hash = `#/notes/b/${n.documentId}`; }],
    ['Open in a new tab', 'Ctrl+Shift+Enter', () => paletteOpenTab('#/notes/n/' + n.id, false)],
    ['Open in the other pane', 'Alt+Enter', () => paletteOpenTab('#/notes/n/' + n.id, true), 'Side by side'],
  ].filter(Boolean); };
  const actsOf = it => !it ? [] : it.doc ? bookActs(it.doc) : it.note ? noteActs(it.note) : [];
  const bookItem = (d, s) => ({ title: d.title, kind: d.category === 'ARTICLE' ? 'Article' : 'Book', s, doc: d, more: abstractOf(d) });
  // ---- the texts of the books: searched on the tablet a moment after typing stops
  let textMode = false, texts = null, textSeq = 0, textTimer = 0, textBusy = false;
  const TEXT_PLACE = 'Search in the texts of your books and articles…';
  const textQuery = () => { const v = input.value.trim(); return textMode ? v : v.startsWith('/') ? v.slice(1).trim() : v; };
  const textOn = () => textMode || input.value.trim().startsWith('/');
  const textLink = (d, hit, q) => isPdf(d) ? `#/read/${d.id}?page=${hit.loc}&find=${encodeURIComponent(q)}`
    : `#/read/${d.id}?href=${encodeURIComponent(hit.href)}&pos=${hit.loc}&find=${encodeURIComponent(q)}&occ=${hit.occurrence || 0}`;
  const textWhere = (d, hit) => isPdf(d) ? 'p. ' + (hit.label || hit.loc) : (hit.label || 'pos. ' + hit.loc);
  const searchTexts = () => {
    clearTimeout(textTimer);
    const q = textQuery();
    if (sub || q.length < 3) { texts = null; textBusy = false; return; }
    if (texts && texts.q === q) return;
    textBusy = true;
    textTimer = setTimeout(async () => {
      const my = ++textSeq;
      let r = null; try { r = await api(`search?q=${encodeURIComponent(q)}${textOn() ? '&per=12' : '&per=3'}`); } catch (_) {}
      if (my !== textSeq || !back.isConnected) return;
      textBusy = false; texts = r ? Object.assign(r, { q }) : { q, books: [], notes: [], failed: true };
      const keep = sel; find(true); sel = Math.min(keep, Math.max(0, items.length - 1)); draw();
    }, 320);
  };
  /** The places in the texts, as palette rows: at most [max]. */
  const textItems = max => {
    const out = []; if (!texts) return out;
    const q = texts.q;
    texts.books.forEach(b => b.hits.forEach(hit => out.push({ title: b.doc.title, kind: textWhere(b.doc, hit), textHit: hit, hitDoc: b.doc, more: hit.snippet, s: 0,
      run: e => { location.hash = e && e.ctrlKey ? '#/search?q=' + encodeURIComponent(q) : textLink(b.doc, hit, q); } })));
    // Evenly from each book first, so one book does not fill the list.
    out.sort((a, c) => texts.books.findIndex(b => b.doc === a.hitDoc) - texts.books.findIndex(b => b.doc === c.hitDoc));
    const per = new Map(), firstRound = [], rest = [];
    out.forEach(it => { const k = it.hitDoc.id, n = per.get(k) || 0; per.set(k, n + 1); (n < 2 ? firstRound : rest).push(it); });
    const notes = (texts.notes || []).slice(0, 4).map(n => ({ title: n.title || 'Note', kind: 'In a note', textHit: { snippet: n.snippet, start: n.start, end: n.end }, noteHit: n, more: n.snippet, s: 0,
      run: () => { location.hash = `#/read/${n.docId}?hl=${n.id}`; } }));
    return firstRound.concat(rest).slice(0, max).concat(textOn() ? notes : []);
  };
  const textTotal = () => texts ? texts.books.reduce((a, b) => a + b.count, 0) : 0;

  const find = fromTexts => {
    if (fromTexts !== true) searchTexts();
    const terms = norm(input.value.trim().replace(/^\//, '')).split(/\s+/).filter(Boolean);
    const out = [];
    if (!sub && textOn()) {
      // Only the texts: Ctrl+F, or a query that starts with /
      textItems(60).forEach(x => out.push(x));
      if (textQuery().length >= 3) out.push({ title: `Open the full search for “${textQuery()}”`, kind: 'Search', sub: 'Filters, keywords in context, AI search', run: () => { location.hash = '#/search?q=' + encodeURIComponent(textQuery()); } });
      items = out; if (fromTexts !== true) sel = 0; draw(); return;
    }
    if (sub) {
      // The actions of one item; what is typed narrows them.
      actsOf(sub.of).filter(a => terms.every(t => norm(a[0]).includes(t))).forEach(a => out.push({ title: a[0], kind: a[1], sub: a[3] || '', run: a[2] }));
      items = out; sel = Math.min(sel, Math.max(0, out.length - 1)); draw(); return;
    }
    const push = (arr, max) => arr.filter(x => x.s >= 0).sort((a, b) => b.s - a.s).slice(0, max).forEach(x => out.push(x));
    if (!terms.length) {
      // Nothing typed: what was open last, then the places.
      data.books.slice().sort((a, b) => (b.lastRead || 0) - (a.lastRead || 0)).slice(0, 5).forEach(d => out.push(bookItem(d, 0)));
      actions.slice(0, 12).forEach(a => out.push(Object.assign({ s: 0 }, a)));
    } else {
      push(actions.map(a => Object.assign({ s: score(a.hay, a.title, terms) + 1 }, a)).map(a => { if (a.s < 1) a.s = -1; return a; }), 5);
      push(data.books.map(d => bookItem(d, score(norm([d.title, d.author, d.displayAuthor, d.year, d.citekey, d.tags, abstractOf(d)].join(' ')), d.title, terms))), 8);
      push(data.docs.map(d => ({ title: d.title, kind: 'Document', sub: `${d.words || 0} words`, s: score(norm(d.title), d.title, terms), run: () => { location.hash = '#/write/' + encodeURIComponent(d.id); } })), 4);
      push(data.notes.map(n => ({ title: n.title || 'Untitled', kind: 'Note', sub: n.documentTitle || '', note: n, s: score(norm([n.title, n.tags, n.documentTitle].join(' ')), n.title || '', terms) })), 8);
      // A taste of what the texts say; Ctrl+F shows more.
      textItems(5).forEach(x => out.push(x));
    }
    items = out; if (fromTexts !== true) sel = 0; draw();
  };
  /** Runs what [key] means for [it]; false when it means nothing. */
  const act = (it, key) => {
    if (!it) return false;
    const acts = actsOf(it);
    if (!acts.length) { if (key !== 'Enter' && key !== 'Ctrl+Enter') return false; if (!it.keepOpen) close(); it.run({ ctrlKey: key !== 'Enter' }); return true; }
    const a = acts.find(x => x[1] === key); if (!a) return false;
    close(); a[2](); return true;
  };
  const meta = d => [whoOf(d), d.year].filter(Boolean).join(' · ');
  const drawSide = () => {
    clear(side); side.hidden = !preview;
    if (!preview) return;
    const it = sub ? sub.of : items[sel];
    if (it && it.doc) {
      const d = it.doc, tags = splitTags(d.tags), cover = h('span', { class: 'palbig' });
      if (!d.placeholder) { const img = h('img', { src: coverUrl(d), alt: '' }); img.addEventListener('error', () => cover.remove()); cover.append(img); }
      side.append(h('div', { class: 'palhead' }, cover, h('div', { style: { minWidth: 0 } },
          h('div', { class: 'serif palstitle' }, d.title), h('div', { class: 'palswho' }, whoOf(d) || 'No author recorded'),
          h('div', { class: 'muted mono palsmeta' }, [d.year || 'no year', it.kind, `${d.highlights || 0} note${d.highlights === 1 ? '' : 's'}`, d.citekey ? '@' + d.citekey : ''].filter(Boolean).join(' · ')))),
        tags.length ? h('div', { class: 'palstags' }, tags.map(t => h('span', null, '#' + t))) : null,
        h('div', { class: 'label', style: { marginTop: '12px' } }, 'Abstract'),
        h('div', { class: 'palabs' }, it.more || h('span', { class: 'muted' }, 'No abstract or summary.')));
    } else if (it && it.note) {
      const n = it.note;
      side.append(h('div', { class: 'serif palstitle' }, n.title || 'Untitled'), h('div', { class: 'palswho' }, n.documentTitle || ''),
        h('div', { class: 'muted mono palsmeta' }, [n.printedPageLabel ? 'p. ' + n.printedPageLabel : '', splitTags(n.tags).map(t => '#' + t).join(' ')].filter(Boolean).join(' · ')),
        n.highlightedText ? h('blockquote', { class: 'palquote' }, n.highlightedText) : null, n.userNotes ? h('div', { class: 'palabs' }, n.userNotes) : null);
    } else if (it && it.textHit) {
      const d = it.hitDoc, n = it.noteHit;
      side.append(h('div', { class: 'serif palstitle' }, d ? d.title : (n.title || 'Note')),
        h('div', { class: 'palswho' }, d ? (whoOf(d) || '') : (n.docTitle || '')),
        h('div', { class: 'muted mono palsmeta' }, [d && d.year, it.kind].filter(Boolean).join(' · ')),
        h('blockquote', { class: 'palquote' }, snippetEl(it.textHit.snippet || '', it.textHit.start || 0, it.textHit.end || 0)),
        h('div', { class: 'muted', style: { fontSize: '12px' } }, d ? 'Enter opens the book here, with the words marked. Ctrl+Enter opens the full search.' : 'Enter opens the note in its book.'));
    } else side.append(h('div', { class: 'muted', style: { fontSize: '13px' } }, 'Highlight a book, an article or a note to see it here.'));
  };
  const drawFoot = () => {
    const it = items[sel], k = (key, what) => [h('kbd', null, key), ' ' + what];
    const parts = sub ? [k('↑ ↓', 'move'), k('Enter', 'do it'), k('Esc', 'back')]
      : it && it.doc ? [k('Enter', 'open'), k('Tab', 'all actions'), k('Ctrl+G', 'its notes'), k('Ctrl+D', 'copy citation'), k('Ctrl+I', preview ? 'hide preview' : 'preview')]
      : it && it.note ? [k('Enter', 'open'), k('Tab', 'all actions'), k('Ctrl+Enter', 'open the source there'), k('Ctrl+I', preview ? 'hide preview' : 'preview')]
      : it && it.textHit ? [k('Enter', 'open at the place'), k('Ctrl+Enter', 'full search'), k('Ctrl+F', textMode ? 'everything' : 'only texts'), k('Ctrl+I', preview ? 'hide preview' : 'preview')]
      : [k('↑ ↓', 'move'), k('Enter', 'open'), k('Ctrl+F', textMode ? 'search everything' : 'search the texts'), k('Ctrl+I', preview ? 'hide preview' : 'preview'), k('Esc', 'close')];
    clear(foot); parts.forEach((p, i) => { if (i) foot.append(' · '); foot.append(...p); });
  };
  /** Citations need the item's details; they are fetched from the tablet a moment after it is highlighted. */
  const warm = () => {
    clearTimeout(bibTimer);
    const it = sub ? sub.of : items[sel]; if (!it || !it.doc || paletteBib.has(it.doc.id)) return;
    const id = it.doc.id;
    bibTimer = setTimeout(() => { api('doc/' + id).then(det => { paletteBib.set(id, det); if (sub && sub.of.doc && sub.of.doc.id === id) find(); }).catch(() => {}); }, 180);
  };
  const mark = () => { list.querySelectorAll('.palrow').forEach((r, k) => r.classList.toggle('on', k === sel)); drawSide(); drawFoot(); warm(); };
  const draw = () => {
    clear(list);
    box.classList.toggle('wide', preview);
    if (sub) list.append(h('div', { class: 'palsub' }, h('span', { class: 'muted' }, 'Actions for '), h('b', null, sub.of.title)));
    if (!items.length) list.append(h('div', { class: 'palnone muted' }, textOn() ? (textQuery().length < 3 ? 'Write at least three letters to search in the texts.' : textBusy ? 'Searching the texts…' : 'Nothing found in the texts.') : 'Nothing found.'));
    if (textOn() && textBusy && items.length) list.prepend(h('div', { class: 'palsec' }, h('span', { class: 'muted' }, 'Searching the texts…')));
    if (texts && texts.indexing && texts.indexing.running && textOn()) list.append(h('div', { class: 'palnone muted', style: { fontSize: '12px' } }, 'Some books are still being read for search; more may turn up later.'));
    items.forEach((it, i) => {
      // A book or article shows its cover, who wrote it and when, and the start of its abstract.
      let cover = null;
      if (it.doc) {
        cover = h('span', { class: 'palcover' });
        if (!it.doc.placeholder) { const img = h('img', { src: coverUrl(it.doc), alt: '', loading: 'lazy' }); img.addEventListener('error', () => img.remove()); cover.append(img); }
      }
      if (it.textHit && (i === 0 || !items[i - 1].textHit)) list.append(h('div', { class: 'palsec' }, h('span', null, 'In the texts'), h('span', { class: 'muted' }, texts && texts.q ? `${textTotal()} page${textTotal() === 1 ? '' : 's'} in ${texts.books.length} book${texts.books.length === 1 ? '' : 's'}${textOn() ? '' : ' · Ctrl+F shows more'}` : '')));
      const row = h('div', { class: 'palrow' + (i === sel ? ' on' : '') + (it.doc ? ' book' : '') + (it.textHit ? ' hit' : ''), onclick: e => { sel = i; if (sub) { close(); it.run(e); } else act(it, e.ctrlKey && e.shiftKey ? 'Ctrl+Shift+Enter' : e.altKey ? 'Alt+Enter' : e.ctrlKey ? 'Ctrl+Enter' : 'Enter') || act(it, 'Enter'); } }, ...[
        cover,
        it.textHit ? h('span', { class: 'palmain' }, h('span', { class: 'palt' }, it.title, it.hitDoc ? h('span', { class: 'muted', style: { fontWeight: 400 } }, '  ' + meta(it.hitDoc)) : (it.noteHit ? h('span', { class: 'muted', style: { fontWeight: 400 } }, '  ' + (it.noteHit.docTitle || '')) : null)),
            h('span', { class: 'palmore palsnip' }, snippetEl(it.textHit.snippet || '', it.textHit.start || 0, it.textHit.end || 0)))
        : it.doc ? h('span', { class: 'palmain' }, h('span', { class: 'palt' }, it.title),
            h('span', { class: 'palwho' }, meta(it.doc) || h('span', { class: 'muted' }, 'No author recorded')),
            it.more ? h('span', { class: 'palmore muted' }, it.more.slice(0, 260)) : null)
          : h('span', { class: 'palline' }, h('span', { class: 'palt' }, it.title), it.sub ? h('span', { class: 'pals muted' }, it.sub) : null),
        h('span', { class: 'palk' }, it.kind)].filter(Boolean));
      row.addEventListener('mousemove', () => { if (sel !== i) { sel = i; mark(); } });
      list.append(row);
    });
    const on = list.querySelector('.palrow.on'); if (on) on.scrollIntoView({ block: 'nearest' });
    drawSide(); drawFoot(); warm();
  };
  const setTextMode = on => {
    textMode = on; texts = null;
    if (on && input.value.trim().startsWith('/')) input.value = input.value.trim().slice(1).trim();
    input.placeholder = on ? TEXT_PLACE : PLACE; box.classList.toggle('textmode', on);
    find(); input.focus();
  };
  window.paletteTextMode = () => setTextMode(true);
  const enter = it => { sub = { of: it, query: input.value, sel }; input.value = ''; input.placeholder = 'Choose an action…'; sel = 0; find(); };
  const leave = () => { const s = sub; sub = null; input.value = s.query; input.placeholder = PLACE; find(); sel = Math.min(s.sel, Math.max(0, items.length - 1)); draw(); };
  const onKey = e => {
    const ctrl = e.ctrlKey || e.metaKey, letter = e.key.length === 1 ? e.key.toUpperCase() : e.key;
    const key = (ctrl ? 'Ctrl+' : '') + (e.altKey ? 'Alt+' : '') + (e.shiftKey && e.key === 'Enter' ? 'Shift+' : '') + letter;
    const stop = () => { e.preventDefault(); e.stopPropagation(); };
    if (e.key === 'Escape') { stop(); if (sub) leave(); else close(); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); sel = Math.min(items.length - 1, sel + 1); mark(); const on = list.querySelector('.palrow.on'); if (on) on.scrollIntoView({ block: 'nearest' }); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); sel = Math.max(0, sel - 1); mark(); const on = list.querySelector('.palrow.on'); if (on) on.scrollIntoView({ block: 'nearest' }); }
    else if (key === 'Ctrl+I') { stop(); preview = !preview; localSet('ml.palPreview', preview ? '1' : ''); draw(); }
    else if (key === 'Ctrl+F' && !sub) { stop(); setTextMode(!textMode); }
    else if (e.key === 'Tab') { stop(); if (sub) leave(); else if (actsOf(items[sel]).length) enter(items[sel]); }
    else if (e.key === 'Backspace' && sub && !input.value) { stop(); leave(); }
    else if (sub) { if (e.key === 'Enter') { stop(); const it = items[sel]; if (it) { close(); it.run(e); } } }
    else if (e.key === 'Enter') { stop(); act(items[sel], key) || act(items[sel], 'Enter'); }
    else if (['Ctrl+G', 'Ctrl+D', 'Ctrl+B', 'Ctrl+Y'].includes(key)) { stop(); act(items[sel], key); }   // taken from the browser while the palette is open
  };
  document.addEventListener('keydown', onKey, true);
  input.addEventListener('input', find);
  back.addEventListener('mousedown', e => { if (e.target === back) close(); });
  box.append(input, h('div', { class: 'palbody' }, list, side), foot);
  back.append(box); document.body.append(back);
  input.focus();
  find();
  paletteLoad(false).then(d => { data = d; find(); });
}
function shortcutsDialog() {
  const rows = [
    ['Everywhere', [['Ctrl+K', 'Open anything, or do anything (the command palette)'], ['?', 'This list']]],
    ['In the command palette, on a book or article', [['Enter', 'Open in the reader'], ['Ctrl+Enter', 'Open its book page'], ['Tab', 'List everything that can be done with it'], ['Ctrl+G', 'Go to its notes'], ['Ctrl+I', 'Show or hide the preview with the whole abstract'], ['Ctrl+D', 'Copy the in-text citation'], ['Ctrl+B', 'Copy the full reference'], ['Ctrl+Y', 'Copy the citekey'], ['Ctrl+Shift+Enter', 'Open in a new tab'], ['Alt+Enter', 'Open in the other pane']]],
    ['Notes', [['/', 'Search the notes'], ['[[', 'In a note: link to another note']]],
    ['Reader', [['← →', 'Previous and next page'], ['Ctrl+F', 'Search in the book']]],
    ['Writing', [['Ctrl+Shift+C', 'Insert a citation'], ['Ctrl+Alt+N', 'Notes panel'], ['Ctrl+Alt+M', 'Comment on the selection'], ['Ctrl+Alt+E', 'Formula'], ['Ctrl+Alt+D', 'Dictate at the cursor (again to stop)'], ['Ctrl+Alt+L', 'Lexicon panel'], ['Ctrl+Alt+S', 'Style panel (British style corrections)'], ['Ctrl+S', 'Save now']]],
    ['Tabs', [['Ctrl+click', 'Open in a new tab'], ['Ctrl+Alt+click', 'Open in the other pane'], ['Middle click on a tab', 'Close it']]],
  ];
  modal((box, close) => {
    box.append(h('h2', null, 'Keyboard shortcuts'), h('div', { class: 'keys' }, rows.map(([group, keys]) => [h('div', { class: 'label keysg' }, group),
      keys.map(([k, what]) => h('div', { class: 'keyrow' }, h('kbd', null, k), h('span', null, what)))])),
      h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: close }, 'Close')));
  });
}
document.addEventListener('keydown', e => {
  if ((e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey && (e.key === 'k' || e.key === 'K')) {
    if (workspace || document.querySelector('.login')) return;
    e.preventDefault(); openPalette(); return;
  }
  if (e.key === '?' && !e.ctrlKey && !e.metaKey && !e.altKey) {
    const t = document.activeElement, tag = (t && t.tagName) || '';
    if (/^(INPUT|TEXTAREA|SELECT)$/.test(tag) || (t && t.isContentEditable) || document.querySelector('.modal, .palette, .login') || workspace) return;
    e.preventDefault(); shortcutsDialog();
  }
});
