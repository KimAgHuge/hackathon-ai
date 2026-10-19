<script setup>
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

const props = defineProps({
    modelValue: { type: Boolean, default: false },
    title: { type: String, required: true },
    busy: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])
const dialog = ref(null)
const titleId = useId()
let previousOverflow = null

const restoreScroll = () => {
    if (previousOverflow === null) return
    document.body.style.overflow = previousOverflow
    previousOverflow = null
}

// 기본 대화상자로 배경 조작과 초점 이탈을 막고 닫을 때 원래 버튼으로 초점을 돌린다.
watch(
    () => props.modelValue,
    async (open) => {
        await nextTick()
        if (!dialog.value) return
        if (open && !dialog.value.open) {
            previousOverflow = document.body.style.overflow
            document.body.style.overflow = 'hidden'
            dialog.value.showModal()
        } else if (!open && dialog.value.open) {
            dialog.value.close()
            restoreScroll()
        }
    },
    { immediate: true }
)

const requestClose = () => {
    if (!props.busy) emit('update:modelValue', false)
}
const onClosed = () => {
    restoreScroll()
    if (props.modelValue) emit('update:modelValue', false)
}
onBeforeUnmount(() => {
    dialog.value?.close()
    restoreScroll()
})
</script>

<template>
    <Teleport to="body">
        <dialog ref="dialog" class="base-modal" :aria-labelledby="titleId" aria-modal="true" @cancel.prevent="requestClose" @close="onClosed">
            <header class="base-modal-header">
                <h2 :id="titleId">{{ title }}</h2>
                <button type="button" class="button secondary" :disabled="busy" autofocus @click="requestClose">닫기</button>
            </header>
            <div class="base-modal-body" :aria-busy="busy">
                <slot />
            </div>
            <footer class="base-modal-footer">
                <p>{{ busy ? '요청을 처리하고 있습니다. 완료될 때까지 기다려 주세요.' : '이 창을 닫아도 저장된 분석과 검토 결과는 유지됩니다.' }}</p>
            </footer>
        </dialog>
    </Teleport>
</template>

<style scoped>
.base-modal {
    width: min(1040px, calc(100vw - 32px));
    max-width: none;
    max-height: calc(100dvh - 48px);
    margin: auto;
    padding: 0;
    overflow: hidden;
    border: 1px solid #dbe3f0;
    border-radius: 20px;
    background: #fff;
    color: #1e293b;
    box-shadow: 0 24px 80px rgb(15 23 42 / 24%);
}
.base-modal[open] {
    display: flex;
    flex-direction: column;
}
.base-modal::backdrop {
    background: rgb(15 23 42 / 55%);
    backdrop-filter: blur(3px);
}
.base-modal-header {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 20px 24px;
    border-bottom: 1px solid #e2e8f0;
}
.base-modal-header h2 {
    min-width: 0;
    overflow-wrap: anywhere;
    font-size: 18px;
    font-weight: 800;
}
.base-modal-header .button {
    flex-shrink: 0;
}
.base-modal-body {
    min-height: 0;
    overflow: auto;
    overscroll-behavior: contain;
    padding: 24px;
    scrollbar-gutter: stable;
}
.base-modal-footer {
    flex-shrink: 0;
    padding: 12px 24px;
    border-top: 1px solid #e2e8f0;
    background: #f8fafc;
    color: #64748b;
    font-size: 12px;
    overflow-wrap: anywhere;
}
:global(.dark) .base-modal {
    border-color: #334155;
    background: #0f172a;
    color: #e2e8f0;
}
:global(.dark) .base-modal-header,
:global(.dark) .base-modal-footer {
    border-color: #334155;
}
:global(.dark) .base-modal-footer {
    background: #172033;
    color: #cbd5e1;
}
@media (max-width: 640px) {
    .base-modal {
        width: calc(100vw - 16px);
        max-height: calc(100dvh - 16px);
        border-radius: 16px;
    }
    .base-modal-header,
    .base-modal-body {
        padding: 16px;
    }
    .base-modal-footer {
        padding: 12px 16px;
    }
}
</style>
