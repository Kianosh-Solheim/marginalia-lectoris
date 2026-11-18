/* Marginalia Lectoris — style corrections in the writing program.
 *
 * Strict, traditional British prose: Oxford spelling (-ize, but analyse, colour, centre, travelled),
 * British conventions (per cent, towards, single quotation marks, 18 November 2026), a formal register
 * (no contractions or colloquialisms), the writer's own rules, and an AI check on request.
 *
 * Findings are underlined (an overlay over the page; the text itself is not touched). Click one to
 * accept, ignore it here, or ignore that word everywhere. Quotations — between quotation marks or in
 * a block quotation — are left alone: other people's words keep their spelling.
 */

// ---------------------------------------------------------------- the rules

const STYLE_SETS = [
  ['spelling', 'Oxford spelling', 'organize, analyse, colour, centre, travelled, defence', '#c0392b'],
  ['conventions', 'British conventions', 'per cent, towards, single quotation marks, 18 November 2026, Mr', '#2563eb'],
  ['register', 'Formal register', 'no contractions; no “a lot of”, “figure out”, “pretty good”', '#b45309'],
  ['own', 'Your own rules', 'pairs you add below', '#7c3aed'],
  ['ai', 'AI check', 'only when you press the button', '#15803d'],
];
const styleColour = set => (STYLE_SETS.find(s => s[0] === set) || [])[3] || '#888';

/** Word → [suggestion, why] for one-word spelling changes. Built once. */
let STYLE_WORDS = null;
function styleWords() {
  if (STYLE_WORDS) return STYLE_WORDS;
  const M = new Map();
  const put = (us, uk, why) => { if (!M.has(us)) M.set(us, [uk, why || '']); };
  // -or → -our
  const our = ['col', 'fav', 'hon', 'hum', 'lab', 'neighb', 'behavi', 'flav', 'harb', 'rum', 'vig', 'endeav', 'od', 'savi', 'arm', 'val', 'splend', 'cand', 'clam', 'rig', 'demean', 'tum', 'parl', 'ard'];
  const ourEnd = ['', 's', 'ed', 'ing', 'ful', 'fully', 'ite', 'ites', 'able', 'ably', 'less', 'er', 'ers', 'ist', 'ists', 'hood', 'hoods', 'y'];
  our.forEach(b => ourEnd.forEach(e => put(b + 'or' + e, b + 'our' + e, 'British -our')));
  ['al', 'ally', 'ism', 'isms', 'ist', 'ists'].forEach(e => put('behavior' + e, 'behaviour' + e, 'British -our'));
  // -er → -re
  [['center', 'centre'], ['epicenter', 'epicentre'], ['theater', 'theatre'], ['fiber', 'fibre'], ['liter', 'litre'], ['milliliter', 'millilitre'], ['caliber', 'calibre'],
   ['saber', 'sabre'], ['somber', 'sombre'], ['specter', 'spectre'], ['luster', 'lustre'], ['meager', 'meagre'], ['sepulcher', 'sepulchre'],
   ['kilometer', 'kilometre'], ['centimeter', 'centimetre'], ['millimeter', 'millimetre']].forEach(([a, b]) => { put(a, b, 'British -re'); put(a + 's', b + 's', 'British -re'); });
  put('centered', 'centred', 'British -re'); put('centering', 'centring', 'British -re'); put('centerpiece', 'centrepiece', 'British -re');
  put('meter', 'metre', 'British -re, for the unit (a measuring device stays “meter”)'); put('meters', 'metres', 'British -re, for the unit (a measuring device stays “meter”)');
  [['maneuver', 'manoeuvre'], ['maneuvers', 'manoeuvres'], ['maneuvered', 'manoeuvred'], ['maneuvering', 'manoeuvring'], ['maneuverable', 'manoeuvrable']].forEach(([a, b]) => put(a, b, 'British spelling'));
  // -yze → -yse (Oxford spelling keeps -yse)
  ['analy', 'paraly', 'cataly', 'breathaly', 'dialy', 'electroly', 'hydroly'].forEach(b => ['ze', 'zes', 'zed', 'zing', 'zer', 'zers'].forEach(e => put(b + e, b + e.replace('z', 's'), 'Oxford spelling: -yse')));
  // -l → -ll before a suffix
  ['travel', 'cancel', 'label', 'model', 'signal', 'fuel', 'level', 'marvel', 'counsel', 'channel', 'total', 'equal', 'duel', 'quarrel', 'rival', 'tunnel', 'pencil', 'shovel', 'jewel', 'dial', 'initial', 'funnel', 'parcel', 'pedal', 'revel', 'grovel', 'swivel', 'unravel', 'dishevel', 'libel', 'enamel']
    .forEach(b => { put(b + 'ed', b + 'led', 'British: double l'); put(b + 'ing', b + 'ling', 'British: double l'); });
  ['travel', 'jewel', 'level', 'revel', 'model', 'label', 'counsel'].forEach(b => { put(b + 'er', b + 'ler', 'British: double l'); put(b + 'ers', b + 'lers', 'British: double l'); });
  // single words
  [['defense', 'defence'], ['defenses', 'defences'], ['offense', 'offence'], ['offenses', 'offences'], ['pretense', 'pretence'], ['pretenses', 'pretences'],
   ['gray', 'grey'], ['grays', 'greys'], ['grayish', 'greyish'], ['grayed', 'greyed'],
   ['catalog', 'catalogue'], ['catalogs', 'catalogues'], ['cataloged', 'catalogued'], ['cataloging', 'cataloguing'],
   ['aluminum', 'aluminium'], ['mustache', 'moustache'], ['plow', 'plough'], ['plows', 'ploughs'], ['plowed', 'ploughed'], ['plowing', 'ploughing'],
   ['skeptic', 'sceptic'], ['skeptics', 'sceptics'], ['skeptical', 'sceptical'], ['skeptically', 'sceptically'], ['skepticism', 'scepticism'],
   ['enroll', 'enrol'], ['enrolls', 'enrols'], ['enrollment', 'enrolment'], ['enrollments', 'enrolments'], ['fulfill', 'fulfil'], ['fulfills', 'fulfils'], ['fulfillment', 'fulfilment'],
   ['installment', 'instalment'], ['installments', 'instalments'], ['instill', 'instil'], ['instills', 'instils'], ['distill', 'distil'], ['distills', 'distils'],
   ['appall', 'appal'], ['appalls', 'appals'], ['enthrall', 'enthral'], ['enthralls', 'enthrals'], ['willful', 'wilful'], ['willfully', 'wilfully'], ['skillful', 'skilful'], ['skillfully', 'skilfully'],
   ['acknowledgment', 'acknowledgement'], ['acknowledgments', 'acknowledgements'], ['aging', 'ageing'], ['artifact', 'artefact'], ['artifacts', 'artefacts'],
   ['cozy', 'cosy'], ['mold', 'mould'], ['molds', 'moulds'], ['molded', 'moulded'], ['molding', 'moulding'], ['moldy', 'mouldy'], ['smolder', 'smoulder'], ['smoldering', 'smouldering'],
   ['pediatric', 'paediatric'], ['archeology', 'archaeology'], ['archeological', 'archaeological'], ['archeologist', 'archaeologist'], ['esthetic', 'aesthetic'], ['esthetics', 'aesthetics'],
   ['fetus', 'foetus'], ['anemia', 'anaemia'], ['anesthesia', 'anaesthesia'], ['estrogen', 'oestrogen'], ['hemorrhage', 'haemorrhage'], ['diarrhea', 'diarrhoea'],
   ['airplane', 'aeroplane'], ['airplanes', 'aeroplanes'], ['jewelry', 'jewellery'], ['marvelous', 'marvellous'], ['marvelously', 'marvellously'], ['counselor', 'counsellor'], ['counselors', 'counsellors'],
   ['woolen', 'woollen'], ['pajamas', 'pyjamas'], ['behoove', 'behove'], ['behooves', 'behoves'], ['ax', 'axe'], ['donut', 'doughnut'], ['tidbit', 'titbit'], ['omelet', 'omelette'],
   ['likable', 'likeable'], ['livable', 'liveable'], ['sizable', 'sizeable'], ['mollusk', 'mollusc'], ['cancelation', 'cancellation'], ['carburetor', 'carburettor'],
   ['analog', 'analogue', 'British spelling (in electronics “analog” is also used)'], ['dialog', 'dialogue', 'British spelling (a computer’s “dialog box” keeps it)'],
   ['program', 'programme', 'British spelling, except for a computer program'], ['programs', 'programmes', 'British spelling, except for computer programs'],
   ['license', 'licence', 'British: “licence” is the noun; the verb is “license”'], ['practice', 'practice', ''],
  ].forEach(([a, b, why]) => { if (a !== b) put(a, b, why || 'British spelling'); });
  STYLE_WORDS = M;
  return M;
}

