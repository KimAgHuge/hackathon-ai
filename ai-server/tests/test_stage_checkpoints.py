import json
import os
import time

from app.services.stage_checkpoints import StageCheckpoints


def test_signed_stage_survives_new_store_and_has_private_permissions(tmp_path):
    directory = tmp_path / "stages"
    store = StageCheckpoints(directory, "test-only-signing-key")
    key = store.key({"project": "project_example", "model": "model_example"})
    assert store.write(key, {"summary": "검증된 가상 분석 결과입니다."})
    assert StageCheckpoints(directory, "test-only-signing-key").read(key) == {"summary": "검증된 가상 분석 결과입니다."}
    assert directory.stat().st_mode & 0o077 == 0
    assert (directory / f"{key}.json").stat().st_mode & 0o077 == 0
    assert "test-only-signing-key" not in (directory / f"{key}.json").read_text()


def test_tampered_expired_or_different_key_stage_is_not_reused(tmp_path, monkeypatch):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key", max_age=60)
    key = store.key({"request": "example"})
    assert store.write(key, {"summary": "정상 검토 결과입니다."})
    assert StageCheckpoints(store.directory, "different-test-key").read(key) is None
    path = store.directory / f"{key}.json"
    original = path.read_text()
    changed = json.loads(original)
    changed["value"]["result"]["summary"] = "바뀐 검토 결과입니다."
    path.write_text(json.dumps(changed))
    assert store.read(key) is None
    path.write_text(original)
    now = time.time()
    monkeypatch.setattr("app.services.stage_checkpoints.time.time", lambda: now + 120)
    assert store.read(key) is None


def test_corrupt_or_linked_stage_is_ignored_without_following_it(tmp_path):
    store = StageCheckpoints(tmp_path / "stages", "test-only-signing-key")
    key = store.key({"request": "example"})
    assert store.write(key, {"summary": "가상 결과입니다."})
    path = store.directory / f"{key}.json"
    path.write_text("not-json")
    assert store.read(key) is None
    path.unlink()
    outside = tmp_path / "outside.json"
    outside.write_text("보존할 파일입니다.")
    path.symlink_to(outside)
    assert store.read(key) is None
    assert store.write(key, {}) is False
    assert outside.read_text() == "보존할 파일입니다."


def test_public_directory_is_not_used_for_checkpoint_writes(tmp_path):
    directory = tmp_path / "public-directory"
    directory.mkdir()
    os.chmod(directory, 0o755)
    store = StageCheckpoints(directory, "test-only-signing-key")
    assert store.write(store.key({"request": "example"}), {"summary": "가상 결과입니다."}) is False
