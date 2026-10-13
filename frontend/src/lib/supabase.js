import { createClient } from '@supabase/supabase-js'

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL
const supabasePublishableKey = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY

const hasPlaceholder = (value) => {
    if (!value) return true

    const normalized = String(value).toLowerCase()

    return normalized.includes('your_') || normalized.includes('your-') || normalized.includes('change_me')
}

export const isSupabaseConfigured = !hasPlaceholder(supabaseUrl) && !hasPlaceholder(supabasePublishableKey)

// URL·publishable key가 유효할 때만 인증 클라이언트를 만든다; 비밀번호/서비스 키는 여기 넣지 않는다.
export const supabase = isSupabaseConfigured
    ? createClient(supabaseUrl, supabasePublishableKey, {
        auth: {
            persistSession: true,
            autoRefreshToken: true,
            detectSessionInUrl: true,
        },
    })
    : null
