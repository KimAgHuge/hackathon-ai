<script setup>
import { computed, reactive, ref } from 'vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTextarea from '@/components/base/BaseTextarea.vue'
import { GIT_AUTH_TYPES, GIT_PROVIDER_TYPES, SOURCE_TYPES } from '@/constants/projectConnectionOptions'
import GitRepositoryConnectionStatus from './GitRepositoryConnectionStatus.vue'

const props = defineProps({
    modelValue: {
        type: Object,
        required: true,
    },
    organizationOptions: {
        type: Array,
        default: () => [],
    },
    organizationsLoading: {
        type: Boolean,
        default: false,
    },
    organizationLoadError: {
        type: String,
        default: '',
    },
    canCreateOrganization: {
        type: Boolean,
        default: false,
    },
    creatingOrganization: {
        type: Boolean,
        default: false,
    },
    organizationCreateError: {
        type: String,
        default: '',
    },
    connectionModelValue: {
        type: Object,
        required: true,
    },
    repositoryStatus: {
        type: String,
        default: 'IDLE',
    },
    repositoryMessage: {
        type: String,
        default: '',
    },
    repositoryBranch: {
        type: String,
        default: '',
    },
    repositoryCommitSha: {
        type: String,
        default: '',
    },
    testingRepository: {
        type: Boolean,
        default: false,
    },
    repositoryTestDisabled: {
        type: Boolean,
        default: false,
    },
})

const emit = defineEmits(['update:modelValue', 'update:connectionModelValue', 'create-organization', 'test-repository'])

const showOrganizationForm = ref(false)
const newOrganization = reactive({
    name: '',
    slug: '',
    description: '',
})

const organizationFormVisible = computed(
    () => showOrganizationForm.value || (!props.organizationsLoading && !props.organizationOptions.length && props.canCreateOrganization)
)

const organizationFormValid = computed(() => Boolean(newOrganization.name.trim() && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(newOrganization.slug.trim())))

const updateValue = (patch) => {
    emit('update:modelValue', {
        ...props.modelValue,
        ...patch,
    })
}

const updateConnectionValue = (patch) => {
    emit('update:connectionModelValue', {
        ...props.connectionModelValue,
        ...patch,
    })
}

const selectSourceType = (sourceType) => {
    const option = SOURCE_TYPES.find(({ value }) => value === sourceType)

    if (!option?.available) return

    updateValue({ sourceType })
}

const createOrganization = () => {
    if (!organizationFormValid.value || props.creatingOrganization) return

    emit('create-organization', {
        name: newOrganization.name.trim(),
        slug: newOrganization.slug.trim(),
        description: newOrganization.description.trim() || null,
    })
}
</script>

