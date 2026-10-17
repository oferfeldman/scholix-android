const assert = require('node:assert/strict');
const test = require('node:test');
const { invoke } = require(process.env.OPENU_WORKER_PATH);

const course = { id: '20474', semester: '2026ב', assignments: [{ assignmentNo: '84', examinationDate: '01/07/2026', hasExamFileId: true, hasQuestionnaireFileId: false, hasQuestionnaire: true }, { assignmentNo: '85', hasExamFileId: false, hasQuestionnaireFileId: false, hasQuestionnaire: false }] };

test('exam date and document availability use actual Sheilta metadata', async () => {
  const client = {
    fetchCourses: async () => [course],
    fetchAssignments: async () => ({ success: true, assignments: ['84', '85'].map(number => ({ number, exam_date: '', artifacts: { notebook: true, questionnaire: 'exam' } })) }),
  };
  const result = await invoke(client, 'assignments', { course_id: course.id, semester: course.semester });
  assert.equal(result.success, true);
  const rows = result.assignments;
  assert.equal(rows[0].exam_date, '01/07/2026');
  assert.equal(rows[0].artifacts.notebook, true);
  assert.equal(rows[1].artifacts.notebook, false);
  assert.equal(rows[1].artifacts.questionnaire, null);
});

test('checked notebook download passes the exact assignment number and preserves PDF bytes', async () => {
  const pdf = Buffer.from('%PDF-1.4\nsynthetic fixture');
  let calls = 0;
  const client = {
    fetchCourses: async () => [course],
    downloadAssessmentDocument: async (...args) => {
      calls++;
      assert.deepEqual(args, ['20474', '2026ב', '84', 'notebook', 'exam']);
      return new Response(pdf, { headers: { 'content-type': 'application/pdf' } });
    },
  };
  const result = await invoke(client, 'assessment_document', { course_id: course.id, semester: course.semester, assignment_no: '84', artifact: 'notebook' });
  assert.equal(calls, 1);
  assert.deepEqual(Buffer.from(result.base64, 'base64'), pdf);
});
