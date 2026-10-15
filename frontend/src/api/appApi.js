import { httpClient } from '@/api/httpClient'
import { getProjects } from '@/api/projectApi'
export { getProjects, deleteProject, restoreProject } from '@/api/projectApi'
import { getAllRequirements } from '@/api/requirementApi'
import { buildDashboardSummary } from '@/lib/dashboardSummary'
import { analysisHistoryMock, changeRequestsMock, dashboardMock, documentsMock, modelsMock, projectsMock } from '@/mocks/appMock'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const wait = (milliseconds = 220) => new Promise((resolve) => setTimeout(resolve, milliseconds))
const clone = (value) => structuredClone(value)

const mockResponse = async (value) => {
    await wait()
    return clone(value)
}

export const getDashboard = async () => {
    if (useMock) return mockResponse(dashboardMock)

    const [projectResult, requirementResult, historyResult] = await Promise.allSettled([
        getProjects(),
        getAllRequirements(),
        getAnalysisHistory({ page: 0, size: 5 }),
    ])
    const results = { projects: projectResult, requirements: requirementResult, analyses: historyResult }
    const failures = Object.entries(results)
        .filter(([, result]) => result.status === 'rejected')
        .map(([source]) => source)

    if (failures.length === 3) throw new Error('대시보드 데이터를 불러오지 못했습니다. 서버 연결과 로그인 상태를 확인해 주세요.')

    return buildDashboardSummary({
        projects: projectResult.status === 'fulfilled' ? projectResult.value : null,
        requirements: requirementResult.status === 'fulfilled' ? requirementResult.value : null,
        history: historyResult.status === 'fulfilled' ? historyResult.value : null,
        failures,
    })
}

export const getChangeRequests = async () => {
    if (useMock) return mockResponse(changeRequestsMock)

    const { data } = await httpClient.get('/change-requests')
    return data
}

export const getAnalysisHistory = async ({ page = 0, size = 10, projectId = null, ...options } = {}) => {
    if (useMock) {
        const rows = analysisHistoryMock.map((item) => ({
            jobId: item.id,
            projectId: projectsMock.find((project) => project.name === item.project)?.id || 'project-demo-001',
            projectName: item.project,
            requirementTitle: '데모 분석',
            status: 'COMPLETED',
            riskLevel: item.risk,
            summary: '화면 확인용 샘플입니다.',
            approved: item.result === 'APPROVED',
            createdAt: item.analyzedAt,
            completedAt: item.analyzedAt,
        }))
        const filtered = projectId ? rows.filter((item) => item.projectId === projectId) : rows
        return mockResponse({ items: filtered.slice(page * size, (page + 1) * size), total: filtered.length, page, size })
    }

    const { data } = await httpClient.get('/analysis-jobs', { ...options, params: { page, size, ...(projectId ? { projectId } : {}) } })
    return data
}

export const getModels = async () => {
    if (useMock) return mockResponse(modelsMock)

    const { data } = await httpClient.get('/models')
    return data
}

export const getDocuments = async () => {
    if (useMock) return mockResponse(documentsMock)

    const { data } = await httpClient.get('/documents')
    return data
}
