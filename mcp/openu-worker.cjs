// One isolated invocation of the owner's existing OpenU client; no automatic write retries.
const { OpenUClient } = require('./lib/openu.js');
const { CookieJar } = require('./lib/cookie-jar.js');

async function file(response) {
  if (!response.ok) throw new Error('OpenU file request returned HTTP ' + response.status + '. Use the exact semester and assignment_no returned by assignments, rather than the exam date.');
  const chunks = [];
  let length = 0;
  for await (const chunk of response.body) {
    length += chunk.length;
    if (length > 10 * 1024 * 1024) throw new Error('File exceeds 10 MiB');
    chunks.push(chunk);
  }
  return { mime_type: response.headers.get('content-type'), base64: Buffer.concat(chunks).toString('base64') };
}

async function invoke(client, operation, args) {
  if (operation === 'login') return client.login(args.username, args.password, args.student_id);
  if (operation === 'courses' || operation === 'grades') return client.fetchCourses();
  if (operation === 'account_info') return client.fetchProfile();
  if (operation === 'exams') return client.fetchExams(await client.fetchCourseRows());
  if (operation === 'calendar') {
    const rows = await client.fetchCourseRows();
    const selected = args.course_id ? rows.filter(row => String(row.courseId) === args.course_id && (!args.semester || String(row.semester) === args.semester)) : rows;
    const meetings = [];
    for (const row of selected) meetings.push(...await client.fetchMeetings(row));
    return { meetings, exams: await client.fetchExams(selected) };
  }
  if (operation === 'recording_collection') return client.discoverCollectionRecordings(args.cmid, args.title || 'Recordings');

  const courses = await client.fetchCourses();
  const matches = courses.filter(course => course.id === args.course_id && (!args.semester || course.semester === args.semester));
  if (matches.length !== 1) throw new Error('Choose an enrolled course_id and exact semester from courses');
  const course = matches[0];
  const semester = course.semester;
  if (operation === 'assignments') {
    const result = await client.fetchAssignments(course.id, semester, undefined, course.assignments);
    result.assignments = result.assignments.map(assignment => {
      const raw = course.assignments?.find(item => String(item.assignmentNo) === assignment.number);
      if (!raw) return assignment;
      if (raw.examinationDate) assignment.exam_date = raw.examinationDate;
      if (typeof raw.hasExamFileId === 'boolean') assignment.artifacts.notebook = raw.hasExamFileId;
      if (typeof raw.hasQuestionnaireFileId === 'boolean') assignment.artifacts.questionnaire = raw.hasQuestionnaireFileId ? 'personal' : raw.hasQuestionnaire ? 'exam' : null;
      return assignment;
    });
    return result;
  }
  if (operation === 'assessment_document') return file(await client.downloadAssessmentDocument(course.id, semester, args.assignment_no, args.artifact, args.kind || 'exam'));
  if (operation === 'assessment_appeal') return { url: await client.resolveAssessmentAppeal(course.id, semester, args.assignment_no) };
  if (operation === 'booklet') return client.fetchCourseBooklet(course.id, semester);
  if (operation === 'booklet_file') return file(await client.downloadCourseBooklet(course.id, semester, args.file));
  if (operation === 'recordings') return client.discoverRecordings(course.id, semester);
  if (operation === 'recording_collections') return client.discoverRecordingCollections(course.id, semester);
  if (operation === 'recording_stream') return { url: await client.resolveStream(course.id, semester, args.title, args.hints || {}) };
  const moodleId = await client.resolveCourseSite(course.id, semester);
  if (!moodleId) return { has_course_site: false, course };
  if (operation === 'course_page') return { course, moodle_id: moodleId, url: `https://opal.openu.ac.il/course/view.php?id=${moodleId}`, ...await client.fetchCourseSections(moodleId) };
  if (operation === 'course_section') return client.fetchCourseSection(moodleId, args.section_id);
  if (operation === 'activity') return client.fetchCourseActivity(moodleId, args.module, args.cmid);
  if (operation === 'forums') return client.fetchCourseForums(moodleId);
  if (operation === 'forum') return client.fetchCourseForum(moodleId, args.forum_id, args.page || 0);
  if (operation === 'forum_discussion') return client.fetchCourseForumDiscussion(moodleId, args.discussion_id);
  if (operation === 'books') return client.fetchCourseBooks(moodleId);
  if (operation === 'book_file') return file(await client.downloadCourseBook(moodleId, args.book_id));
  if (operation === 'accept_book_terms') return { url: await client.acceptCourseBookTerms(moodleId, args.book_id) };
  if (operation === 'start_quiz') return client.startCourseQuiz(moodleId, args.cmid);
  if (operation === 'answer_quiz') return client.submitCourseQuizAnswers(moodleId, args.cmid, args.attempt_id, args.answers);
  if (operation === 'finish_quiz') return client.finishCourseQuiz(moodleId, args.cmid, args.attempt_id);
  if (operation === 'review_quiz') return client.reviewCourseQuiz(moodleId, args.cmid, args.attempt_id);
  throw new Error('Unknown OpenU operation');
}

if (require.main === module) (async () => {
  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  const request = JSON.parse(input);
  const client = new OpenUClient(CookieJar.fromJSON(request.cookies), fetch, AbortSignal.timeout(150000));
  let result;
  try {
    result = { result: await invoke(client, request.operation, request.arguments || {}) };
  } catch (error) {
    // Do not forward upstream error bodies, URLs containing SSO tokens, or credentials.
    result = { error: error.status === 401 ? 'OpenU session expired; reconnect in the owner console' : error.message.startsWith('OpenU file request returned HTTP ') ? error.message : 'OpenU operation failed. Check its availability on the website before retrying.', status: error.status || 502 };
  }
  process.stdout.write(JSON.stringify({ ...result, cookies: client.jar.toJSON() }));
})().catch(() => { process.stderr.write('OpenU worker failed\n'); process.exitCode = 1; });

module.exports = { invoke };
