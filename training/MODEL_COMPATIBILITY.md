# 분석 모델과 학습 기반 모델 연결

맥 스튜디오(스튜디오)는 AI 분석/추론 서버이고, 맥북은 학습 서버다. 화면에서 선택한 정확한 모델 ID를 백엔드가 확인한 뒤, 서명된 `GET /api/training/capabilities?model=<식별자>`로 맥북 학습 서버의 기반 모델·프로필·승인 자료를 조회한다. 학습 시작 시에도 대응을 다시 확인하고 조건이 바뀌면 요청을 거부한다. 미등록 모델을 Qwen으로 대신 학습하지 않는다.

## 맥북 학습 서버 설정

기존 Qwen만 쓰면 `training/.env`의 `DOENAAI_TRAINING_MODEL_DIR`에 맥북 학습 서버의 MLX 모델 절대 경로를 지정한다. 이 설정은 `qwen3.8:27b` 전용이며 스튜디오의 Ollama 모델 경로를 입력하지 않는다.

여러 모델은 [등록 예제](model-registry.example.json)를 로컬 파일로 복사해 연결하고 다음 값을 설정한다.

```dotenv
DOENAAI_TRAINING_MODELS_FILE=/absolute/path/to/local-model-registry.json
```

| 등록 항목 | 의미 |
|---|---|
| 객체의 키 | 화면에서 선택하는 정확한 분석 모델 ID |
| `directory` | 맥북 학습 서버에 이미 준비된 MLX 모델 폴더의 절대 경로 |
| `baseModel` | 해당 폴더 `download_manifest.json`의 `modelId` |
| `trainer` | 서버에 구현된 학습기 ID |
| `profile` | 공통 학습기의 필수 모델별 설정 |
| `resumeJobId` | 선택 사항. 맥북 학습 서버에서 성공한 부모 작업의 UUID |

예제의 `exaone4:example`, `replace-owner/EXAONE4-4bit`와 경로는 작성 형식 예시다. 실제 설치 완료를 뜻하지 않으며 실제 분석 모델 ID·저장소 ID·로컬 경로로 바꿔야 한다. 다운로드 명세의 `revision`은 실제 다운로드에 고정한 40자리 버전이어야 한다.

등록 파일의 명시적 대응이 단일 Qwen 경로보다 우선하며 설정 오류를 다른 모델로 대체하지 않는다. 환경변수 변경은 맥북 수신 서비스 재시작 후 반영하고 등록 파일 변경은 다음 조회에 반영한다. 등록 정보·프로필·부모 어댑터가 바뀌면 이전 화면의 시작 식별값은 무효가 된다. 진행 중인 작업의 기반 모델을 다른 모델로 바꾸지 않는다.

모델 조회만으로 다운로드·패키지 설치·학습을 실행하지 않는다. 경로와 명령은 브라우저에서 받지 않으며 로컬 설정 파일·가중치·토크나이저는 Git에 넣지 않는다.

## 공통 학습기와 모델별 프로필

Python·MLX·MLX-LM 실행 환경은 공유하지만 모델별 가중치·토크나이저·프로필은 따로 준비한다. 선택한 Qwen은 등록한 Qwen 기반 모델로, EXAONE은 등록한 해당 EXAONE 기반 모델로 학습한다. 같은 어댑터를 서로 다른 기반 모델에 적용하지 않는다.

| 학습기 | 실행 범위 |
|---|---|
| `mlx-lora-v1` | 명시한 모델별 프로필을 사용하는 공통 MLX LoRA 시험 학습과 이어 학습 |
| `qwen-mlp-trial-v1` | 기존 Qwen의 긴 입력을 위한 마지막 MLP 1층·rank 4 고정 시험과 이어 학습 |

학습기는 실제 모델의 선택한 모든 층에서 대상 모듈을 확인하고 LoRA 가중치만 갱신한다. 저장 뒤 새 프로세스로 텐서와 확인 출력을 대조한다. 기존 Qwen 학습기의 층 수나 rank를 바꾸려면 공통 학습기와 명시적 프로필을 사용한다.

