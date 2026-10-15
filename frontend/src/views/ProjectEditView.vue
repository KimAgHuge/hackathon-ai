<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import {
    getDatabaseConnection,
    getGitRepositoryConnection,
    saveGitRepositoryConnection,
    saveProjectConnection,
    testDatabaseConnection,
    testGitRepositoryConnection,
} from '@/api/projectConnectionApi'
import { getProjectById, isProjectDemoMode, updateProject } from '@/api/projectApi'
import { buildProjectUpdatePayload, projectErrorMessage, projectForm as metadataForm } from '@/lib/projectManagement'
import { createProjectActionRunner, createProjectRequestScope } from '@/lib/projectRequests'
import { useAuthStore } from '@/stores/authStore'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTextarea from '@/components/base/BaseTextarea.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { DATABASE_TYPES, GIT_AUTH_TYPES, GIT_PROVIDER_TYPES, getDatabaseTypeOption } from '@/constants/projectConnectionOptions'

const route = useRoute()
const authStore = useAuthStore()
const requests = createProjectRequestScope()
const projectId = computed(() => String(route.params.projectId || ''))
const project = ref(null)
const gitConnection = ref(null)
const databaseConnection = ref(null)
const loading = ref(false)
const loadError = ref('')
const metadataError = ref('')
const metadataNotice = ref('')
const gitError = ref('')
const gitNotice = ref('')
const databaseError = ref('')
const databaseNotice = ref('')
const savingMetadata = ref(false)
const testingGit = ref(false)
const savingGit = ref(false)
const testingDatabase = ref(false)
const savingDatabase = ref(false)
const busy = computed(() => savingMetadata.value || testingGit.value || savingGit.value || testingDatabase.value || savingDatabase.value)
let gitRevision = 0
let databaseRevision = 0
const testedGit = ref(null)
const testedDatabase = ref(null)

const projectForm = reactive({ name: '', description: '', repositoryUrl: '', defaultBranch: 'main', status: 'ACTIVE' })
const gitForm = reactive({ providerType: 'AUTO', authType: 'NONE', username: '', secret: '', sshPrivateKey: '', sshPassphrase: '', knownHostsEntry: '' })
const databaseForm = reactive({ dbType: 'POSTGRESQL', host: '', port: 5432, databaseName: '', schemaName: '', username: '', password: '' })
const statusOptions = [
    { value: 'ACTIVE', label: '활성' },
    { value: 'INACTIVE', label: '비활성' },
    { value: 'ARCHIVED', label: '보관' },
]
const databaseTypeOptions = DATABASE_TYPES.filter(({ disabled }) => !disabled)
const databaseNameLabel = computed(() => getDatabaseTypeOption(databaseForm.dbType)?.databaseNameLabel || '데이터베이스명')
const connectionStatusLabel = (connection) => ({ CONNECTED: '연결됨', PENDING: '설정 대기', FAILED: '연결 실패' })[connection?.status] || '미설정'
const gitTargetChanged = computed(
    () =>
        project.value?.sourceType === 'GIT' &&
        (projectForm.repositoryUrl.trim() !== (project.value.repositoryUrl || '') || projectForm.defaultBranch.trim() !== project.value.defaultBranch)
)
const currentGitTarget = () => `${project.value?.repositoryUrl || ''}\n${project.value?.defaultBranch || ''}`
const gitTestValid = computed(() => testedGit.value?.revision === gitRevision && testedGit.value?.target === currentGitTarget())
const databaseTestValid = computed(() => testedDatabase.value === databaseRevision)
const failureMessage = (error, fallback) => error?.response?.data?.message || error?.response?.data?.detail || error?.message || fallback

watch(
    gitForm,
    () => {
        gitRevision += 1
        testedGit.value = null
        gitNotice.value = ''
    },
    { flush: 'sync' }
)
watch(
    databaseForm,
    () => {
        databaseRevision += 1
        testedDatabase.value = null
        databaseNotice.value = ''
    },
    { flush: 'sync' }
)

