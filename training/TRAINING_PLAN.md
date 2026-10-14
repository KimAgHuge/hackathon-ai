# Qwen 학습 준비 정보

확인일: 2026-10-14. 로컬 환경·코드로 확인한 사실과 미정 사항을 구분한다.

| 항목 | 채운 정보 | 상태 |
|---|---|---|
| 기반 모델 | `Qwen/Qwen3.8-27B` (접미사 -Instruct 없음), Apache-2.0. 공식 post-trained 모델이며 thinking 기본 활성화. 정확한 revision과 학습·평가 시 thinking 설정은 고정 필요 | 공개 모델 카드 확인 |
| 운영 Qwen | 사용자 전달: `qwen3.8:27b`, 27.3B, Q4_K_M, Ollama ID `22130167c4c2` | Mac Studio 전달 정보, 이 맥에서 원격 재확인하지 않음 |
| 학습 목적 | 우선순위: ① DB 판단 정확도·위험한 변경 방지 ② 소스 범위·근거 정확도 ③ 확인 질문·위험 설명 ④ 한국어 JSON 준수. 근거 없는 UNIQUE, 기존 데이터 삭제, 팀/조직 임의 동일시를 줄임 | 사용자 전달 우선순위 반영 |
| 학습 장비 | Apple M4 Max, 통합 메모리 64 GiB, macOS 26.6.2, arm64. 모델 다운로드 후 디스크 여유 약 217 GiB | 로컬 확인 |
| 학습 환경 | Python 3.12.15, MLX 0.32.3, MLX-LM 0.32.0, datasets 5.1.0, transformers 5.19.0, Pydantic 2.13.4. `training/.venv` 사용 | 설치 완료, Metal 연산·미분 및 CLI 검증 완료 |
| 학습 방식 | `mlx-community/Qwen3.8-27B-4bit` QLoRA. revision `10c35caafbb80f7dc6a7a432cdd11af10a6d4818`로 로딩·단문 추론·4층 rank 4의 2회 업데이트·adapter 저장/재로딩 통과. 시험 peak 16.25 GiB. 상세는 `QWEN_COMPATIBILITY.md` | 짧은 실행 검증 완료, 업무 학습·긴 입력 미검증 |
| 학습 입력 | 전체 분석: `sourceSummary`(요구사항 `changeRequirement` 포함), `databaseSummary`, `relations`. DB 전용 분석: `changeRequirement`, `databaseType`, 테이블·컬럼·PK/FK·인덱스, `promptCoverage` | 코드로 확인 |
| 학습 데이터 | 업무 학습 JSONL은 없음. 승인 로컬 사례 검사·프로젝트 분리·JSONL 변환 도구 구현 완료. 원격 승인 데이터 건수·품질은 미확인. baseline은 `humanApproved=false`인 평가 원본 | 승인 데이터 확보 필요 |
| 응답 형식 | 전체 단계: `RequirementGeneralAnalysisResponse`; DB 단계: `DatabaseImpact`. 최종 API 응답은 `ProjectAnalysisResponse`로 조합 | 코드로 확인 |
| 평가 기준 | 고정 시험 세트의 두 단계 답변 생성·JSON/라벨/정적 근거 검사 및 동일 조건 비교 도구 구현. UNIQUE/삭제 표현과 한국어·핵심 질문 누락은 사람의 검토 표시로 제공. 상세는 DATA_WORKFLOW.md | 도구 구현, 정답 세트·품질 합격 기준 미정 |
| 배포 대상 | Mac Studio M1 Max·64GB, FastAPI → Ollama. 운영 Qwen 정보는 사용자 전달값 사용. MLX adapter의 직접 적용은 가정하지 않으며 병합·변환·재양자화 및 재평가 경로는 미확정 | 전달값 반영, 배포 호환성 미검증 |

## 출력 계약 주의사항

모델은 현재 두 단계로 호출되므로 최종 API JSON을 모든 학습 사례의 정답으로 사용하지 않는다.
전체 단계의 응답은 `summary`, `plainLanguageSummary`(고객·PM용 한 줄 요약), `frameworks`, `riskLevel`, `riskScore`, `impactAreas`,
`evidences`, `recommendations`, `databaseRelevance`, `databaseRelevanceReason`이다.
DB 단계 응답은 `status`, `explanation`, `observedTables`, `proposedChanges`,
`conditionalPaths`, `openQuestions`이다.
서버가 전체 단계의 DB 관련성 필드를 제거하고 `databaseImpact`를 합쳐 최종 응답을 만든다.

`DatabaseImpact.status`는 `CHANGE_REQUIRED`, `NO_CHANGE`, `NEEDS_CLARIFICATION`,
`UNKNOWN`, `NOT_APPLICABLE`이다.
`NEEDS_CLARIFICATION`은 확인 질문과 최소 두 조건별 경로가 필요하며 확정 변경안을 넣지 않는다.
`CHANGE_REQUIRED`만 구체적인 변경안을 포함할 수 있다.

## 학습 사례에 필요한 검토 정보

- 요구사항, 실제 모델에 전달한 근거, 해당 단계의 승인된 정답
- 프로젝트·소스 커밋·요구사항 버전·DB 구조 수집 시점
- 검토자, 승인 상태, 수정 이유와 적용한 프롬프트/응답 계약 버전
- 프로젝트 단위 학습/검증/시험 분리 기준

비밀값과 개인정보를 제거한다. 승인되지 않은 모델 응답을 정답으로 자동 채택하지 않는다.
학습 후보의 revision 고정·로딩·짧은 QLoRA 실행 검증은 완료했다.
실제 업무 학습은 승인 데이터 확보 후 진행한다.

## 공개 모델 확인 결과와 추가 검증

- 원본: https://huggingface.co/Qwen/Qwen3.8-27B
- MLX 후보: https://huggingface.co/mlx-community/Qwen3.8-27B-4bit
- MLX config: https://huggingface.co/mlx-community/Qwen3.8-27B-4bit/raw/main/config.json
- QLoRA 안내: https://github.com/ml-explore/mlx-lm/blob/main/mlx_lm/LORA.md

MLX 후보는 vision-language 모델이며 config의 `model_type`은 `qwen3_5`,
`language_model_only`는 false, 양자화는 affine 4bit/group_size 64이다.
Ollama의 Q4_K_M과는 서로 다른 양자화 형식이다.
로컬 MLX-LM 구현은 `text_config`와 텍스트 가중치를 사용하고 vision 가중치를 제외한다.
텍스트 가중치·토크나이저 로딩, 짧은 QLoRA backward, adapter 저장/재로딩을 실제로 시험했다.
이미지 학습까지 지원한다고 해석하지 않는다.

원본 모델은 thinking이 기본 활성화다. 운영 추론과 학습·평가의 thinking 설정,
chat template, 최종 JSON 처리 방식을 일치시켜야 한다.
모델 카드의 기본 컨텍스트 길이를 64GB 장비의 학습 가능 길이로 해석하지 않는다.

근거: `training/requirements-lock.txt`, `ai-server/app/models/analysis.py`,
`ai-server/app/services/llm_service.py`, `docs/08_2026-10-13_AI_분석과_맥북_학습_인수인계.md`,
`docs/09_2026-10-13_MacBook_Codex_인수인계.md`.
