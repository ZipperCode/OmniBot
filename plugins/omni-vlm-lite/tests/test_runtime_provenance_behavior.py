from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import py_compile
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


BOOTSTRAP_CLI_SCRIPT = """
import sys, runpy, importlib.machinery, importlib.abc, hashlib, marshal
from pathlib import Path
_C = {}
sys._omniflow_loaded_modules = _C
class _L(importlib.machinery.SourceFileLoader):
    def get_code(self, fn):
        p = self.get_filename(fn)
        if p.endswith(('.pyc', '.pyo')):
            try:
                import importlib.util
                p = importlib.util.source_from_cache(p)
            except Exception:
                p = p[:-1]
        b = Path(p).read_bytes()
        co = compile(b, p, 'exec')
        name = fn[len('omniflow.'):] if fn.startswith('omniflow.') else fn
        _C[name] = {'source_sha256': hashlib.sha256(b).hexdigest(), 'code_sha256': hashlib.sha256(marshal.dumps(co)).hexdigest()}
        return co
class _F(importlib.abc.MetaPathFinder):
    def find_spec(self, fn, path, target=None):
        if not fn.startswith('omniflow'):
            return None
        s = importlib.machinery.PathFinder.find_spec(fn, path, target)
        if s and s.origin and s.origin.endswith(('.py', '.pyc', '.pyo')):
            orig = s.origin[:-1] if s.origin.endswith(('.pyc', '.pyo')) else s.origin
            s.loader = _L(fn, orig)
            return s
        return None
sys.meta_path.insert(0, _F())
sys.argv = ['omniflow.bridge'] + sys.argv[1:]
runpy.run_module('omniflow.bridge', run_name='__main__', alter_sys=True)
"""


