import { httpClient } from '@/api/httpClient'

export const getCurrentUser = async () => {
    const { data } = await httpClient.get('/auth/me')
    return data
}
