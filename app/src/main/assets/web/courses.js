/* Marginalia Lectoris — Courses: programmes → semesters → courses, each with its teachers, links and
 * course outline (sessions with their readings, linked to the library and opened from here).
 *
 * The whole thing is one document on the tablet (courses.json), saved whole with a version number.
 */

const courses = { data: null, version: 0, saving: null };
const cid = () => Math.random().toString(36).slice(2, 10);

async function coursesLoad(fresh) {
  if (courses.data && !fresh) return courses.data;
  const r = await api('courses');
  courses.data = r.data || { programmes: [] }; courses.version = r.version || 0;
  if (!Array.isArray(courses.data.programmes)) courses.data.programmes = [];
  return courses.data;
}
/** Saves the whole document; if another page saved in between, its version is taken and you are told. */
async function coursesSave() {
  try {
    const r = await api('courses', { method: 'PUT', json: { data: courses.data, version: courses.version } });
    if (r.conflict) { courses.data = r.data; courses.version = r.version; toast('Courses were changed elsewhere; showing the newest. Please make your change again.', 5000); route(); return false; }
    courses.version = r.version; return true;
  } catch (e) { fail(e); return false; }
}
/** Finds a course with its semester and programme. */
function courseFind(id) {
  for (const p of courses.data.programmes) for (const s of p.semesters || []) for (const c of s.courses || []) if (c.id === id) return { p, s, c };
  return null;
}
const courseDate = d => { if (!d) return ''; const t = new Date(d + 'T12:00:00'); return isNaN(t) ? d : t.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }); };
const todayIso = () => new Date().toISOString().slice(0, 10);

/** A small form in a dialog: fields [{ key, label, type, value, placeholder, options }]; onSave(values). */
function courseForm(title, fields, onSave, extra) {
  modal((box, close) => {
    const inputs = {};
    const rows = fields.map(f => {
      const el = f.type === 'textarea' ? h('textarea', { rows: f.rows || 4, placeholder: f.placeholder || '' }) :
        f.type === 'select' ? h('select', null, f.options.map(([v, l]) => h('option', { value: v, selected: String(f.value || '') === v }, l))) :
        h('input', { type: f.type || 'text', placeholder: f.placeholder || '' });
      if (f.type !== 'select') el.value = f.value == null ? '' : f.value;
      inputs[f.key] = el;
      return h('label', { class: 'crsfield' + (f.wide ? ' wide' : '') }, h('span', { class: 'label' }, f.label), el);
    });
    const save = () => { const v = {}; Object.entries(inputs).forEach(([k, el]) => { v[k] = el.value.trim(); }); if (onSave(v) !== false) close(); };
    box.classList.add('wide');
    box.append(h('h2', null, title), h('div', { class: 'crsform' }, rows), ...(extra ? [extra(inputs)] : []),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: save }, 'Save')));
    setTimeout(() => { const f = Object.values(inputs)[0]; if (f) f.focus(); }, 30);
  });
}

// ------------------------------------------------------------------ the pages

async function showCourses(parts) {
  const app = clear(document.getElementById('app'));
  state.tab = 'courses';
  app.appendChild(header());
  const main = h('main', { class: 'crspage' }); app.appendChild(main);
  try { await coursesLoad(true); } catch (e) { fail(e); return; }
  const [pid, sid] = [parts[1], parts[2]];
  const p = pid && courses.data.programmes.find(x => x.id === pid);
  const s = p && sid && (p.semesters || []).find(x => x.id === sid);
  const crumbs = h('div', { class: 'crscrumbs' }, h('a', { href: '#/courses' }, 'Courses'),
    ...(p ? [' › ', h('a', { href: `#/courses/${p.id}` }, p.name)] : []),
    ...(s ? [' › ', h('span', null, s.name)] : []));
  main.append(crumbs);
  if (s) return drawSemester(main, p, s);
  if (p) return drawProgramme(main, p);
  drawProgrammes(main);
}

