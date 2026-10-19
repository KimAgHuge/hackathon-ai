<template>
    <section
        class="w-full min-w-0 rounded-xl border border-blue-100 bg-blue-50/60 p-4 dark:border-blue-900 dark:bg-blue-950/20"
        aria-label="학습 데이터 전송 안내"
    >
        <h3 class="font-semibold text-slate-900 dark:text-slate-100">이 분석을 AI 학습 자료로 보내기</h3>
        <p class="mt-2 text-sm leading-relaxed text-slate-700 dark:text-slate-300">
            요구사항, 수집한 소스·DB 구조 근거, 승인한 답변을 묶어 AI 답변 개선에 사용할 자료로 보냅니다. 전송은 선택사항이며, 분석 승인만으로 자동 전송되지
            않습니다.
        </p>
        <ol class="mt-4 grid gap-3 sm:grid-cols-3" aria-label="학습 데이터 준비 순서">
            <li class="min-w-0 rounded-lg border border-blue-100 bg-white p-3 dark:border-slate-700 dark:bg-slate-900">
                <p class="text-sm font-semibold text-slate-900 dark:text-slate-100">1. 내용 확인</p>
                <p class="mt-1 text-xs leading-relaxed text-slate-600 dark:text-slate-300">답변과 근거를 대조하고 비밀정보·개인정보를 제거합니다.</p>
            </li>
            <li class="min-w-0 rounded-lg border border-blue-100 bg-white p-3 dark:border-slate-700 dark:bg-slate-900">
                <p class="text-sm font-semibold text-slate-900 dark:text-slate-100">2. 전송 전 검사</p>
                <p class="mt-1 text-xs leading-relaxed text-slate-600 dark:text-slate-300">
                    필수 항목과 근거·형식을 검사합니다. 이때는 파일을 보내지 않습니다.
                </p>
            </li>
            <li class="min-w-0 rounded-lg border border-blue-100 bg-white p-3 dark:border-slate-700 dark:bg-slate-900">
                <p class="text-sm font-semibold text-slate-900 dark:text-slate-100">3. 학습 서버로 전송</p>
                <p class="mt-1 text-xs leading-relaxed text-slate-600 dark:text-slate-300">
                    공유 폴더를 통해 파일을 전달하고 학습 서버의 수신·검증 결과를 확인합니다.
                </p>
            </li>
        </ol>
        <p class="mt-3 text-xs leading-relaxed text-slate-600 dark:text-slate-300">
            여기서는 데이터만 전달합니다. 모델 학습·평가와 서비스 모델 적용은 별도로 진행합니다.
        </p>
    </section>
</template>
