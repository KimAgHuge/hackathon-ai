# Qwen3.8-27B MLX 로딩·QLoRA 호환성 검증

2026-10-14, MacBook Apple M4 Max·64 GiB, macOS 26.6.2.
검증 실행 당시 프로젝트 기준 커밋은 `022b399`다. 검증 도구·환경 파일은 이 문서와 함께 관리한다.

## 결과

**이 환경에서 텍스트 모델 로딩, 짧은 추론, QLoRA 역전파·업데이트,
어댑터 저장 및 새 프로세스에서 재로딩을 모두 확인했다.**
승인된 업무 데이터를 이용한 품질 학습이나 Ollama 배포 검증은 아니다.

| 항목 | 확인값 |
|---|---|
| 모델 | `mlx-community/Qwen3.8-27B-4bit` |
| 모델 revision | `10c35caafbb80f7dc6a7a432cdd11af10a6d4818` |
| 설정 | `qwen3_5`, affine 4bit, group size 64 |
| Python / MLX / MLX-LM | 3.12.15 / 0.32.3 / 0.32.0 |
| Transformers | 5.19.0 |
| 로딩 | 성공, 최초 측정 약 3.62초 |
| 짧은 추론 | thinking=false, temperature=0, 상한 48토큰. 6토큰 생성 후 정상 종료 |
| 추론 응답 | `{"ok":true}` |
| 추론 시간·최대 GPU 메모리 | 약 2.79초 / 14.38 GiB |
| QLoRA 시험 | 마지막 4층(선형 어텐션 3 + 일반 어텐션 1), rank 4, scale 8, batch 1, Adam 1e-5, 2 updates |
| 시험 데이터 | 숫자 1 응답을 요청하는 합성 대화 1건. 업무 학습 정답이 아님 |
| 토큰 | 전체 70, prompt mask 65. 공식 ChatDataset 기본 template 사용 |
| 학습 가능 파라미터 | adapter 1,823,872개만 업데이트. 양자화 선형층 497개 존재 확인 |
| Loss | 0.001632 → 0.001461 |
| Gradient norm | 0.033141 → 0.029344, finite·nonzero 확인 |
| 실제 adapter 변화 | 최대 절댓값 변화 약 7.41e-5 |
| 학습 시험 총시간·최대 GPU 메모리 | 로딩·업데이트·저장·probe 포함 약 4.20초 / 16.25 GiB |
| 저장 | adapter 62개 텐서, 파일 7,303,199 bytes |
| 새 프로세스 재로딩 | 62개 텐서가 저장값과 정확히 일치. 같은 prompt의 마지막 위치 logits 앞 256개 최대 차이 0 |

로딩 후 모델은 텍스트 부분 64층으로 실행됐다. vision 부분 학습은 시험하지 않았다.
GPU 메모리는 MLX allocator의 프로세스별 peak이며 macOS 전체 메모리 사용량과 다르다.
Loss 감소는 이 단순 실행 시험의 관찰값이며 제품 분석 정확도 개선 근거가 아니다.
70토큰·4층·rank 4 결과를 긴 입력·더 많은 학습층에 그대로 적용하지 않는다.
운영 Mac Studio는 thinking 기본 활성화이므로 이번 thinking=false 단문 속도와 직접 비교하지 않는다.

## 재현

일반 터미널에서 저장소 루트 기준:

```bash
training/.venv/bin/python training/download_model.py
training/.venv/bin/python training/verify_qwen_compatibility.py load
training/.venv/bin/python training/verify_qwen_compatibility.py train
training/.venv/bin/python training/verify_qwen_compatibility.py reload
```

GPU 실행은 Codex 샌드박스 밖에서 승인 후 수행했다.
다운로드한 모델과 아래 생성물은 Git에서 제외한다.

- `training/models/Qwen3.8-27B-4bit/`: 모델·tokenizer·chat template 및 revision manifest
- `training/adapters/compatibility-only/`: 시험 전용 adapter. 운영에 배포하지 않는다.
- `training/outputs/qwen-compatibility/{load,train,reload}.json`: 상세 결과

## 남은 작업

1. 운영과 비교할 thinking·chat template·JSON 출력 조건을 명시하고 학습 전 평가를 고정한다.
2. 실제 근거와 사람이 승인한 정답으로 전체 분석·DB 분석 데이터 및 독립 평가 세트를 준비한다.
3. 실제 입력 길이와 학습층 범위에서 메모리·처리 시간·학습 안정성을 다시 확인한다.
4. MLX adapter 병합, Qwen GGUF 변환·재양자화, Mac Studio Ollama 로딩을 별도로 검증한다.

자료: [MLX 모델](https://huggingface.co/mlx-community/Qwen3.8-27B-4bit),
[MLX-LM 학습 안내](https://github.com/ml-explore/mlx-lm/blob/main/mlx_lm/LORA.md).
