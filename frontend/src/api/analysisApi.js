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
        const finishedAt = Date.now()
        return mockDelay({
            jobId: analysisJobId,
            projectId: 'project-demo-001',
            status: 'COMPLETED',
            sourceProgress: 100,
            databaseProgress: 100,
            relationProgress: 100,
            overallProgress: 100,
            activityLog: [
                { label: 'Git 소스코드 수집', state: 'COMPLETED', at: new Date(finishedAt - 50000).toISOString() },
                { label: '데이터베이스 구조 수집', state: 'COMPLETED', at: new Date(finishedAt - 30000).toISOString() },
                { label: 'AI 일반 영향 분석', state: 'STARTED', at: new Date(finishedAt - 25000).toISOString() },
                { label: 'AI 일반 영향 분석', state: 'COMPLETED', at: new Date(finishedAt - 15000).toISOString() },
                { label: '추가 DB 분석 대상 아님', state: 'COMPLETED', at: new Date(finishedAt - 12000).toISOString() },
                { label: 'AI 모델의 분석 응답 대기', state: 'COMPLETED', at: new Date(finishedAt - 10000).toISOString() },
                { label: '분석 결과 저장', state: 'COMPLETED', at: new Date(finishedAt).toISOString() },
            ],
            errorMessage: null,
            createdAt: new Date(finishedAt - 60000).toISOString(),
            startedAt: new Date(finishedAt - 60000).toISOString(),
            completedAt: new Date(finishedAt).toISOString(),
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
            reviewStatus: 'PENDING',
            correctedResult: null,
            reviewNote: null,
            reviewedBy: null,
            reviewedAt: null,
        })
    }

    const { data } = await httpClient.get(`/analysis-jobs/${analysisJobId}/result`, options)

    return data
}

/** 권한 있는 검토자가 완료된 분석을 한 번만 승인하거나 반려한다. */
export const reviewAnalysisResult = async (analysisJobId, payload) => {
    if (useMock) throw new Error('데모 분석은 승인하거나 반려할 수 없습니다.')
    const { data } = await httpClient.put(`/analysis-jobs/${analysisJobId}/review`, payload)
    return data
}
