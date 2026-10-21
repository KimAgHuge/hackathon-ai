# 승인 자료 전달·분할·평가

맥 스튜디오(스튜디오)는 AI 분석/추론 서버이고, 맥북은 학습 서버다. 기본 흐름은 **분석 결과 승인 → 학습 자료 검토·비식별화 → 전송 전 검사 → NAS 게시 → 맥북 학습 서버 수신·검증**이다. 분석 승인·파일 수신·학습 실행·새 모델 적용은 별도 작업이다. 수신 뒤의 학습은 [서버 안내](README.md), 완성 모델 전달은 [생성·적용 안내](MODEL_EXPORT.md)를 따른다.

## 학습 사례 계약

`examples/reviewed_case.template.json`을 기준으로 JSON 객체 한 건을 한 줄에 저장한다. 승인되지 않은 모델 응답이나 합성 시험 사례를 업무 정답으로 자동 채택하지 않는다.

| 필드 | 내용 |
|---|---|
| `caseId` | 중복되지 않는 비식별 사례 ID |
| `projectGroup` | 같은 프로젝트의 모든 사례에 쓰는 비식별 ID |
| `sourceModel` | 실제 분석을 생성한 원래 모델 식별자 |
| `humanApproved`, `sanitized` | 사람 검토·비식별화 후에만 boolean `true` |
| `approval` | 비식별 검토자 ID·시간대 포함 승인 시각·판단 이유 |
| `snapshot` | 실제 40자리 소스 Git SHA·요구사항 버전·시간대 포함 DB 수집 시각 |
| `request` | 실제 모델 입력의 프로젝트 ID·`sourceSummary`·`databaseSummary`·`relations` |
| `answers.general` | 검토한 일반 단계 `RequirementGeneralAnalysisResponse` 정답 |
| `answers.database` | DB 관련성이 `RELATED`·`UNCERTAIN`일 때 검토한 `DatabaseImpact` 정답 |

`sourceModel`이 없는 과거 자료는 모델별 학습에서 제외한다. 새 학습 모델의 분석은 `sourceTrainedModel`에 실제 실행 모델 이름·엔진 해시를 추가한다. 구형 직접 어댑터 실행의 `sourceAdapter`를 포함해 생성 출처는 임의로 고치지 않는다.

검토자 ID는 이메일 대신 `reviewer-001` 같은 비식별값을 사용한다. 실제 DB 업무 행·개인정보·비밀번호·토큰은 넣지 않는다. 알려진 민감정보 패턴을 검사하지만 자동 비식별화나 승인자 인증을 대신하지 않는다.

일반 정답에는 기술 요약과 별도로 고객·PM용 한 줄 `plainLanguageSummary`가 필요하다. 최종 조합 응답을 두 단계의 정답으로 그대로 쓰지 않는다. 최종 응답에서 빠진 `databaseRelevance`·`databaseRelevanceReason`은 실제 단계 응답이나 사람 검토로 확인하며 추정해 복원하지 않는다.

DB 상태는 `CHANGE_REQUIRED`, `NO_CHANGE`, `NEEDS_CLARIFICATION`, `UNKNOWN`, `NOT_APPLICABLE`이다. 구체적인 변경안은 `CHANGE_REQUIRED`에만 두며 `NEEDS_CLARIFICATION`에는 확인 질문과 최소 두 조건별 경로가 필요하다. 인덱스 추가는 `ADD_INDEX`, 변경 필요성은 `necessity`의 `REQUIRED`·`RECOMMENDED`로 구분한다. 권장 개선만으로 필수 변경을 확정하거나 같은 대상의 중복 추가를 정답에 넣지 않는다.

## 화면에서 검토·전송

승인된 변경 분석의 **검토 내역·학습 데이터 → 학습 데이터 검토하기**에서 초안을 연다. 프로젝트 관리·분석 승인 권한을 확인하며 미승인·반려 결과나 구조 분석은 전송하지 않는다. 프로젝트·검토자 식별자는 서버가 비식별화해 채운다.

