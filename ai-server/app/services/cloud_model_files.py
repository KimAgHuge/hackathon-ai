"""등록을 요청한 모델 파일만 iCloud에서 내려받고 로컬 준비 상태를 기다린다."""

from __future__ import annotations

import json
import os
import stat
import subprocess
import sys
import time
from pathlib import Path


PACKAGE_FILES = frozenset({"model.gguf", "evaluation-summary.json", "manifest.json", "READY.json"})
OSASCRIPT = "/usr/bin/osascript"
# 일부 Python 빌드는 Darwin의 SF_DATALESS 상수를 제공하지 않는다.
DATALESS_FLAG = getattr(stat, "SF_DATALESS", 0x40000000)
POLL_SECONDS = 1.0

# 경로는 실행 코드에 삽입하지 않고 인수로만 전달한다. 파일 본문은 읽지 않는다.
FOUNDATION_SCRIPT = r'''
ObjC.import('Foundation');
function run(argv) {
    var request = argv[0] === 'request';
    var dataless = JSON.parse(argv[1]);
    var manager = $.NSFileManager.defaultManager;
    var keys = [$.NSURLIsUbiquitousItemKey,
                $.NSURLUbiquitousItemDownloadingStatusKey,
                $.NSURLUbiquitousItemDownloadingErrorKey];
    function value(dictionary, key) {
        var item = dictionary.objectForKey(key);
        return item.isNil() ? null : ObjC.unwrap(item);
    }
    function safePath(path) {
        var current = path;
        while (current !== '/') {
            var attributes = manager.attributesOfItemAtPathError(current, null);
            if (!attributes || attributes.isNil()) return false;
            if (value(attributes, $.NSFileType) === ObjC.unwrap($.NSFileTypeSymbolicLink)) return false;
            current = ObjC.unwrap($(current).stringByDeletingLastPathComponent);
        }
        return true;
    }
    var result = [];
    for (var index = 2; index < argv.length; index++) {
        if (!safePath(argv[index])) {
            result.push('INVALID');
            continue;
        }
        var url = $.NSURL.fileURLWithPath(argv[index]);
        var properties = url.resourceValuesForKeysError(keys, null);
        if (!properties || properties.isNil()) {
            result.push('FAILED');
            continue;
        }
        var cloud = !!value(properties, $.NSURLIsUbiquitousItemKey);
        var status = value(properties, $.NSURLUbiquitousItemDownloadingStatusKey);
        var current = ObjC.unwrap($.NSURLUbiquitousItemDownloadingStatusCurrent);
        // 최신 FileProvider는 iCloud 상태 키가 비어 있어도 dataless 파일을 노출한다.
        var needsDownload = dataless[index - 2] || (cloud && status !== current);
        if (needsDownload && request) {
            if (!safePath(argv[index]) || !manager.startDownloadingUbiquitousItemAtURLError(url, null)) {
                result.push('FAILED');
                continue;
            }
            // 같은 NSURL의 이전 메타데이터가 다음 조회에 남지 않도록 새로 만든다.
            url = $.NSURL.fileURLWithPath(argv[index]);
            properties = url.resourceValuesForKeysError(keys, null);
            if (!properties || properties.isNil()) {
                result.push('FAILED');
                continue;
            }
            status = value(properties, $.NSURLUbiquitousItemDownloadingStatusKey);
        }
        if (value(properties, $.NSURLUbiquitousItemDownloadingErrorKey) !== null) {
            result.push('FAILED');
        } else {
            result.push(dataless[index - 2] || (cloud && status !== current) ? 'PENDING' : 'LOCAL');
        }
    }
    return JSON.stringify(result);
}
'''


class CloudModelFilesError(Exception):
    """파일 경로와 운영 체제 응답을 제외한 다운로드 오류다."""

    def __init__(self, code, message):
        super().__init__(message)
        self.code, self.message = code, message


def _checkpoint(deadline, check):
    if check is not None:
        check()
    if time.monotonic() >= deadline:
        raise TimeoutError()


