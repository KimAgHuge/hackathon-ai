<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/authStore'
import { useThemeStore } from '@/stores/themeStore'

defineEmits(['toggle-navigation'])

const route = useRoute()
const authStore = useAuthStore()
const themeStore = useThemeStore()

const breadcrumb = computed(() => route.meta.breadcrumb || route.meta.title || '')
const themeButtonLabel = computed(() => (themeStore.isDark ? '라이트 모드로 전환' : '다크 모드로 전환'))
const roleLabel = computed(
    () => authStore.roles.join(', ') || (authStore.profileError ? '권한 조회 실패' : authStore.currentUser ? '역할 없음' : '권한 확인 중')
)
</script>

<template>
    <header
        class="sticky top-0 z-20 flex h-16 items-center justify-between gap-3 border-b border-slate-200 bg-white/95 px-4 backdrop-blur sm:px-6 lg:px-8 dark:border-slate-800 dark:bg-slate-950/92"
    >
        <div class="flex min-w-0 items-center gap-3">
            <button
                type="button"
                class="hidden h-9 w-9 shrink-0 place-items-center rounded-lg border border-slate-200 text-slate-600 max-lg:grid dark:border-slate-700 dark:text-slate-300"
                aria-label="메뉴 열기"
                @click="$emit('toggle-navigation')"
            >
                <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
                    <path d="M4 6h16M4 12h16M4 18h16" />
                </svg>
            </button>
            <div class="min-w-0">
                <div class="truncate text-xs font-medium text-slate-500 dark:text-slate-400">{{ breadcrumb }}</div>
                <div class="mt-1 truncate text-[10px] font-bold tracking-wide text-slate-500 dark:text-slate-400">{{ roleLabel }}</div>
            </div>
        </div>

        <div class="flex shrink-0 items-center gap-3">
            <div
                class="hidden items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-300 xl:flex"
            >
                <span class="h-2 w-2 rounded-full bg-emerald-500"></span>
                AI 서버 정상
            </div>

            <button
                type="button"
                class="grid h-9 w-9 place-items-center rounded-full border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
                :aria-label="themeButtonLabel"
                :title="themeButtonLabel"
                @click="themeStore.toggleTheme"
            >
                <svg v-if="themeStore.isDark" class="h-[18px] w-[18px]" viewBox="0 0 24 24" fill="none">
                    <circle cx="12" cy="12" r="4" stroke="currentColor" stroke-width="1.8" />
                    <path
                        d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.65 17.65l1.42 1.42M2 12h2M20 12h2M4.93 19.07l1.42-1.42M17.65 6.35l1.42-1.42"
                        stroke="currentColor"
                        stroke-width="1.8"
                        stroke-linecap="round"
                    />
                </svg>

                <svg v-else class="h-[18px] w-[18px]" viewBox="0 0 24 24" fill="none">
                    <path d="M20.5 14.3A8 8 0 0 1 9.7 3.5 8.5 8.5 0 1 0 20.5 14.3Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                </svg>
            </button>
        </div>
    </header>
</template>
