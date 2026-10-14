import test from 'node:test'
import assert from 'node:assert/strict'
import { buildFollowUpPayload } from '../src/lib/followUpWorkflow.js'

test('keeps the original question indexes when blank answers are skipped', () => {
    assert.deepEqual(buildFollowUpPayload('job-1', 'version-1', [' ', ' organization_members ', ''], ' 다른 조직은? '), {
        sourceAnalysisJobId: 'job-1',
        expectedVersionId: 'version-1',
        answers: [{ questionIndex: 1, answer: 'organization_members' }],
        followUpQuestion: '다른 조직은?',
    })
})

test('requires an answer or a new question', () => {
    assert.throws(() => buildFollowUpPayload('job-1', 'version-1', ['', ' '], ' '), /답변이나 추가 질문/)
})
