# 선택한 모델과 공통 학습기의 연결

웹에서 선택한 정확한 모델 ID를 백엔드가 확인하고, 맥북의
`GET /api/training/capabilities?model=모델식별자`를 서명해 조회한다.
학습 서버는 해당 ID에 등록한 로컬 MLX 기반 모델·고정 버전·학습 프로필을 확인한다.
미등록 모델을 Qwen으로 대체하거나 모델 가중치를 자동으로 내려받지 않는다.

## 공통 실행과 모델별 설정

Python·MLX·MLX-LM 실행 환경은 공유한다. 모델 가중치와 토크나이저는 모델마다 준비하고,
공통 `mlx-lora-v1` 학습기에 아래 프로필을 전달한다.
프론트에서 선택한 Qwen은 Qwen MLX 가중치로, EXAONE은 등록한 해당 EXAONE MLX 가중치로 학습한다.
같은 어댑터를 서로 다른 기반 모델에 공통으로 적용하지 않는다.
학습기는 실제 모델의 선택한 모든 층에서 대상 모듈을 확인하고 LoRA 가중치만 갱신한다.
저장 뒤 새 프로세스로 어댑터를 다시 읽어 텐서와 확인 출력을 대조한다.

| 학습기 | 용도 |
| --- | --- |
| `mlx-lora-v1` | 명시한 모델별 프로필을 사용하는 공통 MLX LoRA 시험 학습 |
| `qwen-mlp-trial-v1` | 기존 Qwen의 긴 입력을 위한 마지막 MLP 1층·rank 4 고정 시험. 기존 동작 유지 |

기존 Qwen 학습기에 임의의 층 수나 rank를 지정할 수 없다. 설정을 바꾸려면 공통 학습기와
명시적 프로필을 사용한다. 공통 학습기도 MLX-LM이 읽을 수 있는 모델과 실제 LoRA 대상이 필요하다.
화면에서 모델을 바꾼 것만으로 임의의 구조가 학습 가능해지는 것은 아니다.

## 맥북 등록 파일

기존 Qwen만 쓰면 `DOENAAI_TRAINING_MODEL_DIR`을 유지할 수 있다.
여러 모델을 연결할 때는 `model-registry.example.json`을 로컬 파일로 복사하고
`training/.env`에 절대 경로를 설정한다.

```dotenv
DOENAAI_TRAINING_MODELS_FILE=/absolute/path/to/local-model-registry.json
```

예제의 `exaone4:example`, `replace-owner/EXAONE4-4bit`와 경로는 작성 형식 예시다.
실제 설치 완료를 뜻하지 않으며 실제 분석 모델 ID·가중치 저장소 ID·로컬 경로로 바꿔야 한다.

| 등록 항목 | 의미 |
| --- | --- |
| 객체의 키 | 화면에서 선택하는 정확한 분석 모델 ID |
| `directory` | 이미 준비한 로컬 MLX 모델 폴더의 절대 경로 |
| `baseModel` | 그 폴더의 `download_manifest.json.modelId`와 동일한 값 |
| `trainer` | 서버에 구현된 학습기 ID |
| `profile` | 공통 학습기의 필수 모델별 설정 |
| `resumeJobId` | 선택 사항. 이 맥북에서 성공한 부모 작업의 UUID |

다운로드 명세의 `revision`은 실제 다운로드에 고정한 40자리 버전이다.
등록 파일·모델 가중치·토크나이저는 Git에 넣지 않는다. 경로와 명령은 브라우저에서 받지 않는다.
환경변수 변경은 수신 서비스 재시작이 필요하고, 등록 파일 변경은 다음 조회에 반영된다.
등록 정보·프로필·부모 어댑터가 바뀌면 예전 화면에서 받은 시작 식별값은 무효가 된다.

### 프로필 항목

| 항목 | 의미와 범위 |
| --- | --- |
| `modelType` | `config.json.model_type`과 같은 구조 ID. 예: `exaone4`, `llama`, `qwen3` |
| `loraLayers` | 마지막 몇 층을 학습할지 지정. 1~128, 실제 층 수 이내 |
| `loraRank`, `loraScale`, `loraDropout` | rank 1~128, scale 0 초과~256, dropout 0~0.5 |
| `loraKeys` | 실제 모듈 경로 목록. 예: `self_attn.q_proj`, `self_attn.v_proj` |
| `maxSequenceLength` | 최대 32~32,768토큰. 정답을 잘라 맞추지 않고 긴 사례를 제외 |
| `batchSize`, `learningRate` | 배치 1~4, 학습률 0.0000001~0.01 |
| `gradientCheckpointing` | 메모리 절감 사용 여부 |
| `exporter`, `exportArchitecture` | 선택 사항. 별도로 검토한 GGUF 변환 경로와 구조 ID |

값이 허용 범위라는 이유만으로 64GB 메모리에 들어간다고 보장하지 않는다.
새 모델은 작은 입력·배치 1·적은 층 수로 실제 로딩과 학습을 먼저 확인한다.
토크나이저의 대화 형식이 현재 system·user·assistant 입력을 받을 수 있어야 한다.
지원하지 않는 대화 형식이나 없는 대상 모듈은 실패로 처리한다.

