import { httpClient } from '@/api/httpClient'
import { buildAnalysisJobPayload } from '@/lib/requirementWorkflow'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'
export const isAnalysisDemoMode = useMock

const mockDelay = async (data, delay = 250) => {
    await new Promise((resolve) => setTimeout(resolve, delay))

    return structuredClone(data)
}

export const startProjectAnalysis = async (projectId, requirementVersionId = null) => {
    if (useMock) {
        return mockDelay({
            jobId: crypto.randomUUID(),
            projectId,
            requirementVersionId,
            status: 'PENDING',
            sourceProgress: 0,
            databaseProgress: 0,
            relationProgress: 0,
            overallProgress: 0,
            createdAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.post(`/projects/${projectId}/analysis-jobs`, buildAnalysisJobPayload(requirementVersionId))

    return data
}

export const getAnalysisJob = async (analysisJobId, options = {}) => {
    if (useMock) {
        return mockDelay({
            jobId: analysisJobId,
            projectId: 'project-demo-001',
            status: 'COMPLETED',
            sourceProgress: 100,
            databaseProgress: 100,
            relationProgress: 100,
            overallProgress: 100,
            errorMessage: null,
            createdAt: new Date().toISOString(),
            startedAt: new Date().toISOString(),
            completedAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.get(`/analysis-jobs/${analysisJobId}`, options)

    return data
}

export const getAnalysisResult = async (analysisJobId, options = {}) => {
    if (useMock) {
        return mockDelay({
            jobId: analysisJobId,
            projectId: 'project-demo-001',
            summary: '분석 결과 화면을 확인하기 위한 데모입니다. 실제 저장소 및 데이터베이스는 분석하지 않았습니다.',
            riskLevel: 'UNKNOWN',
            riskScore: null,
            result: {
                frameworks: [],
                impactAreas: [],
                evidences: [],
                recommendations: ['실제 분석을 실행하려면 API 연결 모드로 전환한 뒤 프로젝트 연결 및 분석을 진행하세요.'],
            },
            facts: {
                sourceSummary: { components: [], languages: [], frameworks: [], detectionWarnings: ['데모에는 수집된 소스코드가 없습니다.'] },
                databaseSummary: { tables: [], warnings: ['데모에는 수집된 DB 스키마가 없습니다.'] },
                relations: [],
            },
            createdAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.get(`/analysis-jobs/${analysisJobId}/result`, options)

    return data
}