1. 일반·DB 단계 정답, 소스 근거, 당시 DB 수집 시각을 확인한다. 저장된 단계 응답·승인 수정본·수집 완료 기록으로 보완한 값도 출처와 실제 내용을 검토한다.
2. 누락 정보는 **누락 정보 보완**에서 수정한다. 현재 시각이나 분석 완료 시각을 DB 수집 시각 대신 넣지 않는다. **저장된 근거로 빈 항목 채우기**는 편집한 값을 덮지 않고 빈 항목만 보완한다.
3. 사람 검토·비식별화 확인란을 선택하고 **전송 전 전체 검사**를 실행한다. 내용을 바꾸면 확인란을 다시 선택한다.
4. **AI 학습 서버로 데이터 전송**을 요청해 맥북 학습 서버의 게시·수신 상태를 확인한다. 한 분석 작업에서 전송한 내용을 다른 내용으로 덮어쓰지 않는다.

검토 중 JSON은 브라우저에 저장하지 않는다. 별도 검토가 필요하면 **파일로 따로 검토하기**에서 초안을 내려받는다. 초안 전체를 공유 폴더에 올리거나 학습 입력으로 쓰지 않는다. 로컬에서 보완한 `caseDraft`는 새 파일로 확정한다.

```bash
ai-server/.venv/bin/python training/finalize_draft.py \
  --input '/로컬/경로/training-case-draft-<jobId>.json' \
  --output training/datasets/reviewed_case_001.jsonl
```

### 근거 보강과 입력 범위

정답이 인용한 파일·줄이 원래 입력에 없으면 **학습 입력에 없는 근거**에서 확인한다. **원본 커밋 근거 확인·보강**은 저장된 저장소·권한·고정 커밋을 확인해 짧은 근거를 학습 사례 사본의 `request.sourceSummary.reviewedEvidence`에 추가한다. 기존 분석 결과·입력은 수정하지 않고 DB 수집·AI 재분석도 실행하지 않는다. 전송 직전에도 원본 커밋에서 다시 확인한다.

보강은 최대 20개이며 경로 이탈·숨김 파일·키 파일·심볼릭 링크·큰 파일·잘못된 줄을 거부한다. 원래 커밋을 읽을 수 없으면 다른 커밋이나 임의 줄로 대신하지 않는다. 줄을 명시한 인용은 같은 파일·줄이 있어야 하고, 파일 단위 인용도 실제 소스 근거와 맞아야 한다. 요구사항에 적힌 파일명만으로 소스 근거가 되지는 않는다.

현재 메시지 형식은 `messageFormatVersion=3`이다. 일반 단계는 테이블 개요·소스 연결, DB 단계는 상세 구조·같은 소스 연결을 사용한다. 일반 정답에 입력에 없는 컬럼·인덱스 판단을 섞지 않는다. `databaseReviewPlan`이 있으면 검증한 범위와 직접 연결된 외래키 이웃을 DB 입력에 사용하고, 없는 사례는 전체 범위를 사용한다. 실제 DB 정답이 그 범위를 벗어나지 않는지 검토한다.

계약 검사는 문장의 사실성이나 모든 단계별 근거 일치를 보장하지 않는다. 전체 변환 경로에서는 입력 제한으로 근거가 빠질 수 있으므로 변환된 메시지까지 대조한다. 웹 시험용 `trial_dataset.py`는 일반 단계의 실제 메시지 인용 근거를 다시 검사한다. 검사 통과를 위해 근거·정답을 잘라내지 않는다.

## 공유 폴더 자동 전달

현재 공유 NAS는 스튜디오와 맥북에서 동기화하는 iCloud Drive 폴더다. 회사 자료의 클라우드 저장 조건을 확인하고 승인·비식별화한 자료만 사용한다. 공유 폴더는 원문을 HTTP로 보내는 대신 파일을 전달하는 공간이다.

```text
화면 → 백엔드 권한·승인 확인 → 스튜디오의 AI 분석/추론 서버 계약 검사·outbox 보존
     → 공유 폴더에 해시 이름으로 게시 → 해시·크기·건수만 서명해 맥북 학습 서버에 알림
     → 동기화 대기 → 맥북 학습 서버 파일·계약 검사 → incoming 확정 → 화면 수신 완료
```