| 프로필 항목 | 의미와 범위 |
|---|---|
| `modelType` | `config.json.model_type`과 같은 구조 ID. 예: `exaone4`, `llama`, `qwen3` |
| `loraLayers` | 마지막 학습 층 수. 1~128, 실제 층 수 이내 |
| `loraRank`, `loraScale`, `loraDropout` | rank 1~128, scale 0 초과~256, dropout 0~0.5 |
| `loraKeys` | 실제 모듈 경로 목록. 예: `self_attn.q_proj`, `self_attn.v_proj` |
| `maxSequenceLength` | 32~32,768토큰. 상한을 넘는 사례는 정답을 자르지 않고 제외 |
| `batchSize`, `learningRate` | 배치 1~4, 학습률 0.0000001~0.01 |
| `gradientCheckpointing` | 메모리 절감 사용 여부 |
| `exporter`, `exportArchitecture` | 선택 사항. 별도로 검토한 GGUF 변환 경로와 구조 ID |

허용 범위의 값이라도 64GB 메모리에 들어간다고 보장하지 않는다. 새 모델은 작은 입력·배치 1·적은 층 수로 먼저 실행을 확인한다. 토크나이저는 현재 `system`·`user`·`assistant` 대화를 받을 수 있어야 한다. 없는 모듈이나 지원하지 않는 대화 형식은 실패로 처리한다.

## 실제 검증 범위와 배포 지원

| 구분 | 확인한 범위 |
|---|---|
| 실제 Qwen 기반 모델 | `mlx-community/Qwen3.8-27B-4bit`, revision `10c35caafbb80f7dc6a7a432cdd11af10a6d4818` |
| 실제 Qwen 구조와 환경 | `qwen3_5`, 텍스트 64층, affine 4bit·group size 64, MLX 0.32.3·MLX-LM 0.32.0 |
| 실제 Qwen 실행 | 텍스트 로딩·짧은 추론·QLoRA 업데이트·승인 자료의 마지막 MLP 10회 시험 학습·저장·새 프로세스 재로딩 |
| 작은 임의 Llama·EXAONE4 | 2026-10-21~22 CPU에서 4비트 모델의 새 학습 2회 업데이트·재로딩·이어 학습 2회 업데이트·재로딩 통과. 부모 파일 해시 유지, 확인 출력 차이 0 |
| 아직 입증하지 않은 범위 | 실제 대형 EXAONE4의 로딩·긴 입력 학습·업무 품질·스튜디오 배포, 임의 모델 전체의 호환성 |

작은 CPU 검증은 실제 대형 모델이나 운영 품질의 검증을 대신하지 않는다. Qwen의 vision 가중치를 사용한 이미지 학습은 지원하지 않는다. `READY`는 기반 모델과 실행 환경의 준비 상태이며 자료의 충분함·학습 완료·품질 합격을 뜻하지 않는다. 대응 모델이 없으면 `MODEL_NOT_REGISTERED`, 구현되지 않은 학습기이면 `TRAINER_UNSUPPORTED`로 안내한다.

공통 학습 가능 여부와 GGUF 변환 가능 여부는 별도로 표시한다. 변환 프로필이 없어도 학습 조건이 맞으면 학습할 수 있으며 완성 모델 생성만 차단한다. 공통 변환은 `llama`, `mistral`, `qwen2`, `qwen3`, `exaone4`의 검토한 밀집 텍스트 구조와 명시한 선형 LoRA 모듈로 제한한다. `mistral`의 GGUF 구조는 `llama`다. 기존 `qwen3_5`는 고정 기반 버전과 전용 변환기를 유지한다. Gemma·이전 EXAONE·멀티모달·혼합 전문가 구조는 이름만 등록해 변환을 허용하지 않는다.

MLX 4bit와 스튜디오 분석 엔진의 `Q4_K_M`은 다른 형식이다. 완성 모델은 [별도 병합·변환 절차](MODEL_EXPORT.md)를 거치며, 변환 성공도 실제 스튜디오 등록·응답 검증·운영 적용을 뜻하지 않는다. 손실 감소를 업무 정확도 개선으로 단정하지 않으며 thinking·대화 형식·JSON 조건을 고정해 비교한다. 실행과 평가 절차는 [학습 서버 안내](README.md)와 [자료·평가 안내](DATA_WORKFLOW.md)를 참고한다.

