<script setup>
defineProps({
    current: {
        type: Number,
        default: 1,
    },
})

const steps = ['프로젝트 연결', '병렬 분석', '구조 확인', '영향도 분석']
</script>

<template>
    <div class="stepper">
        <div
            v-for="(step, index) in steps"
            :key="step"
            class="step-item"
            :class="{
                active: current === index + 1,
                complete: current > index + 1,
            }"
        >
            <div class="step-number">{{ index + 1 }}</div>
            <span>{{ step }}</span>
        </div>
    </div>
</template>
