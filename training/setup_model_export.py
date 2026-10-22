"""학습 서버에 고정 버전 모델 변환 도구를 별도 환경으로 준비한다."""

import argparse
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
from pathlib import Path

from model_delivery import read_json, write_file, canonical
from model_export_worker import CONVERTER_CHECK, toolchain
from trained_model_delivery import LLAMA_CPP_REVISION, checksum

ROOT = Path(__file__).resolve().parent
REPOSITORY = "https://github.com/ggml-org/llama.cpp.git"


class SetupError(ValueError):
    """설치 도구의 출력이나 환경 비밀값을 포함하지 않는 오류다."""


def run(command, *, capture=False, timeout=1800):
    """명시적으로 실행한 설치 프로세스만 관리하고 실패·취소 시 자식도 종료한다."""
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE if capture else subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        shell=False, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=timeout)
        if process.returncode:
            raise SetupError("변환 도구 준비 명령을 완료하지 못했습니다. 네트워크와 빌드 도구를 확인하세요.")
        return output.decode("utf-8").strip() if capture else None
    finally:
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
            except ProcessLookupError:
                pass


def configuration(directory, *, verify=True):
    """준비 완료 표시와 실제 고정 도구를 대조하고 전송 키 없이 경로만 반환한다."""
    root = Path(directory)
    if not root.is_absolute() or root.is_symlink() or not root.is_dir():
        raise SetupError("변환 도구 폴더는 심볼릭 링크가 아닌 절대 경로여야 합니다.")
    record = read_json(root / "setup.json")
    if (set(record) != {"formatVersion", "llamaCppRevision", "quantizerSha256"}
            or type(record.get("formatVersion")) is not int or record["formatVersion"] != 1
            or record.get("llamaCppRevision") != LLAMA_CPP_REVISION
            or not isinstance(record.get("quantizerSha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", record["quantizerSha256"])):
        raise SetupError("변환 도구 준비 완료 정보를 확인하세요.")
    repository, python = root / "llama.cpp", root / "converter-venv/bin/python"
    if verify:
        toolchain(repository, python, record["quantizerSha256"])
    return {"DOENAAI_TRAINING_LLAMA_CPP_DIR": str(repository),
            "DOENAAI_TRAINING_CONVERTER_PYTHON": str(python),
            "DOENAAI_TRAINING_QUANTIZER_SHA256": record["quantizerSha256"]}


def install(directory, python, *, runner=run):
    """새 폴더에서만 고정 소스를 설치하며 기존 학습 환경과 설정 파일은 보존한다."""
    directory, python = Path(directory), Path(python)
    if (not directory.is_absolute() or directory.is_symlink()
            or not python.is_absolute() or not python.is_file() or not os.access(python, os.X_OK)):
        raise SetupError("새 설치 폴더와 Python 실행 파일의 절대 경로를 지정하세요.")
    if directory.exists():
        raise SetupError("설치 폴더가 이미 있습니다. --check로 확인하거나 별도의 새 경로를 지정하세요.")
    if sys.platform != "darwin" or any(shutil.which(name) is None for name in ("git", "cmake", "clang")):
        raise SetupError("학습 서버에서 Git·CMake·Xcode 명령줄 도구를 준비한 뒤 실행하세요.")
    runner([str(python), "-c", "import sys; assert sys.version_info >= (3, 10)"])
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    repository, environment = directory / "llama.cpp", directory / "converter-venv"
    print("1/4 고정된 공식 변환 도구를 내려받습니다.", flush=True)
    runner(["git", "clone", "--filter=blob:none", "--no-checkout", REPOSITORY, str(repository)])
    runner(["git", "-C", str(repository), "checkout", "--detach", LLAMA_CPP_REVISION])
    if runner(["git", "-C", str(repository), "rev-parse", "HEAD"], capture=True) != LLAMA_CPP_REVISION:
        raise SetupError("내려받은 변환 도구가 고정한 버전과 다릅니다.")
    print("2/4 기존 학습 환경과 별도로 변환용 Python 환경을 만듭니다.", flush=True)
    runner([str(python), "-m", "venv", str(environment)])
    converter_python = environment / "bin/python"
    runner([str(converter_python), "-m", "pip", "install", "--disable-pip-version-check", "--requirement",
            str(repository / "requirements/requirements-convert_hf_to_gguf.txt")])
    runner([str(converter_python), "-c", CONVERTER_CHECK])
    print("3/4 CPU 양자화 도구를 빌드합니다. 모델 학습이나 변환은 실행하지 않습니다.", flush=True)
    runner(["cmake", "-S", str(repository), "-B", str(repository / "build"),
            "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF", "-DGGML_METAL=OFF",
            "-DLLAMA_OPENSSL=OFF", "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=OFF",
            "-DLLAMA_BUILD_SERVER=OFF", "-DLLAMA_BUILD_APP=OFF", "-DLLAMA_BUILD_TOOLS=ON"])
    runner(["cmake", "--build", str(repository / "build"), "--config", "Release",
            "--target", "llama-quantize", "--parallel", "2"])
    quantizer = repository / "build/bin/llama-quantize"
    value = checksum(quantizer)[0]
    write_file(directory / "setup.json", canonical({"formatVersion": 1,
        "llamaCppRevision": LLAMA_CPP_REVISION, "quantizerSha256": value}))
    print("4/4 도구 준비를 완료했습니다. 아래 세 값을 학습 서버의 .env에 반영하세요.", flush=True)
    return configuration(directory, verify=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__, add_help=False, epilog=(
        "명시적으로 실행하면 공식 소스와 변환 의존성을 내려받아 새 폴더에 설치합니다. "
        "기존 학습 Python 환경과 .env는 수정하지 않습니다. Git·CMake·Xcode 명령줄 도구가 필요합니다. "
        "설치 후 새 모델 생성에는 작업 폴더의 여유 공간 180 GiB 이상이 필요합니다."))
    parser.add_argument("-h", "--help", action="help", help="사용 안내를 보고 종료합니다.")
    parser.add_argument("--directory", type=Path, default=ROOT / ".tools/model-export", help="새 도구 폴더의 절대 경로")
    parser.add_argument("--python", type=Path, help="새 환경의 기반이 될 Python 3.10 이상 실행 파일의 절대 경로")
    parser.add_argument("--check", action="store_true", help="기존 설치의 고정 버전과 해시만 검사하고 설정값 출력")
    args = parser.parse_args()
    if not args.check and args.python is None:
        parser.error("설치에는 --python으로 Python 3.10 이상의 절대 경로를 지정하세요.")
    try:
        values = configuration(args.directory) if args.check else install(args.directory, args.python)
        for key, value in values.items():
            print(f"{key}={shlex.quote(value)}")
    except KeyboardInterrupt:
        print("도구 준비를 취소했습니다. 기존 학습 환경과 설정 파일은 보존했습니다.", file=sys.stderr)
        return 130
    except (ValueError, OSError, RuntimeError, subprocess.SubprocessError, ImportError):
        print("도구 준비에 실패했습니다. 새 폴더·Python 버전·네트워크·Git·CMake·Xcode 명령줄 도구를 확인하세요. 기존 환경은 보존했습니다.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
