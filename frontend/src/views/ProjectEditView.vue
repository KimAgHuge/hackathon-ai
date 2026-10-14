<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
    getDatabaseConnection,
    getGitRepositoryConnection,
    saveGitRepositoryConnection,
    saveProjectConnection,
    testDatabaseConnection,
    testGitRepositoryConnection,
} from '@/api/projectConnectionApi'
import { getProjectById, updateProject } from '@/api/projectApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTextarea from '@/components/base/BaseTextarea.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { DATABASE_TYPES, GIT_AUTH_TYPES, GIT_PROVIDER_TYPES, getDatabaseTypeOption } from '@/constants/projectConnectionOptions'

const route = useRoute()
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
let loadSequence = 0
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

watch(gitForm, () => {
    gitRevision += 1
    testedGit.value = null
    gitNotice.value = ''
})
watch(databaseForm, () => {
    databaseRevision += 1
    testedDatabase.value = null
    databaseNotice.value = ''
})

const loadProject = async () => {
    const sequence = ++loadSequence
    loading.value = true
    loadError.value = ''
    project.value = null
    gitConnection.value = null
    databaseConnection.value = null
    try {
        const result = await getProjectById(projectId.value)
        if (sequence !== loadSequence) return
        project.value = result
        Object.assign(projectForm, {
            name: result.name || '',
            description: result.description || '',
            repositoryUrl: result.repositoryUrl || '',
            defaultBranch: result.defaultBranch || 'main',
            status: statusOptions.some(({ value }) => value === result.status) ? result.status : 'ACTIVE',
        })
        const [gitResult, databaseResult] = await Promise.allSettled([
            result.sourceType === 'GIT' ? getGitRepositoryConnection(projectId.value) : Promise.resolve(null),
            getDatabaseConnection(projectId.value),
        ])
        if (sequence !== loadSequence) return
        if (gitResult.status === 'fulfilled') {
            gitConnection.value = gitResult.value
            if (gitResult.value) {
                Object.assign(gitForm, {
                    providerType: gitResult.value.providerType || 'AUTO',
                    authType: gitResult.value.authType || 'NONE',
                    username: gitResult.value.username || '',
                })
            }
        } else gitError.value = failureMessage(gitResult.reason, 'Git 연결정보를 불러오지 못했습니다.')
        if (databaseResult.status === 'fulfilled') {
            databaseConnection.value = databaseResult.value
            if (databaseResult.value) {
                Object.assign(databaseForm, {
                    dbType: databaseResult.value.dbType,
                    host: databaseResult.value.host || '',
                    port: databaseResult.value.port || 5432,
                    databaseName: databaseResult.value.databaseName || '',
                    schemaName: databaseResult.value.schemaName || '',
                    username: databaseResult.value.username || '',
                })
            }
        } else databaseError.value = failureMessage(databaseResult.reason, 'DB 연결정보를 불러오지 못했습니다.')
    } catch (error) {
        if (sequence === loadSequence) loadError.value = failureMessage(error, '프로젝트를 불러오지 못했습니다.')
    } finally {
        if (sequence === loadSequence) loading.value = false
    }
}

