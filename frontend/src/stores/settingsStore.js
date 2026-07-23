import { reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import {
    getSystemSettings,
    saveAiServerSettings,
    saveSupabaseSettings,
    saveTrainingServerSettings,
    testAiServerConnection,
    testSupabaseConnection,
    testTrainingServerConnection,
} from '@/api/settingsApi'

export const useSettingsStore = defineStore('settings', () => {
    const settings = ref(null)
    const loading = ref(false)
    const notification = ref(null)

    const actions = reactive({
        aiServer: { testing: false, saving: false },
        trainingServer: { testing: false, saving: false },
        supabase: { testing: false, saving: false },
    })

    const notify = (message, type = 'success') => {
        notification.value = {
            message,
            type,
            key: Date.now(),
        }
    }

    const loadSettings = async () => {
        loading.value = true

        try {
            settings.value = await getSystemSettings()
        } catch (exception) {
            notify(exception instanceof Error ? exception.message : '설정 정보를 불러오지 못했습니다.', 'error')
        } finally {
            loading.value = false
        }
    }

    const applyTestResult = (section, result) => {
        settings.value[section].status = result.status
        settings.value[section].lastCheckedAt = result.checkedAt
        settings.value[section].latencyMs = result.latencyMs
        notify(result.message, result.success ? 'success' : 'warning')
    }

    const testAiServer = async () => {
        actions.aiServer.testing = true

        try {
            applyTestResult('aiServer', await testAiServerConnection(settings.value.aiServer))
        } catch (exception) {
            notify(exception instanceof Error ? exception.message : '연결 테스트에 실패했습니다.', 'error')
        } finally {
            actions.aiServer.testing = false
        }
    }

    const saveAiServer = async () => {
        actions.aiServer.saving = true

        try {
            settings.value.aiServer = await saveAiServerSettings(settings.value.aiServer)
            notify('AI 추론 서버 설정을 저장했습니다.')
        } catch (exception) {
            notify(exception instanceof Error ? exception.message : '설정 저장에 실패했습니다.', 'error')
        } finally {
            actions.aiServer.saving = false
        }
    }

    const testTrainingServer = async () => {
        actions.trainingServer.testing = true

        try {
            applyTestResult('trainingServer', await testTrainingServerConnection(settings.value.trainingServer))
        } catch (exception) {
            notify(exception instanceof Error ? exception.message : '연결 테스트에 실패했습니다.', 'error')
        } finally {
            actions.trainingServer.testing = false
        }
    }

    const saveTrainingServer = async () => {
        actions.trainingServer.saving = true

        try {
            settings.value.trainingServer = await saveTrainingServerSettings(settings.value.trainingServer)
            notify('학습 서버 설정을 저장했습니다.')
        } catch (exception) {
            notify(exception instanceof Error ? exception.message : '설정 저장에 실패했습니다.', 'error')
        } finally {
            actions.trainingServer.saving = false
        }
    }

    const testSupabase = async () => {
        actions.supabase.testing = true

        try {
            applyTestResult('supabase', await testSupabaseConnection(settings.value.supabase))
        } catch (exception) {
            notify(exception instanceof Error ? exception.message : '연결 테스트에 실패했습니다.', 'error')
        } finally {
            actions.supabase.testing = false
        }
    }

    const saveSupabase = async () => {
        actions.supabase.saving = true

        try {
            settings.value.supabase = await saveSupabaseSettings(settings.value.supabase)
            notify('Supabase 설정을 저장했습니다.')
        } catch (exception) {
            notify(exception instanceof Error ? exception.message : '설정 저장에 실패했습니다.', 'error')
        } finally {
            actions.supabase.saving = false
        }
    }

    return {
        settings,
        loading,
        actions,
        notification,
        loadSettings,
        testAiServer,
        saveAiServer,
        testTrainingServer,
        saveTrainingServer,
        testSupabase,
        saveSupabase,
    }
})
