# doenaai

고객 요구사항을 프로젝트 소스와 DB 구조에 연결해 영향 범위·위험·예상 작업량을 분석하고,
사람이 검토한 결과를 모델 학습에 활용하는 플랫폼입니다.

## 장비명과 서버 역할

| 장비명 | 서버 역할 |
| --- | --- |
| 맥 스튜디오(스튜디오) | AI 분석/추론 서버 |
| 맥북 | 학습 서버 |

## 구성과 실행

| 영역 | 역할 | 기본 포트 | 안내 |
| --- | --- | --- | --- |
| `frontend` | Vue 웹 화면 | 5173 | [설정과 실행](frontend/README.md) |
| `backend` | Spring Boot 업무 API·권한 검사·분석 워커 | 8080 | 아래 백엔드 준비 |
| `ai-server` | FastAPI 분석·모델 등록과 선택 제어 | 8000 | [설정과 실행](ai-server/README.md) |
| 모델 실행 환경 | 설치 모델의 실제 추론, 현재 Ollama 사용 | 11434 | [스튜디오의 AI 분석/추론 서버 안내](ai-server/README.md) |
| `training` | MLX 학습·완성 모델 생성 | 8011 | [설정과 실행](training/README.md) |
| `supabase` | 인증·업무 DB·마이그레이션 | — | [DB 준비](supabase/README.md) |

두 장비 사이의 NAS 역할은 iCloud Drive 공유 폴더가 맡습니다.

백엔드는 JDK 25를 사용합니다. 최초 설정에서는
[로컬 설정 예시](backend/src/main/resources/application-local.yml.example)를 참고해
`backend/src/main/resources/application-local.yml`을 만들고 DB·JWT·AI 서버 설정을 채웁니다.
실제 설정 파일과 비밀값은 Git에 넣지 않습니다. 기본 실행 프로필은 `local`입니다.

```bash
cd backend
./gradlew bootRun
```

Windows에서는 `.\gradlew.bat bootRun`을 사용합니다.
다른 장비에서 백엔드를 실행할 때는 `doenaai.ai-server.base-url`을 스튜디오의 AI 분석/추론 서버 주소로 설정합니다.
백엔드와 스튜디오의 AI 분석/추론 서버에서 `DOENAAI_AI_INTERNAL_API_KEY`는 같은 값을 사용합니다.
학습 호출에는 별도의 `DOENAAI_TRAINING_RECEIVER_URL`과 `DOENAAI_TRAINING_TRANSFER_KEY`가 필요합니다.

## 분석과 학습 흐름

```text
웹 → 백엔드의 Git·DB 근거 수집 → 스튜디오의 AI 분석/추론 서버 → 모델 응답 → 사람 검토
  → 승인·비식별 검토된 자료 전달 → 맥북 학습
  → 기반 모델과 학습 결과 병합 → GGUF 변환 → NAS 전달
  → 스튜디오의 AI 분석/추론 서버 등록 → JSON 응답 검증 → 새 분석에 적용
```

학습·완성 모델 생성은 맥북 학습 서버에서 수행합니다. 완성 모델을 등록하는 스튜디오의 AI 분석/추론 서버에는 MLX가 필요하지 않습니다.
프론트의 예시 모드와 실제 API 연결 설정은 [프론트 안내](frontend/README.md)를 따릅니다.

## 필요한 문서

| 확인할 내용 | 문서 |
| --- | --- |
| 프로젝트 목적·기능 범위 | [프로젝트 개요](docs/01_프로젝트_개요.md) |
| 서버 구성·분석·학습 데이터 흐름 | [시스템 아키텍처](docs/02_시스템_아키텍처.md) |
| 확인된 구현과 남은 검증 | [현재 진행상황](docs/03_현재_진행상황.md) |
| 여러 백엔드의 분석 작업 처리 | [워커 운영](docs/16_분석_워커_지정_운영.md) |
| 모델 선택·학습·생성·등록·적용 | [AI 모델 관리 사용 안내](docs/18_AI_모델_관리_사용_안내.md) |
| 승인 자료의 계약·전달 | [학습 자료 흐름](training/DATA_WORKFLOW.md) |
| 분석 모델과 학습 기반 모델의 대응 | [학습 모델 호환성](training/MODEL_COMPATIBILITY.md) |
| 맥북 변환 도구·GGUF 생성·등록 | [완성 모델 생성](training/MODEL_EXPORT.md) |

개발 시 작성 언어 규칙은 [AGENTS.md](AGENTS.md)를 따릅니다.
