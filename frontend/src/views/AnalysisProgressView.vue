<script setup>
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import PageTitle from '@/components/common/PageTitle.vue'
import StepHeader from '@/components/common/StepHeader.vue'
import { useProjectStore } from '@/stores/projectStore'

const store = useProjectStore()
const route = useRoute()

onMounted(() => {
    store.loadProgress(route.params.analysisJobId)
})
</script>

<template>
    <div>
        <PageTitle eyebrow="STEP 2" title="소스코드와 DB 구조 병렬 분석" description="Mac Studio의 분석 서버가 두 입력을 병렬로 처리한 뒤 관계를 연결합니다." />

        <StepHeader :current="2" />

        <section v-if="store.progress" class="hero-progress panel">
            <div class="hero-progress-header">
                <div>
                    <span class="badge green">{{ store.progress.status }}</span>
                    <h2>doenaai 통합 분석 진행 중</h2>
                    <p>Controller부터 DB 테이블까지 연결 관계를 생성하고 있습니다.</p>
                </div>
                <div class="progress-score">{{ store.progress.overallProgress }}%</div>
            </div>
            <div class="progress-track large">
                <div class="progress-fill" :style="{ width: `${store.progress.overallProgress}%` }"></div>
            </div>
        </section>

        <div v-if="store.progress" class="grid three-columns">
            <section class="panel progress-panel">
                <div class="progress-panel-icon blue-bg">SRC</div>
                <h3>소스코드 분석</h3>
                <p>Vue, Spring Boot, MyBatis 구조 추출</p>
                <div class="progress-track">
                    <div class="progress-fill" :style="{ width: `${store.progress.sourceProgress}%` }"></div>
                </div>
                <strong>{{ store.progress.sourceProgress }}%</strong>
                <ul class="check-list">
                    <li>Controller 18개</li>
                    <li>Service 27개</li>
                    <li>Mapper SQL 134개</li>
                    <li>Vue 화면 22개</li>
                </ul>
            </section>

            <section class="panel progress-panel">
                <div class="progress-panel-icon purple-bg">DB</div>
                <h3>DB 스키마 분석</h3>
                <p>테이블, 컬럼, 인덱스, 관계 추출</p>
                <div class="progress-track">
                    <div class="progress-fill purple-fill" :style="{ width: `${store.progress.databaseProgress}%` }"></div>
                </div>
                <strong>{{ store.progress.databaseProgress }}%</strong>
                <ul class="check-list">
                    <li>테이블 48개</li>
                    <li>컬럼 612개</li>
                    <li>인덱스 96개</li>
                    <li>관계 53개</li>
                </ul>
            </section>

            <section class="panel progress-panel active-panel">
                <div class="progress-panel-icon orange-bg">LINK</div>
                <h3>코드·DB 관계 연결</h3>
                <p>화면부터 테이블까지 의존관계 생성</p>
                <div class="progress-track">
                    <div class="progress-fill orange-fill" :style="{ width: `${store.progress.relationProgress}%` }"></div>
                </div>
                <strong>{{ store.progress.relationProgress }}%</strong>
                <ul class="check-list">
                    <li>API ↔ Service 연결</li>
                    <li>Mapper ↔ Table 연결</li>
                    <li>Vue ↔ API 연결</li>
                    <li class="working">아키텍처 병합 중</li>
                </ul>
            </section>
        </div>

        <section class="panel activity-log">
            <div class="panel-heading">
                <div>
                    <h2>실시간 분석 로그</h2>
                    <p>현재는 화면 검토용 하드코딩 로그입니다.</p>
                </div>
            </div>
            <div class="log-row"><span>11:02:15</span><strong>ApplicationController 분석 완료</strong></div>
            <div class="log-row"><span>11:02:16</span><strong>TB_BM_APLY_RCPT 스키마 확인 완료</strong></div>
            <div class="log-row"><span>11:02:17</span><strong>ApplicationMapper.insertApplication 관계 연결</strong></div>
            <div class="log-row"><span>11:02:18</span><strong>통합 아키텍처 스냅샷 생성 중</strong></div>
        </section>

        <div class="page-actions">
            <RouterLink class="button primary" to="/architecture">통합 구조 확인</RouterLink>
        </div>
    </div>
</template>
