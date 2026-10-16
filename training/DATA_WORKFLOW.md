# 승인 데이터 전달·변환·평가 안내

현재 원격 DB에 접속하지 않고 사람이 검토한 로컬 JSONL을 받는다.
실제 업무 정답은 아직 확보되지 않았으며 양식과 테스트 사례를 업무 데이터로 쓰지 않는다.
주석·함수 설명·사용 안내는 한글로 작성했다. 운영 시스템 프롬프트는 기존 값을 재사용한다.

## Mac Studio에서 전달할 사례

`examples/reviewed_case.template.json`의 구조로 사례 한 건을 만든다.
여러 건은 `reviewed_cases.jsonl` 파일에 JSON 객체 한 건씩 한 줄로 저장한다.
각 사례에는 다음 정보가 모두 필요하다.

| 필드 | 작성할 내용 |
|---|---|
| `caseId` | 중복되지 않는 비식별 사례 식별자 |
| `projectGroup` | 같은 프로젝트의 모든 사례에 쓰는 동일한 비식별 식별자 |
| `humanApproved`, `sanitized` | 실제 검토·비식별화 완료 후에만 boolean `true`로 설정 |
| `approval` | 비식별 검토자 ID, 시간대 포함 승인 시각, 판단·수정 이유 |
| `snapshot` | 분석 소스의 실제 40자리 Git SHA, 요구사항 버전 ID, 시간대 포함 DB 구조 수집 시각 |
| `request` | 당시 모델에 전달한 프로젝트 ID와 실제 `sourceSummary`, `databaseSummary`, `relations` |
| `answers.general` | 사람이 검토한 `RequirementGeneralAnalysisResponse` 정답 |
| `answers.database` | 관련성 RELATED/UNCERTAIN일 때 사람이 검토한 `DatabaseImpact` 정답 |

이메일 대신 `reviewer-001` 같은 ID를 쓴다. 식별자는 영문·숫자·점·밑줄·하이픈으로 최대 100자다.
실제 DB 업무 행·개인정보·비밀번호·토큰은 전달하지 않는다.
암호 필드명과 알려진 토큰·이메일 패턴을 검사하지만 모든 민감정보를 자동 탐지하는 것은 아니다.
`sanitized=true`는 사람이 비식별화했다고 확인하는 표시이며 도구가 개인정보를 자동 제거하지 않는다.
승인 표시의 진위·검토자 권한도 로컬 도구가 인증하지 않는다. 승인 책임자는 별도로 정해야 한다.

### 승인 결과에서 초안 만들기

분석 결과 화면에서 프로젝트 관리 권한과 분석 승인 권한이 있는 사용자는 승인된 변경 분석의
`학습 사례 초안 내려받기`를 선택할 수 있다. Backend는 같은 권한을 검사하며,
`GET /api/analysis-jobs/{jobId}/training-case-draft`의 응답을 브라우저 캐시에 보관하지 않는다.
구조 분석이나 미승인·반려 결과는 초안을 만들 수 없다.

다운로드한 JSON의 `caseDraft`는 학습 사례 후보이고 `reviewRequired`는 보완 목록이다.
`humanApproved=false`, `sanitized=false`, `projectGroup=null`, 비식별 검토자 ID와
최종 응답에 저장되지 않은 DB 관련성 판단은 빈칸으로 남는다. 사람이 승인한 수정본이
있으면 AI 원본 대신 수정본을 후보로 사용하지만, 최종 위험도는 DB 단계에서 조정됐을
수 있으므로 단계별 정답을 다시 검토한다. 이전 분석에는 DB 구조 수집 시각이 없을 수
있다. 새 분석은 구조 수집 완료 시각을 근거에 저장한다. 시각이나 판단을 추측해 채우지 않는다.

초안은 로컬에서 열어 `reviewRequired`를 기준으로 근거·정답·비식별화를 확인한다.
검토자가 책임지고 `caseDraft`를 보완한 뒤에만 `humanApproved`와 `sanitized`를
`true`로 설정한다. 초안 JSON 전체를 그대로 공유 NAS에 올리거나 학습 입력으로 쓰지 않는다.
확정 도구는 기존 파일을 덮지 않으며 사례 계약이 맞지 않으면 출력하지 않는다.

