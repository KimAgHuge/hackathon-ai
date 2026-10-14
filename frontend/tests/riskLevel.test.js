import assert from 'node:assert/strict'
import test from 'node:test'
import { riskLevelLabel } from '../src/lib/riskLevel.js'

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
