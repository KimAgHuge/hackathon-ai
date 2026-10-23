import { httpClient } from '@/api/httpClient'
import { createModelApi } from '@/api/modelClient'

// 모델 관리의 실제 API만 사용한다. 예시 값으로 연결 실패를 숨기지 않는다.
export const {
    getModelRegistry,
    synchronizeModelHistory,
    getModelRuntime,
    getModelVersion,
    registerModelVersion,
    recordModelEvaluation,
    getInstalledModels,
    probeInstalledModel,
    activateInstalledModel,
    rollbackInstalledModel,
    getModelTraining,
    startModelTraining,
    cancelModelTraining,
    prepareModelAdapter,
    getTrainedModelBuild,
    startTrainedModelBuild,
    cancelTrainedModelBuild,
    importTrainedModel,
    getTrainedModelImport,
    getModelOperations,
    getModelOperation,
} = createModelApi(httpClient)