```bash
ai-server/.venv/bin/python training/finalize_draft.py \
  --input '/로컬/경로/training-case-draft-<jobId>.json' \
  --output training/datasets/reviewed_case_001.jsonl
```

여러 사례는 검증된 JSONL 한 줄씩을 별도 승인 데이터 파일에 모은다. `caseId`와
실제 분석 입력이 중복되면 이후 `stage`에서 거부한다.

## 승인 화면에서 공유 폴더로 자동 전달

승인된 변경 분석 화면에서 **검토 후 학습 서버로 전송**을 누르면 초안과 보완 목록을 볼 수 있다.
검토자가 소스·DB 근거와 정답을 수정하고 비밀값·개인정보를 제거한 다음
`humanApproved=true`, `sanitized=true`로 표시해야 한다. 두 확인란을 선택하고
전송을 다시 확인해야 게시된다. 서버는 학습 사례 계약을 재검증하므로 표시만 바꿔서는
잘못된 사례를 전송할 수 없다. 전송한 사례는 같은 분석 작업에서 다른 내용으로
덮어쓰지 못한다. 검토 중인 JSON은 브라우저에 저장하지 않으므로 화면을 닫기 전
필요한 경우 초안 파일을 별도로 보관한다.

```text
브라우저 → Backend 권한·승인 검사 → AI 서버 사례 검사·outbox 보존
         → 공유 NAS 역할의 폴더에 SHA-256 파일 게시 → 맥북 수신 API에 해시·건수·크기 알림
         → 맥북은 파일이 없으면 대기 → 동기화 후 해시·사례 계약 검사
         → 맥북 incoming에 확정 → Studio 화면에서 검증 완료 표시
```

이 환경의 임시 공유 NAS는 **두 Mac의 iCloud Drive 동기화 폴더**다. SSH 접속이
되지 않아 채택한 방식이며 실제 NAS 장비라는 뜻은 아니다. 회사 자료의 개인
클라우드 반입 정책을 먼저 확인한다. HTTP 알림에는 사례 원문을 넣지 않고,
별도의 32자 이상 전송 키로 요청을 서명한다. 두 기기의 시계가 5분 이상
어긋나지 않아야 한다. iCloud 동기화는 비동기식이므로 화면에 게시 완료가
떠도 맥북 검증 완료 전에는 전달이 끝난 것이 아니다.

Mac Studio의 `ai-server/.env`에 다음 세 값을 설정한다.

```text
DOENAAI_TRAINING_SHARED_DIR=<Studio에서 보이는 공유 폴더 절대 경로>
DOENAAI_TRAINING_RECEIVER_URL=http://<맥북에서 접근 가능한 주소>:8011
DOENAAI_TRAINING_TRANSFER_KEY=<두 Mac에서 동일한 별도 전송 키>
```

맥북에는 같은 공유 폴더의 **맥북 측 절대 경로**와 같은 전송 키를 환경변수로
설정하고 저장소 루트에서 다음을 실행한다. 키는 Git에 기록하지 않는다.

```bash
training/.venv/bin/python training/receiver_service.py --host 0.0.0.0 --port 8011
```

수신 서비스는 알림을 영속 저장하고 5초마다 공유 폴더를 확인한다. 파일이 아직
없으면 `WAITING_FOR_FILE`로 대기하고, 동기화 후 해시·크기·사례 수·계약을
검사해 `training/datasets/incoming/<SHA256>.jsonl`에 확정한다. Studio AI 서버는
알림이 끊겨도 상태를 보존하고 15초마다 미완료 알림을 재시도한다. Backend와
AI 서버, 맥북 수신 서비스를 새 설정으로 재시작해야 한다. 전송 API는 조회·게시
모두 기존 프로젝트 관리 및 `ANALYSIS_APPROVE` 권한을 확인한다.

수신 완료는 **학습 대기 자료가 생겼다**는 뜻이지 모델 학습 시작이나 정답의
사실 검증 완료를 뜻하지 않는다. 맥북에서 프로젝트별 분할·최소 사례 수·평가
기준을 확인한 뒤 학습을 별도로 실행한다. 파일이 없으면 학습은 시작하지 않고
수신 서비스만 대기한다. 맥북 수신 컨트롤러의 서명된
`GET /api/training/intake`는 검증 파일이 없으면 `WAITING_FOR_DATA`, 있으면
`READY_FOR_DATASET`과 파일·사례 수를 반환한다. 수신함을 처리하는 자동 모델
학습 작업은 아직 연결하지 않았다.

