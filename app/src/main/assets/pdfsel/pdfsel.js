/*
 * Finds where a highlight's quote sits in pdf.js's text items, in the form the PDF++ plugin for
 * Obsidian uses in links: selection=beginIndex,beginOffset,endIndex,endOffset (end exclusive).
 * Indices count the page's text items as pdf.js returns them.
 */
(function () {
  'use strict';

  // Letters and digits only, lower-cased: hyphenation, spacing, quotes and line breaks differ
  // between the app's text extraction and pdf.js, the words do not.
  function norm(ch) {
    var c = ch.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase();
    return c.replace(/[^\p{L}\p{N}]/gu, '');
  }

  function stream(items) {
    var text = '', map = [];
    for (var i = 0; i < items.length; i++) {
      var s = items[i].str || '';
      for (var o = 0; o < s.length; o++) {
        var n = norm(s[o]);
        for (var k = 0; k < n.length; k++) { text += n[k]; map.push([i, o]); }
      }
    }
    return { text: text, map: map };
  }

  function normQuote(q) {
    var out = '';
    for (var i = 0; i < q.length; i++) out += norm(q[i]);
    return out;
  }

  /** Returns "b,bo,e,eo" or "" when the quote is not on the page. */
  function findSelection(items, quote) {
    var s = stream(items), q = normQuote(quote);
    if (!q || !s.text) return '';
    var start = s.text.indexOf(q), end;
    if (start >= 0) end = start + q.length - 1;
    else {
      // Allow small differences inside the quote: match its beginning and its end separately.
      var n = Math.min(40, Math.floor(q.length / 2));
      if (n < 8) return '';
      start = s.text.indexOf(q.slice(0, n));
      if (start < 0) return '';
      var tail = q.slice(-n);
      var t = s.text.indexOf(tail, start + n - 1);
      if (t >= 0 && t - start < q.length * 1.5 + 40) end = t + n - 1;
      else end = Math.min(s.text.length - 1, start + q.length - 1); // runs onto the next page
    }
    var a = s.map[start], b = s.map[end];
    return a[0] + ',' + a[1] + ',' + b[0] + ',' + (b[1] + 1);
  }

  var api = { findSelection: findSelection };
  if (typeof module !== 'undefined') module.exports = api;
  if (typeof window === 'undefined') return;
  window.PdfSel = api;

  // ------------------------------------------------------------ in the app (hidden WebView)
  function b64ToBytes(b64) {
    var bin = atob(b64), out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  var loaded = {}; // path -> Promise<PDFDocumentProxy>

  function open(path, length) {
    if (loaded[path]) return loaded[path];
    var transport = new pdfjsLib.PDFDataRangeTransport(length, null);
    transport.requestDataRange = function (begin, end) {
      // pdf.js expects the answer later, not inside this call.
      setTimeout(function () { transport.onDataRange(begin, b64ToBytes(Bridge.read(path, begin, end - begin))); }, 0);
    };
    loaded[path] = pdfjsLib.getDocument({
      range: transport, length: length, disableAutoFetch: true, disableStream: true,
      isEvalSupported: false, useSystemFonts: true
    }).promise;
    return loaded[path];
  }

  /** jobs: [{id, path, length, page, quote}] — answers each with Bridge.done(id, selection). */
  window.locateAll = function (jobsJson) {
    var jobs = JSON.parse(jobsJson);
    var chain = Promise.resolve();
    jobs.forEach(function (j) {
      chain = chain.then(function () {
        return open(j.path, j.length)
          .then(function (doc) { return doc.getPage(j.page); })
          .then(function (p) { return p.getTextContent(); })
          .then(function (tc) { Bridge.done(String(j.id), findSelection(tc.items, j.quote)); })
          .catch(function (e) { Bridge.done(String(j.id), ''); Bridge.log(String(e && e.message || e)); });
      });
    });
    chain.then(function () {
      Object.keys(loaded).forEach(function (k) { loaded[k].then(function (d) { d.destroy(); }, function () {}); });
      loaded = {};
      Bridge.finished();
    });
  };
})();
