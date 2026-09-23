import { httpClient } from '@/api/httpClient'

const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const mockOrganizations = [
    {
        id: '00000000-0000-0000-0000-000000000100',
        name: 'doenaai',
        slug: 'doenaai',
        description: 'doenaai 기본 조직',
        status: 'ACTIVE',
        memberRole: 'OWNER',
    },
]

const mockResponse = async (value) => {
    await new Promise((resolve) => setTimeout(resolve, 200))
    return structuredClone(value)
}

export const getOrganizations = async () => {
    if (useMock) return mockResponse(mockOrganizations)

    const { data } = await httpClient.get('/organizations')
    return data
}

export const createOrganization = async (payload) => {
    if (useMock) {
        return mockResponse({
            id: crypto.randomUUID(),
            ...payload,
            status: 'ACTIVE',
            memberRole: 'OWNER',
        })
    }

    const { data } = await httpClient.post('/organizations', payload)
    return data
}