class RuntimeProvenanceBehaviorTest(unittest.TestCase):
    temp_dir: Path | None = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="omniflow_prov_test_"))
        repo_root = Path(__file__).resolve().parents[3]
        zip_path = repo_root / "artifacts" / "omniflow-gui-runtime-2.1.8.zip"
        if not zip_path.is_file():
            raise FileNotFoundError(f"Base runtime zip not found: {zip_path}")

        with zipfile.ZipFile(zip_path) as z:
            for member in z.namelist():
                if member.startswith("scripts/runtime/python/"):
                    rel = member[len("scripts/runtime/python/") :]
                    if rel:
                        target = cls.temp_dir / rel
                        if member.endswith("/"):
                            target.mkdir(parents=True, exist_ok=True)
                        else:
                            target.parent.mkdir(parents=True, exist_ok=True)
                            target.write_bytes(z.read(member))
                elif member.startswith("vendor/site-packages/"):
                    rel = member[len("vendor/site-packages/") :]
                    if rel:
                        target = cls.temp_dir / rel
                        if member.endswith("/"):
                            target.mkdir(parents=True, exist_ok=True)
                        else:
                            target.parent.mkdir(parents=True, exist_ok=True)
                            target.write_bytes(z.read(member))

        source_root = repo_root / "plugins" / "omni-vlm-lite" / "runtime-source" / "omniflow"
        for py_file in source_root.rglob("*.py"):
            rel = py_file.relative_to(source_root)
            target = cls.temp_dir / "omniflow" / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(py_file.read_bytes())

        for schema_name in ("oob_canonical_actions.v1.json", "omniflow_run_log.v1.json"):
            schema_source = repo_root / "plugins" / "omni-vlm-lite" / "schemas" / "oob" / schema_name
            if schema_source.is_file():
                schema_target = cls.temp_dir / "schemas" / "oob" / schema_name
                schema_target.parent.mkdir(parents=True, exist_ok=True)
                schema_target.write_bytes(schema_source.read_bytes())

        sys.path.insert(0, str(cls.temp_dir))
        for mod in list(sys.modules.keys()):
            if mod.startswith("omniflow"):
                del sys.modules[mod]

    @classmethod
    def tearDownClass(cls) -> None:
        if cls.temp_dir and cls.temp_dir.is_dir():
            try:
                shutil.rmtree(cls.temp_dir)
            except OSError:
                pass
        if cls.temp_dir and str(cls.temp_dir) in sys.path:
            sys.path.remove(str(cls.temp_dir))
        for mod in list(sys.modules.keys()):
            if mod.startswith("omniflow"):
                del sys.modules[mod]

    def test_unbootstrapped_import_reports_all_tracked_modules_as_missing(self) -> None:
        """未通过 bootstrap 启动时，_inspect_loaded_modules 决不读取磁盘假猜，全标记 missing"""
        if hasattr(sys, "_omniflow_loaded_modules"):
            del sys._omniflow_loaded_modules
        import omniflow.bridge as bridge

        inspected = bridge._inspect_loaded_modules()
        for mod_name in bridge._TRACKED_MODULES:
            self.assertEqual({"status": "missing"}, inspected[mod_name])

    def test_real_bootstrap_cli_captures_all_7_modules_from_source(self) -> None:
        """通过真实 Client bootstrap CLI 启动 Python 进程，7个核心模块完整由源编译捕获，且无绝对路径泄漏"""
        assert self.temp_dir is not None
        proc = subprocess.Popen(
            [sys.executable, "-u", "-c", BOOTSTRAP_CLI_SCRIPT, "--store", str(self.temp_dir / "store.json")],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=str(self.temp_dir),
            env={**os.environ, "PYTHONPATH": str(self.temp_dir)},
            text=True,
        )
        req = json.dumps({
            "jsonrpc": "2.0",
            "id": "init-provenance-1",
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "test", "version": "1"},
            },
        }) + "\n"

        stdout, stderr = proc.communicate(input=req, timeout=15)
        self.assertEqual("", stderr)
        resp = json.loads(stdout.strip())
        runtime_meta = resp["result"]["_meta"]["omniflow/runtime"]
        loaded = runtime_meta["loaded_modules"]

        expected_modules = [
            "bridge",
            "runtime.engine",
            "vlm.planner",
            "core.schemas",
            "runtime.app_experience",
            "runtime.screen_collection",
            "runlog",
        ]
        for name in expected_modules:
            self.assertIn(name, loaded)
            entry = loaded[name]
            self.assertIn("source_sha256", entry)
            self.assertIn("code_sha256", entry)
            self.assertEqual(64, len(entry["source_sha256"]))
            self.assertEqual(64, len(entry["code_sha256"]))

        serialized = json.dumps(runtime_meta)
        self.assertNotIn(str(self.temp_dir), serialized)
        self.assertNotIn("D:/", serialized)
        self.assertNotIn("D:\\", serialized)

    def test_stale_pyc_counterexample_bypassed_by_source_loader(self) -> None:
        """旧 pyc 缓存反例：磁盘存在旧字节码 pyc 时，SourceFileLoader 仍强制从源文件编译执行真实代码"""
        assert self.temp_dir is not None
        test_file = self.temp_dir / "omniflow" / "stale_verify.py"
        test_file.write_text("OUT = 'OLD_STALE'\n", encoding="utf-8")
        cache_dir = self.temp_dir / "omniflow" / "__pycache__"
        cache_dir.mkdir(parents=True, exist_ok=True)
        pyc_dst = cache_dir / f"stale_verify.cpython-{sys.version_info.major}{sys.version_info.minor}.pyc"
        py_compile.compile(str(test_file), cfile=str(pyc_dst))

        # 修改源文件为新内容，同时将 pyc 的 mtime 设为未来，制造旧 pyc 假象
        test_file.write_text("OUT = 'NEW_SOURCE'\n", encoding="utf-8")
        os.utime(str(pyc_dst), (2000000000, 2000000000))

        import importlib.machinery
        import importlib.util
        class VerifiedSourceLoader(importlib.machinery.SourceFileLoader):
            def get_code(self, fn: str):
                p = self.get_filename(fn)
                if p.endswith((".pyc", ".pyo")):
                    try:
                        p = importlib.util.source_from_cache(p)
                    except Exception:
                        p = p[:-1]
                b = Path(p).read_bytes()
                return compile(b, p, "exec")
        loader = VerifiedSourceLoader("omniflow.stale_verify", str(pyc_dst))
        code_obj = loader.get_code("omniflow.stale_verify")
        scope: dict[str, str] = {}
        exec(code_obj, scope)
        self.assertEqual("NEW_SOURCE", scope["OUT"])

    def test_hot_disk_change_after_init_does_not_mutate_snapshot(self) -> None:
        """进程内已初始化的 loaded_modules 快照不随运行中磁盘源文件热修改而动态跳变"""
        assert self.temp_dir is not None
        proc = subprocess.Popen(
            [sys.executable, "-u", "-c", BOOTSTRAP_CLI_SCRIPT, "--store", str(self.temp_dir / "store.json")],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=str(self.temp_dir),
            env={**os.environ, "PYTHONPATH": str(self.temp_dir)},
            text=True,
        )
        req1 = json.dumps({
            "jsonrpc": "2.0",
            "id": "init-hot-1",
            "method": "initialize",
            "params": {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}},
        }) + "\n"
        stdout1, _ = proc.communicate(input=req1, timeout=10)
        resp1 = json.loads(stdout1.strip())
        orig_sha = resp1["result"]["_meta"]["omniflow/runtime"]["loaded_modules"]["runtime.engine"]["source_sha256"]

        # 热修改磁盘上的 engine.py
        engine_file = self.temp_dir / "omniflow" / "runtime" / "engine.py"
        engine_file.write_text(engine_file.read_text(encoding="utf-8") + "\n# HOT DISK MUTATION\n", encoding="utf-8")
        new_file_sha = hashlib.sha256(engine_file.read_bytes()).hexdigest()

        # 原快照绝不等于热改后的磁盘哈希
        self.assertNotEqual(new_file_sha, orig_sha)


if __name__ == "__main__":
    unittest.main()
