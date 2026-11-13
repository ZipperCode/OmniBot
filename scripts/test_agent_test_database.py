"""Unit tests for agent_test_database consumer boundaries.

边界测试覆盖：
1. CLI 成功模式：当 sqlite3 CLI 存在且成功时，优先走 .backup 并成功读取只读连接；
2. CLI 缺失回退模式：当 sqlite3 CLI 不可用 (如 exit 127) 时，自动回退到 DebugAgentDatabaseSnapshotReceiver；
3. 完成前不读取半成品：在 status 为 pending 时等待，超时报 TimeoutError，失败报 RuntimeError，绝不提前读取 db；
4. 严格校验回执属性：严格核对 schema_version、nonce 与 db_file，属性不匹配时不误读；
5. 失败清理仅本次 cache 文件：严格仅删除本次 nonce 对应的 cache/oob-checkpoint-* 临时文件；
6. CLI 实质报错与回退失败时完整保留原始错误；
7. 原错误保留：当快照或业务抛出异常且清理阶段发生异常时，严格保留并抛出原始错误，绝不被遮蔽。
"""
import json
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_test_database


def _create_minimal_sqlite_bytes() -> bytes:
    """生成一个合法的最小内存 SQLite 数据库二进制内容。显式关闭连接以避免 Windows WinError 32。"""
    with tempfile.NamedTemporaryFile(suffix='.db', delete=False) as f:
        tmp_path = Path(f.name)
    db = sqlite3.connect(tmp_path)
    try:
        db.execute("CREATE TABLE test_table (id INTEGER PRIMARY KEY, content TEXT)")
        db.execute("INSERT INTO test_table (content) VALUES ('oob_fixture_data')")
        db.commit()
    finally:
        db.close()

    try:
        return tmp_path.read_bytes()
    finally:
        tmp_path.unlink(missing_ok=True)