## Mac Studio → MacBook 수동 안전 전송

검토 API의 승인은 전송을 시작하지 않는다. 먼저 승인된 결과와 실제 분석 입력·snapshot을 위 양식으로 묶어
`reviewed_cases.jsonl`을 만든 뒤 사람이 비식별화해야 한다. `training/datasets/`는 Git에 포함되지 않는다.
수동 전송은 화면 자동 전달을 사용할 수 없을 때 선택한다. 현재 운영 DB에서 승인 사례를
JSONL로 일괄 추출하는 기능은 없으므로 이 입력 파일을 별도로 준비해야 한다.

두 장비에 같은 버전의 `training/transfer_cases.py`가 있어야 하며, SSH 접속·호스트 키 확인을 먼저 설정한다.
비밀번호·토큰을 명령행 인자로 넣거나 호스트 키 검증을 끄지 않는다. 맥북 저장소 경로에는 공백이 없어야 한다.
Mac Studio에서는 AI 서버 가상환경의 Python을 사용한다.

```bash
# Mac Studio: 원본을 검사한 뒤 Git 무시 outbox에 해시 이름으로 보관한다.
ai-server/.venv/bin/python training/transfer_cases.py stage \
  --input training/datasets/reviewed_cases.jsonl

# 출력의 package 경로와 SHA-256을 확인해 실제 값으로 바꾼다.
ai-server/.venv/bin/python training/transfer_cases.py send \
  --package 'training/datasets/outbox/<SHA256>.jsonl' \
  --remote '사용자@맥북-SSH-주소' \
  --remote-repo '/Users/사용자/IdeaProjects/hackathon-ai'
```

`send`는 SSH와 rsync로 최대 5회 재시도한다. 맥북은 `transfer-staging/*.part`에서
SHA-256·사례 계약·중복을 검사하고, 성공한 파일만 `incoming/<SHA256>.jsonl`로 원자적으로 이동한다.
수신 폴더는 소유자만 접근할 수 있는 `700`, 확정 파일은 소유자만 읽고 쓸 수 있는
`600` 권한으로 맞춘다. 수동 복사로 권한이 넓어진 파일과 동일 파일의 재수신에도 적용한다.
맥북의 해시·건수 수신 확인이 발신값과 같을 때만 성공을 출력한다. 연결이 끊기거나 수신 확인이 유실되면
**같은 send 명령을 다시 실행**한다. Mac Studio의 원본·outbox는 삭제되지 않고, 이미 수신한 동일 파일은
`already_received`로 처리한다. 실패한 `.part`는 확정 데이터로 취급하지 않는다.
수신 확인이 JSON 객체가 아니거나 건수가 정수가 아니면 성공으로 처리하지 않고 재시도한다.
재시도 한도에 도달해도 원본과 outbox를 보존하며 수신 확인 원문을 오류에 표시하지 않는다.

맥북에서는 수신 확정 파일을 다시 검사한다.

```bash
training/.venv/bin/python training/export_dataset.py \
  --input 'training/datasets/incoming/<SHA256>.jsonl' --validate-only
```

SSH 연결이 없는 경우에도 outbox의 해시 파일을 다른 안전한 경로로 맥북의
`training/datasets/transfer-staging/<SHA256>.jsonl.part`에 복사한 뒤, 맥북에서
`training/.venv/bin/python training/transfer_cases.py receive --staged 'training/datasets/transfer-staging/<SHA256>.jsonl.part'`
를 실행하면 같은 검증·원자적 확정이 적용된다. 단, 이 수동 복사는 자동 재시도되지 않는다.

### 임시 공유 NAS 경유 방식

현재 두 장비 사이에 SSH 연결이 되지 않아, 양쪽에서 접근 가능한 공통 NAS가 있다는
전제로 공유 폴더 경유 방식을 임시 채택했다. 지금 검증한 구현 환경은 동일 Apple 계정의
**iCloud Drive 폴더를 공유 NAS 경로처럼 사용하는 방식**이다. iCloud Drive가 별도의
물리 NAS 장비라는 뜻은 아니다. 두 Mac의 동기화가 켜져 있고 승인·비식별화된 사례를
개인 클라우드에 보관해도 되는 경우에만 사용한다. 비밀번호·토큰·실제 업무 행은 올리지
않는다. Studio의 원본과 outbox는 그대로 보존한다. 현재 SSH 전송 도구가 공유 폴더를
자동 감시하는 것은 아니며, 아래 복사와 맥북 수신 확인은 수동 작업이다.

