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
            description="가상 이름으로 화면·API·서비스·저장소·테이블 관계의 표현 방식을 보여주는 예시입니다."
        />

        <StepHeader :current="3" />

        <p
            role="note"
            class="mb-5 rounded-xl border border-blue-200 bg-blue-50 p-4 text-sm leading-6 text-blue-900 dark:border-blue-800 dark:bg-blue-950/40 dark:text-blue-100"
        >
            이 화면의 파일·API·테이블과 연결은 모두 가상 예시입니다. 등록한 프로젝트의 실제 분석 결과가 아닙니다.
        </p>

        <div v-if="store.architecture" class="summary-strip">
            <div><span>가상 프로젝트</span><strong>project_example</strong></div>
            <div>
                <span>소스 커밋</span><strong>{{ store.architecture.sourceCommit }}</strong>
            </div>
            <div>
                <span>DB 스냅샷</span><strong>{{ store.architecture.databaseSnapshot }}</strong>
            </div>
            <div><span>표시 자료</span><strong>화면 구성 예시</strong></div>
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
                    <strong>ExampleView.vue</strong>
                    <small>가상 항목 입력 화면</small>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-node api-node">
                    <span class="node-type">REST API</span>
                    <strong>POST /api/examples</strong>
                    <small>ExampleController</small>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-node service-node">
                    <span class="node-type">SERVICE</span>
                    <strong>ExampleService</strong>
                    <small>saveExample()</small>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-node mapper-node">
                    <span class="node-type">REPOSITORY</span>
                    <strong>ExampleRepository</strong>
                    <small>saveExample</small>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-node db-node">
                    <span class="node-type">TABLE</span>
                    <strong>table_example</strong>
                    <small>id_example · description_example</small>
                </div>
            </div>
        </section>

        <div class="grid two-columns">
            <section class="panel">
                <div class="panel-heading">
                    <div>
                        <h2>가상 구성의 기술 예시</h2>
                        <p>실제 프로젝트에서 탐지한 결과가 아닙니다.</p>
                    </div>
                </div>
                <div class="tag-list">
                    <span>Vue 3</span>
                    <span>Vite</span>
                    <span>Spring Boot</span>
                    <span>PostgreSQL</span>
                </div>
            </section>

            <section class="panel">
                <div class="panel-heading">
                    <div>
                        <h2>관계 표현 예시</h2>
                        <p>가상 관계이며 실제 연결이나 신뢰도를 측정하지 않았습니다.</p>
                    </div>
                </div>
                <div class="relation-review">
                    <div>
                        <strong>table_example_history → table_example</strong>
                        <span>테이블 연결을 표현하는 가상 예시</span>
                    </div>
                    <button class="button small-button">확인</button>
                </div>
                <div class="relation-review">
                    <div>
                        <strong>ExampleHistory → table_example_history</strong>
                        <span>소스와 테이블의 연결을 표현하는 가상 예시</span>
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
