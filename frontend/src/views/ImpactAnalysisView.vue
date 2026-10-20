<script setup>
import { onMounted } from 'vue'
import PageTitle from '@/components/common/PageTitle.vue'
import StepHeader from '@/components/common/StepHeader.vue'
import { useProjectStore } from '@/stores/projectStore'
import { riskLevelLabel } from '@/lib/riskLevel'

const store = useProjectStore()

onMounted(() => {
    store.loadImpact()
})
</script>

<template>
    <div>
        <PageTitle eyebrow="STEP 4" title="변경 요청 영향도 분석" description="가상 변경요청으로 영향 영역과 위험도·일정의 표시 방식을 보여주는 예시입니다." />

        <StepHeader :current="4" />

        <p
            role="note"
            class="mb-5 rounded-xl border border-blue-200 bg-blue-50 p-4 text-sm leading-6 text-blue-900 dark:border-blue-800 dark:bg-blue-950/40 dark:text-blue-100"
        >
            이 화면은 항목에 선택 입력인 설명을 추가하는 가상 사례입니다. 파일·테이블·위험도·일정은 실제 분석이나 견적이 아닙니다.
        </p>

        <section class="panel change-request-card">
            <div>
                <span class="badge orange">가상 변경요청</span>
                <h2>예시 항목에 설명 입력 기능 추가</h2>
                <p>가상 테이블 table_example에 선택 입력인 description_example 값을 저장하고 화면에 표시하는 사례입니다.</p>
            </div>
            <button class="button secondary">요청 수정</button>
        </section>

        <template v-if="store.impact">
            <div class="grid result-grid">
                <section class="panel risk-card">
                    <span>위험도 표시 예시</span>
                    <strong>{{ riskLevelLabel(store.impact.riskLevel) }}</strong>
                    <div class="risk-score">{{ store.impact.riskScore }}</div>
                    <p>가상 사례에서는 기존 항목에 설명이 없어도 조회와 수정이 가능한지 확인합니다. 점수는 화면 시험용입니다.</p>
                </section>

                <section class="panel delay-card">
                    <span>일정 표시 예시</span>
                    <strong> {{ store.impact.expectedDelayMin }}~{{ store.impact.expectedDelayMax }}일 </strong>
                    <p>화면 시험을 위한 임의 값입니다. 실제 일정은 변경 범위와 구현·검증 작업을 확인한 뒤 산정합니다.</p>
                </section>

                <section class="panel scope-card">
                    <span>영향 영역</span>
                    <strong>{{ store.impact.impactAreas.length }}개</strong>
                    <div class="tag-list compact">
                        <span v-for="area in store.impact.impactAreas" :key="area">
                            {{ area }}
                        </span>
                    </div>
                </section>
            </div>

            <div class="grid two-columns">
                <section class="panel">
                    <div class="panel-heading">
                        <div>
                            <h2>영향 경로 표시 예시</h2>
                            <p>화면 구성을 설명하기 위해 만든 가상 파일과 테이블입니다.</p>
                        </div>
                    </div>

                    <div v-for="evidence in store.impact.evidences" :key="evidence.name" class="evidence-row">
                        <div class="evidence-index">{{ evidence.order }}</div>
                        <div>
                            <strong>{{ evidence.name }}</strong>
                            <p>{{ evidence.description }}</p>
                            <code>{{ evidence.path }}</code>
                        </div>
                    </div>
                </section>

                <section class="panel">
                    <div class="panel-heading">
                        <div>
                            <h2>권장 대응 방안</h2>
                            <p>가상 사례에서 확인할 항목의 표현 예시입니다.</p>
                        </div>
                    </div>
                    <ol class="recommendation-list">
                        <li v-for="item in store.impact.recommendations" :key="item">
                            {{ item }}
                        </li>
                    </ol>
                </section>
            </div>
        </template>

        <section class="panel feedback-panel">
            <div>
                <h2>저장된 분석 결과를 검토하려면</h2>
                <p>이 화면은 예시입니다. 실제 승인·반려는 저장된 분석 결과 화면에서 진행합니다.</p>
            </div>
            <div class="feedback-actions">
                <RouterLink class="button primary" to="/analysis-history">분석 이력 열기</RouterLink>
            </div>
        </section>
    </div>
</template>
