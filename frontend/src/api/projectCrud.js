/** 실제 서버와 데모 저장소의 프로젝트 목록·상세·생성·수정을 동일한 호출 방식으로 제공한다. */
export const createProjectCrud = ({ client, mockRepository = null }) => ({
    getProjects: async ({ deleted = false, ...options } = {}) =>
        mockRepository ? mockRepository.list({ deleted }) : (await client.get('/projects', { ...options, params: deleted ? { deleted: true } : {} })).data,
    getProjectById: async (id, options = {}) =>
        mockRepository ? mockRepository.get(id) : (await client.get(`/projects/${encodeURIComponent(id)}`, options)).data,
    createProject: async (payload) => (mockRepository ? mockRepository.create(payload) : (await client.post('/projects', payload)).data),
    updateProject: async (id, payload, options = {}) =>
        mockRepository ? mockRepository.update(id, payload) : (await client.put(`/projects/${encodeURIComponent(id)}`, payload, options)).data,
    deleteProject: async (id) => {
        if (mockRepository) return mockRepository.remove(id)
        await client.delete(`/projects/${encodeURIComponent(id)}`)
    },
    restoreProject: async (id) => {
        if (mockRepository) return mockRepository.restore(id)
        await client.post(`/projects/${encodeURIComponent(id)}/restore`)
    },
})