const baselines = reactive({ metadata: '', git: '', database: '' })
const snapshot = (value) => JSON.stringify(value)
const dirty = computed(() =>
    Boolean(
        project.value &&
        !loading.value &&
        (snapshot(projectForm) !== baselines.metadata || snapshot(gitForm) !== baselines.git || snapshot(databaseForm) !== baselines.database)
    )
)
const runAction = createProjectActionRunner({
    scope: requests,
    canEdit: () => authStore.hasPermission('PROJECT_UPDATE'),
    isReady: () => Boolean(project.value && !loading.value && project.value.id === projectId.value),
    onDenied: () => {
        metadataError.value = '프로젝트를 수정할 권한이 없습니다.'
    },
})
const action = (pending, error, execute, onSuccess) =>
    runAction({
        execute,
        onSuccess,
        onError: (failure) => {
            error.value = projectErrorMessage(failure)
        },
        onPending: (value) => {
            pending.value = value
        },
    })
const confirmNavigation = () => {
    if (busy.value) return false
    return !dirty.value || window.confirm('저장하지 않은 변경사항이 있습니다. 이동하시겠습니까?')
}
const beforeUnload = (event) => {
    if (!busy.value && !dirty.value) return
    event.preventDefault()
    event.returnValue = ''
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(confirmNavigation)
onBeforeRouteUpdate(confirmNavigation)
onBeforeUnmount(() => {
    requests.dispose()
    window.removeEventListener('beforeunload', beforeUnload)
})

const loadProject = async () => {
    const ticket = requests.open(projectId.value)
    if (!ticket) return
    loading.value = true
    loadError.value = ''
    project.value = null
    gitConnection.value = null
    databaseConnection.value = null
    for (const field of [metadataError, metadataNotice, gitError, gitNotice, databaseError, databaseNotice]) field.value = ''
    for (const field of [savingMetadata, testingGit, savingGit, testingDatabase, savingDatabase]) field.value = false
    testedGit.value = null
    testedDatabase.value = null
    // 프로젝트를 바꿀 때 이전에 입력한 비밀번호·토큰과 연결 대상이 새 프로젝트에 섞이지 않도록 비운다.
    Object.assign(gitForm, { providerType: 'AUTO', authType: 'NONE', username: '', secret: '', sshPrivateKey: '', sshPassphrase: '', knownHostsEntry: '' })
    Object.assign(databaseForm, { dbType: 'POSTGRESQL', host: '', port: 5432, databaseName: '', schemaName: '', username: '', password: '' })
    try {
        const result = await getProjectById(ticket.id, ticket.options)
        if (!requests.isCurrent(ticket)) return
        if (result.id !== ticket.id) throw new Error('프로젝트 응답이 요청한 프로젝트와 다릅니다.')
        project.value = result
        Object.assign(projectForm, metadataForm(result))
        const [gitResult, databaseResult] = await Promise.allSettled([
            result.sourceType === 'GIT' ? getGitRepositoryConnection(ticket.id, ticket.options) : Promise.resolve(null),
            getDatabaseConnection(ticket.id, ticket.options),
        ])
        if (!requests.isCurrent(ticket)) return
        if (gitResult.status === 'fulfilled') {
            gitConnection.value = gitResult.value
            if (gitResult.value)
                Object.assign(gitForm, {
                    providerType: gitResult.value.providerType || 'AUTO',
                    authType: gitResult.value.authType || 'NONE',
                    username: gitResult.value.username || '',
                })
        } else gitError.value = failureMessage(gitResult.reason, '소스 저장소 연결정보를 불러오지 못했습니다.')
        if (databaseResult.status === 'fulfilled') {
            databaseConnection.value = databaseResult.value
            if (databaseResult.value)
                Object.assign(databaseForm, {
                    dbType: databaseResult.value.dbType,
                    host: databaseResult.value.host || '',
                    port: databaseResult.value.port || 5432,
                    databaseName: databaseResult.value.databaseName || '',
                    schemaName: databaseResult.value.schemaName || '',
                    username: databaseResult.value.username || '',
                })
        } else databaseError.value = failureMessage(databaseResult.reason, '데이터베이스 연결정보를 불러오지 못했습니다.')
    } catch (error) {
        if (requests.isCurrent(ticket)) loadError.value = projectErrorMessage(error)
    } finally {
        if (requests.isCurrent(ticket)) {
            baselines.metadata = snapshot(projectForm)
            baselines.git = snapshot(gitForm)
            baselines.database = snapshot(databaseForm)
            loading.value = false
        }
    }
}

const saveMetadata = async () => {
    metadataError.value = ''
    metadataNotice.value = ''
    const changedTarget = gitTargetChanged.value
    await action(
        savingMetadata,
        metadataError,
        async (ticket) => {
            const payload = buildProjectUpdatePayload(project.value, projectForm)
            const saved = await updateProject(ticket.id, payload, ticket.options)
            if (!requests.isCurrent(ticket)) return null
            if (saved.id !== ticket.id) throw new Error('저장 응답의 프로젝트를 확인할 수 없습니다.')
            // 저장이 완료되면 서버에서 다시 조회해 실제로 저장된 값으로 화면을 갱신한다.
            const loaded = await getProjectById(ticket.id, ticket.options)
            if (loaded.id !== ticket.id) throw new Error('프로젝트 응답이 요청한 프로젝트와 다릅니다.')
            return loaded
        },
        (result) => {
            project.value = result
            Object.assign(projectForm, metadataForm(result))
            baselines.metadata = snapshot(projectForm)
            testedGit.value = null
            metadataNotice.value = changedTarget
                ? '기본정보가 저장되었습니다. 저장소 주소·브랜치를 변경했으므로 아래에서 Git 연결을 다시 테스트하고 저장하세요.'
                : '프로젝트 기본정보가 저장되었습니다.'
        }
    )
}

const gitPayload = () => ({
    repositoryUrl: project.value.repositoryUrl,
    branch: project.value.defaultBranch,
    providerType: gitForm.providerType,
    authType: gitForm.authType,
    username: gitForm.username.trim() || null,
    secret: gitForm.authType.startsWith('HTTPS_') ? gitForm.secret : null,
    sshPrivateKey: gitForm.authType === 'SSH_KEY' ? gitForm.sshPrivateKey : null,
    sshPassphrase: gitForm.authType === 'SSH_KEY' ? gitForm.sshPassphrase || null : null,
    knownHostsEntry: gitForm.authType === 'SSH_KEY' ? gitForm.knownHostsEntry : null,
})
const validateGit = () => {
    if (gitTargetChanged.value) return '저장소 주소·브랜치를 먼저 기본정보에 저장하세요.'
    if (gitForm.authType.startsWith('HTTPS_') && (!gitForm.username.trim() || !gitForm.secret)) return 'Git 사용자명과 새 토큰/비밀번호를 입력하세요.'
    if (gitForm.authType === 'SSH_KEY' && (!gitForm.sshPrivateKey.trim() || !gitForm.knownHostsEntry.trim())) {
        return '새 SSH 개인키와 known_hosts 항목을 입력하세요.'
    }
    return ''
}
const testGit = async () => {
    gitError.value = ''
    gitNotice.value = ''
    await action(
        testingGit,
        gitError,
        async (ticket) => {
            const failure = validateGit()
            if (failure) throw new Error(failure)
            const revision = gitRevision
            const target = currentGitTarget()
            const result = await testGitRepositoryConnection(ticket.id, gitPayload(), ticket.options)
            if (!result.success) throw new Error(result.message || '소스 저장소 연결에 실패했습니다.')
            if (revision !== gitRevision || target !== currentGitTarget()) throw new Error('테스트 중 입력이 바뀌었습니다. 다시 테스트하세요.')
            return { revision, target }
        },
        (result) => {
            testedGit.value = result
            gitNotice.value = '연결 테스트에 성공했습니다. 이제 Git 연결정보를 저장하세요.'
        }
    )
}
const saveGit = async () => {
    gitError.value = ''
    await action(
        savingGit,
        gitError,
        (ticket) => {
            if (!gitTestValid.value || gitTargetChanged.value) throw new Error('현재 입력값으로 Git 연결 테스트를 먼저 완료하세요.')
            return saveGitRepositoryConnection(ticket.id, gitPayload(), ticket.options)
        },
        (result) => {
            gitConnection.value = result
            gitForm.secret = ''
            gitForm.sshPrivateKey = ''
            gitForm.sshPassphrase = ''
            gitForm.knownHostsEntry = ''
            baselines.git = snapshot(gitForm)
            testedGit.value = null
            gitNotice.value = 'Git 연결정보를 저장했습니다. 새 인증정보가 적용되었습니다.'
        }
    )
}

const databasePayload = () => ({
    dbType: databaseForm.dbType,
    host: databaseForm.host.trim(),
    port: Number(databaseForm.port),
    databaseName: databaseForm.databaseName.trim(),
    schemaName: databaseForm.schemaName.trim(),
    username: databaseForm.username.trim(),
    password: databaseForm.password,
})
const testDatabase = async () => {
    databaseError.value = ''
    databaseNotice.value = ''
    await action(
        testingDatabase,
        databaseError,
        async (ticket) => {
            const port = Number(databaseForm.port)
            if (
                !databaseForm.host.trim() ||
                !databaseForm.databaseName.trim() ||
                !databaseForm.schemaName.trim() ||
                !databaseForm.username.trim() ||
                !databaseForm.password
            ) {
                throw new Error('DB 접속 정보와 새 비밀번호를 모두 입력하세요.')
            }
            if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('포트는 1~65535 사이의 정수로 입력하세요.')
            const revision = databaseRevision
            const result = await testDatabaseConnection(ticket.id, databasePayload(), ticket.options)
            if (!result.success) throw new Error(result.message || '데이터베이스 연결에 실패했습니다.')
            if (revision !== databaseRevision) throw new Error('테스트 중 입력이 바뀌었습니다. 다시 테스트하세요.')
            return revision
        },
        (revision) => {
            testedDatabase.value = revision
            databaseNotice.value = '연결 테스트에 성공했습니다. 이제 DB 연결정보를 저장하세요.'
        }
    )
}
const saveDatabase = async () => {
    databaseError.value = ''
    await action(
        savingDatabase,
        databaseError,
        (ticket) => {
            if (!databaseTestValid.value) throw new Error('현재 입력값으로 DB 연결 테스트를 먼저 완료하세요.')
            return saveProjectConnection(ticket.id, databasePayload(), ticket.options)
        },
        (result) => {
            databaseConnection.value = result
            databaseForm.password = ''
            baselines.database = snapshot(databaseForm)
            testedDatabase.value = null
            databaseNotice.value = 'DB 연결정보를 저장했습니다. 새 읽기 전용 계정 정보가 적용되었습니다.'
        }
    )
}

watch(projectId, loadProject, { immediate: true })
</script>

<template>
    <div class="min-w-0">
        <ViewHeading title="프로젝트 수정" description="프로젝트 기본정보와 Git·DB 접속 계정을 관리합니다. 서비스 로그인 계정은 여기서 변경하지 않습니다.">
            <template #actions>
                <BaseButton class="w-full sm:w-auto" variant="secondary" :disabled="busy" :to="{ name: 'project-detail', params: { projectId } }"
                    >상세로 돌아가기</BaseButton
                >
            </template>
        </ViewHeading>

        <p v-if="isProjectDemoMode" role="status" class="mb-4 rounded-lg bg-blue-50 p-3 text-sm text-blue-800">
            데모 모드입니다. 기본정보 변경은 이 브라우저에만 저장되며 연결 테스트는 모의 응답입니다.
        </p>
        <p v-if="loading" role="status" class="rounded-xl border border-slate-200 bg-white p-6 text-sm dark:border-slate-700 dark:bg-slate-900">
            프로젝트를 불러오는 중입니다.
        </p>
        <div
            v-else-if="loadError"
            role="alert"
            class="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300"
        >
            {{ loadError }}
            <BaseButton class="mt-4" variant="secondary" @click="loadProject">다시 시도</BaseButton>
        </div>

        <div v-else-if="project" class="grid min-w-0 gap-5">
            <section class="min-w-0 rounded-xl border border-slate-200 bg-white p-5 sm:p-6 dark:border-slate-700 dark:bg-slate-900">
                <h2 class="text-lg font-bold text-slate-900 dark:text-slate-100">기본정보</h2>
                <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">소속 조직과 소스 방식은 이 화면에서 변경할 수 없습니다.</p>
                <div class="mt-5 grid gap-4 md:grid-cols-2">
                    <BaseInput v-model="projectForm.name" label="프로젝트명" required :disabled="busy" maxlength="200" />
                    <BaseSelect v-model="projectForm.status" label="상태" :options="statusOptions" :disabled="busy" />
                    <div class="md:col-span-2"><BaseTextarea v-model="projectForm.description" label="설명" :disabled="busy" rows="3" /></div>
                    <div v-if="project.sourceType === 'GIT'" class="md:col-span-2">
                        <BaseInput v-model="projectForm.repositoryUrl" label="저장소 URL" required :disabled="busy" maxlength="2000" />
                    </div>
                    <BaseInput
                        v-if="project.sourceType === 'GIT'"
                        v-model="projectForm.defaultBranch"
                        label="기본 브랜치"
                        required
                        :disabled="busy"
                        maxlength="200"
                    />
                </div>
                <p v-if="gitTargetChanged" class="mt-4 text-sm text-amber-700 dark:text-amber-300">
                    저장소 주소·브랜치를 바꾸면 기존 Git 연결과 불일치할 수 있습니다. 기본정보 저장 후 아래에서 새 인증정보로 연결 테스트·저장을 완료하세요.
                </p>
                <p v-if="metadataError" role="alert" class="mt-4 text-sm text-rose-700 dark:text-rose-300">{{ metadataError }}</p>
                <p v-if="metadataNotice" role="status" class="mt-4 text-sm text-emerald-700 dark:text-emerald-300">{{ metadataNotice }}</p>
                <div class="mt-5 flex flex-col gap-2 sm:flex-row sm:justify-end">
                    <BaseButton class="w-full sm:w-auto" :loading="savingMetadata" :disabled="busy" @click="saveMetadata">기본정보 저장</BaseButton>
                </div>
            </section>

            <section
                v-if="project.sourceType === 'GIT'"
                class="min-w-0 rounded-xl border border-slate-200 bg-white p-5 sm:p-6 dark:border-slate-700 dark:bg-slate-900"
            >
                <h2 class="text-lg font-bold text-slate-900 dark:text-slate-100">Git 연결 계정</h2>
                <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">
                    마지막 저장 상태: {{ connectionStatusLabel(gitConnection) }}. 저장된 토큰·비밀번호·개인키는 다시 표시하지 않습니다. 교체하려면 새 값을
                    입력하세요.
                </p>
                <div class="mt-5 grid gap-4 md:grid-cols-2">
                    <BaseSelect v-model="gitForm.providerType" label="Git 제공자" :options="GIT_PROVIDER_TYPES" :disabled="busy" />
                    <BaseSelect v-model="gitForm.authType" label="인증 방식" :options="GIT_AUTH_TYPES" :disabled="busy" />
                    <BaseInput
                        v-if="gitForm.authType.startsWith('HTTPS_')"
                        v-model="gitForm.username"
                        label="Git 사용자명"
                        required
                        :disabled="busy"
                        autocomplete="off"
                        maxlength="200"
                    />
                    <BaseInput
                        v-if="gitForm.authType.startsWith('HTTPS_')"
                        v-model="gitForm.secret"
                        label="새 토큰 또는 비밀번호"
                        type="password"
                        required
                        :disabled="busy"
                        autocomplete="new-password"
                    />
                    <div v-if="gitForm.authType === 'SSH_KEY'" class="md:col-span-2">
                        <BaseTextarea
                            v-model="gitForm.sshPrivateKey"
                            label="새 SSH 개인키"
                            mode="code"
                            required
                            :disabled="busy"
                            rows="4"
                            autocomplete="off"
                            spellcheck="false"
                        />
                    </div>
                    <BaseInput
                        v-if="gitForm.authType === 'SSH_KEY'"
                        v-model="gitForm.sshPassphrase"
                        label="SSH 키 암호 (선택)"
                        type="password"
                        :disabled="busy"
                        autocomplete="new-password"
                    />
                    <div v-if="gitForm.authType === 'SSH_KEY'" class="md:col-span-2">
                        <BaseTextarea
                            v-model="gitForm.knownHostsEntry"
                            label="known_hosts 항목"
                            mode="code"
                            required
                            :disabled="busy"
                            rows="3"
                            autocomplete="off"
                            spellcheck="false"
                        />
                    </div>
                </div>
                <p v-if="gitError" role="alert" class="mt-4 text-sm text-rose-700 dark:text-rose-300">{{ gitError }}</p>
                <p v-if="gitNotice" role="status" class="mt-4 text-sm text-emerald-700 dark:text-emerald-300">{{ gitNotice }}</p>
                <div class="mt-5 flex flex-col gap-2 sm:flex-row sm:justify-end">
                    <BaseButton class="w-full sm:w-auto" variant="secondary" :loading="testingGit" :disabled="busy || gitTargetChanged" @click="testGit"
                        >연결 테스트</BaseButton
                    >
                    <BaseButton class="w-full sm:w-auto" :loading="savingGit" :disabled="busy || !gitTestValid || gitTargetChanged" @click="saveGit"
                        >Git 연결 저장</BaseButton
                    >
                </div>
            </section>

            <section class="min-w-0 rounded-xl border border-slate-200 bg-white p-5 sm:p-6 dark:border-slate-700 dark:bg-slate-900">
                <h2 class="text-lg font-bold text-slate-900 dark:text-slate-100">읽기 전용 DB 연결 계정</h2>
                <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">
                    마지막 저장 상태: {{ connectionStatusLabel(databaseConnection) }}. 비밀번호는 조회할 수 없으며, 변경 시 새 비밀번호를 입력해야 합니다.
                </p>
                <div class="mt-5 grid gap-4 md:grid-cols-2">
                    <BaseSelect v-model="databaseForm.dbType" label="DB 종류" :options="databaseTypeOptions" :disabled="busy" />
                    <BaseInput v-model="databaseForm.host" label="호스트" required :disabled="busy" autocomplete="off" />
                    <BaseInput v-model="databaseForm.port" label="포트" type="number" min="1" max="65535" required :disabled="busy" />
                    <BaseInput v-model="databaseForm.databaseName" :label="databaseNameLabel" required :disabled="busy" />
                    <BaseInput v-model="databaseForm.schemaName" label="스키마" required :disabled="busy" />
                    <BaseInput v-model="databaseForm.username" label="읽기 전용 계정" required :disabled="busy" autocomplete="off" />
                    <BaseInput v-model="databaseForm.password" label="새 비밀번호" type="password" required :disabled="busy" autocomplete="new-password" />
                </div>
                <p v-if="databaseError" role="alert" class="mt-4 text-sm text-rose-700 dark:text-rose-300">{{ databaseError }}</p>
                <p v-if="databaseNotice" role="status" class="mt-4 text-sm text-emerald-700 dark:text-emerald-300">{{ databaseNotice }}</p>
                <div class="mt-5 flex flex-col gap-2 sm:flex-row sm:justify-end">
                    <BaseButton class="w-full sm:w-auto" variant="secondary" :loading="testingDatabase" :disabled="busy" @click="testDatabase"
                        >연결 테스트</BaseButton
                    >
                    <BaseButton class="w-full sm:w-auto" :loading="savingDatabase" :disabled="busy || !databaseTestValid" @click="saveDatabase"
                        >DB 연결 저장</BaseButton
                    >
                </div>
            </section>
        </div>
    </div>
</template>
