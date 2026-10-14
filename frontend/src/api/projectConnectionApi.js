import { httpClient } from '@/api/httpClient'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const mockDelay = async (data, delay = 250) => {
    await new Promise((resolve) => setTimeout(resolve, delay))

    return structuredClone(data)
}

/**
 * Git 저장소와 브랜치 연결 테스트
 *
 * POST /api/projects/{projectId}/repository-connection/test
 * @param {object} payload Git URL·브랜치·인증 방식/자격증명
 * @returns {Promise<object>} 연결 성공 여부와 확인된 커밋 SHA
 */
export const testGitRepositoryConnection = async (projectId, payload, options = {}) => {
    if (useMock) {
        return mockDelay({
            success: true,
            message: 'Git 저장소와 브랜치 연결에 성공했습니다.',
            repositoryUrl: payload.repositoryUrl,
            branch: payload.branch,
            commitSha: '0123456789abcdef0123456789abcdef01234567',
            providerType: payload.providerType,
            authType: payload.authType,
        })
    }

    const { data } = await httpClient.post(`/projects/${projectId}/repository-connection/test`, payload, options)

    return data
}

/**
 * 검증이 끝난 Git 인증정보 저장
 *
 * PUT /api/projects/{projectId}/repository-connection
 * @param {string} projectId 설정을 저장할 프로젝트 ID
 * @param {object} payload 검증할 저장소·인증정보
 * @returns {Promise<object>} 자격증명 원문을 제외한 저장된 Git 연결 상태
 */
export const saveGitRepositoryConnection = async (projectId, payload, options = {}) => {
    if (useMock) {
        return mockDelay({
            id: crypto.randomUUID(),
            projectId,
            providerType: payload.providerType,
            authType: payload.authType,
            username: payload.username || null,
            status: 'CONNECTED',
            verifiedCommitSha: '0123456789abcdef0123456789abcdef01234567',
            lastTestedAt: new Date().toISOString(),
            lastTestResult: 'SUCCESS',
            lastErrorMessage: null,
            credentialsConfigured: payload.authType !== 'NONE',
            knownHostsConfigured: payload.authType === 'SSH_KEY',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.put(`/projects/${projectId}/repository-connection`, payload, options)

    return data
}

/** 저장된 Git 연결 상태를 조회한다. 연결이 없으면 null을 반환하며 인증 비밀값은 받지 않는다. */
export const getGitRepositoryConnection = async (projectId, options = {}) => {
    if (useMock) return mockDelay(null)

    try {
        const { data } = await httpClient.get(`/projects/${projectId}/repository-connection`, options)
        return data
    } catch (error) {
        if (error.response?.status === 404) return null
        throw error
    }
}

/**
 * 데이터베이스 연결 테스트
 *
 * POST /api/projects/{projectId}/database-connection/test
 * @param {object} payload 읽기 전용 DB 호스트·스키마·계정 정보
 * @returns {Promise<object>} 연결 성공 여부와 DB 제품 정보
 */
export const testDatabaseConnection = async (projectId, payload, options = {}) => {
    if (useMock) {
        return mockDelay({
            success: true,
            message: '데이터베이스 연결에 성공했습니다.',
            databaseProduct: payload.dbType || 'ORACLE',
            databaseVersion: 'Oracle Database Mock Version',
        })
    }

    const { data } = await httpClient.post(`/projects/${projectId}/database-connection/test`, payload, options)

    return data
}

/**
 * 프로젝트 DB 연결정보 저장
 *
 * PUT /api/projects/{projectId}/database-connection
 * @param {string} projectId 설정을 저장할 프로젝트 ID
 * @param {object} payload 읽기 전용 DB 연결정보
 * @returns {Promise<object>} 비밀번호를 제외한 저장된 DB 연결 상태
 */
export const saveProjectConnection = async (projectId, payload, options = {}) => {
    if (useMock) {
        return mockDelay({
            id: crypto.randomUUID(),
            projectId,
            connectionMode: (payload.databaseSource || payload).connectionMode || 'DB_CONNECTION',
            dbType: (payload.databaseSource || payload).dbType || 'ORACLE',
            host: (payload.databaseSource || payload).host || null,
            port: (payload.databaseSource || payload).port || null,
            databaseName: (payload.databaseSource || payload).databaseName || null,
            schemaName: (payload.databaseSource || payload).schemaName || null,
            username: (payload.databaseSource || payload).username || null,
            status: 'CONNECTED',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.put(`/projects/${projectId}/database-connection`, payload.databaseSource || payload, options)

    return data
}

/** 저장된 읽기 전용 DB 연결의 공개 메타데이터를 조회한다. 미설정이면 null이다. */
export const getDatabaseConnection = async (projectId, options = {}) => {
    if (useMock) return mockDelay(null)

    const { data } = await httpClient.get(`/projects/${projectId}/database-connection`, options)
    return data || null
}
