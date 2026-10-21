<script setup>
import { computed, reactive, ref, watch } from 'vue'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseModal from '@/components/base/BaseModal.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTextarea from '@/components/base/BaseTextarea.vue'

const props = defineProps({
    modelValue: { type: Boolean, default: false },
    models: { type: Array, default: () => [] },
    modelsTruncated: { type: Boolean, default: false },
    busy: { type: Boolean, default: false },
    error: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'submit'])
const emptyForm = () => ({
    modelId: '',
    modelName: '',
    modelDescription: '',
    provider: '',
    baseModelRef: '',
    version: '',
    artifactType: 'LORA',
    artifactUri: '',
    adapterUri: '',
    checksum: '',
    datasetName: '',
    notes: '',
})
const form = reactive(emptyForm())
const validation = ref('')
const modelOptions = computed(() => [{ value: '', label: '새 모델로 등록' }, ...props.models.map((model) => ({ value: model.id, label: model.name }))])
const artifactOptions = [
    { value: 'LORA', label: 'LoRA 어댑터' },
    { value: 'BASE', label: '기반 모델' },
    { value: 'MERGED', label: '병합 모델' },
    { value: 'EXTERNAL', label: '외부 모델' },
]

watch(
    () => props.modelValue,
    (open) => {
        if (!open) return
        Object.assign(form, emptyForm())
        validation.value = ''
    }
)
watch(
    () => form.modelId,
    (id) => {
        const model = props.models.find((item) => item.id === id)
        if (model) form.baseModelRef = model.baseModelRef || ''
    }
)

const validPath = (value) => {
    if (!value) return true
    return (
        value.length <= 1024 &&
        /^[\p{L}\p{N}._ /-]+$/u.test(value) &&
        !value.startsWith('/') &&
        !value.endsWith('/') &&
        value.split('/').every((part) => part.trim() && !['.', '..'].includes(part.trim()))
    )
}
const submit = () => {
    if (props.busy) return
    const values = Object.fromEntries(Object.entries(form).map(([key, value]) => [key, value.trim()]))
    if ((!values.modelId && !values.modelName) || !values.baseModelRef || !values.version) {
        validation.value = '모델 이름, 기반 모델 식별자와 버전을 입력해 주세요.'
        return
    }
    if (!validPath(values.artifactUri) || !validPath(values.adapterUri)) {
        validation.value = '파일 위치에는 NAS 기준 상대경로만 입력해 주세요. 전체 주소, 절대경로, 상위 폴더 이동은 사용할 수 없습니다.'
        return
    }
    if (values.checksum && !/^[a-fA-F0-9]{64}$/.test(values.checksum)) {
        validation.value = 'SHA-256 해시는 영문 a부터 f와 숫자로 이루어진 64자리 값이어야 합니다.'
        return
    }
    validation.value = ''
    const body = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, value || null]))
    if (body.modelId) {
        delete body.modelName
        delete body.modelDescription
        delete body.provider
    }
    emit('submit', body)
}
</script>

<template>
    <BaseModal
        :model-value="modelValue"
        title="모델 버전 등록"
        :busy="busy"
        footer-text="등록 정보만 서버에 저장합니다. 파일 복사, 학습, 평가와 배포는 실행하지 않습니다."
        @update:model-value="emit('update:modelValue', $event)"
    >
        <form class="grid min-w-0 gap-5" @submit.prevent="submit">
            <div
                class="rounded-xl border border-blue-200 bg-blue-50 p-4 text-sm leading-6 text-blue-800 dark:border-blue-800 dark:bg-blue-950/40 dark:text-blue-200"
            >
                모델 파일의 위치와 버전을 관리 기록으로 등록합니다. 등록 상태는 파일 수신·해시 검증이나 배포 준비 완료를 뜻하지 않습니다. 비밀번호, 인증키와
                접근 토큰은 입력하지 마세요.
            </div>
            <fieldset class="grid min-w-0 gap-5" :disabled="busy">
                <BaseSelect v-model="form.modelId" label="등록할 모델" :options="modelOptions" />
                <p v-if="modelsTruncated" class="text-xs leading-6 text-amber-700 dark:text-amber-300">
                    기존 모델 선택에는 서버가 반환한 최근 항목만 표시됩니다. 찾는 기존 모델이 없다면 새 모델로 다시 만들지 말고 관리자에게 확인해 주세요.
                </p>
                <template v-if="!form.modelId">
                    <div class="grid gap-5 sm:grid-cols-2">
                        <BaseInput v-model="form.modelName" label="새 모델 이름" required maxlength="200" placeholder="관리할 모델 이름" />
                        <BaseInput v-model="form.provider" label="제공자" maxlength="100" placeholder="선택 입력" />
                    </div>
                    <BaseTextarea
                        v-model="form.modelDescription"
                        label="모델 설명"
                        maxlength="2000"
                        rows="2"
                        :disabled="busy"
                        placeholder="모델 용도와 관리 범위"
                    />
                </template>
                <div class="grid gap-5 sm:grid-cols-2">
                    <BaseInput v-model="form.baseModelRef" label="기반 모델 식별자" required maxlength="500" placeholder="모델 저장소의 식별자" />
                    <BaseInput v-model="form.version" label="버전" required maxlength="100" placeholder="구분할 수 있는 버전 이름" />
                    <BaseSelect v-model="form.artifactType" label="파일 유형" :options="artifactOptions" />
                    <BaseInput v-model="form.datasetName" label="학습 데이터셋 이름" maxlength="200" placeholder="선택 입력" />
                    <BaseInput
                        v-model="form.artifactUri"
                        label="모델 파일 위치"
                        maxlength="1024"
                        placeholder="models/모델이름/버전"
                        hint="NAS 기준 상대경로이며 파일을 전송하지 않습니다."
                    />
                    <BaseInput
                        v-model="form.adapterUri"
                        label="어댑터 파일 위치"
                        maxlength="1024"
                        placeholder="adapters/모델이름/버전"
                        hint="LoRA 어댑터 위치를 기록할 때 입력합니다."
                    />
                </div>
                <BaseInput
                    v-model="form.checksum"
                    label="SHA-256 해시"
                    maxlength="64"
                    placeholder="확인한 64자리 해시가 있을 때만 입력"
                    hint="입력값을 실제 파일과 자동으로 대조하지 않습니다."
                />
                <BaseTextarea
                    v-model="form.notes"
                    label="등록 메모"
                    maxlength="4000"
                    rows="3"
                    :disabled="busy"
                    placeholder="출처, 확인한 사항과 남은 검토 내용을 입력해 주세요."
                />
            </fieldset>
            <p v-if="validation || error" role="alert" class="rounded-lg bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">
                {{ validation || error }}
            </p>
            <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                <BaseButton variant="secondary" :disabled="busy" @click="emit('update:modelValue', false)">취소</BaseButton>
                <BaseButton type="submit" :loading="busy">등록 정보 저장</BaseButton>
            </div>
        </form>
    </BaseModal>
</template>
