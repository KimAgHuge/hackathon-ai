<script setup>
import { computed, onMounted, ref } from 'vue'
import { getModels } from '@/api/appApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()
const models = ref(null)
const loading = ref(false)

const columns = computed(() => [
    { key: 'version', label: '버전', width: '120px', sortable: true },
    { key: 'adapter', label: 'Adapter', minWidth: '240px' },
    { key: 'dataset', label: '학습 데이터셋', minWidth: '220px' },
    { key: 'accuracy', label: '정확도', align: 'center', width: '110px', sortable: true },
    { key: 'status', label: '상태', align: 'center', width: '120px' },
    { key: 'createdAt', label: '생성일', align: 'center', width: '130px', sortable: true },
    {
        key: 'actions',
        label: '관리',
        align: 'center',
        width: '120px',
        headerClass: 'sticky right-0 z-20 border-l border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800',
        cellClass: 'sticky right-0 z-10 border-l border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900',
        visible: authStore.hasPermission('MODEL_DEPLOY'),
    },
])

const fetchModels = async () => {
    loading.value = true

    try {
        models.value = await getModels()
    } finally {
        loading.value = false
    }
}

const deployModel = (model) => {
    console.log('모델 배포', model)
}

onMounted(fetchModels)
</script>

<template>
    <div>
        <ViewHeading title="AI 모델 관리" description="모델 버전, LoRA 어댑터, 평가 결과와 배포 상태를 관리합니다.">
            <template #actions>
                <div class="flex flex-col gap-2 sm:flex-row">
                    <BaseButton v-if="authStore.hasPermission('MODEL_TRAIN')" class="w-full sm:w-auto" variant="secondary"> 학습 작업 생성 </BaseButton>
                    <BaseButton v-if="authStore.hasPermission('MODEL_DEPLOY')" class="w-full sm:w-auto"> 새 모델 등록 </BaseButton>
                </div>
            </template>
        </ViewHeading>

        <template v-if="models">
            <section
                class="overflow-hidden rounded-xl border border-blue-200 bg-gradient-to-r from-[#071a38] to-[#0f4ca3] text-white shadow-lg shadow-blue-950/10"
            >
                <div class="grid lg:grid-cols-[1.25fr_0.75fr]">
                    <div class="p-6">
                        <div class="text-[11px] font-extrabold tracking-[0.14em] text-blue-200">CURRENT ACTIVE MODEL</div>
                        <div class="mt-3 flex flex-wrap items-center gap-3">
                            <h2 class="text-2xl font-extrabold">{{ models.active.name }}</h2>
                            <StatusBadge :status="models.active.status" />
                        </div>
                        <div class="mt-5 grid gap-x-8 gap-y-4 text-xs sm:grid-cols-2">
                            <div>
                                <div class="text-blue-200">기반 모델</div>
                                <div class="mt-1 font-bold">{{ models.active.baseModel }}</div>
                            </div>
                            <div>
                                <div class="text-blue-200">LoRA Adapter</div>
                                <div class="mt-1 font-bold">{{ models.active.adapter }}</div>
                            </div>
                            <div>
                                <div class="text-blue-200">배포 서버</div>
                                <div class="mt-1 font-bold">{{ models.active.server }}</div>
                            </div>
                            <div>
                                <div class="text-blue-200">배포 일시</div>
                                <div class="mt-1 font-bold">{{ models.active.deployedAt }}</div>
                            </div>
                        </div>
                    </div>
                    <div class="grid place-items-center border-l border-white/15 bg-white/5 p-6">
                        <div class="text-center">
                            <div class="text-sm font-semibold text-blue-100">검증 정확도</div>
                            <div class="mt-2 text-5xl font-black">{{ models.active.accuracy }}%</div>
                            <BaseButton class="mt-5 border-white/30 text-white hover:bg-white/10" variant="ghost"> 배포 상태 확인 </BaseButton>
                        </div>
                    </div>
                </div>
            </section>

            <div class="mt-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                <section
                    v-for="item in models.evaluations"
                    :key="item.label"
                    class="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900"
                >
                    <div class="text-xs font-semibold text-slate-500 dark:text-slate-400">{{ item.label }}</div>
                    <div class="mt-3 flex items-end justify-between">
                        <strong class="text-2xl font-extrabold text-slate-900 dark:text-slate-100">{{ item.value }}%</strong>
                        <span class="text-xs font-extrabold text-emerald-600">{{ item.delta }}</span>
                    </div>
                </section>
            </div>

            <div class="mt-5">
                <div class="mb-3">
                    <h2 class="text-base font-extrabold text-slate-900 dark:text-slate-100">모델 버전</h2>
                    <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">승인된 모델만 Mac Studio 추론 서버에 배포할 수 있습니다.</p>
                </div>

                <BaseTable :columns="columns" :rows="models.versions" :loading="loading" row-key="version">
                    <template #cell-version="{ value }">
                        <span class="font-extrabold text-slate-900 dark:text-slate-100">{{ value }}</span>
                    </template>

                    <template #cell-adapter="{ value }">
                        <code class="rounded bg-slate-100 px-2 py-1 text-[11px] dark:bg-slate-800">{{ value }}</code>
                    </template>

                    <template #cell-accuracy="{ value }">
                        <span class="font-extrabold">{{ value }}%</span>
                    </template>

                    <template #cell-status="{ value }">
                        <StatusBadge :status="value" />
                    </template>

                    <template #cell-actions="{ row }">
                        <BaseButton v-if="row.status === 'STANDBY'" size="small" @click="deployModel(row)"> 배포 </BaseButton>
                        <BaseButton v-else size="small" variant="secondary"> 상세 </BaseButton>
                    </template>
                </BaseTable>
            </div>
        </template>
    </div>
</template>