1. Studio에서 위 `stage` 명령으로 해시 이름의 outbox 파일을 만든다.
2. 그 파일을 현재 공유 NAS 역할을 하는 iCloud Drive의 `DoenaaiTrainingTransfer` 폴더에 복사한다. 복사 중인
   파일은 `<SHA256>.jsonl.uploading`으로 두고, 로컬 복사·해시 확인 후
   `<SHA256>.jsonl`로 이름을 바꾼다. Finder의 iCloud 상태에서 업로드 완료도 확인한다.
3. 맥북에서 파일을 명시적으로 다운로드하고 로컬
   `training/datasets/transfer-staging/<SHA256>.jsonl.part`로 복사한다.
4. 맥북의 `receive --staged ...`를 실행한다. 이 명령은 SHA-256과 사례 계약을
   검사한 뒤에만 로컬 `incoming`에 확정한다. 성공 수신증의 해시·건수를 Studio의
   `stage` 출력과 비교한다. 이름만 보이거나 업로드 대기 중인 파일은 수신 완료가 아니다.

중간에 끊기면 Studio의 원본과 outbox는 지우지 말고 같은 해시 파일을 다시 복사한다.
맥북에서 `receive`가 실패하면 불완전한 `.part`는 확정되지 않는다. 수신증까지 확인하기
전에는 공유 폴더의 파일이나 Studio 사본을 삭제하지 않는다. 이 절의 수동 복사는
실시간 수신 확인과 자동 재시도를 제공하지 않는다. 위 승인 화면의 자동 전달과 혼동하지 않는다.

기존 DB의 `original_result`·`corrected_result`는 최종 조합 응답이다.
새 계약의 `answers.general.plainLanguageSummary`는 기술 요약과 별도로 고객·PM이 이해할 수 있는 한 줄이다. AI 초안을 그대로 승인하지 말고, 영향이나 확정 여부를 과장하지 않는지 사람이 검토한다.
최종 응답에서 제거된 `databaseRelevance`, `databaseRelevanceReason`을 임의로 복원하지 않는다.
검토자가 전체 단계 정답에 해당 필드를 작성하거나 실제 단계별 응답을 확보해야 한다.
`inference_metadata.facts`는 실제 입력 근거의 출발점이고, 소스 SHA·요구사항 버전은 작업 snapshot과 연결한다.
추정값으로 누락된 정보를 채워 승인하지 않는다.

## 데이터 검사 및 분리

`examples/splits.template.json`을 복사해 프로젝트별 배정표를 만든다.
현재 정책은 학습·검증·시험이 서로 다른 프로젝트 그룹을 쓰도록 하므로 최소 세 그룹이 필요하다.
같은 프로젝트의 유사 사례·요구사항 버전은 항상 같은 그룹에 둔다.
중복 ID, ID만 바뀐 동일 입력, 배정되지 않은 프로젝트, 비어 있는 분할을 거부한다.
검증 실패 시 부분 데이터셋을 만들지 않으며 기존 폴더도 덮어쓰지 않는다.

저장소 루트에서:

```bash
# 승인 사례가 적을 때는 분리·변환 전에 검사만 할 수 있다.
training/.venv/bin/python training/export_dataset.py \
  --input training/datasets/incoming/reviewed_cases.jsonl --validate-only

training/.venv/bin/python training/export_dataset.py \
  --input training/datasets/incoming/reviewed_cases.jsonl \
  --splits training/datasets/incoming/splits.json \
  --output training/datasets/doenaai-v1
```

생성물은 `train.jsonl`, `valid.jsonl`, `test.jsonl`, 각 분할의 `*_cases.jsonl`, `manifest.json`이다.
학습 JSONL은 system/user/assistant 대화 형식이며 일반 단계와 DB 단계를 각각 한 건으로 변환한다.
승인자 정보는 모델 메시지에 포함하지 않는다.
DB 관련성 UNRELATED인 사례에는 DB 단계 정답을 넣지 않는다.
서버 Pydantic 모델과 실제 참조된 테이블·컬럼·파일·줄을 재사용해 검사한다.
현재 수집 입력에 없는 파일·줄은 승인 정답에도 근거로 넣을 수 없다.
문장 의미의 정확도와 변경안의 업무 타당성은 사람의 검토가 필요하다.