const saveMetadata = async () => {
    metadataError.value = ''
    metadataNotice.value = ''
    if (!projectForm.name.trim() || !projectForm.defaultBranch.trim() || (project.value.sourceType === 'GIT' && !projectForm.repositoryUrl.trim())) {
        metadataError.value = '프로젝트명, 저장소 주소와 브랜치(해당하는 경우)를 확인하세요.'
        return
    }
    savingMetadata.value = true
    try {
        const changedTarget = gitTargetChanged.value
        const result = await updateProject(projectId.value, {
            organizationId: project.value.organizationId,
            name: projectForm.name.trim(),
            description: projectForm.description.trim() || null,
            repositoryUrl: project.value.sourceType === 'GIT' ? projectForm.repositoryUrl.trim() : project.value.repositoryUrl,
            defaultBranch: projectForm.defaultBranch.trim(),
            sourceType: project.value.sourceType,
            status: projectForm.status,
        })
        project.value = result
        testedGit.value = null
        metadataNotice.value = changedTarget
            ? '기본정보가 저장되었습니다. 저장소 주소·브랜치를 변경했으므로 아래에서 Git 연결을 다시 테스트하고 저장하세요.'
            : '프로젝트 기본정보가 저장되었습니다.'
    } catch (error) {
        metadataError.value = failureMessage(error, '프로젝트 기본정보를 저장하지 못했습니다.')
    } finally {
        savingMetadata.value = false
    }
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
    gitError.value = validateGit()
    gitNotice.value = ''
    if (gitError.value) return
    const revision = gitRevision
    const target = currentGitTarget()
    testingGit.value = true
    try {
        const result = await testGitRepositoryConnection(projectId.value, gitPayload())
        if (!result.success) throw new Error(result.message || 'Git 연결에 실패했습니다.')
        if (revision !== gitRevision || target !== currentGitTarget()) throw new Error('테스트 중 입력이 바뀌었습니다. 다시 테스트하세요.')
        testedGit.value = { revision, target }
        gitNotice.value = '연결 테스트에 성공했습니다. 이제 Git 연결정보를 저장하세요.'
    } catch (error) {
        gitError.value = failureMessage(error, 'Git 연결 테스트에 실패했습니다.')
    } finally {
        testingGit.value = false
    }
}
const saveGit = async () => {
    gitError.value = ''
    if (!gitTestValid.value || gitTargetChanged.value) {
        gitError.value = '현재 입력값으로 Git 연결 테스트를 먼저 완료하세요.'
        return
    }
    savingGit.value = true
    try {
        gitConnection.value = await saveGitRepositoryConnection(projectId.value, gitPayload())
        gitNotice.value = 'Git 연결정보를 저장했습니다. 새 인증정보가 적용되었습니다.'
        gitForm.secret = ''
        gitForm.sshPrivateKey = ''
        gitForm.sshPassphrase = ''
        gitForm.knownHostsEntry = ''
        testedGit.value = null
    } catch (error) {
        gitError.value = failureMessage(error, 'Git 연결정보를 저장하지 못했습니다.')
    } finally {
        savingGit.value = false
    }
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
    if (
        !databaseForm.host.trim() ||
        !databaseForm.databaseName.trim() ||
        !databaseForm.schemaName.trim() ||
        !databaseForm.username.trim() ||
        !databaseForm.password
    ) {
        databaseError.value = 'DB 접속 정보와 새 비밀번호를 모두 입력하세요.'
        return
    }
    const revision = databaseRevision
    testingDatabase.value = true
    try {
        const result = await testDatabaseConnection(projectId.value, databasePayload())
        if (!result.success) throw new Error(result.message || 'DB 연결에 실패했습니다.')
        if (revision !== databaseRevision) throw new Error('테스트 중 입력이 바뀌었습니다. 다시 테스트하세요.')
        testedDatabase.value = revision
        databaseNotice.value = '연결 테스트에 성공했습니다. 이제 DB 연결정보를 저장하세요.'
    } catch (error) {
        databaseError.value = failureMessage(error, 'DB 연결 테스트에 실패했습니다.')
    } finally {
        testingDatabase.value = false
    }
}
const saveDatabase = async () => {
    databaseError.value = ''
    if (!databaseTestValid.value) {
        databaseError.value = '현재 입력값으로 DB 연결 테스트를 먼저 완료하세요.'
        return
    }
    savingDatabase.value = true
    try {
        databaseConnection.value = await saveProjectConnection(projectId.value, databasePayload())
        databaseForm.password = ''
        testedDatabase.value = null
        databaseNotice.value = 'DB 연결정보를 저장했습니다. 새 읽기 전용 계정 정보가 적용되었습니다.'
    } catch (error) {
        databaseError.value = failureMessage(error, 'DB 연결정보를 저장하지 못했습니다.')
    } finally {
        savingDatabase.value = false
    }
}

watch(projectId, loadProject, { immediate: true })
</script>

<template>
    <div class="min-w-0">
        <ViewHeading title="프로젝트 수정" description="프로젝트 기본정보와 Git·DB 접속 계정을 관리합니다. 서비스 로그인 계정은 여기서 변경하지 않습니다.">
            <template #actions>
                <BaseButton class="w-full sm:w-auto" variant="secondary" :to="{ name: 'project-detail', params: { projectId } }">상세로 돌아가기</BaseButton>
            </template>
        </ViewHeading>

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
                        <BaseTextarea v-model="gitForm.sshPrivateKey" label="새 SSH 개인키" required :disabled="busy" rows="4" autocomplete="off" />
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
                        <BaseTextarea v-model="gitForm.knownHostsEntry" label="known_hosts 항목" required :disabled="busy" rows="3" autocomplete="off" />
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
