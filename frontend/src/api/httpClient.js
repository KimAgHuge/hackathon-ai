import axios from 'axios'
import { supabase } from '@/lib/supabase'

// 모든 Backend API 호출의 주소·시간 제한을 공유한다.
export const httpClient = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
    timeout: Number(import.meta.env.VITE_API_TIMEOUT || 15000),
    headers: {
        'Content-Type': 'application/json',
    },
})

// 로그인 세션의 access token을 Authorization 헤더에 넣어 Backend로 보낸다.
httpClient.interceptors.request.use(async (config) => {
    if (!supabase) return config

    const { data, error } = await supabase.auth.getSession()

    if (error) throw error

    const accessToken = data.session?.access_token

    if (accessToken) {
        config.headers.Authorization = `Bearer ${accessToken}`
    }

    return config
})

// 인증 만료(401)일 때 로컬 세션을 정리하고 원래 오류는 호출자에게 돌려준다.
httpClient.interceptors.response.use(
    (response) => response,
    async (error) => {
        if (error.response?.status === 401 && supabase) {
            await supabase.auth.signOut({
                scope: 'local',
            })
        }

        return Promise.reject(error)
    }
)
