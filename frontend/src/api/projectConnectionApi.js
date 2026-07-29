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
 */
export const testGitRepositoryConnection = async (projectId, payload) => {
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

    const { data } = await httpClient.post(`/projects/${projectId}/repository-connection/test`, payload)

    return data
}

/**
 * 검증이 끝난 Git 인증정보 저장
 *
 * PUT /api/projects/{projectId}/repository-connection
 */
export const saveGitRepositoryConnection = async (projectId, payload) => {
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

    const { data } = await httpClient.put(`/projects/${projectId}/repository-connection`, payload)

    return data
}

/**
 * 데이터베이스 연결 테스트
 *
 * POST /api/projects/{projectId}/database-connection/test
 */
export const testDatabaseConnection = async (projectId, payload) => {
    if (useMock) {
        return mockDelay({
            success: true,
            message: '데이터베이스 연결에 성공했습니다.',
            databaseProduct: payload.dbType || 'ORACLE',
            databaseVersion: 'Oracle Database Mock Version',
        })
    }

    const { data } = await httpClient.post(`/projects/${projectId}/database-connection/test`, payload)

    return data
}

/**
 * 프로젝트 DB 연결정보 저장
 *
 * PUT /api/projects/{projectId}/database-connection
 */
export const saveProjectConnection = async (projectId, payload) => {
    if (useMock) {
        return mockDelay({
            id: crypto.randomUUID(),
            projectId,
            connectionMode: payload.databaseSource?.connectionMode || 'DB_CONNECTION',
            dbType: payload.databaseSource?.dbType || 'ORACLE',
            host: payload.databaseSource?.host || null,
            port: payload.databaseSource?.port || null,
            databaseName: payload.databaseSource?.databaseName || null,
            schemaName: payload.databaseSource?.schemaName || null,
            username: payload.databaseSource?.username || null,
            status: 'CONNECTED',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
        })
    }

    const { data } = await httpClient.put(`/projects/${projectId}/database-connection`, payload.databaseSource || payload)

    return data
}
