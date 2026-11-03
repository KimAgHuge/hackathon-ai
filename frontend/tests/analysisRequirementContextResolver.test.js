import assert from 'node:assert/strict'
import test from 'node:test'
import { requirementContextForJob, requirementContextFromList } from '../src/lib/analysisRequirementContext.js'

const job = (values = {}) => ({ jobId: 'job-3', projectId: 'project-1', requirementVersionId: 'version-3', ...values })
const requirement = (values = {}, version = {}) => ({
    id: 'requirement-1',
    projectId: 'project-1',
    title: '현재 요구사항 제목',
    ...values,
    currentVersion: {
        id: 'version-3',
        versionNumber: 3,
        title: '분석에 사용한 제목',
        originalContent: '목표 완료일을 추가한다.',
        refinedContent: '완료일 입력과 저장을 추가한다.',
        acceptanceCriteria: [{ then: '목록에 완료일이 표시된다.' }],
        sourceAnalysisJobId: 'job-2',
        ...version,
    },
})
const analysis = (facts, values = {}) => ({ jobId: 'job-3', facts: { sourceSummary: { changeRequirement: facts } }, ...values })

test('같은 프로젝트의 정확한 버전만 목록에서 고정 요청 정보로 사용한다', () => {
    const selected = requirement()
    const result = requirementContextFromList([requirement({ projectId: 'project-other' }), requirement({}, { id: 'version-4' }), selected], job())
    assert.deepEqual(result, {
        requirementId: selected.id,
        versionId: 'version-3',
        versionNumber: 3,
        title: '분석에 사용한 제목',
        originalContent: '목표 완료일을 추가한다.',
        refinedContent: '완료일 입력과 저장을 추가한다.',
        acceptanceCriteria: [{ then: '목록에 완료일이 표시된다.' }],
        sourceAnalysisJobId: 'job-2',
        latestFollowUpQuestion: null,
    })
})

test('다른 프로젝트나 다른 버전의 현재 내용을 과거 작업에 섞지 않는다', () => {
    assert.equal(requirementContextFromList([requirement({ projectId: 'project-other' })], job()), null)
    assert.equal(requirementContextFromList([requirement({}, { id: 'version-4' })], job()), null)
    assert.equal(requirementContextFromList([requirement({}, { id: null })], job()), null)
    assert.equal(requirementContextFromList([{ ...requirement(), currentVersion: null }], job()), null)
})

test('목록의 후속 질문은 해당 버전의 이전 작업에서 보낸 마지막 사용자 질문만 사용한다', () => {
    const clarifications = [
        { kind: 'USER_QUESTION', sourceAnalysisJobId: 'job-2', question: '처음 보낸 질문' },
        { kind: 'AI_QUESTION', sourceAnalysisJobId: 'job-2', question: 'AI가 만든 질문' },
        { kind: 'USER_QUESTION', sourceAnalysisJobId: 'job-other', question: '다른 작업의 질문' },
        { kind: 'USER_QUESTION', sourceAnalysisJobId: 'job-2', question: '마지막으로 보낸 질문' },
        { kind: 'USER_QUESTION', sourceAnalysisJobId: 'job-2', question: '   ' },
        { kind: 'USER_QUESTION', sourceAnalysisJobId: 'job-2', question: null },
        null,
    ]
    const result = requirementContextFromList([requirement({}, { clarifications })], job())
    assert.equal(result.latestFollowUpQuestion, '마지막으로 보낸 질문')
})

test('이전 작업이 기록되지 않은 버전은 임의의 후속 질문을 표시하지 않는다', () => {
    const result = requirementContextFromList(
        [requirement({}, { sourceAnalysisJobId: null, clarifications: [{ kind: 'USER_QUESTION', question: '출처가 없는 질문' }] })],
        job()
    )
    assert.equal(result.latestFollowUpQuestion, null)
    assert.equal(requirementContextFromList([requirement({}, { clarifications: {} })], job()).latestFollowUpQuestion, null)
})

