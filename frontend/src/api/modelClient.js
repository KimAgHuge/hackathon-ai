// 전송 구현을 주입해 화면 API와 네트워크 없는 검사를 같은 계약으로 실행한다.
export const createModelApi = (client) => ({
    async getModelRegistry() {
        return (await client.get('/models')).data
    },
    async synchronizeModelHistory() {
        return (await client.post('/models/history/sync', {}, { timeout: 60000 })).data
    },
    async getModelRuntime() {
        return (await client.get('/models/runtime')).data
    },
    async getModelVersion(id) {
        return (await client.get(`/models/versions/${encodeURIComponent(id)}`)).data
    },
    async registerModelVersion(body) {
        return (await client.post('/models/versions', body)).data
    },
    async recordModelEvaluation(id, body) {
        return (await client.post(`/models/versions/${encodeURIComponent(id)}/evaluations`, body)).data
    },
    async getInstalledModels() {
        return (await client.get('/models/installed')).data
    },
    async probeInstalledModel(body) {
        return (await client.post('/models/probe', body, { timeout: 140000 })).data
    },
    async activateInstalledModel(body) {
        return (await client.post('/models/activate', body, { timeout: 15000 })).data
    },
    async rollbackInstalledModel(body) {
        return (await client.post('/models/rollback', body, { timeout: 15000 })).data
    },
    async getModelTraining(model) {
        return (await client.get('/models/training', model ? { params: { model } } : undefined)).data
    },
    async startModelTraining(body) {
        return (await client.post('/models/training', body, { timeout: 15000 })).data
    },
    async cancelModelTraining(id, body) {
        return (await client.post(`/models/training/${encodeURIComponent(id)}/cancel`, body, { timeout: 15000 })).data
    },
    async prepareModelAdapter(id) {
        return (await client.post(`/models/adapters/${encodeURIComponent(id)}/prepare`, {}, { timeout: 30000 })).data
    },
    async getTrainedModelBuild(id) {
        return (await client.get(`/models/trained/${encodeURIComponent(id)}/build`)).data
    },
    async startTrainedModelBuild(id) {
        return (await client.post(`/models/trained/${encodeURIComponent(id)}/build`, {}, { timeout: 15000 })).data
    },
    async cancelTrainedModelBuild(id) {
        return (await client.post(`/models/trained/${encodeURIComponent(id)}/build/cancel`, {}, { timeout: 15000 })).data
    },
    async importTrainedModel(id) {
        return (await client.post(`/models/trained/${encodeURIComponent(id)}/import`, {}, { timeout: 20000 })).data
    },
    async getTrainedModelImport(id) {
        return (await client.get(`/models/trained/${encodeURIComponent(id)}/import`)).data
    },
    async getModelOperations({ page = 0, size = 10, query = '', status = '' } = {}) {
        return (await client.get('/models/operations', { params: { page, size, query, status }, timeout: 30000 })).data
    },
    async getModelOperation(id) {
        return (await client.get(`/models/operations/${encodeURIComponent(id)}`)).data
    },
})
