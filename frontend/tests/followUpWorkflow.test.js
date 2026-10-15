import test from 'node:test'
import assert from 'node:assert/strict'
import { buildFollowUpPayload, canContinueFromAnalysis, savedFollowUpDraft } from '../src/lib/followUpWorkflow.js'

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

test('allows the original questions after a follow-up version was created', () => {
    assert.equal(canContinueFromAnalysis('job-1', 'version-1', { id: 'version-1' }), true)
    assert.equal(canContinueFromAnalysis('job-1', 'version-1', { id: 'version-2', sourceAnalysisJobId: 'job-1' }), true)
    assert.equal(canContinueFromAnalysis('job-1', 'version-1', { id: 'version-2', sourceAnalysisJobId: 'job-2' }), false)
})

test('restores only the saved answers belonging to the original analysis', () => {
    const draft = savedFollowUpDraft('job-1', [
        { kind: 'AI_QUESTION_ANSWER', sourceAnalysisJobId: 'job-1', questionIndex: 1, answer: '기존 답변' },
        { kind: 'AI_QUESTION_ANSWER', sourceAnalysisJobId: 'job-2', questionIndex: 0, answer: '다른 분석' },
        { kind: 'USER_QUESTION', sourceAnalysisJobId: 'job-1', question: '추가 조건' },
        { kind: 'AI_QUESTION_ANSWER', sourceAnalysisJobId: 'job-1', questionIndex: 1, answer: '수정 답변' },
    ])
    assert.equal(draft.answers.length, 2)
    assert.equal(draft.answers[0], undefined)
    assert.equal(draft.answers[1], '수정 답변')
    assert.equal(draft.question, '추가 조건')
})
