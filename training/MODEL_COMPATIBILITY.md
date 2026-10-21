# 분석 모델과 학습 기반 모델 연결

맥 스튜디오(스튜디오)는 AI 분석/추론 서버이고, 맥북은 학습 서버다. 화면에서 선택한 분석 모델을 백엔드가 확인한 뒤, 서명된 `GET /api/training/capabilities?model=<식별자>`로 맥북 학습 서버의 대응 기반 모델과 학습 자료를 조회한다. 학습 시작 시에도 모델·버전·학습기 대응을 다시 확인하며, 화면 조회 뒤 조건이 바뀌면 요청을 거부한다. 미등록 모델을 Qwen으로 대신 학습하지 않는다.

## 맥북 학습 서버 설정

Qwen만 쓰면 `training/.env`의 `DOENAAI_TRAINING_MODEL_DIR`에 맥북 학습 서버의 MLX 모델 절대 경로를 지정한다. 이 설정은 `qwen3.8:27b` 전용이다. 스튜디오의 AI 분석/추론 서버에 있는 Ollama 모델 경로를 입력하지 않는다.

여러 모델의 대응을 관리하려면 `model-registry.example.json`을 로컬 파일로 복사하고 다음 값을 설정한다.

```dotenv
DOENAAI_TRAINING_MODELS_FILE=/absolute/path/to/local-model-registry.json
```

등록 파일은 분석 모델의 정확한 식별자를 키로 사용하며 다음 세 항목을 받는다.

| 항목 | 값 |
|---|---|
| `directory` | 맥북 학습 서버에 이미 준비된 MLX 모델 폴더의 절대 경로 |
| `baseModel` | 해당 폴더 `download_manifest.json`의 `modelId` |
| `trainer` | 서버에 구현된 학습기 식별자 |

다운로드 명세의 `revision`은 40자리 고정 버전이어야 한다. 등록 파일의 명시적 대응이 단일 Qwen 경로보다 우선하며, 파일 오류가 나면 다른 설정으로 조용히 대체하지 않는다. 환경변수 변경은 맥북 학습 서버의 수신 서비스 재시작 후 반영한다. 등록 파일의 내용 변경은 다음 조회에 반영되며 이전 화면의 시작 식별값은 무효가 된다.

진행 중인 작업은 접수 당시 기반 모델을 유지한다. 모델 조회나 선택만으로 다운로드·패키지 설치·학습을 실행하지 않는다. 로컬 설정 파일과 모델 가중치는 Git에 넣지 않는다.

## 현재 지원 범위

| 항목 | 지원값 |
|---|---|
| 분석 모델 | `qwen3.8:27b` |
| 학습 기반 모델 | `mlx-community/Qwen3.8-27B-4bit` |
| 기반 모델 revision | `10c35caafbb80f7dc6a7a432cdd11af10a6d4818` |
| 모델 구조 | `qwen3_5`, 텍스트 64층, affine 4bit·group size 64 |
| 학습기 | `qwen-mlp-trial-v1` |
| 학습 환경 | MLX 0.32.3, MLX-LM 0.32.0 |

텍스트 로딩·짧은 추론·QLoRA 업데이트·어댑터 저장·새 프로세스 재로딩을 검증했다. vision 가중치를 사용한 이미지 학습은 지원하지 않는다. MLX의 4bit와 분석 엔진의 `Q4_K_M`은 다른 형식이며, 분석용 파일은 [별도 병합·변환](MODEL_EXPORT.md)을 거친다.

Gemma·EXAONE·Devstral 등은 모델을 등록하거나 라이브러리에서 불러올 수 있어도 구조별 학습기와 로딩·학습·저장·재로딩 검증이 필요하다. 대응 모델이 없으면 `MODEL_NOT_REGISTERED`, 지원하지 않는 학습기는 `TRAINER_UNSUPPORTED`로 안내한다.

## 모델별 자료와 새 모델의 출처

학습 자료는 `sourceModel`이 선택한 원래 모델과 일치해야 한다. 기록이 없는 과거 자료는 모델별 학습에서 제외하며 현재 선택값으로 추정하지 않는다. 분석 결과에서 내려온 `sourceModel`, `sourceAdapter`, `sourceTrainedModel`은 사용자가 다른 모델로 바꿔 전송할 수 없다.

새로 생성한 `doenaai-trained:web-<jobId>` 모델의 분석 자료는 원래 기반 계열을 `sourceModel`로 유지하고, 실행한 새 모델 이름과 실제 엔진 해시를 `sourceTrainedModel`에 추가한다. 이 엔진 해시는 GGUF 파일 해시·전달 명세 해시와 구분한다. 새 모델 이름이 달라도 같은 기반 계열의 자료와 학습 작업을 조회한다.

`READY`는 기반 모델과 학습 환경이 준비됐다는 뜻이다. 승인 자료의 충분함·학습 완료·업무 품질 평가를 뜻하지 않는다. 짧은 시험의 메모리 사용량과 손실 감소를 긴 입력이나 더 넓은 학습 범위에 그대로 적용하지 않는다. 추론과 평가의 thinking·대화 형식·JSON 조건은 따로 고정해 비교한다.

공개 정보: [Qwen 모델](https://huggingface.co/Qwen/Qwen3.8-27B), [MLX 기반 모델](https://huggingface.co/mlx-community/Qwen3.8-27B-4bit), [MLX-LM 학습 안내](https://github.com/ml-explore/mlx-lm/blob/main/mlx_lm/LORA.md).
