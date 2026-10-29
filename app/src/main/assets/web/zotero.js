/* Zotero in the web view: the Zotero tab (collections, items, link and import), the citation check
 * (the app's details next to Zotero's, field by field: use Zotero's, keep mine, or send mine to
 * Zotero), the Zotero section of a book's Info side panel and the API key in Settings. The tablet
 * talks to Zotero's Web API; the key never leaves it. */
'use strict';

const ZOTERO_TYPES = { book: 'Book', bookSection: 'Book section', journalArticle: 'Journal article', magazineArticle: 'Magazine article', newspaperArticle: 'Newspaper article',
  thesis: 'Thesis', report: 'Report', conferencePaper: 'Conference paper', webpage: 'Web page', document: 'Document', encyclopediaArticle: 'Encyclopedia article', blogPost: 'Blog post' };
const zoteroLink = key => `zotero://select/library/items/${key}`;

/** Zotero's formatted reference (HTML from the Web API) as safe elements: text with italics, bold and super/subscripts. */
function zoteroBibEl(html) {
  const out = h('div', { class: 'zbib' });
  if (!html) return out;
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const allowed = { I: 'i', EM: 'i', B: 'b', STRONG: 'b', SUP: 'sup', SUB: 'sub', SPAN: 'span' };
  const walk = (node, into) => node.childNodes.forEach(c => {
    if (c.nodeType === 3) into.append(c.nodeValue);
    else if (c.nodeType === 1) {
      const st = (c.getAttribute('style') || '').toLowerCase();
      const tag = allowed[c.tagName] || (st.includes('italic') ? 'i' : null);
      if (c.tagName === 'DIV' && c.classList.contains('csl-entry')) { const e = h('div', { class: 'csl-entry' }); walk(c, e); into.append(e); }
      else if (tag) { const e = h(tag); walk(c, e); into.append(e); }
      else walk(c, into);
    }
  });
  walk(doc.body, out);
  return out;
}
const bibText = html => zoteroBibEl(html).textContent.replace(/\s+/g, ' ').trim();

// ------------------------------------------------------------------ the comparison

const CHECK_LABELS = { AUTHOR: 'Author(s)', EDITOR: 'Editor(s)', TRANSLATOR: 'Translator(s)', TITLE: 'Title', SUBTITLE: 'Subtitle', YEAR: 'Year / date', PUBLISHER: 'Publisher',
  PLACE: 'Place', EDITION: 'Edition', JOURNAL: 'Journal / container', BOOKTITLE: 'Book title', VOLUME: 'Volume', ISSUE: 'Issue', PAGES: 'Pages', SERIES: 'Series',
  DOI: 'DOI', ISBN: 'ISBN', ISSN: 'ISSN', URL: 'URL', LANGUAGE: 'Language', ABSTRACT: 'Abstract', TYPE: 'Item type', CITEKEY: 'Citation key' };
const CHECK_ORDER = ['AUTHOR', 'TITLE', 'SUBTITLE', 'YEAR', 'EDITOR', 'TRANSLATOR', 'JOURNAL', 'BOOKTITLE', 'PUBLISHER', 'PLACE', 'EDITION', 'VOLUME', 'ISSUE', 'PAGES', 'SERIES',
  'DOI', 'ISBN', 'ISSN', 'URL', 'LANGUAGE', 'TYPE', 'CITEKEY', 'ABSTRACT'];
const CHAPTER_KEYS = ['AUTHOR', 'TITLE', 'PAGES', 'DOI', 'ABSTRACT'];

/** Rows of a check (GET zotero/doc/…): the app's value and Zotero's for every field either has. */
function zoteroRows(check, chapter) {
  const app = check.app || { fields: {} }, z = (check.item && check.item.found) || { fields: {} };
  const val = (side, k) => k === 'AUTHOR' ? side.author : k === 'ABSTRACT' ? side.abstract : k === 'TYPE' ? side.entryType : k === 'CITEKEY' ? (side === app ? app.citationKey : (side.fields || {}).CITEKEY) : (side.fields || {})[k];
  return CHECK_ORDER.filter(k => !chapter || CHAPTER_KEYS.includes(k)).map(k => {
    const mine = (val(app, k) || '').trim(), theirs = (val(z, k) || '').trim();
    if (!mine && !theirs) return null;
    const same = k === 'TYPE' ? (mine || 'book') === (theirs || 'book') : sameValue(k, mine, theirs);
    return { key: k, label: CHECK_LABELS[k] || k, mine, theirs, same };
  }).filter(Boolean);
}
const shownValue = (k, v) => k === 'TYPE' ? (TYPE_LABELS[v] || v || '—') : (v || '—');

