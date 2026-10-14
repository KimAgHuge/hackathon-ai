import { httpClient } from '@/api/httpClient'
import { buildAnalysisJobPayload } from '@/lib/requirementWorkflow'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'
export const isAnalysisDemoMode = useMock

const mockDelay = async (data, delay = 250) => {
    await new Promise((resolve) => setTimeout(resolve, delay))

    return structuredClone(data)
}

/**
 * @param {string} projectId 분석할 프로젝트 ID
 * @param {string|null} requirementVersionId 변경 영향 분석 대상 버전; null이면 전체 구조 분석
 * @returns {Promise<object>} 생성된 작업 ID와 초기 상태
 */
export const startProjectAnalysis = async (projectId, requirementVersionId = null, reuseFromJobId = null) => {
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

    const { data } = await httpClient.post(`/projects/${projectId}/analysis-jobs`, buildAnalysisJobPayload(requirementVersionId, reuseFromJobId))

    return data
}

/**
 * @param {string} analysisJobId 조회할 작업 ID
 * @param {object} options 요청 취소용 signal 등 HTTP 옵션
 * @returns {Promise<object>} 작업 상태와 단계별 진행률
 */
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

/** PENDING/RUNNING 작업의 서버 실행을 취소한다. 화면을 떠나는 동작과는 다르다. */
export const cancelAnalysisJob = async (analysisJobId) => {
    if (useMock) return mockDelay({ jobId: analysisJobId, status: 'CANCELLED' })
    const { data } = await httpClient.post(`/analysis-jobs/${analysisJobId}/cancel`)
    return data
}

/**
 * @param {string} analysisJobId 완료된 작업 ID
 * @param {object} options 요청 취소용 signal 등 HTTP 옵션
 * @returns {Promise<object>} AI 결과와 분석에 실제 사용된 근거
 */
export const getAnalysisResult = async (analysisJobId, options = {}) => {
    if (useMock) {
        return mockDelay({
            jobId: analysisJobId,
            projectId: 'project-demo-001',
            summary: '분석 결과 화면을 확인하기 위한 데모입니다. 실제 저장소 및 데이터베이스는 분석하지 않았습니다.',
            riskLevel: 'UNKNOWN',
            riskScore: null,
            result: {
                plainLanguageSummary: '이 화면은 분석 결과의 예시이며, 실제 프로젝트를 조사한 내용은 아닙니다.',
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