function drawProgrammes(main) {
  const list = courses.data.programmes;
  main.append(h('div', { class: 'crshead' }, h('h1', null, 'Courses'), h('span', { style: { flex: 1 } }),
    h('button', { class: 'btn', onclick: () => programmeDialog(null) }, '+ Programme')));
  if (!list.length) { main.append(h('div', { class: 'empty' }, h('h2', null, 'No programmes yet'), h('p', { class: 'muted' }, 'Add your programme (for example a bachelor’s or master’s degree), then its semesters and courses.'))); return; }
  const grid = h('div', { class: 'crsgrid' });
  list.forEach(p => {
    const sem = p.semesters || [], n = sem.reduce((k, s) => k + (s.courses || []).length, 0);
    const credits = sem.reduce((k, s) => k + (s.courses || []).reduce((j, c) => j + (+c.credits || 0), 0), 0);
    grid.append(h('a', { class: 'crscard big', href: `#/courses/${p.id}` },
      h('div', { class: 'crskind' }, [p.degree, p.institution].filter(Boolean).join(' · ') || 'Programme'),
      h('div', { class: 'crstitle' }, p.name),
      h('div', { class: 'muted' }, [p.years, `${sem.length} semester${sem.length === 1 ? '' : 's'}`, `${n} course${n === 1 ? '' : 's'}`, credits ? credits + ' credits' : ''].filter(Boolean).join(' · '))));
  });
  main.append(grid);
}

function programmeDialog(p) {
  courseForm(p ? 'Edit the programme' : 'New programme', [
    { key: 'name', label: 'Name', value: p && p.name, placeholder: 'Bachelor in Comparative Politics', wide: true },
    { key: 'degree', label: 'Degree', value: p && p.degree, placeholder: 'Bachelor’s / Master’s' },
    { key: 'institution', label: 'Institution', value: p && p.institution, placeholder: 'University of Bergen' },
    { key: 'years', label: 'Years', value: p && p.years, placeholder: '2022–2025' },
  ], v => {
    if (!v.name) { toast('Give it a name'); return false; }
    if (p) Object.assign(p, v); else courses.data.programmes.push(Object.assign({ id: cid(), semesters: [] }, v));
    coursesSave().then(ok => ok && route());
  });
}

function drawProgramme(main, p) {
  main.append(h('div', { class: 'crshead' }, h('div', null, h('div', { class: 'crskind' }, [p.degree, p.institution, p.years].filter(Boolean).join(' · ')), h('h1', null, p.name)),
    h('span', { style: { flex: 1 } }),
    h('button', { class: 'btn quiet', onclick: () => programmeDialog(p) }, 'Edit'),
    h('button', { class: 'btn quiet', onclick: () => confirmBox('Delete the programme?', `“${p.name}” with its semesters and courses. The books and notes stay in the library.`, 'Delete', () => { courses.data.programmes = courses.data.programmes.filter(x => x !== p); coursesSave().then(() => { location.hash = '#/courses'; }); }) }, 'Delete'),
    h('button', { class: 'btn', onclick: () => semesterDialog(p, null) }, '+ Semester')));
  const sem = p.semesters || [];
  if (!sem.length) { main.append(h('p', { class: 'muted' }, 'No semesters yet.')); return; }
  const grid = h('div', { class: 'crsgrid' });
  sem.forEach(s => {
    const cs = s.courses || [];
    grid.append(h('a', { class: 'crscard', href: `#/courses/${p.id}/${s.id}` },
      h('div', { class: 'crstitle' }, s.name),
      h('div', { class: 'muted' }, `${cs.length} course${cs.length === 1 ? '' : 's'}` + (cs.length ? ' · ' + cs.reduce((k, c) => k + (+c.credits || 0), 0) + ' credits' : '')),
      h('div', { class: 'crschips' }, cs.map(c => h('span', { class: 'crschip' }, c.code || c.title)))));
  });
  main.append(grid);
}

