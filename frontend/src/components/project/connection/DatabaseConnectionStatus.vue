<script setup>
import { computed } from 'vue'

const props = defineProps({
    status: {
        type: String,
        default: 'IDLE',
    },
    message: {
        type: String,
        default: '',
    },
    databaseProduct: {
        type: String,
        default: '',
    },
    databaseVersion: {
        type: String,
        default: '',
    },
})

const presentation = computed(() => {
    const values = {
        IDLE: {
            title: '연결 테스트 전',
            description: '프로젝트와 DB 정보를 입력한 뒤 연결 테스트를 실행하세요.',
            className: 'border-slate-200 bg-slate-50 text-slate-600 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-300',
        },
        TESTING: {
            title: '연결 확인 중',
            description: '네트워크와 인증 정보를 확인하고 있습니다.',
            className: 'border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-900/50 dark:bg-blue-950/30 dark:text-blue-300',
        },
        SUCCESS: {
            title: '연결 성공',
            description: '읽기 전용 연결정보를 안전하게 저장할 수 있습니다.',
            className: 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/50 dark:bg-emerald-950/30 dark:text-emerald-300',
        },
        FAILED: {
            title: '연결 실패',
            description: '호스트, 포트, 서비스명과 계정 정보를 다시 확인하세요.',
            className: 'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300',
        },
    }

    return values[props.status] || values.IDLE
})
</script>

<template>
    <section class="mt-5 rounded-2xl border px-5 py-4" :class="presentation.className">
        <div class="flex flex-wrap items-start justify-between gap-3">
            <div>
                <p class="text-sm font-semibold">{{ presentation.title }}</p>
                <p class="mt-1 text-xs leading-5">
                    {{ message || presentation.description }}
                </p>
            </div>
            <span v-if="status === 'TESTING'" class="size-4 animate-spin rounded-full border-2 border-current border-r-transparent"></span>
        </div>

        <dl v-if="status === 'SUCCESS' && databaseProduct" class="mt-3 grid gap-2 text-xs sm:grid-cols-2">
            <div>
                <dt class="font-semibold">DB 제품</dt>
                <dd class="mt-1">{{ databaseProduct }}</dd>
            </div>
            <div v-if="databaseVersion">
                <dt class="font-semibold">버전</dt>
                <dd class="mt-1 break-words">{{ databaseVersion }}</dd>
            </div>
        </dl>
    </section>
</template>
