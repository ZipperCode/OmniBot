from __future__ import annotations

import asyncio
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import zipfile
import numpy as np


class MatcherCheckpointBehaviorTest(unittest.TestCase):
    """OmniTransfer 真实 NPZ Checkpoint 加载、特征 Schema 与真实 execution 行为回归测试。

    验证：
    1. 真实 rank_action_candidates 消费者回归：
       在不使用任何 mock / stub 的情况下，直接调用真实排序器加载已打包的同一冻结
       NumPy Checkpoint (relation_slots_l3_h64_seed17.npz)，来源点 (250, 250)
       在目标树中成功投影为 (300.0, 300.0)，返回有效候选 (status='scored', candidates >= 1)，
       且结果非 echo；
    2. 兼容性与 Schema 默认推断：
       旧 Checkpoint 缺失 feature_schema_id 时，沿既有官方 Torch (learned_matcher.py)
       的兼容定义，自动采用 LEGACY_GEOMETRIC_FEATURE_SCHEMA_ID ('omnitransfer-direct-pair-evidence-v7')，
       与 Checkpoint 的 32 维 xml_projection.0.weight 权重严格对应；
    3. 未知 Schema 清晰报错：
       对于包含未知 Schema 的 Checkpoint，直接抛出清晰的 ValueError，禁止静默 fallback；
    4. Schema 与 Checkpoint 权重维度不一致报错：
       当 Checkpoint 配置声明的 Schema 特征维度（如 v8 的 38 维）与权重实际维度（32 维）
       不匹配时，提前抛出明确的 ValueError（明确指出维度不一致），禁止静默回退；
    5. 真实 execution.default_transfer click 消费者端到端：
       配合 execution.py 消除未授权关键字参数的修复，真实端到端将 click (250, 250)
       映射为 (300.0, 300.0)，不 mock predict，不手工修改模型属性；
    6. 真实 execution._transfer_swipe swipe 消费者端到端：
       真实端到端将 swipe (250, 250) -> (250, 450) 的两端点映射为 (300.0, 300.0) -> (300.0, 500.0)；
    7. Caller 层应用身份守卫：
       验证应用包名不匹配由 caller 层 (execute_function) 通过 global_package_preflight 拦截并
       返回 package_mismatch，不向下渗透至 ranker。
    """

    temp_dir: Path | None = None
    orig_env_root: str | None = None
    inserted_python_path: str | None = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="omniflow_matcher_test_"))
        repo_root = Path(__file__).resolve().parents[3]

        base_zip = repo_root / "artifacts" / "omniflow-gui-runtime-2.1.8.zip"
        if not base_zip.is_file():
            raise FileNotFoundError(f"Base runtime zip not found: {base_zip}")

        runtime_219_zip = repo_root / "artifacts" / "omniflow-gui-runtime-2.1.9.zip"
        omnitransfer_zip = runtime_219_zip if runtime_219_zip.is_file() else base_zip

        # 1. 解压 Python 运行时与依赖
        with zipfile.ZipFile(base_zip) as z:
            for member in z.namelist():
                if member.startswith("scripts/runtime/python/"):
                    rel = member[len("scripts/runtime/python/") :]
                    if rel:
                        target = cls.temp_dir / "python" / rel
                        if member.endswith("/"):
                            target.mkdir(parents=True, exist_ok=True)
                        else:
                            target.parent.mkdir(parents=True, exist_ok=True)
                            target.write_bytes(z.read(member))
                elif member.startswith("vendor/site-packages/"):
                    rel = member[len("vendor/site-packages/") :]
                    if rel:
                        target = cls.temp_dir / "python" / rel
                        if member.endswith("/"):
                            target.mkdir(parents=True, exist_ok=True)
                        else:
                            target.parent.mkdir(parents=True, exist_ok=True)
                            target.write_bytes(z.read(member))

        # 2. 解压正式 omnitransfer 模块
        with zipfile.ZipFile(omnitransfer_zip) as z:
            for member in z.namelist():
                if member.startswith("scripts/runtime/.runtime/omnitransfer/"):
                    rel = member[len("scripts/runtime/.runtime/omnitransfer/") :]
                    if rel:
                        target = cls.temp_dir / "omnitransfer_root" / rel
                        if member.endswith("/"):
                            target.mkdir(parents=True, exist_ok=True)
                        else:
                            target.parent.mkdir(parents=True, exist_ok=True)
                            target.write_bytes(z.read(member))

        # 3. 叠加本仓库 runtime-source
        source_root = repo_root / "plugins" / "omni-vlm-lite" / "runtime-source" / "omniflow"
        if source_root.is_dir():
            for py_file in source_root.rglob("*.py"):
                rel = py_file.relative_to(source_root)
                target = cls.temp_dir / "python" / "omniflow" / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(py_file.read_bytes())

        # 4. 叠加本仓库 omnitransfer-source 维护覆盖
        omnitransfer_source = repo_root / "plugins" / "omni-vlm-lite" / "omnitransfer-source" / "omnitransfer"
        if omnitransfer_source.is_dir():
            for py_file in omnitransfer_source.rglob("*.py"):
                rel = py_file.relative_to(omnitransfer_source)
                target = cls.temp_dir / "omnitransfer_root" / "src" / "omnitransfer" / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(py_file.read_bytes())

        cls.orig_env_root = os.environ.get("OMNITRANSFER_ROOT")
        os.environ["OMNITRANSFER_ROOT"] = str(cls.temp_dir / "omnitransfer_root")

        cls.inserted_python_path = str(cls.temp_dir / "python")
        if cls.inserted_python_path not in sys.path:
            sys.path.insert(0, cls.inserted_python_path)

        for mod in list(sys.modules.keys()):
            if mod.startswith("omniflow") or mod.startswith("omnitransfer"):
                del sys.modules[mod]

        from omniflow.transfer.runtime import load_omnitransfer

        cls.omnitransfer_mod = load_omnitransfer()
        cls.checkpoint_path = (
            cls.temp_dir
            / "omnitransfer_root"
            / "src"
            / "omnitransfer"
            / "checkpoints"
            / "omnitransfer_unified_association_v1_20260819"
            / "relation_slots_l3_h64_seed17.npz"
        )

    @classmethod
    def tearDownClass(cls) -> None:
        try:
            # 1. 恢复 OMNITRANSFER_ROOT 环境变量
            if cls.orig_env_root is not None:
                os.environ["OMNITRANSFER_ROOT"] = cls.orig_env_root
            else:
                os.environ.pop("OMNITRANSFER_ROOT", None)

            # 2. 移除本次插入的 sys.path
            if cls.inserted_python_path is not None:
                while cls.inserted_python_path in sys.path:
                    sys.path.remove(cls.inserted_python_path)

            # 3. 清理 matcher lru_cache
            try:
                import omnitransfer.runtime

                if hasattr(omnitransfer.runtime._load_matcher, "cache_clear"):
                    omnitransfer.runtime._load_matcher.cache_clear()
            except Exception:
                pass

            # 4. 从 sys.modules 移除来自本次 temp_dir 的模块，防止测试套件污染
            if cls.temp_dir is not None:
                temp_dir_str = str(cls.temp_dir.resolve())
                for name, module in list(sys.modules.items()):
                    if name.startswith("omniflow") or name.startswith("omnitransfer"):
                        mod_file = getattr(module, "__file__", None)
                        if mod_file is None or temp_dir_str in str(Path(mod_file).resolve()):
                            del sys.modules[name]

            # 5. 退出引用
            cls.omnitransfer_mod = None
            cls.checkpoint_path = None
        finally:
            # 6. 先退出所有引用再清空临时目录
            if cls.temp_dir is not None:
                shutil.rmtree(cls.temp_dir, ignore_errors=True)

    def test_rank_action_candidates_consumer_with_frozen_npz(self) -> None:
        """真实调用 rank_action_candidates，验证冻结 NPZ 产生 (250, 250) -> (300, 300) 映射候选。"""
        ranker = self.omnitransfer_mod.runtime.rank_action_candidates

        source_xml = (
            '<hierarchy bounds="[0,0][1000,1000]">'
            '<node bounds="[100,100][500,500]" text="OK" class="android.widget.Button" />'
            '</hierarchy>'
        )
        target_xml = (
            '<hierarchy bounds="[0,0][1000,1000]">'
            '<node bounds="[150,150][550,550]" text="OK" class="android.widget.Button" />'
            '</hierarchy>'
        )

        result = ranker(
            source_xml=source_xml,
            target_xml=target_xml,
            source_point=(250.0, 250.0),
            action_type="click",
            top_k=1,
        )

        self.assertEqual(
            result.get("status"),
            "scored",
            f"ranker failed with status={result.get('status')} reason={result.get('reason')} error={result.get('error')}",
        )
        candidates = result.get("candidates") or []
        self.assertGreaterEqual(len(candidates), 1, "Expected at least 1 candidate ranked")

        top_candidate = candidates[0]
        # 来源点 (250, 250) 相对 [100,100][500,500] 偏移为 (0.375, 0.375)
        # 目标节点 [150,150][550,550]，投影为 150 + 0.375 * 400 = 300.0
        self.assertAlmostEqual(float(top_candidate["new_x"]), 300.0, places=1)
        self.assertAlmostEqual(float(top_candidate["new_y"]), 300.0, places=1)

        # 验证非 echo
        self.assertNotEqual(
            (float(top_candidate["new_x"]), float(top_candidate["new_y"])),
            (250.0, 250.0),
        )

        # 验证使用真实已包冻结模型（同 SHA256）
        self.assertEqual(
            result.get("matcher_checkpoint_sha256"),
            "c262f03c32c4b88d2933323fe2b33007281224ef1a8aae1418a9844d354de232",
        )
        self.assertIsNone(result.get("error"))

    def test_checkpoint_missing_schema_defaults_to_legacy_torch_compatible_schema(self) -> None:
        """验证加载缺少 feature_schema_id 的 Checkpoint 时，默认采用与官方 Torch 兼容的 Legacy Schema。"""
        matcher_cls = self.omnitransfer_mod.numpy_v9_matcher.NumpyGeometricAlignmentMatcher
        matcher = matcher_cls.from_checkpoint(self.checkpoint_path)

        from omnitransfer.learned_matcher import LEGACY_GEOMETRIC_FEATURE_SCHEMA_ID

        self.assertEqual(matcher.feature_schema_id, LEGACY_GEOMETRIC_FEATURE_SCHEMA_ID)
        self.assertEqual(matcher.config.feature_schema_id, LEGACY_GEOMETRIC_FEATURE_SCHEMA_ID)

    def test_checkpoint_unknown_schema_raises_clear_invalid_error(self) -> None:
        """验证传入未知 Schema 的 Checkpoint 时，抛出明确的 ValueError，不静默回退。"""
        matcher_cls = self.omnitransfer_mod.numpy_v9_matcher.NumpyGeometricAlignmentMatcher

        with tempfile.NamedTemporaryFile(suffix=".npz", delete=False) as tmp:
            tmp_path = Path(tmp.name)

        try:
            config_payload = {
                "architecture": "omnitransfer_geometric_alignment_v9",
                "assignment_head": "partial_assignment",
                "association_dim": 64,
                "association_layers": 3,
                "candidate_policy": "all_nodes",
                "dropout": 0.05,
                "hidden_dim": 64,
                "max_tokens": 48,
                "num_heads": 4,
                "relation_hidden_dim": 24,
                "source_context_nodes": 48,
                "target_context_nodes": 64,
                "text_encoder": "learned_token_lookup",
                "token_dim": 48,
                "visual_canvas_size": 384,
                "visual_encoder": "deterministic_icon_v1",
                "visual_patch_size": 32,
                "vocab_size": 8192,
                "feature_schema_id": "unsupported_custom_schema_v99",
            }
            arrays = {
                "__schema_version__": np.array(
                    list("omnitransfer_numpy_unified_association_v1".encode("utf-8")),
                    dtype=np.uint8,
                ),
                "__config_json__": np.array(
                    list(json.dumps(config_payload).encode("utf-8")),
                    dtype=np.uint8,
                ),
                "xml_projection.0.weight": np.ones((32,), dtype=np.float32),
                "xml_projection.0.bias": np.zeros((32,), dtype=np.float32),
            }
            np.savez(tmp_path, **arrays)

            with self.assertRaises(ValueError) as ctx:
                matcher_cls.from_checkpoint(tmp_path)
            self.assertIn("unsupported", str(ctx.exception).lower())
        finally:
            tmp_path.unlink(missing_ok=True)

    def test_checkpoint_dimension_mismatch_raises_clear_invalid_error(self) -> None:
        """验证 Checkpoint 声明的 Schema 特征维度与权重实际维度不匹配时，提前抛出明确的 ValueError。

        GEOMETRIC_FEATURE_SCHEMA_ID 要求 38 维，而权重 xml_projection.0.weight 仅为 32 维。
        """
        matcher_cls = self.omnitransfer_mod.numpy_v9_matcher.NumpyGeometricAlignmentMatcher
        from omnitransfer.learned_matcher import GEOMETRIC_FEATURE_SCHEMA_ID

        with tempfile.NamedTemporaryFile(suffix=".npz", delete=False) as tmp:
            tmp_path = Path(tmp.name)

        try:
            config_payload = {
                "architecture": "omnitransfer_geometric_alignment_v9",
                "assignment_head": "partial_assignment",
                "association_dim": 64,
                "association_layers": 3,
                "candidate_policy": "all_nodes",
                "dropout": 0.05,
                "hidden_dim": 64,
                "max_tokens": 48,
                "num_heads": 4,
                "relation_hidden_dim": 24,
                "source_context_nodes": 48,
                "target_context_nodes": 64,
                "text_encoder": "learned_token_lookup",
                "token_dim": 48,
                "visual_canvas_size": 384,
                "visual_encoder": "deterministic_icon_v1",
                "visual_patch_size": 32,
                "vocab_size": 8192,
                "feature_schema_id": GEOMETRIC_FEATURE_SCHEMA_ID,
            }
            arrays = {
                "__schema_version__": np.array(
                    list("omnitransfer_numpy_unified_association_v1".encode("utf-8")),
                    dtype=np.uint8,
                ),
                "__config_json__": np.array(
                    list(json.dumps(config_payload).encode("utf-8")),
                    dtype=np.uint8,
                ),
                "xml_projection.0.weight": np.ones((32,), dtype=np.float32),
                "xml_projection.0.bias": np.zeros((32,), dtype=np.float32),
            }
            np.savez(tmp_path, **arrays)

            with self.assertRaises(ValueError) as ctx:
                matcher_cls.from_checkpoint(tmp_path)
            err_msg = str(ctx.exception)
            self.assertTrue(
                "dimension" in err_msg.lower() or "mismatch" in err_msg.lower() or "38" in err_msg or "32" in err_msg,
                f"Expected error message mentioning dimension mismatch, got: {err_msg}",
            )
        finally:
            tmp_path.unlink(missing_ok=True)

    def test_execution_default_transfer_click_with_real_npz(self) -> None:
        """真实调用 execution.default_transfer，验证端到端成功映射 click (300.0, 300.0)。

        全链路真实调用，不 mock predict，不手工修改 matcher 内部属性。
        """
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.execution import default_transfer

        source_xml = (
            '<hierarchy bounds="[0,0][1000,1000]">'
            '<node bounds="[100,100][500,500]" text="OK" class="android.widget.Button" />'
            '</hierarchy>'
        )
        target_xml = (
            '<hierarchy bounds="[0,0][1000,1000]">'
            '<node bounds="[150,150][550,550]" text="OK" class="android.widget.Button" />'
            '</hierarchy>'
        )

        obs_source = Observation(
            xml=source_xml,
            package_name="cn.com.omnimind.androidgui.test",
            activity_name="MainActivity",
        )
        obs_target = Observation(
            xml=target_xml,
            package_name="cn.com.omnimind.androidgui.test",
            activity_name="MainActivity",
        )

        action = Action("click", {"x": 250, "y": 250})
        result = default_transfer(action, obs_target, obs_source)

        self.assertIsNotNone(
            result.action,
            f"Transfer mapping failed with reason: {result.reason}, detail: {result.detail}",
        )
        self.assertEqual(result.action.tool, "click")
        self.assertAlmostEqual(float(result.action.args["x"]), 300.0, places=1)
        self.assertAlmostEqual(float(result.action.args["y"]), 300.0, places=1)
        self.assertIn("omnitransfer", str(result.reason))

    def test_execution_transfer_swipe_with_real_npz(self) -> None:
        """真实调用 execution._transfer_swipe，验证端到端成功映射滑动两端点 (300.0, 300.0) -> (300.0, 500.0)。"""
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.execution import _transfer_swipe

        source_xml = (
            '<hierarchy bounds="[0,0][1000,1000]">'
            '<node bounds="[100,100][500,500]" text="StartBtn" class="android.widget.Button" />'
            '<node bounds="[100,400][500,600]" text="EndBtn" class="android.widget.Button" />'
            '</hierarchy>'
        )
        target_xml = (
            '<hierarchy bounds="[0,0][1000,1000]">'
            '<node bounds="[150,150][550,550]" text="StartBtn" class="android.widget.Button" />'
            '<node bounds="[150,450][550,650]" text="EndBtn" class="android.widget.Button" />'
            '</hierarchy>'
        )

        obs_source = Observation(
            xml=source_xml,
            package_name="cn.com.omnimind.androidgui.test",
            activity_name="MainActivity",
        )
        obs_target = Observation(
            xml=target_xml,
            package_name="cn.com.omnimind.androidgui.test",
            activity_name="MainActivity",
        )

        action = Action("swipe", {"x1": 250, "y1": 250, "x2": 250, "y2": 450})
        result = _transfer_swipe(action, obs_target, obs_source)

        self.assertIsNotNone(
            result.action,
            f"Swipe transfer failed with reason: {result.reason}, detail: {result.detail}",
        )
        self.assertEqual(result.action.tool, "swipe")
        self.assertAlmostEqual(float(result.action.args["x1"]), 300.0, places=1)
        self.assertAlmostEqual(float(result.action.args["y1"]), 300.0, places=1)
        self.assertAlmostEqual(float(result.action.args["x2"]), 300.0, places=1)
        self.assertAlmostEqual(float(result.action.args["y2"]), 500.0, places=1)
        self.assertIn("omnitransfer", str(result.reason))

    def test_caller_level_package_mismatch_guard(self) -> None:
        """验证应用身份由 caller 层 (execute_function) 校验拦截并记录 package_mismatch。"""
        from omniflow.core.config import PluginSet
        from omniflow.core.model import (
            Action,
            Function,
            FunctionStep,
            Observation,
            StepResult,
        )
        from omniflow.runtime.execution import execute_function

        class FakePreflightHost:
            async def observe(self, **kwargs):
                return obs_mismatch

            async def act(self, action, **kwargs):
                # open_app 返回失败，触发 preflight 拦截返回 RunResult(False)
                return StepResult(
                    False,
                    action=action,
                    before=obs_mismatch,
                    error="package_preflight_rejected",
                )

        obs_source = Observation(
            xml='<hierarchy bounds="[0,0][1000,1000]" />',
            package_name="cn.com.omnimind.androidgui.test",
        )
        obs_mismatch = Observation(
            xml='<hierarchy bounds="[0,0][1000,1000]" />',
            package_name="com.android.settings",
        )

        func = Function(
            function_id="test_func_pkg",
            name="Test Function",
            description="",
            steps=(
                FunctionStep(
                    step_index=0,
                    action=Action("click", {"x": 250, "y": 250}),
                    source_state_id="state_0",
                ),
            ),
        )

        async def _run() -> None:
            res = await execute_function(
                func,
                host=FakePreflightHost(),
                plugins=PluginSet(),
                observation=obs_mismatch,
                state_loader=lambda sid: obs_source,
            )
            self.assertFalse(res.success)
            decisions = res.detail.get("checker_decisions") or []
            self.assertGreaterEqual(len(decisions), 1)
            first_decision = decisions[0]
            self.assertEqual(first_decision.get("checker_kind"), "global_package_preflight")
            self.assertEqual(first_decision.get("reason"), "package_mismatch")
            self.assertEqual(first_decision.get("target_package"), "cn.com.omnimind.androidgui.test")
            self.assertEqual(first_decision.get("observed_package"), "com.android.settings")

        asyncio.run(_run())
