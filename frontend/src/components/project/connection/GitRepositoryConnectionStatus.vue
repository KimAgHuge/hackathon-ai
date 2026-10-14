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
    branch: {
        type: String,
        default: '',
    },
    commitSha: {
        type: String,
        default: '',
    },
})

const presentation = computed(() => {
    const values = {
        IDLE: {
            title: '저장소 확인 필요',
            description: 'URL과 브랜치를 입력한 뒤 실제 원격 저장소 연결을 확인하세요.',
            className: 'border-slate-200 bg-slate-50 text-slate-600 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-300',
        },
        TESTING: {
            title: '저장소 확인 중',
            description: '원격 저장소 접근과 브랜치의 최신 커밋을 확인하고 있습니다.',
            className: 'border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-900/50 dark:bg-blue-950/30 dark:text-blue-300',
        },
        SUCCESS: {
            title: '저장소 연결 성공',
            description: '분석에 사용할 브랜치와 커밋을 확인했습니다.',
            className: 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/50 dark:bg-emerald-950/30 dark:text-emerald-300',
        },
        FAILED: {
            title: '저장소 연결 실패',
            description: '저장소 공개 여부, URL과 브랜치 이름을 다시 확인하세요.',
            className: 'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300',
        },
    }

    return values[props.status] || values.IDLE
})

const shortCommitSha = computed(() => (props.commitSha ? props.commitSha.slice(0, 12) : ''))
</script>

<template>
    <section class="mt-4 rounded-2xl border px-5 py-4" :class="presentation.className">
        <div class="flex flex-wrap items-start justify-between gap-3">
            <div>
                <p class="text-sm font-semibold">
                    {{ presentation.title }}
                </p>
                <p class="mt-1 text-xs leading-5">
                    {{ message || presentation.description }}
                </p>
            </div>
            <span
                v-if="status === 'TESTING'"
                class="size-4 animate-spin rounded-full border-2 border-current border-r-transparent"
                aria-label="저장소 확인 중"
            ></span>
        </div>

        <dl v-if="status === 'SUCCESS'" class="mt-3 grid gap-2 text-xs sm:grid-cols-2">
            <div>
                <dt class="font-semibold">브랜치</dt>
                <dd class="mt-1">{{ branch }}</dd>
            </div>
            <div>
                <dt class="font-semibold">확인된 커밋</dt>
                <dd class="mt-1 font-mono">{{ shortCommitSha }}</dd>
            </div>
        </dl>
    </section>
</template>
