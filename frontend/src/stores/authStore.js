import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getCurrentUser } from '@/api/authApi'
import { isSupabaseConfigured, supabase } from '@/lib/supabase'

const AUTH_MODE = import.meta.env.VITE_AUTH_MODE || 'mock'
const PROFILE_SOURCE = import.meta.env.VITE_AUTH_PROFILE_SOURCE || 'mock'
const MOCK_SESSION_KEY = 'doenaai.mock.session'

const mockPermissions = [
    'DASHBOARD_READ',
    'ORGANIZATION_READ',
    'ORGANIZATION_MANAGE',
    'PROJECT_READ',
    'PROJECT_CREATE',
    'PROJECT_UPDATE',
    'CHANGE_REQUEST_READ',
    'CHANGE_REQUEST_CREATE',
    'CHANGE_REQUEST_UPDATE',
    'ANALYSIS_READ',
    'ANALYSIS_EXECUTE',
    'ANALYSIS_APPROVE',
    'DOCUMENT_READ',
    'DOCUMENT_UPLOAD',
    'MODEL_READ',
    'MODEL_TRAIN',
    'MODEL_DEPLOY',
    'MODEL_ROLLBACK',
    'SYSTEM_SETTING_READ',
    'SYSTEM_SETTING_WRITE',
    'USER_MANAGE',
]

const createMockSession = (email) => ({
    access_token: 'mock-access-token',
    refresh_token: 'mock-refresh-token',
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    user: {
        id: '00000000-0000-0000-0000-000000000001',
        email,
        user_metadata: {
            display_name: '관리자',
        },
    },
})

const buildMockProfile = (user) => ({
    id: user?.id || '00000000-0000-0000-0000-000000000001',
    email: user?.email || import.meta.env.VITE_MOCK_LOGIN_EMAIL || 'admin@doenaai.local',
    displayName: user?.user_metadata?.display_name || user?.user_metadata?.name || '관리자',
    department: 'AI 플랫폼팀',
    jobTitle: 'Administrator',
    status: 'ACTIVE',
    roles: ['ADMIN'],
    permissions: mockPermissions,
})

export const useAuthStore = defineStore('auth', () => {
    const session = ref(null)
    const currentUser = ref(null)
    const initialized = ref(false)
    const loading = ref(false)
    const errorMessage = ref('')

    let authSubscription = null
    let initializePromise = null

    const isMockAuth = computed(() => AUTH_MODE === 'mock')
    const isAuthenticated = computed(() => Boolean(session.value))
    const roles = computed(() => currentUser.value?.roles || [])
    const permissions = computed(() => currentUser.value?.permissions || [])

    const hasRole = (role) => roles.value.includes(role)
    const hasPermission = (permission) => {
        if (!permission) return true
        return permissions.value.includes(permission)
    }

    const loadCurrentUser = async () => {
        if (!session.value) {
            currentUser.value = null
            return null
        }

        if (PROFILE_SOURCE === 'backend' && !isMockAuth.value) {
            currentUser.value = await getCurrentUser()
            return currentUser.value
        }

        currentUser.value = buildMockProfile(session.value.user)
        return currentUser.value
    }

    const initializeMockSession = async () => {
        const storedSession = window.localStorage.getItem(MOCK_SESSION_KEY)

        if (!storedSession) return

        try {
            session.value = JSON.parse(storedSession)
            await loadCurrentUser()
        } catch {
            window.localStorage.removeItem(MOCK_SESSION_KEY)
            session.value = null
            currentUser.value = null
        }
    }

    const initializeSupabaseSession = async () => {
        if (!supabase || !isSupabaseConfigured) return

        const { data, error } = await supabase.auth.getSession()

        if (error) throw error

        session.value = data.session

        if (session.value) {
            await loadCurrentUser()
        }

        const { data: listener } = supabase.auth.onAuthStateChange((event, nextSession) => {
            session.value = nextSession

            if (!nextSession) {
                currentUser.value = null
                return
            }

            window.setTimeout(() => {
                loadCurrentUser().catch(() => {
                    currentUser.value = null
                })
            }, 0)
        })

        authSubscription = listener.subscription
    }

    const initialize = async () => {
        if (initialized.value) return
        if (initializePromise) return initializePromise

        initializePromise = (async () => {
            try {
                if (isMockAuth.value) {
                    await initializeMockSession()
                } else {
                    await initializeSupabaseSession()
                }
            } finally {
                initialized.value = true
                initializePromise = null
            }
        })()

        return initializePromise
    }

    const loginWithMock = async (email, password) => {
        const expectedEmail = import.meta.env.VITE_MOCK_LOGIN_EMAIL || 'admin@doenaai.local'
        const expectedPassword = import.meta.env.VITE_MOCK_LOGIN_PASSWORD || 'admin1234'

        if (email !== expectedEmail || password !== expectedPassword) {
            throw new Error('이메일 또는 비밀번호가 올바르지 않습니다.')
        }

        session.value = createMockSession(email)
        window.localStorage.setItem(MOCK_SESSION_KEY, JSON.stringify(session.value))
        await loadCurrentUser()
    }

    const loginWithSupabase = async (email, password) => {
        if (!supabase || !isSupabaseConfigured) {
            throw new Error('Supabase 환경변수가 설정되지 않았습니다.')
        }

        const { data, error } = await supabase.auth.signInWithPassword({
            email,
            password,
        })

        if (error) throw error

        session.value = data.session
        await loadCurrentUser()
    }

    const login = async (email, password) => {
        loading.value = true
        errorMessage.value = ''

        try {
            if (isMockAuth.value) {
                await loginWithMock(email, password)
            } else {
                await loginWithSupabase(email, password)
            }

            return currentUser.value
        } catch (error) {
            errorMessage.value = error instanceof Error ? error.message : '로그인에 실패했습니다.'

            throw error
        } finally {
            loading.value = false
        }
    }

    const logout = async () => {
        loading.value = true

        try {
            if (isMockAuth.value) {
                window.localStorage.removeItem(MOCK_SESSION_KEY)
            } else if (supabase) {
                const { error } = await supabase.auth.signOut({
                    scope: 'local',
                })

                if (error) throw error
            }

            session.value = null
            currentUser.value = null
        } finally {
            loading.value = false
        }
    }

    const dispose = () => {
        authSubscription?.unsubscribe()
        authSubscription = null
    }

    return {
        session,
        currentUser,
        initialized,
        loading,
        errorMessage,
        isMockAuth,
        isAuthenticated,
        roles,
        permissions,
        initialize,
        login,
        logout,
        loadCurrentUser,
        hasRole,
        hasPermission,
        dispose,
    }
})
