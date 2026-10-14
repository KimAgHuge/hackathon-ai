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
        <PageTitle
            eyebrow="STEP 4"
            title="변경 요청 영향도 분석"
            description="통합 아키텍처와 DB 구조를 근거로 변경 범위와 위험도를 분석합니다."
        />

        <StepHeader :current="4" />

        <section class="panel change-request-card">
            <div>
                <span class="badge orange">CHANGE REQUEST</span>
                <h2>신청 접수 시 전자서명 검증 결과와 서명 원문 저장</h2>
                <p>기존 신청 접수 흐름에 전자서명 검증 및 이력 저장 절차를 추가합니다.</p>
            </div>
            <button class="button secondary">요청 수정</button>
        </section>

        <template v-if="store.impact">
            <div class="grid result-grid">
                <section class="panel risk-card">
                    <span>종합 위험도</span>
                    <strong>{{ riskLevelLabel(store.impact.riskLevel) }}</strong>
                    <div class="risk-score">{{ store.impact.riskScore }}</div>
                    <p>외부 연계 및 DB 저장 구조 변경이 포함되어 통합 테스트가 필요합니다.</p>
                </section>

                <section class="panel delay-card">
                    <span>예상 일정 영향</span>
                    <strong>
                        {{ store.impact.expectedDelayMin }}~{{ store.impact.expectedDelayMax }}일
                    </strong>
                    <p>전자서명 공통 모듈 존재 여부와 외부기관 테스트 일정에 따라 달라질 수 있습니다.</p>
                </section>

                <section class="panel scope-card">
                    <span>영향 영역</span>
                    <strong>{{ store.impact.impactAreas.length }}개</strong>
                    <div class="tag-list compact">
                        <span
                            v-for="area in store.impact.impactAreas"
                            :key="area"
                        >
                            {{ area }}
                        </span>
                    </div>
                </section>
            </div>

            <div class="grid two-columns">
                <section class="panel">
                    <div class="panel-heading">
                        <div>
                            <h2>근거 기반 영향 경로</h2>
                            <p>분석에 사용된 코드 및 DB 객체</p>
                        </div>
                    </div>

                    <div
                        v-for="evidence in store.impact.evidences"
                        :key="evidence.name"
                        class="evidence-row"
                    >
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
                            <p>실제 구현 전에 검토할 항목</p>
                        </div>
                    </div>
                    <ol class="recommendation-list">
                        <li
                            v-for="item in store.impact.recommendations"
                            :key="item"
                        >
                            {{ item }}
                        </li>
                    </ol>
                </section>
            </div>
        </template>

        <section class="panel feedback-panel">
            <div>
                <h2>분석 결과가 정확한가요?</h2>
                <p>검수 결과는 향후 학습 후보 데이터로 축적됩니다.</p>
            </div>
            <div class="feedback-actions">
                <button class="button secondary">부정확함</button>
                <button class="button secondary">일부 수정</button>
                <button class="button primary">정확함</button>
            </div>
        </section>
    </div>
</template>
