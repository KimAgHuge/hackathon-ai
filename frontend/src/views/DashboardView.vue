<script setup>
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { getDashboard } from '@/api/appApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { riskLevelLabel } from '@/lib/riskLevel'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()
const dashboard = ref(null)
const loading = ref(false)
const error = ref('')

const metricTone = {
    blue: 'bg-blue-50 text-blue-700 dark:bg-blue-950/50 dark:text-blue-300',
    amber: 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300',
    emerald: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300',
    violet: 'bg-violet-50 text-violet-700 dark:bg-violet-950/50 dark:text-violet-300',
}

const riskStyle = {
    CRITICAL: 'bg-rose-100 text-rose-800 dark:bg-rose-950/70 dark:text-rose-200',
    HIGH: 'bg-rose-50 text-rose-700 dark:bg-rose-950/50 dark:text-rose-300',
    MEDIUM: 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300',
    LOW: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300',
    UNKNOWN: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300',
}

const recentColumns = [
    { key: 'id', label: '분석번호', width: '150px' },
    { key: 'request', label: '프로젝트 / 변경요청', minWidth: '320px' },
    { key: 'risk', label: '위험도', align: 'center', width: '130px' },
    { key: 'status', label: '상태', align: 'center', width: '120px' },
]

const fetchDashboard = async () => {
    loading.value = true
    error.value = ''

    try {
        dashboard.value = await getDashboard()
    } catch (failure) {
        dashboard.value = null
        error.value = failure.message || '대시보드를 불러오지 못했습니다.'
    } finally {
        loading.value = false
    }
}

onMounted(fetchDashboard)
</script>

<template>
    <div>
        <ViewHeading title="대시보드" description="프로젝트와 변경요청, 저장된 분석 현황을 확인합니다.">
            <template #actions>
                <BaseButton class="w-full sm:w-auto" variant="secondary" :loading="loading" @click="fetchDashboard">새로고침</BaseButton>
                <BaseButton v-if="authStore.hasPermission('PROJECT_CREATE')" class="w-full sm:w-auto" to="/projects/new"> 새 프로젝트 분석 </BaseButton>
            </template>
        </ViewHeading>

        <div
            v-if="loading && !dashboard"
            class="rounded-xl border border-slate-200 bg-white px-5 py-12 text-center text-sm text-slate-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400"
        >
            대시보드 데이터를 불러오고 있습니다.
        </div>

        <div
            v-if="error"
            role="alert"
            class="mb-4 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300"
        >
            {{ error }}
            <BaseButton class="mt-3" size="small" variant="secondary" @click="fetchDashboard">다시 시도</BaseButton>
        </div>

        <template v-if="dashboard">
            <div
                v-if="dashboard.warnings?.length"
                role="status"
                class="mb-4 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300"
            >
                일부 정보를 불러오지 못했습니다: {{ dashboard.warnings.join(' ') }} 새로고침으로 다시 시도할 수 있습니다.
            </div>

            <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                <section
                    v-for="metric in dashboard.metrics"
                    :key="metric.label"
                    class="rounded-xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-900/3 dark:border-slate-700 dark:bg-slate-900"
                >
                    <div class="text-xs font-semibold text-slate-500 dark:text-slate-400">{{ metric.label }}</div>
                    <div class="mt-3 flex items-end justify-between gap-3">
                        <strong class="text-3xl font-extrabold tracking-tight text-slate-900 dark:text-slate-100">{{ metric.value }}</strong>
                        <span class="max-w-[60%] rounded-lg px-2 py-1 text-right text-[11px] font-bold" :class="metricTone[metric.tone]">{{
                            metric.change
                        }}</span>
                    </div>
                </section>
            </div>

            <div class="mt-5 grid gap-5 xl:grid-cols-[1.45fr_0.8fr]">
                <section class="min-w-0">
                    <div class="mb-3 flex flex-wrap items-end justify-between gap-2">
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
                        :empty-text="dashboard.analysesAvailable === false ? '분석 이력을 불러오지 못했습니다.' : '저장된 분석 작업이 없습니다.'"
                        row-key="id"
                    >
                        <template #cell-id="{ row }">
                            <RouterLink
                                v-if="dashboard.mode === 'live' && row.projectId && row.jobId"
                                class="font-semibold text-blue-700 hover:underline dark:text-blue-300"
                                :to="{ name: 'analysis-progress', params: { projectId: row.projectId, analysisJobId: row.jobId } }"
                            >
                                {{ row.id }}
                            </RouterLink>
                            <span v-else class="font-semibold text-slate-600 dark:text-slate-400">{{ row.id }}</span>
                        </template>

                        <template #cell-request="{ row }">
                            <div class="font-bold text-slate-900 dark:text-slate-100">{{ row.project }}</div>
                            <div class="mt-1 text-xs text-slate-500 dark:text-slate-400">{{ row.request }}</div>
                        </template>

                        <template #cell-risk="{ row }">
                            <span
                                v-if="row.risk"
                                class="rounded-md px-2 py-1 text-xs font-extrabold"
                                :class="riskStyle[String(row.risk).toUpperCase()] || riskStyle.UNKNOWN"
                            >
                                {{ riskLevelLabel(row.risk) }}{{ row.score != null ? ` · ${row.score}` : '' }}
                            </span>
                            <span v-else class="text-xs text-slate-500 dark:text-slate-400">미산정</span>
                        </template>

                        <template #cell-status="{ value }">
                            <StatusBadge :status="value" />
                        </template>
                    </BaseTable>
                </section>

                <section class="rounded-xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-900/3 dark:border-slate-700 dark:bg-slate-900">
                    <h2 class="text-base font-extrabold text-slate-900 dark:text-slate-100">
                        {{ dashboard.mode === 'live' ? '데이터 조회 상태' : '시스템 상태' }}
                    </h2>
                    <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">
                        {{
                            dashboard.mode === 'live'
                                ? '각 목록 API의 이번 조회 결과입니다. 서버 자체의 가동 상태는 아닙니다.'
                                : '연결된 서버와 저장소 상태입니다.'
                        }}
                    </p>

                    <div class="mt-4 space-y-3">
                        <div v-for="system in dashboard.systems" :key="system.name" class="rounded-lg border border-slate-200 p-4 dark:border-slate-700">
                            <div class="flex items-start justify-between gap-3">
                                <div>
                                    <div class="text-sm font-bold text-slate-800 dark:text-slate-200">{{ system.name }}</div>
                                    <div class="mt-1 text-[11px] text-slate-500 dark:text-slate-400">{{ system.description }}</div>
                                </div>
                                <StatusBadge :status="system.status" />
                            </div>
                            <div v-if="system.latency" class="mt-3 text-[11px] font-semibold text-slate-500 dark:text-slate-400">
                                응답시간 {{ system.latency }}
                            </div>
                        </div>
                    </div>

                    <BaseButton v-if="authStore.hasPermission('PROJECT_READ')" class="mt-4" to="/projects" block variant="secondary">프로젝트 보기</BaseButton>
                </section>
            </div>
        </template>
    </div>
</template>
