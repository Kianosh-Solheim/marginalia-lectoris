// ------------------------------------------------------------------ convert saved web pages → EPUB / PDF
/* A book's chapters (or an article), saved from the publisher's reading page — SingleFile in Firefox
   or Chrome, or "Save page as… → Web page, complete" — become one EPUB and/or PDF the reader opens.
   Kept: the printed page numbers (a marker in the text, "go to page N" in the EPUB, the page in the
   PDF's running head), headings and contents, notes with links both ways, figures, tables,
   quotations, boxes, links between chapters, italics and the like. Everything happens in this
   browser; the PDF is set with Typst, the engine Writing uses. Sage Knowledge, Oxford Academic,
   Cambridge Core and Idunn have their own rules; any other site is read with general ones. */

const WC_SITES = [
  { key: 'sage', name: 'Sage Knowledge', host: /sagepub\.com/i, sniff: 'section.page-section',
    content: ['section.page-section'], pages: ['span.page-wrapper'] },
  { key: 'oup', name: 'Oxford Academic', host: /oup\.com|oxfordre\.com|oxfordscholarship|oxfordhandbooks/i, sniff: '[data-widgetname="ArticleFulltext"], .widget-ArticleFulltext',
    content: ['[data-widgetname="ArticleFulltext"]', '.widget-ArticleFulltext', '#ContentTab .content-inner-wrap', '.chapter-wrap', '.article-body', '#ContentTab'],
    pages: ['.page-number', '.pageNum', '.jp-pagebreak', '.page-marker'] },
  { key: 'cup', name: 'Cambridge Core', host: /cambridge\.org/i, sniff: '#htmlContent, .html-content',
    content: ['#htmlContent', '[data-test-id="html-content"]', '.html-content', '#content-container .content-box', '.chapter-body', '.article-body'],
    pages: ['.page-break', '.pagebreak', '.page-number', '[data-page]'] },
  { key: 'idunn', name: 'Idunn', host: /idunn\.no/i, sniff: '.hlFld-Fulltext',
    content: ['.hlFld-Fulltext', '.article__body', '.article-body', '.article-full-text', 'article .content', 'article'],
    pages: ['.page-number', '[data-page]'] },
];
const WC_GENERIC_PAGES = ['[epub\\:type~="pagebreak"]', '[role="doc-pagebreak"]', '[data-page-number]', '.pagebreak', '.page-break', '.pagenum', '.page-num', '.page-number', '.pageNum', '.pb'];
const WC_JUNK = 'script,style,noscript,button,input,form,template,iframe,select,textarea,nav,link,meta,svg,canvas,video,audio,object,embed,'
  + '.sf-hidden,.sr-only,.visually-hidden,.screen-reader-text,[hidden],.icon-download,a.link-download,.head_apa,.doi,th.hidden,'
  + '.link-page-number-block,.locked-info-content,.share,.social-share,.article-tools,.toolbar';
const WC_PAGE_RE = /^\s*\[?\s*(?:(?:page|pg|pp?|side|s)\s*\.?\s*)?([0-9]{1,4}|[ivxlcdm]{1,7})\s*\]?\s*$/i;
const WC_PAGE_TEXT_RE = /^\s*\[?\s*(?:page|pg\.?|p\.|side|s\.)\s*([0-9]{1,4}|[ivxlcdm]{1,7})\s*\]?\s*$/i;
const WC_KEEP_ATTRS = new Set(['id', 'class', 'href', 'src', 'alt', 'colspan', 'rowspan', 'lang', 'title', 'epub:type', 'role', 'aria-label', 'data-page', 'start', 'type']);
const WC_INLINE = [[/^(hi-)?(bold-?italic)$/i, 'strong', 'italic'], [/^(hi-)?(italic|ital|it|emphasis)$/i, 'em'], [/^(hi-)?(bold|strong)$/i, 'strong'],
  [/^(hi-)?(superscript|sup)$/i, 'sup'], [/^(hi-)?(subscript|sub)$/i, 'sub'], [/^(hi-)?(underline)$/i, 'u'], [/^(hi-)?(small-?caps|sc)$/i, 'span', 'smallcaps']];

function wcRename(el, tag) {
  if (el.tagName.toLowerCase() === tag) return el;
  const n = el.ownerDocument.createElement(tag);
  for (const a of [...el.attributes]) n.setAttribute(a.name, a.value);
  while (el.firstChild) n.appendChild(el.firstChild);
  el.replaceWith(n);
  return n;
}
const wcPText = el => { let n = 0; el.querySelectorAll('p').forEach(p => { n += p.textContent.length; }); return n; };
const wcEsc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const wcSlug = (s, n = 70) => (String(s).replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, n).trim()) || 'Book';

// ------------------------------------------------------------------ reading one saved page

/** First look at a saved page: what it is, without changing anything. */
function wcParse(html, fileName) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const meta = n => [...doc.querySelectorAll(`meta[name="${n}"], meta[property="${n}"]`)].map(m => (m.getAttribute('content') || '').trim()).filter(Boolean);
  const one = (...ns) => { for (const n of ns) { const v = meta(n); if (v.length) return v[0]; } return ''; };
  const head = html.slice(0, 6000);
  const saved = /url:\s*(https?:\/\/\S+)/i.exec(head) || /saved from url=\(\d+\)(\S+)/i.exec(head);
  const canon = doc.querySelector('link[rel="canonical"]');
  const url = (saved && saved[1]) || (canon && canon.getAttribute('href')) || one('og:url') || '';
  const site = WC_SITES.find(s => s.host.test(url)) || WC_SITES.find(s => s.sniff && doc.querySelector(s.sniff)) || null;
  const journal = one('citation_journal_title');
  const chapter = one('citation_title', 'dc.title', 'DC.title', 'og:title') || (doc.title || fileName).trim();
  const authors = meta('citation_author').length ? meta('citation_author') : meta('dc.creator').length ? meta('dc.creator') : meta('author');
  return {
    doc, url, site, fileName,
    canonical: wcNormUrl(url),
    chapter,
    book: one('citation_inbook_title', 'citation_book_title') || (journal ? chapter : ''),
    journal,
    authors: authors.map(a => a.includes(',') ? a.split(',').map(x => x.trim()).reverse().join(' ') : a),
    publisher: one('citation_publisher', 'dc.publisher', 'DC.publisher'),
    date: one('citation_publication_date', 'citation_date', 'dc.date', 'citation_online_date'),
    isbn: one('citation_isbn'), doi: one('citation_doi', 'dc.identifier'),
    lang: (one('citation_language', 'dc.language') || doc.documentElement.getAttribute('lang') || 'en').slice(0, 5),
    firstPage: parseInt(one('citation_firstpage'), 10) || null,
  };
}
function wcNormUrl(u) { return String(u || '').split('#')[0].split('?')[0].replace(/\/+$/, ''); }

function wcFindContent(doc, site) {
  const total = wcPText(doc.body) || 1;
  for (const sel of site ? site.content : []) {
    const el = doc.querySelector(sel);
    if (el && wcPText(el) > Math.min(400, total * 0.3)) return el;
  }
  for (const sel of ['[role="main"] article', 'main article', 'article', '[role="main"]', 'main']) {
    for (const el of doc.querySelectorAll(sel)) if (wcPText(el) > total * 0.6) return el;
  }
  let cur = doc.body;   // walk down while one child holds nearly all the text
  for (;;) {
    let best = null, bt = 0;
    for (const c of cur.children) { const t = wcPText(c); if (t > bt) { bt = t; best = c; } }
    if (!best || bt < wcPText(cur) * 0.85) return cur;
    cur = best;
  }
}

