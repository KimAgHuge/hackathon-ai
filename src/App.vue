<script setup>
import { onMounted, ref } from 'vue'

const isDark = ref(false)
const analyzing = ref(false)
const analysisResult = ref(null)

const project = ref({
  name: '공공 마이데이터 고도화',
  description: '전자서명 기반 마이데이터 제공 요구 시스템',
  deadline: '2026-07-31',
  progress: 72,
  teamSize: 6,
  techStack: 'Vue 3, Spring Boot, Supabase',
})

const changeRequest = ref({
  title: '전자서명 기능 추가',
  priority: 'HIGH',
  requestedDeadline: '2026-07-01',
  content:
    '2026년 7월 1일부터 마이데이터 요청 시 제공 요구서를 전자서명하여 요청하도록 기능을 추가합니다.',
})

const toggleTheme = () => {
  isDark.value = !isDark.value

  document.documentElement.classList.toggle('dark', isDark.value)

  localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
}

const analyzeProject = async () => {
  analyzing.value = true
  analysisResult.value = null

  await new Promise((resolve) => setTimeout(resolve, 1200))

  analysisResult.value = {
    decision: '조건부 가능',
    riskLevel: '높음',
    riskScore: 82,
    expectedDelayMin: 5,
    expectedDelayMax: 8,
    impactAreas: ['전자서명 API', '인증서 검증', '요청 데이터베이스', '통합 테스트'],
    risks: [
      '기존 요청 방식과 신규 방식 간 호환성 검토가 필요합니다',
      '인증 실패에 대한 추가 예외처리가 필요합니다',
      '적용 희망일까지 테스트 기간이 부족할 수 있습니다',
    ],
    recommendations: [
      '기존 요청 방식과 전자서명 방식을 일정 기간 병행합니다',
      '전자서명 기능을 별도 모듈로 분리합니다',
      '인증 실패와 인증서 만료 테스트를 우선 진행합니다',
    ],
    summary: '기능 구현은 가능하지만 적용 일정과 테스트 범위를 조정해야 합니다.',
  }

  analyzing.value = false
}

onMounted(() => {
  const savedTheme = localStorage.getItem('theme')

  isDark.value = savedTheme
    ? savedTheme === 'dark'
    : window.matchMedia('(prefers-color-scheme: dark)').matches

  document.documentElement.classList.toggle('dark', isDark.value)
})
</script>

