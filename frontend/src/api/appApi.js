import { httpClient } from '@/api/httpClient'
import {
    analysisHistoryMock,
    changeRequestsMock,
    dashboardMock,
    documentsMock,
    modelsMock,
    projectsMock,
} from '@/mocks/appMock'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const wait = (milliseconds = 220) => new Promise((resolve) => setTimeout(resolve, milliseconds))
const clone = (value) => structuredClone(value)

const mockResponse = async (value) => {
    await wait()
    return clone(value)
}

export const getDashboard = async () => {
    if (useMock) return mockResponse(dashboardMock)

    const { data } = await httpClient.get('/dashboard')
    return data
}

export const getProjects = async () => {
    if (useMock) return mockResponse(projectsMock)

    const { data } = await httpClient.get('/projects')
    return data
}

export const getChangeRequests = async () => {
    if (useMock) return mockResponse(changeRequestsMock)

    const { data } = await httpClient.get('/change-requests')
    return data
}

export const getAnalysisHistory = async () => {
    if (useMock) return mockResponse(analysisHistoryMock)

    const { data } = await httpClient.get('/analyses')
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