## 파생 모델의 이어 학습

`doenaai-trained:web-<부모 작업 UUID>` 또는 `doenaai-mlx:web-<부모 작업 UUID>`를 선택하면,
그 작업이 이 맥북에 `SUCCEEDED`로 남아 있고 원본 기반 모델·프로필·어댑터가 일치하는지 확인한다.
기반 모델에 부모 어댑터를 읽은 뒤 그 LoRA 가중치를 이어 학습한다.
부모를 두 번 병합하지 않으며 결과는 새 작업 UUID의 어댑터에 저장한다.
다른 맥북으로 이동했다면 부모 작업 기록·어댑터·보고서도 검증 가능한 상태로 있어야 한다.
임의의 Ollama 모델 파일에서 학습 상태를 자동 복원하는 기능은 아니다.

`selectedModel`은 실제 선택한 모델, `sourceModel`은 승인 자료의 원래 생성 모델이다.
둘을 구분해 파생 모델을 선택해도 같은 출처의 승인 자료를 찾는다.
같은 원 요구사항의 수정 버전·후속 질문은 항상 같은 학습·검증·시험 분할에 묶는다.

## ID와 파일 위치 규칙

모델 ID와 작업 UUID가 식별의 기준이다. 모델마다 파일명을 사람이 지정하지 않는다.
서버는 다음 위치와 고정 이름을 사용하고 기존 파일을 덮어쓰지 않는다.

| 대상 | 서버가 정하는 위치 또는 이름 |
| --- | --- |
| 원본 MLX 모델 | 등록 파일의 `directory`. 기반 ID·revision은 다운로드 명세로 확인 |
| 작업 자료·보고서 | `training/outputs/web-jobs/<작업 UUID>/` |
| 작업별 어댑터 | `training/adapters/trial-web-<작업 UUID>/adapters.safetensors` |
| 로컬 완성 모델 | `training/outputs/model-build-packages/trained-web-<작업 UUID>/model.gguf` |
| iCloud 완성 모델 | `<공유 폴더>/model-deliveries/trained-web-<작업 UUID>/model.gguf` |
| 분석 서버 등록 ID | `doenaai-trained:web-<작업 UUID>` |

완성 패키지는 `model.gguf`, `evaluation-summary.json`, `manifest.json`, `READY.json`으로 구성한다.
명세는 기반 ID·revision·작업 ID·파일 해시를 기록하고 서명으로 보호한다.
선택 모델과 부모 작업·어댑터의 출처는 작업 기록과 새 패키지의 평가 요약에도 보존한다.
파일 이름이 같아도 작업 폴더가 다르므로 서로 다른 학습 결과가 충돌하지 않는다.

## 학습과 배포의 지원 범위

공통 학습 가능 여부와 GGUF 변환 가능 여부는 별도로 표시한다.
변환 프로필이 없어도 학습 환경이 맞으면 학습할 수 있고, 완성 모델 생성만 차단한다.
공통 변환 경로는 현재 `llama`, `mistral`, `qwen2`, `qwen3`, `exaone4`의 검토한
밀집 텍스트 구조와 명시한 선형 LoRA 모듈로 제한한다. `mistral`의 GGUF 구조는 `llama`다.
기존 Qwen의 `qwen3_5`는 검증한 기반 버전과 기존 전용 변환기를 유지한다.
Gemma·이전 EXAONE 구조·멀티모달·혼합 전문가 모델은 이름만 등록해 GGUF 변환을 허용하지 않는다.

2026-10-21~22 작은 임의 Llama·EXAONE4 모델로 다음 CPU 통합 검사를 마쳤다.
4비트 기반 모델에서 각각 새 학습 2회 → 새 프로세스 재로딩 → 이어 학습 2회 → 재로딩이 통과했다.
두 모델 모두 부모 파일 해시가 유지됐고 재로딩 확인 출력의 최대 차이는 0이었다.
실제 대형 EXAONE4 가중치·긴 입력 성능·업무 정답 품질·Mac Studio 배포를 검증한 결과는 아니다.
기존 실제 Qwen 시험 결과는 [Qwen 검증 기록](QWEN_COMPATIBILITY.md)을 참고한다.

## 서버 반영 순서

맥북은 코드 반영 뒤 활성 학습·모델 생성 작업이 없는지 확인하고 수신 서버를 재시작한다.
백엔드와 분석 서버에도 같은 코드가 필요하며, 다음에 Mac Studio에 접근할 때 업데이트·재시작한 뒤
선택 모델 조회 → 승인 자료 → 학습 요청 → 파일 수신 → 새 모델 등록 → 응답 검증을 확인한다.
서버에 접근하지 못하는 동안에는 등록과 실제 분석 검증을 완료로 표시하지 않는다.
완성 모델 생성·등록만으로 운영 모델이 바뀌지는 않는다.