<template>
  <div
    class="bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100 min-h-screen transition-colors duration-300"
  >
    <div
      class="inset-0 pointer-events-none fixed bg-[radial-gradient(circle_at_top_right,_rgba(99,102,241,0.14),_transparent_32%),radial-gradient(circle_at_bottom_left,_rgba(14,165,233,0.10),_transparent_30%)]"
    />

    <header
      class="top-0 border-slate-200/80 bg-white/80 backdrop-blur-xl dark:border-slate-800 dark:bg-slate-950/80 sticky z-30 border-b"
    >
      <div class="h-16 max-w-7xl px-4 sm:px-6 lg:px-8 mx-auto flex items-center justify-between">
        <div class="gap-3 flex items-center">
          <div
            class="h-10 w-10 rounded-2xl from-indigo-500 to-sky-500 text-white shadow-lg shadow-indigo-500/20 flex items-center justify-center bg-gradient-to-br"
          >
            <svg
              viewBox="0 0 24 24"
              class="h-5 w-5"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="M12 3v18M3 12h18" />
              <path d="m5 5 14 14M19 5 5 19" />
            </svg>
          </div>

          <div>
            <p class="text-xs font-semibold text-indigo-500 tracking-[0.2em] uppercase">
              Project Impact AI
            </p>

            <h1 class="font-bold text-slate-950 dark:text-white">프로젝트 변경 영향 분석</h1>
          </div>
        </div>

        <div class="gap-3 flex items-center">
          <span
            class="border-emerald-200 bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700 sm:inline-flex dark:border-emerald-900 dark:bg-emerald-950/50 dark:text-emerald-300 hidden rounded-full border"
          >
            AI 서비스 정상
          </span>

          <button
            type="button"
            class="h-10 w-10 rounded-xl border-slate-200 bg-white text-slate-600 hover:-translate-y-0.5 hover:border-indigo-300 hover:text-indigo-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 flex items-center justify-center border transition"
            aria-label="다크모드 변경"
            @click="toggleTheme"
          >
            <svg
              v-if="isDark"
              viewBox="0 0 24 24"
              class="h-5 w-5"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <circle cx="12" cy="12" r="4" />
              <path
                d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.42-1.41M17.66 6.34l1.41-1.41"
              />
            </svg>

            <svg
              v-else
              viewBox="0 0 24 24"
              class="h-5 w-5"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z" />
            </svg>
          </button>
        </div>
      </div>
    </header>

    <main class="max-w-7xl px-4 py-8 sm:px-6 lg:px-8 relative mx-auto">
      <section
        class="mb-8 rounded-3xl border-indigo-100 from-indigo-600 via-violet-600 to-sky-500 p-6 text-white shadow-2xl shadow-indigo-500/15 sm:p-8 overflow-hidden border bg-gradient-to-r"
      >
        <div class="gap-6 lg:flex-row lg:items-end flex flex-col justify-between">
          <div class="max-w-3xl">
            <span
              class="mb-4 border-white/20 bg-white/10 px-3 py-1 text-xs font-semibold backdrop-blur inline-flex rounded-full border"
            >
              AI 기반 프로젝트 의사결정 지원
            </span>

            <h2 class="text-2xl font-bold leading-tight sm:text-4xl">
              변경 요청이 프로젝트에 미치는 영향을
              <br class="sm:block hidden" />
              몇 초 안에 분석하세요
            </h2>

            <p class="mt-4 max-w-2xl text-sm text-white/80 sm:text-base">
              일정, 진행률, 기술 스택과 변경 요청을 종합하여 위험 수준과 현실적인 대응 방안을
              제공합니다.
            </p>
          </div>

          <div
            class="gap-3 rounded-2xl border-white/15 bg-white/10 p-4 backdrop-blur grid grid-cols-3 border"
          >
            <div class="text-center">
              <p class="text-2xl font-bold">{{ project.progress }}%</p>
              <p class="mt-1 text-xs text-white/70">진행률</p>
            </div>

            <div class="border-white/15 border-x text-center">
              <p class="text-2xl font-bold">
                {{ project.teamSize }}
              </p>
              <p class="mt-1 text-xs text-white/70">팀원</p>
            </div>

            <div class="text-center">
              <p class="text-2xl font-bold">1</p>
              <p class="mt-1 text-xs text-white/70">분석 대기</p>
            </div>
          </div>
        </div>
      </section>

      <div class="gap-6 lg:grid-cols-[1.1fr_0.9fr] grid">
        <div class="space-y-6">
          <section
            class="rounded-3xl border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900 border shadow-[0_18px_45px_rgba(15,23,42,0.06)] dark:shadow-none"
          >
            <div class="mb-6 flex items-center justify-between">
              <div>
                <p class="text-xs font-bold text-indigo-500 tracking-[0.18em] uppercase">Step 01</p>
                <h2 class="mt-1 text-xl font-bold text-slate-950 dark:text-white">
                  프로젝트 기본 정보
                </h2>
              </div>

              <span
                class="bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-500 dark:bg-slate-800 dark:text-slate-400 rounded-full"
              >
                필수 정보
              </span>
            </div>

            <div class="gap-5 sm:grid-cols-2 grid">
              <label class="sm:col-span-2">
                <span class="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-300 block">
                  프로젝트명
                </span>

                <input
                  v-model="project.name"
                  type="text"
                  class="rounded-2xl border-slate-200 bg-slate-50 px-4 py-3 text-sm placeholder:text-slate-400 focus:border-indigo-500 focus:bg-white focus:ring-indigo-500/10 dark:border-slate-700 dark:bg-slate-950 dark:focus:border-indigo-400 w-full border transition outline-none focus:ring-4"
                />
              </label>

              <label>
                <span class="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-300 block">
                  완료 예정일
                </span>

                <input
                  v-model="project.deadline"
                  type="date"
                  class="rounded-2xl border-slate-200 bg-slate-50 px-4 py-3 text-sm focus:border-indigo-500 focus:bg-white focus:ring-indigo-500/10 dark:border-slate-700 dark:bg-slate-950 w-full border transition outline-none focus:ring-4"
                />
              </label>

              <label>
                <span class="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-300 block">
                  팀원 수
                </span>

                <input
                  v-model.number="project.teamSize"
                  type="number"
                  min="1"
                  class="rounded-2xl border-slate-200 bg-slate-50 px-4 py-3 text-sm focus:border-indigo-500 focus:bg-white focus:ring-indigo-500/10 dark:border-slate-700 dark:bg-slate-950 w-full border transition outline-none focus:ring-4"
                />
              </label>

              <label class="sm:col-span-2">
                <span
                  class="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-300 flex items-center justify-between"
                >
                  현재 진행률

                  <strong class="text-indigo-500"> {{ project.progress }}% </strong>
                </span>

                <input
                  v-model.number="project.progress"
                  type="range"
                  min="0"
                  max="100"
                  class="accent-indigo-600 w-full"
                />
              </label>

              <label class="sm:col-span-2">
                <span class="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-300 block">
                  기술 스택
                </span>

                <input
                  v-model="project.techStack"
                  type="text"
                  class="rounded-2xl border-slate-200 bg-slate-50 px-4 py-3 text-sm focus:border-indigo-500 focus:bg-white focus:ring-indigo-500/10 dark:border-slate-700 dark:bg-slate-950 w-full border transition outline-none focus:ring-4"
                />
              </label>
            </div>
          </section>

          <section
            class="rounded-3xl border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900 border shadow-[0_18px_45px_rgba(15,23,42,0.06)] dark:shadow-none"
          >
            <div class="mb-6">
              <p class="text-xs font-bold text-sky-500 tracking-[0.18em] uppercase">Step 02</p>
              <h2 class="mt-1 text-xl font-bold text-slate-950 dark:text-white">변경 요청 입력</h2>
            </div>

            <div class="gap-5 sm:grid-cols-2 grid">
              <label class="sm:col-span-2">
                <span class="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-300 block">
                  요청 제목
                </span>

                <input
                  v-model="changeRequest.title"
                  type="text"
                  class="rounded-2xl border-slate-200 bg-slate-50 px-4 py-3 text-sm focus:border-sky-500 focus:bg-white focus:ring-sky-500/10 dark:border-slate-700 dark:bg-slate-950 w-full border transition outline-none focus:ring-4"
                />
              </label>

              <label>
                <span class="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-300 block">
                  우선순위
                </span>

                <select
                  v-model="changeRequest.priority"
                  class="rounded-2xl border-slate-200 bg-slate-50 px-4 py-3 text-sm focus:border-sky-500 focus:ring-sky-500/10 dark:border-slate-700 dark:bg-slate-950 w-full border transition outline-none focus:ring-4"
                >
                  <option value="LOW">낮음</option>
                  <option value="MEDIUM">보통</option>
                  <option value="HIGH">높음</option>
                  <option value="CRITICAL">긴급</option>
                </select>
              </label>

              <label>
                <span class="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-300 block">
                  희망 적용일
                </span>

                <input
                  v-model="changeRequest.requestedDeadline"
                  type="date"
                  class="rounded-2xl border-slate-200 bg-slate-50 px-4 py-3 text-sm focus:border-sky-500 focus:ring-sky-500/10 dark:border-slate-700 dark:bg-slate-950 w-full border transition outline-none focus:ring-4"
                />
              </label>

              <label class="sm:col-span-2">
                <span class="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-300 block">
                  변경 요청 내용
                </span>

                <textarea
                  v-model="changeRequest.content"
                  rows="6"
                  class="rounded-2xl border-slate-200 bg-slate-50 px-4 py-3 text-sm leading-6 focus:border-sky-500 focus:bg-white focus:ring-sky-500/10 dark:border-slate-700 dark:bg-slate-950 w-full resize-none border transition outline-none focus:ring-4"
                ></textarea>
              </label>
            </div>

            <button
              type="button"
              :disabled="analyzing"
              class="mt-6 gap-2 rounded-2xl from-indigo-600 to-sky-500 px-5 py-4 text-sm font-bold text-white shadow-lg shadow-indigo-500/20 hover:-translate-y-0.5 hover:shadow-xl flex w-full items-center justify-center bg-gradient-to-r transition disabled:cursor-not-allowed disabled:opacity-60"
              @click="analyzeProject"
            >
              <svg
                v-if="!analyzing"
                viewBox="0 0 24 24"
                class="h-5 w-5"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <path d="m12 3-1.9 5.1L5 10l5.1 1.9L12 17l1.9-5.1L19 10l-5.1-1.9L12 3Z" />
                <path d="m5 3-.7 1.8L2.5 5.5l1.8.7L5 8l.7-1.8 1.8-.7-1.8-.7L5 3Z" />
              </svg>

              <svg v-else viewBox="0 0 24 24" class="h-5 w-5 animate-spin" fill="none">
                <circle
                  cx="12"
                  cy="12"
                  r="9"
                  stroke="currentColor"
                  stroke-width="3"
                  class="opacity-25"
                />
                <path
                  d="M21 12a9 9 0 0 0-9-9"
                  stroke="currentColor"
                  stroke-width="3"
                  stroke-linecap="round"
                />
              </svg>

              {{ analyzing ? 'AI가 변경 영향을 분석하고 있습니다' : 'AI 변경 영향 분석' }}
            </button>
          </section>
        </div>

        <aside class="lg:sticky lg:top-24 lg:self-start">
          <section
            class="rounded-3xl border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900 overflow-hidden border shadow-[0_18px_45px_rgba(15,23,42,0.06)] dark:shadow-none"
          >
            <div class="border-slate-200 px-6 py-5 dark:border-slate-800 border-b">
              <p class="text-xs font-bold text-violet-500 tracking-[0.18em] uppercase">
                AI Analysis
              </p>
              <h2 class="mt-1 text-xl font-bold text-slate-950 dark:text-white">분석 결과</h2>
            </div>

            <div
              v-if="!analysisResult && !analyzing"
              class="px-8 flex min-h-[520px] flex-col items-center justify-center text-center"
            >
              <div
                class="h-20 w-20 rounded-3xl bg-indigo-50 text-indigo-500 dark:bg-indigo-950/60 dark:text-indigo-300 flex items-center justify-center"
              >
                <svg
                  viewBox="0 0 24 24"
                  class="h-10 w-10"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.7"
                >
                  <path d="M9 3h6l1 4H8l1-4Z" />
                  <path d="M6 7h12v14H6z" />
                  <path d="M9 11h6M9 15h4" />
                </svg>
              </div>

              <h3 class="mt-5 text-lg font-bold text-slate-900 dark:text-white">
                아직 분석 결과가 없습니다
              </h3>

              <p class="mt-2 max-w-xs text-sm leading-6 text-slate-500 dark:text-slate-400">
                프로젝트 정보와 변경 요청을 입력한 뒤 AI 분석 버튼을 눌러주세요.
              </p>
            </div>

            <div
              v-else-if="analyzing"
              class="px-8 flex min-h-[520px] flex-col items-center justify-center text-center"
            >
              <div class="relative">
                <div class="h-20 w-20 animate-ping bg-indigo-500/20 rounded-full" />

                <div
                  class="inset-0 bg-indigo-600 text-white absolute flex items-center justify-center rounded-full"
                >
                  AI
                </div>
              </div>

              <h3 class="mt-6 text-lg font-bold text-slate-900 dark:text-white">
                영향 범위를 계산하고 있습니다
              </h3>

              <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">
                일정과 기술적 위험 요소를 검토 중입니다.
              </p>
            </div>

            <div v-else class="space-y-6 p-6">
              <div
                class="rounded-2xl border-rose-200 bg-rose-50 p-5 dark:border-rose-900 dark:bg-rose-950/40 border"
              >
                <div class="gap-4 flex items-start justify-between">
                  <div>
                    <p class="text-xs font-bold text-rose-500 tracking-[0.15em] uppercase">
                      최종 판정
                    </p>

                    <p class="mt-2 text-2xl font-black text-rose-700 dark:text-rose-300">
                      {{ analysisResult.decision }}
                    </p>
                  </div>

                  <div class="rounded-2xl bg-rose-500 px-3 py-2 text-white text-center">
                    <p class="text-xl font-black">
                      {{ analysisResult.riskScore }}
                    </p>
                    <p class="font-bold text-[10px]">RISK</p>
                  </div>
                </div>

                <div class="mt-4">
                  <div
                    class="mb-2 text-xs font-semibold text-rose-700 dark:text-rose-300 flex justify-between"
                  >
                    <span>위험도 {{ analysisResult.riskLevel }}</span>
                    <span>{{ analysisResult.riskScore }}%</span>
                  </div>

                  <div class="h-2 bg-rose-200 dark:bg-rose-950 overflow-hidden rounded-full">
                    <div
                      class="bg-rose-500 h-full rounded-full"
                      :style="{
                        width: `${analysisResult.riskScore}%`,
                      }"
                    />
                  </div>
                </div>
              </div>

              <div class="gap-3 grid grid-cols-2">
                <div class="rounded-2xl bg-slate-50 p-4 dark:bg-slate-950">
                  <p class="text-xs font-semibold text-slate-500 dark:text-slate-400">최소 지연</p>
                  <p class="mt-1 text-xl font-black text-slate-950 dark:text-white">
                    {{ analysisResult.expectedDelayMin }}일
                  </p>
                </div>

                <div class="rounded-2xl bg-slate-50 p-4 dark:bg-slate-950">
                  <p class="text-xs font-semibold text-slate-500 dark:text-slate-400">최대 지연</p>
                  <p class="mt-1 text-xl font-black text-slate-950 dark:text-white">
                    {{ analysisResult.expectedDelayMax }}일
                  </p>
                </div>
              </div>

              <div>
                <h3 class="text-sm font-bold text-slate-900 dark:text-white">영향 영역</h3>

                <div class="mt-3 gap-2 flex flex-wrap">
                  <span
                    v-for="area in analysisResult.impactAreas"
                    :key="area"
                    class="border-indigo-200 bg-indigo-50 px-3 py-1.5 text-xs font-semibold text-indigo-700 dark:border-indigo-900 dark:bg-indigo-950/50 dark:text-indigo-300 rounded-full border"
                  >
                    {{ area }}
                  </span>
                </div>
              </div>

              <div>
                <h3 class="text-sm font-bold text-slate-900 dark:text-white">주요 위험 요소</h3>

                <ul class="mt-3 space-y-3">
                  <li
                    v-for="risk in analysisResult.risks"
                    :key="risk"
                    class="gap-3 text-sm leading-6 text-slate-600 dark:text-slate-300 flex"
                  >
                    <span class="mt-2 h-1.5 w-1.5 bg-rose-500 shrink-0 rounded-full" />
                    {{ risk }}
                  </li>
                </ul>
              </div>

              <div>
                <h3 class="text-sm font-bold text-slate-900 dark:text-white">권장 대응 방안</h3>

                <ul class="mt-3 space-y-3">
                  <li
                    v-for="recommendation in analysisResult.recommendations"
                    :key="recommendation"
                    class="gap-3 rounded-xl bg-emerald-50 p-3 text-sm leading-6 text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-200 flex"
                  >
                    <svg
                      viewBox="0 0 24 24"
                      class="mt-0.5 h-4 w-4 shrink-0"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                    >
                      <path d="m5 12 4 4L19 6" />
                    </svg>

                    {{ recommendation }}
                  </li>
                </ul>
              </div>

              <div
                class="rounded-2xl border-slate-200 p-4 text-sm leading-6 text-slate-600 dark:border-slate-700 dark:text-slate-300 border"
              >
                {{ analysisResult.summary }}
              </div>
            </div>
          </section>
        </aside>
      </div>
    </main>
  </div>
</template>
