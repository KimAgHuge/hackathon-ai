<script setup>
import AppHeader from '@/components/layout/AppHeader.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
</script>

<template>
    <div class="min-h-screen bg-[#f4f7fb] text-slate-800 dark:bg-slate-950 dark:text-slate-100">
        <AppSidebar />

        <div class="ml-64 min-h-screen">
            <AppHeader />

            <main class="mx-auto max-w-[1600px] px-8 py-7">
                <RouterView />
            </main>
        </div>
    </div>
</template>