def _files(package, filenames):
    package = Path(package)
    names = tuple(filenames)
    if (not package.is_absolute() or ".." in package.parts or not names
            or len(names) != len(set(names)) or not set(names) <= PACKAGE_FILES):
        raise CloudModelFilesError("TRAINED_MODEL_CLOUD_PATH_INVALID", "완성 모델의 공유 폴더와 필수 파일 경로를 확인하세요.")
    # 모델 파일뿐 아니라 부모 폴더를 통한 공유 폴더 이탈도 거부한다.
    for parent in (*reversed(package.parents), package):
        information = parent.lstat()
        if not stat.S_ISDIR(information.st_mode) or stat.S_ISLNK(information.st_mode):
            raise CloudModelFilesError("TRAINED_MODEL_CLOUD_PATH_INVALID", "완성 모델의 공유 폴더와 필수 파일 경로를 확인하세요.")
    paths, dataless = [], False
    for name in names:
        path = package / name
        information = path.lstat()
        if not stat.S_ISREG(information.st_mode):
            raise CloudModelFilesError("TRAINED_MODEL_CLOUD_PATH_INVALID", "완성 모델의 공유 폴더와 필수 파일 경로를 확인하세요.")
        paths.append(path)
        dataless = dataless or bool(getattr(information, "st_flags", 0) & DATALESS_FLAG)
    return paths, dataless


def _native_states(paths, request, deadline, check):
    _checkpoint(deadline, check)
    dataless = [bool(getattr(path.lstat(), "st_flags", 0) & DATALESS_FLAG) for path in paths]
    try:
        process = subprocess.Popen(
            [OSASCRIPT, "-l", "JavaScript", "-e", FOUNDATION_SCRIPT, "--",
             "request" if request else "status", json.dumps(dataless), *(os.fspath(path) for path in paths)],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8", close_fds=True, start_new_session=True,
        )
    except OSError:
        raise CloudModelFilesError("TRAINED_MODEL_CLOUD_DOWNLOAD_UNAVAILABLE", "AI 분석/추론 서버에서 iCloud 자동 다운로드를 요청하지 못했습니다. iCloud 연결과 접근 권한을 확인하세요.") from None
    try:
        while True:
            _checkpoint(deadline, check)
            try:
                output, _ = process.communicate(timeout=min(0.2, max(0.001, deadline - time.monotonic())))
                break
            except subprocess.TimeoutExpired:
                continue
        _checkpoint(deadline, check)
        if process.returncode != 0 or len(output) > 4096:
            raise ValueError()
        states = json.loads(output)
        if (not isinstance(states, list) or len(states) != len(paths)
                or any(value not in {"LOCAL", "PENDING", "FAILED", "INVALID"} for value in states)):
            raise ValueError()
        return states
    except (ValueError, TypeError, UnicodeError):
        raise CloudModelFilesError("TRAINED_MODEL_CLOUD_DOWNLOAD_UNAVAILABLE", "AI 분석/추론 서버에서 iCloud 다운로드 상태를 확인하지 못했습니다. iCloud 연결과 접근 권한을 확인하세요.") from None
    finally:
        if process.poll() is None:
            process.kill()
            process.communicate()


def ensure_local_files(package, filenames, deadline, *, check=None, progress=None):
    """본문을 열기 전에 다운로드를 요청하고 제한 시간 안에서 완료를 확인한다."""
    _checkpoint(deadline, check)
    names = tuple(filenames)
    paths, dataless = _files(package, names)
    if sys.platform != "darwin" or not dataless:
        return
    if progress is not None:
        progress("DOWNLOADING")
    request = True
    while True:
        _checkpoint(deadline, check)
        paths, dataless = _files(package, names)
        states = _native_states(paths, request, deadline, check)
        request = False
        # 요청 중 파일이 교체되거나 링크로 바뀐 경우에도 본문 읽기로 넘어가지 않는다.
        _, dataless = _files(package, names)
        if "INVALID" in states:
            raise CloudModelFilesError("TRAINED_MODEL_CLOUD_PATH_INVALID", "완성 모델의 공유 폴더와 필수 파일 경로를 확인하세요.")
        if "FAILED" in states:
            raise CloudModelFilesError("TRAINED_MODEL_CLOUD_DOWNLOAD_FAILED", "AI 분석/추론 서버의 iCloud 파일 다운로드가 완료되지 않았습니다. iCloud 연결과 저장 공간을 확인한 뒤 다시 등록하세요.")
        if not dataless and all(value == "LOCAL" for value in states):
            return
        _checkpoint(deadline, check)
        time.sleep(min(POLL_SECONDS, max(0.0, deadline - time.monotonic())))
