<script setup>
import BaseButton from '@/components/base/BaseButton.vue'
</script>

<template>
    <div class="grid min-h-[70vh] place-items-center">
        <div class="text-center">
            <div class="text-6xl font-black text-slate-200 dark:text-slate-700">403</div>
            <h1 class="mt-4 text-2xl font-black text-slate-900 dark:text-white">접근 권한이 없습니다.</h1>
            <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">
                현재 계정에는 이 화면을 조회할 권한이 없습니다.
            </p>
            <BaseButton class="mt-6" to="/dashboard">대시보드로 이동</BaseButton>
        </div>
    </div>
</template>
