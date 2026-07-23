import { httpClient } from '@/api/httpClient'
import { settingsMock } from '@/mocks/settingsMock'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))
const clone = (value) => structuredClone(value)

export const getSystemSettings = async () => {
    if (useMock) {
        await wait(250)
        return clone(settingsMock)
    }

    const { data } = await httpClient.get('/settings')
    return data
}

const mockTest = async (section) => {
    await wait(650)

    if (section === 'training-server') {
        return {
            success: false,
            status: 'OFFLINE',
            message: '학습 서버가 응답하지 않습니다. AI 분석 기능에는 영향이 없습니다.',
            checkedAt: new Date().toLocaleString('ko-KR'),
            latencyMs: null,
        }
    }

    return {
        success: true,
        status: 'ONLINE',
        message: section === 'supabase' ? 'Supabase 연결에 성공했습니다.' : 'AI 추론 서버 연결에 성공했습니다.',
        checkedAt: new Date().toLocaleString('ko-KR'),
        latencyMs: section === 'supabase' ? 64 : 39,
    }
}

export const testAiServerConnection = async (payload) => {
    if (useMock) return mockTest('ai-server')

    const { data } = await httpClient.post('/settings/ai-server/test', payload)
    return data
}

export const saveAiServerSettings = async (payload) => {
    if (useMock) {
        await wait(450)
        return {
            ...clone(payload),
            internalApiKey: '',
            apiKeyConfigured: payload.apiKeyConfigured || Boolean(payload.internalApiKey),
        }
    }

    const { data } = await httpClient.put('/settings/ai-server', payload)
    return data
}

export const testTrainingServerConnection = async (payload) => {
    if (useMock) return mockTest('training-server')

    const { data } = await httpClient.post('/settings/training-server/test', payload)
    return data
}

export const saveTrainingServerSettings = async (payload) => {
    if (useMock) {
        await wait(450)
        return clone(payload)
    }

    const { data } = await httpClient.put('/settings/training-server', payload)
    return data
}

export const testSupabaseConnection = async (payload) => {
    if (useMock) return mockTest('supabase')

    const { data } = await httpClient.post('/settings/supabase/test', payload)
    return data
}

export const saveSupabaseSettings = async (payload) => {
    if (useMock) {
        await wait(450)
        return {
            ...clone(payload),
            serviceRoleKey: '',
            serviceRoleConfigured: payload.serviceRoleConfigured || Boolean(payload.serviceRoleKey),
        }
    }

    const { data } = await httpClient.put('/settings/supabase', payload)
    return data
}
