<script setup>
import { computed } from 'vue'
import BaseButton from '@/components/base/BaseButton.vue'
import DatabaseConnectionStatus from './DatabaseConnectionStatus.vue'
import DatabaseSourceForm from './DatabaseSourceForm.vue'
import ProjectSourceForm from './ProjectSourceForm.vue'

const props = defineProps({
    project: {
        type: Object,
        required: true,
    },
    repositoryConnection: {
        type: Object,
        required: true,
    },
    databaseSource: {
        type: Object,
        required: true,
    },
    connectionStatus: {
        type: String,
        default: 'IDLE',
    },
    connectionMessage: {
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
    saving: {
        type: Boolean,
        default: false,
    },
    testingRepository: {
        type: Boolean,
        default: false,
    },
    testingConnection: {
        type: Boolean,
        default: false,
    },
    startingAnalysis: {
        type: Boolean,
        default: false,
    },
})

const emit = defineEmits(['update:project', 'update:repositoryConnection', 'update:databaseSource', 'save', 'test-repository', 'test-connection', 'next'])

const projectValid = computed(() =>
    Boolean(props.project.name.trim() && props.project.sourceType === 'GIT' && props.project.repositoryUrl.trim() && props.project.defaultBranch.trim())
)

const databaseValid = computed(() =>
    Boolean(
        props.databaseSource.connectionMode === 'DB_CONNECTION' &&
        props.databaseSource.dbType &&
        props.databaseSource.host.trim() &&
        Number(props.databaseSource.port) > 0 &&
        props.databaseSource.databaseName.trim() &&
        props.databaseSource.schemaName.trim() &&
        props.databaseSource.username.trim() &&
        props.databaseSource.password
    )
)

const repositoryCredentialsValid = computed(() => {
    const connection = props.repositoryConnection

    if (connection.authType === 'NONE') {
        return true
    }

    if (connection.authType === 'HTTPS_TOKEN' || connection.authType === 'HTTPS_BASIC') {
        return Boolean(connection.username.trim() && connection.secret)
    }

    if (connection.authType === 'SSH_KEY') {
        return Boolean(connection.sshPrivateKey.trim() && connection.knownHostsEntry.trim())
    }

    return false
})

const updateProject = (project) => {
    emit('update:project', project)
}

const updateDatabaseSource = (databaseSource) => {
    emit('update:databaseSource', databaseSource)
}

const updateRepositoryConnection = (repositoryConnection) => {
    emit('update:repositoryConnection', repositoryConnection)
}
</script>

<template>
    <section>
        <div class="grid gap-5 xl:grid-cols-2">
            <ProjectSourceForm
                :model-value="project"
                :connection-model-value="repositoryConnection"
                :repository-status="repositoryStatus"
                :repository-message="repositoryMessage"
                :repository-branch="repositoryBranch"
                :repository-commit-sha="repositoryCommitSha"
                :testing-repository="testingRepository"
                :repository-test-disabled="!projectValid || !repositoryCredentialsValid || saving || testingConnection || startingAnalysis"
                @update:model-value="updateProject"
                @update:connection-model-value="updateRepositoryConnection"
                @test-repository="emit('test-repository')"
            />
            <div>
                <DatabaseSourceForm
                    :model-value="databaseSource"
                    :testing-connection="testingConnection"
                    :test-disabled="repositoryStatus !== 'SUCCESS' || !databaseValid"
                    @update:model-value="updateDatabaseSource"
                    @test-connection="emit('test-connection')"
                />
                <DatabaseConnectionStatus
                    :status="connectionStatus"
                    :message="connectionMessage"
                    :database-product="databaseProduct"
                    :database-version="databaseVersion"
                />
            </div>
        </div>

        <div class="mt-6 flex flex-wrap justify-end gap-3">
            <BaseButton
                variant="secondary"
                :loading="saving"
                :disabled="!projectValid || testingRepository || testingConnection || startingAnalysis"
                @click="emit('save')"
            >
                프로젝트 임시 저장
            </BaseButton>
            <BaseButton
                :loading="startingAnalysis"
                :disabled="repositoryStatus !== 'SUCCESS' || connectionStatus !== 'SUCCESS' || saving || testingRepository || testingConnection"
                @click="emit('next')"
            >
                분석 시작
            </BaseButton>
        </div>
    </section>
</template>
