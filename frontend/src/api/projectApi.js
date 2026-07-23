import { httpClient } from '@/api/httpClient'
import {
    projectMock,
    analysisProgressMock,
    architectureMock,
    impactAnalysisMock,
} from '@/mocks/projectMock'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const mockDelay = async (data, delay = 250) => {
    await new Promise(resolve => setTimeout(resolve, delay))
    return structuredClone(data)
}

export const getProject = async () => {
    if (useMock) return mockDelay(projectMock)

    const { data } = await httpClient.get('/projects/demo')
    return data
}

export const startAnalysis = async payload => {
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

export const getAnalysisProgress = async () => {
    if (useMock) return mockDelay(analysisProgressMock)

    const { data } = await httpClient.get('/analysis-jobs/demo')
    return data
}

export const getArchitecture = async () => {
    if (useMock) return mockDelay(architectureMock)

    const { data } = await httpClient.get('/projects/demo/architecture')
    return data
}

export const getImpactAnalysis = async () => {
    if (useMock) return mockDelay(impactAnalysisMock)

    const { data } = await httpClient.get('/projects/demo/impact')
    return data
}