<template>
    <article class="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-900">
        <div class="mb-6 flex items-start justify-between gap-4">
            <div>
                <h2 class="text-lg font-semibold text-slate-900 dark:text-slate-100">프로젝트 소스</h2>
                <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">우선 Git 저장소 연결을 지원하며 ZIP 업로드는 다음 단계에서 추가합니다.</p>
            </div>
            <span class="rounded-full bg-brand-50 px-3 py-1 text-[10px] font-bold tracking-wider text-brand-600 dark:bg-brand-950/40 dark:text-brand-300">
                SOURCE
            </span>
        </div>

        <div class="space-y-5">
            <div class="space-y-3 rounded-2xl border border-slate-200 bg-slate-50/70 p-4 dark:border-slate-700 dark:bg-slate-800/40">
                <BaseSelect
                    :model-value="modelValue.organizationId"
                    :options="organizationOptions"
                    name="organizationId"
                    label="소속 조직"
                    :disabled="organizationsLoading || Boolean(organizationLoadError)"
                    :hint="organizationsLoading ? '조직 목록을 불러오는 중입니다.' : '프로젝트가 소속될 조직을 선택하세요.'"
                    :error="organizationLoadError"
                    required
                    @update:model-value="updateValue({ organizationId: $event })"
                >
                    <template #before-options>
                        <option value="" disabled>
                            {{ organizationsLoading ? '불러오는 중...' : organizationOptions.length ? '조직을 선택하세요' : '선택 가능한 조직이 없습니다' }}
                        </option>
                    </template>
                </BaseSelect>

                <div v-if="canCreateOrganization && organizationOptions.length" class="flex justify-end">
                    <BaseButton
                        class="w-full sm:w-auto"
                        variant="secondary"
                        size="small"
                        :disabled="organizationsLoading"
                        @click="showOrganizationForm = !showOrganizationForm"
                    >
                        {{ organizationFormVisible ? '조직 생성 닫기' : '새 조직 생성' }}
                    </BaseButton>
                </div>

                <div
                    v-else-if="!organizationsLoading && !organizationOptions.length && !organizationLoadError"
                    class="rounded-xl bg-amber-50 px-4 py-3 text-xs text-amber-700 dark:bg-amber-950/30 dark:text-amber-300"
                >
                    소속된 활성 조직이 없습니다. 조직 관리자에게 멤버 등록을 요청하세요.
                </div>

                <div v-if="organizationFormVisible" class="grid gap-4 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
                    <BaseInput v-model="newOrganization.name" name="newOrganizationName" label="새 조직명" placeholder="예: 두나AI 개발팀" required />
                    <BaseInput
                        v-model="newOrganization.slug"
                        name="newOrganizationSlug"
                        label="조직 식별자"
                        placeholder="예: doenaai-dev"
                        hint="소문자 영문, 숫자와 단일 하이픈만 사용할 수 있습니다."
                        required
                    />
                    <BaseTextarea
                        v-model="newOrganization.description"
                        name="newOrganizationDescription"
                        label="조직 설명"
                        placeholder="조직의 용도나 담당 업무를 입력하세요."
                        rows="2"
                    />
                    <p v-if="organizationCreateError" class="text-xs font-medium text-rose-600 dark:text-rose-400">
                        {{ organizationCreateError }}
                    </p>
                    <div class="flex justify-end">
                        <BaseButton class="w-full sm:w-auto" :loading="creatingOrganization" :disabled="!organizationFormValid" @click="createOrganization">
                            조직 생성 및 선택
                        </BaseButton>
                    </div>
                </div>
            </div>

            <BaseInput
                :model-value="modelValue.name"
                name="projectName"
                label="프로젝트명"
                placeholder="예: doenaai-demo"
                required
                @update:model-value="updateValue({ name: $event })"
            />

            <BaseTextarea
                :model-value="modelValue.description"
                name="projectDescription"
                label="프로젝트 설명"
                placeholder="분석 대상 시스템과 목적을 간단히 입력하세요."
                rows="3"
                @update:model-value="updateValue({ description: $event })"
            />

            <div class="inline-flex flex-wrap rounded-xl border border-slate-200 p-1 dark:border-slate-700">
                <button
                    v-for="option in SOURCE_TYPES"
                    :key="option.value"
                    type="button"
                    class="rounded-lg px-4 py-2 text-sm font-semibold transition"
                    :class="[
                        modelValue.sourceType === option.value ? 'bg-brand-50 text-brand-700 dark:bg-brand-950/40 dark:text-brand-300' : 'text-slate-500',
                        { 'cursor-not-allowed opacity-45': !option.available },
                    ]"
                    :disabled="!option.available"
                    @click="selectSourceType(option.value)"
                >
                    {{ option.label }}
                    <span v-if="!option.available" class="ml-1 text-[10px]">준비 중</span>
                </button>
            </div>

            <template v-if="modelValue.sourceType === 'GIT'">
                <BaseInput
                    :model-value="modelValue.repositoryUrl"
                    name="repositoryUrl"
                    label="저장소 URL"
                    placeholder="https://github.com/example/doenaai-demo.git"
                    required
                    @update:model-value="updateValue({ repositoryUrl: $event })"
                />
                <BaseInput
                    :model-value="modelValue.defaultBranch"
                    name="defaultBranch"
                    label="기본 브랜치"
                    placeholder="main"
                    required
                    @update:model-value="updateValue({ defaultBranch: $event })"
                />

                <div class="grid gap-4 md:grid-cols-2">
                    <BaseSelect
                        :model-value="connectionModelValue.providerType"
                        :options="GIT_PROVIDER_TYPES"
                        name="repositoryProviderType"
                        label="Git 공급자"
                        required
                        @update:model-value="updateConnectionValue({ providerType: $event })"
                    />
                    <BaseSelect
                        :model-value="connectionModelValue.authType"
                        :options="GIT_AUTH_TYPES"
                        name="repositoryAuthType"
                        label="인증 방식"
                        required
                        @update:model-value="updateConnectionValue({ authType: $event })"
                    />
                </div>

                <template v-if="['HTTPS_TOKEN', 'HTTPS_BASIC'].includes(connectionModelValue.authType)">
                    <BaseInput
                        :model-value="connectionModelValue.username"
                        name="repositoryUsername"
                        label="Git 사용자명"
                        autocomplete="username"
                        required
                        @update:model-value="updateConnectionValue({ username: $event })"
                    />
                    <BaseInput
                        :model-value="connectionModelValue.secret"
                        name="repositorySecret"
                        :label="connectionModelValue.authType === 'HTTPS_TOKEN' ? 'Access Token' : '비밀번호'"
                        type="password"
                        autocomplete="new-password"
                        required
                        hint="값은 브라우저에 다시 표시하지 않으며 서버에서 암호화하여 저장합니다."
                        @update:model-value="updateConnectionValue({ secret: $event })"
                    />
                </template>

                <template v-else-if="connectionModelValue.authType === 'SSH_KEY'">
                    <BaseTextarea
                        :model-value="connectionModelValue.sshPrivateKey"
                        name="repositorySshPrivateKey"
                        label="SSH 개인키"
                        rows="7"
                        required
                        placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
                        @update:model-value="updateConnectionValue({ sshPrivateKey: $event })"
                    />
                    <BaseInput
                        :model-value="connectionModelValue.sshPassphrase"
                        name="repositorySshPassphrase"
                        label="개인키 Passphrase"
                        type="password"
                        autocomplete="new-password"
                        hint="개인키에 Passphrase가 설정된 경우에만 입력합니다."
                        @update:model-value="updateConnectionValue({ sshPassphrase: $event })"
                    />
                    <BaseTextarea
                        :model-value="connectionModelValue.knownHostsEntry"
                        name="repositoryKnownHosts"
                        label="SSH known_hosts"
                        rows="4"
                        required
                        placeholder="git.example.com ssh-ed25519 AAAAC3..."
                        hint="접속할 서버의 신뢰할 수 있는 호스트 공개키 한 줄 이상을 입력합니다."
                        @update:model-value="updateConnectionValue({ knownHostsEntry: $event })"
                    />
                </template>

                <p
                    v-if="connectionModelValue.authType === 'NONE'"
                    class="rounded-xl bg-slate-50 px-4 py-3 text-xs leading-5 text-slate-500 dark:bg-slate-800/70 dark:text-slate-400"
                >
                    공개 HTTPS 저장소만 사용할 수 있습니다. Private 저장소는 Token, 비밀번호 또는 SSH 개인키 인증을 선택하세요.
                </p>

                <div class="flex justify-end">
                    <BaseButton
                        class="w-full sm:w-auto"
                        variant="secondary"
                        :loading="testingRepository"
                        :disabled="repositoryTestDisabled"
                        @click="emit('test-repository')"
                    >
                        저장소 확인
                    </BaseButton>
                </div>

                <GitRepositoryConnectionStatus
                    :status="repositoryStatus"
                    :message="repositoryMessage"
                    :branch="repositoryBranch"
                    :commit-sha="repositoryCommitSha"
                />
            </template>
        </div>
    </article>
</template>