스튜디오의 AI 분석/추론 서버 `ai-server/.env`에 다음 값을 설정한다. 맥북 학습 서버의 `training/.env`에는 그 장비에서 보이는 공유 폴더와 같은 전송 키를 설정한다.

```dotenv
DOENAAI_TRAINING_SHARED_DIR=<이 장비에서 보이는 공유 폴더 절대 경로>
DOENAAI_TRAINING_RECEIVER_URL=http://<맥북 주소>:8011
DOENAAI_TRAINING_TRANSFER_KEY=<두 서버의 같은 32자 이상 전송 키>
```

서명에는 `X-Transfer-Timestamp`·`X-Transfer-Signature`를 사용하며 두 장비의 시계 차이는 5분 이내여야 한다. 브라우저에 키를 보내거나 Git·로그에 기록하지 않는다. 설정 변경 후 관련 서버를 재시작한다.

게시 전에 수신 서버의 서명된 `GET /api/training/capabilities`에서 사례에 필요한 기능을 확인한다. 현재 계약 기능은 `FILE_LEVEL_EVIDENCE`, `DATABASE_SCOPE_REVIEW`, `DATABASE_CHANGE_SEMANTICS_V2`, `DATABASE_REVIEW_PLAN_V1`, `SOURCE_MODEL_V1`, `SOURCE_ADAPTER_V1`, `SOURCE_TRAINED_MODEL_V1`이다. 필요한 기능이 없거나 확인할 수 없으면 `RECEIVER_UPDATE_REQUIRED`로 중단하며 일부 파일만 복사해 계약을 맞추지 않는다.

맥북 학습 서버는 알림을 보존하고 5초마다 공유 폴더를 확인한다. 파일이 없으면 `WAITING_FOR_FILE`, 동기화 중이면 `WAITING_FOR_SYNC`로 대기한다. 해시·크기·건수·계약이 맞아야 `training/datasets/incoming/<SHA256>.jsonl`로 확정한다. 스튜디오의 AI 분석/추론 서버는 미완료 알림을 15초마다 재시도한다.

`VERIFIED`는 수신·검증 완료다. 게시 성공이나 파일 이름 표시만으로 전달 완료를 판단하지 않는다. `GET /api/training/intake`의 `READY_FOR_DATASET`도 학습 대기 자료가 있다는 뜻이다. 원본·outbox·수신 기록은 보존하며 자동으로 학습을 시작하지 않는다. 로그는 상태 변화·안전한 오류 코드만 표시하고 키·원문·전체 로컬 경로·예외 원문을 남기지 않는다.

### 화면 전송을 사용할 수 없는 경우

원격 승인 DB의 일괄 자동 추출은 지원하지 않는다. 사람이 준비한 승인 JSONL을 검사해 outbox로 보관하고, SSH가 준비된 경우 수동 전송할 수 있다. 비밀번호를 명령행에 넣거나 호스트 키 검사를 끄지 않는다.

```bash
ai-server/.venv/bin/python training/transfer_cases.py stage \
  --input training/datasets/reviewed_cases.jsonl

ai-server/.venv/bin/python training/transfer_cases.py send \
  --package 'training/datasets/outbox/<SHA256>.jsonl' \
  --remote '사용자@맥북-SSH-주소' \
  --remote-repo '/Users/사용자/IdeaProjects/hackathon-ai'
```

실패하면 원본을 보존한 채 같은 `send`를 다시 요청한다. 직접 안전하게 복사했다면 맥북의 `training/datasets/transfer-staging/<SHA256>.jsonl.part`를 `transfer_cases.py receive --staged <경로>`로 확정한다. 검사 전 `.part`를 incoming으로 옮기지 않는다. 수신 확인의 해시·건수가 같아야 완료이며 동일 파일 재수신은 중복 처리한다.

## 자료 분할·변환

일반 변환 도구는 프로젝트 단위 분리이며 최소 세 프로젝트 그룹이 필요하다. 같은 프로젝트의 요구사항 버전·유사 사례는 같은 분할에 둔다. 중복 ID·같은 입력·미배정 프로젝트·빈 분할·기존 출력 폴더를 거부한다. `examples/splits.template.json`으로 배정표를 만든다.

