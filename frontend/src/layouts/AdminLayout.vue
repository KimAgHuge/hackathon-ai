<script setup>
import { ref } from 'vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'

const sidebarOpen = ref(false)
</script>

<template>
    <div class="min-h-screen bg-[#f4f7fb] text-slate-800 dark:bg-slate-950 dark:text-slate-100">
        <AppSidebar :open="sidebarOpen" @close="sidebarOpen = false" />

        <div class="min-w-0 min-h-screen lg:ml-64">
            <AppHeader @toggle-navigation="sidebarOpen = !sidebarOpen" />

            <main class="mx-auto max-w-[1600px] min-w-0 px-4 py-5 sm:px-6 lg:px-8 lg:py-7">
                <RouterView />
            </main>
        </div>
    </div>
</template>
