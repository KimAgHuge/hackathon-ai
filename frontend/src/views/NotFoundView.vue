<script setup>
import BaseButton from '@/components/base/BaseButton.vue'
</script>

<template>
    <div class="grid min-h-screen place-items-center bg-slate-100 px-6 dark:bg-slate-950">
        <div class="text-center">
            <div class="text-7xl font-black text-slate-200 dark:text-slate-800">404</div>
            <h1 class="mt-4 text-2xl font-black text-slate-900 dark:text-white">페이지를 찾을 수 없습니다.</h1>
            <BaseButton class="mt-6" to="/dashboard">대시보드로 이동</BaseButton>
        </div>
    </div>
</template>
