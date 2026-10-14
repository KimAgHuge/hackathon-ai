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

기존 DB의 `original_result`·`corrected_result`는 최종 조합 응답이다.
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
