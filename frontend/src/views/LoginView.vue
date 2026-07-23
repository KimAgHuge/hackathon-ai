<script setup>
import { computed, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import { useAuthStore } from '@/stores/authStore'
import { useThemeStore } from '@/stores/themeStore'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const themeStore = useThemeStore()

const form = reactive({
    email: import.meta.env.VITE_AUTH_MODE === 'mock'
        ? import.meta.env.VITE_MOCK_LOGIN_EMAIL || 'admin@doenaai.local'
        : '',
    password: import.meta.env.VITE_AUTH_MODE === 'mock'
        ? import.meta.env.VITE_MOCK_LOGIN_PASSWORD || 'admin1234'
        : '',
})

const authModeLabel = computed(() => {
    return authStore.isMockAuth ? '로컬 Mock 인증' : 'Supabase Auth'
})

const handleLogin = async () => {
    try {
        await authStore.login(form.email.trim(), form.password)

        const redirect = typeof route.query.redirect === 'string'
            ? route.query.redirect
            : '/dashboard'

        await router.replace(redirect)
    } catch {
        // 오류 메시지는 authStore에서 관리한다
    }
}
</script>

<template>
    <div class="relative grid min-h-screen place-items-center overflow-hidden px-6 py-12">
        <div class="absolute inset-0 bg-[radial-gradient(circle_at_10%_10%,rgba(37,99,235,0.16),transparent_32%),radial-gradient(circle_at_90%_90%,rgba(14,165,233,0.12),transparent_30%)]"></div>

        <button
            type="button"
            class="absolute right-6 top-6 grid h-10 w-10 place-items-center rounded-full border border-slate-200 bg-white text-slate-600 shadow-sm dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300"
            :title="themeStore.isDark ? '라이트 모드' : '다크 모드'"
            @click="themeStore.toggleTheme"
        >
            <span v-if="themeStore.isDark">☀</span>
            <span v-else>☾</span>
        </button>

        <div class="relative grid w-full max-w-5xl overflow-hidden rounded-3xl border border-white/60 bg-white shadow-2xl shadow-slate-900/10 dark:border-slate-800 dark:bg-slate-900 lg:grid-cols-[1.1fr_0.9fr]">
            <section class="hidden bg-gradient-to-br from-[#071a38] via-[#0d3470] to-[#1268c8] p-12 text-white lg:block">
                <div class="flex items-center gap-3">
                    <div class="grid h-12 w-12 place-items-center rounded-2xl bg-white/15 text-xl font-black backdrop-blur">
                        D
                    </div>
                    <div>
                        <div class="text-xl font-black">doenaai</div>
                        <div class="text-xs text-blue-100">AI 기반 변경 영향도 분석 시스템</div>
                    </div>
                </div>

                <div class="mt-20 max-w-md">
                    <div class="text-xs font-extrabold tracking-[0.18em] text-cyan-200">SECURE PROJECT ANALYSIS</div>
                    <h1 class="mt-4 text-4xl font-black leading-tight">
                        변경 전에 영향 범위와 위험 요소를 먼저 확인하세요.
                    </h1>
                    <p class="mt-5 text-sm leading-7 text-blue-100">
                        소스코드, 데이터베이스, 요구사항 문서를 함께 분석하고 근거가 포함된 변경 영향 결과를 제공합니다.
                    </p>
                </div>

                <div class="mt-16 grid grid-cols-3 gap-3 text-xs">
                    <div class="rounded-2xl border border-white/15 bg-white/10 p-4">
                        <div class="text-blue-200">인증</div>
                        <strong class="mt-2 block">Supabase JWT</strong>
                    </div>
                    <div class="rounded-2xl border border-white/15 bg-white/10 p-4">
                        <div class="text-blue-200">권한</div>
                        <strong class="mt-2 block">Spring RBAC</strong>
                    </div>
                    <div class="rounded-2xl border border-white/15 bg-white/10 p-4">
                        <div class="text-blue-200">분석</div>
                        <strong class="mt-2 block">Private AI API</strong>
                    </div>
                </div>
            </section>

            <section class="p-8 sm:p-12">
                <div>
                    <div class="text-xs font-extrabold tracking-[0.14em] text-blue-600">WELCOME BACK</div>
                    <h2 class="mt-3 text-3xl font-black text-slate-900 dark:text-white">로그인</h2>
                    <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">
                        등록된 계정으로 관리자 화면에 접속합니다.
                    </p>
                </div>

                <form class="mt-9 space-y-5" @submit.prevent="handleLogin">
                    <BaseInput
                        v-model.trim="form.email"
                        name="email"
                        label="이메일"
                        type="email"
                        autocomplete="email"
                        placeholder="admin@doenaai.ai"
                        required
                    />

                    <BaseInput
                        v-model="form.password"
                        name="password"
                        label="비밀번호"
                        type="password"
                        autocomplete="current-password"
                        placeholder="비밀번호를 입력하세요"
                        required
                    />

                    <div
                        v-if="authStore.errorMessage"
                        class="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-medium text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300"
                    >
                        {{ authStore.errorMessage }}
                    </div>

                    <BaseButton
                        type="submit"
                        size="large"
                        block
                        :loading="authStore.loading"
                    >
                        로그인
                    </BaseButton>
                </form>

                <div class="mt-7 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-xs text-slate-500 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-400">
                    인증 모드: <strong class="text-slate-700 dark:text-slate-200">{{ authModeLabel }}</strong>
                    <template v-if="authStore.isMockAuth">
                        <br />개발용 계정은 <code>{{ form.email }}</code>입니다.
                    </template>
                </div>
            </section>
        </div>
    </div>
</template>
