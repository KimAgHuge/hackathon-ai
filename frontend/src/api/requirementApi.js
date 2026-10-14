import { httpClient } from '@/api/httpClient'

/** 접근 가능한 모든 활성 프로젝트의 변경요청을 조회한다. */
export const getAllRequirements = async () => {
    const { data } = await httpClient.get('/requirements')
    return data
}

/**
 * @param {string} projectId 요구사항이 속한 프로젝트 ID
 * @returns {Promise<object[]>} 현재 사용자가 볼 수 있는 요구사항 목록
 */
export const getProjectRequirements = async (projectId) => {
    const { data } = await httpClient.get(`/projects/${projectId}/requirements`)
    return data
}

/**
 * @param {string} projectId 요구사항이 속한 프로젝트 ID
 * @param {object} payload 제목·원문·정리 내용·우선순위 등 첫 버전 입력
 * @returns {Promise<object>} 저장된 요구사항과 현재 버전
 */
export const createProjectRequirement = async (projectId, payload) => {
    const { data } = await httpClient.post(`/projects/${projectId}/requirements`, payload)
    return data
}
