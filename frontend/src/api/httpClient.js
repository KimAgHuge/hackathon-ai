import axios from 'axios'
import { supabase } from '@/lib/supabase'

export const httpClient = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
    timeout: Number(import.meta.env.VITE_API_TIMEOUT || 15000),
    headers: {
        'Content-Type': 'application/json',
    },
})

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
