import test from 'node:test'
import assert from 'node:assert/strict'
import { analysisReviewLabel, trainingTransferPresentation } from '../src/lib/analysisReviewPresentation.js'

test('검토 상태를 작업 중심의 명칭으로 표시한다', () => {
    assert.equal(analysisReviewLabel('APPROVED'), '승인 완료')
    assert.equal(analysisReviewLabel('REJECTED'), '반려')
    assert.equal(analysisReviewLabel('PENDING'), '검토 대기')
    assert.equal(analysisReviewLabel(undefined), '검토 대기')
})

test('데이터 전달 완료와 모델 학습·적용을 구분한다', () => {
    const info = trainingTransferPresentation('VERIFIED')
    assert.equal(info.label, 'AI 학습 서버 수신·검증 완료')
    assert.match(info.description, /모델 학습·평가는 별도 단계/)
    assert.match(info.description, /모델은 바뀌지 않습니다/)
})

test('연결 대기와 동기화 대기 및 검증 실패의 다음 행동을 구분한다', () => {
    assert.match(trainingTransferPresentation('WAITING_FOR_RECEIVER').description, /다시 전송할 필요는 없습니다/)
    assert.match(trainingTransferPresentation('WAITING_FOR_SYNC').description, /파일 동기화와 수신 검증/)
    assert.match(trainingTransferPresentation('REJECTED').description, /학습 자료로 사용하지 않습니다/)
    for (const status of [undefined, '', 'UNEXPECTED', '__proto__']) {
        assert.equal(trainingTransferPresentation(status).label, '전송 상태 확인 중')
    }
})
