import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
    getProject,
    getAnalysisProgress,
    getArchitecture,
    getImpactAnalysis,
} from '@/api/projectApi'

export const useProjectStore = defineStore('project', () => {
    const project = ref(null)
    const progress = ref(null)
    const architecture = ref(null)
    const impact = ref(null)
    const loading = ref(false)
    const error = ref(null)

    const run = async action => {
        loading.value = true
        error.value = null

        try {
            return await action()
        } catch (e) {
            error.value = e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.'
            throw e
        } finally {
            loading.value = false
        }
    }

    const loadProject = () => run(async () => {
        project.value = await getProject()
    })

    const loadProgress = () => run(async () => {
        progress.value = await getAnalysisProgress()
    })

    const loadArchitecture = () => run(async () => {
        architecture.value = await getArchitecture()
    })

    const loadImpact = () => run(async () => {
        impact.value = await getImpactAnalysis()
    })

    return {
        project,
        progress,
        architecture,
        impact,
        loading,
        error,
        loadProject,
        loadProgress,
        loadArchitecture,
        loadImpact,
    }
})