/** Page numbers of the printed book → <span class="pagenum" epub:type="pagebreak">. */
function wcPageEls(root, site) {
  const found = [];
  const add = e => { if (!found.some(f => f.contains(e) || e.contains(f))) found.push(e); };
  for (const sel of [...(site ? site.pages : []), ...WC_GENERIC_PAGES]) {
    let els; try { els = root.querySelectorAll(sel); } catch (_) { continue; }
    els.forEach(add);
  }
  if (!found.length) {   // plain "[Page 12]" / "p. 12" markers with no class to go by
    root.querySelectorAll('span, a, small, sup, div, p, b, strong').forEach(e => {
      const t = e.textContent;
      if (t.length < 16 && e.children.length <= 1 && WC_PAGE_TEXT_RE.test(t) && !(e.tagName === 'A' && !/^#/.test(e.getAttribute('href') || '#'))) add(e);
    });
  }
  return found;
}
function wcPages(root, site, label) {
  const found = wcPageEls(root, site);
  let n = 0;
  for (const e of found) {
    const raw = e.getAttribute('data-page') || e.getAttribute('data-page-number') || e.getAttribute('title') || e.getAttribute('aria-label') || e.textContent;
    const m = WC_PAGE_RE.exec(raw || '') || /([0-9]{1,4})\s*\]?\s*$/.exec(raw || '');
    if (!m) { if (!e.textContent.trim()) e.remove(); continue; }
    const s = e.ownerDocument.createElement('span');
    s.className = 'pagenum';
    s.setAttribute('epub:type', 'pagebreak'); s.setAttribute('role', 'doc-pagebreak');
    s.setAttribute('aria-label', m[1]); s.setAttribute('data-page', m[1]);
    s.id = 'page-' + m[1] + (root.querySelector('#page-' + m[1]) ? '-' + (++n) : '');
    s.textContent = label + m[1];
    e.replaceWith(s);
  }
}

function wcClean(root) {
  const it = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_COMMENT);
  const cs = []; while (it.nextNode()) cs.push(it.currentNode); cs.forEach(c => c.remove());
  root.querySelectorAll(WC_JUNK).forEach(e => e.remove());
  root.querySelectorAll('wbr').forEach(e => e.remove());
  root.querySelectorAll('thead').forEach(t => { if (!t.textContent.trim()) t.remove(); });
  root.querySelectorAll('span[class]').forEach(sp => {
    for (const c of sp.classList) {
      const r = WC_INLINE.find(([re]) => re.test(c));
      if (!r) continue;
      const n = wcRename(sp, r[1]); n.removeAttribute('class'); if (r[2]) n.className = r[2];
      break;
    }
  });
  root.querySelectorAll('a').forEach(a => {
    a.removeAttribute('target');
    const href = a.getAttribute('href') || '';
    if (/^javascript:/i.test(href) || href === '#') a.replaceWith(...a.childNodes);
  });
}

/** Note references and the notes they point to, linked both ways. */
function wcNotes(root) {
  const byId = new Map(); root.querySelectorAll('[id]').forEach(e => byId.set(e.id, e));
  const backs = new Map(); let n = 0;
  for (const a of [...root.querySelectorAll('a[href^="#"]')]) {
    let id; try { id = decodeURIComponent(a.getAttribute('href').slice(1)); } catch (_) { continue; }
    const t = byId.get(id);
    const txt = a.textContent.trim();
    if (!t || t.contains(a) || txt.length > 6) continue;
    if (!(a.querySelector('sup') || a.closest('sup') || /^\[?\(?\d{1,3}[a-z]?\)?\]?$|^[*†‡§¶]+$/.test(txt) || /fn|footnote|noteref|endnote/i.test(a.className))) continue;
    a.className = 'noteref'; a.setAttribute('epub:type', 'noteref'); a.setAttribute('role', 'doc-noteref');
    if (!backs.has(id)) { a.id = 'ref-' + id.replace(/[^\w.:-]/g, '_') + (byId.has('ref-' + id) ? '-' + (++n) : ''); backs.set(id, a.id); }
    else a.removeAttribute('id');
  }
  for (const [id, back] of backs) {
    const t = byId.get(id);
    if (!t || !root.contains(t)) continue;
    const blockTags = /^(P|LI|DIV|ASIDE|DD|SECTION)$/;
    let block = blockTags.test(t.tagName) ? t : t.parentElement && t.parentElement.closest('p, li, div, aside, dd');
    if (!block || !root.contains(block) || block.textContent.length > 4000) continue;
    const doc = root.ownerDocument;
    if (block !== t) {   // the number in the note becomes the way back
      const lab = doc.createElement('a'); lab.className = 'note-label'; lab.setAttribute('href', '#' + back);
      lab.textContent = t.textContent.trim() || '↑';
      t.replaceWith(lab);
      if (!block.id) block.id = id; else { const anchor = doc.createElement('span'); anchor.id = id; block.prepend(anchor); }
    } else {
      const lab = doc.createElement('a'); lab.className = 'note-back'; lab.setAttribute('href', '#' + back); lab.textContent = ' ↑';
      block.append(lab);
    }
    block.setAttribute('epub:type', 'footnote');
    if (block.tagName === 'LI') continue;
    if (block.tagName !== 'ASIDE') {
      const kids = [...block.childNodes];
      const aside = wcRename(block, 'aside');
      if (!kids.some(k => k.nodeType === 1 && /^(P|DIV|UL|OL|TABLE|BLOCKQUOTE)$/.test(k.tagName))) {
        const p = doc.createElement('p'); while (aside.firstChild) p.appendChild(aside.firstChild); aside.append(p);
      }
      aside.className = 'note'; aside.setAttribute('role', 'doc-footnote');
    }
  }
  root.querySelectorAll('div.appendix, div.notes, div.footnotes, div.endnotes, div.fn-group, section.notes, section.footnotes, section.endnotes').forEach(sec => {
    if (!sec.querySelector('[epub\\:type="footnote"]')) return;
    const s = wcRename(sec, 'section'); s.className = 'endnotes'; s.setAttribute('epub:type', 'endnotes'); s.setAttribute('role', 'doc-endnotes');
  });
}

/** Figures, tables, boxes, quotations; then the headings get the levels of a book. */
function wcStructure(root, site, chapterTitle) {
  const doc = root.ownerDocument;
  root.querySelectorAll('div.figure, div.fig, figure').forEach(f0 => {
    const fig = wcRename(f0, 'figure'); fig.className = 'figure';
    const cap = fig.querySelector('figcaption, .caption, .fig-caption, h2, h3, h4, h5, .head, .title');
    if (cap) { const c = wcRename(cap, 'figcaption'); [...c.attributes].forEach(a => { if (a.name !== 'id') c.removeAttribute(a.name); }); fig.append(c); }
  });
  root.querySelectorAll('div.table-wrap, div.table, .table-container').forEach(tw => {
    const tbl = tw.querySelector('table'); if (!tbl) return;
    tw.className = 'table-wrap';
    const th = tw.querySelector('.tableHead, .table-caption, .caption, .label') || null;
    if (th && !tbl.contains(th) && !tbl.querySelector('caption')) {
      const cap = doc.createElement('caption'); while (th.firstChild) cap.appendChild(th.firstChild); th.remove(); tbl.prepend(cap);
    }
  });
  root.querySelectorAll('table').forEach(tbl => {
    [...tbl.attributes].forEach(a => { if (a.name !== 'id') tbl.removeAttribute(a.name); });
    const first = tbl.querySelector('tr');
    if (first && !tbl.querySelector('thead, th')) {
      const cells = [...first.children];
      if (cells.length && cells.every(c => { const s = c.querySelector('strong, b'); return s && s.textContent.trim() === c.textContent.trim(); })) cells.forEach(c => wcRename(c, 'th'));
    }
  });
  root.querySelectorAll('div.border-box, div.boxed-text, div.textbox, div.box, aside.box, div.sidebar').forEach(b0 => {
    const keep = [...b0.classList].filter(c => /^(green|blue|grey|gray)$/.test(c));
    const box = wcRename(b0, 'aside'); box.className = ['box', ...keep].join(' ');
    box.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach(hh => { const p = wcRename(hh, 'p'); p.className = 'box-head'; });
  });
  root.querySelectorAll('div.quote, div.disp-quote, div.extract, div.blockquote').forEach(q => { wcRename(q, 'blockquote').className = 'quote'; });
  root.querySelectorAll('.bibl, .attrib, .attribution').forEach(b => { const p = wcRename(b, 'p'); p.className = 'bibl'; });

  // headings: the chapter's title is h1, its sections h2, h3 …
  const hs = [...root.querySelectorAll('h1, h2, h3, h4, h5, h6')].filter(x => x.textContent.trim() || x.remove());
  let lv;
  if (site && site.key === 'sage') {
    lv = hs.map(x => /\bchapter\b/i.test(x.className) ? 1 : 1 + Math.max(1, wcDepth(x, 'section.page-subsection')));
  } else if (hs.length) {
    const raw = hs.map(x => +x.tagName[1]), min = Math.min(...raw);
    const single = raw.filter(l => l === min).length === 1 && raw[0] === min;
    lv = raw.map(l => l - min + (single ? 1 : 2));
    if (!single && chapterTitle) { const t = doc.createElement('h1'); t.textContent = chapterTitle; root.prepend(t); }
  } else if (chapterTitle) { const t = doc.createElement('h1'); t.textContent = chapterTitle; root.prepend(t); lv = []; }
  hs.forEach((x, i) => { const n = wcRename(x, 'h' + Math.min(6, lv[i])); n.removeAttribute('class'); });
  root.querySelectorAll('section, div, ul, ol, li, p').forEach(e => {
    if (!/^(endnotes|quote|bibl|box-head|figure|table-wrap|note)$/.test(e.className) && !e.classList.contains('box')) e.removeAttribute('class');
  });
}
function wcDepth(el, sel) { let n = 0; for (let p = el.parentElement; p; p = p.parentElement) if (p.matches(sel)) n++; return n; }

