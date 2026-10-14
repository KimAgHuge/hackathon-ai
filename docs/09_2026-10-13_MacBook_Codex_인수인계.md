# MacBook Codex 인수인계 — 분석 품질 검증부터 이어가기

> 2026-10-14 전체 제품 재점검: 학습 데이터 대기와 별개로 Frontend·검토·견적 개발은 남아 있다. [현재 진행상황](03_현재_진행상황.md)과 [Frontend 점검](13_2026-10-14_Frontend_구현_점검.md)을 함께 읽는다.

> 2026-10-14 MacBook 작업 갱신: 학습 환경·Qwen 로딩·짧은 QLoRA 검증과 승인 데이터 변환·평가 도구를 구현했다. [12번 진행상황](12_2026-10-14_MacBook_학습_도구_진행상황.md)을 먼저 읽는다. 아래 학습 보류·placeholder 설명은 10월 13일 당시 기록이며, 실제 업무 학습은 승인 데이터 수신 후 진행한다.

기준일: 2026-10-13. 이 문서는 새 MacBook에서 같은 저장소를 연 Codex가 **현재 구현과 미검증 사항을 구분하고 다음 작업을 이어가기 위한 시작점**이다. 작성 전 로컬 `main` HEAD는 `f8af631`이며 작업 트리는 깨끗했다. 이 문서의 커밋·원격 반영 여부는 MacBook에서 별도로 확인한다.

> 2026-10-14 모델 선택 갱신: 아래 Devstral 설정·품질 평가는 작성 당시 기록이다. 현재 분석 기본 모델은 Qwen3.8 27B로 결정했다. [AI 모델 선정 근거](10_2026-10-14_AI_모델_선정_근거.md)의 비교 결과와 남은 검증 과제를 먼저 읽는다.

## 1. 먼저 읽을 파일과 순서

| 순서 | 파일 | 확인할 내용 |
|---|---|---|
| 1 | 이 문서 | 현재 상태, 우선 작업, 재현·완료 기준 |
| 2 | [`08_2026-10-13_AI_분석과_맥북_학습_인수인계.md`](08_2026-10-13_AI_분석과_맥북_학습_인수인계.md) | Mac Studio 추론과 MacBook 학습 역할, DB 분석 분기, 실모델 검증 결과 |
| 3 | [`07_팀원_온보딩_프로젝트_설명.md`](07_팀원_온보딩_프로젝트_설명.md) | 제품 목적, 서버 구성, 계정·DB 구분, 팀 설명 자료 |
| 4 | [`06_2026-10-12_분석_Worker_구현_인수인계.md`](06_2026-10-12_분석_Worker_구현_인수인계.md) | Backend Worker, Git·DB 수집, 결과 저장과 실행 방법 |
| 5 | [`README.md`](../README.md), [`02_시스템_아키텍처.md`](02_시스템_아키텍처.md) | 모듈 실행과 상세 아키텍처 |

`03`~`05` 문서는 이전 시점의 기록이다. 현재 구현 여부가 충돌하면 이 문서와 `06`~`08`, 그리고 실제 코드를 우선 확인한다. 비개발자에게 서버 구성을 보여줄 때는 `08` 문서의 `assets/doenaai-server-overview-with-training-v2.png`를 사용한다. 그 그림은 목표 구성을 설명하는 자료이며 구현 완료를 보증하는 상태표가 아니다.

## 2. 지금 가능한 흐름과 장비 역할

- Frontend(Vue)는 조직·프로젝트·Git·DB 연결을 등록하고 변경 요구사항의 **현재 버전**으로 분석 작업을 시작한다. 실제 API 모드에는 `VITE_USE_MOCK=false`가 필요하다.
- Backend(Spring Boot)의 Worker가 고정 Git 커밋과 저장된 **읽기 전용 DB 계정**으로 소스 및 DB **구조 메타데이터**를 수집한다. SQL·명시적 ORM의 테이블 참조를 정적으로 찾고 AI 입력을 제한·검사한다. 대상 DB의 업무 데이터 행을 모델에 전달하거나 변경 SQL을 실행하지 않는다.
- Mac Studio의 FastAPI가 Ollama/Devstral로 추론한다. 요구사항이 없으면 구조 분석 한 번, 요구사항이 있으면 전체 영향 분석 후 DB 관련성에 따라 DB 전용 호출을 생략하거나 추가한다. Backend가 응답 형식을 재검증하고 결과와 실제 전달 근거를 저장한다.
- 별도 M4 Max MacBook의 학습·평가·모델 배포는 **현재 필수 실행 경로가 아니다.** `training/export_dataset.py`, `training/train_lora.py`는 placeholder이며 MacBook이 없어도 위 추론 흐름은 돌아간다. 모델은 요청마다 근거를 입력받을 뿐, 요청 결과로 자동 학습되지 않는다.