```bash
training/.venv/bin/python training/export_dataset.py \
  --input 'training/datasets/incoming/<SHA256>.jsonl' --validate-only

training/.venv/bin/python training/export_dataset.py \
  --input training/datasets/reviewed_cases.jsonl \
  --splits training/datasets/splits.json \
  --output training/datasets/doenaai-v1
```

생성물은 `train.jsonl`, `valid.jsonl`, `test.jsonl`, 분할별 `*_cases.jsonl`, `manifest.json`이다. 일반·DB 단계는 별도 system/user/assistant 메시지로 변환하고 승인자 정보는 넣지 않는다. `UNRELATED` 사례에는 DB 단계 정답을 넣지 않는다.

웹 시험 학습은 같은 프로젝트 안에서도 `changeRequirement.requirementId`로 서로 다른 원 요구사항을 세 그룹에 나눈다. 같은 요구사항의 수정본·후속 질문은 같은 분할에 묶는다. 원 요구사항 ID 누락·중복 입력·분할 간 중복을 거부한다. 현재 웹 학습은 총 세 그룹, 유효한 학습·검증 자료 각각 한 그룹과 18,000토큰 상한을 요구하며 DB 단계는 학습하지 않는다. 길이 초과 자료는 자르지 않고 제외하며 `excludedTestSamples`로 알린다.

## 학습 전후 품질 비교

같은 고정 시험 세트에서 원본 모델 평가를 저장하고, 학습 어댑터를 지정해 동일 조건으로 다시 평가한다. GPU 작업은 웹 학습·모델 생성과 겹치지 않게 실행한다.

```bash
training/.venv/bin/python training/evaluate_model.py run \
  --dataset training/datasets/doenaai-v1 \
  --output training/outputs/evaluation-before \
  --thinking on --reasoning-effort xhigh --max-tokens 2048

training/.venv/bin/python training/evaluate_model.py run \
  --dataset training/datasets/doenaai-v1 \
  --adapter training/adapters/doenaai-v1 \
  --output training/outputs/evaluation-after \
  --thinking on --reasoning-effort xhigh --max-tokens 2048

training/.venv/bin/python training/evaluate_model.py compare \
  --before training/outputs/evaluation-before \
  --after training/outputs/evaluation-after \
  --output training/outputs/comparison-v1.json
```

시험 사례·메시지·스키마·프롬프트·모델 revision·패키지·thinking·입출력 상한·평가 코드가 다르면 비교하지 않는다. 기본 입력 상한은 8192토큰이며 근거를 자동으로 자르지 않는다. JSON 실패·누락·출력 잘림을 제외하지 않고 지표에 반영한다. 중단된 실행은 새 출력 경로에서 다시 평가하며 호환성 전용 어댑터는 업무 평가에 쓰지 않는다.

| 지표 | 의미 |
|---|---|
| `schemaPassRate` | JSON·서버 계약·추가 필드·알려진 민감정보 검사 통과율 |
| `labelAgreementRate` | 승인 정답의 DB 관련성·DB 상태 일치율 |
| `groundedResponseRate` | 유효 JSON과 정적 파일·테이블·컬럼 검사 통과율 |
| `humanReviewFlagRate` | UNIQUE·삭제 표현·한국어·핵심 질문 누락의 사람 검토 표시 |
| `truncationRate` | 정상 종료하지 않은 생성 비율 |
| `meanSeconds` | 고정된 단계의 평균 생성 시간 |

검토 표시는 위험한 제안 비율이 아니며 안전한 문장도 표시될 수 있다. 승인된 단계를 고정 실행하므로 운영의 분기·재시도 포함 지연시간과 다르다. MLX와 Ollama의 스키마 전달 방식도 달라 같은 조건으로 간주하지 않는다. `predictions.jsonl`과 실제 근거를 사람이 대조해 DB 판단·잘못된 변경 제안·소스 범위·확인 질문·한국어 품질을 확인한다.

원본 답변·토큰·시간·최대 메모리·상세 지표·버전 정보는 Git에서 제외한 `training/datasets`·`training/outputs`에 보관한다. 형식 통과·손실 감소·재로딩 성공만으로 업무 품질 개선을 확정하지 않는다.
