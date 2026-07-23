<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import PageTitle from '@/components/common/PageTitle.vue'
import StepHeader from '@/components/common/StepHeader.vue'
import { startAnalysis } from '@/api/projectApi'

const router = useRouter()
const loading = ref(false)

const form = ref({
    projectName: 'doenaai',
    sourceType: 'ZIP',
    repositoryUrl: 'https://github.com/example/doenaai.git',
    branch: 'main',
    databaseMode: 'CONNECTION',
    databaseType: 'TIBERO',
    host: '192.168.0.30',
    port: '8629',
    schema: 'DOENAAI',
    username: 'doenaai_reader',
})

const handleStart = async () => {
    loading.value = true

    try {
        await startAnalysis(form.value)
        router.push('/analysis/progress')
    } finally {
        loading.value = false
    }
}
</script>

<template>
    <div>
        <PageTitle
            eyebrow="STEP 1"
            title="프로젝트와 데이터베이스 연결"
            description="소스코드와 DB 스키마를 동시에 분석하기 위한 연결 정보를 등록합니다."
        />

        <StepHeader :current="1" />

        <div class="grid two-columns">
            <section class="panel">
                <div class="panel-heading">
                    <div>
                        <h2>프로젝트 소스</h2>
                        <p>Git 저장소 또는 ZIP 파일을 연결합니다.</p>
                    </div>
                    <span class="badge blue">SOURCE</span>
                </div>

                <div class="form-group">
                    <label>프로젝트명</label>
                    <input v-model="form.projectName" />
                </div>

                <div class="segmented">
                    <button
                        type="button"
                        :class="{ selected: form.sourceType === 'ZIP' }"
                        @click="form.sourceType = 'ZIP'"
                    >
                        ZIP 업로드
                    </button>
                    <button
                        type="button"
                        :class="{ selected: form.sourceType === 'GIT' }"
                        @click="form.sourceType = 'GIT'"
                    >
                        Git 저장소
                    </button>
                </div>

                <div v-if="form.sourceType === 'ZIP'" class="dropzone">
                    <div class="dropzone-icon">ZIP</div>
                    <strong>프로젝트 ZIP 파일 업로드</strong>
                    <p>현재는 화면 확인용이며 실제 업로드 API 연결 전 상태입니다.</p>
                    <button type="button" class="button secondary">파일 선택</button>
                </div>

                <template v-else>
                    <div class="form-group">
                        <label>저장소 URL</label>
                        <input v-model="form.repositoryUrl" />
                    </div>
                    <div class="form-group">
                        <label>브랜치</label>
                        <input v-model="form.branch" />
                    </div>
                </template>
            </section>

            <section class="panel">
                <div class="panel-heading">
                    <div>
                        <h2>데이터베이스</h2>
                        <p>읽기 전용 연결 또는 DDL 파일을 등록합니다.</p>
                    </div>
                    <span class="badge purple">DATABASE</span>
                </div>

                <div class="segmented">
                    <button
                        type="button"
                        :class="{ selected: form.databaseMode === 'CONNECTION' }"
                        @click="form.databaseMode = 'CONNECTION'"
                    >
                        DB 연결
                    </button>
                    <button
                        type="button"
                        :class="{ selected: form.databaseMode === 'DDL' }"
                        @click="form.databaseMode = 'DDL'"
                    >
                        DDL 업로드
                    </button>
                </div>

                <template v-if="form.databaseMode === 'CONNECTION'">
                    <div class="form-group">
                        <label>DB 종류</label>
                        <select v-model="form.databaseType">
                            <option>TIBERO</option>
                            <option>ORACLE</option>
                            <option>POSTGRESQL</option>
                            <option>MYSQL</option>
                        </select>
                    </div>

                    <div class="form-row">
                        <div class="form-group">
                            <label>호스트</label>
                            <input v-model="form.host" />
                        </div>
                        <div class="form-group small">
                            <label>포트</label>
                            <input v-model="form.port" />
                        </div>
                    </div>

                    <div class="form-group">
                        <label>스키마</label>
                        <input v-model="form.schema" />
                    </div>

                    <div class="form-group">
                        <label>읽기 전용 계정</label>
                        <input v-model="form.username" />
                    </div>
                </template>

                <div v-else class="dropzone compact-dropzone">
                    <div class="dropzone-icon">DDL</div>
                    <strong>스키마 DDL 파일 업로드</strong>
                    <p>CREATE TABLE, INDEX, CONSTRAINT 파일을 등록합니다.</p>
                    <button type="button" class="button secondary">파일 선택</button>
                </div>

                <div class="info-box">
                    실제 업무 데이터는 읽지 않고 테이블, 컬럼, 인덱스, 제약조건 등
                    스키마 메타데이터만 조회합니다.
                </div>
            </section>
        </div>

        <div class="page-actions">
            <button type="button" class="button secondary">임시 저장</button>
            <button
                type="button"
                class="button primary"
                :disabled="loading"
                @click="handleStart"
            >
                {{ loading ? '분석 작업 생성 중...' : '프로젝트 분석 시작' }}
            </button>
        </div>
    </div>
</template>
