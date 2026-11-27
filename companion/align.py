"""
Lines the book's sentences up with an audiobook transcript.

A faithful port of abAlign() in the web view (app/src/main/assets/web/audiobook.js), so a map made here is the
same as one made in the browser. Offsets and lengths are counted in UTF-16 code units, as in JavaScript, because
the tablet's readers find each sentence by them.
"""
import math
import re
import time
import unicodedata

# JavaScript's \s (ECMAScript WhiteSpace and LineTerminator)
_JS_WS = set('\t\n\v\f\r       　﻿') | {chr(c) for c in range(0x2000, 0x200B)}

AB_ABBR = {'mr', 'mrs', 'ms', 'dr', 'st', 'prof', 'eg', 'ie', 'etc', 'vs', 'cf', 'nr', 'no', 'ca', 'bl', 'mfl', 'osv',
           'dvs', 'fx', 'jf', 'kap', 'jr', 'sr', 'vol', 'ed', 'pp', 'p', 'ch', 'fig'}

_END = re.compile('[.!?…]["\'”’»)\\]]*$')
_OPEN = set('"\'“‘«([')


def js_round(x):
    """Math.round: halves go up."""
    return math.floor(x + 0.5)


def u16len(s):
    return len(s.encode('utf-16-le')) // 2


def u16slice(s, a, b=None):
    enc = s.encode('utf-16-le')
    return enc[2 * a: None if b is None else 2 * b].decode('utf-16-le', errors='surrogatepass')


def _is_letter_or_number(ch):
    return unicodedata.category(ch)[0] in ('L', 'N')


def ab_norm(w):
    s = unicodedata.normalize('NFKD', str(w).lower())
    s = ''.join(ch for ch in s if not ('̀' <= ch <= 'ͯ'))
    return ''.join(ch for ch in s if _is_letter_or_number(ch))


def _js_words(text):
    """Matches of /\\S+/g with their start and end in UTF-16 units."""
    out, i, pos16 = [], 0, 0
    start = None
    start16 = 0
    for ch in text:
        if ch in _JS_WS:
            if start is not None:
                out.append((text[start:i], start16, pos16))
                start = None
        else:
            if start is None:
                start, start16 = i, pos16
        i += 1
        pos16 += 2 if ord(ch) > 0xFFFF else 1
    if start is not None:
        out.append((text[start:], start16, pos16))
    return out


def _starts_sentence(raw):
    k = 0
    if raw and raw[0] in _OPEN:
        k = 1
    if k >= len(raw):
        return False
    cat = unicodedata.category(raw[k])
    return cat == 'Lu' or cat[0] == 'N'


def book_tokens(text):
    toks, sents = [], []
    for ui, u in enumerate(text['units']):
        start = len(toks)
        if text['kind'] == 'pdf':
            for i, w in enumerate(u['words']):
                toks.append({'raw': w, 'n': ab_norm(w), 'u': ui, 'i': i})
        else:
            for raw, s, e in _js_words(u.get('text', '')):
                toks.append({'raw': raw, 'n': ab_norm(raw), 'u': ui, 's': s, 'e': e})
        s0 = start
        for k in range(start, len(toks)):
            raw = toks[k]['raw']
            nxt = toks[k + 1] if k + 1 < len(toks) else None
            end = k == len(toks) - 1 or (
                _END.search(raw) is not None and ab_norm(raw) not in AB_ABBR and u16len(raw) > 1 and
                (nxt is None or nxt['u'] != ui or _starts_sentence(nxt['raw'])))
            if end:
                sents.append((s0, k))
                s0 = k + 1
        if s0 < len(toks):
            sents.append((s0, len(toks) - 1))
    return toks, sents


def transcript_tokens(segments):
    out = []
    for sg in segments:
        words = [w for w in re.split('[' + ''.join(sorted(_JS_WS)) + ']+', sg['t']) if w]
        total = sum(u16len(w) + 1 for w in words) or 1
        at = 0
        for w in words:
            n = ab_norm(w)
            if n:
                out.append({'n': n, 't': js_round(sg['s'] + (sg['e'] - sg['s']) * at / total)})
            at += u16len(w) + 1
    return out


def _lis(pairs):
    tails, prev_idx, tail_idx = [], [0] * len(pairs), []
    for i, p in enumerate(pairs):
        lo, hi = 0, len(tails)
        while lo < hi:
            mid = (lo + hi) >> 1
            if tails[mid] < p[0]:
                lo = mid + 1
            else:
                hi = mid
        if lo == len(tails):
            tails.append(p[0]); tail_idx.append(i)
        else:
            tails[lo] = p[0]; tail_idx[lo] = i
        prev_idx[i] = tail_idx[lo - 1] if lo > 0 else -1
    out = []
    k = tail_idx[len(tails) - 1] if tails else None
    while k is not None and k >= 0:
        out.append(pairs[k])
        k = prev_idx[k]
    out.reverse()
    return out


