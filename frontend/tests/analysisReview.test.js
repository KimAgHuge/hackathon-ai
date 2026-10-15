import test from 'node:test'
import assert from 'node:assert/strict'
import { buildAnalysisReviewPayload } from '../src/lib/analysisReview.js'

test('AI 원본 승인과 반려는 수정 결과를 보내지 않는다', () => {
    assert.deepEqual(buildAnalysisReviewPayload('APPROVED', '  실제 근거를 확인했습니다.  '), {
        decision: 'APPROVED',
        note: '실제 근거를 확인했습니다.',
        correctedResult: null,
    })
    assert.equal(buildAnalysisReviewPayload('REJECTED', '근거가 틀립니다.', true, '{"summary":"수정"}').correctedResult, null)
})

test('수정본 승인은 JSON 객체를 전달한다', () => {
    const result = { summary: '수정한 설명' }
    assert.deepEqual(buildAnalysisReviewPayload('APPROVED', '검토 완료', true, JSON.stringify(result)).correctedResult, result)
})

test('사유 누락과 잘못된 수정 JSON을 차단한다', () => {
    assert.throws(() => buildAnalysisReviewPayload('APPROVED', '  '), /검토 이유/)
    assert.throws(() => buildAnalysisReviewPayload('APPROVED', '검토 완료', true, '{'), /JSON/)
    assert.throws(() => buildAnalysisReviewPayload('APPROVED', '검토 완료', true, '[]'), /JSON 객체/)
})
