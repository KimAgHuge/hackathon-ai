<script setup>
import { onMounted, ref } from 'vue'
import { useSettingsStore } from '@/stores/settingsStore'
import { useThemeStore } from '@/stores/themeStore'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'

const store = useSettingsStore()
const themeStore = useThemeStore()
const activeTab = ref('connections')

const tabs = [
    { id: 'connections', label: '서버 연결' },
    { id: 'analysis', label: '분석 설정' },
    { id: 'security', label: '보안 및 운영' },
]

onMounted(() => {
    store.loadSettings()
})
</script>

<template>
    <div>
        <ViewHeading title="설정" description="AI 서버, 학습 서버 및 외부 데이터 저장소 연결을 관리합니다." />

        <div class="mb-5 flex border-b border-slate-200 dark:border-slate-700">
            <button
                v-for="tab in tabs"
                :key="tab.id"
                class="-mb-px border-b-2 px-5 py-3 text-sm font-bold"
                :class="activeTab === tab.id ? 'border-blue-600 text-blue-700' : 'border-transparent text-slate-500'"
                @click="activeTab = tab.id"
            >
                {{ tab.label }}
            </button>
        </div>

        <div
            v-if="store.notification"
            class="mb-4 rounded-lg border px-4 py-3 text-sm font-semibold"
            :class="{
                'border-emerald-200 bg-emerald-50 text-emerald-700': store.notification.type === 'success',
                'border-amber-200 bg-amber-50 text-amber-700': store.notification.type === 'warning',
                'border-rose-200 bg-rose-50 text-rose-700': store.notification.type === 'error',
            }"
        >
            {{ store.notification.message }}
        </div>

        <template v-if="activeTab === 'connections' && store.settings">
            <section class="mb-4 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-sm shadow-slate-900/3">
                <div class="flex items-start justify-between border-b border-slate-100 dark:border-slate-800 px-6 py-5">
                    <div>
                        <div class="flex items-center gap-3">
                            <h2 class="text-lg font-extrabold text-slate-900 dark:text-slate-100">AI 추론 서버</h2>
                            <StatusBadge :status="store.settings.aiServer.status" />
                        </div>
                        <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">Mac Studio에서 코드·DB 분석과 로컬 모델 추론을 수행합니다.</p>
                    </div>
                    <div class="text-right text-[11px] text-slate-500 dark:text-slate-400">
                        <div>마지막 확인 {{ store.settings.aiServer.lastCheckedAt }}</div>
                        <div class="mt-1 font-bold text-slate-700 dark:text-slate-300">응답 {{ store.settings.aiServer.latencyMs ?? '-' }}ms</div>
                    </div>
                </div>

                <div class="grid grid-cols-2 gap-4 p-6">
                    <div class="form-group">
                        <label>서버 이름</label>
                        <input v-model="store.settings.aiServer.serverName" />
                    </div>
                    <div class="form-group">
                        <label>Base URL</label>
                        <input v-model="store.settings.aiServer.baseUrl" />
                    </div>
                    <div class="form-group">
                        <label>Health Check 경로</label>
                        <input v-model="store.settings.aiServer.healthPath" />
                    </div>
                    <div class="form-group">
                        <label>분석 API 경로</label>
                        <input v-model="store.settings.aiServer.analysisPath" />
                    </div>
                    <div class="form-group">
                        <label>요청 제한 시간(초)</label>
                        <input v-model.number="store.settings.aiServer.timeoutSeconds" type="number" />
                    </div>
                    <div class="form-group">
                        <label>내부 API Key</label>
                        <input v-model="store.settings.aiServer.internalApiKey" type="password" :placeholder="store.settings.aiServer.apiKeyConfigured ? '기존 키가 설정되어 있습니다' : '새 키 입력'" />
                    </div>
                </div>

                <div class="flex justify-end gap-2 border-t border-slate-100 dark:border-slate-800 px-6 py-4">
                    <button class="button secondary" :disabled="store.actions.aiServer.testing" @click="store.testAiServer">
                        {{ store.actions.aiServer.testing ? '연결 확인 중...' : '연결 테스트' }}
                    </button>
                    <button class="button primary" :disabled="store.actions.aiServer.saving" @click="store.saveAiServer">
                        {{ store.actions.aiServer.saving ? '저장 중...' : '설정 저장' }}
                    </button>
                </div>
            </section>

            <section class="mb-4 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-sm shadow-slate-900/3">
                <div class="flex items-start justify-between border-b border-slate-100 dark:border-slate-800 px-6 py-5">
                    <div>
                        <div class="flex items-center gap-3">
                            <h2 class="text-lg font-extrabold text-slate-900 dark:text-slate-100">학습 서버</h2>
                            <StatusBadge :status="store.settings.trainingServer.status" />
                        </div>
                        <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">M4 Max MacBook에서 데이터셋 생성, LoRA 학습과 평가를 수행합니다.</p>
                    </div>
                    <div class="text-right text-[11px] text-slate-500 dark:text-slate-400">
                        <div>마지막 확인 {{ store.settings.trainingServer.lastCheckedAt }}</div>
                    </div>
                </div>

                <div v-if="store.settings.trainingServer.status === 'OFFLINE'" class="mx-6 mt-5 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-700">
                    학습 서버가 오프라인입니다. AI 분석 기능은 정상적으로 사용할 수 있으며, 모델 학습과 업데이트만 제한됩니다.
                </div>

                <div class="grid grid-cols-2 gap-4 p-6">
                    <div class="form-group">
                        <label>서버 이름</label>
                        <input v-model="store.settings.trainingServer.serverName" />
                    </div>
                    <div class="grid grid-cols-[1fr_120px] gap-3">
                        <div class="form-group">
                            <label>Host</label>
                            <input v-model="store.settings.trainingServer.host" />
                        </div>
                        <div class="form-group">
                            <label>SSH 포트</label>
                            <input v-model.number="store.settings.trainingServer.sshPort" type="number" />
                        </div>
                    </div>
                    <div class="form-group">
                        <label>SSH 사용자</label>
                        <input v-model="store.settings.trainingServer.sshUser" />
                    </div>
                    <div class="form-group">
                        <label>비밀값 참조</label>
                        <input v-model="store.settings.trainingServer.secretReference" />
                    </div>
                    <div class="form-group">
                        <label>학습 작업 경로</label>
                        <input v-model="store.settings.trainingServer.workspacePath" />
                    </div>
                    <div class="form-group">
                        <label>Adapter 출력 경로</label>
                        <input v-model="store.settings.trainingServer.adapterOutputPath" />
                    </div>
                </div>

                <div class="flex justify-end gap-2 border-t border-slate-100 dark:border-slate-800 px-6 py-4">
                    <button class="button secondary" :disabled="store.actions.trainingServer.testing" @click="store.testTrainingServer">
                        {{ store.actions.trainingServer.testing ? '연결 확인 중...' : '연결 테스트' }}
                    </button>
                    <button class="button primary" :disabled="store.actions.trainingServer.saving" @click="store.saveTrainingServer">
                        {{ store.actions.trainingServer.saving ? '저장 중...' : '설정 저장' }}
                    </button>
                </div>
            </section>

            <section class="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-sm shadow-slate-900/3">
                <div class="flex items-start justify-between border-b border-slate-100 dark:border-slate-800 px-6 py-5">
                    <div>
                        <div class="flex items-center gap-3">
                            <h2 class="text-lg font-extrabold text-slate-900 dark:text-slate-100">Supabase</h2>
                            <StatusBadge :status="store.settings.supabase.status" />
                        </div>
                        <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">사용자 인증, 프로젝트 데이터, 분석 결과와 벡터 인덱스를 저장합니다.</p>
                    </div>
                    <div class="text-right text-[11px] text-slate-500 dark:text-slate-400">
                        <div>마지막 확인 {{ store.settings.supabase.lastCheckedAt }}</div>
                        <div class="mt-1 font-bold text-slate-700 dark:text-slate-300">응답 {{ store.settings.supabase.latencyMs ?? '-' }}ms</div>
                    </div>
                </div>

                <div class="grid grid-cols-2 gap-4 p-6">
                    <div class="form-group col-span-2">
                        <label>Project URL</label>
                        <input v-model="store.settings.supabase.projectUrl" />
                    </div>
                    <div class="form-group">
                        <label>Publishable Key</label>
                        <input v-model="store.settings.supabase.publishableKey" />
                    </div>
                    <div class="form-group">
                        <label>Service Role Key</label>
                        <input v-model="store.settings.supabase.serviceRoleKey" type="password" :placeholder="store.settings.supabase.serviceRoleConfigured ? '기존 키가 설정되어 있습니다' : '새 키 입력'" />
                    </div>
                    <div class="form-group col-span-2">
                        <label>JWT Issuer</label>
                        <input v-model="store.settings.supabase.jwtIssuer" />
                    </div>
                </div>

                <div class="flex justify-end gap-2 border-t border-slate-100 dark:border-slate-800 px-6 py-4">
                    <button class="button secondary" :disabled="store.actions.supabase.testing" @click="store.testSupabase">
                        {{ store.actions.supabase.testing ? '연결 확인 중...' : '연결 테스트' }}
                    </button>
                    <button class="button primary" :disabled="store.actions.supabase.saving" @click="store.saveSupabase">
                        {{ store.actions.supabase.saving ? '저장 중...' : '설정 저장' }}
                    </button>
                </div>
            </section>
        </template>

        <section v-else-if="activeTab === 'analysis'" class="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-6">
            <div class="grid grid-cols-2 gap-5">
                <div class="form-group">
                    <label>기본 분석 제한 시간</label>
                    <select>
                        <option>5분</option>
                        <option>10분</option>
                        <option>30분</option>
                    </select>
                </div>
                <div class="form-group">
                    <label>기본 DB 분석 범위</label>
                    <select>
                        <option>메타데이터만 분석</option>
                        <option>메타데이터 + 통계정보</option>
                    </select>
                </div>
                <div class="form-group col-span-2">
                    <label>소스 제외 패턴</label>
                    <textarea rows="5" value="node_modules/**&#10;dist/**&#10;build/**&#10;*.min.js"></textarea>
                </div>
            </div>
            <div class="mt-5 flex justify-end"><button class="button primary">분석 설정 저장</button></div>
        </section>

        <section v-else-if="activeTab === 'security'" class="rounded-xl border border-slate-200 bg-white p-6 dark:border-slate-700 dark:bg-slate-900">
            <div class="mb-5 rounded-lg border border-slate-200 p-4 dark:border-slate-700">
                <div class="flex items-center justify-between gap-5">
                    <div>
                        <div class="text-sm font-bold text-slate-800 dark:text-slate-200">화면 모드</div>
                        <div class="mt-1 text-xs text-slate-500 dark:text-slate-400">선택한 모드는 브라우저에 저장되어 다음 접속에도 유지됩니다.</div>
                    </div>

                    <div class="flex rounded-lg border border-slate-200 p-1 dark:border-slate-700">
                        <button
                            type="button"
                            class="rounded-md px-4 py-2 text-xs font-bold"
                            :class="!themeStore.isDark ? 'bg-blue-600 text-white' : 'text-slate-500 dark:text-slate-400'"
                            @click="themeStore.applyTheme('light')"
                        >
                            라이트
                        </button>
                        <button
                            type="button"
                            class="rounded-md px-4 py-2 text-xs font-bold"
                            :class="themeStore.isDark ? 'bg-blue-600 text-white' : 'text-slate-500 dark:text-slate-400'"
                            @click="themeStore.applyTheme('dark')"
                        >
                            다크
                        </button>
                    </div>
                </div>
            </div>

            <h2 class="text-lg font-extrabold text-slate-900 dark:text-slate-100">보안 및 운영</h2>
            <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">비밀값 마스킹, 감사로그 및 관리자 접근 정책을 설정합니다.</p>

            <div class="mt-5 space-y-4">
                <label class="flex items-center justify-between rounded-lg border border-slate-200 dark:border-slate-700 p-4">
                    <div>
                        <div class="text-sm font-bold text-slate-800 dark:text-slate-200">설정 변경 감사로그</div>
                        <div class="mt-1 text-xs text-slate-500 dark:text-slate-400">서버 주소와 비밀값 변경 이력을 기록합니다.</div>
                    </div>
                    <input type="checkbox" checked class="h-4 w-4" />
                </label>
                <label class="flex items-center justify-between rounded-lg border border-slate-200 dark:border-slate-700 p-4">
                    <div>
                        <div class="text-sm font-bold text-slate-800 dark:text-slate-200">관리자만 설정 변경</div>
                        <div class="mt-1 text-xs text-slate-500 dark:text-slate-400">일반 사용자는 연결 상태만 조회할 수 있습니다.</div>
                    </div>
                    <input type="checkbox" checked class="h-4 w-4" />
                </label>
            </div>
        </section>

        <section class="mt-5 rounded-xl border border-blue-200 bg-blue-50 px-5 py-4">
            <div class="flex items-center justify-between gap-5">
                <div>
                    <div class="text-sm font-extrabold text-blue-900">현재 활성 모델은 설정에서 변경하지 않습니다.</div>
                    <div class="mt-1 text-xs text-blue-700">모델 버전, 평가, 배포와 롤백은 AI 모델 관리 화면에서 수행합니다.</div>
                </div>
                <RouterLink to="/models" class="rounded-lg bg-blue-700 px-4 py-2 text-xs font-bold text-white">AI 모델 관리로 이동</RouterLink>
            </div>
        </section>
    </div>
</template>
