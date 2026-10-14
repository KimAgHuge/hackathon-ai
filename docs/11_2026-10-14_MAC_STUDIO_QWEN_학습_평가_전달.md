# Mac Studio → MacBook: Qwen 운영 설정과 학습 전 기준

확인일: 2026-10-14. 이 문서는 Mac Studio에서 확인한 운영 사실을 전달한다. **학습 데이터가 준비됐다는 뜻은 아니다.** 모델 선택 이유는 [10번 문서](10_2026-10-14_AI_모델_선정_근거.md)를 참고한다.

## 1. 실제 운영 모델과 호출 설정

| 항목 | Mac Studio 확인값 |
|---|---|
| Ollama CLI·서버 | `0.34.2` (`ollama --version`) |
| Ollama 모델 | `qwen3.8:27b`, 로컬 ID `22130167c4c2`, 설치 크기 약 17GB |
| 모델 메타데이터 | 27.3B, `qwen35` 내부 아키텍처, `Q4_K_M` 양자화, 최대 컨텍스트 262144토큰, text/vision/tools/thinking 지원 (`ollama show qwen3.8:27b`) |
| 학습 원본 계열 | [Qwen/Qwen3.8-27B](https://huggingface.co/Qwen/Qwen3.8-27B). 운영용 Ollama GGUF 파일과 별도이다. |
| MLX 4비트 후보 | [mlx-community/Qwen3.8-27B-4bit](https://huggingface.co/mlx-community/Qwen3.8-27B-4bit). MacBook의 MLX-LM 0.32.0에서 실제 역전파·LoRA 학습 성공 여부는 아직 검증하지 않았다. |
| AI 서버 모델 설정 | 로컬 `ai-server/.env`와 [설정 기본값](../ai-server/app/config.py)은 `qwen3.8:27b` |
| 추론 방식 | [호출 코드](../ai-server/app/services/llm_service.py)는 `think`를 **명시하지 않는다**. Qwen/Ollama의 기본 thinking 동작을 사용한다. 짧은 직접 호출에서 thinking 필드가 생성되는 것을 확인했다. 이전 비교에서는 이를 ‘추론 켬’으로 기록했다. |
| 컨텍스트 | 모델 지원 최대 262144토큰. AI 서버는 `num_ctx`를 지정하지 않는다. 실제 로드 시 값은 `ollama ps`로 재확인해야 한다. |
| 출력 토큰 상한 | AI 서버는 `num_predict`를 지정하지 않는다. **운영 코드에 고정된 출력 상한은 없다.** |
| 샘플링 | 모델 Modelfile의 `temperature=1`, `top_k=20`, `top_p=0.95`, `min_p=0` 중 AI 서버가 매 호출 `temperature=0`으로 덮어쓴다. 나머지는 호출 코드에서 재지정하지 않는다. |
| 시간·재시도 | Ollama 호출당 300초, 검증 실패 시 1회 재시도, `keep_alive=30m` (로컬 `.env`와 코드 확인). |
| 구조화 출력 | 두 호출 모두 `format=response_type.model_json_schema(by_alias=True)`로 JSON Schema를 Ollama에 전달하고 Pydantic으로 다시 검증한다. |

`ollama show --modelfile qwen3.8:27b`에서 확인한 이 설치본의 핵심 설정은 다음과 같다. 원본 출력의 `FROM /Users/.../.ollama/models/blobs/...` 두 줄은 이 Mac에만 유효한 가중치·프로젝터 경로라 제외했다. 전체 Apache 2.0 라이선스 문구도 반복하지 않았다.

```text
FROM qwen3.8:27b
TEMPLATE {{ .Prompt }}
RENDERER qwen3.8
PARSER qwen3.5
PARAMETER top_k 20
PARAMETER top_p 0.95
PARAMETER draft_num_predict 4
PARAMETER min_p 0
PARAMETER presence_penalty 0
PARAMETER repeat_penalty 1
PARAMETER temperature 1
```

`ollama show --template qwen3.8:27b`의 출력은 **`{{ .Prompt }}`** 한 줄이다. Ollama의 `RENDERER qwen3.8`가 실제 메시지를 렌더링하므로 이것만으로 전체 직렬화 규칙을 복원할 수는 없다. MacBook에서 HF/MLX로 비교할 때는 [공식 체크포인트의 chat_template.jinja](https://huggingface.co/Qwen/Qwen3.8-27B/blob/main/chat_template.jinja)를 사용하되, Ollama 출력과 동일하다고 가정하지 말고 같은 짧은 메시지로 확인한다. 비밀키는 이 문서에 포함하지 않았다.

기본 thinking 확인용 단문 시험은 `think`를 생략하고 `temperature=0`, **시험에만** `num_predict=120`을 지정했다. 17.31초 후 thinking 583자·본문 0자·생성 120토큰이 나왔다. 이는 thinking이 켜져 있고 짧은 출력 상한은 답변 본문을 비워버릴 수 있음을 보여준다. 운영 API에는 이 120토큰 상한을 설정하지 않았다.

## 2. 분석 입력·출력 계약

- Backend가 실제로 전달하는 입력은 `projectId`, `sourceSummary`, `databaseSummary`, `relations`다. `sourceSummary`에 변경 요구사항과 소스 근거·`promptCoverage`가 포함된다. [입력 구성 코드](../backend/src/main/java/com/doenaai/analysis/service/AnalysisPromptBuilder.java), [요청 모델](../ai-server/app/models/analysis.py)을 기준으로 한다.
- 변경 요구사항이 있으면 먼저 `RequirementGeneralAnalysisResponse`(요약·프레임워크·위험·영향 영역·근거·권고·DB 관련성)를 생성한다. `RELATED` 또는 `UNCERTAIN`이면 별도 `DatabaseImpact`(상태·설명·관찰 테이블·제안 변경·조건별 경로·확인 질문)를 생성한다. 최종 API 응답은 `ProjectAnalysisResponse`다. [분석 로직](../ai-server/app/services/llm_service.py)과 [스키마](../ai-server/app/models/analysis.py)를 참고한다.
- 학습용 정답을 만들 때 일반 분석과 DB 분석의 필수 필드를 각각 충족해야 한다. 요구사항이 애매하면 확정 변경보다 `NEEDS_CLARIFICATION` 및 대안별 영향·질문을 정답으로 삼는다. 개인정보·접속 비밀·업무 데이터 행은 넣지 않는다.

## 3. 사람이 승인한 학습 사례의 현재 상태

- 저장소에는 **JSONL 학습 데이터가 없고**, `training/export_dataset.py`와 `training/train_lora.py`는 placeholder다.
- DB 스키마의 `public.analysis_results`에는 `original_result`, `corrected_result`, `approved`, `approved_by`, `approved_at`, `review_note`, `inference_metadata` 필드가 있다. 현재 Backend는 원본 응답과 실제 전달 근거(`inference_metadata.facts`)를 저장하지만, 저장소 코드에서는 사람이 수정·승인하는 API/화면을 확인하지 못했다. [결과 저장 코드](../backend/src/main/java/com/doenaai/analysis/repository/AnalysisResultRepository.java), [DB 스키마](../supabase/migrations/002_projects_analysis.sql) 참조.
- 따라서 **승인된 사례 건수·승인 정답·수정 이유·프로젝트/요구사항 버전의 학습용 묶음은 아직 전달할 수 없다.** 원격 DB의 승인 건수는 조회하지 않았다. 단순 모델 출력을 승인 정답으로 표시하거나 임시 데이터를 정답처럼 학습시켜서는 안 된다.
- 다음 데이터 작업은 승인 기준·검토 책임자를 정하고, 입력 당시의 요구사항 버전·소스 커밋·실제 전달 facts·원본 응답·수정 정답·수정 이유를 연결해 비밀정보를 제거한 뒤 학습/검증/시험으로 분리하는 것이다.

## 4. 학습 전 Qwen 기준 결과

2026-10-14에 같은 저장소 발췌 근거로 ‘한 팀만 프로젝트를 관리’ 질문을 [실제 AI 서버의 두 단계 로직](../ai-server/app/services/llm_service.py)에 직접 보낸 이전 시험이 있다. 정확한 입력 snapshot과 원본 응답은 [Qwen 기준 JSON](evaluation/qwen3.8_27b_team_management_baseline.json)에 보관했다. 일반 분석 **144.26초**, DB 분석 **204.69초**, 합계 **348.95초**였다. 최종 JSON 검증은 통과했고, 위험도는 `HIGH` 78점, DB 판단은 `NEEDS_CLARIFICATION`이었다. 재시도 횟수와 출력 토큰 수는 그때 별도로 기록하지 않아 **미확인**이다.

DB 분석은 ‘팀=기존 조직’과 ‘팀=별도 엔티티’ 경로를 분리하고, `projects.organization_id`의 NULL 가능성과 기존 데이터 이관 위험을 언급했다. 반면 **공통 분석은 팀을 조직으로 먼저 단정하고, 이미 조직 구성원을 조인하는 접근 로직의 변경을 권하는 등 근거가 약했다.** 기존 프로젝트 삭제를 선택지에 넣은 문장도 있었다. 이 응답은 비교용 **미승인 원본**이지 학습 정답이 아니다.

시험은 실제 Frontend → Backend → AI 서버의 운영 작업이 아니라, 저장소에서 발췌한 네 테이블·두 관계·세 구성요소를 `LlmService`에 직접 보낸 재현이다. 실제 DB 업무 행은 조회하지 않았고 Git 커밋 SHA도 당시 기록하지 않았다. MacBook에서 같은 질문을 평가에 사용하려면 입력 snapshot과 모델·코드 버전을 고정해 새 기준 시험을 만드는 것이 좋다.

## 5. MacBook 쪽에 남은 확인 사항

1. `Qwen/Qwen3.8-27B` 또는 MLX 4비트 후보를 **다운로드·로딩**하고, 설치된 MLX-LM 버전에서 아주 짧은 text-only 역전파 시험으로 LoRA/QLoRA 호환성을 확인한다. Ollama의 `Q4_K_M` GGUF를 MLX 학습 파일로 직접 사용하지 않는다.
2. 승인된 사례의 존재·권한·비식별화 기준을 확인한다. 현재 확보된 정답 데이터가 없으므로 운영 출력만으로 학습을 시작하지 않는다.
3. 동일한 입력과 응답 스키마로 학습 전후의 DB 판단 정확도·위험한 변경 제안·근거·한국어 JSON·시간·메모리를 비교한다. MLX 어댑터를 Mac Studio Ollama에 적용하는 배포·재양자화 경로는 별도 검증이 필요하다.