핵심 코드 순서: [`frontend/src/components/requirement/LiveRequirementAnalysis.vue`](../frontend/src/components/requirement/LiveRequirementAnalysis.vue) → [`backend/src/main/java/com/doenaai/analysis/service/AnalysisJobService.java`](../backend/src/main/java/com/doenaai/analysis/service/AnalysisJobService.java) → [`AnalysisWorker.java`](../backend/src/main/java/com/doenaai/analysis/service/AnalysisWorker.java) → [`SourceEvidenceCollector.java`](../backend/src/main/java/com/doenaai/analysis/service/SourceEvidenceCollector.java) / [`DatabaseMetadataCollector.java`](../backend/src/main/java/com/doenaai/project/connection/service/DatabaseMetadataCollector.java) → [`AnalysisPromptBuilder.java`](../backend/src/main/java/com/doenaai/analysis/service/AnalysisPromptBuilder.java) → [`ai-server/app/services/llm_service.py`](../ai-server/app/services/llm_service.py) → [`frontend/src/views/AnalysisProgressView.vue`](../frontend/src/views/AnalysisProgressView.vue). 주요 함수에는 역할·입력·출력 주석을 추가했다.

## 3. 확인된 것과 아직 해결했다고 말할 수 없는 것

- 2026-10-13 기준 자동 검증: AI 서버 pytest **38개**, Backend Gradle 전체 테스트, Frontend 테스트 **14개**와 production build 통과. 이는 모델 답변의 정확도 검증과는 별개다.
- UI 전용 요구사항 샘플은 실제 Devstral 호출에서 `databaseImpact.status=NOT_APPLICABLE`을 반환했고 DB 전용 호출을 생략했다.
- **DB 관련 요구사항의 실모델 품질은 미확정.** 이전에 Devstral이 기존 조직 외래 키에 잘못된 `UNIQUE` 제약을 제안하거나 핵심 질문을 놓쳤다. 이를 거부하고 재요청하는 검사·테스트를 넣었지만 모든 오류를 잡는 것은 아니다. 마지막 DB 전용 시험은 AI 서버의 **호출당 300초 제한**에 걸려 안정적인 성공을 확인하지 못했다.
- 마지막 로컬 상태 확인에서는 Mac Studio의 `/health`와 Ollama `/api/tags`가 각각 HTTP 200이었고 `devstral:latest`, `qwen3.8:27b`가 설치 목록에 있었다. 이는 그때의 프로세스 상태일 뿐 새 PC에서의 연결이나 완료 분석을 보장하지 않는다.
- 새 코드·환경변수는 실행 중인 프로세스에 자동 반영되지 않는다. AI 서버와 Backend를 재시작하고 **새 분석 작업**을 만들어야 한다. 기존 저장 결과는 변경되지 않는다.

## 4. 맥북에서 먼저 할 일 — 학습은 뒤로 미룬다

1. 저장소를 받은 뒤 `git status -sb`, `git log -1 --oneline`으로 브랜치·커밋을 확인한다. 이 문서가 없으면 작성 PC의 문서 커밋과 원격 푸시 여부를 먼저 확인한다. 작성 시점의 로컬 `origin/main` 추적 값은 `f8af631`이었지만 원격 직접 조회는 GitHub 인증 부재로 실패했으므로 푸시 완료라고 가정하지 않는다.
2. `08` 문서와 위 핵심 코드를 읽는다. MacBook에서 개발·테스트를 진행하더라도 **운영 추론 대상은 Mac Studio의 FastAPI**다. MacBook에 학습 모델·데이터셋을 새로 설치할 필요는 없다.
3. 프런트엔드를 실제 API 모드로 실행하고, 프로젝트의 Git 및 읽기 전용 DB 연결을 확인한다. 비밀번호·토큰·내부 API 키는 문서나 Git에 넣지 말고 각 PC의 로컬 설정에서만 관리한다.
4. 변경 요구사항을 세 부류로 시험한다: (a) UI만 바뀌는 요청 → DB 호출 생략 및 `NOT_APPLICABLE`, (b) 명시적 DB 변경 요청 → 수집된 테이블/컬럼에 근거한 판단, (c) “팀”과 “조직”처럼 의미가 애매한 요청 → 임의 변경을 확정하지 않고 `NEEDS_CLARIFICATION`과 확인 질문. 각 작업의 ID, 전체 소요시간, DB 판단, 잘못된 제안 여부, `promptCoverage`, 수집 경고를 **비밀정보 없이** 기록한다.
5. DB 관련 시험이 다시 시간 초과되면 모델 호출별 경과 시간·입력 범위·재시도 여부를 먼저 확인한다. 정확도가 부족하면 Devstral과 다른 설치 모델을 **같은 근거와 질문**으로 비교한다. 시간 제한 증가만으로 근거 누락이나 잘못된 DB 제안이 해결되지는 않는다.