/** Pictures stored in the page itself (SingleFile keeps them as data: links) → files in the book. */
async function wcImages(root, store) {
  for (const img of [...root.querySelectorAll('img')]) {
    const src = img.getAttribute('src') || '';
    const alt0 = img.getAttribute('alt') || '';
    let rec = store.bySrc.get(src);
    if (!rec && /^data:image\//.test(src)) {
      try {
        let blob = await (await fetch(src)).blob();
        let type = blob.type, w = 0, hgt = 0;
        if (blob.size < 200) throw new Error('placeholder');
        if (/svg/.test(type)) {
          const im = new Image(); im.src = src; await im.decode().catch(() => {}); w = im.naturalWidth || 600; hgt = im.naturalHeight || 400;
        } else {
          const bmp = await createImageBitmap(blob); w = bmp.width; hgt = bmp.height;
          if (!/jpe?g|png|gif/.test(type)) {   // e-readers and Typst want JPEG/PNG/GIF
            const cv = document.createElement('canvas'); cv.width = w; cv.height = hgt;
            const g = cv.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, w, hgt); g.drawImage(bmp, 0, 0);
            blob = await new Promise(r => cv.toBlob(r, 'image/jpeg', 0.9)); type = 'image/jpeg';
          }
          if (bmp.close) bmp.close();
        }
        if (w < 3 || hgt < 3) throw new Error('tiny');
        const ext = /png/.test(type) ? 'png' : /gif/.test(type) ? 'gif' : /svg/.test(type) ? 'svg' : 'jpg';
        rec = { name: `img${String(store.list.length + 1).padStart(3, '0')}.${ext}`, blob, type: /jpe?g/.test(type) ? 'image/jpeg' : type, w, h: hgt };
        store.list.push(rec); store.bySrc.set(src, rec);
      } catch (_) { rec = null; }
    }
    if (!rec) { img.remove(); continue; }
    let alt = alt0;
    if (!alt) { const f = img.closest('figure'); const c = f && f.querySelector('figcaption'); alt = c ? c.textContent.trim() : 'Illustration'; }
    [...img.attributes].forEach(a => img.removeAttribute(a.name));
    img.setAttribute('src', 'images/' + rec.name); img.setAttribute('alt', alt.slice(0, 300));
    img.dataset.w = rec.w; img.dataset.h = rec.h;
    // a large picture inside a paragraph stands on its own after it
    const p = img.closest('p');
    if (p && rec.w >= 160 && !img.closest('figure')) { const fig = img.ownerDocument.createElement('figure'); fig.className = 'figure'; p.after(fig); fig.append(img); }
  }
}

function wcStripAttrs(root) {
  root.querySelectorAll('*').forEach(t => {
    for (const a of [...t.attributes]) {
      if (a.name === 'data-w' || a.name === 'data-h') continue;
      if (!WC_KEEP_ATTRS.has(a.name)) t.removeAttribute(a.name);
    }
    if (t.getAttribute('class') === '') t.removeAttribute('class');
    if (t.hasAttribute('aria-label') && t.getAttribute('role') !== 'doc-pagebreak') t.removeAttribute('aria-label');
    if (t.hasAttribute('role') && !/^doc-/.test(t.getAttribute('role'))) t.removeAttribute('role');
  });
}

/** Ids unique across the book (c1-…, c2-…) and safe as Typst labels. */
function wcPrefixIds(root, prefix) {
  const map = new Map(), used = new Set();
  root.querySelectorAll('[id]').forEach(t => {
    const old = t.id;
    let nu = prefix + old.replace(/[^A-Za-z0-9_.:-]/g, '_');
    while (used.has(nu)) nu += '_';
    used.add(nu); if (!map.has(old)) map.set(old, nu); t.id = nu;
  });
  root.querySelectorAll('a[href^="#"]').forEach(a => {
    let k = a.getAttribute('href').slice(1); try { k = decodeURIComponent(k); } catch (_) {}
    if (map.has(k)) a.setAttribute('href', '#' + map.get(k));
  });
  return used;
}

/** One saved page → a chapter of the book. */
async function wcChapter(info, n, opts, store) {
  const { doc, site } = info;
  const src = wcFindContent(doc, site);
  const root = doc.createElement('div');
  root.append(src.cloneNode(true));
  wcPages(root, site, opts.label);
  wcClean(root);
  wcNotes(root);
  wcStructure(root, site, info.chapter);
  await wcImages(root, store);
  wcStripAttrs(root);
  // give headings ids for the contents
  let k = 0;
  root.querySelectorAll('h1, h2').forEach(x => { if (!x.id) x.id = 'h' + (++k); });
  const ids = wcPrefixIds(root, `c${n}-`);
  // peel off the wrappers that hold nothing but the chapter
  while (root.children.length === 1 && /^(DIV|SECTION|ARTICLE|MAIN)$/.test(root.firstElementChild.tagName) && !root.firstElementChild.id) root.firstElementChild.replaceWith(...root.firstElementChild.childNodes);
  const h1 = root.querySelector('h1');
  const pages = [...root.querySelectorAll('span.pagenum')].map(s => [s.getAttribute('data-page'), s.id]);
  const firstNum = pages.map(p => parseInt(p[0], 10)).find(x => !isNaN(x));
  return {
    info, root, ids, pages, n,
    title: info.chapter || (h1 ? h1.textContent.trim() : info.fileName),
    firstPage: firstNum != null ? firstNum : info.firstPage,
    toc: [...root.querySelectorAll('h1, h2')].map(x => [+x.tagName[1], x.textContent.replace(/\s+/g, ' ').trim(), x.id]),
  };
}

/** Links between chapters: to an id in another chapter, or to another chapter's web address. */
function wcLinks(chs, mode) {
  const idFile = new Map(), urlCh = new Map();
  chs.forEach(c => { c.ids.forEach(i => idFile.set(i, c)); if (c.info.canonical) urlCh.set(c.info.canonical, c); });
  chs.forEach(c => c.root.querySelectorAll('a[href]').forEach(a => {
    const href = a.getAttribute('href');
    if (href.startsWith('#')) {
      const id = href.slice(1);
      if (!c.ids.has(id) && idFile.has(id)) { if (mode === 'epub') a.setAttribute('href', idFile.get(id).file + href); }
      else if (!c.ids.has(id)) a.replaceWith(...a.childNodes);   // a target that was not saved
      return;
    }
    const other = urlCh.get(wcNormUrl(href));
    if (other) a.setAttribute('href', mode === 'epub' ? other.file : '#' + other.anchor);
  }));
}

// ------------------------------------------------------------------ EPUB