test('버전 제목이 없을 때 같은 요구사항 제목만 대신 사용한다', () => {
    assert.equal(requirementContextFromList([requirement({}, { title: '' })], job()).title, '현재 요구사항 제목')
})

test('작업에 고정된 요청 정보를 다른 후보와 섞지 않고 가장 먼저 사용한다', () => {
    const frozen = Object.freeze({ versionId: 'version-3', title: '분석 시작 때의 제목', originalContent: '분석 시작 때의 원문' })
    const result = requirementContextForJob(
        job({ requirementContext: frozen }),
        analysis({ versionId: 'version-3', title: '저장 결과의 제목', refinedContent: '저장 결과의 정리 내용' }),
        { versionId: 'version-3', title: '현재 목록 제목', latestFollowUpQuestion: '현재 목록 질문' }
    )
    assert.strictEqual(result, frozen)
    assert.equal(result.refinedContent, undefined)
    assert.equal(result.latestFollowUpQuestion, undefined)
})

test('같은 작업에 저장된 이전 형식의 근거를 같은 버전 목록보다 먼저 사용한다', () => {
    const fallback = Object.freeze({ versionId: 'version-3', title: '목록 제목', versionNumber: 3, originalContent: '목록 원문' })
    const facts = Object.freeze({ title: '저장 결과 제목', originalContent: '저장 결과 원문', refinedContent: '저장 결과 정리' })
    const result = requirementContextForJob(job(), analysis(facts), fallback)
    assert.deepEqual(result, { ...fallback, ...facts, versionId: 'version-3' })
    assert.equal(fallback.title, '목록 제목')
    assert.equal(facts.versionId, undefined)
    assert.deepEqual(requirementContextForJob(job(), analysis(facts), null), { ...facts, versionId: 'version-3' })
})

test('다른 작업 결과나 다른 버전에 저장된 근거는 사용하지 않는다', () => {
    const fallback = Object.freeze({ versionId: 'version-3', title: '안전한 같은 버전 제목' })
    const facts = { versionId: 'version-3', title: '다른 작업 제목' }
    assert.strictEqual(requirementContextForJob(job(), analysis(facts, { jobId: 'job-other' }), fallback), fallback)
    assert.equal(requirementContextForJob(job(), analysis(facts, { jobId: 'job-other' }), null), null)
    assert.strictEqual(requirementContextForJob(job(), analysis({ ...facts, versionId: 'version-4' }), fallback), fallback)
    assert.equal(requirementContextForJob(job(), analysis({ ...facts, versionId: 'version-4' }), null), null)
})

test('작업의 요청 정보나 목록 버전이 다르면 같은 작업의 저장 근거까지만 허용한다', () => {
    const mismatchedJob = job({ requirementContext: { versionId: 'version-4', title: '다른 버전 제목' } })
    const mismatchedFallback = { versionId: 'version-4', title: '다른 목록 제목' }
    const facts = { versionId: 'version-3', title: '이 작업의 제목' }
    assert.deepEqual(requirementContextForJob(mismatchedJob, analysis(facts), mismatchedFallback), facts)
    assert.equal(requirementContextForJob(mismatchedJob, null, mismatchedFallback), null)
    assert.equal(requirementContextForJob(job({ requirementContext: [] }), analysis([]), []), null)
})

test('요구사항 버전이 없는 구조 분석이나 잘못된 목록 입력은 요청 정보가 없다', () => {
    const context = { versionId: 'version-3', title: '남아 있는 이전 요청' }
    assert.equal(requirementContextForJob(null, analysis(context), context), null)
    assert.equal(requirementContextForJob(job({ requirementVersionId: null, requirementContext: context }), analysis(context), context), null)
    assert.equal(requirementContextFromList([requirement()], null), null)
    assert.equal(requirementContextFromList([requirement()], job({ requirementVersionId: null })), null)
    for (const value of [null, undefined, {}, 'requirements']) assert.equal(requirementContextFromList(value, job()), null)
})
