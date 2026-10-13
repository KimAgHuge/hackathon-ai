import assert from 'node:assert/strict'
import test from 'node:test'
import { buildAnalysisJobPayload, buildRequirementPayload } from '../src/lib/requirementWorkflow.js'

test('builds a requirement with a refined version and line-based acceptance criteria', () => {
    const payload = buildRequirementPayload({
        requirementKey: ' REQ-12 ',
        title: ' 조직 선택 변경 ',
        priority: 'HIGH',
        originalContent: ' 원래 요청 ',
        refinedContent: ' 정리한 변경 내용 ',
        acceptanceCriteriaText: ' 첫 번째 조건\n\n 두 번째 조건 ',
    })

    assert.equal(payload.requirementKey, 'REQ-12')
    assert.equal(payload.title, '조직 선택 변경')
    assert.equal(payload.originalContent, '원래 요청')
    assert.equal(payload.refinedContent, '정리한 변경 내용')
    assert.deepEqual(payload.acceptanceCriteria, ['첫 번째 조건', '두 번째 조건'])
    assert.equal(payload.sourceType, null)
    assert.equal(payload.customerVisible, false)
})

test('rejects a requirement without content for the analysis prompt', () => {
    assert.throws(
        () =>
            buildRequirementPayload({
                requirementKey: '',
                title: '요구사항',
                priority: 'MEDIUM',
                originalContent: '요청 원문',
                refinedContent: '  ',
                acceptanceCriteriaText: '',
            }),
        /정리한 변경 내용/
    )
})

test('only targeted analysis sends a requirement version', () => {
    const versionId = 'd29a0057-6e7f-4c0c-8959-091db31699d9'
    assert.deepEqual(buildAnalysisJobPayload(versionId), { requirementVersionId: versionId })
    assert.equal(buildAnalysisJobPayload(null), undefined)
})
