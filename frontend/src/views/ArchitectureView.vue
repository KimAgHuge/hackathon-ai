<script setup>
import { onMounted } from 'vue'
import PageTitle from '@/components/common/PageTitle.vue'
import StepHeader from '@/components/common/StepHeader.vue'
import { useProjectStore } from '@/stores/projectStore'

const store = useProjectStore()

onMounted(() => {
    store.loadArchitecture()
})
</script>

<template>
    <div>
        <PageTitle
            eyebrow="STEP 3"
            title="통합 프로젝트 아키텍처"
            description="소스코드와 DB 스키마를 결합해 생성한 화면·API·서비스·SQL·테이블 관계입니다."
        />

        <StepHeader :current="3" />

        <div v-if="store.architecture" class="summary-strip">
            <div><span>프로젝트</span><strong>doenaai</strong></div>
            <div><span>소스 커밋</span><strong>{{ store.architecture.sourceCommit }}</strong></div>
            <div><span>DB 스냅샷</span><strong>{{ store.architecture.databaseSnapshot }}</strong></div>
            <div><span>분석 상태</span><strong class="success-text">검토 가능</strong></div>
        </div>

        <section class="panel architecture-canvas">
            <div class="architecture-toolbar">
                <div>
                    <button class="chip selected">전체</button>
                    <button class="chip">프론트엔드</button>
                    <button class="chip">백엔드</button>
                    <button class="chip">DB</button>
                </div>
                <button class="button secondary">PNG 내보내기</button>
            </div>

            <div class="flow-diagram">
                <div class="flow-node frontend-node">
                    <span class="node-type">VUE VIEW</span>
                    <strong>ApplicationView.vue</strong>
                    <small>신청 접수 및 변경 요청 화면</small>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-node api-node">
                    <span class="node-type">REST API</span>
                    <strong>POST /api/applications</strong>
                    <small>ApplicationController</small>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-node service-node">
                    <span class="node-type">SERVICE</span>
                    <strong>ApplicationService</strong>
                    <small>createApplication()</small>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-node mapper-node">
                    <span class="node-type">MYBATIS</span>
                    <strong>ApplicationMapper</strong>
                    <small>insertApplication</small>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-node db-node">
                    <span class="node-type">TIBERO TABLE</span>
                    <strong>TB_BM_APLY_RCPT</strong>
                    <small>APLY_RCPT_NO · BRNO · STATUS_CD</small>
                </div>
            </div>
        </section>

        <div class="grid two-columns">
            <section class="panel">
                <div class="panel-heading">
                    <div>
                        <h2>감지된 기술 스택</h2>
                        <p>소스코드 및 설정 파일 기반 결과</p>
                    </div>
                </div>
                <div class="tag-list">
                    <span>Vue 3</span>
                    <span>Vite</span>
                    <span>Spring Boot</span>
                    <span>MyBatis</span>
                    <span>Tibero</span>
                    <span>Jenkins</span>
                    <span>Nginx</span>
                </div>
            </section>

            <section class="panel">
                <div class="panel-heading">
                    <div>
                        <h2>검토가 필요한 관계</h2>
                        <p>코드 명칭 또는 JOIN 패턴으로 추정된 항목</p>
                    </div>
                </div>
                <div class="relation-review">
                    <div>
                        <strong>TB_BM_VRFC_RCPT → TB_BM_APLY_RCPT</strong>
                        <span>SQL_JOIN · 신뢰도 92%</span>
                    </div>
                    <button class="button small-button">확인</button>
                </div>
                <div class="relation-review">
                    <div>
                        <strong>ApplicationHistory → TB_BM_APLY_HIST</strong>
                        <span>NAMING_INFERENCE · 신뢰도 48%</span>
                    </div>
                    <button class="button small-button">수정</button>
                </div>
            </section>
        </div>

        <div class="page-actions">
            <RouterLink class="button primary" to="/impact">변경 영향 분석으로 이동</RouterLink>
        </div>
    </div>
</template>