const WC_CSS = `@charset "utf-8";
body { font-family: Charter, "Bitstream Charter", Georgia, serif; line-height: 1.5; margin: 0 4%; text-align: justify; hyphens: auto; -webkit-hyphens: auto; }
h1, h2, h3, h4, h5, h6, figcaption, caption, .box-head, .pagenum, .note-label, th { font-family: "Helvetica Neue", Arial, sans-serif; text-align: left; hyphens: manual; -webkit-hyphens: manual; }
h1 { font-size: 1.7em; line-height: 1.2; margin: .6em 0 1em; }
h2 { font-size: 1.3em; line-height: 1.25; margin: 1.6em 0 .5em; page-break-after: avoid; }
h3 { font-size: 1.12em; margin: 1.3em 0 .4em; page-break-after: avoid; }
h4, h5, h6 { font-size: 1em; font-style: italic; margin: 1.1em 0 .3em; }
p { margin: 0; text-indent: 1.3em; orphans: 2; widows: 2; }
h1 + p, h2 + p, h3 + p, h4 + p, h5 + p, h6 + p, blockquote + p, figure + p, .table-wrap + p, aside + p, ul + p, ol + p, table + p, p:first-child { text-indent: 0; }
.smallcaps { font-variant: small-caps; } strong.italic { font-style: italic; }
sup, sub { font-size: .72em; line-height: 0; }
a { color: #0b5a8c; text-decoration: none; }
.pagenum { font-size: .62em; font-weight: 600; color: #6b6b6b; text-indent: 0; font-style: normal; white-space: nowrap; padding: 0 .25em; margin: 0 .15em; border: 1px solid #b9b9b9; border-radius: .3em; vertical-align: .15em; }
body.no-pagenums .pagenum { display: none; }
blockquote { margin: 1em 1.6em; font-size: .95em; }
blockquote p { text-indent: 0; margin-bottom: .4em; }
p.bibl { text-align: right; font-style: italic; margin-top: .3em; text-indent: 0; }
ul, ol { margin: .6em 0 .8em 1.6em; padding: 0; } li { margin: .25em 0; } li p { text-indent: 0; }
aside.box { border: 1px solid #08c9c4; background: #e6f9f9; padding: .8em 1em .6em; margin: 1.2em 0; }
aside.box.blue { border-color: #0b5a8c; background: #eaf2f8; } aside.box.grey, aside.box.gray { border-color: #999; background: #f2f2f2; }
aside.box p { text-indent: 0; margin-bottom: .45em; } .box-head { font-weight: 700; margin: 0 0 .35em; }
figure { margin: 1.2em 0; text-align: center; page-break-inside: avoid; } figure img { max-width: 100%; height: auto; }
figcaption, caption { font-size: .85em; font-weight: 600; margin: .45em 0; text-align: left; caption-side: top; }
table { border-collapse: collapse; width: 100%; font-size: .82em; line-height: 1.35; margin: 1em 0; }
th, td { border-top: 1px solid #c7c7c7; border-bottom: 1px solid #c7c7c7; padding: .3em .45em; vertical-align: top; text-align: left; }
th { background: #eee; } tr:nth-child(even) td { background: #f7f7f7; } td p, th p { text-indent: 0; }
section.endnotes { margin-top: 2em; font-size: .88em; border-top: 1px solid #bbb; padding-top: .3em; }
aside.note { margin: .45em 0; } aside.note p { text-indent: 0; } .note-label { font-weight: 700; margin-right: .35em; }
.titlepage { text-align: center; margin-top: 20%; } .titlepage h1 { text-align: center; font-size: 2em; }
.titlepage p { text-indent: 0; text-align: center; } .titlepage .author { font-size: 1.25em; margin: 1.2em 0; } .titlepage .small { font-size: .85em; color: #555; }
nav ol { list-style: none; margin-left: 0; } nav ol ol { margin-left: 1.3em; } nav li { margin: .35em 0; }
img.cover { display: block; width: 100%; height: auto; }
`;

function wcXhtml(title, body, lang, bodyClass) {
  return '<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n'
    + `<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${wcEsc(lang)}" xml:lang="${wcEsc(lang)}">\n`
    + `<head>\n<meta charset="utf-8"/>\n<title>${wcEsc(title)}</title>\n<link rel="stylesheet" type="text/css" href="style.css"/>\n</head>\n`
    + `<body${bodyClass ? ` class="${bodyClass}"` : ''}>\n${body}\n</body>\n</html>\n`;
}
function wcSerialize(root) {
  const s = new XMLSerializer();
  return [...root.childNodes].map(n => s.serializeToString(n)).join('')
    .replace(/ xmlns="http:\/\/www\.w3\.org\/1999\/xhtml"/g, '')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
}

async function wcCover(meta, nChapters) {
  const W = 1404, H = 1872, cv = document.createElement('canvas'); cv.width = W; cv.height = H;
  const g = cv.getContext('2d');
  g.fillStyle = '#f4f1ea'; g.fillRect(0, 0, W, H);
  g.fillStyle = '#08a5a1'; g.fillRect(0, 0, W, 60); g.fillRect(0, H - 60, W, 60);
  const wrap = (text, font, width) => {
    g.font = font; const out = []; let cur = '';
    for (const w of String(text).split(/\s+/)) { const t = (cur + ' ' + w).trim(); if (g.measureText(t).width <= width) cur = t; else { if (cur) out.push(cur); cur = w; } }
    if (cur) out.push(cur); return out;
  };
  const [title, ...rest] = String(meta.title).split(':'); const sub = rest.join(':').trim();
  let y = 470; g.textBaseline = 'alphabetic';
  g.fillStyle = '#1a1a1a';
  for (const l of wrap(title.trim(), 'bold 104px Georgia, "Times New Roman", serif', W - 220)) { g.fillText(l, 110, y); y += 128; }
  if (sub) { y += 20; g.fillStyle = '#3a3a3a'; for (const l of wrap(sub, 'bold 58px Georgia, serif', W - 220)) { g.fillText(l, 110, y); y += 76; } }
  g.fillStyle = '#08a5a1'; g.fillRect(110, y + 30, 220, 8);
  g.fillStyle = '#1a1a1a'; let ay = y + 130;
  for (const l of wrap(meta.authors.join(', '), '60px "Helvetica Neue", Arial, sans-serif', W - 220)) { g.fillText(l, 110, ay); ay += 72; }
  g.font = '36px "Helvetica Neue", Arial, sans-serif'; g.fillStyle = '#555';
  g.fillText([meta.publisher, meta.date && meta.date.slice(0, 4)].filter(Boolean).join(' · '), 110, H - 160);
  g.fillStyle = '#777'; g.fillText(nChapters === 1 ? '1 chapter' : nChapters + ' chapters', 110, H - 110);
  return new Promise(r => cv.toBlob(r, 'image/jpeg', 0.88));
}