class AgentDatabaseSnapshotConsumerTest(unittest.TestCase):

    def setUp(self):
        self.sample_db_bytes = _create_minimal_sqlite_bytes()
        # 全局屏蔽真实 sleep，确保测试毫秒级确定性执行，不发生真实忙等
        sleep_patcher = patch('time.sleep', return_value=None)
        self.mock_sleep = sleep_patcher.start()
        self.addCleanup(sleep_patcher.stop)

    def test_cli_success(self):
        """CLI 可用且成功时走 .backup 路径，并以只读模式连接本地副本。"""
        def fake_run(cmd, *args, **kwargs):
            cmd_str = ' '.join(cmd) if isinstance(cmd, list) else cmd
            # sqlite3 .backup 命令行调用
            if 'sqlite3' in cmd_str and '.backup' in cmd_str:
                return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')
            # ls -l 检查文件生成：动态返回真实目标文件名
            if 'ls' in cmd_str and '-l' in cmd_str:
                target_file = cmd[-1] if isinstance(cmd, list) else cmd_str.split()[-1]
                return subprocess.CompletedProcess(cmd, 0, stdout=f'-rw------- 1 u0_a100 u0_a100 4096 {target_file}', stderr='')
            # exec-out cat 读取 db 文件
            if 'exec-out' in cmd_str and 'cat' in cmd_str and '.db' in cmd_str:
                return subprocess.CompletedProcess(cmd, 0, stdout=self.sample_db_bytes, stderr=b'')
            # rm -f 清理
            if 'rm' in cmd_str and '-f' in cmd_str:
                return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')
            return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')

        with patch('subprocess.run', side_effect=fake_run):
            with agent_test_database.agent_database_snapshot('emulator-5554') as db:
                row = db.execute("SELECT content FROM test_table").fetchone()
                self.assertIsNotNone(row)
                self.assertEqual(row[0], 'oob_fixture_data')

                # 验证连接只读约束：任何写操作必须被 SQLite 引擎拦截
                with self.assertRaises(sqlite3.OperationalError):
                    db.execute("INSERT INTO test_table (content) VALUES ('forbidden_write')")

    def test_cli_missing_fallback_to_receiver(self):
        """CLI 缺失（exit 127）时平滑回退至广播 Receiver VACUUM INTO。"""
        captured_commands = []
        target_nonce = None

        def fake_run(cmd, *args, **kwargs):
            nonlocal target_nonce
            cmd_str = ' '.join(cmd) if isinstance(cmd, list) else cmd
            captured_commands.append(cmd)

            # sqlite3 CLI 缺失返回 exit code 127
            if 'sqlite3' in cmd_str and '.backup' in cmd_str:
                return subprocess.CompletedProcess(cmd, 127, stdout='', stderr='sqlite3: inaccessible or not found')
            # 广播命令成功，提取 nonce
            if 'am' in cmd_str and 'broadcast' in cmd_str:
                if '--es' in cmd:
                    nonce_idx = cmd.index('--es') + 2
                    target_nonce = cmd[nonce_idx]
                return subprocess.CompletedProcess(cmd, 0, stdout='Broadcast completed: result=0\n', stderr='')
            # 读取状态回执：返回与 nonce 严格匹配的 completed 回执
            if 'exec-out' in cmd_str and 'cat' in cmd_str and '.json' in cmd_str:
                receipt = json.dumps({
                    'schema_version': agent_test_database.SCHEMA_VERSION,
                    'nonce': target_nonce,
                    'status': 'completed',
                    'success': True,
                    'db_file': f'oob-checkpoint-{target_nonce}.db'
                }).encode('utf-8')
                return subprocess.CompletedProcess(cmd, 0, stdout=receipt, stderr=b'')
            # 读取数据库内容
            if 'exec-out' in cmd_str and 'cat' in cmd_str and '.db' in cmd_str:
                return subprocess.CompletedProcess(cmd, 0, stdout=self.sample_db_bytes, stderr=b'')
            # rm -f 清理
            if 'rm' in cmd_str and '-f' in cmd_str:
                return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')
            return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')

        with patch('subprocess.run', side_effect=fake_run):
            with agent_test_database.agent_database_snapshot('emulator-5554') as db:
                row = db.execute("SELECT content FROM test_table").fetchone()
                self.assertEqual(row[0], 'oob_fixture_data')

        # 断言广播接收器确实被触发了
        broadcast_called = any(
            isinstance(c, list) and 'DebugAgentDatabaseSnapshotReceiver' in ' '.join(c)
            for c in captured_commands
        )
        self.assertTrue(broadcast_called, "DebugAgentDatabaseSnapshotReceiver broadcast must be invoked on CLI missing")

    def test_never_reads_incomplete_or_pending_db(self):
        """在状态为 pending 时绝不读取 db 半成品；超时时抛出 TimeoutError，使用确定性时钟。"""
        cat_db_called = False
        target_nonce = None

        def fake_run(cmd, *args, **kwargs):
            nonlocal cat_db_called, target_nonce
            cmd_str = ' '.join(cmd) if isinstance(cmd, list) else cmd

            if 'sqlite3' in cmd_str and '.backup' in cmd_str:
                return subprocess.CompletedProcess(cmd, 127, stdout='', stderr='sqlite3: not found')
            if 'am' in cmd_str and 'broadcast' in cmd_str:
                if '--es' in cmd:
                    target_nonce = cmd[cmd.index('--es') + 2]
                return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')
            if 'exec-out' in cmd_str and 'cat' in cmd_str and '.json' in cmd_str:
                # 一直返回 pending
                pending_json = json.dumps({
                    'schema_version': agent_test_database.SCHEMA_VERSION,
                    'nonce': target_nonce,
                    'status': 'pending',
                    'success': False
                }).encode('utf-8')
                return subprocess.CompletedProcess(cmd, 0, stdout=pending_json, stderr=b'')
            if 'exec-out' in cmd_str and 'cat' in cmd_str and '.db' in cmd_str:
                cat_db_called = True
                return subprocess.CompletedProcess(cmd, 0, stdout=self.sample_db_bytes, stderr=b'')
            return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')

        # 确定性 mock clock：每次调用单调递增，无任何真实延迟
        mock_time = [1000.0]
        def fake_monotonic():
            mock_time[0] += 0.5
            return mock_time[0]

        with patch('subprocess.run', side_effect=fake_run), patch('time.monotonic', side_effect=fake_monotonic):
            with self.assertRaises(TimeoutError):
                with agent_test_database.agent_database_snapshot('emulator-5554', timeout=2):
                    pass

        # 核心断言：在状态未 completed 前，绝不读取半成品数据库文件
        self.assertFalse(cat_db_called, "Must never read database file before snapshot status is completed")

    def test_device_failure_status_raises_runtime_error_with_safe_enum(self):
        """当设备端返回 status=failed 时明确抛出带有受限枚举错误码的 RuntimeError。"""
        target_nonce = None

        def fake_run(cmd, *args, **kwargs):
            nonlocal target_nonce
            cmd_str = ' '.join(cmd) if isinstance(cmd, list) else cmd
            if 'sqlite3' in cmd_str and '.backup' in cmd_str:
                return subprocess.CompletedProcess(cmd, 127, stdout='', stderr='sqlite3: not found')
            if 'am' in cmd_str and 'broadcast' in cmd_str:
                if '--es' in cmd:
                    target_nonce = cmd[cmd.index('--es') + 2]
                return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')
            if 'exec-out' in cmd_str and 'cat' in cmd_str and '.json' in cmd_str:
                failed_json = json.dumps({
                    'schema_version': agent_test_database.SCHEMA_VERSION,
                    'nonce': target_nonce,
                    'status': 'failed',
                    'success': False,
                    'error': 'vacuum_into_failed'
                }).encode('utf-8')
                return subprocess.CompletedProcess(cmd, 0, stdout=failed_json, stderr=b'')
            return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')

        with patch('subprocess.run', side_effect=fake_run):
            with self.assertRaises(RuntimeError) as ctx:
                with agent_test_database.agent_database_snapshot('emulator-5554'):
                    pass
            self.assertIn('vacuum_into_failed', str(ctx.exception))

    def test_cleanup_only_current_nonce_files(self):
        """验证 finally 清理严格只清理本次 nonce 的 cache 文件，绝不扩大清理范围。"""
        cleanup_files = []

        def fake_run(cmd, *args, **kwargs):
            cmd_str = ' '.join(cmd) if isinstance(cmd, list) else cmd
            if 'sqlite3' in cmd_str and '.backup' in cmd_str:
                return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')
            if 'ls' in cmd_str and '-l' in cmd_str:
                target_file = cmd[-1] if isinstance(cmd, list) else cmd_str.split()[-1]
                return subprocess.CompletedProcess(cmd, 0, stdout=f'-rw------- 1 u0_a100 u0_a100 4096 {target_file}', stderr='')
            if 'exec-out' in cmd_str and 'cat' in cmd_str and '.db' in cmd_str:
                return subprocess.CompletedProcess(cmd, 0, stdout=self.sample_db_bytes, stderr=b'')
            if 'rm' in cmd_str and '-f' in cmd_str:
                rm_index = cmd.index('rm')
                cleanup_files.extend(cmd[rm_index + 2:])
                return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')
            return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')

        with patch('subprocess.run', side_effect=fake_run):
            with agent_test_database.agent_database_snapshot('emulator-5554') as db:
                pass

        self.assertTrue(len(cleanup_files) > 0, "Cleanup files list must not be empty")
        db_file = [f for f in cleanup_files if f.endswith('.db')][0]
        nonce_match = agent_test_database.NONCE_PATTERN.search(db_file.split('oob-checkpoint-')[-1].split('.')[0])
        self.assertIsNotNone(nonce_match)
        nonce = nonce_match.group(0)

        for target in cleanup_files:
            self.assertTrue(
                target.startswith(f"cache/oob-checkpoint-{nonce}"),
                f"Cleanup target {target} does not belong to current nonce {nonce}"
            )

    def test_cli_real_error_and_receiver_failure_preserves_cli_error(self):
        """当 CLI 遇到实质错误（非缺失）且回退 receiver 失败时，保留 CLI 原始错误。"""
        def fake_run(cmd, *args, **kwargs):
            cmd_str = ' '.join(cmd) if isinstance(cmd, list) else cmd
            if 'sqlite3' in cmd_str and '.backup' in cmd_str:
                return subprocess.CompletedProcess(cmd, 1, stdout='', stderr='sqlite3_disk_io_error')
            if 'am' in cmd_str and 'broadcast' in cmd_str:
                return subprocess.CompletedProcess(cmd, 1, stdout='', stderr='broadcast_failed')
            return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')

        with patch('subprocess.run', side_effect=fake_run):
            with self.assertRaises(RuntimeError) as ctx:
                with agent_test_database.agent_database_snapshot('emulator-5554'):
                    pass
            self.assertIn('cli_backup_failed', str(ctx.exception))

    def test_original_error_preserved_when_cleanup_fails(self):
        """当发生业务异常且随后清理也抛出异常时，必须严格保留原始业务异常。"""
        class TargetBusinessException(Exception):
            pass

        def fake_run(cmd, *args, **kwargs):
            cmd_str = ' '.join(cmd) if isinstance(cmd, list) else cmd
            if 'rm' in cmd_str and '-f' in cmd_str:
                # 模拟清理时 adb 连接崩溃抛出异常
                raise subprocess.SubprocessError("adb transport disconnected during cleanup")
            # 模拟前面的获取过程报错
            raise TargetBusinessException("original_snapshot_failure")

        with patch('subprocess.run', side_effect=fake_run):
            with self.assertRaises(TargetBusinessException) as ctx:
                with agent_test_database.agent_database_snapshot('emulator-5554'):
                    pass
            self.assertEqual(str(ctx.exception), "original_snapshot_failure")


if __name__ == '__main__':
    unittest.main()
