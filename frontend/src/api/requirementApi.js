import { httpClient } from '@/api/httpClient'

export const getProjectRequirements = async (projectId) => {
    const { data } = await httpClient.get(`/projects/${projectId}/requirements`)
    return data
}

export const createProjectRequirement = async (projectId, payload) => {
    const { data } = await httpClient.post(`/projects/${projectId}/requirements`, payload)
    return data
}