## 학습 전후 평가

동일한 시험 세트에서 원본 모델 평가를 먼저 저장한다.
GPU 작업은 일반 터미널이나 승인된 샌드박스 외부에서 실행한다.

```bash
training/.venv/bin/python training/evaluate_model.py run \
  --dataset training/datasets/doenaai-v1 \
  --output training/outputs/evaluation-before \
  --thinking on --reasoning-effort xhigh --max-tokens 2048

# 승인 데이터로 학습한 실제 어댑터가 준비된 후 실행한다.
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

모델은 다운로드 도구의 고정 revision manifest를 사용한다.
시험 사례, 렌더링된 메시지, 응답 스키마·프롬프트, 모델 revision, 패키지 버전,
thinking·출력 상한·입력 상한, 평가 코드가 다르면 비교를 거부한다.
기본 입력 상한은 8192토큰이며 초과한 근거를 자동으로 자르지 않는다.
모든 실행에 같은 상한을 지정해야 하며 출력 상한으로 thinking/본문이 잘리면 결과에 표시된다.
JSON 실패·누락된 정답 단계도 제외하지 않고 지표에 반영하거나 평가 오류로 처리한다.
실행이 중단된 `status=running` 결과는 비교하지 않는다. 새 출력 경로에서 다시 실행한다.
호환성 검증용 `compatibility-only` 어댑터는 업무 평가에 사용할 수 없다.

평가는 승인 사례에 있는 단계를 고정해서 실행한다.
전체 단계가 잘못 UNRELATED를 반환해도 DB 단계 자체의 성능을 측정하려고 승인된 DB 단계를 실행한다.
따라서 이 도구의 총시간은 운영 서비스의 분기·재시도 포함 지연시간이 아니다.
Ollama는 별도 `format` 인자로 스키마를 전달하지만 MLX 도구는 시스템 메시지에 스키마를 넣는다.
이 결과를 Mac Studio의 기존 Ollama 측정값과 동일 조건으로 간주하지 않는다.

## 결과 해석

- `schemaPassRate`: JSON·서버 계약·추가 필드·알려진 민감정보 검사 통과율
- `labelAgreementRate`: 승인 정답의 DB 관련성 또는 DB 상태와 일치한 비율
- `groundedResponseRate`: 유효한 JSON이며 정적 파일·테이블·컬럼 검사를 통과한 비율
- `humanReviewFlagRate`: UNIQUE·삭제 관련 표현, 한국어 누락, 핵심 용어 질문 누락의 검토 표시 비율
- `truncationRate`: 정상 종료가 아닌 생성의 비율
- `meanSeconds`: 고정된 단계의 평균 생성 시간

`humanReviewFlagRate`는 위험한 제안 비율이 아니다. “삭제하지 않는다” 같은 안전한 문장도 표시될 수 있다.
일치율이 높다고 소스 범위·설명·질문·한국어 품질이 모두 정확하다는 뜻은 아니다.
사람이 `predictions.jsonl`의 원문과 실제 근거를 대조해 위험·근거 누락·질문 적절성을 별도로 검토한다.
평가별 원본 답변, 토큰 수, 시간, 최대 MLX GPU 메모리, 상세 지표와 버전 manifest를 저장한다.
데이터와 평가 원문은 Git 제외 대상인 `training/datasets`, `training/outputs`에 보관한다.

## 도구 검증

```bash
training/.venv/bin/python -m unittest discover -s training/tests -v
```

합성 fixture는 데이터 계약과 실패 처리의 시험 전용이며 실제 학습·평가 정답이 아니다.
실제 업무 데이터 변환·학습·품질 비교는 승인 사례가 준비된 뒤 수행한다.

2026-10-14 합성 UI 사례 1건으로 새 평가 도구의 실제 Qwen GPU 추론·원문 저장·지표 생성을 확인했다.
모델이 JSON을 Markdown 코드 블록으로 감싼 답변을 반환해 형식 실패로 기록됐다.
이 경로는 정상적으로 실패를 집계했으며, 그 응답을 학습 정답으로 채택하지 않았다.
이 측정은 도구 실행 검증이며 업무 품질 평가가 아니다.
