import { httpClient } from '@/api/httpClient'
import { projectMock, analysisProgressMock, architectureMock, impactAnalysisMock } from '@/mocks/projectMock'
import { projectsMock } from '@/mocks/appMock'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const mockDelay = async (data, delay = 250) => {
    await new Promise((resolve) => setTimeout(resolve, delay))

    return structuredClone(data)
}

/*
 * 프로젝트 CRUD
 */

/**
 * 프로젝트 목록 조회
 *
 * GET /api/projects
 * @returns {Promise<object[]>} 접근 가능한 프로젝트 목록
 */
export const getProjects = async () => {
    if (useMock) {
        return mockDelay([
            {
                id: projectMock.id || 'project-demo-001',
                organizationId: projectMock.organizationId || '00000000-0000-0000-0000-000000000100',
                name: projectMock.name || 'doenaai-demo',
                description: projectMock.description || 'AI 기반 소프트웨어 변경 영향도 분석 프로젝트',
                repositoryUrl: projectMock.repositoryUrl || 'https://github.com/example/doenaai-demo.git',
                defaultBranch: projectMock.defaultBranch || 'main',
                sourceType: projectMock.sourceType || 'GIT',
                status: projectMock.status || 'ACTIVE',
                createdBy: null,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
            },
        ])
    }

    const { data } = await httpClient.get('/projects')

    return data
}

/**
 * 실제 프로젝트 상세 조회
 *
 * 기존 getProject()와 이름이 겹치지 않도록
 * getProjectById()로 별도 정의한다.
 *
 * GET /api/projects/{projectId}
 * @param {string} projectId 조회할 프로젝트 ID
 * @returns {Promise<object>} 프로젝트 기본정보
 */
export const getProjectById = async (projectId) => {
    if (useMock) {
        const listedProject = projectsMock.find((project) => project.id === projectId)
        if (!listedProject && projectId !== projectMock.id) {
            throw new Error('프로젝트를 찾을 수 없습니다.')
        }
        return mockDelay({
            id: projectId,
            organizationId: projectMock.organizationId || '00000000-0000-0000-0000-000000000100',
            name: listedProject?.name || projectMock.name || 'doenaai-demo',
            description: listedProject ? null : projectMock.description || 'AI 기반 소프트웨어 변경 영향도 분석 프로젝트',
            repositoryUrl: listedProject?.source === 'ZIP' ? null : projectMock.repositoryUrl || 'https://github.com/example/doenaai-demo.git',
            defaultBranch: listedProject?.branch || projectMock.defaultBranch || 'main',
            sourceType: listedProject?.source === 'ZIP' ? 'ZIP' : 'GIT',
            sourceFileName: null,
            status: 'ACTIVE',
            createdBy: null,
            createdAt: listedProject?.updatedAt || new Date().toISOString(),
            updatedAt: listedProject?.updatedAt || new Date().toISOString(),
        })
    }

    const { data } = await httpClient.get(`/projects/${projectId}`)

    return data
}

/**
 * 프로젝트 생성
 *
 * POST /api/projects
 * @param {object} payload 조직 ID·이름·Git 정보 등 생성값
 * @returns {Promise<object>} 생성된 프로젝트
 */
export const createProject = async (payload) => {
    if (useMock) {
        return mockDelay({
            id: crypto.randomUUID(),
            organizationId: payload.organizationId,
            name: payload.name,
            description: payload.description || null,
            repositoryUrl: payload.repositoryUrl || null,
            defaultBranch: payload.defaultBranch || 'main',
            sourceType: payload.sourceType || 'GIT',
            status: 'ACTIVE',
            createdBy: null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.post('/projects', payload)

    return data
}

/**
 * 프로젝트 수정
 *
 * PUT /api/projects/{projectId}
 * @returns {Promise<object>} 수정된 프로젝트
 */
export const updateProject = async (projectId, payload) => {
    if (useMock) {
        return mockDelay({
            id: projectId,
            organizationId: payload.organizationId,
            name: payload.name,
            description: payload.description || null,
            repositoryUrl: payload.repositoryUrl || null,
            defaultBranch: payload.defaultBranch || 'main',
            sourceType: payload.sourceType || 'GIT',
            status: payload.status || 'ACTIVE',
            createdBy: null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.put(`/projects/${projectId}`, payload)

    return data
}

/*
 * 기존 프로젝트 분석 Wizard API
 *
 * 현재 기존 화면에서 사용할 수 있으므로 유지한다.
 */

/**
 * 기존 데모 프로젝트 조회
 *
 * 현재 /projects/demo 백엔드 API가 없다면
 * 실 API 전환 전까지 Mock에서만 사용한다.
 */
export const getProject = async () => {
    if (useMock) {
        return mockDelay(projectMock)
    }

    const { data } = await httpClient.get('/projects/demo')

    return data
}

/**
 * 분석 시작
 */
export const startAnalysis = async (payload) => {
    if (useMock) {
        return mockDelay({
            jobId: 'job-demo-001',
            status: 'PROCESSING',
            payload,
        })
    }

    const { data } = await httpClient.post('/projects/demo/analysis', payload)

    return data
}

/**
 * 분석 진행률 조회
 */
export const getAnalysisProgress = async () => {
    if (useMock) {
        return mockDelay(analysisProgressMock)
    }

    const { data } = await httpClient.get('/analysis-jobs/demo')

    return data
}

/**
 * 아키텍처 분석 결과 조회
 */
export const getArchitecture = async () => {
    if (useMock) {
        return mockDelay(architectureMock)
    }

    const { data } = await httpClient.get('/projects/demo/architecture')

    return data
}

/**
 * 영향도 분석 결과 조회
 */
export const getImpactAnalysis = async () => {
    if (useMock) {
        return mockDelay(impactAnalysisMock)
    }

    const { data } = await httpClient.get('/projects/demo/impact')

    return data
}
