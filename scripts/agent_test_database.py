"""Consistent read-only snapshots for synthetic Android history assertions.

针对真机/无 sqlite3 CLI 环境，提供双模快照支持：
1. 优先尝试真机 sqlite3 .backup 命令行（若存在）；
2. 缺失时自动回退至 App 专有 DebugAgentDatabaseSnapshotReceiver，利用 Android SQLite 原生
   VACUUM INTO ? 生成一致只读副本；
3. 严格通过原子回执轮询，校验 schema_version、nonce 与 db_file，完成前绝不读取半成品，超时保错误；
4. 错误输出限制为安全枚举类别，绝不向标准输出输出设备原始命令文本或私有数据；
5. 本地副本强制以只读 URI (mode=ro) 连接并在退出时显式关闭，防止 Windows WinError 32；
6. finally 仅清理本次特定 nonce 的 cache 临时文件，且绝不掩盖原始异常。
"""
import json
import re
import shlex
import sqlite3
import subprocess
import tempfile
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

PACKAGE_NAME = 'cn.com.omnimind.bot'
DATABASE_NAME = 'omnibot_cache_databaseoss'
SNAPSHOT_RECEIVER = f'{PACKAGE_NAME}/.debug.DebugAgentDatabaseSnapshotReceiver'
SNAPSHOT_ACTION = f'{PACKAGE_NAME}.debug.SNAPSHOT_DATABASE'
SCHEMA_VERSION = 'oob.database_snapshot.v1'
NONCE_PATTERN = re.compile(r'^[0-9a-fA-F]{16,64}$')

VALID_ERROR_CODES = {
    'invalid_nonce',
    'nonce_collision',
    'source_database_missing',
    'vacuum_into_failed',
    'receipt_write_failed',
    'internal_error',
}


def _try_cli_backup(serial: str, remote_db: str, timeout: int = 15) -> tuple[bool, str]:
    """尝试通过真机 sqlite3 CLI 进行 .backup。若 CLI 不可用或报错则返回 (False, safe_error_code)。"""
    command = shlex.join(['run-as', PACKAGE_NAME, 'sqlite3',
        f'databases/{DATABASE_NAME}', f".backup '{remote_db}'"])
    target_name = remote_db.split('/')[-1]
    try:
        proc = subprocess.run(
            ['adb', '-s', serial, 'shell', command],
            capture_output=True,
            text=True,
            timeout=timeout
        )
        if proc.returncode == 0:
            # 严格核对完整 nonce 文件名是否存在，绝不使用前缀放宽
            check_cmd = ['adb', '-s', serial, 'shell', 'run-as', PACKAGE_NAME, 'ls', '-l', remote_db]
            check_res = subprocess.run(check_cmd, capture_output=True, text=True, timeout=10)
            if check_res.returncode == 0 and target_name in check_res.stdout:
                return True, 'cli_success'
            return False, 'cli_file_missing'

        # 仅分类为固定安全类别，绝不暴露 stderr/stdout 原文
        stderr_lower = proc.stderr.lower() if proc.stderr else ''
        if proc.returncode == 127 or 'not found' in stderr_lower or 'inaccessible' in stderr_lower:
            return False, 'cli_missing'
        return False, 'cli_backup_failed'
    except (subprocess.SubprocessError, OSError):
        return False, 'cli_execution_error'


def _receiver_snapshot(serial: str, nonce: str, remote_status: str, remote_db: str, timeout: int = 30) -> None:
    """通过 DebugAgentDatabaseSnapshotReceiver 广播触发 VACUUM INTO，并轮询等待原子完成回执。"""
    if not NONCE_PATTERN.match(nonce):
        raise ValueError(f"Invalid snapshot nonce format: {nonce}")

    broadcast_cmd = [
        'adb', '-s', serial, 'shell', 'am', 'broadcast',
        '-n', SNAPSHOT_RECEIVER,
        '-a', SNAPSHOT_ACTION,
        '--es', 'nonce', nonce,
    ]
    broadcast_res = subprocess.run(
        broadcast_cmd,
        capture_output=True,
        text=True,
        timeout=timeout
    )
    if broadcast_res.returncode != 0:
        raise RuntimeError(
            f"Failed to broadcast snapshot intent (exit code {broadcast_res.returncode})"
        )

    deadline = time.monotonic() + timeout
    poll_interval = 0.2
    completed_receipt = None
    expected_db_file = f"oob-checkpoint-{nonce}.db"

    while time.monotonic() < deadline:
        cat_cmd = ['adb', '-s', serial, 'exec-out', 'run-as', PACKAGE_NAME, 'cat', remote_status]
        cat_res = subprocess.run(cat_cmd, capture_output=True, timeout=10)
        if cat_res.returncode == 0 and cat_res.stdout.strip():
            try:
                receipt = json.loads(cat_res.stdout.decode('utf-8'))
                # 严格校验 schema_version 与 nonce 归属
                if receipt.get('schema_version') != SCHEMA_VERSION or receipt.get('nonce') != nonce:
                    time.sleep(poll_interval)
                    continue

                status = receipt.get('status')
                if status == 'pending':
                    # 正在生成中，继续等待，绝不读取半成品
                    time.sleep(poll_interval)
                    continue
                elif status == 'completed':
                    if receipt.get('db_file') == expected_db_file and receipt.get('success') is True:
                        completed_receipt = receipt
                        break
                    else:
                        raise RuntimeError("Device snapshot completed with corrupted receipt state")
                elif status == 'failed':
                    raw_error = str(receipt.get('error', 'internal_error'))
                    safe_error = raw_error if raw_error in VALID_ERROR_CODES else 'internal_error'
                    raise RuntimeError(f"Device snapshot failed: {safe_error}")
            except (json.JSONDecodeError, UnicodeDecodeError):
                # 处于文件重命名或写入间隙，重试
                pass
        time.sleep(poll_interval)

    if completed_receipt is None:
        raise TimeoutError(
            f"Database snapshot timed out waiting for receipt after {timeout}s: nonce={nonce}"
        )


