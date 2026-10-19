<script setup>
defineProps({ training: { type: Boolean, default: false } })

const reviewItems = [
    ['summary', '분석 요약', '요구사항의 변경 범위·제외 범위·판단 근거·남은 한계를 적습니다.'],
    ['plainLanguageSummary', '쉽게 보는 한 줄 요약', '비개발자를 위한 설명을 줄바꿈 없이 200자 이하로 적습니다.'],
    ['recommendations', '권장 대응 방안', '실제로 수행할 작업을 문장 목록으로 적습니다. 두 번째 항목의 JSON 위치는 recommendations[1]입니다.'],
    ['impactAreas / frameworks', '영향 영역 / 기술 구성', '영향을 받는 기능·화면·파일과 확인된 기술 이름을 각각 문장 목록으로 적습니다.'],
    ['evidences', '분석 근거', '실제 확인한 파일·줄 번호·설명을 유지합니다. 추측한 경로나 확인하지 않은 코드를 근거로 추가하지 마세요.'],
    [
        'riskAssessment / riskScore / riskLevel',
        '위험도 판단',
        '세부 점수와 이유, 점수 합계, 등급이 서로 맞아야 합니다. 세부 상한은 변경 범위 25·데이터 20·보안 25·구현 난도 20·불확실성 10점입니다. 등급은 0~24 LOW, 25~49 MEDIUM, 50~74 HIGH, 75~100 CRITICAL입니다. 총점이나 등급만 따로 바꾸지 마세요.',
    ],
    [
        'databaseImpact',
        'DB 영향 판단',
        'status는 CHANGE_REQUIRED(변경 필요), NO_CHANGE(변경 불필요), NEEDS_CLARIFICATION(확인 필요), UNKNOWN(판단 불가), NOT_APPLICABLE(분석 대상 아님)입니다. 상태·이유·테이블·변경안·조건별 경로·질문을 함께 유지합니다. CHANGE_REQUIRED 외에는 proposedChanges를 빈 목록으로 유지하고, NEEDS_CLARIFICATION은 두 가지 이상 조건별 경로와 확인 질문이 필요합니다.',
    ],
]
const trainingItems = [
    ['caseId / projectGroup', '분석·프로젝트 식별자', '서버가 자동 입력합니다. 사례를 늘리려고 임의로 바꾸거나 새 식별자를 만들지 마세요.'],
    ['request', '질문과 실제 근거', '요구사항, 수집된 소스·DB 구조·관계 정보입니다. 근거를 임의로 지우거나 현재와 다른 DB 구조를 만들어 넣지 마세요.'],
    ['answers.general', '일반 분석 정답', '검토한 요약·변경 범위·위험도·근거·권장 대응 방안과 DB 관련성·이유를 담습니다.'],
    ['answers.database', 'DB 분석 정답', 'DB 분석 단계가 필요한 사례에서만 함께 보냅니다. 관련 없음으로 판단한 사례는 이 단계를 임의로 추가하지 마세요.'],
    ['snapshot', '분석 당시 기준', 'Git 커밋·요구사항 버전·DB 수집 시각입니다. 수집 시각을 현재 시각이나 승인 시각으로 대신 채우지 마세요.'],
    ['approval', '검토 기록', '검토자 식별자·승인 시각·검토 이유입니다. 식별자는 로그인 정보에서 자동 입력됩니다.'],
    ['humanApproved / sanitized', '검토 확인', '아래 확인란의 선택이 자동 반영됩니다. JSON에 true를 직접 쓰는 것만으로 확인을 대신하지 않습니다.'],
]
</script>

<template>
    <details class="min-w-0 rounded-xl border border-slate-200 p-4 dark:border-slate-700">
        <summary class="cursor-pointer text-sm font-semibold text-slate-800 dark:text-slate-100">
            {{ training ? '학습 서버에 보내는 데이터는 무엇인가요?' : '항목별 작성 방법과 JSON 위치 보기' }}
        </summary>
        <p class="mt-3 text-sm leading-relaxed text-slate-600 dark:text-slate-300">
            {{
                training
                    ? '자동 준비된 질문·근거·정답·검토 기록을 보냅니다. 비밀번호·토큰·개인정보·고객 원문은 제거해야 합니다.'
                    : '기존 분석 결과가 자동으로 채워집니다. 처음부터 작성하지 말고 잘못된 내용만 수정하세요. 수정하지 않은 항목도 함께 보존됩니다.'
            }}
        </p>
        <dl class="mt-4 grid gap-4">
            <div v-for="item in training ? trainingItems : reviewItems" :key="item[0]" class="min-w-0 border-t border-slate-100 pt-3 dark:border-slate-800">
                <dt class="text-sm font-semibold text-slate-800 dark:text-slate-100">{{ item[1] }}</dt>
                <dd class="mt-1 break-words text-xs leading-relaxed text-slate-600 dark:text-slate-300">{{ item[2] }}</dd>
                <dd class="mt-1 break-all font-mono text-xs text-blue-700 dark:text-blue-300">{{ item[0] }}</dd>
            </div>
        </dl>
        <p v-if="!training" class="mt-4 text-xs leading-relaxed text-amber-800 dark:text-amber-300">
            전체 JSON을 직접 수정할 때는 영어 항목 이름·따옴표·쉼표·괄호를 유지하고 필수 항목을 삭제하지 마세요. 형식은 서버가 최종 검사하지만 내용의 사실
            여부는 검토자가 확인해야 합니다.
        </p>
    </details>
</template>
