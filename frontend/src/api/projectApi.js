import { httpClient } from '@/api/httpClient'
import { projectMock, analysisProgressMock, architectureMock, impactAnalysisMock } from '@/mocks/projectMock'
import { projectsMock } from '@/mocks/appMock'
import { createProjectCrud } from '@/api/projectCrud'
import { createProjectMockRepository } from '@/mocks/projectRepositoryMock'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const mockDelay = async (data, delay = 250) => {
    await new Promise((resolve) => setTimeout(resolve, delay))

    return structuredClone(data)
}

// 기본정보·삭제 표식을 공유해 목록과 상세, 수정 화면이 같은 데모 값을 사용한다.
const demoSeed = [...projectsMock, { ...projectMock, source: 'GIT' }].map((project) => ({
    id: project.id,
    organizationId: '00000000-0000-0000-0000-000000000100',
    name: project.name,
    description: '화면 동작을 확인하기 위한 데모 프로젝트입니다.',
    repositoryUrl: project.source === 'ZIP' ? null : 'https://github.com/example/doenaai-demo.git',
    defaultBranch: project.branch || 'main',
    sourceType: project.source === 'ZIP' ? 'ZIP' : 'GIT',
    sourceFileName: null,
    status: 'ACTIVE',
    createdBy: null,
    createdAt: '2026-10-14T00:00:00Z',
    updatedAt: '2026-10-14T00:00:00Z',
}))
const demoStorage = () => {
    try {
        return window.localStorage
    } catch {
        return null
    }
}
const crud = createProjectCrud({ client: httpClient, mockRepository: useMock ? createProjectMockRepository({ storage: demoStorage(), seed: demoSeed }) : null })
export const isProjectDemoMode = useMock
export const { getProjects, getProjectById, createProject, updateProject, deleteProject, restoreProject } = crud

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
