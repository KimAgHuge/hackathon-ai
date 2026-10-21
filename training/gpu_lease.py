"""학습과 모델 생성이 같은 장비의 GPU 작업을 겹쳐 실행하지 않게 한다."""

import fcntl
import os
from pathlib import Path


class GpuLease:
    """자식 프로세스에도 이어지는 파일 잠금으로 서버 재시작 사이의 중복 실행을 막는다."""

    def __init__(self, path):
        self.path = Path(path)
        self.descriptor = None

    def acquire(self):
        if self.descriptor is not None:
            return False
        descriptor = os.open(self.path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            os.close(descriptor)
            return False
        except BaseException:
            os.close(descriptor)
            raise
        self.descriptor = descriptor
        return True

    def release(self):
        if self.descriptor is not None:
            os.close(self.descriptor)
            self.descriptor = None