/**
 * The citation check of one book (or chapter): every field side by side; for each difference
 * choose Use Zotero's, Keep mine, or Send mine to Zotero; then apply.
 */
function zoteroCheckDialog(docId, chapter, onDone) {
  modal(async (box, close) => {
    box.style.width = '900px';
    box.append(h('h2', null, chapter == null ? 'Check against Zotero' : 'Check this chapter against Zotero'), h('div', { class: 'loading', style: { position: 'static', height: '120px' } }, h('div', { class: 'spin' })));
    let check;
    try { check = await api(`zotero/doc/${docId}?bib=1${chapter == null ? '' : '&chapter=' + chapter}`); } catch (e) { fail(e); close(); return; }
    clear(box).append(h('h2', null, chapter == null ? 'Check against Zotero' : 'Check this chapter against Zotero'));
    if (!check.linked || !check.item) { box.append(h('p', null, check.missing ? 'The linked item is no longer in your Zotero library (open the Zotero tab to refresh).' : 'Not linked to a Zotero item yet.'), h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Close'))); return; }
    const rows = zoteroRows(check, chapter != null);
    const diff = rows.filter(r => !r.same);
    diff.forEach(r => { r.choice = r.key === 'CITEKEY' ? (!r.theirs && check.canWrite ? 'send' : (r.theirs ? 'use' : 'keep')) : (!r.mine ? 'use' : 'keep'); });
    const table = h('div', { class: 'cmp zcmp' }, h('div', { class: 'cmph' }, h('span', null, 'Field'), h('span', null, 'This app'), h('span', null, 'Zotero'), h('span', null, '')));
    diff.forEach(r => {
      const btn = (c, label, title) => { const b = h('button', { type: 'button', class: 'chip', title }, label); b.onclick = () => { r.choice = c; paint(); }; b.dataset.c = c; return b; };
      const btns = [btn('keep', 'Keep mine', 'Leave both as they are'), btn('use', 'Use Zotero’s', 'Copy Zotero’s value into the app')];
      if (check.canWrite && r.mine) btns.push(btn('send', 'Send mine', 'Write the app’s value into Zotero'));
      const line = h('div', { class: 'cmpr' + (r.key === 'ABSTRACT' ? ' long' : '') },
        h('span', { class: 'label' }, r.label), h('span', { class: 'now' + (r.mine ? '' : ' blank') }, shownValue(r.key, r.mine)), h('span', { class: 'fnd' + (r.theirs ? '' : ' blank') }, shownValue(r.key, r.theirs)),
        h('span', { class: 'row', style: { gap: '4px', flexWrap: 'wrap' } }, ...btns));
      const paint = () => { btns.forEach(b => b.classList.toggle('on', b.dataset.c === r.choice)); line.classList.toggle('chosen', r.choice !== 'keep'); };
      paint();
      table.append(line);
    });
    const refs = h('div', { class: 'zrefs' },
      h('div', null, h('div', { class: 'label' }, `This app · ${check.styleLabel}`), h('div', { class: 'zbib' }, check.app.reference || '—')),
      h('div', null, h('div', { class: 'label' }, `Zotero · ${check.styleLabel}`), check.bib ? zoteroBibEl(check.bib) : h('div', { class: 'muted' }, 'Zotero’s reference could not be fetched.')));
    const apply = async () => {
      const use = diff.filter(r => r.choice === 'use'), send = diff.filter(r => r.choice === 'send');
      try {
        if (use.length) {
          if (chapter != null) {
            await api('zotero/chapter-apply', { method: 'POST', json: { doc: docId, chapter, fields: Object.fromEntries(use.map(r => [r.key, r.theirs])) } });
          } else {
            const patch = {}, bib = {};
            use.forEach(r => {
              if (r.key === 'AUTHOR') patch.author = r.theirs;
              else if (r.key === 'ABSTRACT') patch.summary = r.theirs;
              else if (r.key === 'TYPE') bib._type = r.theirs;
              else bib[r.key] = r.theirs;
            });
            if (Object.keys(patch).length) await api(`doc/${docId}`, { method: 'PATCH', json: patch });
            if (Object.keys(bib).length) await api(`doc/${docId}/bib`, { method: 'PUT', json: bib });
          }
        }
        let msg = '';
        if (send.length) {
          const fields = {};
          send.forEach(r => {
            if (r.key === 'TITLE' || r.key === 'SUBTITLE') {
              const t = (check.app.fields.TITLE || '').trim(), sub = (check.app.fields.SUBTITLE || '').trim();
              fields.TITLE = sub && chapter == null ? `${t}: ${sub}` : t;
            } else if (r.key !== 'TYPE') fields[r.key] = r.mine;
          });
          const res = await api('zotero/push', { method: 'POST', json: { key: check.key, fields } });
          msg = res.message;
        }
        toast(msg || (use.length ? `Took ${use.length} field${use.length === 1 ? '' : 's'} from Zotero` : 'Nothing changed'), 3200);
        close(); if (onDone) onDone();
      } catch (e) { fail(e); }
    };
    box.append(...[
      h('div', { class: 'muted', style: { fontSize: '13px', margin: '0 0 8px' } },
        diff.length ? `${diff.length} field${diff.length === 1 ? '' : 's'} differ · ${rows.length - diff.length} match` : `All ${rows.length} fields match Zotero.`,
        check.canWrite ? '' : ' · Your key is read-only, so nothing can be sent to Zotero.'),
      diff.length ? table : null, refs,
      h('div', { class: 'foot' },
        h('a', { class: 'btn quiet', href: zoteroLink(check.key) }, 'Open in Zotero'),
        h('span', { style: { flex: 1 } }),
        h('button', { class: 'btn', onclick: close }, diff.length ? 'Cancel' : 'Close'),
        diff.length ? h('button', { class: 'btn fill', onclick: apply }, 'Apply chosen') : null)].filter(Boolean));
  });
}

/** A small search of the Zotero library, for linking a book or chapter by hand. */
function zoteroPicker(title, query, onPick) {
  modal((box, close) => {
    const q = h('input', { type: 'search', value: query || '', placeholder: 'Title, author, year, DOI, citation key…' });
    const list = h('div', { class: 'zpick' });
    let t = 0;
    const run = async () => {
      try {
        const r = await api(`zotero/items?q=${encodeURIComponent(q.value.trim())}`);
        clear(list).append(...(r.items.length ? r.items.slice(0, 60).map(it => h('button', { class: 'cand', type: 'button', onclick: () => { close(); onPick(it); } },
          h('b', null, it.title), h('span', null, [it.creators, it.year, ZOTERO_TYPES[it.itemType] || it.itemType, it.linked ? 'already linked' : ''].filter(Boolean).join(' · ')))) : [h('div', { class: 'muted' }, 'No items match.')]));
      } catch (e) { fail(e); }
    };
    q.addEventListener('input', () => { clearTimeout(t); t = setTimeout(run, 250); });
    box.append(h('h2', null, title), q, list, h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel')));
    run();
  });
}

// ------------------------------------------------------------------ the Info side panel

/** The Zotero section of a book's Info panel: link, the check at a glance, and Zotero's reference on request. */
function zoteroInfoSection(ctx, chapterIndex) {
  const box = h('div', { class: 'zinfo' });
  const d = ctx.d;
  const load = async () => {
    clear(box).append(h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Checking…'));
    let c;
    try { c = await api(`zotero/doc/${d.id}`); } catch (e) { clear(box).append(h('div', { class: 'muted' }, e.message)); return; }
    clear(box);
    if (!c.connected) { box.append(h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Not connected. Add your Zotero API key in ', h('a', { href: '#/settings' }, 'Settings'), '.')); return; }
    if (!c.linked) {
      box.append(h('div', { class: 'muted', style: { fontSize: '12px', marginBottom: '6px' } }, 'Not linked to a Zotero item.'));
      (c.suggestions || []).slice(0, 3).forEach(s => box.append(h('div', { class: 'zsug' },
        h('div', null, h('b', null, s.title), h('div', { class: 'muted' }, [s.creators, s.year, s.reason].filter(Boolean).join(' · '))),
        h('button', { class: 'btn', onclick: () => link(s.key) }, 'Link'))));
      box.append(h('div', { class: 'row', style: { gap: '4px', marginTop: '6px' } },
        h('button', { class: 'btn', onclick: () => zoteroPicker('Link to a Zotero item', d.title, it => link(it.key)) }, 'Find in Zotero…'),
        c.canWrite ? h('button', { class: 'btn quiet', title: 'Create a Zotero item from this book’s details, with its citation key', onclick: () => confirmBox('Add this book to Zotero?', 'A new item is made in your Zotero library from the details here, and linked.', 'Add to Zotero', async () => {
          try { await api('zotero/create', { method: 'POST', json: { doc: d.id } }); toast('Added to Zotero'); load(); } catch (e) { fail(e); }
        }) }, '+ Add to Zotero') : null));
      return;
    }
    if (!c.item) { box.append(h('div', { class: 'muted' }, 'The linked item is not in the Zotero cache. Open the Zotero tab to refresh.')); return; }
    const rows = zoteroRows(c, false), diff = rows.filter(r => !r.same);
    box.append(...[
      h('div', { class: 'zstate ' + (diff.length ? 'bad' : 'ok') }, diff.length ? `⚠ ${diff.length} difference${diff.length === 1 ? '' : 's'} from Zotero` : '✓ Matches Zotero'),
      diff.length ? h('div', { class: 'muted', style: { fontSize: '12px' } }, diff.slice(0, 6).map(r => r.label).join(', ') + (diff.length > 6 ? '…' : '')) : null,
      h('div', { class: 'info-row' }, h('div', { class: 'info-lab' }, 'Item'), h('div', { class: 'info-val' }, c.item.title, h('div', { class: 'muted', style: { fontSize: '12px' } }, [ZOTERO_TYPES[c.item.itemType] || c.item.itemType, c.item.collections.join(', ')].filter(Boolean).join(' · ')))),
      h('div', { class: 'info-row' }, h('div', { class: 'info-lab' }, 'Citation key'), h('div', { class: 'info-val mono' + (c.zoteroCitationKey ? '' : ' unknown') }, c.zoteroCitationKey || 'not set in Zotero')),
      h('div', { class: 'row', style: { gap: '4px', marginTop: '6px', flexWrap: 'wrap' } },
        h('button', { class: 'btn' + (diff.length ? ' fill' : ''), onclick: () => zoteroCheckDialog(d.id, null, ctx.reloadInfo) }, diff.length ? 'Review differences' : 'Compare'),
        h('button', { class: 'btn quiet', onclick: showRef }, 'Zotero’s reference'),
        h('a', { class: 'btn quiet', href: zoteroLink(c.key) }, 'Open in Zotero'),
        h('button', { class: 'btn quiet', onclick: () => confirmBox('Unlink from Zotero?', 'The book stays as it is; it is just no longer linked to the Zotero item.', 'Unlink', async () => { await api('zotero/unlink', { method: 'POST', json: { doc: d.id } }); load(); }) }, 'Unlink')),
      refBox].filter(Boolean));
    if (chapterIndex != null) box.append(chapterBox(c));
  };
  const refBox = h('div', { class: 'zref' });
  const showRef = async () => {
    clear(refBox).append(h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Asking Zotero…'));
    try {
      const c = await api(`zotero/doc/${d.id}?bib=1`);
      clear(refBox).append(h('div', { class: 'label' }, `Zotero · ${c.styleLabel}`), c.bib ? zoteroBibEl(c.bib) : h('div', { class: 'muted' }, '—'),
        c.bib ? h('button', { class: 'btn quiet', onclick: () => copyText(bibText(c.bib), 'Zotero’s reference copied') }, 'Copy') : null);
    } catch (e) { clear(refBox).append(h('div', { class: 'muted' }, e.message)); }
  };
  const chapterBox = () => {
    const cb = h('div', { class: 'zchap' }, h('div', { class: 'label', style: { marginTop: '10px' } }, 'This chapter in Zotero'), h('div', { class: 'muted', style: { fontSize: '12px' } }, 'Checking…'));
    (async () => {
      try {
        const cc = await api(`zotero/doc/${d.id}?chapter=${chapterIndex}`);
        clear(cb).append(h('div', { class: 'label', style: { marginTop: '10px' } }, 'This chapter in Zotero'));
        if (!cc.linked) {
          (cc.suggestions || []).filter(s => s.itemType === 'bookSection').slice(0, 2).forEach(s => cb.append(h('div', { class: 'zsug' },
            h('div', null, h('b', null, s.title), h('div', { class: 'muted' }, [s.creators, s.reason].filter(Boolean).join(' · '))),
            h('button', { class: 'btn', onclick: () => linkChapter(s.key) }, 'Link'))));
          cb.append(h('button', { class: 'btn', onclick: () => zoteroPicker('Link this chapter to a Zotero book section', '', it => linkChapter(it.key)) }, 'Link a book section…'));
          return;
        }
        const diff = zoteroRows(cc, true).filter(r => !r.same);
        cb.append(h('div', { class: 'zstate ' + (diff.length ? 'bad' : 'ok') }, diff.length ? `⚠ ${diff.length} difference${diff.length === 1 ? '' : 's'}` : '✓ Matches Zotero'),
          h('div', { class: 'row', style: { gap: '4px', marginTop: '4px' } },
            h('button', { class: 'btn', onclick: () => zoteroCheckDialog(d.id, chapterIndex, ctx.reloadInfo) }, diff.length ? 'Review' : 'Compare'),
            h('button', { class: 'btn quiet', onclick: async () => { await api('zotero/unlink', { method: 'POST', json: { doc: d.id, chapter: chapterIndex } }); load(); } }, 'Unlink')));
      } catch (e) { clear(cb).append(h('div', { class: 'muted' }, e.message)); }
    })();
    return cb;
  };
  const link = async key => { try { await api('zotero/link', { method: 'POST', json: { doc: d.id, key } }); toast('Linked to Zotero'); if (ctx.reloadInfo) ctx.reloadInfo(); else load(); } catch (e) { fail(e); } };
  const linkChapter = async key => { try { await api('zotero/link', { method: 'POST', json: { doc: d.id, key, chapter: chapterIndex } }); toast('Chapter linked'); load(); } catch (e) { fail(e); } };
  load();
  return box;
}

// ------------------------------------------------------------------ Settings

function zoteroSettingsSection() {
  const sec = h('section', { class: 'set-group', id: 'set-zotero' }, h('h2', null, 'Zotero'));
  const draw = st => {
    clear(sec).append(h('h2', null, 'Zotero'));
    const inp = h('input', { type: 'password', placeholder: st.connected ? 'Paste a new key to replace it' : 'Paste your Zotero API key', autocomplete: 'off', spellcheck: 'false' });
    const send = async () => {
      if (!inp.value.trim()) { toast('Paste a key first'); return; }
      try { toast('Checking the key with Zotero…'); draw(await api('zotero/key', { method: 'POST', json: { key: inp.value.trim() }, timeout: 120000 })); toast('Zotero connected'); } catch (e) { fail(e); }
    };
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
    sec.append(...[
      h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('div', null, 'API key'),
        h('div', { class: 'muted set-help' }, 'Make one at zotero.org/settings/keys with “Allow library access”, and “Allow write access” to send fixes and new items to Zotero. Stored encrypted on the tablet, never in backups.')),
        h('div', { class: 'set-control' },
          h('div', { class: 'mono set-key-state' + (st.connected ? ' ok' : ''), style: { marginBottom: '6px' } }, st.connected ? `Connected as ${st.username || st.userId} · ••••${st.last4} · ${st.canWrite ? 'read and write' : 'read only'}` : 'Not connected'),
          h('div', { class: 'row' }, inp, h('button', { class: 'btn fill', onclick: send }, 'Save'),
            st.connected ? h('button', { class: 'btn', onclick: () => confirmBox('Disconnect Zotero?', 'The key and the cached library are removed from the tablet. Links between books and Zotero items are kept.', 'Disconnect', async () => { try { draw(await api('zotero/key', { method: 'DELETE' })); } catch (e) { fail(e); } }) }, 'Disconnect') : null))),
      st.connected ? h('div', { class: 'set-row' }, h('div', { class: 'set-label' }, h('div', null, 'Library'), h('div', { class: 'muted set-help' }, `Citations are checked in your default style (${st.styleLabel}).`)),
        h('div', { class: 'set-control' }, `${st.items} items · ${st.collections} collections · fetched ${st.lastSync ? new Date(st.lastSync).toLocaleString() : 'never'}`, ' ', h('a', { href: '#/zotero' }, 'Open the Zotero tab'))) : null].filter(Boolean));
  };
  api('zotero/status').then(draw).catch(e => sec.append(h('div', { class: 'muted' }, e.message)));
  return sec;
}

// ------------------------------------------------------------------ the Zotero tab

const zstate = { collection: localGet('ml.zcol', ''), q: '', item: null, deep: localGet('ml.zdeep', '1') === '1' };

async function showZotero() {
  const app = clear(document.getElementById('app'));
  app.appendChild(header());
  const main = h('main', { class: 'zotero' });
  app.appendChild(main);
  let st;
  try { st = await api('zotero/status'); } catch (e) { fail(e); return; }
  if (!st.connected) {
    main.append(h('div', { class: 'empty' }, h('h2', null, 'Connect Zotero'), h('p', { class: 'muted' }, 'Paste a Zotero API key in Settings to see your Zotero library here, link it to the books on the tablet and check your citations.'),
      h('a', { class: 'btn fill', href: '#/settings' }, 'Go to Settings')));
    return;
  }
  const status = h('span', { class: 'muted mono', style: { fontSize: '12px' } });
  const showStatus = s => { status.textContent = `${s.items} items · ${s.username || ''}${s.canWrite ? '' : ' · read-only key'} · fetched ${s.lastSync ? new Date(s.lastSync).toLocaleTimeString() : 'never'}`; };
  showStatus(st);
  const cols = h('div', { class: 'zcols' }), itemsBox = h('div', { class: 'zitems' }), detail = h('div', { class: 'zdetail' });
  const search = h('input', { type: 'search', placeholder: 'Search the library…', value: zstate.q });
  const refresh = h('button', { class: 'btn', onclick: () => sync(true) }, 'Refresh');
  const importBtn = h('button', { class: 'btn', title: 'Link every item in this collection to its book, and tag the books with the collection', onclick: () => importCollection() }, 'Import collection…');
  main.append(h('div', { class: 'ztool' }, h('h1', null, 'Zotero'), status, h('span', { style: { flex: 1 } }), search, importBtn, refresh), h('div', { class: 'zgrid' }, cols, itemsBox, detail));
  let items = [];
  const drawCols = async () => {
    let r;
    try { r = await api('zotero/collections'); } catch (e) { fail(e); return; }
    const byParent = {};
    r.collections.forEach(c => { (byParent[c.parent || ''] = byParent[c.parent || ''] || []).push(c); });
    const entry = (key, label, count, depth, cls) => h('button', { class: 'zcol' + (zstate.collection === key ? ' on' : '') + (cls ? ' ' + cls : ''), style: { paddingLeft: 8 + depth * 14 + 'px' },
      onclick: () => { zstate.collection = key; localSet('ml.zcol', key); zstate.item = null; drawCols(); loadItems(); } }, h('span', null, label), h('span', { class: 'muted' }, String(count)));
    clear(cols).append(entry('', 'My Library', r.total, 0, 'lib'), entry('@unfiled', 'Unfiled items', r.unfiled, 0));
    const walk = (parent, depth) => (byParent[parent] || []).forEach(c => { cols.append(entry(c.key, c.name, c.count, depth + 1)); walk(c.key, depth + 1); });
    walk('', 0);
    cols.append(h('label', { class: 'row zdeep', style: { fontSize: '12px', gap: '4px', margin: '8px' } }, (() => { const cb = h('input', { type: 'checkbox', checked: zstate.deep }); cb.addEventListener('change', () => { zstate.deep = cb.checked; localSet('ml.zdeep', cb.checked ? '1' : '0'); loadItems(); }); return cb; })(), 'Show items in sub-collections'));
    importBtn.disabled = !zstate.collection || zstate.collection === '@unfiled';
  };
  const loadItems = async () => {
    const q = new URLSearchParams();
    if (zstate.collection === '@unfiled') q.set('unfiled', '1'); else if (zstate.collection) q.set('collection', zstate.collection);
    if (zstate.q.trim()) q.set('q', zstate.q.trim());
    if (zstate.deep) q.set('deep', '1');
    try { items = (await api('zotero/items?' + q)).items; } catch (e) { fail(e); return; }
    drawItems();
  };
  const drawItems = () => {
    clear(itemsBox);
    if (!items.length) { itemsBox.append(h('div', { class: 'muted', style: { padding: '16px' } }, zstate.q ? 'No items match.' : 'No items here.')); return; }
    itemsBox.append(h('div', { class: 'label', style: { padding: '6px 10px' } }, `${items.length} item${items.length === 1 ? '' : 's'}`));
    items.forEach(it => itemsBox.append(h('button', { class: 'zitem' + (zstate.item === it.key ? ' on' : ''), onclick: () => { zstate.item = it.key; drawItems(); showItem(it.key); } },
      h('div', { class: 'zt' }, it.linked ? h('span', { class: 'zlinked', title: 'On the tablet' }, '▣ ') : null, it.title),
      h('div', { class: 'muted' }, [it.creators, it.year, ZOTERO_TYPES[it.itemType] || it.itemType].filter(Boolean).join(' · ')))));
  };
  const showItem = async key => {
    clear(detail).append(h('div', { class: 'loading', style: { position: 'static', height: '120px' } }, h('div', { class: 'spin' })));
    let it;
    try { it = await api(`zotero/item/${key}`); } catch (e) { clear(detail).append(h('div', { class: 'muted' }, e.message)); return; }
    const f = it.found.fields || {};
    const rowsEl = [['Item type', ZOTERO_TYPES[it.itemType] || it.itemType], ['Author(s)', splitPeople(it.found.author).join(', ')], ['Editor(s)', splitPeople(f.EDITOR).join(', ')],
      ['Date', it.date], [it.itemType === 'bookSection' ? 'Book title' : 'Publication', it.container], ['Publisher', f.PUBLISHER], ['Place', f.PLACE], ['Edition', f.EDITION],
      ['Volume', f.VOLUME], ['Issue', f.ISSUE], ['Pages', f.PAGES], ['DOI', f.DOI], ['ISBN', f.ISBN], ['Citation key', it.citationKey], ['Collections', it.collections.join(', ')], ['Tags', (it.tags || []).join(', ')]]
      .filter(([, v]) => v).map(([l, v]) => h('div', { class: 'info-row' }, h('div', { class: 'info-lab' }, l), h('div', { class: 'info-val' + (l === 'Citation key' ? ' mono' : '') }, v)));
    const linkBox = h('div', { class: 'zlinkbox' });
    const linkTo = async docId => { try { await api('zotero/link', { method: 'POST', json: { doc: docId, key } }); toast('Linked'); await loadItems(); showItem(key); } catch (e) { fail(e); } };
    if (it.linkedDoc) {
      linkBox.append(h('div', { class: 'zstate ok' }, '▣ On the tablet: ', h('a', { href: `#/book/${it.linkedDoc.id}` }, it.linkedDoc.title), it.linkedDoc.chapter != null ? ` (chapter ${it.linkedDoc.chapter + 1})` : ''),
        h('div', { class: 'row', style: { gap: '4px' } },
          h('button', { class: 'btn fill', onclick: () => zoteroCheckDialog(it.linkedDoc.id, it.linkedDoc.chapter == null ? null : it.linkedDoc.chapter, () => showItem(key)) }, 'Check citation'),
          h('a', { class: 'btn', href: `#/read/${it.linkedDoc.id}` }, 'Read'),
          h('button', { class: 'btn quiet', onclick: async () => { await api('zotero/unlink', { method: 'POST', json: { doc: it.linkedDoc.id, chapter: it.linkedDoc.chapter == null ? null : it.linkedDoc.chapter } }); await loadItems(); showItem(key); } }, 'Unlink')));
    } else {
      linkBox.append(h('div', { class: 'label' }, 'Not on the tablet yet'));
      (it.suggestions || []).forEach(s => linkBox.append(h('div', { class: 'zsug' }, h('div', null, h('b', null, s.title), h('div', { class: 'muted' }, [s.author, s.reason].filter(Boolean).join(' · '))),
        h('button', { class: 'btn' + (s.strength >= 3 ? ' fill' : ''), onclick: () => linkTo(s.id) }, 'Link'))));
      linkBox.append(h('button', { class: 'btn', onclick: () => pickBook(it.title, linkTo) }, 'Link to another book…'));
    }
    clear(detail).append(...[h('h2', null, it.title), linkBox,
      h('div', { class: 'label', style: { marginTop: '12px' } }, `Zotero’s reference · ${(STYLE_NAMES[it.style] || it.style)}`),
      it.bib ? zoteroBibEl(it.bib) : h('div', { class: 'muted' }, '—'),
      h('div', { class: 'row', style: { gap: '4px', margin: '6px 0 12px' } },
        it.bib ? h('button', { class: 'btn quiet', onclick: () => copyText(bibText(it.bib), 'Reference copied') }, 'Copy reference') : null,
        it.citationKey ? h('button', { class: 'btn quiet', onclick: () => copyText(`[@${it.citationKey}]`, 'Citation key copied') }, `Copy [@${it.citationKey}]`) : null,
        h('a', { class: 'btn quiet', href: zoteroLink(key) }, 'Open in Zotero')),
      h('div', { class: 'info' }, ...rowsEl),
      it.abstract ? h('div', { class: 'muted', style: { fontSize: '13px', marginTop: '10px' } }, it.abstract) : null].filter(Boolean));
  };
  const pickBook = async (title, onPick) => {
    const docs = state.docs && state.docs.length ? state.docs : await loadLibrary();
    modal((box, close) => {
      const q = h('input', { type: 'search', value: title || '', placeholder: 'Search the library' });
      const list = h('div', { class: 'zpick' });
      const draw = () => {
        const terms = norm(q.value).split(/\s+/).filter(Boolean);
        const hits = docs.filter(d => !terms.length || terms.every(t => norm([d.title, d.author].join(' ')).includes(t))).slice(0, 60);
        clear(list).append(...hits.map(d => h('button', { class: 'cand', type: 'button', onclick: () => { close(); onPick(d.id); } }, h('b', null, d.title), h('span', null, d.displayAuthor || d.author || ''))));
      };
      q.addEventListener('input', draw);
      box.append(h('h2', null, 'Link to a book on the tablet'), q, list, h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel')));
      draw();
    });
  };
  const importCollection = async () => {
    if (!zstate.collection || zstate.collection === '@unfiled') return;
    let r;
    try { toast('Matching the collection with the library…'); r = await api('zotero/import', { method: 'POST', json: { collection: zstate.collection } }); } catch (e) { fail(e); return; }
    await loadItems();
    modal((box, close) => {
      const sec = (title, list, extra) => list.length ? h('div', { class: 'zimp' }, h('div', { class: 'label' }, `${title} · ${list.length}`), ...list.map(extra)) : null;
      box.style.width = '760px';
      box.append(...[h('h2', null, 'Collection imported'),
        h('p', { class: 'muted', style: { marginTop: 0 } }, `${r.linked.length} linked now, ${r.already.length} were linked already, ${r.tagged} book${r.tagged === 1 ? '' : 's'} tagged with the collection. Details are not changed: check each book to take Zotero’s values.`),
        sec('Linked now', r.linked, it => h('div', { class: 'zsug' }, h('div', null, h('b', null, it.title), h('div', { class: 'muted' }, `→ ${it.doc.title} · ${it.reason}`)),
          h('button', { class: 'btn', onclick: () => zoteroCheckDialog(it.doc.id, null) }, 'Check'))),
        sec('Probably on the tablet — choose', r.unsure, it => h('div', { class: 'zsug' }, h('div', null, h('b', null, it.title), h('div', { class: 'muted' }, [it.creators, it.year].filter(Boolean).join(' · '))),
          h('div', { class: 'row', style: { gap: '4px' } }, ...it.suggestions.slice(0, 2).map(s => h('button', { class: 'btn', title: `${s.title} · ${s.reason}`, onclick: async e => {
            try { await api('zotero/link', { method: 'POST', json: { doc: s.id, key: it.key } }); e.target.textContent = 'Linked ✓'; e.target.disabled = true; } catch (er) { fail(er); }
          } }, `Link to “${s.title.slice(0, 28)}${s.title.length > 28 ? '…' : ''}”`))))),
        sec('Not on the tablet', r.unmatched, it => h('div', { class: 'zsug' }, h('div', null, h('b', null, it.title), h('div', { class: 'muted' }, [it.creators, it.year].filter(Boolean).join(' · '))),
          h('button', { class: 'btn quiet', onclick: () => { close(); pickBook(it.title, async docId => { try { await api('zotero/link', { method: 'POST', json: { doc: docId, key: it.key } }); toast('Linked'); loadItems(); } catch (e) { fail(e); } }); } }, 'Link to a book…'))),
        h('div', { class: 'foot' }, h('button', { class: 'btn fill', onclick: close }, 'Done'))].filter(Boolean));
    });
  };
  const sync = async loud => {
    refresh.disabled = true; refresh.textContent = 'Fetching…';
    try { const s = await api('zotero/sync', { method: 'POST', timeout: 180000 }); showStatus(s); if (loud) toast(s.changed ? `${s.changed} change${s.changed === 1 ? '' : 's'} from Zotero` : 'Up to date'); await drawCols(); await loadItems(); }
    catch (e) { fail(e); }
    refresh.disabled = false; refresh.textContent = 'Refresh';
  };
  let qt = 0;
  search.addEventListener('input', () => { zstate.q = search.value; clearTimeout(qt); qt = setTimeout(loadItems, 200); });
  await drawCols(); await loadItems();
  if (zstate.item) showItem(zstate.item);
  sync(false); // what changed since last time, in the background
}

const STYLE_NAMES = { CHICAGO_AUTHOR_DATE: 'Chicago author-date', APA7: 'APA 7', CHICAGO_NOTES: 'Chicago notes', HARVARD: 'Harvard', MLA9: 'MLA 9' };