/** Oxford spelling uses -ize; these words are -ise in every spelling and are left alone. */
const ISE_KEEP = /(wise|vise|prise|guise|aise|oise|uise)$/;
const ISE_KEEP_WORDS = new Set(['advertise', 'chastise', 'franchise', 'enfranchise', 'disenfranchise', 'merchandise', 'paradise', 'despise', 'demise', 'promise', 'compromise', 'surmise', 'comprise', 'surprise', 'enterprise', 'apprise', 'circumcise', 'excise', 'incise', 'televise', 'supervise', 'improvise', 'advise', 'devise', 'revise', 'noise', 'poise', 'raise', 'praise', 'appraise', 'braise', 'cruise', 'bruise', 'guise', 'disguise', 'arise', 'anise', 'expertise', 'treatise', 'exercise', 'concise', 'precise', 'premise', 'otherwise', 'likewise', 'clockwise', 'wise', 'rise', 'sunrise', 'valise', 'chemise', 'mortise', 'reprise', 'practise', 'tortoise', 'porpoise', 'turquoise', 'remise', 'imprecise', 'exorcise', 'incise', 'ise']);
function iseToIze(lower) {
  const m = lower.match(/^(.{3,})is(e|es|ed|ing|er|ers|ation|ations|ational|able|ability)$/); if (!m) return null;
  const base = m[1] + 'ise';
  if (ISE_KEEP_WORDS.has(base) || ISE_KEEP.test(base) || base.length < 7) return null;
  return m[1] + 'iz' + m[2];
}

