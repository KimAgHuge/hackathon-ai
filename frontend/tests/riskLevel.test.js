import assert from 'node:assert/strict'
import test from 'node:test'
import { riskLevelForScore, riskLevelLabel, riskPresentation } from '../src/lib/riskLevel.js'

test('renders every supported risk level in Korean', () => {
    assert.equal(riskLevelLabel('LOW'), '낮음')
    assert.equal(riskLevelLabel('MEDIUM'), '보통')
    assert.equal(riskLevelLabel('HIGH'), '높음')
    assert.equal(riskLevelLabel('CRITICAL'), '매우 높음')
    assert.equal(riskLevelLabel('UNKNOWN'), '판단 불가')
})

test('handles missing or unrecognized risk levels without exposing raw codes', () => {
    assert.equal(riskLevelLabel(null), '미산정')
    assert.equal(riskLevelLabel('high'), '높음')
    assert.equal(riskLevelLabel('UNRECOGNIZED'), '확인 필요')
})

test('derives a consistent grade from the same score bands as the AI server', () => {
    assert.equal(riskLevelForScore(24), 'LOW')
    assert.equal(riskLevelForScore(25), 'MEDIUM')
    assert.equal(riskLevelForScore(50), 'HIGH')
    assert.equal(riskLevelForScore(75), 'CRITICAL')
})

test('flags old contradictory results instead of displaying a misleading score', () => {
    const displayed = riskPresentation('HIGH', 8)
    assert.equal(displayed.label, '점검 필요')
    assert.equal(displayed.score, null)
    assert.match(displayed.warning, /일치하지 않습니다/)
})

test('keeps unresolved conditions separate from implementation risk', () => {
    const assessment = {
        changeScope: { score: 15, reason: '여러 영역 변경' },
        dataImpact: { score: 8, reason: '저장 구조 변경' },
        securityImpact: { score: 22, reason: '인증 경로 변경' },
        deliveryComplexity: { score: 12, reason: '배포 조정' },
        uncertainty: { score: 5, reason: '업무 조건 미정' },
    }
    const displayed = riskPresentation('HIGH', 62, assessment)
    assert.equal(displayed.label, '높음')
    assert.equal(displayed.score, 62)
    assert.equal(displayed.provisional, true)
    assert.ok(riskPresentation('HIGH', 61, assessment).warning)
})
