<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import BaseButton from '@/components/base/BaseButton.vue'
import { useAuthStore } from '@/stores/authStore'

const router = useRouter()
const authStore = useAuthStore()

const menus = [
    {
        label: '대시보드',
        path: '/dashboard',
        permission: 'DASHBOARD_READ',
        icon: 'M3 12l9-9 9 9M5 10v10h14V10M9 20v-6h6v6',
    },
    {
        label: '프로젝트 관리',
        path: '/projects',
        permission: 'PROJECT_READ',
        icon: 'M3 7h7l2 2h9v10H3V7z',
    },
    {
        label: '변경요청 관리',
        path: '/change-requests',
        permission: 'CHANGE_REQUEST_READ',
        icon: 'M9 5h6M9 9h6M9 13h4M5 3h14v18H5V3z',
    },
    {
        label: '분석 이력',
        path: '/analysis-history',
        permission: 'ANALYSIS_READ',
        icon: 'M12 8v5l3 2M21 12a9 9 0 1 1-3-6.7',
    },
    {
        label: 'AI 모델 관리',
        path: '/models',
        permission: 'MODEL_READ',
        icon: 'M8 8h8v8H8V8zM3 10h3M3 14h3M18 10h3M18 14h3M10 3v3M14 3v3M10 18v3M14 18v3',
    },
    {
        label: '문서 관리',
        path: '/documents',
        permission: 'DOCUMENT_READ',
        icon: 'M6 3h9l3 3v15H6V3zM14 3v4h4M9 12h6M9 16h6',
    },
    {
        label: '설정',
        path: '/settings',
        permission: 'SYSTEM_SETTING_READ',
        icon: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4 12H2M22 12h-2M12 4V2M12 22v-2M6.3 6.3 4.9 4.9M19.1 19.1l-1.4-1.4M17.7 6.3l1.4-1.4M4.9 19.1l1.4-1.4',
    },
]

const visibleMenus = computed(() => {
    return menus.filter((menu) => authStore.hasPermission(menu.permission))
})

const displayName = computed(() => authStore.currentUser?.displayName || '사용자')
const email = computed(() => authStore.currentUser?.email || '-')
const initial = computed(() => displayName.value.slice(0, 1))

const handleLogout = async () => {
    await authStore.logout()
    await router.replace('/login')
}
</script>

<template>
    <aside class="fixed inset-y-0 left-0 z-30 flex w-64 flex-col border-r border-slate-800 bg-[#071a38] px-4 py-5 text-white">
        <div class="flex items-center gap-3 px-2 pb-7">
            <div class="grid h-11 w-11 place-items-center rounded-xl bg-gradient-to-br from-cyan-400 to-blue-600 shadow-lg shadow-blue-950/40">
                <svg class="h-7 w-7" viewBox="0 0 24 24" fill="none">
                    <path d="M12 2 20 6.5v11L12 22l-8-4.5v-11L12 2Z" stroke="currentColor" stroke-width="1.6" />
                    <path d="m8 15 3-7 2 5 3-2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
            </div>
            <div>
                <div class="text-lg font-extrabold tracking-tight">doenaai</div>
                <div class="mt-0.5 text-[11px] text-slate-400">변경 영향도 분석 시스템</div>
            </div>
        </div>

        <nav class="space-y-1">
            <RouterLink
                v-for="menu in visibleMenus"
                :key="menu.path"
                :to="menu.path"
                class="group flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-semibold text-slate-300 transition hover:bg-white/8 hover:text-white"
                active-class="bg-blue-600 text-white shadow-lg shadow-blue-950/30"
            >
                <svg class="h-[18px] w-[18px] shrink-0" viewBox="0 0 24 24" fill="none">
                    <path
                        :d="menu.icon"
                        stroke="currentColor"
                        stroke-width="1.7"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                    />
                </svg>
                <span>{{ menu.label }}</span>
            </RouterLink>
        </nav>

        <div class="mt-auto border-t border-slate-700/70 pt-4">
            <div class="flex items-center gap-3 rounded-xl px-2 py-2">
                <div class="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-slate-200 text-xs font-bold text-slate-700">
                    {{ initial }}
                </div>
                <div class="min-w-0 flex-1">
                    <div class="truncate text-sm font-semibold">{{ displayName }}</div>
                    <div class="truncate text-[11px] text-slate-400">{{ email }}</div>
                </div>
            </div>

            <BaseButton
                class="mt-2"
                variant="ghost"
                size="small"
                block
                :loading="authStore.loading"
                @click="handleLogout"
            >
                로그아웃
            </BaseButton>
        </div>
    </aside>
</template>