const MONTHS = 'January|February|March|April|May|June|July|August|September|October|November|December';
const DAYS = 'Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday';
/** Phrase rules: [set, regex, replacement (string or fn(m) → string|null), why]. */
function phraseRules(opts) {
  const R = [
    ['conventions', /(\d)\s?%/g, m => m[1] + ' per cent', 'British: “per cent” in running text'],
    ['conventions', /\bpercent\b/gi, 'per cent', 'British: two words'],
    ['conventions', /\btoward\b/gi, 'towards', 'British: towards'],
    ['conventions', /\bafterward\b/gi, 'afterwards', 'British: afterwards'],
    ['conventions', /\b(?<!ill-)gotten\b/gi, 'got', 'British: got (“ill-gotten” stays)'],
    ['conventions', new RegExp(`\\b(${MONTHS})\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b`, 'g'), m => `${m[2]} ${m[1]} ${m[3]}`, 'British dates: day, month, year'],
    ['conventions', new RegExp(`\\b(${MONTHS})\\s+(\\d{1,2})(?:st|nd|rd|th)\\b`, 'g'), m => `${m[2]} ${m[1]}`, 'British dates: day before month'],
    ['conventions', /\b(Mr|Mrs|Ms|Dr|St)\.(?=\s+\p{Lu})/gu, m => m[1], 'Oxford: no full stop after a contraction (Mr, Dr, St)'],
    ['conventions', /\bdifferent than\b/gi, 'different from', 'British: different from'],
    ['conventions', /\bon the weekend\b/gi, 'at the weekend', 'British: at the weekend'],
    ['conventions', new RegExp(`\\b(${DAYS}) through (${DAYS})\\b`, 'g'), m => `${m[1]} to ${m[2]}`, 'British: Monday to Friday'],
    ['conventions', /\banymore\b/gi, 'any more', 'British: two words'],
    ['conventions', /\balright\b/gi, 'all right', 'Traditional: all right'],
    ['conventions', /\bmath\b/g, 'maths', 'British: maths'],
    ['conventions', /\b(in the|this|last|next|that|during the|by the|early|late) fall\b/gi, m => m[1] + ' autumn', 'British: autumn'],
    ['conventions', /\bfall of (\d{4})\b/gi, m => 'autumn of ' + m[1], 'British: autumn'],
    ['register', /\b(figure|figures|figured|figuring) out\b/gi, m => ({ figure: 'work out', figures: 'works out', figured: 'worked out', figuring: 'working out' })[m[1].toLowerCase()], 'Informal; or “determine”, “establish”'],
    ['register', /\ba lot of\b/gi, 'a great deal of', 'Informal; or “many”, “much”, “considerable”'],
    ['register', /\blots of\b/gi, 'a great many', 'Informal; or “much”, “many”'],
    ['register', /\ba lot\b/gi, 'a great deal', 'Informal'],
    ['register', /\b(?<!(?:\b(?:a|an|the|this|that|these|those|what|which|some|any|one|every|each|no|another|same|other|kind|sort)\s))(kind|sort) of\b/gi, 'rather', 'Informal hedge; or “somewhat”'],
    ['register', /\bpretty (much|well|good|bad|sure|clear|obvious|important|similar|different|big|small|large|high|low|strong|weak|easy|hard|difficult|likely|quickly|soon|often|common|rare)\b/gi, m => 'rather ' + m[1], 'Informal; or “fairly”, “quite”'],
    ['register', /\breal (good|bad|quick|quickly|easy|hard|important|big|nice|sure|well|serious|strong)\b/gi, m => 'very ' + m[1], 'Colloquial American; “very” or “really”'],
    ['register', /\bokay\b/gi, 'acceptable', 'Informal; or “satisfactory”'],
    ['register', /\b(gonna|wanna|gotta)\b/gi, m => ({ gonna: 'going to', wanna: 'want to', gotta: 'have to' })[m[1].toLowerCase()], 'Colloquial'],
    ['register', /\banyways\b/gi, 'anyway', 'Colloquial'],
    ['register', /\b(come|comes|came|coming) up with\b/gi, m => ({ come: 'devise', comes: 'devises', came: 'devised', coming: 'devising' })[m[1].toLowerCase()], 'Informal; or “propose”, “produce”'],
    ['register', /\b(check|checks|checked|checking) out\b/gi, m => ({ check: 'examine', checks: 'examines', checked: 'examined', checking: 'examining' })[m[1].toLowerCase()], 'Informal'],
    ['register', /\b(get|gets|got|getting) rid of\b/gi, m => ({ get: 'dispose of', gets: 'disposes of', got: 'disposed of', getting: 'disposing of' })[m[1].toLowerCase()], 'Informal; or “remove”'],
    ['register', /\blike (I|you|we|they|he|she|it) (said|say|says|mentioned|noted|argued|did|do|does|was|were|have|has|had)\b/g, m => `as ${m[1]} ${m[2]}`, '“Like” is not a conjunction in formal prose'],
    ['register', /(^|[.!?]\s+)(So|Plus),\s/g, m => m[1] + (m[2] === 'So' ? 'Thus, ' : 'Moreover, '), 'Informal opening', 2],
    ['register', /\bsuper (\w+)/gi, m => 'extremely ' + m[1], 'Informal'],
    ['register', /\b(awesome|stuff)\b/gi, m => m[1].toLowerCase() === 'stuff' ? 'material' : 'excellent', 'Informal'],
  ];
  if (opts.whilst) R.push(['conventions', /\bwhile\b/gi, 'whilst', 'Your choice: whilst'], ['conventions', /\bamong\b/gi, 'amongst', 'Your choice: amongst']);
  return R;
}

const CONTRACTIONS = {
  "don't": 'do not', "doesn't": 'does not', "didn't": 'did not', "can't": 'cannot', "won't": 'will not', "isn't": 'is not', "aren't": 'are not', "wasn't": 'was not',
  "weren't": 'were not', "hasn't": 'has not', "haven't": 'have not', "hadn't": 'had not', "couldn't": 'could not', "shouldn't": 'should not', "wouldn't": 'would not',
  "mustn't": 'must not', "needn't": 'need not', "shan't": 'shall not', "mightn't": 'might not', "it's": 'it is', "that's": 'that is', "there's": 'there is', "here's": 'here is',
  "what's": 'what is', "who's": 'who is', "let's": 'let us', "i'm": 'I am', "you're": 'you are', "we're": 'we are', "they're": 'they are', "he's": 'he is', "she's": 'she is',
  "i've": 'I have', "you've": 'you have', "we've": 'we have', "they've": 'they have', "i'll": 'I shall', "we'll": 'we shall', "you'll": 'you will', "he'll": 'he will',
  "she'll": 'she will', "it'll": 'it will', "they'll": 'they will', "i'd": 'I would', "you'd": 'you would', "we'd": 'we would', "they'd": 'they would', "he'd": 'he would', "she'd": 'she would',
};
const CONTRACTION_WHY = { "it's": 'No contractions in formal prose (or “it has”)', "he's": 'No contractions (or “he has”)', "she's": 'No contractions (or “she has”)', "i'll": 'No contractions; traditional “shall” for I and we', "we'll": 'No contractions; traditional “shall” for I and we' };

