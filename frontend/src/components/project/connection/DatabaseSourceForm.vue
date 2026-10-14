<script setup>
import { computed } from 'vue'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import { DATABASE_MODES, DATABASE_TYPES, getDatabaseTypeOption } from '@/constants/projectConnectionOptions'

const props = defineProps({
    modelValue: {
        type: Object,
        required: true,
    },
    testingConnection: {
        type: Boolean,
        default: false,
    },
    testDisabled: {
        type: Boolean,
        default: false,
    },
})

const emit = defineEmits(['update:modelValue', 'test-connection'])

const selectedDatabaseType = computed(() => getDatabaseTypeOption(props.modelValue.dbType) || DATABASE_TYPES[0])

const updateValue = (patch) => {
    emit('update:modelValue', {
        ...props.modelValue,
        ...patch,
    })
}

const selectDatabaseMode = (connectionMode) => {
    const option = DATABASE_MODES.find(({ value }) => value === connectionMode)

    if (!option?.available) return

    updateValue({ connectionMode })
}

const updateDatabaseType = (dbType) => {
    const option = getDatabaseTypeOption(dbType)

    updateValue({
        dbType,
        port: option?.defaultPort || props.modelValue.port,
    })
}
</script>

<template>
    <article class="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-900">
        <div class="mb-6 flex items-start justify-between gap-4">
            <div>
                <h2 class="text-lg font-semibold text-slate-900 dark:text-slate-100">데이터베이스</h2>
                <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">읽기 전용 연결정보로 접속과 메타데이터 접근 가능 여부를 확인합니다.</p>
            </div>
            <span class="rounded-full bg-violet-50 px-3 py-1 text-[10px] font-bold tracking-wider text-violet-600 dark:bg-violet-950/30 dark:text-violet-300">
                DATABASE
            </span>
        </div>

        <div class="space-y-5">
            <div class="inline-flex flex-wrap rounded-xl border border-slate-200 p-1 dark:border-slate-700">
                <button
                    v-for="option in DATABASE_MODES"
                    :key="option.value"
                    type="button"
                    class="rounded-lg px-4 py-2 text-sm font-semibold transition"
                    :class="[
                        modelValue.connectionMode === option.value ? 'bg-brand-50 text-brand-700 dark:bg-brand-950/40 dark:text-brand-300' : 'text-slate-500',
                        { 'cursor-not-allowed opacity-45': !option.available },
                    ]"
                    :disabled="!option.available"
                    @click="selectDatabaseMode(option.value)"
                >
                    {{ option.label }}
                    <span v-if="!option.available" class="ml-1 text-[10px]">준비 중</span>
                </button>
            </div>

            <BaseSelect
                :model-value="modelValue.dbType"
                name="databaseType"
                label="DB 종류"
                :options="DATABASE_TYPES"
                required
                @update:model-value="updateDatabaseType"
            />

            <div class="grid gap-4 md:grid-cols-[1fr_130px]">
                <BaseInput
                    :model-value="modelValue.host"
                    name="dbHost"
                    label="호스트"
                    placeholder="127.0.0.1"
                    required
                    @update:model-value="updateValue({ host: $event })"
                />
                <BaseInput
                    :model-value="modelValue.port"
                    name="dbPort"
                    label="포트"
                    type="number"
                    min="1"
                    max="65535"
                    required
                    @update:model-value="updateValue({ port: Number($event) })"
                />
            </div>

            <BaseInput
                :model-value="modelValue.databaseName"
                name="databaseName"
                :label="selectedDatabaseType.databaseNameLabel"
                :placeholder="selectedDatabaseType.databaseNamePlaceholder"
                required
                @update:model-value="updateValue({ databaseName: $event })"
            />
            <BaseInput
                :model-value="modelValue.schemaName"
                name="schemaName"
                label="스키마"
                placeholder="예: DOENAAI"
                required
                @update:model-value="updateValue({ schemaName: $event })"
            />
            <BaseInput
                :model-value="modelValue.username"
                name="dbUsername"
                label="읽기 전용 계정"
                placeholder="doenaai_reader"
                autocomplete="off"
                required
                @update:model-value="updateValue({ username: $event })"
            />
            <BaseInput
                :model-value="modelValue.password"
                name="dbPassword"
                label="비밀번호"
                type="password"
                placeholder="읽기 전용 계정 비밀번호"
                autocomplete="new-password"
                hint="비밀번호는 Pinia나 브라우저 저장소에 보관하지 않습니다."
                required
                @update:model-value="updateValue({ password: $event })"
            />

            <div class="flex justify-end">
                <BaseButton
                    class="w-full sm:w-auto"
                    type="button"
                    variant="secondary"
                    :loading="testingConnection"
                    :disabled="testDisabled"
                    @click="emit('test-connection')"
                >
                    연결 테스트
                </BaseButton>
            </div>

            <div
                class="rounded-xl border border-brand-100 bg-brand-50 px-4 py-3 text-xs leading-5 text-brand-700 dark:border-brand-900/40 dark:bg-brand-950/20 dark:text-brand-300"
            >
                실제 업무 데이터는 조회하지 않고 연결 정보와 DB 제품 정보만 확인합니다.
            </div>
        </div>
    </article>
</template>
