import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('저장 근거 목록은 프로젝트·페이지·전용 필터를 서버에 보내고 기존 이력 조회는 유지한다', async () => {
    const calls = []
    const response = { items: [], total: 0, page: 2, size: 20 }
    const key = '__savedEvidenceApiTest'
    globalThis[key] = { get: async (...args) => (calls.push(args), { data: response }) }
    try {
        const source = (await readFile(new URL('../src/api/appApi.js', import.meta.url), 'utf8'))
            .replace("import { httpClient } from '@/api/httpClient'", `const httpClient = globalThis.${key}`)
            .replace("import { getProjects } from '@/api/projectApi'", 'const getProjects = () => []')
            .replace("export { getProjects, deleteProject, restoreProject } from '@/api/projectApi'", '')
            .replace("import { getAllRequirements } from '@/api/requirementApi'", 'const getAllRequirements = () => []')
            .replace("import { buildDashboardSummary } from '@/lib/dashboardSummary'", 'const buildDashboardSummary = (value) => value')
            .replace(
                "import { analysisHistoryMock, changeRequestsMock, dashboardMock, documentsMock, modelsMock, projectsMock } from '@/mocks/appMock'",
                'const analysisHistoryMock = [], changeRequestsMock = [], dashboardMock = {}, documentsMock = [], modelsMock = [], projectsMock = []'
            )
            .replace('import.meta.env.VITE_USE_MOCK', "'false'")
        const moduleUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
        const { getAnalysisHistory } = await import(moduleUrl)
        const signal = new AbortController().signal
        assert.equal(await getAnalysisHistory({ projectId: 'project-test', page: 2, size: 20, reusableEvidenceOnly: true, signal }), response)
        await getAnalysisHistory({ projectId: 'project-test', page: 1, size: 10, reusableEvidenceOnly: false })
        await getAnalysisHistory()
        assert.deepEqual(calls, [
            ['/analysis-jobs', { signal, params: { projectId: 'project-test', page: 2, size: 20, reusableEvidenceOnly: true } }],
            ['/analysis-jobs', { params: { projectId: 'project-test', page: 1, size: 10 } }],
            ['/analysis-jobs', { params: { page: 0, size: 10 } }],
        ])
    } finally {
        delete globalThis[key]
    }
})
