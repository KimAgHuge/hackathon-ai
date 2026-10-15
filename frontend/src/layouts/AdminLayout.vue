<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import BaseButton from '@/components/base/BaseButton.vue'
import { useAuthStore } from '@/stores/authStore'

const sidebarOpen = ref(false)
const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

// 열려 있는 화면에서 세션이 만료되면 보호된 화면을 남겨두지 않고 로그인으로 이동한다.
watch(
    () => authStore.isAuthenticated,
    (authenticated) => {
        if (!authenticated) {
            router.replace({ name: 'login', query: { redirect: route.fullPath } })
        }
    }
)

const retryProfile = async () => {
    try {
        await authStore.loadCurrentUser()
    } catch {
        // 조회 실패 안내는 authStore의 profileError에 표시된다.
    }
}
</script>

<template>
    <div class="min-h-screen bg-[#f4f7fb] text-slate-800 dark:bg-slate-950 dark:text-slate-100">
        <AppSidebar :open="sidebarOpen" @close="sidebarOpen = false" />

        <div class="min-w-0 min-h-screen lg:ml-64">
            <AppHeader @toggle-navigation="sidebarOpen = !sidebarOpen" />

            <main class="mx-auto max-w-[1600px] min-w-0 px-4 py-5 sm:px-6 lg:px-8 lg:py-7">
                <div
                    v-if="!authStore.currentUser"
                    class="mx-auto mt-10 max-w-xl rounded-2xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-900"
                    role="status"
                >
                    <h1 class="text-lg font-bold text-slate-900 dark:text-white">계정 권한 확인</h1>
                    <p class="mt-2 text-sm text-slate-600 dark:text-slate-300">
                        {{ authStore.profileLoading ? '계정 정보와 권한을 불러오는 중입니다.' : authStore.profileError || '계정 정보를 확인하고 있습니다.' }}
                    </p>
                    <BaseButton v-if="!authStore.profileLoading" class="mt-5" :loading="authStore.profileLoading" @click="retryProfile"
                        >권한 다시 확인</BaseButton
                    >
                </div>
                <RouterView v-else />
            </main>
        </div>
    </div>
</template>
