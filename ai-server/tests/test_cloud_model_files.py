"""실제 공유 파일과 네트워크 없이 iCloud 다운로드 요청과 대기 처리를 검사한다."""

import json
import stat
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.services import cloud_model_files as cloud


NAMES = ("model.gguf", "evaluation-summary.json", "manifest.json", "READY.json")


@pytest.fixture
def package(tmp_path):
    folder = tmp_path.resolve() / "package"
    folder.mkdir()
    for name in NAMES:
        (folder / name).write_bytes(b"local")
    return folder


def dataless_files(monkeypatch, package, flags=None):
    paths = [package / name for name in NAMES]
    values = iter(flags or [True] * 100)
    calls = []

    def files(folder, names):
        assert folder == package and tuple(names) == NAMES
        calls.append(True)
        return paths, next(values)

    monkeypatch.setattr(cloud, "_files", files)
    monkeypatch.setattr(cloud.sys, "platform", "darwin")
    return paths, calls


@pytest.mark.parametrize("platform", ["darwin", "linux", "win32"])
def test_regular_local_files_do_not_launch_native_tools_or_open_contents(package, monkeypatch, platform):
    monkeypatch.setattr(cloud.sys, "platform", platform)

    def forbidden(*args, **kwargs):
        pytest.fail("일반 파일은 도구를 실행하거나 파일 본문을 열면 안 됩니다.")

    monkeypatch.setattr(cloud.subprocess, "Popen", forbidden)
    monkeypatch.setattr(Path, "open", forbidden)
    cloud.ensure_local_files(package, NAMES, time.monotonic() + 10)


def test_dataless_flag_uses_darwin_value_when_python_has_no_named_constant(package, monkeypatch):
    original = Path.lstat

    def information(path, *args, **kwargs):
        if path == package / "model.gguf":
            return SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_flags=0x40000000)
        return original(path, *args, **kwargs)

    monkeypatch.setattr(Path, "lstat", information)
    paths, dataless = cloud._files(package, NAMES)
    assert dataless and paths == [package / name for name in NAMES]


@pytest.mark.parametrize("names", [[], ["../model.gguf"], ["other.gguf"], ["model.gguf", "model.gguf"]])
def test_only_fixed_unique_package_names_are_allowed(package, names):
    with pytest.raises(cloud.CloudModelFilesError) as error:
        cloud.ensure_local_files(package, names, time.monotonic() + 10)
    assert error.value.code == "TRAINED_MODEL_CLOUD_PATH_INVALID"
    assert str(package) not in str(error.value)


def test_relative_package_and_parent_traversal_are_rejected(package):
    for folder in (Path("package"), package / ".." / "package"):
        with pytest.raises(cloud.CloudModelFilesError):
            cloud.ensure_local_files(folder, NAMES, time.monotonic() + 10)


def test_symbolic_link_file_is_rejected_before_native_request(package):
    (package / "model.gguf").unlink()
    (package / "model.gguf").symlink_to(package / "READY.json")
    with pytest.raises(cloud.CloudModelFilesError):
        cloud.ensure_local_files(package, NAMES, time.monotonic() + 10)


def test_symbolic_link_ancestor_is_rejected_before_native_request(package):
    link = package.parent / "shared-link"
    link.symlink_to(package, target_is_directory=True)
    with pytest.raises(cloud.CloudModelFilesError):
        cloud.ensure_local_files(link, NAMES, time.monotonic() + 10)


def test_non_regular_file_is_rejected(package):
    (package / "model.gguf").unlink()
    (package / "model.gguf").mkdir()
    with pytest.raises(cloud.CloudModelFilesError):
        cloud.ensure_local_files(package, NAMES, time.monotonic() + 10)


def test_request_download_once_for_all_files_and_wait_without_reading(package, monkeypatch):
    paths, calls = dataless_files(monkeypatch, package, [True, True, True, False, False])
    progress, requests = [], []

    def native(selected, request, deadline, check):
        assert selected == paths
        requests.append(request)
        return ["PENDING", "LOCAL", "LOCAL", "LOCAL"] if request else ["LOCAL"] * 4

    monkeypatch.setattr(cloud, "_native_states", native)
    monkeypatch.setattr(cloud.time, "sleep", lambda seconds: None)
    cloud.ensure_local_files(package, NAMES, time.monotonic() + 10, progress=progress.append)
    assert requests == [True, False] and progress == ["DOWNLOADING"] and len(calls) == 5