/** Keeps the capitals of the original: Color → Colour, COLOR → COLOUR. */
function keepCase(orig, sug) {
  if (!sug) return sug;
  if (orig.length > 1 && orig === orig.toUpperCase() && /\p{L}/u.test(orig)) return sug.toUpperCase();
  if (/^\p{Lu}/u.test(orig) && /^\p{Ll}/u.test(sug)) return sug[0].toUpperCase() + sug.slice(1);
  return sug;
}

/** Where in a block's text quotations are (so their words are left alone). */
function quotedSpans(t) {
  const out = [];
  const re = /“[^”]*”|"[^"\n]*"|‘[^’]*’(?!\p{L})|«[^»]*»|„[^“”]*[“”]/gu;
  let m; while ((m = re.exec(t))) out.push([m.index, m.index + m[0].length]);
  return out;
}

/**
 * All findings in the document. Each: { id, set, from, to, text, sug, why, edits: [[from, to, text]] }.
 * [o]: { sets: {spelling, conventions, register, own}, whilst, rules: [{from, to}], ignore: [word], ignored: Set(key) }
 */
function styleFindings(doc, o) {
  const out = [], words = styleWords(), phrases = phraseRules(o);
  const ignoreWords = new Set((o.ignore || []).map(w => w.toLowerCase()));
  const own = (o.rules || []).filter(r => r && r.from && r.from.trim()).map(r => {
    const f = r.from.trim(), esc = f.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return { re: new RegExp((/^\p{L}/u.test(f) ? '(?<![\\p{L}\\p{N}])' : '') + esc + (/\p{L}$/u.test(f) ? '(?![\\p{L}\\p{N}])' : ''), 'giu'), to: r.to || '', why: r.why || 'Your own rule' };
  });
  doc.descendants((node, pos, parent) => {
    if (node.type.name === 'codeBlock' || node.type.name === 'blockquote' || node.type.name === 'footnote') return false;
    if (!node.isTextblock) return true;
    // The block's text, with a position for each character (other inline things count as one mark).
    let text = '', at = [];
    node.forEach((child, off) => {
      if (child.isText) {
        if (child.marks.some(mk => mk.type.name === 'code' || mk.type.name === 'link')) { for (let i = 0; i < child.text.length; i++) { text += '￼'; at.push(pos + 1 + off + i); } return; }
        for (let i = 0; i < child.text.length; i++) { text += child.text[i]; at.push(pos + 1 + off + i); }
      } else { text += '￼'; at.push(pos + 1 + off); }
    });
    if (!text.trim()) return false;
    at.push(pos + 1 + node.content.size);
    const quotes = quotedSpans(text);
    const inQuote = (a, b) => quotes.some(([x, y]) => a >= x && b <= y);
    const add = (set, a, b, sug, why, edits) => {
      const orig = text.slice(a, b);
      if (sug == null || sug === orig) return;
      if (ignoreWords.has(orig.toLowerCase())) return;
      const f = { set, from: at[a], to: at[b], text: orig, sug, why: why || '' };
      f.key = set + '|' + orig + '|' + text.slice(Math.max(0, a - 12), a) + '|' + text.slice(b, b + 12);
      if (o.ignored && o.ignored.has(f.key)) return;
      f.edits = edits || [[f.from, f.to, sug]];
      out.push(f);
    };
    const S = o.sets || {};
    // one word at a time: spelling, contractions
    if (S.spelling || S.register) {
      const wre = /\p{L}+(?:['’]\p{L}+)?/gu; let m;
      while ((m = wre.exec(text))) {
        const w = m[0], a = m.index, b = a + w.length, low = w.toLowerCase().replace('’', "'");
        if (inQuote(a, b)) continue;
        if (S.register && CONTRACTIONS[low]) { const sug = CONTRACTIONS[low]; add('register', a, b, /^I\b/.test(sug) ? sug : keepCase(w, sug), CONTRACTION_WHY[low] || 'No contractions in formal prose'); continue; }
        if (!S.spelling || /['’]/.test(w)) continue;
        // proper names mid-sentence (a capital not after a full stop) are left alone, except all-lower words
        const startOfSentence = /(^|[.!?:]\s+|^\s*[‘“"(]?)$/.test(text.slice(Math.max(0, a - 3), a)) || a === 0;
        if (/^\p{Lu}/u.test(w) && !startOfSentence) continue;
        const hit = words.get(low);
        if (hit) { add('spelling', a, b, keepCase(w, hit[0]), hit[1]); continue; }
        const ize = iseToIze(low);
        if (ize) add('spelling', a, b, keepCase(w, ize), o.spellingIse ? '' : 'Oxford spelling: -ize');
      }
    }
    if (S.conventions || S.register) {
      phrases.forEach(([set, re, rep, why, group]) => {
        if (!S[set]) return;
        re.lastIndex = 0; let m;
        while ((m = re.exec(text))) {
          let a = m.index, b = a + m[0].length;
          if (m[0].length === 0) { re.lastIndex++; continue; }
          if (inQuote(a, b)) continue;
          const sug = typeof rep === 'function' ? rep(m) : keepCase(m[0], rep);
          if (group) { const lead = m[1] || ''; a += lead.length; add(set, a, b, sug.slice(lead.length), why); }
          else add(set, a, b, sug, why);
        }
      });
    }
    // double quotation marks → single (only the two marks change)
    if (S.conventions) {
      const qre = /“([^”]{1,600})”|"([^"\n]{1,600})"/g; let m;
      while ((m = qre.exec(text))) {
        const a = m.index, b = a + m[0].length, inner = m[1] != null ? m[1] : m[2];
        if (inner.includes('￼') && inner.replace(/￼/g, '').trim() === '') continue;
        add('conventions', a, b, '‘' + inner + '’', 'British: single quotation marks (double ones inside them)', [[at[a], at[a + 1], '‘'], [at[b - 1], at[b], '’']]);
      }
    }
    if (S.own) own.forEach(r => {
      r.re.lastIndex = 0; let m;
      while ((m = r.re.exec(text))) { const a = m.index, b = a + m[0].length; if (!inQuote(a, b)) add('own', a, b, keepCase(m[0], r.to), r.why); }
    });
    return false;
  });
  return styleOverlaps(out);
}
/** Where two findings overlap, the longer one wins (“a lot of” over “a lot”). */
function styleOverlaps(out) {
  out.sort((x, y) => x.from - y.from || (y.to - y.from) - (x.to - x.from));
  const kept = [];
  out.forEach(f => { const last = kept[kept.length - 1]; if (last && f.from < last.to) { if (f.to - f.from > last.to - last.from) kept[kept.length - 1] = f; return; } kept.push(f); });
  kept.forEach((f, i) => { f.id = i; });
  return kept;
}

// ---------------------------------------------------------------- in the editor

/**
 * The style checker of one writing document. [settings]: the document's own ({ off, ignored }),
 * [onChange]: called when those change (to be saved with the document).
 */
class StyleChecker {
  constructor(editor, sheet, settings, onChange) {
    this.editor = editor; this.sheet = sheet; this.onChange = onChange || (() => {});
    const s = settings && typeof settings === 'object' ? settings : {};
    this.off = !!s.off;
    this.ignored = new Set(Array.isArray(s.ignored) ? s.ignored : []);
    this.prefs = { sets: { spelling: true, conventions: true, register: true, own: true }, whilst: false, rules: [], ignore: [] };
    this.found = []; this.ai = []; this.timer = 0; this.listeners = [];
    this.layer = h('div', { class: 'wstylelayer' });
    sheet.style.position = sheet.style.position || 'relative';
    sheet.append(this.layer);
    this.pop = null;
    this.onResize = () => this.paintSoon();
    window.addEventListener('resize', this.onResize);
    editor.on('update', () => this.checkSoon());
    // AI findings move with the text when something before them changes.
    editor.on('transaction', ({ transaction }) => {
      if (!transaction.docChanged || !this.ai.length) return;
      this.ai.forEach(f => { f.from = transaction.mapping.map(f.from, 1); f.to = transaction.mapping.map(f.to, -1); f.edits = [[f.from, f.to, f.sug]]; });
    });
    editor.on('selectionUpdate', () => { const f = this.popFor, s = editor.state.selection; if (this.pop && (!f || !s.empty || s.from < f.from || s.from > f.to)) this.closePop(); });
    editor.view.dom.addEventListener('click', e => this.clicked(e));
    this.load();
  }
  async load() {
    try { const all = await api('note-pages'); const p = all && all._style; if (p) this.prefs = Object.assign(this.prefs, p, { sets: Object.assign({}, this.prefs.sets, p.sets || {}) }); } catch (_) {}
    this.check();
  }
  savePrefs() { api('note-pages', { method: 'PUT', json: { key: '_style', page: this.prefs } }).catch(fail); }
  settings() { return { off: this.off, ignored: [...this.ignored].slice(-600) }; }
  subscribe(fn) { this.listeners.push(fn); }
  emit() { this.listeners.forEach(fn => { try { fn(); } catch (_) {} }); }
  destroy() { window.removeEventListener('resize', this.onResize); this.layer.remove(); this.closePop(); }

  checkSoon() {
    clearTimeout(this.timer);
    // AI findings follow edits made elsewhere; the one edited is dropped.
    this.timer = setTimeout(() => this.check(), 450);
    this.layer.classList.add('stale');
  }
  check() {
    clearTimeout(this.timer);
    this.layer.classList.remove('stale');
    if (this.off) { this.found = []; this.paint(); this.emit(); return; }
    const doc = this.editor.state.doc;
    this.found = styleFindings(doc, { sets: this.prefs.sets, whilst: this.prefs.whilst, rules: this.prefs.rules, ignore: this.prefs.ignore, ignored: this.ignored });
    // AI findings: still where they were, with the same words?
    this.ai = this.ai.filter(f => f.to <= doc.content.size && doc.textBetween(f.from, f.to, '\n', '￼') === f.text && !this.ignored.has(f.key));
    if (this.prefs.sets.ai !== false) this.found = styleOverlaps(this.found.concat(this.ai));
    this.paint(); this.emit();
  }
  paintSoon() { cancelAnimationFrame(this.raf); this.raf = requestAnimationFrame(() => this.paint()); }
  /** The underlines: one thin line under each stretch, drawn over the page. */
  paint() {
    clear(this.layer);
    if (this.off || !this.found.length) return;
    const view = this.editor.view, base = this.sheet.getBoundingClientRect();
    const frag = document.createDocumentFragment();
    this.found.slice(0, 1500).forEach(f => {
      let rects;
      try {
        const a = view.domAtPos(f.from), b = view.domAtPos(f.to), r = document.createRange();
        r.setStart(a.node, a.offset); r.setEnd(b.node, b.offset); rects = [...r.getClientRects()];
      } catch (_) { return; }
      rects.filter(q => q.width > 1).forEach(q => frag.append(h('i', { class: 'wsu ' + f.set, style: { left: (q.left - base.left) + 'px', top: (q.bottom - base.top - 2) + 'px', width: q.width + 'px' } })));
    });
    this.layer.append(frag);
  }

  setOff(v) { this.off = v; this.onChange(); this.check(); }
  setSet(k, v) { this.prefs.sets[k] = v; this.savePrefs(); this.check(); }
  setWhilst(v) { this.prefs.whilst = v; this.savePrefs(); this.check(); }
  addRule(from, to) { this.prefs.rules = (this.prefs.rules || []).filter(r => r.from.toLowerCase() !== from.toLowerCase()).concat([{ from, to }]); this.prefs.sets.own = true; this.savePrefs(); this.check(); }
  removeRule(i) { this.prefs.rules.splice(i, 1); this.savePrefs(); this.check(); }
  unignore(w) { this.prefs.ignore = (this.prefs.ignore || []).filter(x => x !== w); this.savePrefs(); this.check(); }

  /** Puts the suggestion in (only the characters that change, so formatting stays). */
  accept(f, quiet) {
    const doc = this.editor.state.doc;
    if (f.to > doc.content.size || doc.textBetween(f.from, f.to, '\n', '￼') !== f.text) { this.check(); return false; }
    const tr = this.editor.state.tr;
    f.edits.slice().sort((x, y) => y[0] - x[0]).forEach(([a, b, t]) => { if (t) tr.insertText(t, a, b); else tr.delete(a, b); });
    this.editor.view.dispatch(tr);
    this.ai = this.ai.filter(x => x !== f);
    if (!quiet) { this.closePop(); this.check(); }
    return true;
  }
  /** Accepts every finding of the same kind (the same word and suggestion). */
  acceptAllLike(f) {
    const same = this.found.filter(x => x.set === f.set && x.text === f.text && x.sug === f.sug).sort((a, b) => b.from - a.from);
    same.forEach(x => this.accept(x, true));
    this.closePop(); this.check();
    toast(`${same.length} changed`);
  }
  ignore(f) { this.ignored.add(f.key); this.onChange(); this.closePop(); this.check(); }
  ignoreEverywhere(f) {
    const w = f.text.toLowerCase();
    if (!(this.prefs.ignore || []).includes(w)) this.prefs.ignore = (this.prefs.ignore || []).concat([w]);
    this.savePrefs(); this.closePop(); this.check();
    toast(`“${f.text}” will not be marked again (Style panel → Ignored words)`);
  }

  at(pos) { return this.found.find(f => pos >= f.from && pos <= f.to && f.to > f.from); }
  clicked(e) {
    if (this.off || e.button) return;
    const sel = this.editor.state.selection; if (!sel.empty) return;
    const hit = this.editor.view.posAtCoords({ left: e.clientX, top: e.clientY });
    const f = hit && this.at(hit.pos); if (!f) { this.closePop(); return; }
    this.show(f);
  }
  /** Goes to a finding and opens it. */
  goTo(f) {
    try { this.editor.chain().focus().setTextSelection(f.from).run(); } catch (_) {}
    const dom = this.editor.view.domAtPos(f.from);
    const el = dom.node.nodeType === 1 ? dom.node : dom.node.parentElement;
    if (el && el.scrollIntoView) el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    setTimeout(() => this.show(f), 260);
  }
  closePop() { if (this.pop) { this.pop.remove(); this.pop = null; this.popFor = null; } }
  show(f) {
    this.closePop();
    const view = this.editor.view;
    let c; try { c = view.coordsAtPos(f.from); } catch (_) { return; }
    const set = STYLE_SETS.find(s => s[0] === f.set);
    const same = this.found.filter(x => x.set === f.set && x.text === f.text && x.sug === f.sug).length;
    const pop = h('div', { class: 'wstylepop', onmousedown: e => e.preventDefault() },
      h('div', { class: 'wsptag', style: { color: styleColour(f.set) } }, set ? set[1] : ''),
      h('div', { class: 'wspchange' }, h('s', null, f.text.length > 80 ? f.text.slice(0, 80) + '…' : f.text), ' → ', h('b', null, f.sug.length > 120 ? f.sug.slice(0, 120) + '…' : (f.sug || '(remove)'))),
      f.why ? h('div', { class: 'wspwhy muted' }, f.why) : null,
      h('div', { class: 'wspbtns' },
        h('button', { class: 'btn fill', onclick: () => this.accept(f) }, 'Accept'),
        same > 1 ? h('button', { class: 'btn', onclick: () => this.acceptAllLike(f), title: 'Every place with the same word and suggestion' }, `Accept all ${same}`) : null,
        h('button', { class: 'btn', onclick: () => this.ignore(f) }, 'Ignore'),
        f.set !== 'ai' ? h('button', { class: 'btn quiet', onclick: () => this.ignoreEverywhere(f), title: 'Never mark this word again, in any document' }, 'Ignore everywhere') : null,
        f.set !== 'ai' && f.set !== 'own' ? h('button', { class: 'btn quiet', onclick: () => { this.closePop(); styleRuleDialog(this, f.text, ''); }, title: 'Make your own rule for this word' }, 'Own rule…') : null));
    document.body.append(pop);
    const w = pop.offsetWidth, hgt = pop.offsetHeight;
    let left = Math.min(Math.max(8, c.left - 12), innerWidth - w - 8), top = c.bottom + 8;
    if (top + hgt > innerHeight - 8) top = c.top - hgt - 8;
    pop.style.left = left + 'px'; pop.style.top = top + 'px';
    this.pop = pop; this.popFor = f;
    const away = ev => { if (this.pop === pop && !pop.contains(ev.target) && !this.editor.view.dom.contains(ev.target)) { this.closePop(); document.removeEventListener('mousedown', away, true); } };
    document.addEventListener('mousedown', away, true);
  }

  /** The AI check of [from, to] (a selection, the section around the cursor, or the whole document). */
  async aiCheck(scope) {
    const ed = this.editor, doc = ed.state.doc;
    let from = 0, to = doc.content.size;
    if (scope === 'selection') { ({ from, to } = ed.state.selection); if (to <= from) return toast('Select some text first'); }
    else if (scope === 'section') {
      const pos = ed.state.selection.from; let start = 0, end = doc.content.size, level = 9;
      doc.forEach((n, off) => { if (n.type.name === 'heading') { if (off <= pos && (start === 0 || off >= start)) { start = off; level = n.attrs.level; } } });
      doc.forEach((n, off) => { if (off > start && n.type.name === 'heading' && n.attrs.level <= level && off < end && off > pos) end = off; });
      from = start; to = end;
    }
    // The text with a map back to positions (quotations stay in, so the AI sees the sentence).
    let text = '', at = [];
    doc.nodesBetween(from, to, (node, pos) => {
      if (node.type.name === 'codeBlock') return false;
      if (node.isTextblock) {
        if (text) { text += '\n\n'; at.push(-1, -1); }
        node.forEach((child, off) => {
          const p0 = pos + 1 + off;
          if (child.isText) { for (let i = 0; i < child.text.length; i++) { const p = p0 + i; if (p >= from && p < to) { text += child.text[i]; at.push(p); } } }
          else if (p0 >= from && p0 < to) { text += '￼'; at.push(p0); }
        });
        return false;
      }
      return true;
    });
    if (!text.trim()) return toast('There is no text there');
    if (text.length > 14000) return toast('Too much text for one check. Select a part, or check the section around the cursor.');
    this.aiBusy = true; this.emit();
    try {
      const r = await api('style-check', { method: 'POST', json: { text: text.replace(/￼/g, '[…]'), instructions: this.prefs.aiNote || '' } });
      let n = 0;
      (r.issues || []).forEach(x => {
        const orig = String(x.original || '').replace(/\[…\]/g, '￼'); if (!orig || orig === x.suggestion) return;
        let i = text.indexOf(orig), hits = 0;
        while (i >= 0 && hits < 5) {
          const a = at[i], b = at[i + orig.length - 1];
          if (a >= 0 && b >= 0) {
            const f = { set: 'ai', from: a, to: b + 1, text: doc.textBetween(a, b + 1, '\n', '￼'), sug: String(x.suggestion || '').replace(/\[…\]/g, ''), why: String(x.reason || '') };
            f.key = 'ai|' + f.text + '|' + f.sug; f.edits = [[f.from, f.to, f.sug]];
            if (f.text === orig && !this.ignored.has(f.key) && !this.ai.some(y => y.from === f.from && y.to === f.to)) { this.ai.push(f); n++; }
          }
          hits++; i = text.indexOf(orig, i + orig.length);
        }
      });
      this.prefs.sets.ai = true;
      this.check();
      toast(n ? `The AI marked ${n} place${n === 1 ? '' : 's'} (green underline)` : 'The AI found nothing to change', 3500);
    } catch (e) { fail(e); }
    this.aiBusy = false; this.emit();
  }
}

/** Add or change one of your own rules. */
function styleRuleDialog(checker, from, to) {
  modal((box, close) => {
    const a = h('input', { value: from || '', placeholder: 'e.g. impact (as a verb)' }), b = h('input', { value: to || '', placeholder: 'e.g. affect' });
    const go = () => { if (!a.value.trim()) return toast('Write what should be replaced'); checker.addRule(a.value.trim(), b.value); close(); toast('Rule added'); };
    [a, b].forEach(x => x.addEventListener('keydown', e => { if (e.key === 'Enter') go(); }));
    box.append(h('h2', null, 'Your own rule'),
      h('p', { class: 'muted', style: { marginTop: 0 } }, 'Wherever the first appears as a whole word or phrase, the second is suggested. Leave the second empty to have the first only marked. Capitals follow the text.'),
      h('div', { class: 'fields' }, h('label', null, h('span', { class: 'label' }, 'Not this'), a), h('label', null, h('span', { class: 'label' }, 'But this'), b)),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: go }, 'Add rule')));
  });
}

/** The Style panel: what is switched on, the findings in order, your rules, ignored words, the AI check. */
function stylePanel(checker, onToggle) {
  const el = h('aside', { class: 'wstylepanel', hidden: true });
  const body = h('div');
  el.append(h('div', { class: 'wnhead' }, h('b', null, 'Style'), h('span', { style: { flex: 1 } }), h('button', { class: 'btn quiet', title: 'Close', onclick: () => api_.close() }, '×')), body);
  let filter = '';
  const draw = () => {
    if (el.hidden) return;
    const keepScroll = el.scrollTop;
    clear(body);
    const S = checker.prefs.sets;
    const onOff = h('input', { type: 'checkbox', checked: !checker.off });
    onOff.addEventListener('change', () => checker.setOff(!onOff.checked));
    body.append(h('label', { class: 'wsprow strong' }, onOff, h('span', null, 'Check this document')));
    if (checker.off) { body.append(h('p', { class: 'muted', style: { fontSize: '12.5px' } }, 'Off for this document (for instance a text in Norwegian). Your rules and choices are kept.')); return; }
    const counts = {}; checker.found.forEach(f => { counts[f.set] = (counts[f.set] || 0) + 1; });
    STYLE_SETS.forEach(([k, label, sub]) => {
      const cb = h('input', { type: 'checkbox', checked: S[k] !== false });
      cb.addEventListener('change', () => checker.setSet(k, cb.checked));
      body.append(h('label', { class: 'wsprow' }, cb, h('i', { class: 'wspdot', style: { background: styleColour(k) } }),
        h('span', { style: { flex: 1 } }, h('b', null, label), h('div', { class: 'muted', style: { fontSize: '11.5px' } }, sub)),
        counts[k] ? h('button', { class: 'wspcount' + (filter === k ? ' on' : ''), title: 'Show only these', onclick: e => { e.preventDefault(); filter = filter === k ? '' : k; draw(); } }, String(counts[k])) : null));
    });
    const wh = h('input', { type: 'checkbox', checked: !!checker.prefs.whilst });
    wh.addEventListener('change', () => checker.setWhilst(wh.checked));
    body.append(h('label', { class: 'wsprow small' }, wh, h('span', null, 'Prefer “whilst” and “amongst”')));

    // AI
    body.append(...[h('div', { class: 'label', style: { marginTop: '14px' } }, 'AI check'),
      h('div', { class: 'muted', style: { fontSize: '12px', marginBottom: '6px' } }, 'Sends the text to your AI (Settings → AI) for register, grammar and British usage. Runs only when you press.'),
      h('div', { class: 'row', style: { gap: '4px', flexWrap: 'wrap' } },
        h('button', { class: 'btn', disabled: checker.aiBusy, onclick: () => checker.aiCheck('selection') }, 'Selection'),
        h('button', { class: 'btn', disabled: checker.aiBusy, onclick: () => checker.aiCheck('section') }, 'This section'),
        h('button', { class: 'btn', disabled: checker.aiBusy, onclick: () => checker.aiCheck('all') }, 'Whole text')),
      checker.aiBusy ? h('div', { class: 'muted', style: { fontSize: '12px', marginTop: '4px' } }, 'The AI is reading…') : null].filter(Boolean));
    const note = h('input', { value: checker.prefs.aiNote || '', placeholder: 'Extra wishes for the AI (optional)', style: { width: '100%', boxSizing: 'border-box', marginTop: '6px', fontSize: '12px' } });
    note.addEventListener('change', () => { checker.prefs.aiNote = note.value.trim(); checker.savePrefs(); });
    body.append(note);

    // findings
    const list = filter ? checker.found.filter(f => f.set === filter) : checker.found;
    body.append(h('div', { class: 'label', style: { marginTop: '14px' } }, list.length ? `${list.length} to look at` + (filter ? ' · ' : '') : 'Nothing to look at',
      filter ? h('a', { href: 'javascript:void 0', onclick: () => { filter = ''; draw(); } }, 'show all') : null));
    const ul = h('div', { class: 'wsplist' });
    list.slice(0, 300).forEach(f => ul.append(h('div', { class: 'wspitem', onclick: () => checker.goTo(f), title: f.why || '' },
      h('i', { class: 'wspdot', style: { background: styleColour(f.set) } }),
      h('span', { class: 'wspi' }, h('s', null, f.text.length > 40 ? f.text.slice(0, 40) + '…' : f.text), ' → ', h('b', null, f.sug.length > 50 ? f.sug.slice(0, 50) + '…' : (f.sug || '(remove)'))),
      h('button', { class: 'btn quiet mxmini', title: 'Accept', onclick: e => { e.stopPropagation(); checker.accept(f); } }, '✓'))));
    if (list.length > 300) ul.append(h('div', { class: 'muted', style: { fontSize: '12px' } }, `…and ${list.length - 300} more`));
    body.append(ul);
    if (list.length > 1) body.append(h('button', { class: 'btn', style: { marginTop: '6px' }, onclick: () => confirmBox(`Accept all ${list.length}?`, 'Every suggestion in the list is put in. Ctrl+Z takes them back one by one.', 'Accept all', () => { list.slice().sort((a, b) => b.from - a.from).forEach(f => checker.accept(f, true)); checker.check(); }) }, `Accept all ${list.length}`));

    // own rules
    body.append(h('div', { class: 'label', style: { marginTop: '16px' } }, 'Your own rules'));
    (checker.prefs.rules || []).forEach((r, i) => body.append(h('div', { class: 'wsprule' }, h('span', null, h('s', null, r.from), ' → ', h('b', null, r.to || '(mark only)')), h('button', { class: 'btn quiet mxmini', title: 'Remove the rule', onclick: () => checker.removeRule(i) }, '×'))));
    body.append(h('button', { class: 'btn', onclick: () => styleRuleDialog(checker, '', '') }, '+ Add a rule'));
    if ((checker.prefs.ignore || []).length) {
      body.append(h('div', { class: 'label', style: { marginTop: '16px' } }, 'Ignored words'));
      body.append(h('div', { class: 'wspign' }, checker.prefs.ignore.map(w => h('span', { class: 'mxchip' }, h('span', null, w), h('button', { title: 'Mark it again', onclick: () => checker.unignore(w) }, '×')))));
    }
    el.scrollTop = keepScroll;
  };
  checker.subscribe(draw);
  const api_ = {
    el,
    isOpen: () => !el.hidden,
    open() { el.hidden = false; localSet('ml.wstyleOpen', '1'); draw(); onToggle && onToggle(true); },
    close() { el.hidden = true; localSet('ml.wstyleOpen', ''); onToggle && onToggle(false); },
    toggle() { if (el.hidden) api_.open(); else api_.close(); },
  };
  return api_;
}
