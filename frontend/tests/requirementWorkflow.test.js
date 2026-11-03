import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
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

test('구조 분석과 요구사항 분석은 추론 기본값을 보내며 대상 버전과 저장 근거를 유지한다', () => {
    const versionId = 'd29a0057-6e7f-4c0c-8959-091db31699d9'
    const snapshotId = 'eb590190-a818-48a5-937d-64d35b4e3468'
    assert.deepEqual(buildAnalysisJobPayload(versionId), { requirementVersionId: versionId, thinkingEnabled: true, thinkingEffort: 'medium' })
    assert.deepEqual(buildAnalysisJobPayload(null), { thinkingEnabled: true, thinkingEffort: 'medium' })
    assert.deepEqual(buildAnalysisJobPayload(versionId, snapshotId), {
        requirementVersionId: versionId,
        reuseFromJobId: snapshotId,
        confirmedUnchanged: true,
        thinkingEnabled: true,
        thinkingEffort: 'medium',
    })
    assert.deepEqual(buildAnalysisJobPayload(versionId, snapshotId, false), {
        requirementVersionId: versionId,
        reuseFromJobId: snapshotId,
        confirmedUnchanged: true,
        thinkingEnabled: false,
        thinkingEffort: 'medium',
    })
})

test('선택한 추론 강도는 저장 근거와 추론 해제 상태에서도 보존한다', () => {
    for (const effort of ['low', 'medium', 'high']) {
        const payload = buildAnalysisJobPayload('version-test', 'snapshot-test', false, effort)
        assert.equal(payload.thinkingEffort, effort)
        assert.equal(payload.thinkingEnabled, false)
        assert.equal(payload.reuseFromJobId, 'snapshot-test')
        assert.equal(payload.confirmedUnchanged, true)
    }
})

test('잘못된 추론 강도를 보통으로 바꾸지 않고 요청 전에 차단한다', () => {
    for (const effort of ['maximum', '', 'HIGH', null, 2]) {
        assert.throws(() => buildAnalysisJobPayload(null, null, true, effort), /추론 강도/)
    }
})

test('근거 선택은 요구사항이 있고 자동이 아닌 경우에만 추론 설정과 함께 보낸다', () => {
    const versionId = 'd29a0057-6e7f-4c0c-8959-091db31699d9'
    const defaults = { requirementVersionId: versionId, thinkingEnabled: true, thinkingEffort: 'medium' }
    assert.deepEqual(buildAnalysisJobPayload(versionId, null, true, 'medium', 'AUTO'), defaults)
    for (const evidenceSelection of ['ALWAYS', 'OFF']) {
        assert.deepEqual(buildAnalysisJobPayload(versionId, null, true, 'medium', evidenceSelection), { ...defaults, evidenceSelection })
        assert.deepEqual(buildAnalysisJobPayload(null, null, true, 'medium', evidenceSelection), { thinkingEnabled: true, thinkingEffort: 'medium' })
    }
    assert.deepEqual(buildAnalysisJobPayload(versionId, null, true, 'medium', 'weird'), defaults)
})

test('저장 근거와 추론 해제·강도·근거 선택을 서로 바꾸거나 누락하지 않는다', () => {
    for (const evidenceSelection of ['ALWAYS', 'OFF']) {
        for (const effort of ['low', 'medium', 'high']) {
            assert.deepEqual(buildAnalysisJobPayload('version-test', 'snapshot-test', false, effort, evidenceSelection), {
                requirementVersionId: 'version-test',
                reuseFromJobId: 'snapshot-test',
                confirmedUnchanged: true,
                thinkingEnabled: false,
                thinkingEffort: effort,
                evidenceSelection,
            })
        }
    }
})

test('분석 시작 API는 기존 추론 인자 위치를 유지하고 여섯 번째 근거 선택을 함께 전송한다', async () => {
    const calls = []
    const key = '__combinedAnalysisOptionsTest'
    globalThis[key] = { post: async (...args) => (calls.push(args), { data: { jobId: 'job-test' } }) }
    try {
        const source = (await readFile(new URL('../src/api/analysisApi.js', import.meta.url), 'utf8'))
            .replace("import { httpClient } from '@/api/httpClient'", `const httpClient = globalThis.${key}`)
            .replace("'@/lib/requirementWorkflow'", JSON.stringify(new URL('../src/lib/requirementWorkflow.js', import.meta.url).href))
            .replace('import.meta.env.VITE_USE_MOCK', "'false'")
        const moduleUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
        const { startProjectAnalysis } = await import(moduleUrl)
        await startProjectAnalysis('project-test', 'version-test', 'snapshot-test', false, 'high', 'ALWAYS')
        await startProjectAnalysis('project-test', 'version-test', null, true, 'low', 'OFF')
        await startProjectAnalysis('project-test', null, null, true, 'medium')
        assert.deepEqual(calls, [
            [
                '/projects/project-test/analysis-jobs',
                {
                    requirementVersionId: 'version-test',
                    reuseFromJobId: 'snapshot-test',
                    confirmedUnchanged: true,
                    thinkingEnabled: false,
                    thinkingEffort: 'high',
                    evidenceSelection: 'ALWAYS',
                },
            ],
            [
                '/projects/project-test/analysis-jobs',
                {
                    requirementVersionId: 'version-test',
                    thinkingEnabled: true,
                    thinkingEffort: 'low',
                    evidenceSelection: 'OFF',
                },
            ],
            ['/projects/project-test/analysis-jobs', { thinkingEnabled: true, thinkingEffort: 'medium' }],
        ])
    } finally {
        delete globalThis[key]
    }
})