function semesterDialog(p, s) {
  courseForm(s ? 'Edit the semester' : 'New semester', [
    { key: 'name', label: 'Name', value: s && s.name, placeholder: 'Autumn 2024', wide: true },
    { key: 'start', label: 'Starts', type: 'date', value: s && s.start },
    { key: 'end', label: 'Ends', type: 'date', value: s && s.end },
  ], v => {
    if (!v.name) { toast('Give it a name'); return false; }
    if (s) Object.assign(s, v); else { p.semesters = p.semesters || []; p.semesters.push(Object.assign({ id: cid(), courses: [] }, v)); }
    coursesSave().then(ok => ok && route());
  });
}

function drawSemester(main, p, s) {
  main.append(h('div', { class: 'crshead' }, h('div', null, h('div', { class: 'crskind' }, p.name), h('h1', null, s.name)),
    h('span', { style: { flex: 1 } }),
    h('button', { class: 'btn quiet', onclick: () => semesterDialog(p, s) }, 'Edit'),
    h('button', { class: 'btn quiet', onclick: () => confirmBox('Delete the semester?', `“${s.name}” with its courses.`, 'Delete', () => { p.semesters = p.semesters.filter(x => x !== s); coursesSave().then(() => { location.hash = `#/courses/${p.id}`; }); }) }, 'Delete'),
    h('button', { class: 'btn', onclick: () => courseDialog(s, null) }, '+ Course')));
  const cs = s.courses || [];
  if (!cs.length) { main.append(h('p', { class: 'muted' }, 'No courses yet.')); return; }
  const grid = h('div', { class: 'crsgrid' });
  cs.forEach(c => {
    const sessions = c.sessions || [], readings = sessions.flatMap(x => x.readings || []);
    grid.append(h('a', { class: 'crscard', href: `#/course/${c.id}` },
      h('div', { class: 'crskind' }, [c.code, c.credits ? c.credits + ' credits' : '', c.grade ? 'grade ' + c.grade : ''].filter(Boolean).join(' · ')),
      h('div', { class: 'crstitle' }, c.title || c.code),
      h('div', { class: 'muted' }, [(c.teachers || []).map(t => t.name).filter(Boolean).join(', '), sessions.length ? `${sessions.length} sessions` : '', readings.length ? `${readings.length} readings` : ''].filter(Boolean).join(' · '))));
  });
  main.append(grid);
}

function courseDialog(s, c) {
  courseForm(c ? 'Edit the course' : 'New course', [
    { key: 'code', label: 'Code', value: c && c.code, placeholder: 'SAMPOL390' },
    { key: 'title', label: 'Title', value: c && c.title, placeholder: 'Comparative judicial behaviour', wide: true },
    { key: 'credits', label: 'Credits (ECTS)', type: 'number', value: c && c.credits },
    { key: 'grade', label: 'Grade', value: c && c.grade, placeholder: 'A–F / pass' },
    { key: 'description', label: 'About the course', type: 'textarea', value: c && c.description, wide: true },
  ], v => {
    if (!v.code && !v.title) { toast('Give it a code or a title'); return false; }
    if (c) { Object.assign(c, v); coursesSave().then(ok => ok && route()); }
    else { const n = Object.assign({ id: cid(), teachers: [], links: [], sessions: [] }, v); s.courses = s.courses || []; s.courses.push(n); coursesSave().then(ok => { if (ok) location.hash = `#/course/${n.id}`; }); }
  });
}

// ------------------------------------------------------------------ a course

