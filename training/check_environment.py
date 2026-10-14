"""Apple Silicon에서 MLX의 Metal GPU 연산과 자동 미분이 동작하는지 확인한다."""

import importlib.metadata
import platform
import sys

import mlx.core as mx


def main():
    """실행 장비와 패키지 버전을 출력하고 GPU 계산·미분 결과를 검증한다."""
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise SystemExit("이 학습 환경은 Apple Silicon macOS에서 실행해야 합니다.")
    if not mx.metal.is_available():
        raise SystemExit("MLX에서 Metal GPU를 사용할 수 없습니다.")
    mx.set_default_device(mx.gpu)
    values = mx.array([1.0, 2.0, 3.0])
    result = mx.sum(values * values)
    gradient = mx.grad(lambda x: mx.sum(x * x))(values)
    mx.eval(result, gradient)
    if result.item() != 14.0 or gradient.tolist() != [2.0, 4.0, 6.0]:
        raise SystemExit("MLX GPU 연산 또는 자동 미분 검증에 실패했습니다.")
    print(f"Python: {sys.version.split()[0]} ({platform.machine()})")
    for package in ("mlx", "mlx-lm", "datasets", "transformers"):
        print(f"{package}: {importlib.metadata.version(package)}")
    print(f"GPU: {mx.device_info()}")
    print("검증 통과: Metal GPU 연산 및 자동 미분")


if __name__ == "__main__":
    main()