## 모델별 자료와 파생 모델의 이어 학습

`selectedModel`은 실제 선택한 모델, `sourceModel`은 승인 자료의 원래 생성 모델이다. 생성 모델 기록이 없는 과거 자료를 현재 선택값으로 추정하지 않는다. 분석 결과의 `sourceModel`, `sourceAdapter`, `sourceTrainedModel`을 사용자가 다른 모델로 바꿔 전송할 수 없다.

새 `doenaai-trained:web-<작업 UUID>`의 분석 자료는 원래 기반 계열을 `sourceModel`로 유지하고 실제 새 모델 이름·엔진 해시를 `sourceTrainedModel`에 기록한다. 엔진 해시와 GGUF 파일 해시·전달 명세 해시는 서로 구분한다. 파생 모델을 선택해도 같은 출처의 승인 자료와 학습 작업을 찾는다.

`doenaai-trained:web-<부모 작업 UUID>` 또는 `doenaai-mlx:web-<부모 작업 UUID>`를 선택하면 맥북 학습 서버의 부모 작업이 `SUCCEEDED`이고 기반 모델·프로필·어댑터가 일치하는지 확인한다. 기반 모델에 부모 어댑터를 한 번 읽어 그 LoRA 가중치를 이어 학습하고 새 작업 경로에 저장한다. 다른 맥북에서는 부모 작업 기록·어댑터·보고서도 검증 가능한 상태로 있어야 한다. 임의의 Ollama 파일에서 학습 상태를 자동 복원하지 않는다.

한 작업 안에서 같은 원 요구사항의 수정 버전·후속 질문은 같은 학습·검증·시험 분할에 묶는다. 여러 번 이어 학습할 때 작업 간 평가 그룹까지 고정하는 정책은 별도 보완이 필요하다. 현재 시험 준비는 일반 단계만 사용하며 운영의 분할 DB 호출별 정답을 복원하지 못한 DB 단계는 보관한다.

## 결과 파일과 서버 반영

모델 ID와 작업 UUID가 식별 기준이며 서버가 정한 새 경로에 저장한다. 기존 기반 모델과 부모 어댑터는 덮어쓰지 않는다.

| 대상 | 서버가 정하는 위치 또는 이름 |
|---|---|
| 원본 MLX 모델 | 등록 파일의 `directory`, 기반 ID·revision은 다운로드 명세로 확인 |
| 작업 자료·프로필·보고서 | `training/outputs/web-jobs/<작업 UUID>/` |
| 작업별 어댑터 | `training/adapters/trial-web-<작업 UUID>/adapters.safetensors` |
| 로컬 완성 모델 | `training/outputs/model-build-packages/trained-web-<작업 UUID>/model.gguf` |
| iCloud 완성 모델 | `<공유 폴더>/model-deliveries/trained-web-<작업 UUID>/model.gguf` |
| 스튜디오 등록 ID | `doenaai-trained:web-<작업 UUID>` |

완성 패키지는 `model.gguf`, `evaluation-summary.json`, `manifest.json`, `READY.json` 네 파일이다. 명세에는 기반 ID·revision·작업 ID·파일 해시를 기록하고 서명한다. 선택 모델과 부모 어댑터의 출처도 작업 기록과 평가 요약에 보존한다.

맥북 학습 서버는 코드 반영 뒤 활성 학습·모델 생성이 없는지 확인하고 수신 서비스를 재시작한다. 백엔드와 스튜디오 AI 분석/추론 서버에도 같은 코드를 반영한 뒤 선택 모델 조회·승인 자료 수신·학습 요청·완성 파일 수신·등록·응답 검증을 확인한다. 접근하지 못한 서버의 작업은 완료로 표시하지 않으며 생성·등록만으로 운영 모델을 바꾸지 않는다.
