import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { analysisHistoryMock, changeRequestsMock, dashboardMock, documentsMock, projectsMock } from '../src/mocks/appMock.js'
import { architectureMock, impactAnalysisMock, projectMock } from '../src/mocks/projectMock.js'

test('기본 프로젝트와 관련 목록은 가상 이름을 사용하고 같은 프로젝트를 참조한다', () => {
    const names = new Set(projectsMock.map((project) => project.name))
    assert.equal(names.size, 4)
    for (const name of names) assert.match(name, /^project_example_\d{2}$/)
    assert.equal(projectMock.name, 'project_example')
    for (const row of [...dashboardMock.recentAnalyses, ...changeRequestsMock, ...analysisHistoryMock, ...documentsMock]) {
        assert.ok(names.has(row.project), '다른 프로젝트나 연결되지 않은 이름을 예시 목록에 넣지 않는다.')
    }
})

test('문서·요청자·검토자는 실제 업무나 사람을 가리키지 않는 가상 식별자를 사용한다', () => {
    for (const row of documentsMock) assert.match(row.name, /^document_example_\d{2}\.(pdf|xlsx|md)$/)
    for (const row of changeRequestsMock) assert.match(row.requester, /^team_example_\d{2}$/)
    for (const row of analysisHistoryMock) assert.match(row.reviewer, /^(reviewer_example_\d{2}|-)$/)
})

test('아키텍처와 영향 경로의 파일·API·테이블은 하나의 중립적인 가상 사례로 맞춘다', () => {
    assert.deepEqual(
        architectureMock.nodes.map((node) => node.name),
        ['ExampleView.vue', 'POST /api/examples', 'ExampleService', 'ExampleRepository', 'table_example']
    )
    assert.deepEqual(
        impactAnalysisMock.evidences.map((evidence) => evidence.path),
        ['frontend/src/views/ExampleView.vue', 'backend/src/main/java/example/ExampleService.java', 'database:schema_example.table_example']
    )
    assert.ok(impactAnalysisMock.evidences.every((evidence) => evidence.description.startsWith('가상 사례:')))
})

test('가상 아키텍처·영향도 화면은 실제 결과와 구분하고 측정하지 않은 관계 신뢰도를 표시하지 않는다', async () => {
    const architecture = await readFile(new URL('../src/views/ArchitectureView.vue', import.meta.url), 'utf8')
    const impact = await readFile(new URL('../src/views/ImpactAnalysisView.vue', import.meta.url), 'utf8')
    assert.match(architecture, /실제 분석 결과가 아닙니다/)
    assert.match(impact, /실제 분석이나 견적이 아닙니다/)
    for (const node of architectureMock.nodes) assert.ok(architecture.includes(node.name))
    assert.doesNotMatch(architecture, /신뢰도\s*\d+\s*%/)
    assert.match(impact, /table_example/)
    assert.match(impact, /임의 값/)
})