### 시간 제한에 관한 현재 결정 상태

**아직 설정을 변경하지 않았다.** Mac Studio `ai-server/.env`에는 `DOENAAI_AI_REQUEST_TIMEOUT_SECONDS=300`, `DOENAAI_AI_MODEL=devstral`, 검증 재시도 1회가 설정돼 있다. Backend의 기본 전체 HTTP 제한은 `DOENAAI_AI_TIMEOUT_SECONDS=660`초다. 사용자는 견적·위험도 분석이 10분 이상 걸려도 괜찮다고 했으므로 **호출당 900초 시험**을 논의했다. 적용한다면 AI 서버와 Backend 제한을 함께 조정하고 두 프로세스를 재시작해야 한다. 정상적인 두 모델 호출을 기다리려면 Backend에 약 2100초, 두 단계가 모두 한 번씩 재시도하는 최악의 경우까지 허용하려면 약 3900초와 그보다 긴 고착 작업 판정 시간이 필요하다. 이 수치는 제안이지 검증된 운영 설정이 아니다. 한 Worker 프로세스는 작업을 순차 처리하므로 긴 제한은 뒤 작업도 대기시킨다.

큰 프로젝트에서 **시간 제한과 수집 범위는 별개**다. 현재 소스 스캔은 기본 최대 3000파일·총 16 MiB, DB 구조 수집은 100테이블·테이블별 100컬럼·약 30초다. AI 요청은 기본 9만 문자이며 시작 상한이 40테이블·200관계·150구성요소다. 결과의 `sourceSummary.promptCoverage`와 `databaseSummary.truncated`를 확인하고, 누락이 있으면 검색·분할 분석을 설계해야 한다. 제한 시간을 늘려도 생략된 근거가 다시 포함되지는 않는다.

## 5. 재현·검증 명령

MacBook에 필요한 런타임을 준비한 뒤 저장소 루트 기준으로 실행한다. 처음 설치하는 PC에서는 의존성 다운로드가 필요할 수 있다.

```bash
cd ai-server
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m pytest -q

cd ../backend
bash ./gradlew test --rerun-tasks

cd ../frontend
npm ci
npm run build
node --test tests/*.test.js
```

Mac Studio 실행·네트워크 설정은 [`ai-server/README.md`](../ai-server/README.md)를 참고한다. FastAPI의 공개 `/health`는 프로세스 생존만 확인한다. 모델 설치·가용성은 내부 키가 필요한 `/api/models/health`로 따로 확인한다. 다른 PC에서 Mac Studio를 쓸 때는 사설망의 FastAPI 주소를 Backend에 설정하며, Ollama `11434`를 외부에 열지 않는다. Backend와 AI 서버가 서로 다른 PC에 있으면 양쪽의 내부 API 키는 **같은 값**이어야 한다.

## 6. 다음 Codex에 보낼 첫 메시지 예시

> `docs/09_2026-10-13_MacBook_Codex_인수인계.md`를 먼저 끝까지 읽고, 안내된 `08`, `07`, `06` 문서와 현재 Git 상태를 확인해줘. MacBook 학습 구현은 지금 보류한다. 우선 기존 Mac Studio 추론 경로에서 변경 요구사항 세 유형의 DB 영향 판단·정확도·시간 초과를 검증하고, 확인된 사실과 미검증 가설을 구분해서 보고해줘. 비밀키·DB 비밀번호·Git 토큰은 출력하거나 커밋하지 마. 코드 변경이나 모델 교체 전에는 재현 결과와 영향 범위를 먼저 설명해줘.

MacBook 학습 작업을 다시 시작할 때에는 `08` 문서의 승인 데이터·평가·배포 기준을 사용한다. 모델/adapter, 고객 원문, 접속 비밀은 Git에 올리지 않는다.