def test_native_local_status_does_not_bypass_dataless_flag(package, monkeypatch):
    dataless_files(monkeypatch, package, [True, True, True, False, False])
    requests = []
    monkeypatch.setattr(cloud, "_native_states", lambda paths, request, *args: requests.append(request) or ["LOCAL"] * 4)
    monkeypatch.setattr(cloud.time, "sleep", lambda seconds: None)
    cloud.ensure_local_files(package, NAMES, time.monotonic() + 10)
    assert requests == [True, False]


@pytest.mark.parametrize("status,code", [("FAILED", "TRAINED_MODEL_CLOUD_DOWNLOAD_FAILED"), ("INVALID", "TRAINED_MODEL_CLOUD_PATH_INVALID")])
def test_cloud_errors_return_fixed_safe_messages(package, monkeypatch, status, code):
    dataless_files(monkeypatch, package)
    monkeypatch.setattr(cloud, "_native_states", lambda *args: [status] + ["LOCAL"] * 3)
    with pytest.raises(cloud.CloudModelFilesError) as error:
        cloud.ensure_local_files(package, NAMES, time.monotonic() + 10)
    assert error.value.code == code and str(package) not in str(error.value)


def test_expired_deadline_does_not_inspect_files_or_launch_tools(package, monkeypatch):
    monkeypatch.setattr(cloud, "_files", lambda *args: pytest.fail("시간이 끝나면 파일을 확인하지 않아야 합니다."))
    with pytest.raises(TimeoutError):
        cloud.ensure_local_files(package, NAMES, time.monotonic() - 1)


def test_shutdown_check_interrupts_wait(package, monkeypatch):
    dataless_files(monkeypatch, package)
    monkeypatch.setattr(cloud, "_native_states", lambda *args: ["PENDING"] * 4)
    monkeypatch.setattr(cloud.time, "sleep", lambda seconds: None)
    calls = []

    def check():
        calls.append(True)
        if len(calls) == 4:
            raise InterruptedError("중단")

    with pytest.raises(InterruptedError, match="중단"):
        cloud.ensure_local_files(package, NAMES, time.monotonic() + 10, check=check)
    assert len(calls) == 4


class NativeProcess:
    def __init__(self, output, *, running=False, returncode=0):
        self.output, self.running, self.returncode = output, running, returncode
        self.killed = False

    def communicate(self, timeout=None):
        if self.running and not self.killed:
            raise subprocess.TimeoutExpired("고정 도구", timeout)
        return self.output, None

    def poll(self):
        return None if self.running and not self.killed else self.returncode

    def kill(self):
        self.killed, self.running = True, False


def test_native_command_keeps_path_out_of_script_and_suppresses_stderr(package, monkeypatch):
    commands = []

    def popen(command, **kwargs):
        commands.append((command, kwargs))
        return NativeProcess(json.dumps(["LOCAL"] * 4))

    monkeypatch.setattr(cloud.subprocess, "Popen", popen)
    paths = [package / name for name in NAMES]
    assert cloud._native_states(paths, True, time.monotonic() + 10, None) == ["LOCAL"] * 4
    command, kwargs = commands[0]
    assert command[:3] == ["/usr/bin/osascript", "-l", "JavaScript"]
    assert command[4] == cloud.FOUNDATION_SCRIPT and str(package) not in command[4]
    assert command[5:] == ["--", "request", "[false, false, false, false]", *(str(path) for path in paths)]
    assert kwargs["stderr"] == subprocess.DEVNULL and not kwargs.get("shell", False)
    assert kwargs["stdin"] == subprocess.DEVNULL and kwargs["start_new_session"] is True


def test_dataless_request_does_not_depend_on_cloud_resource_metadata(package, monkeypatch):
    commands = []
    original = Path.lstat

    def information(path, *args, **kwargs):
        if path == package / "model.gguf":
            return SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_flags=0x40000000)
        return original(path, *args, **kwargs)

    def popen(command, **kwargs):
        commands.append(command)
        return NativeProcess(json.dumps(["PENDING"] + ["LOCAL"] * 3))

    monkeypatch.setattr(Path, "lstat", information)
    monkeypatch.setattr(cloud.subprocess, "Popen", popen)
    assert cloud._native_states([package / name for name in NAMES], True, time.monotonic() + 10, None)[0] == "PENDING"
    assert json.loads(commands[0][7]) == [True, False, False, False]