async function wcEpub(chs, meta, store, opts) {
  chs.forEach((c, i) => { c.file = `chap${String(i + 1).padStart(2, '0')}.xhtml`; c.anchor = `top-chap${String(i + 1).padStart(2, '0')}`; });
  wcLinks(chs, 'epub');
  const zip = new JSZip();
  zip.file('mimetype', 'application/epub+zip', { compression: 'STORE' });
  zip.file('META-INF/container.xml', '<?xml version="1.0" encoding="utf-8"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>');
  const O = zip.folder('OEBPS'), lang = meta.lang || 'en';
  O.file('style.css', WC_CSS);
  const manifest = ['<item id="css" href="style.css" media-type="text/css"/>', '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'];
  const spine = [];
  const cover = await wcCover(meta, chs.length);
  if (cover) {
    O.file('images/cover.jpg', cover);
    manifest.push('<item id="cover-img" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>', '<item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>');
    O.file('cover.xhtml', wcXhtml('Cover', '<section epub:type="cover"><img class="cover" src="images/cover.jpg" alt="Cover"/></section>', lang));
    spine.push('cover');
  }
  for (const im of store.list) { O.file('images/' + im.name, im.blob); manifest.push(`<item id="i_${im.name.replace(/\W/g, '_')}" href="images/${im.name}" media-type="${im.type}"/>`); }
  const ident = [meta.isbn && 'ISBN ' + meta.isbn, meta.doi && 'DOI ' + meta.doi].filter(Boolean).join(' · ');
  O.file('title.xhtml', wcXhtml(meta.title, `<section class="titlepage" epub:type="titlepage"><h1>${wcEsc(meta.title)}</h1><p class="author">${wcEsc(meta.authors.join(', '))}</p>`
    + `<p class="small">${wcEsc([meta.publisher, meta.date].filter(Boolean).join(' · '))}</p><p class="small">${wcEsc(ident)}</p>`
    + (chs.some(c => c.pages.length) ? '<p class="small">The page markers in the text give the pages of the printed edition.</p>' : '') + '</section>', lang));
  manifest.push('<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>', '<item id="titlepage" href="title.xhtml" media-type="application/xhtml+xml"/>');
  spine.push('titlepage', 'nav');
  const bodyClass = opts.showPages ? '' : 'no-pagenums';
  chs.forEach((c, i) => {
    const id = 'chap' + String(i + 1).padStart(2, '0');
    O.file(c.file, wcXhtml(c.title, `<section epub:type="chapter" role="doc-chapter" id="${c.anchor}">\n${wcSerialize(c.root)}\n</section>`, lang, bodyClass));
    manifest.push(`<item id="${id}" href="${c.file}" media-type="application/xhtml+xml"/>`); spine.push(id);
  });
  const toc = chs.map(c => {
    const subs = c.toc.filter(t => t[0] === 2).map(t => `<li><a href="${c.file}#${t[2]}">${wcEsc(t[1])}</a></li>`).join('');
    return `<li><a href="${c.file}">${wcEsc(c.title)}</a>${subs ? `<ol>${subs}</ol>` : ''}</li>`;
  }).join('');
  const pageList = chs.flatMap(c => c.pages.map(([num, pid]) => `<li><a href="${c.file}#${pid}">${wcEsc(num)}</a></li>`)).join('');
  O.file('nav.xhtml', wcXhtml('Contents', `<nav epub:type="toc" id="toc" role="doc-toc"><h1>Contents</h1><ol>${toc}</ol></nav>\n`
    + (pageList ? `<nav epub:type="page-list" id="page-list" hidden=""><h2>Pages</h2><ol>${pageList}</ol></nav>\n` : '')
    + `<nav epub:type="landmarks" id="landmarks" hidden=""><h2>Landmarks</h2><ol>${cover ? '<li><a epub:type="cover" href="cover.xhtml">Cover</a></li>' : ''}`
    + `<li><a epub:type="toc" href="nav.xhtml">Contents</a></li><li><a epub:type="bodymatter" href="${chs[0].file}">Start</a></li></ol></nav>`, lang));
  // NCX, for older readers
  let po = 0;
  const points = chs.map(c => {
    const cp = ++po;
    const kids = c.toc.filter(t => t[0] === 2).map(t => { const p = ++po; return `<navPoint id="np${p}" playOrder="${p}"><navLabel><text>${wcEsc(t[1])}</text></navLabel><content src="${c.file}#${t[2]}"/></navPoint>`; }).join('');
    return `<navPoint id="np${cp}" playOrder="${cp}"><navLabel><text>${wcEsc(c.title)}</text></navLabel><content src="${c.file}"/>${kids}</navPoint>`;
  }).join('');
  const uid = 'urn:uuid:' + wcUuid(meta.title + '|' + chs.map(c => c.title).join('|'));
  O.file('toc.ncx', `<?xml version="1.0" encoding="utf-8"?>\n<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="${uid}"/><meta name="dtb:depth" content="2"/><meta name="dtb:totalPageCount" content="0"/><meta name="dtb:maxPageNumber" content="0"/></head><docTitle><text>${wcEsc(meta.title)}</text></docTitle><navMap>${points}</navMap></ncx>`);
  const modified = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
  const creators = meta.authors.map((a, i) => `<dc:creator id="cr${i + 1}">${wcEsc(a)}</dc:creator><meta refines="#cr${i + 1}" property="role" scheme="marc:relators">aut</meta>`).join('');
  O.file('content.opf', `<?xml version="1.0" encoding="utf-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="${wcEsc(lang)}"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">`
    + `<dc:identifier id="bookid">${uid}</dc:identifier><dc:title>${wcEsc(meta.title)}</dc:title>${creators}<dc:language>${wcEsc(lang)}</dc:language>`
    + (meta.publisher ? `<dc:publisher>${wcEsc(meta.publisher)}</dc:publisher>` : '') + (/^\d{4}(-\d\d(-\d\d)?)?$/.test(meta.date.replace(/\//g, '-')) ? `<dc:date>${wcEsc(meta.date.replace(/\//g, '-'))}</dc:date>` : '')
    + (meta.isbn ? `<dc:source>urn:isbn:${wcEsc(meta.isbn)}</dc:source>` : '') + `<meta property="dcterms:modified">${modified}</meta>` + (cover ? '<meta name="cover" content="cover-img"/>' : '')
    + `</metadata><manifest>${manifest.join('')}</manifest><spine toc="ncx">${spine.map(s => `<itemref idref="${s}"/>`).join('')}</spine></package>`);
  return zip.generateAsync({ type: 'blob', mimeType: 'application/epub+zip', compression: 'DEFLATE', compressionOptions: { level: 6 } });
}
function wcUuid(s) {   // a stable, name-based id (no crypto.subtle over plain http)
  let h1 = 0x811c9dc5, h2 = 0x01000193, out = '';
  for (let i = 0; i < 16; i++) {
    for (let j = 0; j < s.length; j++) { h1 = Math.imul(h1 ^ s.charCodeAt(j), 16777619) >>> 0; h2 = Math.imul(h2 + s.charCodeAt(j) + i, 2654435761) >>> 0; }
    out += ((h1 ^ h2) & 255).toString(16).padStart(2, '0');
  }
  return `${out.slice(0, 8)}-${out.slice(8, 12)}-5${out.slice(13, 16)}-a${out.slice(17, 20)}-${out.slice(20, 32)}`;
}

// ------------------------------------------------------------------ PDF (Typst)

const wcTyStr = s => '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, ' ') + '"';
const wcTyText = s => s.replace(/[\\#*_`$<>@\[\]~\/=+\-]/g, c => '\\' + c).replace(/(\d)\./g, '$1\\.');

/** The book as Typst source: the chapters' cleaned HTML, element by element. */
function wcTypst(chs, meta, opts) {
  chs.forEach((c, i) => { c.anchor = `top-chap${String(i + 1).padStart(2, '0')}`; });
  wcLinks(chs, 'pdf');
  const labels = new Set(), wanted = new Set();
  chs.forEach(c => {
    labels.add(c.anchor);
    c.root.querySelectorAll('[id]').forEach(e => labels.add(e.id));
    c.root.querySelectorAll('a[href^="#"]').forEach(a => wanted.add(a.getAttribute('href').slice(1)));
  });
  const [pw, ph] = opts.pageSize;
  const textW = (pw - 26) * 2.835, maxH = (ph - 36) * 2.835 * 0.82;   // pt

  const inl = (node, ctx) => {
    let out = '';
    for (const n of node.childNodes) {
      if (n.nodeType === 3) { out += wcTyText(n.nodeValue.replace(/[\s\n\r\t]+/g, ' ')).replace(/^[(.]/, '\\$&'); continue; }   // "#emph[x](" would go on as a call
      if (n.nodeType !== 1) continue;
      const t = n.tagName.toLowerCase(), cls = n.getAttribute('class') || '';
      const lab = n.id && wanted.has(n.id) ? `#metadata(none)<${n.id}>` : '';
      if (cls === 'pagenum') { out += `#pg(${wcTyStr(n.getAttribute('data-page'))})`; continue; }
      if (t === 'br') { out += ' #linebreak() '; continue; }
      if (t === 'img') { out += `#box(image(${wcTyStr(n.getAttribute('src'))}, height: 1.1em))`; continue; }
      const inner = inl(n, ctx);
      switch (t) {
        case 'em': case 'i': case 'cite': case 'dfn': case 'var': out += lab + `#emph[${inner}]`; break;
        case 'strong': case 'b': out += lab + (cls === 'italic' ? `#strong[#emph[${inner}]]` : `#strong[${inner}]`); break;
        case 'sup': out += lab + (/^#super\[/.test(inner.trim()) ? inner : `#super[${inner}]`); break;
        case 'sub': out += lab + `#sub[${inner}]`; break;
        case 'u': out += lab + `#underline[${inner}]`; break;
        case 's': case 'del': case 'strike': out += lab + `#strike[${inner}]`; break;
        case 'code': case 'kbd': case 'samp': out += lab + `#raw(${wcTyStr(n.textContent)})`; break;
        case 'a': {
          const href = n.getAttribute('href') || '';
          if (href.startsWith('#') && labels.has(href.slice(1))) {
            const body = cls === 'noteref' && !/#super\[/.test(inner) ? `#super[${inner}]` : inner;
            out += lab + `#link(<${href.slice(1)}>)[${cls === 'note-label' ? `#strong[${body}]` : body}]`;
          } else if (/^(https?|mailto):/i.test(href)) out += lab + `#link(${wcTyStr(href)})[${inner}]`;
          else if (cls === 'note-label') out += lab + `#strong[${inner}]`;
          else out += lab + inner;
          break;
        }
        default:
          out += lab + (cls === 'smallcaps' ? `#smallcaps[${inner}]` : inner);
      }
    }
    return out;
  };
  const para = s => {
    s = s.replace(/^\s+|\s+$/g, '');
    if (!s) return '';
    if (/^([=+\-]|\d+\.)/.test(s)) s = '#[]' + s;   // would start a heading or a list
    return s + '\n\n';
  };
  const BLOCK = /^(p|div|section|article|aside|blockquote|ul|ol|li|figure|figcaption|table|h[1-6]|hr|pre|dl|dt|dd|header|footer|main|caption)$/;
  const blocks = node => {
    let out = '', run = null;
    const flush = () => { if (run) { out += para(inl(run)); run = null; } };
    for (const n of [...node.childNodes]) {
      const isBlock = n.nodeType === 1 && BLOCK.test(n.tagName.toLowerCase()) && !(n.tagName === 'IMG');
      if (!isBlock) {
        if (n.nodeType === 3 && !n.nodeValue.trim() && !run) continue;
        if (!run) run = node.ownerDocument.createElement('span');
        run.append(n.cloneNode(true)); continue;
      }
      flush();
      out += block(n);
    }
    flush();
    return out;
  };
  const block = n => {
    const t = n.tagName.toLowerCase(), cls = n.getAttribute('class') || '';
    const lab = n.id && wanted.has(n.id) ? `#metadata(none)<${n.id}>\n` : '';
    if (/^h[1-6]$/.test(t)) {
      const txt = inl(n).trim();
      return txt ? `${lab}${'='.repeat(+t[1])} ${txt}\n\n` : '';
    }
    switch (t) {
      case 'p':
        if (cls === 'bibl') return lab + `#align(right, emph[${inl(n).trim()}])\n\n`;
        if (cls === 'box-head') return lab + `#block(below: 0.6em, strong[${inl(n).trim()}])\n\n`;
        return lab + para(inl(n));
      case 'blockquote': return lab + `#bq[\n${blocks(n)}]\n\n`;
      case 'ul': case 'ol': {
        const items = [...n.children].filter(li => li.tagName === 'LI').map(li => {
          const l = li.id && wanted.has(li.id) ? `#metadata(none)<${li.id}>` : '';
          return `[${l}${blocks(li).trim()}]`;
        });
        if (!items.length) return '';
        const start = t === 'ol' && n.getAttribute('start') ? `start: ${parseInt(n.getAttribute('start'), 10) || 1}, ` : '';
        return lab + `#${t === 'ol' ? 'enum' : 'list'}(${start}${items.join(', ')})\n\n`;
      }
      case 'figure': {
        const imgs = [...n.querySelectorAll('img')].map(im => {
          const w = +im.dataset.w || 600, h0 = +im.dataset.h || 400;
          const natW = w * 0.75, hAtW = Math.min(natW, textW) * h0 / w;
          const size = hAtW > maxH ? `height: ${Math.round(maxH)}pt` : natW < textW ? `width: ${Math.round(natW)}pt` : 'width: 100%';
          return `image(${wcTyStr(im.getAttribute('src'))}, ${size})`;
        });
        const cap = n.querySelector('figcaption');
        const rest = n.cloneNode(true); rest.querySelectorAll('img, figcaption').forEach(e => e.remove());
        const extra = rest.textContent.trim() ? blocks(rest) : '';
        return lab + `#fig((${imgs.join(', ')}${imgs.length === 1 ? ',' : ''}), [${cap ? inl(cap).trim() : ''}])\n${extra}\n`;
      }
      case 'table': return lab + table(n);
      case 'aside':
        if (/\bbox\b/.test(cls)) return lab + `#boxed[\n${blocks(n)}]\n\n`;
        if (cls === 'note') return lab + `#note[\n${blocks(n)}]\n\n`;
        return lab + blocks(n);
      case 'section':
        if (cls === 'endnotes') return lab + `#notes[\n${blocks(n)}]\n\n`;
        return lab + blocks(n);
      case 'hr': return '#line(length: 100%, stroke: 0.4pt + luma(160))\n\n';
      case 'pre': return lab + `#block(raw(${wcTyStr(n.textContent)}, block: true))\n\n`;
      case 'dl': return lab + [...n.children].map(x => x.tagName === 'DT' ? `#strong[${inl(x).trim()}] #linebreak()\n` : blocks(x)).join('') + '\n';
      case 'figcaption': case 'caption': return lab + `#tcap[${inl(n).trim()}]\n\n`;
      default: return lab + blocks(n);
    }
  };
  const table = tbl => {
    const rows = [...tbl.querySelectorAll('tr')].filter(r => r.closest('table') === tbl);
    if (!rows.length) return '';
    let cols = 0;
    rows.forEach(r => { let c = 0; [...r.children].forEach(x => { c += Math.max(1, parseInt(x.getAttribute('colspan'), 10) || 1); }); cols = Math.max(cols, c); });
    const cell = x => {
      const cs = parseInt(x.getAttribute('colspan'), 10) || 1, rs = parseInt(x.getAttribute('rowspan'), 10) || 1;
      let body = blocks(x).trim().replace(/\n\n+/g, ' #parbreak() ');
      if (x.tagName === 'TH') body = `#strong[${body}]`;
      const lab = x.id && wanted.has(x.id) ? `#metadata(none)<${x.id}>` : '';
      return cs > 1 || rs > 1 ? `table.cell(${cs > 1 ? `colspan: ${cs}, ` : ''}${rs > 1 ? `rowspan: ${rs}, ` : ''})[${lab}${body}]` : `[${lab}${body}]`;
    };
    const headRows = [];
    for (const r of rows) { if ([...r.children].every(x => x.tagName === 'TH') && r.children.length) headRows.push(r); else break; }
    const bodyRows = rows.slice(headRows.length);
    const cap = tbl.querySelector('caption');
    const hdr = headRows.length ? `table.header(${headRows.flatMap(r => [...r.children].map(cell)).join(', ')}), ` : '';
    return (cap ? `#tcap[${inl(cap).trim()}]\n` : '')
      + `#table(columns: ${cols}, ${hdr}${bodyRows.flatMap(r => [...r.children].map(cell)).join(', ')})\n\n`;
  };

  const lbl = wcTyStr(opts.label);
  const lang = (meta.lang || 'en').slice(0, 2).toLowerCase();
  let src = `#set document(title: ${wcTyStr(meta.title)}${meta.authors.length ? `, author: (${meta.authors.map(wcTyStr).join(', ')},)` : ''})
#let lbl = ${lbl}
#let pg(n) = [#metadata(n)<origpage>${opts.showPages ? '#box(inset: (x: 1.6pt), outset: (y: 1.2pt), stroke: 0.4pt + luma(165), radius: 1.5pt, text(size: 6.2pt, fill: luma(95), tracking: 0.02em, lbl + n))' : ''}]
#let bq(body) = pad(x: 1.4em, { set text(size: 0.95em); set par(first-line-indent: 0pt); body })
#let boxed(body) = block(width: 100%, inset: (x: 9pt, y: 8pt), stroke: 0.6pt + rgb("#08a5a1"), fill: rgb("#e6f9f9"), breakable: true, { set par(first-line-indent: 0pt); body })
#let note(body) = block(above: 0.5em, below: 0.5em, body)
#let notes(body) = { v(1.4em); line(length: 100%, stroke: 0.4pt + luma(160)); set text(size: 0.88em); set par(first-line-indent: 0pt, justify: false); body }
#let tcap(body) = block(below: 0.5em, sticky: true, text(size: 0.85em, weight: "bold", body))
#let fig(imgs, cap) = block(width: 100%, breakable: false, above: 1.1em, below: 1.1em, { set align(center); for i in imgs { i; v(0.2em) }; if cap != [] { set align(left); text(size: 0.85em, weight: "bold", cap) } })
#set page(width: ${pw}mm, height: ${ph}mm, margin: (top: 17mm, bottom: 15mm, x: 13mm),
  header: context {
    let p = here().page()
    let chs = query(heading.where(level: 1).before(here())).filter(x => x.outlined)
    let opening = query(heading.where(level: 1)).filter(x => x.location().page() == p).len() > 0
    if chs.len() > 0 and not opening {
      let ms = query(selector(<origpage>).before(here()))
      set text(size: 7.5pt, fill: luma(110))
      grid(columns: (1fr, auto), column-gutter: 1em, chs.last().body, if ms.len() > 0 { lbl + ms.last().value })
    }
  },
  footer: context { if here().page() > 1 { align(center, text(size: 8pt, fill: luma(90), counter(page).display())) } })
#set text(font: "Libertinus Serif", size: ${opts.fontSize}pt, lang: ${wcTyStr(lang)}, hyphenate: true)
#set par(justify: true, first-line-indent: 1.2em, leading: 0.62em, spacing: 0.62em)
#set table(stroke: (x: none, y: 0.4pt + luma(170)), inset: 4pt, fill: (_, y) => if calc.even(y) and y > 0 { luma(246) })
#show table: set text(size: 0.84em)
#show table: set par(justify: false, first-line-indent: 0pt)
#show heading: set par(justify: false)
#show heading.where(level: 1): it => block(above: 0.4em, below: 1.2em, text(size: 1.6em, weight: "bold", it.body))
#show heading.where(level: 2): it => block(above: 1.5em, below: 0.6em, sticky: true, text(size: 1.2em, weight: "bold", it.body))
#show heading.where(level: 3): it => block(above: 1.3em, below: 0.5em, sticky: true, text(size: 1.06em, weight: "bold", it.body))
#show heading: it => if it.level > 3 { block(above: 1.1em, below: 0.4em, sticky: true, emph(it.body)) } else { it }
#show link: it => if type(it.dest) == str { text(fill: rgb("#0b5a8c"), it) } else { it }

#page(header: none, footer: none)[
  #v(22%)
  #align(center)[
    #text(size: 21pt, weight: "bold")[${wcTyText(meta.title)}]
    #v(1.4em)
    #text(size: 13pt)[${wcTyText(meta.authors.join(', '))}]
    #v(1.2em)
    #text(size: 9pt, fill: luma(80))[${wcTyText([meta.publisher, meta.date].filter(Boolean).join(' · '))}]
    ${meta.isbn ? `#linebreak() #text(size: 9pt, fill: luma(80))[ISBN ${wcTyText(meta.isbn)}]` : ''}
    ${chs.some(c => c.pages.length) ? `#v(3em) #text(size: 8pt, fill: luma(110))[The page markers in the text and the page in the running head are those of the printed edition.]` : ''}
  ]
]
#outline(title: [Contents], depth: 2, indent: auto)
`;
  chs.forEach(c => { src += `\n#pagebreak(weak: true)\n#metadata(none)<${c.anchor}>\n` + blocks(c.root); });
  return src;
}

// ------------------------------------------------------------------ the whole job

/** Saved pages (File objects) → { epub?: Blob, pdf?: Blob, name, meta }. */
async function wcConvert(files, opts, progress = () => {}) {
  const infos = [];
  for (const f of files) { progress(`Reading ${f.name}…`); infos.push(wcParse(await f.text(), f.name)); }
  const groups = opts.separate ? infos.map(i => [i]) : [infos];
  const results = [];
  for (const group of groups) {
    const store = { list: [], bySrc: new Map() };
    let chs = [];
    for (let i = 0; i < group.length; i++) { progress(`Chapter ${i + 1} of ${group.length}: ${group[i].chapter}`); chs.push(await wcChapter(group[i], i + 1, opts, store)); }
    chs = chs.map((c, i) => [c, i]).sort((a, b) => ((a[0].firstPage == null) - (b[0].firstPage == null)) || ((a[0].firstPage || 0) - (b[0].firstPage || 0)) || a[1] - b[1]).map(x => x[0]);
    const m0 = chs[0].info;
    const meta = {
      title: opts.title || (group.length === 1 && opts.separate ? `${m0.book ? m0.book + ' — ' : ''}${m0.chapter}` : (m0.book || m0.chapter)),
      authors: opts.authors && opts.authors.length ? opts.authors : m0.authors,
      publisher: m0.publisher, date: m0.date, isbn: m0.isbn, doi: m0.doi, lang: m0.lang,
    };
    const name = wcSlug(opts.separate && group.length === 1 ? `${m0.book ? wcSlug(m0.book, 40) + ' - ' : ''}${m0.chapter}` : meta.title, 90);
    const res = { name, meta, chapters: chs.length, pages: chs.reduce((n, c) => n + c.pages.length, 0), images: store.list.length };
    if (opts.epub) { progress('Making the EPUB…'); res.epub = await wcEpub(chs.map(c => wcRecopy(c)), meta, store, opts); }
    if (opts.pdf) {
      progress('Setting the PDF…');
      const files2 = {}; store.list.forEach(im => { files2['images/' + im.name] = im.blob; });
      res.typst = wcTypst(chs.map(c => wcRecopy(c)), meta, opts);
      res.pdf = await typstPdf(res.typst, files2);
    }
    results.push(res);
  }
  return results;
}
/** Each format rewrites links its own way, so each works on its own copy of a chapter. */
function wcRecopy(c) { const root = c.root.cloneNode(true); return Object.assign({}, c, { root }); }

// ------------------------------------------------------------------ the dialog

var wcDropTarget = null;   // while the dialog is open, files dropped anywhere come to it (see the drop handler in app.js)
function convertDialog() {
  const opts = {
    epub: localGet('ml.wc.epub', '1') === '1', pdf: localGet('ml.wc.pdf', '1') === '1',
    separate: false, showPages: localGet('ml.wc.pages', '1') === '1',
    label: localGet('ml.wc.label', 'p. '), pageSize: [158, 210], fontSize: 10.5,
    toLibrary: localGet('ml.wc.lib', '1') === '1', download: localGet('ml.wc.dl', '0') === '1',
    category: state.category === 'ARTICLE' ? 'ARTICLE' : 'BOOK',
  };
  let files = [], infos = [];
  modal((box, close) => {
    box.style.width = '720px';
    const pick = h('input', { type: 'file', multiple: true, accept: '.html,.htm,.xhtml', hidden: true });
    const list = h('div', { class: 'wc-list' });
    const title = h('input', { placeholder: 'From the pages' }), authors = h('input', { placeholder: 'From the pages' });
    const status = h('div', { class: 'muted wc-status' });
    const go = h('button', { class: 'btn fill', disabled: true }, 'Convert');
    const chip = (label, on, fn, tip) => h('button', { class: 'chip' + (on() ? ' on' : ''), title: tip || null, onclick: e => { fn(); e.currentTarget.classList.toggle('on', on()); draw(); } }, label);
    const optsBox = h('div');
    const sizes = [['E-ink 10.3″', [158, 210]], ['E-ink 7.8″', [120, 160]], ['A5', [148, 210]], ['A4', [210, 297]]];
    const draw = () => {
      clear(optsBox).append(
        h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'Make'),
          chip('EPUB', () => opts.epub, () => { opts.epub = !opts.epub; localSet('ml.wc.epub', opts.epub ? '1' : '0'); }),
          chip('PDF', () => opts.pdf, () => { opts.pdf = !opts.pdf; localSet('ml.wc.pdf', opts.pdf ? '1' : '0'); }),
          h('span', { class: 'spacer' }),
          chip('One book', () => !opts.separate, () => { opts.separate = false; }, 'All the chosen pages become the chapters of one book'),
          chip('One per page', () => opts.separate, () => { opts.separate = true; }, 'Each saved page becomes a file of its own')),
        h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'Page numbers'),
          chip('Show in the text', () => opts.showPages, () => { opts.showPages = !opts.showPages; localSet('ml.wc.pages', opts.showPages ? '1' : '0'); }, 'Off: no markers in the text; “go to page” and the PDF\'s running head still have them'),
          (() => { const i = h('input', { value: opts.label, style: { width: '60px' }, title: 'Before the number: “p. ”, “s. ”, or nothing' }); i.addEventListener('input', () => { opts.label = i.value; localSet('ml.wc.label', i.value); }); return i; })(),
          h('span', { class: 'muted', style: { fontSize: '12px' } }, 'before the number')),
        opts.pdf ? h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'PDF page'),
          sizes.map(([l, s]) => chip(l, () => opts.pageSize[0] === s[0] && opts.pageSize[1] === s[1], () => { opts.pageSize = s; })),
          h('span', { class: 'spacer' }),
          chip('Smaller', () => false, () => { opts.fontSize = Math.max(8, opts.fontSize - 0.5); }), h('span', { class: 'mono', style: { fontSize: '12px' } }, opts.fontSize + ' pt'),
          chip('Larger', () => false, () => { opts.fontSize = Math.min(14, opts.fontSize + 0.5); })) : null,
        h('div', { class: 'wc-row' }, h('span', { class: 'label' }, 'Then'),
          chip('Add to the library', () => opts.toLibrary, () => { opts.toLibrary = !opts.toLibrary; localSet('ml.wc.lib', opts.toLibrary ? '1' : '0'); }),
          opts.toLibrary ? [chip('as Book', () => opts.category === 'BOOK', () => { opts.category = 'BOOK'; }), chip('as Article', () => opts.category === 'ARTICLE', () => { opts.category = 'ARTICLE'; })] : null,
          h('span', { class: 'spacer' }),
          chip('Download', () => opts.download, () => { opts.download = !opts.download; localSet('ml.wc.dl', opts.download ? '1' : '0'); })));
      go.disabled = !files.length || !(opts.epub || opts.pdf) || !(opts.toLibrary || opts.download);
    };
    const drawList = () => {
      clear(list);
      if (!files.length) {
        list.append(h('div', { class: 'wc-drop', onclick: () => pick.click() }, h('b', null, 'Choose the saved pages'), h('span', null, 'or drop them here — one HTML file per chapter, saved with SingleFile or “Save page as… → Web page, complete”')));
        return;
      }
      const order = infos.map((x, i) => [x, i]);
      list.append(h('div', { class: 'wc-files' }, order.map(([x]) => {
        const pages = x.doc ? (() => { const pm = wcPageEls(wcFindContent(x.doc, x.site), x.site).length; return pm ? `${pm} printed pages marked` : 'no page numbers found'; })() : '';
        return h('div', { class: 'wc-file' }, h('b', null, x.chapter), h('span', { class: 'muted' }, [x.book && x.book !== x.chapter ? x.book : null, x.site ? x.site.name : 'other site (general rules)', pages].filter(Boolean).join(' · ')));
      })), h('div', { class: 'row', style: { gap: '6px', marginTop: '6px' } }, h('button', { class: 'chip', onclick: () => pick.click() }, '+ More pages'), h('button', { class: 'chip', onclick: () => { files = []; infos = []; drawList(); draw(); } }, 'Clear')));
      const i0 = infos[0];
      title.placeholder = (opts.separate || !i0 ? '' : (i0.book || i0.chapter)) || 'From the pages';
      authors.placeholder = (i0 && i0.authors.join(', ')) || 'From the pages';
    };
    const addFiles = async fs => {
      fs = fs.filter(f => /\.(x?html?)$/i.test(f.name));
      if (!fs.length) { toast('Choose HTML files: the saved pages.'); return; }
      status.textContent = 'Reading…';
      for (const f of fs) { files.push(f); try { infos.push(wcParse(await f.text(), f.name)); } catch (e) { infos.push({ chapter: f.name, authors: [], site: null }); } }
      status.textContent = ''; drawList(); draw();
    };
    pick.addEventListener('change', () => { addFiles([...pick.files]); pick.value = ''; });
    list.addEventListener('dragover', e => { e.preventDefault(); e.stopPropagation(); list.classList.add('over'); });
    list.addEventListener('dragleave', () => list.classList.remove('over'));
    list.addEventListener('drop', e => { e.preventDefault(); e.stopPropagation(); list.classList.remove('over'); addFiles([...e.dataTransfer.files]); });
    go.addEventListener('click', async () => {
      go.disabled = true;
      const o = Object.assign({}, opts, { title: title.value.trim(), authors: authors.value.split(/\s*;\s*|\s*,\s*(?=\S+\s+\S)/).map(s => s.trim()).filter(Boolean) });
      try {
        const results = await wcConvert(files, o, msg => { status.textContent = msg; });
        for (const r of results) {
          const made = [];
          if (r.epub) made.push(new File([r.epub], r.name + '.epub', { type: 'application/epub+zip' }));
          if (r.pdf) made.push(new File([r.pdf], r.name + '.pdf', { type: 'application/pdf' }));
          if (o.download) made.forEach(f => { const a = h('a', { href: URL.createObjectURL(f), download: f.name }); document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 60000); });
          if (o.toLibrary) { status.textContent = 'Adding to the library…'; await wcToLibrary(made, o.category); }
        }
        const r0 = results[0];
        status.textContent = '';
        toast(results.length > 1 ? `Made ${results.length} files` : `Made “${r0.meta.title}”: ${r0.chapters} ${r0.chapters === 1 ? 'chapter' : 'chapters'}, ${r0.pages} printed pages marked`, 4000);
        close();
      } catch (e) { console.error(e); status.textContent = 'Could not convert: ' + (e.message || e); go.disabled = false; }
    });
    box.append(h('h2', null, 'Convert web pages to EPUB or PDF'),
      h('p', { class: 'muted', style: { marginTop: '-6px', fontSize: '13px' } }, 'Chapters you have saved from a publisher\'s reading page become a book for the tablet, keeping the printed page numbers, notes, figures and tables. Sage Knowledge, Oxford Academic, Cambridge Core and Idunn are recognised; other sites are read with general rules.'),
      pick, list, h('div', { class: 'fields wc-fields' }, h('label', null, h('span', { class: 'label' }, 'Title'), title), h('label', null, h('span', { class: 'label' }, 'Author(s)'), authors)),
      optsBox, status,
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), go));
    drawList(); draw();
    wcDropTarget = addFiles;
  }, () => { wcDropTarget = null; });
}

/** The first file becomes the item; a second (the PDF beside the EPUB) is added to it as another edition. */
async function wcToLibrary(made, category) {
  const send = async f => {
    const r = await fetch(`/api/upload?name=${encodeURIComponent(f.name)}&category=${category}`, { method: 'POST', credentials: 'same-origin', headers: { 'X-Marginalia': '1', 'Content-Type': 'application/octet-stream' }, body: f });
    if (r.status === 401) { showLogin(); throw new Error('Please sign in.'); }
    let data = {}; try { data = await r.json(); } catch (_) {}
    if (!r.ok) throw new Error(data.error || 'The tablet said ' + r.status);
    return data;
  };
  const first = await send(made[0]);
  for (const f of made.slice(1)) {
    const d = await send(f);
    try { await api(`doc/${d.id}/attach-to`, { method: 'POST', json: { target: first.id } }); } catch (e) { console.warn(e); }
  }
  if (first.lookalike) offerAttach(first, null);
  refreshAfterUpload();
}
