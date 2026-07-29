import { httpClient } from '@/api/httpClient'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const mockDelay = async (data, delay = 250) => {
    await new Promise((resolve) => setTimeout(resolve, delay))

    return structuredClone(data)
}

export const startProjectAnalysis = async (projectId) => {
    if (useMock) {
        return mockDelay({
            jobId: crypto.randomUUID(),
            projectId,
            status: 'PENDING',
            sourceProgress: 0,
            databaseProgress: 0,
            relationProgress: 0,
            overallProgress: 0,
            createdAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.post(`/projects/${projectId}/analysis-jobs`)

    return data
}

export const getAnalysisJob = async (analysisJobId) => {
    if (useMock) {
        return mockDelay({
            jobId: analysisJobId,
            projectId: 'project-demo-001',
            status: 'RUNNING',
            sourceProgress: 72,
            databaseProgress: 100,
            relationProgress: 54,
            overallProgress: 75,
            createdAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.get(`/analysis-jobs/${analysisJobId}`)

    return data
}