def _fetch_remote_db(serial: str, remote_db: str, local_path: Path, timeout: int = 30) -> None:
    """将远端完成的快照文件拉取到本地临时文件。不向标准输出输出数据库私有内容。"""
    cat_cmd = ['adb', '-s', serial, 'exec-out', 'run-as', PACKAGE_NAME, 'cat', remote_db]
    result = subprocess.run(cat_cmd, capture_output=True, timeout=timeout)
    if result.returncode != 0 or not result.stdout:
        raise RuntimeError(
            f"Failed to fetch snapshot database {remote_db} from device (exit code {result.returncode})"
        )
    local_path.write_bytes(result.stdout)


def _cleanup_remote_files(serial: str, remote_files: list[str]) -> None:
    """清理远端本次特定生成的临时文件。仅删除本次指定文件，不影响其他文件。"""
    rm_cmd = ['adb', '-s', serial, 'shell', 'run-as', PACKAGE_NAME, 'rm', '-f'] + remote_files
    subprocess.run(rm_cmd, capture_output=True, timeout=10)


@contextmanager
def agent_database_snapshot(serial: str, timeout: int = 30):
    with tempfile.TemporaryDirectory(prefix='oob-checkpoint-') as directory:
        local_db_path = Path(directory) / 'history.db'
        nonce = uuid.uuid4().hex
        remote_lock = f"cache/oob-checkpoint-{nonce}.lock"
        remote_db = f"cache/oob-checkpoint-{nonce}.db"
        remote_db_tmp = f"cache/oob-checkpoint-{nonce}.db.tmp"
        remote_status = f"cache/oob-checkpoint-{nonce}.json"
        remote_status_tmp = f"cache/oob-checkpoint-{nonce}.json.tmp"
        all_remote_files = [remote_lock, remote_db, remote_db_tmp, remote_status, remote_status_tmp]

        db = None
        original_error = None
        try:
            # 1. 优先尝试 sqlite3 CLI .backup
            cli_ok, cli_err_code = _try_cli_backup(serial, remote_db, timeout=min(timeout, 15))
            if not cli_ok:
                try:
                    # 2. 回退到 Receiver VACUUM INTO 原生快照
                    _receiver_snapshot(serial, nonce, remote_status, remote_db, timeout=timeout)
                except Exception as receiver_err:
                    # 若 CLI 遇到实质错误（非缺失）且回退亦失败，抛出固定安全类别错误，绝不暴露私有数据
                    if cli_err_code != 'cli_missing':
                        raise RuntimeError(f"Database snapshot failed: {cli_err_code}") from receiver_err
                    raise

            # 3. 读取已就绪的完整数据库快照
            _fetch_remote_db(serial, remote_db, local_db_path, timeout=timeout)

            # 4. 连接副本以只读模式 (mode=ro) 打开，杜绝任何意外写入
            db_uri = f"{local_db_path.resolve().as_uri()}?mode=ro"
            db = sqlite3.connect(db_uri, uri=True)
            yield db
        except Exception as error:
            original_error = error
            raise
        finally:
            # 5. 必须在退出 TemporaryDirectory 前显式关闭 SQLite 连接，防止 Windows 下出现 WinError 32
            if db is not None:
                try:
                    db.close()
                except Exception:
                    pass

            # 6. 清理远端 cache 目录下的本次特定临时文件；异常时不掩盖原始业务异常
            try:
                _cleanup_remote_files(serial, all_remote_files)
            except Exception:
                if original_error is None:
                    raise
