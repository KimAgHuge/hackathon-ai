import { httpClient } from '@/api/httpClient'

/** @returns {Promise<object>} Backend가 확인한 현재 사용자 프로필·역할·권한 */
export const getCurrentUser = async () => {
    const { data } = await httpClient.get('/auth/me')
    return data
}