@pytest.mark.skipif(sys.platform != "darwin", reason="macOS 기본 JavaScript 실행기가 필요합니다.")
def test_null_cloud_metadata_starts_request_and_becomes_local_when_dataless_clears():
    # Foundation 대신 메모리 객체를 주입해 실제 파일이나 다운로드를 건드리지 않는다.
    bridge = r'''
var requests = 0;
function boxed(value) {
    return {raw: value, isNil: function () { return value === null; }};
}
var ObjC = {unwrap: function (value) {
    return value && value.raw !== undefined ? value.raw : value;
}};
var $ = function (value) {
    return {stringByDeletingLastPathComponent: value.substring(0, value.lastIndexOf('/')) || '/'};
};
$.NSURLIsUbiquitousItemKey = 'cloud';
$.NSURLUbiquitousItemDownloadingStatusKey = 'status';
$.NSURLUbiquitousItemDownloadingErrorKey = 'error';
$.NSURLUbiquitousItemDownloadingStatusCurrent = 'current';
$.NSFileType = 'type';
$.NSFileTypeSymbolicLink = 'link';
var emptyProperties = {
    isNil: function () { return false; },
    objectForKey: function () { return boxed(null); }
};
$.NSURL = {fileURLWithPath: function () {
    return {resourceValuesForKeysError: function () { return emptyProperties; }};
}};
$.NSFileManager = {defaultManager: {
    attributesOfItemAtPathError: function () {
        return {isNil: function () { return false; }, objectForKey: function () { return boxed('file'); }};
    },
    startDownloadingUbiquitousItemAtURLError: function () { requests++; return true; }
}};
'''
    script = cloud.FOUNDATION_SCRIPT.replace("ObjC.import('Foundation');", "").replace("function run(argv)", "function cloudRun(argv)")
    execution = r'''
function run() {
    var pending = JSON.parse(cloudRun(['request', '[true]', '/fixture/model.gguf']));
    var local = JSON.parse(cloudRun(['status', '[false]', '/fixture/model.gguf']));
    return JSON.stringify({requests: requests, pending: pending, local: local});
}
'''
    completed = subprocess.run([cloud.OSASCRIPT, "-l", "JavaScript", "-e", bridge + script + execution],
                               check=True, capture_output=True, text=True, timeout=10)
    assert json.loads(completed.stdout) == {"requests": 1, "pending": ["PENDING"], "local": ["LOCAL"]}


@pytest.mark.parametrize("output", ["/private/user-secret", "{}", '["LOCAL"]', '["UNKNOWN","LOCAL","LOCAL","LOCAL"]'])
def test_native_invalid_output_is_not_exposed(package, monkeypatch, output):
    monkeypatch.setattr(cloud.subprocess, "Popen", lambda *args, **kwargs: NativeProcess(output))
    with pytest.raises(cloud.CloudModelFilesError) as error:
        cloud._native_states([package / name for name in NAMES], True, time.monotonic() + 10, None)
    assert output not in str(error.value) and error.value.code == "TRAINED_MODEL_CLOUD_DOWNLOAD_UNAVAILABLE"


def test_native_start_failure_does_not_expose_os_error(package, monkeypatch):
    def fail(*args, **kwargs):
        raise OSError("/private/key-and-path")

    monkeypatch.setattr(cloud.subprocess, "Popen", fail)
    with pytest.raises(cloud.CloudModelFilesError) as error:
        cloud._native_states([package / name for name in NAMES], True, time.monotonic() + 10, None)
    assert "key-and-path" not in str(error.value)


@pytest.mark.parametrize("cancelled", [False, True])
def test_native_process_is_killed_when_cancelled_or_timed_out(package, monkeypatch, cancelled):
    process = NativeProcess("", running=True)
    monkeypatch.setattr(cloud.subprocess, "Popen", lambda *args, **kwargs: process)
    calls = []

    def check():
        calls.append(True)
        if len(calls) == 3:
            if cancelled:
                raise InterruptedError("중단")
            raise TimeoutError()

    with pytest.raises(InterruptedError if cancelled else TimeoutError):
        cloud._native_states([package / name for name in NAMES], True, time.monotonic() + 10, check)
    assert process.killed