def align(text, segments):
    toks, sents = book_tokens(text)
    b_idx = [i for i, t in enumerate(toks) if t['n']]
    B = [toks[i]['n'] for i in b_idx]
    T = transcript_tokens(segments)
    Tn = [x['n'] for x in T]
    K = 3

    def grams(arr):
        m = {}
        for i in range(0, len(arr) - K + 1):
            g = ' '.join(arr[i:i + K])
            v = m.get(g)
            if v is None:
                m[g] = i
            elif v != -1:
                m[g] = -1
        return m

    bg, tg = grams(B), grams(Tn)
    pairs = []  # (b, t)
    for g, ti in tg.items():
        if ti < 0:
            continue
        bi = bg.get(g)
        if bi is not None and bi >= 0:
            pairs.append((bi, ti))
    pairs.sort(key=lambda p: p[1])
    anchors = _lis(pairs)

    match = [-1] * len(B)
    for pb, pt in anchors:
        for k in range(K):
            if match[pb + k] < 0:
                match[pb + k] = pt + k
    for pb, pt in anchors:
        b, t = pb + K, pt + K
        while b < len(B) and t < len(Tn) and match[b] < 0 and B[b] == Tn[t]:
            match[b] = t; b += 1; t += 1
        b, t = pb - 1, pt - 1
        while b >= 0 and t >= 0 and match[b] < 0 and B[b] == Tn[t]:
            match[b] = t; b -= 1; t -= 1

    def fill(b0, b1, t0, t1):
        nb, nt = b1 - b0, t1 - t0
        if nb <= 0 or nt <= 0 or nb * nt > 250000:
            return
        L = [[0] * (nt + 1) for _ in range(nb + 1)]
        for i in range(nb - 1, -1, -1):
            Li, Li1 = L[i], L[i + 1]
            bw = B[b0 + i]
            for j in range(nt - 1, -1, -1):
                Li[j] = Li1[j + 1] + 1 if bw == Tn[t0 + j] else max(Li1[j], Li[j + 1])
        i = j = 0
        while i < nb and j < nt:
            if B[b0 + i] == Tn[t0 + j]:
                match[b0 + i] = t0 + j; i += 1; j += 1
            elif L[i + 1][j] >= L[i][j + 1]:
                i += 1
            else:
                j += 1

    last_b, last_t = -1, -1
    for b in range(len(B) + 1):
        if b == len(B) or match[b] >= 0:
            t = len(Tn) if b == len(B) else match[b]
            if t > last_t:
                fill(last_b + 1, b, last_t + 1, t)
            if b < len(B):
                last_b, last_t = b, match[b]

    tm = [math.nan] * len(B)
    prev = -1
    for b in range(len(B) + 1):
        if b < len(B) and match[b] < 0:
            continue
        if b < len(B):
            tm[b] = T[match[b]]['t']
        if prev >= 0 and b < len(B) and b - prev > 1:
            t0, t1, n = tm[prev], tm[b], b - prev
            if t1 > t0 and (t1 - t0) <= max(15000, n * 1500) and (t1 - t0) >= n * 120:
                for k in range(1, n):
                    tm[prev + k] = t0 + (t1 - t0) * k / n
        prev = b

    pos = [-1] * len(toks)
    for bi, ti in enumerate(b_idx):
        pos[ti] = bi
    out, last_t2, timed, counted = [], -1, 0, 0
    for s0, s1 in sents:
        first, before = -1, 0
        for k in range(s0, s1 + 1):
            bi = pos[k]
            if bi < 0:
                continue
            if not math.isnan(tm[bi]):
                first = bi
                break
            before += 1
        words = s1 - s0 + 1
        if words >= 2:
            counted += 1
        if first < 0:
            continue
        t = js_round(tm[first] - before * 350)
        if t <= last_t2:
            if tm[first] <= last_t2:
                continue
            t = last_t2 + 1
        last_t2 = t
        if words >= 2:
            timed += 1
        u = text['units'][toks[s0]['u']]
        if text['kind'] == 'pdf':
            l = {'p': u['page'], 'w': [toks[s0]['i'], toks[s1]['i'] if toks[s1]['u'] == toks[s0]['u'] else len(u['words']) - 1]}
        else:
            l = {'c': u.get('c'), 'p': u.get('p'), 'h': u.get('h') or '', 's': toks[s0]['s'], 'e': toks[s1]['e']}
        if text['kind'] == 'pdf' or toks[s1]['u'] != toks[s0]['u']:
            x = ' '.join(z['raw'] for z in toks[s0:s1 + 1])
        else:
            x = u16slice(u['text'], toks[s0]['s'], toks[s1]['e'])
        x = u16slice(x, 0, 400)
        out.append({'t': int(t), 'l': l, 'x': x})
    return {'version': 1, 'kind': text['kind'], 'created': int(time.time() * 1000),
            'coverage': timed / counted if counted else 0, 'anchors': len(anchors), 'sentences': out}
