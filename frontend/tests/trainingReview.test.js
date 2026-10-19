import test from 'node:test'
import assert from 'node:assert/strict'
import { applyTrainingReviewConfirmations } from '../src/lib/trainingReview.js'

test('확인란의 선택을 반영하고 기존 사례 내용은 유지한다', () => {
    const draft = { caseId: 'case-1', projectGroup: 'project-1', humanApproved: false, sanitized: false, answers: { general: { summary: '검토한 결과' } } }
    const result = applyTrainingReviewConfirmations(JSON.stringify(draft), true, true)
    assert.equal(result.humanApproved, true)
    assert.equal(result.sanitized, true)
    assert.deepEqual(result.answers, draft.answers)
    assert.equal(result.projectGroup, 'project-1')
    assert.equal(draft.humanApproved, false)
})

test('JSON에 true를 직접 써도 선택하지 않은 확인란은 승인되지 않는다', () => {
    const text = JSON.stringify({ humanApproved: true, sanitized: true })
    assert.deepEqual(applyTrainingReviewConfirmations(text, false, true), { humanApproved: false, sanitized: true })
    assert.deepEqual(applyTrainingReviewConfirmations(text, true, false), { humanApproved: true, sanitized: false })
})

test('잘못된 JSON과 객체가 아닌 입력을 거부한다', () => {
    for (const text of ['{', 'null', '[]', 'true', '"문자열"']) {
        assert.throws(() => applyTrainingReviewConfirmations(text, true, true))
    }
})
