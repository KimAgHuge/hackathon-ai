import test from 'node:test'
import assert from 'node:assert/strict'
import { analysisCorrectionIssues, parseAnalysisCorrection, updateAnalysisCorrection, updateAnalysisCorrectionItem } from '../src/lib/analysisCorrection.js'
import { buildAnalysisReviewPayload } from '../src/lib/analysisReview.js'

const result = () => ({
    summary: '현재 코드와 요구사항을 확인한 분석 요약',
    plainLanguageSummary: '표시 설정을 변경합니다.',
    recommendations: ['첫 번째 작업', '두 번째 작업', '세 번째 작업'],
    impactAreas: ['목록 화면'],
    riskScore: 8,
    riskLevel: 'LOW',
    riskAssessment: { changeScope: { score: 3, reason: '작은 변경' } },
    databaseImpact: { status: 'NOT_APPLICABLE', explanation: 'DB 변경 없음', proposedChanges: [] },
    evidences: [{ file: 'src/list.vue', line: 12, description: '확인한 선언' }],
    frameworks: ['Vue'],
})

test('두 번째 대응 방안만 수정하고 나머지 판단과 근거를 보존한다', () => {
    const original = result()
    const text = JSON.stringify(original)
    const changed = parseAnalysisCorrection(updateAnalysisCorrectionItem(text, 'recommendations', 1, '기존 첫 페이지 이동 처리를 유지합니다.'))
    assert.deepEqual(changed.recommendations, ['첫 번째 작업', '기존 첫 페이지 이동 처리를 유지합니다.', '세 번째 작업'])
    for (const key of Object.keys(original).filter((key) => key !== 'recommendations')) assert.deepEqual(changed[key], original[key])
    assert.equal(original.recommendations[1], '두 번째 작업')
    assert.deepEqual(buildAnalysisReviewPayload('APPROVED', '기존 코드와 대조해 문장을 수정했습니다.', true, JSON.stringify(changed)).correctedResult, changed)
})

test('간편 입력과 전체 JSON 편집이 같은 객체를 읽으며 나머지 값을 다시 만들지 않는다', () => {
    const original = result()
    original.databaseImpact.explanation = '전체 JSON에서 검토한 내용'
    const changed = parseAnalysisCorrection(updateAnalysisCorrection(JSON.stringify(original), 'summary', '수정한 분석 요약'))
    assert.equal(changed.summary, '수정한 분석 요약')
    assert.deepEqual(changed.databaseImpact, original.databaseImpact)
    assert.deepEqual(changed.riskAssessment, original.riskAssessment)
})

test('목록 추가·삭제와 편집 중 빈 값의 위치를 안내한다', () => {
    let text = updateAnalysisCorrection(JSON.stringify(result()), 'recommendations', ['남길 작업', ''])
    assert.match(analysisCorrectionIssues(text).join(' '), /권장 대응 방안 2/)
    text = updateAnalysisCorrection(text, 'recommendations', ['남길 작업'])
    assert.deepEqual(analysisCorrectionIssues(text), [])
    assert.deepEqual(analysisCorrectionIssues(updateAnalysisCorrection(text, 'recommendations', [])), [])
})

test('확인하지 않은 경로와 잘못된 목록 위치·문법은 덮어쓰지 않는다', () => {
    const text = JSON.stringify(result())
    for (const path of ['riskScore', 'databaseImpact', '__proto__', 'evidences']) assert.throws(() => updateAnalysisCorrection(text, path, '값'))
    for (const index of [-1, 3, 1.5]) assert.throws(() => updateAnalysisCorrectionItem(text, 'recommendations', index, '문장'))
    assert.throws(() => updateAnalysisCorrection(text, 'recommendations', [{ unexpected: true }]))
    assert.throws(() => updateAnalysisCorrection('{}', 'summary', '없는 항목'))
    for (const invalid of ['{', 'null', '[]', 'true']) {
        assert.throws(() => parseAnalysisCorrection(invalid))
        assert.ok(analysisCorrectionIssues(invalid).length)
    }
})

test('글자 수·한 줄 요약·필수 문장의 문제를 승인 전에 안내한다', () => {
    const original = result()
    original.plainLanguageSummary = '첫 줄\n둘째 줄'
    assert.match(analysisCorrectionIssues(JSON.stringify(original)).join(' '), /줄바꿈/)
    original.plainLanguageSummary = '가'.repeat(201)
    assert.match(analysisCorrectionIssues(JSON.stringify(original)).join(' '), /200자/)
    original.summary = ''
    original.impactAreas = ['']
    const issues = analysisCorrectionIssues(JSON.stringify(original)).join(' ')
    assert.match(issues, /분석 요약/)
    assert.match(issues, /영향 영역 1/)
})