async function showCourse(id) {
  const app = clear(document.getElementById('app'));
  state.tab = 'courses';
  app.appendChild(header());
  const main = h('main', { class: 'crspage crscourse' }); app.appendChild(main);
  let docs = [];
  try { await coursesLoad(true); docs = await loadLibrary(); } catch (e) { fail(e); return; }
  const f = courseFind(id);
  if (!f) { main.append(h('div', { class: 'empty' }, h('h2', null, 'Course not found'), h('a', { href: '#/courses' }, 'All courses'))); return; }
  const { p, s, c } = f;
  const byId = new Map(docs.map(d => [d.id, d]));
  const save = () => coursesSave().then(ok => ok && showCourse(id));

  main.append(h('div', { class: 'crscrumbs' }, h('a', { href: '#/courses' }, 'Courses'), ' › ', h('a', { href: `#/courses/${p.id}` }, p.name), ' › ', h('a', { href: `#/courses/${p.id}/${s.id}` }, s.name)));
  main.append(h('div', { class: 'crshead' },
    h('div', null, h('div', { class: 'crskind' }, [c.code, c.credits ? c.credits + ' credits' : '', c.grade ? 'grade ' + c.grade : '', s.name].filter(Boolean).join(' · ')), h('h1', null, c.title || c.code)),
    h('span', { style: { flex: 1 } }),
    h('button', { class: 'btn quiet', onclick: () => courseDialog(s, c) }, 'Edit'),
    h('button', { class: 'btn quiet', onclick: () => confirmBox('Delete the course?', `“${c.title || c.code}” with its outline. The books and notes stay in the library.`, 'Delete', () => { s.courses = s.courses.filter(x => x !== c); coursesSave().then(() => { location.hash = `#/courses/${p.id}/${s.id}`; }); }) }, 'Delete')));
  if (c.description) main.append(h('p', { class: 'crsdesc' }, c.description));

  // Links
  const links = h('div', { class: 'crslinks' });
  (c.links || []).forEach((l, k) => links.append(h('span', { class: 'crslink' },
    h('a', { href: l.url, target: '_blank', rel: 'noopener' }, l.label || l.url.replace(/^https?:\/\//, '').slice(0, 40)),
    h('button', { class: 'btn quiet', title: 'Remove the link', onclick: () => { c.links.splice(k, 1); save(); } }, '×'))));
  links.append(h('button', { class: 'btn quiet', onclick: () => courseForm('Add a link', [
    { key: 'label', label: 'Name', placeholder: 'Course page / Canvas / Exam' }, { key: 'url', label: 'Address', placeholder: 'https://…', wide: true }],
    v => { if (!/^https?:\/\//.test(v.url)) { toast('The address must start with https://'); return false; } c.links = c.links || []; c.links.push(v); save(); }) }, '+ Link'));
  main.append(h('section', { class: 'crssec' }, h('h2', null, 'Links'), links));

  // Teachers
  const people = h('div', { class: 'crspeople' });
  (c.teachers || []).forEach((t, k) => people.append(teacherCard(t, () => teacherDialog(c, t, save), () => confirmBox('Remove the teacher?', t.name, 'Remove', () => { c.teachers.splice(k, 1); save(); }))));
  people.append(h('button', { class: 'crsperson add', onclick: () => teacherDialog(c, null, save) }, '+ Teacher'));
  main.append(h('section', { class: 'crssec' }, h('h2', null, 'Teachers'), people));

  // The course outline
  const sessions = (c.sessions || []).slice().sort((a, b) => (a.date || '9999').localeCompare(b.date || '9999') || (a.time || '').localeCompare(b.time || ''));
  const today = todayIso(), next = sessions.find(x => x.date && x.date >= today);
  const outline = h('div', { class: 'crsoutline' });
  if (!sessions.length) outline.append(h('p', { class: 'muted' }, 'No sessions yet. Add the lectures and seminars with their readings.'));
  sessions.forEach((x, k) => {
    const rd = h('div', { class: 'crsreads' });
    (x.readings || []).forEach((r, j) => rd.append(readingRow(r, byId.get(r.docId), c, () => readingDialog(x, r, docs, save), () => { x.readings.splice(j, 1); save(); }, save)));
    rd.append(h('button', { class: 'btn quiet crsaddread', onclick: () => readingDialog(x, null, docs, save) }, '+ Reading'));
    outline.append(h('div', { class: 'crssession' + (x === next ? ' next' : '') + (x.date && x.date < today ? ' past' : '') },
      h('div', { class: 'crswhen' }, h('div', { class: 'crsdate' }, courseDate(x.date) || 'No date'), h('div', { class: 'muted' }, [x.time, x.room].filter(Boolean).join(' · ')),
        x === next ? h('span', { class: 'crsnext' }, 'Next') : null),
      h('div', { class: 'crswhat' },
        h('div', { class: 'crstopic' }, h('span', { class: 'crskindtag' }, x.kind || 'Lecture'), ' ', x.topic || h('span', { class: 'muted' }, '(no topic)'),
          h('span', { style: { flex: 1 } }),
          h('button', { class: 'btn quiet', title: 'Edit the session', onclick: () => sessionDialog(c, x, save) }, 'Edit'),
          h('button', { class: 'btn quiet', title: 'Delete the session', onclick: () => confirmBox('Delete the session?', x.topic || courseDate(x.date), 'Delete', () => { c.sessions = c.sessions.filter(y => y !== x); save(); }) }, '×')),
        x.note ? h('div', { class: 'crsnote' }, x.note) : null, rd)));
  });
  main.append(h('section', { class: 'crssec' }, h('div', { class: 'row', style: { alignItems: 'center', gap: '8px' } }, h('h2', null, 'Course outline'), h('span', { style: { flex: 1 } }),
    h('button', { class: 'btn', onclick: () => sessionDialog(c, null, save) }, '+ Session')), outline));
  if (next) setTimeout(() => { const el = main.querySelector('.crssession.next'); if (el && location.hash === `#/course/${id}`) el.scrollIntoView({ block: 'center' }); }, 200);
}

function teacherCard(t, onEdit, onRemove) {
  return h('div', { class: 'crsperson' },
    t.photo ? h('img', { class: 'crsphoto', src: '/api/course-photo/' + t.photo, alt: '' }) : h('div', { class: 'crsphoto none' }, (t.name || '?').split(/\s+/).map(w => w[0]).slice(0, 2).join('')),
    h('div', { class: 'crspinfo' },
      h('div', { class: 'crspname' }, t.name || '(no name)'),
      h('div', { class: 'muted' }, [t.role, t.title].filter(Boolean).join(' · ')),
      t.email ? h('a', { href: 'mailto:' + t.email }, t.email) : null,
      t.phone ? h('div', null, h('a', { href: 'tel:' + t.phone.replace(/\s+/g, '') }, t.phone)) : null,
      t.office ? h('div', { class: 'muted' }, t.office) : null,
      t.profile ? h('a', { href: t.profile, target: '_blank', rel: 'noopener' }, 'Profile ↗') : null,
      h('div', { class: 'row', style: { gap: '4px', marginTop: '4px' } }, h('button', { class: 'btn quiet', onclick: onEdit }, 'Edit'), h('button', { class: 'btn quiet', onclick: onRemove }, 'Remove'))));
}

function teacherDialog(c, t, save) {
  let photo = t && t.photo;
  courseForm(t ? 'Edit the teacher' : 'Add a teacher', [
    { key: 'name', label: 'Name', value: t && t.name, wide: true },
    { key: 'role', label: 'Role in the course', type: 'select', value: (t && t.role) || 'Lecturer', options: ['Course leader', 'Lecturer', 'Seminar leader', 'Supervisor', 'Examiner', 'Guest lecturer'].map(x => [x, x]) },
    { key: 'title', label: 'Title', value: t && t.title, placeholder: 'Professor / Associate professor' },
    { key: 'email', label: 'Email', type: 'email', value: t && t.email },
    { key: 'phone', label: 'Phone', value: t && t.phone },
    { key: 'office', label: 'Office', value: t && t.office, placeholder: 'Building, room' },
    { key: 'profile', label: 'Profile page', value: t && t.profile, placeholder: 'https://www.uib.no/en/persons/…', wide: true },
  ], v => {
    if (!v.name) { toast('Give a name'); return false; }
    const n = Object.assign({}, v, { photo: photo || '' });
    if (t) Object.assign(t, n); else { c.teachers = c.teachers || []; c.teachers.push(Object.assign({ id: cid() }, n)); }
    save();
  }, () => {
    const img = h('div', { class: 'crsphoto' + (photo ? '' : ' none') });
    const show = () => { clear(img); img.classList.toggle('none', !photo); if (photo) img.append(h('img', { src: '/api/course-photo/' + photo, alt: '' })); else img.append('photo'); };
    show();
    const file = h('input', { type: 'file', accept: 'image/*', style: { display: 'none' } });
    file.addEventListener('change', async () => {
      const f0 = file.files[0]; if (!f0) return;
      try {
        const r = await fetch('/api/course-photo', { method: 'POST', body: f0, headers: { 'X-Marginalia': '1', 'Content-Type': f0.type || 'image/jpeg' }, credentials: 'same-origin' });
        const j = await r.json(); if (!r.ok) throw new Error(j.error || 'Upload failed');
        photo = j.name; show();
      } catch (e) { fail(e); }
    });
    return h('div', { class: 'row', style: { gap: '10px', alignItems: 'center', margin: '8px 0' } }, img, h('button', { class: 'btn', onclick: () => file.click() }, 'Choose a photo…'),
      h('button', { class: 'btn quiet', onclick: () => { photo = ''; show(); } }, 'No photo'), file);
  });
}

function sessionDialog(c, x, save) {
  courseForm(x ? 'Edit the session' : 'New session', [
    { key: 'date', label: 'Date', type: 'date', value: x && x.date },
    { key: 'time', label: 'Time', value: x && x.time, placeholder: '10:15–12:00' },
    { key: 'kind', label: 'Kind', type: 'select', value: (x && x.kind) || 'Lecture', options: ['Lecture', 'Seminar', 'Workshop', 'Supervision', 'Exam', 'Deadline', 'Other'].map(k => [k, k]) },
    { key: 'room', label: 'Room', value: x && x.room },
    { key: 'topic', label: 'Topic', value: x && x.topic, wide: true },
    { key: 'note', label: 'Notes', type: 'textarea', rows: 3, value: x && x.note, wide: true },
  ], v => {
    if (x) Object.assign(x, v); else { c.sessions = c.sessions || []; c.sessions.push(Object.assign({ id: cid(), readings: [] }, v)); }
    save();
  });
}

const READ_STATUS = [['unread', 'Unread'], ['started', 'Started'], ['done', 'Done']];
function readingRow(r, d, c, onEdit, onRemove, save) {
  const status = h('select', { class: 'crsstatus s-' + (r.status || 'unread'), title: 'How far you have come' }, READ_STATUS.map(([v, l]) => h('option', { value: v, selected: (r.status || 'unread') === v }, l)));
  status.addEventListener('change', () => { r.status = status.value; status.className = 'crsstatus s-' + r.status; coursesSave(); });
  const where = [r.chapter, r.pages ? 'pp. ' + r.pages : ''].filter(Boolean).join(' · ');
  const page = +r.startPage || +(String(r.pages || '').match(/\d+/) || [])[0] || 0;
  const href = d ? `#/read/${d.id}?${page ? `page=${page}&pos=${page}&` : ''}course=${encodeURIComponent(c.id)}` : null;
  return h('div', { class: 'crsread' + (r.required === 'optional' ? ' optional' : '') },
    d ? h('img', { class: 'crscover', src: coverUrl(d), alt: '', loading: 'lazy', onerror: e => { e.target.style.visibility = 'hidden'; } }) : h('div', { class: 'crscover none' }, '?'),
    h('div', { class: 'crsrinfo' },
      h('div', { class: 'crsrtitle' }, d ? d.title : (r.text || '(a reading)'), r.required === 'optional' ? h('span', { class: 'muted' }, ' · optional') : null),
      h('div', { class: 'muted' }, [d && d.author, where].filter(Boolean).join(' · ') || (d ? '' : 'Not in the library yet'))),
    status,
    href ? h('a', { class: 'btn', href, title: 'Read it' + (page ? ' from page ' + page : '') }, 'Open') : null,
    h('button', { class: 'btn quiet', onclick: onEdit }, 'Edit'),
    h('button', { class: 'btn quiet', title: 'Remove the reading', onclick: onRemove }, '×'));
}

/** A reading: a book or article from the library (searched by title or author), with chapter and pages. */
function readingDialog(x, r, docs, save) {
  let chosen = r && r.docId ? docs.find(d => d.id === r.docId) : null;
  modal((box, close) => {
    box.classList.add('wide');
    const q = h('input', { type: 'search', placeholder: 'Search the library: title or author…' });
    const results = h('div', { class: 'crsresults' });
    const picked = h('div', { class: 'crspicked' });
    const chapter = h('input', { placeholder: 'e.g. Chapter 3: Resentment' }); chapter.value = (r && r.chapter) || '';
    const pages = h('input', { placeholder: 'e.g. 45–78', style: { width: '120px' } }); pages.value = (r && r.pages) || '';
    const text = h('textarea', { rows: 2, placeholder: 'As it stands in the reading list (used when it is not in the library)' }); text.value = (r && r.text) || '';
    const req = h('select', null, [['required', 'Required'], ['optional', 'Optional']].map(([v, l]) => h('option', { value: v, selected: ((r && r.required) || 'required') === v }, l)));
    const drawPicked = () => { clear(picked).append(chosen ? h('div', { class: 'row', style: { gap: '8px', alignItems: 'center' } }, h('b', null, chosen.title), h('span', { class: 'muted' }, chosen.author || ''), h('button', { class: 'btn quiet', onclick: () => { chosen = null; drawPicked(); } }, 'Change')) : h('span', { class: 'muted' }, 'Nothing chosen from the library (the reading is kept as text).')); };
    const search = () => {
      const terms = norm(q.value).split(/\s+/).filter(Boolean);
      clear(results);
      if (!terms.length) return;
      docs.filter(d => terms.every(t => norm([d.title, d.author, d.year, (d.tags || '')].join(' ')).includes(t))).slice(0, 12).forEach(d => results.append(
        h('button', { class: 'crsres', onclick: () => { chosen = d; drawPicked(); clear(results); q.value = ''; } }, h('b', null, d.title), ' ', h('span', { class: 'muted' }, [d.author, d.year].filter(Boolean).join(' · ')))));
    };
    q.addEventListener('input', search);
    drawPicked();
    const go = () => {
      if (!chosen && !text.value.trim()) { toast('Choose something from the library or write what the reading is'); return; }
      const v = { docId: chosen ? chosen.id : null, chapter: chapter.value.trim(), pages: pages.value.trim(), text: text.value.trim(), required: req.value };
      if (r) Object.assign(r, v); else { x.readings = x.readings || []; x.readings.push(Object.assign({ id: cid(), status: 'unread' }, v)); }
      close(); save();
    };
    box.append(h('h2', null, r ? 'Edit the reading' : 'Add a reading'), q, results, h('div', { class: 'label', style: { marginTop: '8px' } }, 'From the library'), picked,
      h('div', { class: 'crsform' }, h('label', { class: 'crsfield wide' }, h('span', { class: 'label' }, 'Chapter or part'), chapter), h('label', { class: 'crsfield' }, h('span', { class: 'label' }, 'Pages'), pages), h('label', { class: 'crsfield' }, h('span', { class: 'label' }, 'Kind'), req),
        h('label', { class: 'crsfield wide' }, h('span', { class: 'label' }, 'As listed'), text)),
      h('div', { class: 'foot' }, h('button', { class: 'btn', onclick: close }, 'Cancel'), h('button', { class: 'btn fill', onclick: go }, r ? 'Save' : 'Add')));
    setTimeout(() => q.focus(), 30);
  });
}
