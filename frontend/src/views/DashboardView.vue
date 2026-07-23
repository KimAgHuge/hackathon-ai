<script setup>
import { onMounted, ref } from 'vue'
import { getDashboard } from '@/api/appApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()
const dashboard = ref(null)
const loading = ref(false)

const metricTone = {
    blue: 'bg-blue-50 text-blue-700 dark:bg-blue-950/50 dark:text-blue-300',
    amber: 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300',
    emerald: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300',
    violet: 'bg-violet-50 text-violet-700 dark:bg-violet-950/50 dark:text-violet-300',
}

const riskStyle = {
    HIGH: 'bg-rose-50 text-rose-700 dark:bg-rose-950/50 dark:text-rose-300',
    MEDIUM: 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300',
    LOW: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300',
}

const recentColumns = [
    { key: 'id', label: '분석번호', width: '150px' },
    { key: 'request', label: '프로젝트 / 변경요청', minWidth: '320px' },
    { key: 'risk', label: '위험도', align: 'center', width: '130px' },
    { key: 'status', label: '상태', align: 'center', width: '120px' },
]

const fetchDashboard = async () => {
    loading.value = true

    try {
        dashboard.value = await getDashboard()
    } finally {
        loading.value = false
    }
}

onMounted(fetchDashboard)
</script>

<template>
    <div>
        <ViewHeading title="대시보드" description="프로젝트 분석 현황과 시스템 연결 상태를 확인합니다.">
            <template #actions>
                <BaseButton
                    v-if="authStore.hasPermission('PROJECT_CREATE')"
                    to="/projects/new"
                >
                    새 프로젝트 분석
                </BaseButton>
            </template>
        </ViewHeading>

        <template v-if="dashboard">
            <div class="grid grid-cols-4 gap-4">
                <section
                    v-for="metric in dashboard.metrics"
                    :key="metric.label"
                    class="rounded-xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-900/3 dark:border-slate-700 dark:bg-slate-900"
                >
                    <div class="text-xs font-semibold text-slate-500 dark:text-slate-400">{{ metric.label }}</div>
                    <div class="mt-3 flex items-end justify-between gap-3">
                        <strong class="text-3xl font-extrabold tracking-tight text-slate-900 dark:text-slate-100">{{ metric.value }}</strong>
                        <span class="rounded-lg px-2 py-1 text-[11px] font-bold" :class="metricTone[metric.tone]">{{ metric.change }}</span>
                    </div>
                </section>
            </div>

            <div class="mt-5 grid grid-cols-[1.45fr_0.8fr] gap-5">
                <section>
                    <div class="mb-3 flex items-end justify-between">
                        <div>
                            <h2 class="text-base font-extrabold text-slate-900 dark:text-slate-100">최근 분석</h2>
                            <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">최근 수행된 변경 영향도 분석입니다.</p>
                        </div>
                        <BaseButton to="/analysis-history" size="small" variant="ghost">전체 보기 →</BaseButton>
                    </div>

                    <BaseTable
                        :columns="recentColumns"
                        :rows="dashboard.recentAnalyses"
                        :loading="loading"
                        row-key="id"
                    >
                        <template #cell-id="{ value }">
                            <span class="font-semibold text-slate-600 dark:text-slate-400">{{ value }}</span>
                        </template>

                        <template #cell-request="{ row }">
                            <div class="font-bold text-slate-900 dark:text-slate-100">{{ row.project }}</div>
                            <div class="mt-1 text-xs text-slate-500 dark:text-slate-400">{{ row.request }}</div>
                        </template>

                        <template #cell-risk="{ row }">
                            <span class="rounded-md px-2 py-1 text-xs font-extrabold" :class="riskStyle[row.risk]">
                                {{ row.risk }} · {{ row.score }}
                            </span>
                        </template>

                        <template #cell-status="{ value }">
                            <StatusBadge :status="value" />
                        </template>
                    </BaseTable>
                </section>

                <section class="rounded-xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-900/3 dark:border-slate-700 dark:bg-slate-900">
                    <h2 class="text-base font-extrabold text-slate-900 dark:text-slate-100">시스템 상태</h2>
                    <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">연결된 서버와 저장소 상태입니다.</p>

                    <div class="mt-4 space-y-3">
                        <div v-for="system in dashboard.systems" :key="system.name" class="rounded-lg border border-slate-200 p-4 dark:border-slate-700">
                            <div class="flex items-start justify-between gap-3">
                                <div>
                                    <div class="text-sm font-bold text-slate-800 dark:text-slate-200">{{ system.name }}</div>
                                    <div class="mt-1 text-[11px] text-slate-500 dark:text-slate-400">{{ system.description }}</div>
                                </div>
                                <StatusBadge :status="system.status" />
                            </div>
                            <div class="mt-3 text-[11px] font-semibold text-slate-500 dark:text-slate-400">응답시간 {{ system.latency }}</div>
                        </div>
                    </div>

                    <BaseButton class="mt-4" to="/settings" block variant="secondary">
                        연결 설정 관리
                    </BaseButton>
                </section>
            </div>
        </template>
    </div>
</template>
