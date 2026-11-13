from __future__ import annotations

import asyncio
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import MagicMock
import zipfile


class ScreenPerceptionBehaviorTest(unittest.TestCase):
    temp_dir: Path | None = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="omniflow_perception_test_"))
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

        # 覆写本仓库中最新维护的 runtime-source Python 文件
        source_root = repo_root / "plugins" / "omni-vlm-lite" / "runtime-source" / "omniflow"
        for py_file in source_root.rglob("*.py"):
            rel = py_file.relative_to(source_root)
            target = cls.temp_dir / "omniflow" / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(py_file.read_bytes())

        # 与正式组包一致，规范动作契约随源码一并叠加两份规范 Schema。
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
        if cls.temp_dir is not None:
            temp_str = str(cls.temp_dir)
            while temp_str in sys.path:
                sys.path.remove(temp_str)
            for mod in list(sys.modules.keys()):
                if mod.startswith("omniflow"):
                    del sys.modules[mod]
            if cls.temp_dir.is_dir():
                try:
                    shutil.rmtree(cls.temp_dir, ignore_errors=True)
                except Exception:
                    pass

    def test_goal_relevance_ranks_item_30_into_summary(self) -> None:
        """目标相关节点选择：排在第30位的相关节点仍应优先排入前24个摘要中"""
        from omniflow.vlm.context import analyze_page_context

        xml_parts = ['<hierarchy rotation="0">']
        for i in range(1, 35):
            txt = "Settings Option" if i == 30 else f"Button {i}"
            xml_parts.append(
                f'<node class="android.widget.Button" text="{txt}" clickable="true" '
                f'bounds="[{i*10},{i*10}][{i*10+50},{i*10+50}]" id="btn_{i}" />'
            )
        xml_parts.append("</hierarchy>")
        state = {"xml": "".join(xml_parts), "display": {"width": 1080, "height": 2400}}

        # 无目标时按顺序截断，第30项不会出现在前24项中
        ctx_no_goal = analyze_page_context(state, goal="", limit=24)
        self.assertNotIn("Settings Option", ctx_no_goal.evidence)

        # 设定目标后，相关性最高的第30项被优先置顶在第一位
        ctx_goal = analyze_page_context(state, goal="Open Settings", limit=24)
        self.assertIn("Settings Option", ctx_goal.evidence)

    def test_independent_child_and_focusable_not_swallowed(self) -> None:
        """父标签聚合跳过隐藏/禁用子节点，不吞独立 clickable/focusable 控件"""
        from omniflow.vlm.context import analyze_page_context

        xml_card = """
        <hierarchy rotation="0">
            <node class="android.widget.LinearLayout" clickable="true" bounds="[0,0][500,300]" id="card1">
                <node class="android.widget.TextView" text="Card Title" bounds="[10,10][300,50]" />
                <node class="android.widget.TextView" text="Old Hidden Text" visible="false" bounds="[10,60][300,100]" />
                <node class="android.widget.TextView" text="Disabled Notice" enabled="false" bounds="[10,110][300,150]" />
                <node class="android.widget.ImageButton" clickable="true" content-desc="Delete" bounds="[400,50][480,150]" id="btn_delete" />
                <node class="android.view.View" focusable="true" clickable="false" content-desc="FocusableItem" bounds="[10,200][200,280]" id="item_focus" />
            </node>
        </hierarchy>
        """
        state = {"xml": xml_card, "display": {"width": 500, "height": 1000}}
        ctx = analyze_page_context(state, limit=24)
        # 1. 独立 clickable 子控件保留独立性
        self.assertIn("btn_delete", ctx.evidence)
        self.assertIn('desc="Delete"', ctx.evidence)
        # 2. 独立 focusable 子控件同样不被吞掉
        self.assertIn("item_focus", ctx.evidence)
        self.assertIn('desc="FocusableItem"', ctx.evidence)
        # 3. 聚合仅吸收可见且启用的纯展示文字，不吸收隐藏与已禁用的文本
        self.assertIn("Card Title", ctx.evidence)
        self.assertNotIn("Old Hidden Text", ctx.evidence)
        self.assertNotIn("Disabled Notice", ctx.evidence)

    def test_duplicate_name_disambiguation_rejected_without_fallback(self) -> None:
        """语义target多匹配时必须blocked，不回退坐标盲猜（同名消歧拒绝）"""
        from omniflow.core.config import PluginSet
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.execution import execute_robust_action
        from omniflow.runtime.semantic_grounding import resolve_semantic_action

        xml_dup = """
        <hierarchy rotation="0">
            <node class="android.widget.Button" text="Confirm" clickable="true" bounds="[100,100][300,150]" id="btn_1" />
            <node class="android.widget.Button" text="Confirm" clickable="true" bounds="[100,200][300,250]" id="btn_2" />
        </hierarchy>
        """
        obs = Observation.from_value(
            {
                "state_id": "state_dup",
                "xml": xml_dup,
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )
        action = Action("click", {"target_description": "Confirm", "x": 500, "y": 500})
        res = resolve_semantic_action(action, obs)
        self.assertEqual(res.detail["status"], "blocked")
        self.assertEqual(res.detail["reason"], "target_ambiguous")
        self.assertEqual(res.detail["candidate_count"], 2)

        # 在执行器中验证直接 blocked，绝不执行宿主分发或坐标回退
        host = MagicMock()
        step = asyncio.run(
            execute_robust_action(
                action,
                observation=obs,
                host=host,
                plugins=PluginSet(),
            )
        )
        self.assertFalse(step.success)
        self.assertEqual(step.origin, "blocked")
        self.assertEqual(step.error, "target_ambiguous")
        self.assertEqual(host.act.call_count, 0)

    def test_empty_xml_with_ocr_and_input_text_guard(self) -> None:
        """空树或无匹配时回退OCR：click与long_press可用，但input_text禁止将OCR文字作为输入框"""
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.semantic_grounding import resolve_semantic_action

        obs_ocr = Observation.from_value(
            {
                "state_id": "state_ocr",
                "xml": "",
                "display": {"width": 1000, "height": 1000},
                "extra": {
                    "display": {"width": 1000, "height": 1000},
                    "ocr": {
                        "status": "ready",
                        "items": [
                            {
                                "text": "Submit Form",
                                "bounds": [200, 400, 600, 500],
                                "confidence": 0.95,
                                "source": "ocr",
                            }
                        ],
                    },
                },
            }
        )

        # 1. click 正常解析 OCR 坐标，不虚构控件属性
        res_click = resolve_semantic_action(
            Action("click", {"target_description": "Submit Form"}), obs_ocr
        )
        self.assertEqual(res_click.detail["status"], "resolved")
        self.assertEqual(res_click.detail["match_source"], "ocr")
        self.assertEqual(res_click.action.args["x"], 400.0)
        self.assertEqual(res_click.action.args["y"], 450.0)
        self.assertNotIn("node_id", res_click.action.args)
        self.assertNotIn("node_resource_id", res_click.action.args)

        # 2. long_press 也支持 OCR 坐标定位
        res_long = resolve_semantic_action(
            Action("long_press", {"target_description": "Submit Form"}), obs_ocr
        )
        self.assertEqual(res_long.detail["status"], "resolved")
        self.assertEqual(res_long.detail["match_source"], "ocr")
        self.assertEqual(res_long.action.args["x"], 400.0)

        # 3. input_text 不能将 OCR 识别文字当作输入框，必须明确 blocked
        res_input = resolve_semantic_action(
            Action("input_text", {"target_description": "Submit Form", "text": "hello"}),
            obs_ocr,
        )
        self.assertEqual(res_input.detail["status"], "blocked")
        self.assertEqual(res_input.detail["reason"], "ocr_input_text_unsupported")

    def test_ocr_validation_guards(self) -> None:
        """OCR数据强校验：status!=ready不使用、非法NaN/bool/越界坐标不使用、同名多框不猜"""
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.semantic_grounding import resolve_semantic_action

        # 1. status != ready
        obs_not_ready = Observation.from_value(
            {
                "state_id": "state_not_ready",
                "xml": "",
                "display": {"width": 1000, "height": 1000},
                "extra": {
                    "display": {"width": 1000, "height": 1000},
                    "ocr": {
                        "status": "unavailable",
                        "items": [
                            {
                                "text": "Login",
                                "bounds": [100, 100, 200, 200],
                                "confidence": 0.9,
                                "source": "ocr",
                            }
                        ],
                    },
                },
            }
        )
        res1 = resolve_semantic_action(
            Action("click", {"target_description": "Login"}), obs_not_ready
        )
        self.assertEqual(res1.detail["status"], "blocked")
        self.assertEqual(res1.detail["reason"], "target_missing_no_coordinates")

        # 2. NaN / bool / 非法坐标
        obs_nan_bounds = Observation.from_value(
            {
                "state_id": "state_nan",
                "xml": "",
                "display": {"width": 1000, "height": 1000},
                "extra": {
                    "display": {"width": 1000, "height": 1000},
                    "ocr": {
                        "status": "ready",
                        "items": [
                            {
                                "text": "Login",
                                "bounds": [True, 100.0, float("nan"), 200.0],
                                "confidence": 0.9,
                                "source": "ocr",
                            }
                        ],
                    },
                },
            }
        )
        res2 = resolve_semantic_action(
            Action("click", {"target_description": "Login"}), obs_nan_bounds
        )
        self.assertEqual(res2.detail["status"], "blocked")

        # 3. 同名多框不猜
        obs_multi = Observation.from_value(
            {
                "state_id": "state_multi",
                "xml": "",
                "display": {"width": 1000, "height": 1000},
                "extra": {
                    "display": {"width": 1000, "height": 1000},
                    "ocr": {
                        "status": "ready",
                        "items": [
                            {"text": "Retry", "bounds": [100, 100, 200, 150], "confidence": 0.9, "source": "ocr"},
                            {"text": "Retry", "bounds": [100, 300, 200, 350], "confidence": 0.9, "source": "ocr"},
                        ],
                    },
                },
            }
        )
        res3 = resolve_semantic_action(
            Action("click", {"target_description": "Retry"}), obs_multi
        )
        self.assertEqual(res3.detail["status"], "blocked")
        self.assertEqual(res3.detail["reason"], "ocr_target_ambiguous")

    def test_target_missing_allows_explicit_coords_with_hint(self) -> None:
        """target_missing：若模型提供明确坐标仍允许，但提示可inspect OCR；无坐标则拒绝"""
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.semantic_grounding import resolve_semantic_action

        obs = Observation.from_value(
            {
                "state_id": "state_empty",
                "xml": '<hierarchy rotation="0"><node class="android.view.View" bounds="[0,0][100,100]" /></hierarchy>',
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )
        res = resolve_semantic_action(
            Action("click", {"target_description": "Missing", "x": 300, "y": 400}), obs
        )
        self.assertEqual(res.detail["status"], "fallback")
        self.assertEqual(res.detail["reason"], "target_missing")
        self.assertEqual(res.detail["hint"], "target_missing_inspect_ocr_available")

        res_no_coords = resolve_semantic_action(
            Action("click", {"target_description": "Missing"}), obs
        )
        self.assertEqual(res_no_coords.detail["status"], "blocked")
        self.assertEqual(res_no_coords.detail["reason"], "target_missing_no_coordinates")

    def test_roi_and_get_state_handling(self) -> None:
        """ROI区域图像注入模型，get_state带ocr与区域校验转发至host.observe"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import build_model_turn_request

        # 1. build_model_turn_request 包含裁剪区域图
        state = {
            "image_base64": "ZnVsbF9zY3JlZW4=",
            "package_name": "com.test.app",
            "activity_name": "MainActivity",
            "display": {"width": 1080, "height": 2400},
            "extra": {
                "visual_regions": [
                    {
                        "bounds": [100, 200, 500, 600],
                        "image_base64": "Y3JvcF9pbWFnZQ==",
                    }
                ]
            },
        }
        req = build_model_turn_request(
            goal="Inspect fine print",
            model="test-model",
            state=state,
            turn_index=1,
        )
        content = req["messages"][1]["content"]
        images = [item for item in content if item.get("type") == "image_url"]
        self.assertEqual(len(images), 2)
        self.assertIn("Y3JvcF9pbWFnZQ==", images[1]["image_url"]["url"])

        # 2. engine._observe 转发 ocr 与 region 区域
        observe_calls = []

        class MockHost:
            state_counter = 0

            async def observe(self, **kwargs):
                observe_calls.append(kwargs)
                MockHost.state_counter += 1
                return {
                    "state_id": f"state_{MockHost.state_counter}",
                    "xml": '<hierarchy rotation="0"><node class="android.view.View" bounds="[0,0][100,100]" /></hierarchy>',
                    "display": {"width": 1000, "height": 1000},
                    "extra": {"observation_id": f"obs_{MockHost.state_counter}"},
                }

            async def installed_apps(self):
                return {}

            async def act(self, action):
                return {"success": True}

        host = MockHost()
        store_file = self.temp_dir / "store.json"
        engine = OmniFlow(store_file, host=host)

        asyncio.run(
            engine._observe(
                screenshot=True,
                ocr=True,
                region={"x1": 100.0, "y1": 200.0, "x2": 500.0, "y2": 600.0},
            )
        )
        self.assertTrue(len(observe_calls) > 0)
        last_call = observe_calls[-1]
        self.assertTrue(last_call.get("ocr"))
        self.assertEqual(
            last_call.get("region"),
            {"x1": 100.0, "y1": 200.0, "x2": 500.0, "y2": 600.0},
        )

    def test_planner_get_state_runtime_cycle_and_action_feedback(self) -> None:
        """真实 VLMPlanner(带 transport) 连续 >=3 次交互（get_state -> 2 -> 3 -> + -> finished），累积并保留所有已确证 committed pairs，模型按顺序推进至完结"""
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def fake_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            if turn_idx == 1:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_1_get_state",
                            "type": "function",
                            "function": {
                                "name": "get_state",
                                "arguments": json.dumps(
                                    {
                                        "reason": "Inspect formula before calculate",
                                        "ocr": True,
                                        "x1": 0.0,
                                        "y1": 0.0,
                                        "x2": 1000.0,
                                        "y2": 300.0,
                                    }
                                ),
                            },
                        }
                    ],
                    "usage": {"prompt_tokens": 4122, "completion_tokens": 105, "total_tokens": 4227},
                }
            elif turn_idx == 2:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_2_click_2",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps(
                                    {"target_description": "2", "x": 376.0, "y": 823.0}
                                ),
                            },
                        }
                    ],
                    "usage": {"prompt_tokens": 4246, "completion_tokens": 42, "total_tokens": 4288},
                }
            elif turn_idx == 3:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_3_click_3",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps(
                                    {"target_description": "3", "x": 624.0, "y": 823.0}
                                ),
                            },
                        }
                    ],
                    "usage": {"prompt_tokens": 4350, "completion_tokens": 42, "total_tokens": 4392},
                }
            elif turn_idx == 4:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_4_click_add",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps(
                                    {"target_description": "+", "x": 800.0, "y": 800.0}
                                ),
                            },
                        }
                    ],
                    "usage": {"prompt_tokens": 4450, "completion_tokens": 42, "total_tokens": 4492},
                }
            else:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_5_finished",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({"content": "42"}),
                            },
                        }
                    ],
                    "usage": {"prompt_tokens": 4550, "completion_tokens": 15, "total_tokens": 4565},
                }

        observe_calls = []

        class MockHost:
            count = 0

            async def observe(self, **kwargs):
                observe_calls.append(kwargs)
                MockHost.count += 1
                formula_text = "23" if MockHost.count >= 4 else "2" if MockHost.count == 3 else ""
                return {
                    "state_id": f"state_{MockHost.count}",
                    "xml": f'<hierarchy><node class="android.widget.EditText" id="com.google.android.calculator:id/formula" text="{formula_text}" bounds="[100,200][900,400]"/><node class="android.widget.Button" id="com.google.android.calculator:id/digit_2" text="2" bounds="[100,500][300,700]"/><node class="android.widget.Button" id="com.google.android.calculator:id/digit_3" text="3" bounds="[400,500][600,700]"/><node class="android.widget.Button" id="com.google.android.calculator:id/op_add" text="+" bounds="[700,500][900,700]"/></hierarchy>',
                    "package_name": "com.google.android.calculator",
                    "activity_name": "Calculator",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": f"obs_{MockHost.count}",
                        "display": {"width": 1080, "height": 2400},
                        "ocr": {
                            "status": "ready",
                            "items": [
                                {
                                    "text": formula_text or "0",
                                    "bounds": [100, 200, 900, 400],
                                    "confidence": 0.99,
                                    "source": "ocr",
                                }
                            ],
                        }
                        if kwargs.get("ocr")
                        else None,
                        "visual_regions": [
                            {
                                "bounds": [0.0, 0.0, 1000.0, 300.0],
                                "image_base64": "Y3JvcA==",
                            }
                        ]
                        if kwargs.get("region")
                        else None,
                    },
                }

            async def installed_apps(self):
                return {"计算器": "com.google.android.calculator"}

            async def act(self, action):
                return {"success": True}

        host = MockHost()
        planner = VLMPlanner(
            model="scene.vlm.operation.primary", transport=fake_transport
        )
        store_file = self.temp_dir / "store.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"计算器": "com.google.android.calculator"},
        )

        result = asyncio.run(
            engine.arun("计算 23+19，并视觉验证结果等于 42")
        )

        # 1. 任务顺利完成，未发生20次无限循环耗尽
        self.assertTrue(result.success)
        self.assertIsNone(result.error)
        self.assertEqual(len(transport_envelopes), 5)

        # 2. get_state 只读操作不计入 actions_executed，不录入 trace；仅 3 次点击操作计入
        self.assertEqual(result.actions_executed, 3)
        self.assertEqual(len(result.detail["trace"]), 3)
        self.assertEqual(
            [s["action"]["tool"] for s in result.detail["trace"]],
            ["click", "click", "click"],
        )

        # 3. 第 4 轮请求（连续 3 次交互后）必须完整看到所有已 committed 的历史 pairs
        turn_4_req = transport_envelopes[3]["request"]
        turn_4_msgs = turn_4_req["messages"]
        # system(0) 与 user(1) 开首，后续历史 (assistant, tool) 对，且各轮用户文字观测保留
        self.assertEqual(turn_4_msgs[0]["role"], "system")
        self.assertEqual(turn_4_msgs[1]["role"], "user")

        # 第 1 对：get_state
        tool_1 = next(m for m in turn_4_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_1_get_state")
        get_state_res = json.loads(tool_1["content"])
        self.assertTrue(get_state_res["success"])
        self.assertEqual(get_state_res["tool"], "get_state")

        # 第 2 对：click 2
        tool_2 = next(m for m in turn_4_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_2_click_2")
        click_2_res = json.loads(tool_2["content"])
        self.assertTrue(click_2_res["success"])
        self.assertEqual(click_2_res["tool"], "click")

        # 第 3 对：click 3
        tool_3 = next(m for m in turn_4_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_3_click_3")
        click_3_res = json.loads(tool_3["content"])
        self.assertTrue(click_3_res["success"])
        self.assertEqual(click_3_res["tool"], "click")

        # 最新快照仅作为最后的 user 消息呈现，不累积旧图
        self.assertEqual(turn_4_msgs[-1]["role"], "user")

    def test_default_summary_retains_formula_and_tail_operator_labels(self) -> None:
        """真实计算器内嵌夹具：默认 32 预算下 focused formula 23 以及排在后部的加/等于号叶子控件均正常保留在摘要内，无标签容器不挤占预算"""
        from omniflow.vlm.context import analyze_page_context

        # 内嵌全套自包含测试夹具（完全独立，不依赖外部 C:/Temp 路径）
        xml_calc_fixture = """
        <hierarchy rotation="0">
          <node class="android.widget.FrameLayout" id="com.google.android.calculator:id/main_calculator" bounds="[0,0][1080,2400]">
            <node class="android.widget.LinearLayout" id="com.google.android.calculator:id/history_layout" bounds="[0,100][1080,500]">
              <node class="androidx.recyclerview.widget.RecyclerView" id="com.google.android.calculator:id/history_recycler_view" scrollable="true" bounds="[0,100][1080,500]" />
            </node>
            <node class="android.widget.HorizontalScrollView" id="com.google.android.calculator:id/formula_scroll_view" scrollable="true" bounds="[0,500][1080,800]">
              <node class="android.widget.EditText" id="com.google.android.calculator:id/formula" text="23" focused="true" bounds="[100,550][980,750]" />
            </node>
            <node class="android.widget.GridLayout" bounds="[0,800][1080,2400]">
              <node class="android.widget.ImageButton" desc="全部清除" id="com.google.android.calculator:id/clr" clickable="true" bounds="[10,415][248,528]" />
              <node class="android.widget.ImageButton" desc="除" id="com.google.android.calculator:id/op_div" clickable="true" bounds="[752,415][990,528]" />
              <node class="android.widget.ImageButton" desc="7" id="com.google.android.calculator:id/digit_7" clickable="true" bounds="[10,532][248,645]" />
              <node class="android.widget.ImageButton" desc="8" id="com.google.android.calculator:id/digit_8" clickable="true" bounds="[257,532][495,645]" />
              <node class="android.widget.ImageButton" desc="9" id="com.google.android.calculator:id/digit_9" clickable="true" bounds="[505,532][743,645]" />
              <node class="android.widget.ImageButton" desc="乘" id="com.google.android.calculator:id/op_mul" clickable="true" bounds="[752,532][990,645]" />
              <node class="android.widget.ImageButton" desc="4" id="com.google.android.calculator:id/digit_4" clickable="true" bounds="[10,650][248,763]" />
              <node class="android.widget.ImageButton" desc="5" id="com.google.android.calculator:id/digit_5" clickable="true" bounds="[257,650][495,763]" />
              <node class="android.widget.ImageButton" desc="6" id="com.google.android.calculator:id/digit_6" clickable="true" bounds="[505,650][743,763]" />
              <node class="android.widget.ImageButton" desc="减" id="com.google.android.calculator:id/op_sub" clickable="true" bounds="[752,650][990,763]" />
              <node class="android.widget.ImageButton" desc="1" id="com.google.android.calculator:id/digit_1" clickable="true" bounds="[10,767][248,880]" />
              <node class="android.widget.ImageButton" desc="2" id="com.google.android.calculator:id/digit_2" clickable="true" bounds="[257,767][495,880]" />
              <node class="android.widget.ImageButton" desc="3" id="com.google.android.calculator:id/digit_3" clickable="true" bounds="[505,767][743,880]" />
              <node class="android.widget.ImageButton" desc="加" id="com.google.android.calculator:id/op_add" clickable="true" bounds="[752,767][990,880]" />
              <node class="android.widget.ImageButton" desc="0" id="com.google.android.calculator:id/digit_0" clickable="true" bounds="[10,885][248,998]" />
              <node class="android.widget.ImageButton" desc="点号" id="com.google.android.calculator:id/dec_point" clickable="true" bounds="[257,885][495,998]" />
              <node class="android.widget.ImageButton" desc="删除" id="com.google.android.calculator:id/del" clickable="true" bounds="[505,885][743,998]" />
              <node class="android.widget.ImageButton" desc="等于" id="com.google.android.calculator:id/eq" clickable="true" bounds="[752,885][990,998]" />
            </node>
          </node>
        </hierarchy>
        """
        state = {"xml": xml_calc_fixture, "display": {"width": 1080, "height": 2400}}
        ctx = analyze_page_context(
            state,
            goal="计算 23+19，并视觉验证结果等于 42",
        )
        # 1. 验证 focused formula 23 保留在摘要中
        self.assertIn('text="23"', ctx.evidence)
        self.assertIn("formula", ctx.evidence)

        # 2. 验证排在后部的加/等于号叶子控件未被截断，均正常保留在摘要内
        self.assertIn("op_add", ctx.evidence)
        self.assertIn("eq", ctx.evidence)

    def test_planner_same_name_different_args_fails_closed(self) -> None:
        """同名但不同参数的动作绝不盲目归因 ack：canonical 参数不匹配时 fail closed 拒绝，直接终止"""
        import asyncio
        import json
        from omniflow.core.model import Observation
        from omniflow.vlm.planner import VLMPlanner

        transport_requests = []

        def test_transport(envelope):
            transport_requests.append(envelope["request"])
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": "call_inspect_1",
                        "type": "function",
                        "function": {
                            "name": "get_state",
                            "arguments": json.dumps({"reason": "reason_A"}),
                        },
                    }
                ],
            }

        planner = VLMPlanner(model="test-model", transport=test_transport)

        # 第 1 轮：模型输出 get_state(reason="reason_A")
        obs_1 = Observation(
            package_name="com.test",
            extra={"display": {"width": 1000, "height": 1000}},
        )
        asyncio.run(planner.one_step_tool_call("test goal", obs_1))

        # 第 2 轮：宿主环境记录的 previous_action 参数为 reason_B（同为 get_state，但参数不同）
        obs_2 = Observation(
            package_name="com.test",
            extra={
                "display": {"width": 1000, "height": 1000},
                "previous_action": {
                    "tool": "get_state",
                    "args": {"reason": "reason_B"},
                },
                "previous_action_result": "succeeded",
            },
        )
        # fail-closed: 无法确证上一轮执行结果，必须抛出 RuntimeError 直接终止，绝不发送第二轮请求
        with self.assertRaises(RuntimeError) as cm:
            asyncio.run(planner.one_step_tool_call("test goal", obs_2))
        self.assertIn("action_execution_unconfirmed", str(cm.exception))
        self.assertEqual(len(transport_requests), 1)

    def _create_mock_host(
        self,
        *,
        xml_getter=None,
        default_xml: str = '<hierarchy><node class="android.view.View" bounds="[0,0][100,100]"/></hierarchy>',
        act_impl=None,
        extra_builder=None,
    ):
        class MockHost:
            def __init__(self):
                self.act_calls = []
                self.count = 0

            async def observe(self, **kwargs):
                self.count += 1
                xml = xml_getter(self.count, kwargs) if xml_getter else default_xml
                extra = {
                    "observation_id": f"obs_{self.count}",
                    "display": {"width": 1080, "height": 2400},
                }
                if extra_builder:
                    extra.update(extra_builder(kwargs))
                return {
                    "state_id": f"state_{self.count}",
                    "xml": xml,
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": extra,
                }

            async def installed_apps(self):
                return {"App": "com.test.app"}

            async def act(self, action):
                self.act_calls.append(action)
                if act_impl:
                    return await act_impl(action)
                return {"success": True}

        return MockHost()

    def _run_perception_engine(self, host, transport, goal="测试", name="test"):
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=transport)
        store_file = self.temp_dir / f"store_{name}.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"App": "com.test.app"},
        )
        return asyncio.run(engine.arun(goal))

    def test_multicall_single_admission_and_tail_unexecuted_feedback(self) -> None:
        """多原生调用单动作准入：provider 一次返回 click A/B 时，host 只执行 A；宿主随 A 产生新观测状态，第 2 轮模型观察到新状态后选择 C（非 B 旧坐标），执行 C 后完结，断言真实动作序列 A,C 且 B 旧坐标从不执行"""
        import json

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            if turn_idx == 1:
                # 初始屏幕看到 A 和 B，模型返回多调用 click A 和 click B
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_click_a",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps({"target_description": "BtnA", "x": 100.0, "y": 200.0}),
                            },
                        },
                        {
                            "id": "call_click_b",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps({"target_description": "BtnB", "x": 888.0, "y": 999.0}),
                            },
                        },
                    ],
                }
            elif turn_idx == 2:
                # 看到执行 A 之后的新屏幕（出现按钮 C），模型选择点击 C（而不是使用 B 旧坐标）
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_click_c",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps({"target_description": "BtnC", "x": 500.0, "y": 600.0}),
                            },
                        }
                    ],
                }
            else:
                # 看到执行 C 后的最终屏幕，模型完结
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_finished",
                            "type": "function",
                            "function": {"name": "finished", "arguments": json.dumps({"content": "all completed"})},
                        }
                    ],
                }

        def screen_xml_by_turn(observe_count, kwargs):
            if observe_count == 1:
                return '<hierarchy><node class="android.widget.Button" id="btn_a" text="A" bounds="[100,200][300,400]"/><node class="android.widget.Button" id="btn_b" text="B" bounds="[800,900][950,1050]"/></hierarchy>'
            elif observe_count == 2:
                return '<hierarchy><node class="android.widget.Button" id="btn_c" text="C" bounds="[400,500][600,700]"/></hierarchy>'
            else:
                return '<hierarchy><node class="android.widget.TextView" text="Done" bounds="[0,0][100,100]"/></hierarchy>'

        host = self._create_mock_host(xml_getter=screen_xml_by_turn)
        result = self._run_perception_engine(host, test_transport, "点击序列任务", "admission_multi")

        self.assertTrue(result.success)
        self.assertEqual(len(transport_envelopes), 3)

        # 1. 真实动作序列严格为 [click A, click C]，actions_executed 为 2
        self.assertEqual(len(host.act_calls), 2)
        self.assertEqual(host.act_calls[0].tool, "click")
        self.assertEqual(host.act_calls[0].args["x"], 100.0)
        self.assertEqual(host.act_calls[0].args["y"], 200.0)

        self.assertEqual(host.act_calls[1].tool, "click")
        self.assertEqual(host.act_calls[1].args["x"], 500.0)
        self.assertEqual(host.act_calls[1].args["y"], 600.0)

        # 验证 B 旧坐标 (888.0, 999.0) 绝未在任何步骤执行
        all_executed_coords = [(c.args.get("x"), c.args.get("y")) for c in host.act_calls]
        self.assertNotIn((888.0, 999.0), all_executed_coords)
        self.assertEqual(result.actions_executed, 2)

        # 2. 验证第 2 轮请求中的 messages 结构与未执行反馈
        turn_2_req = transport_envelopes[1]["request"]
        turn_2_msgs = turn_2_req["messages"]
        self.assertEqual(turn_2_msgs[0]["role"], "system")
        self.assertEqual(turn_2_msgs[1]["role"], "user")

        # 单条 assistant 包含两个原生 tool_calls
        asst_msg = next(m for m in turn_2_msgs if m.get("role") == "assistant")
        calls = asst_msg.get("tool_calls", [])
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0]["id"], "call_click_a")
        self.assertEqual(calls[1]["id"], "call_click_b")

        # 首个 tool A 真实成功
        tool_a = next(m for m in turn_2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_click_a")
        res_a = json.loads(tool_a["content"])
        self.assertTrue(res_a["success"])
        self.assertEqual(res_a["tool"], "click")

        # 尾部 tool B 明确未执行错误
        tool_b = next(m for m in turn_2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_click_b")
        res_b = json.loads(tool_b["content"])
        self.assertFalse(res_b["success"])
        self.assertFalse(res_b["executed"])
        self.assertEqual(res_b["error"], "not_executed_observation_refresh_required")
        self.assertEqual(res_b["tool"], "click")

        # 结尾的 user 消息已是包含 C 的新屏幕状态
        self.assertEqual(turn_2_msgs[-1]["role"], "user")
        self.assertIn("btn_c", str(turn_2_msgs[-1]["content"]))

    def test_multicall_first_action_failed_tail_not_executed_and_reports_real_error(self) -> None:
        """首动作执行失败时：绝不执行尾部调用，下一轮如实反馈首动作失败与尾部未执行错误"""
        import json

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            if len(transport_envelopes) == 1:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_fail_a",
                            "type": "function",
                            "function": {"name": "click", "arguments": json.dumps({"target_description": "BtnFail", "x": 111.0, "y": 222.0})},
                        },
                        {
                            "id": "call_tail_b",
                            "type": "function",
                            "function": {"name": "click", "arguments": json.dumps({"target_description": "BtnTail", "x": 333.0, "y": 444.0})},
                        },
                    ],
                }
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": "call_finish_after_fail",
                        "type": "function",
                        "function": {"name": "finished", "arguments": json.dumps({"content": "observed failure"})},
                    }
                ],
            }

        async def fail_act(action):
            return {"success": False, "error": "element_click_intercepted"}

        host = self._create_mock_host(act_impl=fail_act)
        result = self._run_perception_engine(host, test_transport, "尝试点击", "first_fail")

        self.assertTrue(result.success)
        self.assertEqual(len(transport_envelopes), 2)

        # 仅分发了首动作，尾部调用未分发
        self.assertEqual(len(host.act_calls), 1)
        self.assertEqual(host.act_calls[0].args["x"], 111.0)

        # 第 2 轮请求必须如实反映首动作失败以及尾部未执行
        turn_2_msgs = transport_envelopes[1]["request"]["messages"]
        self.assertEqual(turn_2_msgs[0]["role"], "system")
        self.assertEqual(turn_2_msgs[1]["role"], "user")

        tool_a = next(m for m in turn_2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_fail_a")
        res_a = json.loads(tool_a["content"])
        self.assertFalse(res_a["success"])
        self.assertIn("element_click_intercepted", res_a.get("error", ""))

        tool_b = next(m for m in turn_2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_tail_b")
        res_b = json.loads(tool_b["content"])
        self.assertFalse(res_b["success"])
        self.assertFalse(res_b["executed"])
        self.assertEqual(res_b["error"], "not_executed_observation_refresh_required")

    def test_multicall_get_state_and_click_tail_never_dispatched(self) -> None:
        """多调用包含 get_state 与尾部 click：首个只读 get_state 刷新观测，尾部 click 绝不分发给 host.act"""
        import json

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            if len(transport_envelopes) == 1:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_get_state_1",
                            "type": "function",
                            "function": {"name": "get_state", "arguments": json.dumps({"reason": "check ocr", "ocr": True})},
                        },
                        {
                            "id": "call_click_tail_2",
                            "type": "function",
                            "function": {"name": "click", "arguments": json.dumps({"target_description": "Submit", "x": 500.0, "y": 600.0})},
                        },
                    ],
                }
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": "call_done",
                        "type": "function",
                        "function": {"name": "finished", "arguments": json.dumps({"content": "verified"})},
                    }
                ],
            }

        def build_ocr_extra(kwargs):
            if kwargs.get("ocr"):
                return {
                    "ocr": {
                        "status": "ready",
                        "items": [{"text": "Data", "bounds": [0, 0, 100, 100], "confidence": 1.0, "source": "ocr"}],
                    }
                }
            return {}

        host = self._create_mock_host(extra_builder=build_ocr_extra)
        result = self._run_perception_engine(host, test_transport, "感知并确认", "get_state_multi")

        self.assertTrue(result.success)
        self.assertEqual(result.actions_executed, 0)
        self.assertEqual(len(host.act_calls), 0)

        turn_2_msgs = transport_envelopes[1]["request"]["messages"]
        self.assertEqual(turn_2_msgs[0]["role"], "system")
        self.assertEqual(turn_2_msgs[1]["role"], "user")

        tool_state = next(m for m in turn_2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_get_state_1")
        res_state = json.loads(tool_state["content"])
        self.assertTrue(res_state["success"])
        self.assertEqual(res_state["tool"], "get_state")
        self.assertEqual(res_state["ocr"]["status"], "ready")

        tool_click = next(m for m in turn_2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_click_tail_2")
        res_click = json.loads(tool_click["content"])
        self.assertFalse(res_click["success"])
        self.assertFalse(res_click["executed"])
        self.assertEqual(res_click["error"], "not_executed_observation_refresh_required")

    def test_multicall_validation_rejections_no_dispatch(self) -> None:
        """响应校验失败时坚决拒绝并零分发：空 calls、缺失/空白 id、重复 id、非法首调用"""
        import json

        cases = [
            ({"requested_model": "scene.vlm.operation.primary", "tool_calls": []}, "model_turn_tool_calls_empty"),
            (
                {
                    "requested_model": "scene.vlm.operation.primary",
                    "tool_calls": [
                        {
                            "type": "function",
                            "function": {"name": "click", "arguments": json.dumps({"target_description": "A", "x": 100.0, "y": 100.0})},
                        }
                    ],
                },
                "model_turn_tool_call_id_missing",
            ),
            (
                {
                    "requested_model": "scene.vlm.operation.primary",
                    "tool_calls": [
                        {
                            "id": "dup_id",
                            "type": "function",
                            "function": {"name": "click", "arguments": json.dumps({"target_description": "A", "x": 100.0, "y": 100.0})},
                        },
                        {
                            "id": "dup_id",
                            "type": "function",
                            "function": {"name": "click", "arguments": json.dumps({"target_description": "B", "x": 200.0, "y": 200.0})},
                        },
                    ],
                },
                "model_turn_tool_call_id_duplicate",
            ),
            (
                {
                    "requested_model": "scene.vlm.operation.primary",
                    "tool_calls": [
                        {
                            "id": "call_invalid_tool",
                            "type": "function",
                            "function": {"name": "unsupported_shell_execute", "arguments": json.dumps({"cmd": "ls"})},
                        },
                        {
                            "id": "call_valid_tail",
                            "type": "function",
                            "function": {"name": "click", "arguments": json.dumps({"target_description": "B", "x": 200.0, "y": 200.0})},
                        },
                    ],
                },
                "model_turn_tool_not_visible",
            ),
        ]

        for payload, expected_error in cases:
            host = self._create_mock_host()
            result = self._run_perception_engine(host, lambda env, p=payload: p, "测试拒绝", f"reject_{expected_error}")
            self.assertFalse(result.success)
            self.assertIn(expected_error, str(result.error))
            self.assertEqual(len(host.act_calls), 0)

    def test_terminal_tool_multicall_boundaries(self) -> None:
        """终端工具多调用边界：首调用为 finished/abort 且 len>1 时拒绝且零分发；首 click 尾 finished 正常进入循环并在新观测后完成；单 finished 正常完结"""
        import json

        # 1. 首调用为 finished 且存在尾部调用：拒绝 model_turn_terminal_tool_must_be_alone，零分发
        host_1 = self._create_mock_host()
        res_1 = self._run_perception_engine(
            host_1,
            lambda env: {
                "requested_model": env["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {"id": "call_fin", "type": "function", "function": {"name": "finished", "arguments": json.dumps({"content": "done"})}},
                    {"id": "call_clk", "type": "function", "function": {"name": "click", "arguments": json.dumps({"target_description": "X", "x": 10.0, "y": 20.0})}},
                ],
            },
            name="term_1",
        )
        self.assertFalse(res_1.success)
        self.assertIn("model_turn_terminal_tool_must_be_alone", str(res_1.error))
        self.assertEqual(len(host_1.act_calls), 0)

        # 2. 首调用为 abort 且存在尾部调用：拒绝且零分发
        host_2 = self._create_mock_host()
        res_2 = self._run_perception_engine(
            host_2,
            lambda env: {
                "requested_model": env["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {"id": "call_ab", "type": "function", "function": {"name": "abort", "arguments": json.dumps({"value": "failed"})}},
                    {"id": "call_clk", "type": "function", "function": {"name": "click", "arguments": json.dumps({"target_description": "X", "x": 10.0, "y": 20.0})}},
                ],
            },
            name="term_2",
        )
        self.assertFalse(res_2.success)
        self.assertIn("model_turn_terminal_tool_must_be_alone", str(res_2.error))
        self.assertEqual(len(host_2.act_calls), 0)

        # 3. 首 click 尾 finished：首动作 click 执行，尾部 finished 标为未执行，第 2 轮模型新观测下单独 finished 完结
        turn_requests_3 = []

        def transport_3(env):
            turn_requests_3.append(env["request"])
            if len(turn_requests_3) == 1:
                return {
                    "requested_model": env["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {"id": "call_clk_first", "type": "function", "function": {"name": "click", "arguments": json.dumps({"target_description": "Btn", "x": 50.0, "y": 60.0})}},
                        {"id": "call_fin_tail", "type": "function", "function": {"name": "finished", "arguments": json.dumps({"content": "too early"})}},
                    ],
                }
            return {
                "requested_model": env["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {"id": "call_fin_final", "type": "function", "function": {"name": "finished", "arguments": json.dumps({"content": "all done"})}}
                ],
            }

        host_3 = self._create_mock_host()
        res_3 = self._run_perception_engine(host_3, transport_3, name="term_3")
        self.assertTrue(res_3.success)
        self.assertEqual(len(host_3.act_calls), 1)
        self.assertEqual(host_3.act_calls[0].args["x"], 50.0)
        turn_2_msgs = turn_requests_3[1]["messages"]
        tail_msg = next(m for m in turn_2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_fin_tail")
        tail_res = json.loads(tail_msg["content"])
        self.assertFalse(tail_res["success"])
        self.assertFalse(tail_res["executed"])
        self.assertEqual(tail_res["error"], "not_executed_observation_refresh_required")

        # 4. 单独 finished (len==1)：正常完结
        host_4 = self._create_mock_host()
        res_4 = self._run_perception_engine(
            host_4,
            lambda env: {
                "requested_model": env["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {"id": "call_fin_single", "type": "function", "function": {"name": "finished", "arguments": json.dumps({"content": "success"})}}
                ],
            },
            name="term_4",
        )
        self.assertTrue(res_4.success)

    def test_planner_function_different_args_fails_closed(self) -> None:
        """Function 动作确证：参数不同时绝不能借由旧证据盲目 ack，必须 fail-closed 抛异常终止"""
        import asyncio
        import json
        from omniflow.core.model import Function, Observation
        from omniflow.vlm.planner import VLMPlanner

        func = Function(
            function_id="search_item",
            name="search_item",
            description="搜索商品",
            steps=(),
            input_schema={
                "type": "object",
                "properties": {"keyword": {"type": "string"}},
                "required": ["keyword"],
            },
        )

        transport_requests = []

        def test_transport(envelope):
            transport_requests.append(envelope["request"])
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": "call_fn_1",
                        "type": "function",
                        "function": {
                            "name": "search_item",
                            "arguments": json.dumps({"keyword": "book"}),
                        },
                    }
                ],
            }

        planner = VLMPlanner(model="test-model", transport=test_transport)

        # 第 1 轮：模型输出 search_item(keyword="book")
        obs_1 = Observation(
            package_name="com.test",
            extra={"display": {"width": 1000, "height": 1000}},
        )
        call = asyncio.run(planner.one_step_tool_call("test goal", obs_1, functions=(func,)))
        self.assertEqual(call.name, "search_item")
        self.assertEqual(call.arguments["keyword"], "book")

        # 第 2 轮：observation 中返回的 previous_action 参数为 keyword="pen"（参数不匹配），
        # 且即使带有旧的 function_execution 成功标记，也绝不盲目 ack
        obs_2 = Observation(
            package_name="com.test",
            extra={
                "display": {"width": 1000, "height": 1000},
                "previous_action": {
                    "tool": "search_item",
                    "args": {"keyword": "pen"},
                },
                "previous_action_result": "succeeded",
                "function_execution": {
                    "function_id": "search_item",
                    "replay_status": "actions_succeeded",
                },
            },
        )
        with self.assertRaises(RuntimeError) as cm:
            asyncio.run(planner.one_step_tool_call("test goal", obs_2, functions=(func,)))
        self.assertIn("action_execution_unconfirmed", str(cm.exception))
        self.assertEqual(len(transport_requests), 1)
    def test_empty_background_decision_launches_installed_target(self) -> None:
        """空后台新决策启动已装目标：后台虚拟屏无前台应用与桌面，模型根据上下文与已安装候选主动调用 open_app 启动目标应用"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            if turn_idx == 1:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_open_1",
                            "type": "function",
                            "function": {
                                "name": "open_app",
                                "arguments": json.dumps({"package_name": "com.android.vending"}),
                            },
                        }
                    ],
                }
            else:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_fin_2",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({"content": "store_opened"}),
                            },
                        }
                    ],
                }

        class EmptyBackgroundHost:
            def __init__(self):
                self.act_calls = []
                self.obs_count = 0

            async def observe(self, **kwargs):
                self.obs_count += 1
                if self.obs_count == 1:
                    return {
                        "state_id": "state_empty_1",
                        "xml": "",
                        "package_name": "-",
                        "activity_name": "-",
                        "display": {"width": 1080, "height": 2400},
                        "extra": {"display": {"width": 1080, "height": 2400}},
                    }
                return {
                    "state_id": "state_store_2",
                    "xml": '<hierarchy><node class="android.widget.TextView" text="Google Play" bounds="[100,100][500,200]"/></hierarchy>',
                    "package_name": "com.android.vending",
                    "activity_name": "MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {"display": {"width": 1080, "height": 2400}},
                }

            async def installed_apps(self):
                return {"Google Play": "com.android.vending", "设置": "com.android.settings"}

            async def act(self, action):
                self.act_calls.append(action)
                return {"success": True}

        host = EmptyBackgroundHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_empty_bg.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"Google Play": "com.android.vending", "设置": "com.android.settings"},
        )
        result = asyncio.run(engine.arun("下载淘宝应用"))

        self.assertTrue(result.success)
        self.assertEqual(len(host.act_calls), 1)
        self.assertEqual(host.act_calls[0].tool, "open_app")
        self.assertEqual(host.act_calls[0].args.get("package_name"), "com.android.vending")
        self.assertEqual(result.actions_executed, 1)


    def test_identical_empty_read_gives_no_progress_feedback_and_bounded_termination(self) -> None:
        """相同空读无进展反馈和有界结束：连续重复空读获得 progress=False 与文字提示反馈，并在达到有界阈值时明确以 no_progress_detected 终止"""
        import asyncio
        import json
        from omniflow.bridge import _run_result
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": f"call_get_state_{turn_idx}",
                        "type": "function",
                        "function": {
                            "name": "get_state",
                            "arguments": json.dumps({"reason": f"wait_screen_{turn_idx}"}),
                        },
                    }
                ],
            }

        class StaticEmptyHost:
            async def observe(self, **kwargs):
                return {
                    "state_id": "state_empty_const",
                    "xml": "",
                    "package_name": "-",
                    "activity_name": "-",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {"display": {"width": 1080, "height": 2400}},
                }

            async def installed_apps(self):
                return {"应用商店": "com.android.vending"}

            async def act(self, action):
                return {"success": True}

        host = StaticEmptyHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_stalled_read.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"应用商店": "com.android.vending"},
        )
        result = asyncio.run(engine.arun("下载应用"))

        # 1. 验证有界终止：在第 2 次相同空读后终止，不无限消耗至 20 轮
        self.assertFalse(result.success)
        self.assertEqual(result.error, "no_progress_detected")
        self.assertEqual(result.detail.get("done_reason"), "no_progress_detected")
        self.assertEqual(len(transport_envelopes), 2)

        # 2. 验证第 2 轮中的无进展反馈：tool message 中 progress=False，以及 user 文本提示
        turn_2_msgs = transport_envelopes[1]["request"]["messages"]
        tool_msg = next(m for m in turn_2_msgs if m.get("role") == "tool")
        tool_res = json.loads(tool_msg["content"])
        self.assertTrue(tool_res["success"])
        self.assertFalse(tool_res["progress"])
        self.assertFalse(tool_res["screen_changed"])

        # 3. 验证经过 bridge 处理后的错误码透传具体原因而非 FUNCTION_CALL_FAILED
        bridge_res = _run_result(result, body={"run_id": "stalled_run_1"}, function=None)
        self.assertEqual(bridge_res["error_code"], "NO_PROGRESS_DETECTED")
        self.assertEqual(bridge_res["error_message"], "no_progress_detected")
        self.assertEqual(bridge_res["done_reason"], "no_progress_detected")

    def test_distinct_roi_and_ocr_reads_are_valid_and_not_stalled(self) -> None:
        """不同ROI/OCR合法读取：多轮请求不同 OCR 或 ROI 区域属于合法感知，不误判为无进展中断，可正常推进并完成"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            if turn_idx == 1:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_read_1",
                            "type": "function",
                            "function": {
                                "name": "get_state",
                                "arguments": json.dumps({"reason": "initial inspect"}),
                            },
                        }
                    ],
                }
            elif turn_idx == 2:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_read_2",
                            "type": "function",
                            "function": {
                                "name": "get_state",
                                "arguments": json.dumps({"reason": "request ocr", "ocr": True}),
                            },
                        }
                    ],
                }
            elif turn_idx == 3:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_read_3",
                            "type": "function",
                            "function": {
                                "name": "get_state",
                                "arguments": json.dumps({
                                    "reason": "request roi",
                                    "ocr": True,
                                    "x1": 0.0,
                                    "y1": 0.0,
                                    "x2": 500.0,
                                    "y2": 500.0,
                                }),
                            },
                        }
                    ],
                }
            else:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_read_4",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({"content": "verified"}),
                            },
                        }
                    ],
                }

        class StaticScreenWithPerceptionHost:
            async def observe(self, **kwargs):
                extra = {"display": {"width": 1080, "height": 2400}}
                if kwargs.get("ocr"):
                    extra["ocr"] = {"status": "ready", "items": [{"text": "Hello", "bounds": [10, 10, 100, 50]}]}
                if kwargs.get("region"):
                    extra["visual_regions"] = [{"bounds": [0.0, 0.0, 500.0, 500.0], "image_base64": "Y3JvcA=="}]
                return {
                    "state_id": "state_static_1",
                    "xml": '<hierarchy><node class="android.widget.TextView" text="Hello" bounds="[10,10][100,50]"/></hierarchy>',
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": extra,
                }

            async def installed_apps(self):
                return {"App": "com.test.app"}

            async def act(self, action):
                return {"success": True}

        host = StaticScreenWithPerceptionHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_distinct_reads.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"App": "com.test.app"},
        )
        result = asyncio.run(engine.arun("感知验证"))

        self.assertTrue(result.success)
        self.assertIsNone(result.error)
        self.assertEqual(len(transport_envelopes), 4)

    def test_max_steps_exceeded_reports_explicit_budget_error(self) -> None:
        """max budget 明确错误：步数耗尽时明确报告 max_steps_exceeded 与正确终止 reason，绝不冒充 step_completed 或 Function 报错"""
        import asyncio
        import json
        from omniflow.bridge import _run_result
        from omniflow.core.config import OmniFlowConfig, RuntimeSettings
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": f"call_click_{turn_idx}",
                        "type": "function",
                        "function": {
                            "name": "click",
                            "arguments": json.dumps({"target_description": f"btn_{turn_idx}", "x": 100.0 * turn_idx, "y": 100.0 * turn_idx}),
                        },
                    }
                ],
            }

        host = self._create_mock_host()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_budget_exceeded.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"App": "com.test.app"},
            config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=2)),
        )
        result = asyncio.run(engine.arun("连续点击"))

        # 1. 引擎执行结果确认为 max_steps_exceeded
        self.assertFalse(result.success)
        self.assertEqual(result.error, "max_steps_exceeded")
        self.assertEqual(result.detail.get("done_reason"), "max_steps_exceeded")
        self.assertEqual(len(transport_envelopes), 2)

        # 2. bridge 处理结果明确为 MAX_STEPS_EXCEEDED，而不是 FUNCTION_CALL_FAILED 或 step_completed
        bridge_res = _run_result(result, body={"run_id": "budget_run_1"}, function=None)
        self.assertEqual(bridge_res["error_code"], "MAX_STEPS_EXCEEDED")
        self.assertEqual(bridge_res["error_message"], "max_steps_exceeded")
        self.assertEqual(bridge_res["done_reason"], "max_steps_exceeded")
        self.assertEqual(bridge_res["status"], "failed")
    def test_alternating_roi_or_ocr_deduplicated_and_bounded(self) -> None:
        """同一未变屏幕下已读OCR与ROI组合去重：在相同未变屏幕下交替轮播 OCR/ROI 不得被永久判定为新进展，重复读取后有界终止"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            # 交替轮播：Turn 1 OCR=True -> Turn 2 OCR=False -> Turn 3 OCR=True (已读过) -> Turn 4 OCR=False (已读过)
            ocr_val = (turn_idx % 2 == 1)
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": f"call_alt_{turn_idx}",
                        "type": "function",
                        "function": {
                            "name": "get_state",
                            "arguments": json.dumps({"reason": f"inspect_{turn_idx}", "ocr": ocr_val}),
                        },
                    }
                ],
            }

        class StaticScreenHost:
            async def observe(self, **kwargs):
                extra = {"display": {"width": 1080, "height": 2400}}
                if kwargs.get("ocr"):
                    extra["ocr"] = {"status": "ready", "items": []}
                return {
                    "state_id": "state_static_screen",
                    "xml": '<hierarchy><node class="android.widget.TextView" text="Page" bounds="[0,0][100,100]"/></hierarchy>',
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": extra,
                }

            async def installed_apps(self):
                return {"App": "com.test.app"}

            async def act(self, action):
                return {"success": True}

        host = StaticScreenHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_alt_read.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"App": "com.test.app"},
        )
        result = asyncio.run(engine.arun("交替轮播感知"))

        # Turn 1 (OCR=True): 新证据，progress=True
        # Turn 2 (OCR=False): 新证据，progress=True
        # Turn 3 (OCR=True): 已存在于本屏幕 evidence set 中，progress=False (stall=1)
        # Turn 4 (OCR=False): 已存在于本屏幕 evidence set 中，progress=False (stall=2 -> 有界终止)
        self.assertFalse(result.success)
        self.assertEqual(result.error, "no_progress_detected")
        self.assertEqual(result.detail.get("done_reason"), "no_progress_detected")
        self.assertEqual(len(transport_envelopes), 4)
    def test_canvas_identical_xml_with_visual_update_not_stalled(self) -> None:
        """Canvas场景：结构化XML保持不变但画面内容发生真实更新时，不误判为stalled中断，可正常推进"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            if turn_idx == 1:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_canvas_1",
                            "type": "function",
                            "function": {
                                "name": "get_state",
                                "arguments": json.dumps({"reason": "check canvas initial"}),
                            },
                        }
                    ],
                }
            elif turn_idx == 2:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_canvas_2",
                            "type": "function",
                            "function": {
                                "name": "get_state",
                                "arguments": json.dumps({"reason": "check canvas updated"}),
                            },
                        }
                    ],
                }
            else:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_canvas_3",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({"content": "canvas_verified"}),
                            },
                        }
                    ],
                }

        class DynamicCanvasHost:
            def __init__(self):
                self.count = 0

            async def observe(self, **kwargs):
                self.count += 1
                # 相同 XML 容器树（如 SurfaceView/Canvas），但画面字节持续更新
                return {
                    "state_id": f"state_canvas_{self.count}",
                    "xml": '<hierarchy><node class="android.view.SurfaceView" bounds="[0,0][1080,2400]"/></hierarchy>',
                    "package_name": "com.test.game",
                    "activity_name": "GameActivity",
                    "image_base64": f"Y2FudmFzX2ZyYW1l_{self.count}",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {"display": {"width": 1080, "height": 2400}},
                }

            async def installed_apps(self):
                return {"Game": "com.test.game"}

            async def act(self, action):
                return {"success": True}

        host = DynamicCanvasHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_canvas_read.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"Game": "com.test.game"},
        )
        result = asyncio.run(engine.arun("Canvas画面感知"))

        # 画面产生变化时均被判定为实质进展，顺利推进完成，绝不被误判为 stalled_read
        self.assertTrue(result.success)
        self.assertIsNone(result.error)
        self.assertEqual(len(transport_envelopes), 3)

    def test_regression_step1_model_turn_tools_and_parsing(self) -> None:
        """行为回归：模型工具放行 long_press 与 wait，且 parse_model_turn_response 正常解析（当前基线报 model_turn_tool_not_visible）"""
        import json
        from omniflow.core.schemas import vlm_action_tools
        from omniflow.vlm.planner import _normalize_response, parse_model_turn_response

        tools = vlm_action_tools()
        # wait 模型工具最大等待时间为 10000ms
        wait_tool = next(t for t in tools if t.get("function", {}).get("name") == "wait")
        props_wait = wait_tool["function"]["parameters"]["properties"]
        self.assertEqual(props_wait["duration_ms"].get("maximum"), 10000)
        self.assertIn("condition", props_wait)
        self.assertIn("target_description", props_wait)

        # click / input_text / long_press 暴露 node_id 与 node_resource_id 选择器
        for name in ("click", "input_text", "long_press"):
            tool_spec = next(t for t in tools if t.get("function", {}).get("name") == name)
            props = tool_spec["function"]["parameters"]["properties"]
            self.assertIn("node_id", props)
            self.assertIn("node_resource_id", props)

        # parse_model_turn_response 必须能解析 long_press
        raw_lp_response = {
            "requested_model": "scene.vlm.operation.primary",
            "tool_calls": [
                {
                    "id": "call_lp_1",
                    "type": "function",
                    "function": {
                        "name": "long_press",
                        "arguments": json.dumps(
                            {
                                "x": 300,
                                "y": 400,
                                "duration_ms": 700,
                                "target_description": "Hold",
                                "node_id": "btn_1",
                                "node_resource_id": "com.app:id/btn",
                            }
                        ),
                    },
                }
            ],
        }
        lp_call, _ = parse_model_turn_response(
            raw_lp_response,
            requested_model="scene.vlm.operation.primary",
            turn_index=0,
        )
        self.assertEqual(lp_call.name, "long_press")
        self.assertEqual(lp_call.arguments.get("duration_ms"), 700)
        self.assertEqual(lp_call.arguments.get("node_id"), "btn_1")
        self.assertEqual(lp_call.arguments.get("node_resource_id"), "com.app:id/btn")

        # parse_model_turn_response 必须能解析 wait
        raw_wait_response = {
            "requested_model": "scene.vlm.operation.primary",
            "tool_calls": [
                {
                    "id": "call_wait_1",
                    "type": "function",
                    "function": {
                        "name": "wait",
                        "arguments": json.dumps(
                            {
                                "duration_ms": 2500,
                                "condition": "text_present",
                                "target_description": "去结算",
                            }
                        ),
                    },
                }
            ],
        }
        wait_call, _ = parse_model_turn_response(
            raw_wait_response,
            requested_model="scene.vlm.operation.primary",
            turn_index=1,
        )
        self.assertEqual(wait_call.name, "wait")
        self.assertEqual(wait_call.arguments.get("duration_ms"), 2500)
        self.assertEqual(wait_call.arguments.get("condition"), "text_present")
        self.assertEqual(wait_call.arguments.get("target_description"), "去结算")

    def test_regression_step1_long_press_host_preserves_duration(self) -> None:
        """行为回归：execute_robust_action 分发 long_press 时宿主必须收到 700ms 实际 duration_ms，不丢参数或被冒充"""
        import asyncio
        from unittest.mock import MagicMock
        from omniflow.core.config import PluginSet
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.execution import execute_robust_action

        host = MagicMock()
        async def fake_act(act):
            return {"success": True}
        host.act = MagicMock(side_effect=fake_act)
        async def fake_obs(**kwargs):
            return {"state_id": "s_after", "xml": "<hierarchy/>", "display": {"width": 1000, "height": 1000}}
        host.observe = MagicMock(side_effect=fake_obs)

        obs = Observation.from_value(
            {
                "state_id": "s_lp",
                "xml": '<hierarchy><node class="android.widget.Button" text="HoldMe" clickable="true" bounds="[100,100][300,300]" id="btn_hold"/></hierarchy>',
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )
        action = Action(
            "long_press",
            {"x": 200, "y": 200, "duration_ms": 700, "target_description": "HoldMe"},
        )
        step = asyncio.run(
            execute_robust_action(
                action,
                observation=obs,
                host=host,
                plugins=PluginSet(),
            )
        )
        self.assertTrue(step.success)
        self.assertEqual(host.act.call_count, 1)
        dispatched_action = host.act.call_args[0][0]
        self.assertEqual(dispatched_action.tool, "long_press")
        self.assertEqual(dispatched_action.args.get("duration_ms"), 700)
        self.assertEqual(dispatched_action.args.get("x"), 200.0)
        self.assertEqual(dispatched_action.args.get("y"), 200.0)

    def test_regression_step1_wait_tool_and_conditions(self) -> None:
        """行为回归：wait 条件校验（text_present/absent 须 target，screen_changed 禁 target，超限拒派发）与宿主诊断回传"""
        import asyncio
        from unittest.mock import MagicMock
        from omniflow.core.config import PluginSet
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.execution import execute_robust_action

        obs = Observation.from_value(
            {
                "state_id": "s_wait",
                "xml": "<hierarchy/>",
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )
        host = MagicMock()

        # 1. text_present 缺少 target_description 或为空白字符串 -> invalid_wait_condition，宿主零派发
        step1 = asyncio.run(
            execute_robust_action(
                Action("wait", {"duration_ms": 2000, "condition": "text_present"}),
                observation=obs,
                host=host,
                plugins=PluginSet(),
            )
        )
        self.assertFalse(step1.success)
        self.assertEqual(step1.error, "invalid_wait_condition")
        self.assertEqual(host.act.call_count, 0)

        step1_ws = asyncio.run(
            execute_robust_action(
                Action("wait", {"duration_ms": 2000, "condition": "text_present", "target_description": "   "}),
                observation=obs,
                host=host,
                plugins=PluginSet(),
            )
        )
        self.assertFalse(step1_ws.success)
        self.assertEqual(step1_ws.error, "invalid_wait_condition")
        self.assertEqual(host.act.call_count, 0)

        # 2. screen_changed 或无 condition 提供 target_description (哪怕为空字符串) -> invalid_wait_condition
        step2 = asyncio.run(
            execute_robust_action(
                Action(
                    "wait",
                    {
                        "duration_ms": 2000,
                        "condition": "screen_changed",
                        "target_description": "extra",
                    },
                ),
                observation=obs,
                host=host,
                plugins=PluginSet(),
            )
        )
        self.assertFalse(step2.success)
        self.assertEqual(step2.error, "invalid_wait_condition")

        step2_empty = asyncio.run(
            execute_robust_action(
                Action(
                    "wait",
                    {
                        "duration_ms": 2000,
                        "condition": "screen_changed",
                        "target_description": "",
                    },
                ),
                observation=obs,
                host=host,
                plugins=PluginSet(),
            )
        )
        self.assertFalse(step2_empty.success)
        self.assertEqual(step2_empty.error, "invalid_wait_condition")

        # 3. 条件等待 duration_ms 非法 (0, 15000, bool, float) -> invalid_wait_duration
        for bad_dur in (0, 15000, True, 2500.5):
            step_bad = asyncio.run(
                execute_robust_action(
                    Action(
                        "wait",
                        {
                            "duration_ms": bad_dur,
                            "condition": "text_present",
                            "target_description": "Loaded",
                        },
                    ),
                    observation=obs,
                    host=host,
                    plugins=PluginSet(),
                )
            )
            self.assertFalse(step_bad.success)
            self.assertEqual(step_bad.error, "invalid_wait_duration")

        # 4. 宿主返回 false wait_condition_timeout 时，StepResult 必须为 false 且包含真实诊断，不可根据时长伪造成功
        async def fake_timeout_act(act):
            return {
                "success": False,
                "error": "wait_condition_timeout",
                "extra": {
                    "message": "wait_condition_timeout",
                    "diagnostics": {
                        "wait_condition": "text_present",
                        "condition_met": "false",
                        "elapsed_ms": "2500",
                    },
                },
            }
        host.act = MagicMock(side_effect=fake_timeout_act)
        async def fake_obs(**kwargs):
            return {"state_id": "s_after_wait", "xml": "<hierarchy/>", "display": {"width": 1000, "height": 1000}}
        host.observe = MagicMock(side_effect=fake_obs)

        step_timeout = asyncio.run(
            execute_robust_action(
                Action(
                    "wait",
                    {
                        "duration_ms": 2500,
                        "condition": "text_present",
                        "target_description": "Loaded",
                    },
                ),
                observation=obs,
                host=host,
                plugins=PluginSet(),
            )
        )
        self.assertFalse(step_timeout.success)
        self.assertEqual(step_timeout.error, "wait_condition_timeout")
        self.assertIsNotNone(step_timeout.result)
        diag_timeout = step_timeout.result.extra.get("diagnostics") or {}
        self.assertEqual(str(diag_timeout.get("condition_met")), "false")

        # 5. 合法条件等待派发宿主成功，携带诊断 wait_condition/condition_met/elapsed_ms
        async def fake_wait_act(act):
            return {
                "success": True,
                "extra": {
                    "message": "wait_condition_met",
                    "diagnostics": {
                        "wait_condition": "text_present",
                        "condition_met": "true",
                        "elapsed_ms": "1500",
                    },
                },
            }
        host.act = MagicMock(side_effect=fake_wait_act)

        step5 = asyncio.run(
            execute_robust_action(
                Action(
                    "wait",
                    {
                        "duration_ms": 2500,
                        "condition": "text_present",
                        "target_description": "Loaded",
                    },
                ),
                observation=obs,
                host=host,
                plugins=PluginSet(),
            )
        )
        self.assertTrue(step5.success)
        self.assertEqual(host.act.call_count, 1)
        diag_ok = step5.result.extra.get("diagnostics") or {}
        self.assertEqual(diag_ok.get("wait_condition"), "text_present")
        self.assertEqual(str(diag_ok.get("condition_met")), "true")
        self.assertEqual(str(diag_ok.get("elapsed_ms")), "1500")
    def test_regression_step1_dynamic_selector_resolution(self) -> None:
        """行为回归：显式 selector 唯一绑定、冲突拒绝、多候选拒绝、index不充当id、清除传给host的target_description"""
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.semantic_grounding import resolve_semantic_action
        from omniflow.vlm.context import analyze_page_context

        xml = """
        <hierarchy rotation="0">
            <node class="android.widget.LinearLayout" clickable="true" bounds="[100,100][500,300]" id="card_parent" resource-id="com.app:id/card">
                <node class="android.widget.TextView" text="去结算(3)" clickable="false" bounds="[120,120][300,180]" id="checkout_txt" resource-id="com.app:id/checkout_label" />
            </node>
            <node class="android.widget.Button" text="Other" clickable="true" bounds="[100,400][300,500]" id="btn_dup" resource-id="com.app:id/dup" />
            <node class="android.widget.Button" text="Other 2" clickable="true" bounds="[100,550][300,650]" id="btn_dup" resource-id="com.app:id/dup" />
            <node class="android.widget.Button" text="OnlyRes" clickable="true" bounds="[100,700][300,800]" resource-id="com.app:id/unique_res" />
            <node class="android.widget.Button" text="IndexOnly" clickable="true" bounds="[100,850][300,950]" index="9" />
        </hierarchy>
        """
        obs = Observation.from_value(
            {
                "state_id": "s_selector",
                "xml": xml,
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )

        # 1. 显式 node_id 唯一匹配，解析到可操作祖先 card_parent，清除传给 host 的 target_description，原 target 保留在 detail
        action_checkout = Action(
            "click",
            {
                "target_description": "去结算",
                "x": 0,
                "y": 0,
                "node_id": "checkout_txt",
            },
        )
        res_checkout = resolve_semantic_action(action_checkout, obs)
        self.assertEqual(res_checkout.detail["status"], "resolved")
        self.assertEqual(res_checkout.detail["target_description"], "去结算")
        self.assertEqual(res_checkout.action.args["x"], 300.0)
        self.assertEqual(res_checkout.action.args["y"], 200.0)
        self.assertEqual(res_checkout.action.args.get("node_id"), "card_parent")
        self.assertEqual(res_checkout.action.args.get("node_resource_id"), "com.app:id/card")
        self.assertNotIn("target_description", res_checkout.action.args)

        # 2. 同时给 node_id 与 resource_id，但属于不同节点 -> target_selector_mismatch，无坐标回退
        action_conflict = Action(
            "click",
            {
                "x": 100,
                "y": 100,
                "node_id": "checkout_txt",
                "node_resource_id": "com.app:id/wrong_res",
            },
        )
        res_conflict = resolve_semantic_action(action_conflict, obs)
        self.assertEqual(res_conflict.detail["status"], "blocked")
        self.assertEqual(res_conflict.detail["reason"], "target_selector_mismatch")

        # 3. 找不到指定 node_id -> target_selector_missing，无坐标回退
        action_missing = Action(
            "click",
            {"x": 100, "y": 100, "node_id": "nonexistent_id"},
        )
        res_missing = resolve_semantic_action(action_missing, obs)
        self.assertEqual(res_missing.detail["status"], "blocked")
        self.assertEqual(res_missing.detail["reason"], "target_selector_missing")

        # 4. 重复 node_id -> target_ambiguous，无坐标回退
        action_ambiguous = Action(
            "click",
            {"x": 100, "y": 100, "node_id": "btn_dup"},
        )
        res_ambiguous = resolve_semantic_action(action_ambiguous, obs)
        self.assertEqual(res_ambiguous.detail["status"], "blocked")
        self.assertEqual(res_ambiguous.detail["reason"], "target_ambiguous")

        # 5. 仅提供唯一 resource_id 成功定位
        action_res = Action(
            "click",
            {"x": 0, "y": 0, "node_resource_id": "com.app:id/unique_res"},
        )
        res_res = resolve_semantic_action(action_res, obs)
        self.assertEqual(res_res.detail["status"], "resolved")
        self.assertEqual(res_res.action.args["x"], 200.0)
        self.assertEqual(res_res.action.args["y"], 750.0)

        # 6. index 绝不充当 node_id
        ctx = analyze_page_context(obs.to_dict(), limit=24)
        self.assertNotIn("node_id=9", ctx.evidence)
        action_index = Action(
            "click",
            {"x": 100, "y": 100, "node_id": "9"},
        )
        res_index = resolve_semantic_action(action_index, obs)
        self.assertEqual(res_index.detail["status"], "blocked")
        self.assertEqual(res_index.detail["reason"], "target_selector_missing")

    def test_regression_step1_runlog_roundtrip_long_press_and_wait(self) -> None:
        """行为回归：public run log -> project_androidworld_step_actions -> Function -> execute_function 往返，保留 long_press duration 与 wait condition/target"""
        import asyncio
        import jsonschema
        from unittest.mock import MagicMock
        from omniflow.core.config import PluginSet
        from omniflow.core.model import Observation
        from omniflow.core.schemas import load_omniflow_run_log_schema
        from omniflow.functions.assets import parse_function_artifact
        from omniflow.runlog import project_androidworld_step_actions
        from omniflow.runtime.execution import execute_function

        public_run_log = {
            "schema_version": "omniflow.run_log.v1",
            "run_id": "run_test_step1",
            "task_name": "vlm_task",
            "goal": "Test Step 1 Roundtrip",
            "task_parameters": {"goal": "Test Step 1 Roundtrip"},
            "seed": None,
            "status": "succeeded",
            "success": True,
            "validator": {
                "official": True,
                "success": True,
                "reward": 1.0,
            },
            "provenance": {
                "kind": "runtime",
            },
            "steps": [
                {
                    "step_index": 0,
                    "observation": {
                        "pixels": None,
                        "xml": '<hierarchy><node class="android.widget.Button" text="Hold" clickable="true" bounds="[100,100][500,500]" id="btn_1"/></hierarchy>',
                        "auxiliaries": {
                            "display": {"width": 1000, "height": 1000},
                            "package_name": "com.test.app",
                            "activity_name": "MainActivity",
                        },
                    },
                    "action": {
                        "action_type": "long_press",
                        "x": 300,
                        "y": 400,
                        "duration_ms": 700,
                    },
                    "result": {"success": True},
                },
                {
                    "step_index": 1,
                    "observation": {
                        "pixels": None,
                        "xml": '<hierarchy><node class="android.widget.TextView" text="Loading" bounds="[100,100][300,200]"/></hierarchy>',
                        "auxiliaries": {
                            "display": {"width": 1000, "height": 1000},
                            "package_name": "com.test.app",
                            "activity_name": "MainActivity",
                        },
                    },
                    "action": {
                        "action_type": "wait",
                        "duration_ms": 2500,
                        "condition": "text_present",
                        "target_description": "去结算",
                    },
                    "result": {"success": True},
                },
                {
                    "step_index": 2,
                    "observation": {
                        "pixels": None,
                        "xml": "<hierarchy/>",
                        "auxiliaries": {
                            "display": {"width": 1000, "height": 1000},
                            "package_name": "com.test.app",
                            "activity_name": "MainActivity",
                        },
                    },
                    "action": {
                        "action_type": "wait",
                    },
                    "result": {"success": True},
                },
            ],
        }
        run_log_schema = load_omniflow_run_log_schema()
        jsonschema.validate(instance=public_run_log, schema=run_log_schema)
        from omniflow.runlog import import_run_log_evidence
        imported_run_log, _ = import_run_log_evidence(public_run_log)
        self.assertEqual(imported_run_log["run_id"], "run_test_step1")

        # 2. project_androidworld_step_actions 投影：
        proj_step0 = project_androidworld_step_actions(public_run_log["steps"][0])
        self.assertEqual(len(proj_step0), 1)
        self.assertEqual(proj_step0[0]["tool"], "long_press")
        self.assertEqual(proj_step0[0]["args"]["duration_ms"], 700)
        self.assertEqual(proj_step0[0]["args"]["x"], 300)
        self.assertEqual(proj_step0[0]["args"]["y"], 400)

        proj_step1 = project_androidworld_step_actions(public_run_log["steps"][1])
        self.assertEqual(len(proj_step1), 1)
        self.assertEqual(proj_step1[0]["tool"], "wait")
        self.assertEqual(proj_step1[0]["args"]["duration_ms"], 2500)
        self.assertEqual(proj_step1[0]["args"]["condition"], "text_present")
        self.assertEqual(proj_step1[0]["args"]["target_description"], "去结算")

        # 3. 历史无 duration_ms 的旧 wait 投影为 1000ms 默认值
        proj_step2 = project_androidworld_step_actions(public_run_log["steps"][2])
        self.assertEqual(len(proj_step2), 1)
        self.assertEqual(proj_step2[0]["tool"], "wait")
        self.assertEqual(proj_step2[0]["args"]["duration_ms"], 1000)
        self.assertNotIn("condition", proj_step2[0]["args"])

        # 4. Function 编译与持久化：parse_function_artifact 必须保留 wait 的 condition 与 target_description
        fn_artifact = {
            "schema_version": "omniflow.function.v2",
            "function_id": "func_step1",
            "name": "func_step1",
            "description": "Step 1 Function",
            "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
            "bindings": [],
            "steps": [
                {"step_index": 0, "source_state_id": "s1", "action": proj_step0[0]},
                {"step_index": 1, "source_state_id": "s2", "action": proj_step1[0]},
            ],
            "checker_rules": [],
            "agent_visible": True,
        }
        fn = parse_function_artifact(fn_artifact)
        self.assertEqual(fn.steps[0].action.args.get("duration_ms"), 700)
        self.assertEqual(fn.steps[1].action.args.get("duration_ms"), 2500)
        self.assertEqual(fn.steps[1].action.args.get("condition"), "text_present")
        self.assertEqual(fn.steps[1].action.args.get("target_description"), "去结算")

        # 5. Function 回放：execute_function 分发给宿主
        class ReplayHost:
            def __init__(self):
                self.dispatched_actions = []

            async def act(self, action):
                self.dispatched_actions.append(action)
                return {
                    "success": True,
                    "extra": {
                        "message": "action_completed",
                        "diagnostics": {
                            "wait_condition": "text_present",
                            "condition_met": "true",
                            "elapsed_ms": "2500",
                        },
                    },
                }

            async def observe(self, **kwargs):
                return {
                    "state_id": "s_replay",
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "xml": '<hierarchy><node class="android.widget.Button" text="Hold" clickable="true" bounds="[100,100][500,500]" id="btn_1"/></hierarchy>',
                    "display": {"width": 1000, "height": 1000},
                }

            async def installed_apps(self):
                return {"App": "com.test.app"}

        host = ReplayHost()
        obs1 = Observation.from_value(
            {
                "state_id": "s1",
                "package_name": "com.test.app",
                "activity_name": "MainActivity",
                "xml": '<hierarchy><node class="android.widget.Button" text="Hold" clickable="true" bounds="[100,100][500,500]" id="btn_1"/></hierarchy>',
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )
        async def passthrough_transfer(action, observation, source_state):
            from omniflow.core.model import TransferResult
            return TransferResult(action=action)

        replay = asyncio.run(
            execute_function(
                fn,
                host=host,
                plugins=PluginSet(transfer=passthrough_transfer),
                observation=obs1,
            )
        )
        self.assertTrue(replay.success)
        self.assertEqual(len(host.dispatched_actions), 2)
        # 确认 host 实际收到的 long_press 动作包含 700ms duration
        self.assertEqual(host.dispatched_actions[0].tool, "long_press")
        self.assertEqual(host.dispatched_actions[0].args.get("duration_ms"), 700)
        # 确认 host 实际收到的 wait 动作包含 2500ms duration、condition、target_description
        self.assertEqual(host.dispatched_actions[1].tool, "wait")
        self.assertEqual(host.dispatched_actions[1].args.get("duration_ms"), 2500)
        self.assertEqual(host.dispatched_actions[1].args.get("condition"), "text_present")
        self.assertEqual(host.dispatched_actions[1].args.get("target_description"), "去结算")

    def test_regression_function_enhancement_float_epsilon_and_mismatch_rejection(self) -> None:
        """真实 save_function 增强回归：source public 1080x2400 像素 488 对应 203.33333333333334，模型写 203.3333333333333 吸收 <=1e-9 浮点精度等价顺利三 stage 保存；真实坐标偏移或错误 source_state/工具/时长严格拒绝，保证参数回放关联。"""
        import json
        import shutil
        import tempfile
        from pathlib import Path
        from omniflow.core.trajectory import state_id
        from omniflow.functions.assets import save_function, FunctionStore, bind_function

        # 1080x2400 屏幕下，像素 488 转换为 0..1000 相对坐标：
        # 488 / 2400.0 * 1000.0 = 203.33333333333334
        # 像素 540 转换为 0..1000 相对坐标：
        # 540 / 1080.0 * 1000.0 = 500.0
        obs0 = {
            "pixels": None,
            "xml": '<hierarchy><node class="android.widget.Button" text="Home" bounds="[0,0][100,100]"/></hierarchy>',
            "auxiliaries": {"display": {"width": 1080, "height": 2400}, "package_name": "com.test", "activity_name": "MainActivity"},
        }
        obs1 = {
            "pixels": None,
            "xml": '<hierarchy><node class="android.widget.Button" text="Form" bounds="[100,100][500,200]"/></hierarchy>',
            "auxiliaries": {"display": {"width": 1080, "height": 2400}, "package_name": "com.test", "activity_name": "MainActivity"},
        }
        obs2 = {
            "pixels": None,
            "xml": '<hierarchy><node class="android.widget.Button" text="Submit" bounds="[100,300][500,400]"/></hierarchy>',
            "auxiliaries": {"display": {"width": 1080, "height": 2400}, "package_name": "com.test", "activity_name": "MainActivity"},
        }

        sensitive_input = "SECRET_USER_INPUT_DO_NOT_LEAK"
        run_log = {
            "schema_version": "omniflow.run_log.v1",
            "run_id": "run_test_enhancement_epsilon",
            "task_name": "vlm_task",
            "goal": "Test Function Enhancement Epsilon",
            "task_parameters": {"goal": "Test Function Enhancement Epsilon"},
            "seed": None,
            "status": "succeeded",
            "success": True,
            "validator": {"official": True, "success": True, "reward": 1.0},
            "provenance": {"kind": "runtime"},
            "steps": [
                {"step_index": 0, "observation": obs0, "action": {"action_type": "click", "x": 540, "y": 488}, "result": {"success": True}},
                {"step_index": 1, "observation": obs1, "action": {"action_type": "click", "x": 540, "y": 720}, "result": {"success": True}},
                {"step_index": 2, "observation": obs2, "action": {"action_type": "click", "x": 540, "y": 1200}, "result": {"success": True}},
            ],
        }

        s0 = state_id(obs0)
        s1 = state_id(obs1)
        s2 = state_id(obs2)

        store_dir = Path(tempfile.mkdtemp(prefix="store_epsilon_test_"))
        store_path = store_dir / "functions.json"

        try:
            # 来源 Step 0 坐标在 0..1000 下为 x=500, y=203.33333333333334
            # 模型 proposal 写 y=203.3333333333333 (差值约 4.26e-14 <= 1e-9)
            fn_complete_epsilon = {
                "schema_version": "omniflow.function.v2",
                "function_id": "full_fn",
                "name": "Full Function",
                "description": "Full description",
                "input_schema": {"type": "object", "properties": {}, "required": [], "additionalProperties": False},
                "bindings": [],
                "steps": [
                    {"step_index": 0, "source_state_id": s0, "action": {"tool": "click", "args": {"x": 500, "y": 203.3333333333333}}},
                    {"step_index": 1, "source_state_id": s1, "action": {"tool": "click", "args": {"x": 500, "y": 300}}},
                    {"step_index": 2, "source_state_id": s2, "action": {"tool": "click", "args": {"x": 500, "y": 500}}},
                ],
                "checker_rules": [],
                "agent_visible": True,
            }

            fn_subsegment = {
                "schema_version": "omniflow.function.v2",
                "function_id": "sub_fn",
                "name": "Sub Function",
                "description": "Sub description",
                "input_schema": {
                    "type": "object",
                    "properties": {"target_y": {"type": "integer"}},
                    "required": ["target_y"],
                    "additionalProperties": False,
                },
                "bindings": [
                    {"source": "$.arguments.target_y", "target": "$.steps[0].action.args.y"}
                ],
                "steps": [
                    {"step_index": 0, "source_state_id": s1, "action": {"tool": "click", "args": {"x": 500, "y": 300}}},
                    {"step_index": 1, "source_state_id": s2, "action": {"tool": "click", "args": {"x": 500, "y": 500}}},
                ],
                "checker_rules": [],
                "agent_visible": True,
            }

            sub_split = dict(
                fn_subsegment,
                input_schema={"type": "object", "properties": {}, "required": [], "additionalProperties": False},
                bindings=[],
            )
            # 1. 成功分支：模型写 y=203.3333333333333（差值 <= 1e-9），三 stage 增强顺利保存并确立参数绑定关联
            def mock_llm_three_stages(prompt: str) -> str:
                if "stage split" in prompt:
                    return json.dumps({
                        "functions": [fn_complete_epsilon, sub_split],
                        "arguments": {"full_fn": {}, "sub_fn": {}},
                    })
                elif "stage parameters" in prompt:
                    return json.dumps({
                        "functions": [fn_complete_epsilon, fn_subsegment],
                        "arguments": {"full_fn": {}, "sub_fn": {"target_y": 300}},
                    })
                else:  # checkers
                    return json.dumps({
                        "functions": [fn_complete_epsilon, fn_subsegment],
                        "arguments": {"full_fn": {}, "sub_fn": {"target_y": 300}},
                    })

            save_function(run_log, store_path, enhance=True, complete_json=mock_llm_three_stages)
            persisted = FunctionStore(store_path)
            rebound = bind_function(persisted.get_function("sub_fn"), {"target_y": 350})
            self.assertEqual(rebound.steps[0].action.to_dict(), {"tool": "click", "args": {"x": 500, "y": 350}})

            # 2. 拒绝分支 A：错误 source_state_id 严格拒绝且 store 不被持久化
            fn_wrong_state = json.loads(json.dumps(fn_complete_epsilon))
            fn_wrong_state["steps"][0]["source_state_id"] = "wrong_state_id"
            def mock_llm_wrong_state(prompt: str) -> str:
                return json.dumps({
                    "functions": [fn_wrong_state, sub_split],
                    "arguments": {"full_fn": {}, "sub_fn": {}},
                })
            state_target_store = store_dir / "f_state.json"
            with self.assertRaises(ValueError) as ctx_state:
                save_function(run_log, state_target_store, enhance=True, complete_json=mock_llm_wrong_state)
            self.assertFalse(state_target_store.exists())
            self.assertNotIn(s0, str(ctx_state.exception))
            self.assertNotIn(sensitive_input, str(ctx_state.exception))

            # 3. 拒绝分支 B：真实坐标偏移（如 delta = 0.001 > 1e-9）严格拒绝且 store 不变
            fn_drift = json.loads(json.dumps(fn_complete_epsilon))
            fn_drift["steps"][0]["action"]["args"]["y"] = 203.33433333333334
            def mock_llm_drift(prompt: str) -> str:
                return json.dumps({
                    "functions": [fn_drift, sub_split],
                    "arguments": {"full_fn": {}, "sub_fn": {}},
                })
            drift_target_store = store_dir / "f_drift.json"
            with self.assertRaises(ValueError) as ctx_drift:
                save_function(run_log, drift_target_store, enhance=True, complete_json=mock_llm_drift)
            self.assertFalse(drift_target_store.exists())
            self.assertNotIn(s0, str(ctx_drift.exception))
            self.assertNotIn(sensitive_input, str(ctx_drift.exception))

            # 4. 拒绝分支 C：非坐标参数（如 tool 或其他字段）不匹配严格拒绝且不泄露敏感字面量
            fn_wrong_tool = json.loads(json.dumps(fn_complete_epsilon))
            fn_wrong_tool["steps"][0]["action"] = {"tool": "input_text", "args": {"text": sensitive_input, "x": 500, "y": 203.3333333333333}}
            def mock_llm_wrong_tool(prompt: str) -> str:
                return json.dumps({
                    "functions": [fn_wrong_tool, sub_split],
                    "arguments": {"full_fn": {}, "sub_fn": {}},
                })
            tool_target_store = store_dir / "f_tool.json"
            with self.assertRaises(ValueError) as ctx_tool:
                save_function(run_log, tool_target_store, enhance=True, complete_json=mock_llm_wrong_tool)
            self.assertFalse(tool_target_store.exists())
            self.assertNotIn(sensitive_input, str(ctx_tool.exception))

            # 同一 long_press 的时长不能沿坐标容差被接受。
            duration_run = json.loads(json.dumps(run_log))
            duration_run["steps"][0]["action"] = {
                "action_type": "long_press", "x": 540, "y": 488, "duration_ms": 800,
            }
            duration_full = json.loads(json.dumps(fn_complete_epsilon))
            duration_full["steps"][0]["action"] = {
                "tool": "long_press",
                "args": {"x": 500, "y": 203.3333333333333, "duration_ms": 801},
            }

            def mismatched_duration(prompt: str) -> str:
                return json.dumps({
                    "functions": [duration_full, sub_split],
                    "arguments": {"full_fn": {}, "sub_fn": {}},
                })

            duration_store = store_dir / "f_duration.json"
            with self.assertRaises(ValueError):
                save_function(duration_run, duration_store, enhance=True, complete_json=mismatched_duration)
            self.assertFalse(duration_store.exists())
        finally:
            shutil.rmtree(store_dir, ignore_errors=True)

    def test_regression_step1_tool_feedback_planned_action_identity_commit(self) -> None:
        """行为回归：标准工具反馈严格基于原始 planned action 与同一 call_id 进行 commit，即使 grounded 动作坐标改变且 target_description 被清除也不触发 fail-closed 动作不匹配"""
        import asyncio
        import json
        from unittest.mock import MagicMock
        from omniflow.core.config import PluginSet
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.execution import execute_robust_action
        from omniflow.vlm.planner import VLMPlanner

        transport_requests = []
        def test_transport(envelope):
            transport_requests.append(envelope)
            return {
                "requested_model": "scene.vlm.operation.primary",
                "tool_calls": [
                    {
                        "id": "call_click_checkout_1",
                        "type": "function",
                        "function": {
                            "name": "click",
                            "arguments": json.dumps(
                                {
                                    "target_description": "去结算",
                                    "x": 0,
                                    "y": 0,
                                    "node_id": "checkout_txt",
                                }
                            ),
                        },
                    }
                ],
            }

        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        xml = """
        <hierarchy rotation="0">
            <node class="android.widget.LinearLayout" clickable="true" bounds="[100,100][500,300]" id="card_parent" resource-id="com.app:id/card">
                <node class="android.widget.TextView" text="去结算(3)" clickable="false" bounds="[120,120][300,180]" id="checkout_txt" resource-id="com.app:id/checkout_label" />
            </node>
        </hierarchy>
        """
        obs0 = Observation.from_value(
            {
                "state_id": "s_turn0",
                "xml": xml,
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )

        # 1. 第一轮规划：产生 planned_call
        tool_call = asyncio.run(planner.one_step_tool_call("结算订单", obs0))
        self.assertEqual(tool_call.name, "click")
        planned_args = tool_call.arguments
        self.assertEqual(planned_args.get("target_description"), "去结算")
        self.assertEqual(planned_args.get("node_id"), "checkout_txt")

        # 2. 模拟宿主执行：execute_robust_action 解析得到 grounded 动作（坐标变为 (300, 200)，target_description 被清除）
        planned_action = Action(tool_call.name, planned_args)
        host = MagicMock()
        dispatched = []
        async def fake_act(act):
            dispatched.append(act)
            return {"success": True}
        host.act = MagicMock(side_effect=fake_act)
        async def fake_obs(**kwargs):
            return {"state_id": "s_after_click", "xml": "<hierarchy/>", "display": {"width": 1000, "height": 1000}}
        host.observe = MagicMock(side_effect=fake_obs)

        step = asyncio.run(execute_robust_action(planned_action, observation=obs0, host=host, plugins=PluginSet()))
        self.assertTrue(step.success)
        # 确认传给 host 的 grounded 动作清除了 target_description，且坐标已解析
        self.assertNotIn("target_description", dispatched[0].args)
        self.assertEqual(dispatched[0].args["x"], 300.0)

        # 3. 按照 engine.py 契约：previous_action 记录 planned 动作而非 grounded 动作
        # 下一轮 observation 携带 previous_action = planned_action
        obs1 = Observation.from_value(
            {
                "state_id": "s_turn1",
                "xml": "<hierarchy/>",
                "display": {"width": 1000, "height": 1000},
                "extra": {
                    "display": {"width": 1000, "height": 1000},
                    "previous_action": planned_action.to_dict(),
                    "previous_action_result": "succeeded",
                    "previous_action_error": None,
                },
            }
        )

        # 4. 第二轮规划：one_step_tool_call 必须用原 call_id ("call_click_checkout_1") 成功 commit，绝不因接地差异抛出 action_execution_unconfirmed
        asyncio.run(planner.one_step_tool_call("结算订单", obs1))
        turn2_msgs = transport_requests[1]["request"]["messages"]
        tool_msg = next(m for m in turn2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_click_checkout_1")
        res_payload = json.loads(tool_msg["content"])
        self.assertTrue(res_payload["success"])
        self.assertEqual(res_payload["tool"], "click")
    def test_regression_step2_turn_text_contains_observation_id(self) -> None:
        """步骤2行为回归：_turn_text展示当前host observation_id，不伪造state_id"""
        from omniflow.vlm.planner import build_model_turn_request
        from omniflow.core.model import Observation
        obs = Observation.from_value({
            "state_id": "state_raw_001",
            "package_name": "com.test.app",
            "activity_name": "MainActivity",
            "xml": "<hierarchy/>",
            "display": {"width": 1080, "height": 2400},
            "extra": {
                "observation_id": "obs_evidence_999",
                "display": {"width": 1080, "height": 2400},
            },
        })
        req = build_model_turn_request(
            goal="测试任务",
            state=obs.to_dict(),
            model="scene.vlm.operation.primary",
            turn_index=0,
        )
        user_msg = next(m for m in req["messages"] if m.get("role") == "user")
        user_text = next(p["text"] for p in user_msg["content"] if p.get("type") == "text")
        self.assertIn("obs_evidence_999", user_text)

    def test_regression_step2_alternating_wait_and_get_state_stalled_observations(self) -> None:
        """步骤2行为回归：同一静止屏幕下wait与get_state交替调用，被动观察计数consecutive_stalled_observations不相互清零，2次stall有界终止"""
        import asyncio
        import json
        from omniflow.bridge import _run_result
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            if turn_idx == 1:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_wait_1",
                            "type": "function",
                            "function": {
                                "name": "wait",
                                "arguments": json.dumps({"duration_ms": 1000}),
                            },
                        }
                    ],
                }
            else:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": f"call_get_state_{turn_idx}",
                            "type": "function",
                            "function": {
                                "name": "get_state",
                                "arguments": json.dumps({"reason": "check_after_wait"}),
                            },
                        }
                    ],
                }

        class StaticHost:
            async def observe(self, **kwargs):
                return {
                    "state_id": "state_const_1",
                    "xml": "<hierarchy><node text='不变内容' bounds='[0,0][1080,2400]'/></hierarchy>",
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": "obs_const_1",
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def installed_apps(self):
                return {"测试应用": "com.test.app"}

            async def act(self, action):
                return {"success": True, "detail": {"wait_diagnostics": {"elapsed_ms": 1000}}}

        host = StaticHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_alternating_stall.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"测试应用": "com.test.app"},
        )
        result = asyncio.run(engine.arun("交替等待与读屏"))

        self.assertFalse(result.success)
        self.assertEqual(result.error, "no_progress_detected")
        self.assertEqual(result.detail.get("done_reason"), "no_progress_detected")
        self.assertEqual(len(transport_envelopes), 4)

        bridge_res = _run_result(result, body={"run_id": "run_stall_alt"}, function=None)
        self.assertEqual(bridge_res["error_code"], "NO_PROGRESS_DETECTED")

    def test_regression_step2_repeated_satisfied_wait_condition_not_progress(self) -> None:
        """步骤2行为回归：同一屏幕重复执行已经满足的条件wait不算新进展，与随后读屏累加被动观察计数并有界终止"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": f"call_wait_cond_{turn_idx}",
                        "type": "function",
                        "function": {
                            "name": "wait",
                            "arguments": json.dumps({
                                "duration_ms": 1000,
                                "condition": "text_present",
                                "target_description": "已就绪",
                            }),
                        },
                    }
                ],
            }

        class StaticHost:
            async def observe(self, **kwargs):
                return {
                    "state_id": "state_const_ready",
                    "xml": "<hierarchy><node text='已就绪' bounds='[0,0][1080,2400]'/></hierarchy>",
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": "obs_const_ready",
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def installed_apps(self):
                return {"测试应用": "com.test.app"}

            async def act(self, action):
                return {
                    "success": True,
                    "extra": {
                        "wait_diagnostics": {
                            "condition_met": True,
                            "wait_condition": "text_present",
                            "elapsed_ms": 200,
                        }
                    },
                }

        host = StaticHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_repeated_cond.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"测试应用": "com.test.app"},
        )
        result = asyncio.run(engine.arun("重复条件等待"))

        self.assertFalse(result.success)
        self.assertEqual(result.error, "no_progress_detected")
        self.assertEqual(len(transport_envelopes), 3)

    def test_regression_step2_finished_missing_expected_text_evidence_mismatch_and_retry(self) -> None:
        """步骤2行为回归：finished显式提供expected_text但当前XML与OCR无匹配证据时，不结束任务且不抛planner_failed，以finished_evidence_mismatch反馈给下一轮，原tool_call_id配对提交"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            if turn_idx == 1:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_finished_mismatch_1",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({
                                    "expected_text": "支付完成",
                                    "content": "任务已完成",
                                }),
                            },
                        }
                    ],
                }
            else:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_finished_ok_2",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({"content": "改用纯模型断言完成"}),
                            },
                        }
                    ],
                }

        class HostWithUnmatchedText:
            async def observe(self, **kwargs):
                return {
                    "state_id": "state_not_paid",
                    "xml": "<hierarchy><node text='待付款' bounds='[0,0][1080,2400]'/></hierarchy>",
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": "obs_not_paid",
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def installed_apps(self):
                return {"测试应用": "com.test.app"}

            async def act(self, action):
                return {"success": True}

        host = HostWithUnmatchedText()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_finished_mismatch.json"
        from omniflow.core.config import OmniFlowConfig, RuntimeSettings
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"测试应用": "com.test.app"},
            config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=2)),
        )
        result = asyncio.run(engine.arun("完成订单任务"))

        self.assertTrue(result.success)
        self.assertEqual(len(transport_envelopes), 2)

        turn_2_msgs = transport_envelopes[1]["request"]["messages"]
        tool_msg = next(m for m in turn_2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_finished_mismatch_1")
        tool_content = json.loads(tool_msg["content"])
        self.assertFalse(tool_content["success"])
        self.assertEqual(tool_content["error"], "finished_evidence_mismatch")

    def test_regression_step2_finished_observation_expired_and_retry(self) -> None:
        """步骤2行为回归：finished显式提供不匹配的过期observation_id时，返回finished_observation_expired错误反馈并进入下一轮"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            if turn_idx == 1:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_finished_expired_1",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({
                                    "observation_id": "obs_stale_001",
                                    "content": "已完成",
                                }),
                            },
                        }
                    ],
                }
            else:
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_finished_correct_2",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({
                                    "observation_id": "obs_fresh_002",
                                    "content": "使用正确obs_id完成",
                                }),
                            },
                        }
                    ],
                }

        class HostWithObsId:
            async def observe(self, **kwargs):
                return {
                    "state_id": "state_fresh",
                    "xml": "<hierarchy/>",
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": "obs_fresh_002",
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def installed_apps(self):
                return {"测试应用": "com.test.app"}

            async def act(self, action):
                return {"success": True}

        host = HostWithObsId()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_finished_expired.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"测试应用": "com.test.app"},
        )
        result = asyncio.run(engine.arun("完成任务"))

        self.assertTrue(result.success)
        self.assertEqual(len(transport_envelopes), 2)

        turn_2_msgs = transport_envelopes[1]["request"]["messages"]
        tool_msg = next(m for m in turn_2_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "call_finished_expired_1")
        tool_content = json.loads(tool_msg["content"])
        self.assertFalse(tool_content["success"])
        self.assertEqual(tool_content["error"], "finished_observation_expired")

    def test_regression_step2_finished_no_expected_text_model_asserted_with_5_evidence_fields(self) -> None:
        """步骤2行为回归：无expected_text时finished成功但标记为model_asserted而非verified，evidence固定5字段通过_run_result透传"""
        import asyncio
        import json
        from omniflow.bridge import _run_result
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        def test_transport(envelope):
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": "call_finished_model_1",
                        "type": "function",
                        "function": {
                            "name": "finished",
                            "arguments": json.dumps({"content": "纯模型视觉断言已完成"}),
                        },
                    }
                ],
            }

        class HostWithCanvas:
            async def observe(self, **kwargs):
                return {
                    "state_id": "state_canvas_88",
                    "xml": "<hierarchy/>",
                    "package_name": "com.test.app",
                    "activity_name": "CanvasActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": "obs_canvas_88",
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def installed_apps(self):
                return {"测试应用": "com.test.app"}

            async def act(self, action):
                return {"success": True}

        host = HostWithCanvas()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        store_file = self.temp_dir / "store_finished_model_asserted.json"
        engine = OmniFlow(
            store_file,
            host=host,
            planner=planner,
            installed_apps={"测试应用": "com.test.app"},
        )
        result = asyncio.run(engine.arun("完成绘制"))

        self.assertTrue(result.success)
        evidence = result.detail.get("finished_evidence")
        self.assertIsNotNone(evidence)
        self.assertEqual(evidence["status"], "model_asserted")
        self.assertEqual(evidence["source"], "model")
        self.assertEqual(evidence["observation_id"], "obs_canvas_88")
        self.assertEqual(evidence["state_id"], "state_canvas_88")
        self.assertIsNone(evidence["matched_node_id"])

        bridge_res = _run_result(result, body={"run_id": "run_asserted_1"}, function=None)
        self.assertIn("finished_evidence", bridge_res)
        self.assertEqual(bridge_res["finished_evidence"], evidence)

    def test_regression_step2_finished_expected_text_matched_xml_and_ocr(self) -> None:
        """步骤2行为回归：expected_text在XML中匹配为matched(source=xml,node_id)，在ready OCR(>=0.5)匹配为matched(source=ocr)"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        def transport_xml(envelope):
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": "call_finished_xml",
                        "type": "function",
                        "function": {
                            "name": "finished",
                            "arguments": json.dumps({"expected_text": "支付成功"}),
                        },
                    }
                ],
            }

        class HostXml:
            def __init__(self):
                self.act_calls = []

            async def observe(self, **kwargs):
                return {
                    "state_id": "state_xml_ok",
                    "xml": '<hierarchy><node id="com.test:id/title" text=" 支付成功 " bounds="[10,10][100,100]"/></hierarchy>',
                    "package_name": "com.test",
                    "activity_name": "PayOkActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": "obs_xml_ok",
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def installed_apps(self):
                return {"测试": "com.test"}

            async def act(self, action):
                self.act_calls.append(action)
                return {"success": True}

        host1 = HostXml()
        planner1 = VLMPlanner(model="scene.vlm.operation.primary", transport=transport_xml)
        engine1 = OmniFlow(self.temp_dir / "store_f_xml.json", host=host1, planner=planner1)
        res1 = asyncio.run(engine1.arun("核对支付"))
        self.assertTrue(res1.success)
        self.assertEqual(len(host1.act_calls), 0)
        ev1 = res1.detail.get("finished_evidence")
        self.assertEqual(ev1["status"], "matched")
        self.assertEqual(ev1["source"], "xml")
        self.assertEqual(ev1["matched_node_id"], "com.test:id/title")
        self.assertEqual(ev1["observation_id"], "obs_xml_ok")

        def transport_ocr(envelope):
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": "call_finished_ocr",
                        "type": "function",
                        "function": {
                            "name": "finished",
                            "arguments": json.dumps({"expected_text": "订单已提交"}),
                        },
                    }
                ],
            }

        class HostOcr:
            def __init__(self):
                self.act_calls = []

            async def observe(self, **kwargs):
                return {
                    "state_id": "state_ocr_ok",
                    "xml": "<hierarchy/>",
                    "package_name": "com.test",
                    "activity_name": "SubmitActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": "obs_ocr_ok",
                        "display": {"width": 1080, "height": 2400},
                        "ocr": {
                            "status": "ready",
                            "items": [
                                {"text": "订单已提交", "confidence": 0.88, "bounds": [10, 10, 200, 50]}
                            ],
                        },
                    },
                }

            async def installed_apps(self):
                return {"测试": "com.test"}

            async def act(self, action):
                self.act_calls.append(action)
                return {"success": True}

        host2 = HostOcr()
        planner2 = VLMPlanner(model="scene.vlm.operation.primary", transport=transport_ocr)
        engine2 = OmniFlow(self.temp_dir / "store_f_ocr.json", host=host2, planner=planner2)
        res2 = asyncio.run(engine2.arun("核对提交"))
        self.assertTrue(res2.success)
        self.assertEqual(len(host2.act_calls), 0)
        ev2 = res2.detail.get("finished_evidence")
        self.assertEqual(ev2["status"], "matched")
        self.assertEqual(ev2["source"], "ocr")
        self.assertIsNone(ev2["matched_node_id"])

        # 3. 负例验证：自身/祖先不可见或 visible-to-user="false" 节点即使文字匹配也不算 matched
        from omniflow.runtime.semantic_grounding import match_expected_text_in_observation
        from omniflow.core.model import Observation
        hidden_self_obs = Observation.from_value({
            "state_id": "state_hidden_self",
            "xml": '<hierarchy><node visible-to-user="false" id="secret" text="支付成功" bounds="[10,10][100,100]"/></hierarchy>',
            "extra": {"display": {"width": 1080, "height": 2400}},
        })
        self.assertIsNone(match_expected_text_in_observation(hidden_self_obs, "支付成功"))

        hidden_parent_obs = Observation.from_value({
            "state_id": "state_hidden_parent",
            "xml": '<hierarchy><node visible-to-user="false"><node id="child_secret" text="支付成功" bounds="[10,10][100,100]"/></node></hierarchy>',
            "extra": {"display": {"width": 1080, "height": 2400}},
        })
        self.assertIsNone(match_expected_text_in_observation(hidden_parent_obs, "支付成功"))

    def test_regression_step3_repeated_confirmed_rejected_action_blocked_and_bounded_termination(self) -> None:
        """步骤3行为回归：同屏重复 confirmed non-executed (action_observation_expired) 动作不进行 host dispatch，反馈 repeated_rejected_action；连续第2次 repeat 沿 stall no_progress 终止"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            # 连续3轮发送完全相同的点击动作
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": f"call_click_repeat_{turn_idx}",
                        "type": "function",
                        "function": {
                            "name": "click",
                            "arguments": json.dumps({"x": 500, "y": 500}),
                        },
                    }
                ],
            }

        class HostExpired:
            def __init__(self):
                self.act_calls = []

            async def observe(self, **kwargs):
                return {
                    "state_id": "state_static_1",
                    "xml": '<hierarchy><node class="android.widget.Button" text="提交" bounds="[400,400][600,600]"/></hierarchy>',
                    "package_name": "com.test",
                    "activity_name": "PayActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": "obs_static_1",
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def installed_apps(self):
                return {"测试": "com.test"}

            async def act(self, action):
                self.act_calls.append(action)
                # 宿主返回确证未执行的观测失效拒绝
                return {"success": False, "error": "action_observation_expired"}

        host = HostExpired()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        engine = OmniFlow(self.temp_dir / "store_repeat_obs_expired.json", host=host, planner=planner)
        res = asyncio.run(engine.arun("测试重复拒绝有界终止"))

        # 1. 结果以 no_progress_detected 终止
        self.assertFalse(res.success)
        self.assertEqual(res.error, "no_progress_detected")
        self.assertEqual(res.detail.get("done_reason"), "no_progress_detected")

        # 2. 模型共调用 3 轮 (Turn 1 初次拒绝, Turn 2 第1次repeat, Turn 3 第2次repeat终止)
        self.assertEqual(res.model_calls, 3)
        self.assertEqual(len(transport_envelopes), 3)

        # 3. 宿主动作派发仅在第 1 轮发生 1 次，第 2、3 轮完全阻断，零新增派发
        self.assertEqual(len(host.act_calls), 1)

        # 4. 第 2 轮请求中的 tool 消息携带第 1 轮原 call_id 的 action_observation_expired
        turn2_messages = transport_envelopes[1]["request"]["messages"]
        tool_msg_1 = [m for m in turn2_messages if m.get("role") == "tool" and m.get("tool_call_id") == "call_click_repeat_1"]
        self.assertEqual(len(tool_msg_1), 1)
        content_1 = json.loads(tool_msg_1[0]["content"])
        self.assertFalse(content_1["success"])
        self.assertEqual(content_1["error"], "action_observation_expired")

        # 5. 第 3 轮请求中的 tool 消息携带第 2 轮原 call_id 的 repeated_rejected_action 反馈
        turn3_messages = transport_envelopes[2]["request"]["messages"]
        tool_msg_2 = [m for m in turn3_messages if m.get("role") == "tool" and m.get("tool_call_id") == "call_click_repeat_2"]
        self.assertEqual(len(tool_msg_2), 1)
        content_2 = json.loads(tool_msg_2[0]["content"])
        self.assertFalse(content_2["success"])
        self.assertEqual(content_2["error"], "repeated_rejected_action")

    def test_regression_step3_selector_blocked_zero_host_dispatch(self) -> None:
        """步骤3行为回归：显式 selector 缺失时（target_selector_missing）宿主零分发；同屏重复调用依然零分发并有界终止"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            return {
                "requested_model": envelope["model"],
                "resolved_model": "deepseek-v4-pro",
                "tool_calls": [
                    {
                        "id": f"call_selector_repeat_{turn_idx}",
                        "type": "function",
                        "function": {
                            "name": "click",
                            "arguments": json.dumps({"x": 100, "y": 100, "node_resource_id": "com.test:id/non_existent_btn"}),
                        },
                    }
                ],
            }

        class HostSelectorMissing:
            def __init__(self):
                self.act_calls = []

            async def observe(self, **kwargs):
                return {
                    "state_id": "state_sel_static",
                    "xml": '<hierarchy><node class="android.widget.TextView" text="欢迎" bounds="[10,10][200,50]"/></hierarchy>',
                    "package_name": "com.test",
                    "activity_name": "MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": "obs_sel_static",
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def installed_apps(self):
                return {"测试": "com.test"}

            async def act(self, action):
                self.act_calls.append(action)
                return {"success": True}

        host = HostSelectorMissing()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        engine = OmniFlow(self.temp_dir / "store_repeat_selector_missing.json", host=host, planner=planner)
        res = asyncio.run(engine.arun("测试selector缺失重复拒绝"))

        self.assertFalse(res.success)
        self.assertEqual(res.error, "no_progress_detected")
        self.assertEqual(res.model_calls, 3)
        # 全程宿主零派发
        self.assertEqual(len(host.act_calls), 0)

        # 第 2 轮获得第 1 轮 target_selector_missing 反馈
        turn2_messages = transport_envelopes[1]["request"]["messages"]
        tool_msg_1 = [m for m in turn2_messages if m.get("role") == "tool" and m.get("tool_call_id") == "call_selector_repeat_1"]
        self.assertEqual(len(tool_msg_1), 1)
        self.assertEqual(json.loads(tool_msg_1[0]["content"])["error"], "target_selector_missing")

        # 第 3 轮获得第 2 轮 repeated_rejected_action 反馈
        turn3_messages = transport_envelopes[2]["request"]["messages"]
        tool_msg_2 = [m for m in turn3_messages if m.get("role") == "tool" and m.get("tool_call_id") == "call_selector_repeat_2"]
        self.assertEqual(len(tool_msg_2), 1)
        self.assertEqual(json.loads(tool_msg_2[0]["content"])["error"], "repeated_rejected_action")

    def test_regression_step3_different_action_or_screen_change_clears_rejected_action(self) -> None:
        """步骤3行为回归：模型改变参数做出新决策时不被误阻断；动作成功或屏幕变化后旧拒绝签名自动清除"""
        import asyncio
        import json
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        transport_envelopes = []

        def test_transport(envelope):
            transport_envelopes.append(envelope)
            turn_idx = len(transport_envelopes)
            if turn_idx == 1:
                # Turn 1: 失败的点击动作
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_turn1",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps({"x": 100, "y": 100}),
                            },
                        }
                    ],
                }
            elif turn_idx == 2:
                # Turn 2: 模型改变参数选择新坐标点击，不应被当做 repeat
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_turn2",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps({"x": 300, "y": 300}),
                            },
                        }
                    ],
                }
            else:
                # Turn 3: 任务完成
                return {
                    "requested_model": envelope["model"],
                    "resolved_model": "deepseek-v4-pro",
                    "tool_calls": [
                        {
                            "id": "call_turn3",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({"content": "完成"}),
                            },
                        }
                    ],
                }

        class DynamicHost:
            def __init__(self):
                self.act_calls = []
                self.obs_count = 0

            async def observe(self, **kwargs):
                self.obs_count += 1
                xml = '<hierarchy><node text="新页面" bounds="[0,0][100,100]"/></hierarchy>' if self.obs_count >= 2 else '<hierarchy><node text="旧页面" bounds="[0,0][100,100]"/></hierarchy>'
                return {
                    "state_id": f"state_{self.obs_count}",
                    "xml": xml,
                    "package_name": "com.test",
                    "activity_name": "DynamicActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": f"obs_{self.obs_count}",
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def installed_apps(self):
                return {"测试": "com.test"}

            async def act(self, action):
                self.act_calls.append(action)
                if len(self.act_calls) == 1:
                    return {"success": False, "error": "action_observation_expired"}
                return {"success": True}

        host = DynamicHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=test_transport)
        engine = OmniFlow(self.temp_dir / "store_change_params.json", host=host, planner=planner)
        res = asyncio.run(engine.arun("测试改变参数新决策"))

        self.assertTrue(res.success)
        # Turn 1 发生 1 次 host 调用（失败），Turn 2 改变参数后发生第 2 次 host 调用（成功并产生新屏幕）
        self.assertEqual(len(host.act_calls), 2)
        self.assertEqual(res.model_calls, 3)

    def test_regression_step4_collect_screen_invalid_arguments_zero_dispatch(self) -> None:
        """步骤4行为回归：非法参数（坐标颠倒/越界/方向错误）直接拒绝为 invalid_collect_screen_arguments，零 host 动作派发"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner
        import json

        class InvalidArgsHost:
            def __init__(self):
                self.act_calls = []
            async def observe(self, **kwargs):
                return {
                    "state_id": "s_1",
                    "xml": '<hierarchy><node text="ITEM" bounds="[100,100][900,900]"/></hierarchy>',
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "display": {"width": 1000, "height": 1000},
                    "extra": {"observation_id": "obs_1", "display_id": 1, "display": {"width": 1000, "height": 1000}},
                }
            async def installed_apps(self):
                return {"测试": "com.test.app"}
            async def act(self, action):
                self.act_calls.append(action)
                return {"success": True}

        bad_arg_cases = [
            ("dict_dir", {"direction": {}, "x1": 100, "y1": 100, "x2": 900, "y2": 900}),
            ("list_dir", {"direction": ["down"], "x1": 100, "y1": 100, "x2": 900, "y2": 900}),
            ("dict_coord", {"direction": "down", "x1": {}, "y1": 100, "x2": 900, "y2": 900}),
            ("inverted_coords", {"direction": "down", "x1": 800, "y1": 100, "x2": 200, "y2": 900}),
            ("invalid_dir", {"direction": "sideways", "x1": 100, "y1": 100, "x2": 900, "y2": 900}),
            ("zero_max_pages", {"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900, "max_pages": 0}),
            ("overflow_max_items", {"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900, "max_items": 300}),
            ("non_bool_ocr", {"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900, "ocr": "true"}),
            ("missing_coords", {"direction": "down", "x1": 100}),
        ]

        for label, bad_args in bad_arg_cases:
            with self.subTest(case=label):
                inv_turns = 0
                def inv_transport(envelope):
                    nonlocal inv_turns
                    inv_turns += 1
                    if inv_turns == 1:
                        return {
                            "requested_model": envelope["model"],
                            "tool_calls": [{
                                "id": "c_inv",
                                "type": "function",
                                "function": {
                                    "name": "collect_screen",
                                    "arguments": json.dumps(bad_args),
                                },
                            }],
                        }
                    return {
                        "requested_model": envelope["model"],
                        "tool_calls": [{
                            "id": "c_fin",
                            "type": "function",
                            "function": {"name": "finished", "arguments": json.dumps({"content": "完成"})},
                        }],
                    }

                host = InvalidArgsHost()
                planner = VLMPlanner(model="scene.vlm.operation.primary", transport=inv_transport)
                engine = OmniFlow(self.temp_dir / f"store_step4_inv_{label}.json", host=host, planner=planner)
                res = asyncio.run(engine.arun(f"测试非法参数零派发_{label}"))

                self.assertTrue(res.success)
                self.assertEqual(len(host.act_calls), 0)
                inv_msg = next(m for m in getattr(planner, "_committed_tool_messages", []) if m.get("role") == "tool" and m.get("tool_call_id") == "c_inv")
                inv_fb = json.loads(inv_msg["content"])
                self.assertFalse(inv_fb["success"])
                self.assertEqual(inv_fb["error"], "invalid_collect_screen_arguments")
    def test_regression_step4_collect_screen_empty_background_zero_dispatch(self) -> None:
        """步骤4行为回归：空后台（package为空或android空界面）直接拒绝为 collect_screen_no_app，零动作派发"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner
        import json

        class EmptyBgHost:
            def __init__(self):
                self.act_calls = []
            async def observe(self, **kwargs):
                return {
                    "state_id": "s_1",
                    "xml": "",
                    "package_name": "android",
                    "activity_name": "Launcher",
                    "display": {"width": 1000, "height": 1000},
                    "extra": {"observation_id": "obs_1", "display_id": 1, "display": {"width": 1000, "height": 1000}},
                }
            async def installed_apps(self):
                return {"测试": "com.test.app"}
            async def act(self, action):
                self.act_calls.append(action)
                return {"success": True}

        empty_turns = 0
        def empty_transport(envelope):
            nonlocal empty_turns
            empty_turns += 1
            if empty_turns == 1:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [{
                        "id": "c_empty",
                        "type": "function",
                        "function": {
                            "name": "collect_screen",
                            "arguments": json.dumps({"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900}),
                        },
                    }],
                }
            return {
                "requested_model": envelope["model"],
                "tool_calls": [{
                    "id": "c_fin",
                    "type": "function",
                    "function": {"name": "finished", "arguments": json.dumps({"content": "完成"})},
                }],
            }

        host = EmptyBgHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=empty_transport)
        engine = OmniFlow(self.temp_dir / "store_step4_empty.json", host=host, planner=planner)
        res = asyncio.run(engine.arun("测试空后台零派发"))
        self.assertFalse(res.success)
        self.assertEqual(res.error, "collect_screen_no_app")
        self.assertEqual(res.model_calls, 1)
        self.assertEqual(len(host.act_calls), 0)
        empty_msg = next(m for m in getattr(planner, "_committed_tool_messages", []) if m.get("role") == "tool" and m.get("tool_call_id") == "c_empty")
        empty_fb = json.loads(empty_msg["content"])
        self.assertFalse(empty_fb["success"])
        self.assertEqual(empty_fb["error"], "collect_screen_no_app")

    def test_regression_step4_collect_screen_raw_pixel_adaptation_and_budget_trajectory(self) -> None:
        """步骤4行为回归：1080x2400真机下raw-pixel frame坐标自动转换至0..1000；确定性视口宿主下多屏采集完整提取52有序项（含2独立DUPLICATE_ROW），避免惯性跳过视口"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner
        from omniflow.core.config import OmniFlowConfig, RuntimeSettings
        from typing import Any
        import json

        # 构建 52 个有序行：ITEM_01..10，第 1 个 DUPLICATE_ROW，ITEM_11..25，第 2 个 DUPLICATE_ROW，ITEM_26..50
        # 与 androidTest 夹具 TestGuiFixtureActivity.buildCollectionLayout 保持严格一致
        row_specs: list[dict[str, str]] = []
        for i in range(1, 11):
            row_specs.append({"text": f"ITEM_{i:02d}", "res_id": f"item_{i:02d}"})
        row_specs.append({"text": "DUPLICATE_ROW", "res_id": "item_duplicate_1"})
        for i in range(11, 26):
            row_specs.append({"text": f"ITEM_{i:02d}", "res_id": f"item_{i:02d}"})
        row_specs.append({"text": "DUPLICATE_ROW", "res_id": "item_duplicate_2"})
        for i in range(26, 51):
            row_specs.append({"text": f"ITEM_{i:02d}", "res_id": f"item_{i:02d}"})

        # 真实均匀排布：行高同间距 137px，文字高 53px 居中（top 偏移 42px，bottom 偏移 42px）
        # 严格遵守真实 Android 列表均匀布局，禁止为钉特定丢行制造非均匀行
        row_layout: list[dict[str, Any]] = []
        for i, spec in enumerate(row_specs):
            row_top = i * 137
            text_top = row_top + 42
            text_bottom = text_top + 53
            row_layout.append({
                "spec": spec,
                "row_top": row_top,
                "row_bottom": row_top + 137,
                "text_top": text_top,
                "text_bottom": text_bottom,
            })

        total_content_height = len(row_specs) * 137  # 7124
        viewport_top = 417
        viewport_bottom = 2368
        viewport_height = viewport_bottom - viewport_top  # 1951
        max_scroll_y = max(0.0, float(total_content_height - viewport_height))  # 5173.0

        class DeterministicViewportHost:
            """确定性视口宿主：仅根据当前派发 swipe 动作计算物理位移与惯性近似，动态导出当前视口内可见 XML，不预设下一页或偷看期望"""

            def __init__(self):
                self.act_calls = []
                self.obs_count = 0
                self.scroll_y = 0.0

            async def installed_apps(self):
                return {"GUI验收": "cn.com.omnimind.androidgui.test"}

            async def observe(self, **kwargs):
                self.obs_count += 1
                visible_nodes = []
                for item in row_layout:
                    screen_top = viewport_top + item["text_top"] - self.scroll_y
                    screen_bottom = viewport_top + item["text_bottom"] - self.scroll_y
                    if screen_bottom > viewport_top and screen_top < viewport_bottom:
                        b_top = max(viewport_top, int(screen_top))
                        b_bottom = min(viewport_bottom, int(screen_bottom))
                        if b_bottom > b_top:
                            spec = item["spec"]
                            visible_nodes.append(
                                f'<node class="android.widget.TextView" '
                                f'resource-id="cn.com.omnimind.androidgui.test:id/{spec["res_id"]}" '
                                f'text="{spec["text"]}" '
                                f'bounds="[32,{b_top}][1048,{b_bottom}]"/>'
                            )

                xml_str = (
                    '<hierarchy rotation="0">'
                    '<node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">'
                    '<node class="android.widget.TextView" resource-id="cn.com.omnimind.androidgui.test:id/text_collection_header" '
                    'text="多屏长列表采集 (50项 + 2重复项)" bounds="[32,100][1048,400]"/>'
                    f'<node class="android.widget.ScrollView" resource-id="cn.com.omnimind.androidgui.test:id/scroll_collection" bounds="[32,{viewport_top}][1048,{viewport_bottom}]">'
                    + "".join(visible_nodes)
                    + "</node></node></hierarchy>"
                )

                return {
                    "state_id": f"s_det_{self.obs_count}",
                    "xml": xml_str,
                    "package_name": "cn.com.omnimind.androidgui.test",
                    "activity_name": "TestGuiFixtureActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {
                        "observation_id": f"obs_det_{self.obs_count}",
                        "display_id": 1,
                        "display": {"width": 1080, "height": 2400},
                    },
                }

            async def act(self, action):
                self.act_calls.append(action)
                if action.tool == "swipe":
                    args = action.args or {}
                    y1 = float(args.get("y1", 0.0))
                    y2 = float(args.get("y2", 0.0))
                    duration_ms = float(args.get("duration_ms", 300))
                    direction = args.get("direction", "up")

                    # 将规范坐标 (0..1000) 转换为 1080x2400 物理像素长度与初速度
                    dy_canonical = abs(y2 - y1)
                    dy_pixels = dy_canonical / 1000.0 * 2400.0
                    duration_s = max(duration_ms, 1.0) / 1000.0
                    velocity = dy_pixels / duration_s

                    # 独立且可解释的惯性滑动近似：
                    # 真实真机 (1080x2400, ScrollView 高 1951) 下旧参数 (1170.72px / 300ms, 初速度 ~3902.4px/s)
                    # 实际产生 2163px 滚动位移（> 1951px 视口，跳过 ITEM14, ITEM15）。
                    # 阻尼时间常数 tau = (2163.0 - 1170.72) / (1170.72 / 0.3) ≈ 0.254274 秒。
                    # 当手势速度降低时（如新保守参数），初速度大幅下降，产生适量惯性且总位移远小于视口高。
                    tau = (2163.0 - 1170.72) / (1170.72 / 0.3)
                    fling_pixels = velocity * tau
                    scroll_delta = dy_pixels + fling_pixels

                    if direction == "up":
                        self.scroll_y = min(max_scroll_y, self.scroll_y + scroll_delta)
                    elif direction == "down":
                        self.scroll_y = max(0.0, self.scroll_y - scroll_delta)

                return {"success": True}

        model_turns = 0

        def model_transport(envelope):
            nonlocal model_turns
            model_turns += 1
            if model_turns == 1:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [
                        {
                            "id": "c_collect_det",
                            "type": "function",
                            "function": {
                                "name": "collect_screen",
                                # 1080x2400 原始像素坐标：ScrollView 区域 [32, 417][1048, 2368]
                                # 由 VLMPlanner.screen_pixel_args_to_canonical 自动归一化至 0..1000 规范坐标
                                "arguments": json.dumps(
                                    {
                                        "direction": "down",
                                        "x1": 32,
                                        "y1": 417,
                                        "x2": 1048,
                                        "y2": 2368,
                                        "max_pages": 20,
                                        "max_items": 200,
                                    }
                                ),
                            },
                        }
                    ],
                }
            return {
                "requested_model": envelope["model"],
                "tool_calls": [
                    {
                        "id": "c_fin_det",
                        "type": "function",
                        "function": {
                            "name": "finished",
                            # 终态使用当前末页真实可见的 ITEM_50，满足 finished 证据校验
                            "arguments": json.dumps({
                                "content": "ITEM_50",
                                "expected_text": "ITEM_50",
                            }),
                        },
                    }
                ],
            }

        host = DeterministicViewportHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=model_transport)
        engine = OmniFlow(
            self.temp_dir / "store_step4_deterministic_viewport.json",
            config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=25)),
            host=host,
            planner=planner,
        )
        res = asyncio.run(engine.arun("测试确定性视口多屏采集"))

        # 1. 整体任务成功（若失败显示真实 res.error 便于排查）
        self.assertTrue(res.success, f"任务执行失败：{res.error}")
        self.assertIn("collection", res.detail)
        coll = res.detail["collection"]

        # 2. 停机边界与截断标记：完整有序读完全表，未被截断、无部分失败、无跨页重叠不确定性
        self.assertFalse(coll.get("truncated", True), f"采集结果被意外截断：{coll}")
        self.assertFalse(coll.get("partial", True), f"采集结果处于部分失败状态：{coll}")
        self.assertFalse(coll.get("overlap_uncertain", True), f"采集跨页重叠存在不确定性：{coll}")
        self.assertEqual(coll.get("stop_reason"), "no_new_content")

        # 3. 完整提取全部 52 项：50 个不同编号项 (ITEM_01..50) 不丢不重且顺序正确
        collected_items = coll.get("items", [])
        self.assertEqual(len(collected_items), 52, f"采集项总数不符（期望 52，实际 {len(collected_items)}）；任务错误={res.error}")
        all_texts = [it["text"] for it in collected_items]
        expected_all_texts = [spec["text"] for spec in row_specs]
        self.assertEqual(all_texts, expected_all_texts, f"采集项文本缺失或不匹配：{set(expected_all_texts) - set(all_texts)}")

        # 4. 严格断言 2 个同文 DUPLICATE_ROW 项位于独立物理位置与页面，未被全局去重误杀
        dup_items = [it for it in collected_items if it["text"] == "DUPLICATE_ROW"]
        self.assertEqual(len(dup_items), 2)
        self.assertNotEqual(dup_items[0]["bounds"], dup_items[1]["bounds"])
        self.assertNotEqual(dup_items[0]["page_index"], dup_items[1]["page_index"])

        # 5. 保留 raw-pixel frame 自动适配到规范坐标与消耗 budget 的断言，非 pin 具体的 sy/ey/duration
        self.assertGreater(len(host.act_calls), 0)
        self.assertGreater(res.actions_executed, 0)
        for act in host.act_calls:
            if act.tool == "swipe":
                self.assertEqual(act.args.get("direction"), "up")
                self.assertTrue(0.0 <= float(act.args.get("x1", -1)) <= 1000.0)
                self.assertTrue(0.0 <= float(act.args.get("y1", -1)) <= 1000.0)
                self.assertTrue(0.0 <= float(act.args.get("x2", -1)) <= 1000.0)
                self.assertTrue(0.0 <= float(act.args.get("y2", -1)) <= 1000.0)

    def test_regression_step4_collect_screen_package_changed_stops_partial(self) -> None:
        """步骤4行为回归：滑动后切包立即终止，返回 partial=True、stop_reason=package_changed 及已收集条目；run 整体失败且不再请求模型"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner
        from omniflow.core.config import OmniFlowConfig, RuntimeSettings
        import json

        class PkgChangeHost:
            def __init__(self):
                self.act_calls = []
                self.page = 0
            async def observe(self, **kwargs):
                self.page += 1
                pkg = "com.test.app" if self.page == 1 else "com.other.ad"
                return {
                    "state_id": f"s_{self.page}",
                    "xml": f'<hierarchy><node text="ITEM_{self.page}" bounds="[100,200][900,400]"/></hierarchy>',
                    "package_name": pkg,
                    "activity_name": "ListActivity",
                    "display": {"width": 1000, "height": 1000},
                    "extra": {"observation_id": f"obs_{self.page}", "display_id": 1, "display": {"width": 1000, "height": 1000}},
                }
            async def installed_apps(self):
                return {"测试": "com.test.app"}
            async def act(self, action):
                self.act_calls.append(action)
                return {"success": True}

        model_calls = 0
        def pkg_transport(envelope):
            nonlocal model_calls
            model_calls += 1
            if model_calls == 1:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [{
                        "id": "c_pkg",
                        "type": "function",
                        "function": {
                            "name": "collect_screen",
                            "arguments": json.dumps({"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900, "max_pages": 3}),
                        },
                    }],
                }
            return {
                "requested_model": envelope["model"],
                "tool_calls": [{
                    "id": "c_fake_fin",
                    "type": "function",
                    "function": {"name": "finished", "arguments": json.dumps({"content": "完成"})},
                }],
            }

        host = PkgChangeHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=pkg_transport)
        engine = OmniFlow(self.temp_dir / "store_step4_pkg.json", config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=10)), host=host, planner=planner)
        res = asyncio.run(engine.arun("测试切包partial"))
        self.assertFalse(res.success)
        self.assertEqual(res.error, "package_changed")
        self.assertEqual(res.model_calls, 1)
        self.assertEqual(len(host.act_calls), 1)
        coll = res.detail["collection"]
        self.assertTrue(coll["partial"])
        self.assertEqual(coll["stop_reason"], "package_changed")
        self.assertEqual(len(coll["items"]), 1)

    def test_regression_step4_collect_screen_lost_display_stops_partial(self) -> None:
        """步骤4行为回归：滑动后失去 display_id 立即终止，返回 partial=True、stop_reason=display_unavailable；run 整体失败且不再请求模型"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner
        from omniflow.core.config import OmniFlowConfig, RuntimeSettings
        import json

        class DispLostHost:
            def __init__(self):
                self.act_calls = []
                self.page = 0
            async def observe(self, **kwargs):
                self.page += 1
                extra = {"observation_id": f"obs_{self.page}", "display_id": 1, "display": {"width": 1000, "height": 1000}} if self.page == 1 else {"observation_id": f"obs_{self.page}", "display": {"width": 1000, "height": 1000}}
                return {
                    "state_id": f"s_{self.page}",
                    "xml": f'<hierarchy><node text="ITEM_{self.page}" bounds="[100,200][900,400]"/></hierarchy>',
                    "package_name": "com.test.app",
                    "activity_name": "ListActivity",
                    "display": {"width": 1000, "height": 1000},
                    "extra": extra,
                }
            async def installed_apps(self):
                return {"测试": "com.test.app"}
            async def act(self, action):
                self.act_calls.append(action)
                return {"success": True}

        model_calls = 0
        def disp_transport(envelope):
            nonlocal model_calls
            model_calls += 1
            if model_calls == 1:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [{
                        "id": "c_disp",
                        "type": "function",
                        "function": {
                            "name": "collect_screen",
                            "arguments": json.dumps({"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900, "max_pages": 3}),
                        },
                    }],
                }
            return {
                "requested_model": envelope["model"],
                "tool_calls": [{
                    "id": "c_fake_fin",
                    "type": "function",
                    "function": {"name": "finished", "arguments": json.dumps({"content": "完成"})},
                }],
            }

        host = DispLostHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=disp_transport)
        engine = OmniFlow(self.temp_dir / "store_step4_disp.json", config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=10)), host=host, planner=planner)
        res = asyncio.run(engine.arun("测试失display partial"))
        self.assertFalse(res.success)
        self.assertEqual(res.error, "display_unavailable")
        self.assertEqual(res.model_calls, 1)
        self.assertEqual(len(host.act_calls), 1)
        coll = res.detail["collection"]
        self.assertTrue(coll["partial"])
        self.assertEqual(coll["stop_reason"], "display_unavailable")
        self.assertEqual(len(coll["items"]), 1)

    def test_regression_step4_collect_screen_swipe_and_observe_failures(self) -> None:
        """步骤4行为回归：swipe拒绝归因swipe_failed，滑动后observe报错归因observe_failed，均立即终止为run failure且不得有后续模型call"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner
        from omniflow.core.config import OmniFlowConfig, RuntimeSettings
        import json

        cases = [
            ("swipe_rejected", False, False, False, "swipe_failed"),
            ("record_step_error", True, False, True, "swipe_failed"),
            ("observe_after_swipe_error", True, True, False, "observe_failed"),
        ]
        for name, act_ok, obs_throws, rec_throws, expected_reason in cases:
            with self.subTest(case=name):
                class FailureHost:
                    def __init__(self):
                        self.act_calls = []
                        self.page = 0
                    async def observe(self, **kwargs):
                        self.page += 1
                        if self.page >= 2 and obs_throws:
                            raise RuntimeError("JSONRPC host observe error after swipe")
                        return {
                            "state_id": f"s_{self.page}",
                            "xml": f'<hierarchy><node text="ITEM_{self.page}" bounds="[100,200][900,400]"/></hierarchy>',
                            "package_name": "com.test.app",
                            "activity_name": "ListActivity",
                            "display": {"width": 1000, "height": 1000},
                            "extra": {"observation_id": f"obs_{self.page}", "display_id": 1, "display": {"width": 1000, "height": 1000}},
                        }
                    async def installed_apps(self):
                        return {"测试": "com.test.app"}
                    async def act(self, action):
                        self.act_calls.append(action)
                        if not act_ok:
                            return {"success": False, "error": "action_swipe_obstructed"}
                        return {"success": True}
                    async def record_step(self, step):
                        if rec_throws:
                            raise RuntimeError("JSONRPC host record_step error")
                        return {"success": True}
                fail_model_calls = 0
                def fail_transport(envelope):
                    nonlocal fail_model_calls
                    fail_model_calls += 1
                    if fail_model_calls == 1:
                        return {
                            "requested_model": envelope["model"],
                            "tool_calls": [{
                                "id": "c_fail",
                                "type": "function",
                                "function": {
                                    "name": "collect_screen",
                                    "arguments": json.dumps({"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900, "max_pages": 3}),
                                },
                            }],
                        }
                    return {
                        "requested_model": envelope["model"],
                        "tool_calls": [{
                            "id": "c_fake_fin",
                            "type": "function",
                            "function": {"name": "finished", "arguments": json.dumps({"content": "完成"})},
                        }],
                    }

                host = FailureHost()
                planner = VLMPlanner(model="scene.vlm.operation.primary", transport=fail_transport)
                engine = OmniFlow(self.temp_dir / f"store_step4_fail_{name}.json", config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=10)), host=host, planner=planner)
                res = asyncio.run(engine.arun(f"测试失败类型_{name}"))
                self.assertFalse(res.success)
                self.assertEqual(res.error, expected_reason)
                self.assertEqual(res.model_calls, 1)
                self.assertEqual(len(host.act_calls), 1)
                coll = res.detail["collection"]
                self.assertTrue(coll["partial"])
                self.assertEqual(coll["stop_reason"], expected_reason)
                self.assertEqual(len(coll["items"]), 1)
    def test_regression_step4_collect_screen_ocr_failed_retains_xml_and_status(self) -> None:
        """步骤4行为回归：ocr=True 请求但端侧 OCR 状态为 unavailable 时，XML 正常提取保留，回报真实 ocr_status"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner
        from omniflow.core.config import OmniFlowConfig, RuntimeSettings
        import json

        class OcrFailHost:
            async def observe(self, **kwargs):
                return {
                    "state_id": "s_1",
                    "xml": '<hierarchy><node text="XML_ITEM_1" bounds="[100,200][900,400]"/><node text="XML_ITEM_2" bounds="[100,500][900,700]"/></hierarchy>',
                    "package_name": "com.test.app",
                    "activity_name": "ListActivity",
                    "display": {"width": 1000, "height": 1000},
                    "extra": {
                        "observation_id": "obs_1",
                        "display_id": 1,
                        "display": {"width": 1000, "height": 1000},
                        "ocr": {"status": "unavailable", "items": []},
                    },
                }
            async def installed_apps(self):
                return {"测试": "com.test.app"}
            async def act(self, action):
                return {"success": True}

        ocr_step = 0
        def ocr_transport(envelope):
            nonlocal ocr_step
            ocr_step += 1
            if ocr_step == 1:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [{
                        "id": "c_ocr",
                        "type": "function",
                        "function": {
                            "name": "collect_screen",
                            "arguments": json.dumps({"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900, "max_pages": 1, "ocr": True}),
                        },
                    }],
                }
            return {
                "requested_model": envelope["model"],
                "tool_calls": [{
                    "id": "c_fin",
                    "type": "function",
                    "function": {"name": "finished", "arguments": json.dumps({"content": "完成"})},
                }],
            }

        host = OcrFailHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=ocr_transport)
        engine = OmniFlow(self.temp_dir / "store_step4_ocr.json", config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=2)), host=host, planner=planner)
        res = asyncio.run(engine.arun("测试OCR失败仍保留XML"))
        coll = res.detail["collection"]
        self.assertEqual(coll["ocr_status"], "unavailable")
        self.assertEqual(len(coll["items"]), 2)
        self.assertEqual([it["text"] for it in coll["items"]], ["XML_ITEM_1", "XML_ITEM_2"])

    def test_regression_step4_collect_screen_multiple_collects_run_text_limit(self) -> None:
        """步骤4行为回归：同一 run 内多次 collect 累计字符限额 32768，超限明确 text_limit 截断且保早期条目"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner
        from omniflow.core.config import OmniFlowConfig, RuntimeSettings
        import json

        chunk = "X" * 20000
        class TextLimitHost:
            def __init__(self):
                self.p = 0
            async def observe(self, **kwargs):
                self.p += 1
                return {
                    "state_id": f"s_{self.p}",
                    "xml": f'<hierarchy><node text="{chunk}_{self.p}" bounds="[100,200][900,800]"/></hierarchy>',
                    "package_name": "com.test.app",
                    "activity_name": "ListActivity",
                    "display": {"width": 1000, "height": 1000},
                    "extra": {"observation_id": f"obs_{self.p}", "display_id": 1, "display": {"width": 1000, "height": 1000}},
                }
            async def installed_apps(self):
                return {"测试": "com.test.app"}
            async def act(self, action):
                return {"success": True}

        lim_turns = 0
        def lim_transport(envelope):
            nonlocal lim_turns
            lim_turns += 1
            if lim_turns <= 2:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [{
                        "id": f"c_lim_{lim_turns}",
                        "type": "function",
                        "function": {
                            "name": "collect_screen",
                            "arguments": json.dumps({"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900, "max_pages": 1}),
                        },
                    }],
                }
            return {
                "requested_model": envelope["model"],
                "tool_calls": [{
                    "id": "c_fin",
                    "type": "function",
                    "function": {"name": "finished", "arguments": json.dumps({"content": "完成"})},
                }],
            }

        host = TextLimitHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=lim_transport)
        engine = OmniFlow(self.temp_dir / "store_step4_lim.json", config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=5)), host=host, planner=planner)
        res = asyncio.run(engine.arun("测试多次采集文本限额"))
        coll = res.detail["collection"]
        self.assertTrue(coll["truncated"])
        self.assertEqual(coll["stop_reason"], "text_limit")
        self.assertEqual(len(coll["items"]), 1)
        self.assertTrue(coll["items"][0]["text"].startswith(chunk))

    def test_regression_step4_collect_screen_toolcall_pairing_and_budget_exhaustion_no_extra_model_request(self) -> None:
        """步骤4行为回归：首call collect与尾call click完整配对反馈，内部swipe耗尽预算后纯提交结果并直接终止，不再发起多余模型请求"""
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner
        from omniflow.core.config import OmniFlowConfig, RuntimeSettings
        import json

        class BudgetPairingHost:
            def __init__(self):
                self.p = 0
            async def observe(self, **kwargs):
                self.p += 1
                return {
                    "state_id": f"s_{self.p}",
                    "xml": f'<hierarchy><node text="ITEM_{self.p}" bounds="[100,200][900,800]"/></hierarchy>',
                    "package_name": "com.test.app",
                    "activity_name": "ListActivity",
                    "display": {"width": 1000, "height": 1000},
                    "extra": {"observation_id": f"obs_{self.p}", "display_id": 1, "display": {"width": 1000, "height": 1000}},
                }
            async def installed_apps(self):
                return {"测试": "com.test.app"}
            async def act(self, action):
                return {"success": True}

        pairing_calls = []
        def pairing_transport(envelope):
            pairing_calls.append(envelope)
            return {
                "requested_model": envelope["model"],
                "tool_calls": [
                    {
                        "id": "c_head",
                        "type": "function",
                        "function": {
                            "name": "collect_screen",
                            "arguments": json.dumps({"direction": "down", "x1": 100, "y1": 100, "x2": 900, "y2": 900, "max_pages": 5}),
                        },
                    },
                    {
                        "id": "c_tail",
                        "type": "function",
                        "function": {
                            "name": "click",
                            "arguments": json.dumps({"x": 500, "y": 500}),
                        },
                    },
                ],
            }

        host = BudgetPairingHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=pairing_transport)
        engine = OmniFlow(self.temp_dir / "store_step4_budget.json", config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=2)), host=host, planner=planner)
        res = asyncio.run(engine.arun("测试预算耗尽与尾调用未执行"))
        self.assertFalse(res.success)
        self.assertEqual(res.error, "max_steps_exceeded")
        self.assertEqual(len(pairing_calls), 1)
        committed_msgs = getattr(planner, "_committed_tool_messages", [])
        asst_msg = next(m for m in committed_msgs if m.get("role") == "assistant")
        self.assertEqual(asst_msg["role"], "assistant")

        head_msg = next(m for m in committed_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "c_head")
        head_fb = json.loads(head_msg["content"])
        self.assertTrue(head_fb["success"])
        self.assertEqual(head_fb["collection"]["stop_reason"], "step_limit")

        tail_msg = next(m for m in committed_msgs if m.get("role") == "tool" and m.get("tool_call_id") == "c_tail")
        tail_fb = json.loads(tail_msg["content"])
        self.assertFalse(tail_fb["success"])
        self.assertFalse(tail_fb["executed"])
        self.assertEqual(tail_fb["error"], "not_executed_observation_refresh_required")
    def test_step5_experience_selection_prompt_rendering_and_disabled_suppression(self) -> None:
        """步骤5行为回归：当前页面匹配的唯一资源ID经验渲染为中文参考模板，停用/重复ID不渲染，不泄露run_id或用户自由文字"""
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id
        from omniflow.vlm.planner import _turn_text

        exp_store_dir = self.temp_dir / "step5_exp_prompt"
        exp_store_dir.mkdir(parents=True, exist_ok=True)
        exp_store_file = exp_store_dir / "app-experiences.json"

        rule_enabled = {
            "action": "click",
            "target_resource_id": "com.test.app:id/checkout_btn",
            "target_class": "android.widget.Button",
            "after_activity": "com.test.app.PaymentActivity",
        }
        id_enabled = compute_experience_id("com.test.app", "MainActivity", rule_enabled)

        rule_disabled = {
            "action": "click",
            "target_resource_id": "com.test.app:id/disabled_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        id_disabled = compute_experience_id("com.test.app", "MainActivity", rule_disabled)

        rule_dup = {
            "action": "click",
            "target_resource_id": "com.test.app:id/dup_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        id_dup = compute_experience_id("com.test.app", "MainActivity", rule_dup)

        store_data = {
            "schema_version": "omniflow.app-experiences.v1",
            "revision": 1,
            "processed_run_ids": ["run_secret_999"],
            "items": [
                {
                    "id": id_enabled,
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "status": "trial",
                    "enabled": True,
                    "source_run_id": "run_secret_999",
                    "source_step_index": 0,
                    "rule": rule_enabled,
                    "successful_run_ids": [],
                    "failed_run_ids": [],
                    "consecutive_failures": 0,
                    "previous_verified_id": None,
                },
                {
                    "id": id_disabled,
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "status": "trial",
                    "enabled": False,
                    "source_run_id": "run_secret_999",
                    "source_step_index": 1,
                    "rule": rule_disabled,
                    "successful_run_ids": [],
                    "failed_run_ids": [],
                    "consecutive_failures": 0,
                    "previous_verified_id": None,
                },
                {
                    "id": id_dup,
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "status": "trial",
                    "enabled": True,
                    "source_run_id": "run_secret_999",
                    "source_step_index": 2,
                    "rule": rule_dup,
                    "successful_run_ids": [],
                    "failed_run_ids": [],
                    "consecutive_failures": 0,
                    "previous_verified_id": None,
                },
            ],
        }
        with open(exp_store_file, "w", encoding="utf-8") as f:
            json.dump(store_data, f)

        store = AppExperienceStore(exp_store_file)
        xml = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.Button" resource-id="com.test.app:id/checkout_btn" text="去结算" bounds="[0,0][100,100]" visible-to-user="true"/>'
            '<node class="android.widget.Button" resource-id="com.test.app:id/dup_btn" text="重复1" bounds="[0,100][100,200]" visible-to-user="true"/>'
            '<node class="android.widget.Button" resource-id="com.test.app:id/dup_btn" text="重复2" bounds="[0,200][100,300]" visible-to-user="true"/>'
            '<node class="android.widget.Button" resource-id="com.test.app:id/disabled_btn" text="停用" bounds="[0,300][100,400]" visible-to-user="true"/>'
            '</hierarchy>'
        )
        from omniflow.runtime.engine import _extract_visible_resource_ids
        visible_ids = _extract_visible_resource_ids(xml)
        selected = store.select("com.test.app", "MainActivity", visible_ids)
        self.assertEqual(len(selected), 1)
        self.assertEqual(selected[0]["id"], id_enabled)

        prompt = _turn_text(
            goal="测试结算",
            state={
                "package_name": "com.test.app",
                "activity_name": "MainActivity",
                "xml": xml,
                "extra": {"app_experiences": selected},
            },
        )
        exp_lines = [line for line in prompt.splitlines() if line.startswith("当前页面参考：")]
        self.assertEqual(len(exp_lines), 1)
        self.assertIn("控件com.test.app:id/checkout_btn可用于点击，曾到达com.test.app.PaymentActivity；以当前状态和目标为准", exp_lines[0])
        self.assertTrue(all("disabled_btn" not in line for line in exp_lines))
        self.assertTrue(all("dup_btn" not in line for line in exp_lines))
        self.assertNotIn("run_secret_999", prompt)

    def test_step5_step_metadata_app_experience_attribution_and_neutral_protection(self) -> None:
        """步骤5行为回归：真实执行后只有匹配本轮选中规则的步骤才写入app_experience_ids，未匹配或无目标关联的失败保持中性"""
        from omniflow.core.model import Action, Observation
        from omniflow.runtime.engine import _resolve_step_app_experience_ids

        rule = {
            "action": "click",
            "target_resource_id": "com.test.app:id/submit_btn",
            "target_class": "android.widget.Button",
            "after_activity": "com.test.app.DoneActivity",
        }
        selected = [{
            "id": "exp_test_001",
            "rule": rule,
        }]

        before_xml = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.Button" resource-id="com.test.app:id/submit_btn" text="提交" bounds="[0,0][100,100]" visible-to-user="true"/>'
            '<node class="android.widget.Button" resource-id="com.test.app:id/cancel_btn" text="取消" bounds="[0,100][100,200]" visible-to-user="true"/>'
            '</hierarchy>'
        )
        before_obs = Observation(package_name="com.test.app", activity_name="MainActivity", xml=before_xml)

        class FakeStepResult:
            def __init__(self, success, action, error=None, extra=None, detail=None):
                self.success = success
                self.action = action
                self.error = error
                self.result = MagicMock()
                self.result.extra = extra or {}
                self.detail = detail or {}

        # 1. 成功且 resource_id + class 匹配 -> 成功归因
        step_ok = FakeStepResult(
            True,
            Action("click", {"x": 50, "y": 50, "node_resource_id": "com.test.app:id/submit_btn"}),
            extra={"diagnostics": {"guard_status": "target_confirmed", "target_resource_id": "com.test.app:id/submit_btn", "target_class": "android.widget.Button"}},
            detail={"semantic_grounding": {"status": "resolved", "node_resource_id": "com.test.app:id/submit_btn"}},
        )
        ids_ok = _resolve_step_app_experience_ids(selected, Action("click", {"node_resource_id": "com.test.app:id/submit_btn"}), step_ok, before_obs)
        self.assertEqual(ids_ok, ["exp_test_001"])

        # 2. 成功但执行了无关控件 cancel_btn -> 不归因
        step_unrelated = FakeStepResult(
            True,
            Action("click", {"x": 50, "y": 150, "node_resource_id": "com.test.app:id/cancel_btn"}),
            extra={"diagnostics": {"guard_status": "target_confirmed", "target_resource_id": "com.test.app:id/cancel_btn", "target_class": "android.widget.Button"}},
            detail={"semantic_grounding": {"status": "resolved", "node_resource_id": "com.test.app:id/cancel_btn"}},
        )
        ids_unrelated = _resolve_step_app_experience_ids(selected, Action("click", {"node_resource_id": "com.test.app:id/cancel_btn"}), step_unrelated, before_obs)
        self.assertEqual(ids_unrelated, [])

        # 3. 失败但没有引用 submit_btn -> 保持中性
        step_neutral_fail = FakeStepResult(
            False,
            Action("click", {"x": 50, "y": 150}),
            error="action_target_missing",
        )
        ids_neutral = _resolve_step_app_experience_ids(selected, Action("click", {}), step_neutral_fail, before_obs)
        self.assertEqual(ids_neutral, [])

        # 4. 失败且显式引用了 submit_btn 且错误为可归因错误 -> 记录已用ID以供Store负向归因
        step_attributable_fail = FakeStepResult(
            False,
            Action("click", {"node_resource_id": "com.test.app:id/submit_btn"}),
            error="target_selector_missing",
        )
        ids_fail = _resolve_step_app_experience_ids(selected, Action("click", {"node_resource_id": "com.test.app:id/submit_btn"}), step_attributable_fail, before_obs)
        self.assertEqual(ids_fail, ["exp_test_001"])

        # 5. 模型仅提供 target_description（无 node_id/node_resource_id），
        # 经语义接地解析为 submit_btn 后被宿主拒绝 -> 正确归因已使用经验
        step_grounded_fail = FakeStepResult(
            False,
            Action("click", {"x": 50, "y": 50, "node_resource_id": "com.test.app:id/submit_btn"}),
            error="action_target_missing",
            extra={"diagnostics": {"reason": "action_target_missing"}},
            detail={"semantic_grounding": {"status": "resolved", "node_resource_id": "com.test.app:id/submit_btn", "class": "android.widget.Button"}},
        )
        planned_desc_only = Action("click", {"x": 50, "y": 50, "target_description": "提交"})
        ids_grounded = _resolve_step_app_experience_ids(selected, planned_desc_only, step_grounded_fail, before_obs)
        self.assertEqual(ids_grounded, ["exp_test_001"])

        # 6. 中性对照：模型仅提供 target_description，但接地解析为无关控件 cancel_btn -> 保持中性
        step_unrelated_grounded_fail = FakeStepResult(
            False,
            Action("click", {"x": 50, "y": 150, "node_resource_id": "com.test.app:id/cancel_btn"}),
            error="action_target_missing",
            extra={"diagnostics": {"reason": "action_target_missing"}},
            detail={"semantic_grounding": {"status": "resolved", "node_resource_id": "com.test.app:id/cancel_btn", "class": "android.widget.Button"}},
        )
        ids_unrelated_grounded = _resolve_step_app_experience_ids(selected, planned_desc_only, step_unrelated_grounded_fail, before_obs)
        self.assertEqual(ids_unrelated_grounded, [])

    def test_step5_bridge_tools_list_isolation_and_parameter_contracts(self) -> None:
        """步骤5行为回归：tools/list暴露公开管理工具但不泄露私有learn，learn严格校验run_id与封存状态并经get_state读取状态"""
        from omniflow.bridge import JsonLineBridge

        bridge_dir = self.temp_dir / "step5_bridge"
        bridge_dir.mkdir(parents=True, exist_ok=True)
        store_path = bridge_dir / "store.json"

        bridge = JsonLineBridge(store_path)
        tool_list = bridge._handle("req_list", "tools/list", {})
        tool_names = [t["name"] for t in tool_list.get("tools", [])]

        self.assertIn("list_app_experiences", tool_names)
        self.assertIn("set_app_experience_enabled", tool_names)
        self.assertNotIn("learn_app_experiences", tool_names)

        # 1. 尝试向私有 learn 传递额外字段（如 run_log 或 rule）-> 严格合同拒绝
        with self.assertRaises(ValueError) as ctx:
            bridge._handle("req_c1", "tools/call", {
                "name": "learn_app_experiences",
                "arguments": {"run_id": "r1", "rule": {"action": "click"}},
            })
        self.assertIn("request_unknown_fields:rule", str(ctx.exception))

        # 2. 尝试向私有 learn 传递空或缺少 run_id -> 严格合同拒绝
        with self.assertRaises(ValueError) as ctx:
            bridge._handle("req_c2", "tools/call", {
                "name": "learn_app_experiences",
                "arguments": {},
            })
        self.assertIn("request_missing_fields:run_id", str(ctx.exception))

        # 3. 模拟未封存的 run_log (缺 finished_at_ms) -> 拒绝
        host_calls = []
        def mock_host_call(req_id, method, args):
            host_calls.append((method, dict(args)))
            if method == "get_run_log":
                return {
                    "run_id": "run_unsealed",
                    "success": True,
                    "finished_at_ms": None,
                    "steps": [],
                }
            return {}

        bridge.host_call = mock_host_call
        res_unsealed = bridge._handle("req_c3", "tools/call", {
            "name": "learn_app_experiences",
            "arguments": {"run_id": "run_unsealed"},
        })
        self.assertFalse(res_unsealed.get("success"))
        self.assertEqual(res_unsealed.get("error_code"), "RUN_LOG_NOT_SEALED")

        # 4. 模拟已封存 run_log，断言宿主方法使用 get_state 读取引用状态（绝不调用不存在的 get_run_log_state）
        host_calls.clear()
        def mock_host_call_sealed(req_id, method, args):
            host_calls.append((method, dict(args)))
            if method == "get_run_log":
                return {
                    "run_id": "run_sealed_1",
                    "success": True,
                    "finished_at_ms": 1700000000000,
                    "diagnostics": {"finished_evidence": {"status": "matched"}},
                    "steps": [
                        {
                            "observation": {"auxiliaries": {"state_id": "state_101"}},
                            "next_observation": {"auxiliaries": {"state_id": "state_102"}},
                            "action": {"action_type": "click"},
                            "result": {"success": True},
                            "metadata": {
                                "transfer": {
                                    "semantic_grounding": {
                                        "status": "resolved",
                                        "node_resource_id": "com.test.app:id/btn",
                                    }
                                },
                                "action_result": {
                                    "diagnostics": {
                                        "guard_status": "target_confirmed",
                                        "target_resource_id": "com.test.app:id/btn",
                                        "target_class": "android.widget.Button",
                                    }
                                },
                            },
                        }
                    ],
                }
            if method == "get_state":
                sid = args.get("state_id")
                return {
                    "state_id": sid,
                    "xml": '<hierarchy><node class="android.widget.Button" resource-id="com.test.app:id/btn" bounds="[0,0][10,10]" visible-to-user="true"/></hierarchy>',
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                }
            return {}

        bridge.host_call = mock_host_call_sealed
        res_learn = bridge._handle("req_c4", "tools/call", {
            "name": "learn_app_experiences",
            "arguments": {"run_id": "run_sealed_1"},
        })
        self.assertTrue(res_learn.get("success"))
        methods_called = [m[0] for m in host_calls]
        self.assertIn("get_run_log", methods_called)
        self.assertIn("get_state", methods_called)
        self.assertNotIn("get_run_log_state", methods_called)

    def test_step5_corrupted_store_graceful_gui_and_no_overwrite(self) -> None:
        """步骤5行为回归：app-experiences.json损坏时GUI正常执行并在diagnostics报告错误，绝不覆写原损坏文件"""
        from omniflow.bridge import JsonLineBridge
        from omniflow.core.model import Observation, ToolCall
        from omniflow.runtime.engine import OmniFlow

        store_dir = self.temp_dir / "step5_corrupt"
        store_dir.mkdir(parents=True, exist_ok=True)
        store_path = store_dir / "store.json"
        exp_path = store_dir / "app-experiences.json"

        corrupted_content = "{ this is corrupted json payload !!!"
        with open(exp_path, "w", encoding="utf-8") as f:
            f.write(corrupted_content)

        # 1. OmniFlow 构造与运行：正常完成，记录 diagnostics，文件不被覆盖
        class SimpleHost:
            async def installed_apps(self):
                return {}
            async def observe(self, **kwargs):
                return Observation(package_name="com.test.app", activity_name="MainActivity")
            async def act(self, action):
                return {"success": True}

        class MockPlanner:
            async def one_step_tool_call(self, *args, **kwargs):
                return ToolCall("finished", {})

        engine = OmniFlow(store_path, host=SimpleHost(), planner=MockPlanner())
        result = asyncio.run(engine.arun("测试损坏store"))
        self.assertTrue(result.success)
        diag = result.detail.get("planner_diagnostics") or {}
        self.assertEqual(diag.get("experience_store_error"), "app_experience_store_invalid")

        # 检查原文件未被覆写
        with open(exp_path, "r", encoding="utf-8") as f:
            current_content = f.read()
        self.assertEqual(current_content, corrupted_content)

        # 2. Bridge 管理工具：list_app_experiences 与 set_app_experience_enabled 明确报告 app_experience_store_invalid
        bridge = JsonLineBridge(store_path)
        list_res = bridge._handle("req_l", "tools/call", {"name": "list_app_experiences", "arguments": {}})
        self.assertFalse(list_res.get("success"))
        self.assertEqual(list_res.get("error_code"), "app_experience_store_invalid")

        set_res = bridge._handle("req_s", "tools/call", {
            "name": "set_app_experience_enabled",
            "arguments": {"experience_id": "exp_x", "enabled": True, "expected_revision": 0},
        })
        self.assertFalse(set_res.get("success"))
        self.assertEqual(set_res.get("error_code"), "app_experience_store_invalid")

        # 检查原文件依然完整保留
        with open(exp_path, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), corrupted_content)

    def test_step5_bridge_list_and_set_enabled_zero_host_zero_model(self) -> None:
        """步骤5行为回归：list_app_experiences与set_app_experience_enabled纯本地Store操作，零host与零模型调用，支持版本冲突校验"""
        from omniflow.bridge import JsonLineBridge
        from omniflow.runtime.app_experience import compute_experience_id

        manage_dir = self.temp_dir / "step5_manage"
        manage_dir.mkdir(parents=True, exist_ok=True)
        store_path = manage_dir / "store.json"
        exp_path = manage_dir / "app-experiences.json"

        rule = {
            "action": "click",
            "target_resource_id": "com.test.app:id/item_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        exp_id = compute_experience_id("com.test.app", "MainActivity", rule)

        store_data = {
            "schema_version": "omniflow.app-experiences.v1",
            "revision": 5,
            "processed_run_ids": [],
            "items": [
                {
                    "id": exp_id,
                    "package_name": "com.test.app",
                    "activity_name": "MainActivity",
                    "status": "verified",
                    "enabled": True,
                    "source_run_id": "run_001",
                    "source_step_index": 0,
                    "rule": rule,
                    "successful_run_ids": ["run_002", "run_003"],
                    "failed_run_ids": [],
                    "consecutive_failures": 0,
                    "previous_verified_id": None,
                }
            ],
        }
        with open(exp_path, "w", encoding="utf-8") as f:
            json.dump(store_data, f)

        bridge = JsonLineBridge(store_path)
        host_called = False
        def fake_host_call(*args, **kwargs):
            nonlocal host_called
            host_called = True
            return {}
        bridge.host_call = fake_host_call

        # 1. list_app_experiences: 零 host 调用
        list_res = bridge._handle("req_1", "tools/call", {
            "name": "list_app_experiences",
            "arguments": {"limit": 10, "offset": 0},
        })
        self.assertTrue(list_res.get("success"))
        self.assertEqual(list_res.get("total"), 1)
        self.assertEqual(list_res.get("revision"), 5)
        self.assertFalse(host_called)

        # 2. set_app_experience_enabled 版本冲突
        set_conflict = bridge._handle("req_2", "tools/call", {
            "name": "set_app_experience_enabled",
            "arguments": {"experience_id": exp_id, "enabled": False, "expected_revision": 999},
        })
        self.assertFalse(set_conflict.get("success"))
        self.assertEqual(set_conflict.get("error_code"), "app_experience_revision_conflict")
        self.assertFalse(host_called)

        # 3. set_app_experience_enabled 正常更新
        set_ok = bridge._handle("req_3", "tools/call", {
            "name": "set_app_experience_enabled",
            "arguments": {"experience_id": exp_id, "enabled": False, "expected_revision": 5},
        })
        self.assertTrue(set_ok.get("success"))
        self.assertEqual(set_ok.get("revision"), 6)
        self.assertFalse(set_ok.get("item", {}).get("enabled"))
        self.assertFalse(host_called)

    def test_step5_real_engine_delayed_reads_clear_episode_failure_indexes_neutral(self) -> None:
        """步骤5行为回归：真实engine延迟读屏变化正确重置episode使用索引，使后续停滞不误罚前序经验"""
        import asyncio
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id
        from omniflow.runtime.engine import OmniFlow
        from omniflow.vlm.planner import VLMPlanner

        xml_btn = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.Button" resource-id="com.test.app:id/submit_btn" text="提交" bounds="[0,0][100,100]" visible-to-user="true"/>'
            '</hierarchy>'
        )
        xml_page1 = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.Button" resource-id="com.test.app:id/submit_btn" text="提交" bounds="[0,0][100,100]" visible-to-user="true"/>'
            '<node class="android.widget.TextView" text="page1_loaded" bounds="[0,100][100,200]" visible-to-user="true"/>'
            '</hierarchy>'
        )

        class DelayedHost:
            def __init__(self):
                self.observe_count = 0
                self.act_calls = []
                self.facts = []

            async def observe(self, **kwargs):
                self.observe_count += 1
                # 初始观察与立即后置观察：xml_btn；第3次观察起（首次get_state）：xml_page1（页面发生实质变化）
                xml = xml_page1 if self.observe_count >= 3 else xml_btn
                return {
                    "xml": xml,
                    "package_name": "com.test.app",
                    "activity_name": "com.test.app.MainActivity",
                    "display": {"width": 1080, "height": 2400},
                    "extra": {"observation_id": f"obs_{self.observe_count}"},
                }

            async def installed_apps(self):
                return {"测试应用": "com.test.app"}

            async def act(self, action):
                self.act_calls.append(action)
                return {
                    "success": True,
                    "extra": {
                        "diagnostics": {
                            "guard_status": "target_confirmed",
                            "target_resource_id": "com.test.app:id/submit_btn",
                            "target_class": "android.widget.Button",
                        },
                    },
                }

            async def record_step(self, fact):
                recorded = {"step_index": len(self.facts), **fact}
                self.facts.append(recorded)
                return {"step": recorded}

        turn_count = 0
        def delayed_transport(request):
            nonlocal turn_count
            turn_count += 1
            name = "click" if turn_count == 1 else "get_state"
            arguments = (
                {"x": 50, "y": 50, "target_description": "提交"}
                if turn_count == 1 else {}
            )
            return {
                "requested_model": request["model"],
                "resolved_model": "synthetic-local",
                "tool_calls": [{
                    "id": f"call_{turn_count}",
                    "type": "function",
                    "function": {"name": name, "arguments": json.dumps(arguments)},
                }],
            }

        exp_dir = self.temp_dir / "step5_delayed_engine"
        exp_dir.mkdir(parents=True, exist_ok=True)
        store_path = exp_dir / "store.json"
        exp_store_path = exp_dir / "app-experiences.json"

        rule = {
            "action": "click",
            "target_resource_id": "com.test.app:id/submit_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        exp_id = compute_experience_id("com.test.app", "com.test.app.MainActivity", rule)
        exp_store = AppExperienceStore(exp_store_path)
        exp_store.items.append({
            "id": exp_id,
            "package_name": "com.test.app",
            "activity_name": "com.test.app.MainActivity",
            "status": "trial",
            "enabled": True,
            "source_run_id": "run_src",
            "source_step_index": 0,
            "rule": rule,
            "successful_run_ids": [],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": None,
        })
        exp_store._save()

        host = DelayedHost()
        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=delayed_transport)
        engine = OmniFlow(store_path, host=host, planner=planner, installed_apps={"测试应用": "com.test.app"})

        res = asyncio.run(engine.arun("测试延迟变页中性保护"))
        self.assertFalse(res.success)
        self.assertEqual(res.error, "no_progress_detected")
        diag = res.detail.get("planner_diagnostics") or {}
        failure_indexes = diag.get("app_experience_failure_step_indexes")
        self.assertEqual(failure_indexes, [])
        self.assertEqual([action.tool for action in host.act_calls], ["click"])
        self.assertEqual(host.facts[0]["metadata"]["app_experience_ids"], [exp_id])
        self.assertGreaterEqual(host.observe_count, 3)

    def test_regression_planner_multi_turn_strict_transcript_consumer_protocol(self) -> None:
        """多轮规划严格传输消费者回归：
        1. 每一个向 transport 发出的请求，首个非 system 消息必须为 user（严禁以 assistant 起首）；
        2. 每一组 assistant tool_calls 之前必须存在真实当时 user 文字观测（非伪造空 user）；
        3. 每一个已派发的 tool_call_id 必须在后续消息中具有对应的 tool 结果反馈；
        4. 只有最新的 user 消息包含图片，历史各轮 user 的文字观测（目标、state_id、XML）必须完整保留而图片绝不累积；
        5. 实际三轮 planner 交互（click -> get_state -> finished）；
        6. 失败反馈分支（如动作受阻）同样保持 user 观测与 tool 失败反馈的严格配对。
        """
        import asyncio
        import json
        from omniflow.core.model import Observation
        from omniflow.vlm.planner import VLMPlanner

        # 辅助验证函数：校验单个请求的完整 transcript 是否符合严格消费者规范
        def assert_strict_transcript(messages: list[dict], turn_label: str) -> None:
            self.assertGreaterEqual(len(messages), 2, f"{turn_label}: messages 至少包含 system 与 user")
            self.assertEqual(messages[0].get("role"), "system", f"{turn_label}: 首消息必须是 system")
            if messages[1].get("role") != "user":
                raise AssertionError(
                    f"{turn_label}: strict transport 违背协议 - 非 system 首消息必须为 user，实际为 {messages[1].get('role')}"
                )

            # 遍历所有 assistant 消息，验证紧邻的前一条消息必须是真实的 user 消息（含非空观测文字）
            user_indices = []
            for idx, msg in enumerate(messages):
                role = msg.get("role")
                if role == "user":
                    user_indices.append(idx)
                    content = msg.get("content")
                    user_text = ""
                    if isinstance(content, str):
                        user_text = content
                    elif isinstance(content, list):
                        for part in content:
                            if isinstance(part, dict) and part.get("type") == "text":
                                user_text += part.get("text", "")
                    self.assertTrue(
                        len(user_text.strip()) > 0,
                        f"{turn_label}: index {idx} 的 user 消息缺少实质文字内容，不得补假空 user",
                    )
                elif role == "assistant":
                    if idx == 0:
                        raise AssertionError(f"{turn_label}: assistant 不能位于 index 0")
                    prev_msg = messages[idx - 1]
                    if prev_msg.get("role") != "user":
                        raise AssertionError(
                            f"{turn_label}: strict transport 违背协议 - assistant (index {idx}) 前必须是触发其决策的真实 user 消息，实际为 {prev_msg.get('role')}"
                        )
                    tool_calls = msg.get("tool_calls", [])
                    for call in tool_calls:
                        call_id = call.get("id")
                        tool_matches = [
                            m for m in messages[idx + 1:]
                            if m.get("role") == "tool" and m.get("tool_call_id") == call_id
                        ]
                        self.assertTrue(
                            len(tool_matches) >= 1,
                            f"{turn_label}: tool_call_id {call_id} 缺少对应的 tool 结果消息",
                        )

            # 图片累积检查：只有最新的 user 消息允许包含图片，历史 user 消息不得包含任何图片
            self.assertTrue(len(user_indices) > 0, f"{turn_label}: 缺少 user 消息")
            latest_user_idx = user_indices[-1]
            for u_idx in user_indices:
                u_msg = messages[u_idx]
                content = u_msg.get("content", [])
                img_count = 0
                if isinstance(content, list):
                    img_count = len([p for p in content if isinstance(p, dict) and p.get("type") == "image_url"])
                if u_idx != latest_user_idx:
                    self.assertEqual(
                        img_count, 0,
                        f"{turn_label}: 历史 user (index {u_idx}) 不得累积图片，实际包含 {img_count} 张图",
                    )
                else:
                    self.assertGreaterEqual(
                        img_count, 1,
                        f"{turn_label}: 当前最新 user (index {u_idx}) 必须包含当帧观测图片",
                    )

        # -------------------------------------------------------------
        # 路径 A：三轮成功流程 click -> get_state -> finished
        # -------------------------------------------------------------
        transport_envelopes = []

        def strict_transport(envelope):
            turn_idx = len(transport_envelopes) + 1
            label = f"Turn {turn_idx}"
            req = envelope["request"]
            messages = req.get("messages", [])

            # 严格传输校验：若非 system 首消息不是 user 或缺少来源 user，直接断言失败
            assert_strict_transcript(messages, label)
            transport_envelopes.append(envelope)

            if turn_idx == 1:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [
                        {
                            "id": "call_multi_1_click",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps({"x": 120, "y": 240}),
                            },
                        }
                    ],
                }
            elif turn_idx == 2:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [
                        {
                            "id": "call_multi_2_get_state",
                            "type": "function",
                            "function": {
                                "name": "get_state",
                                "arguments": json.dumps({"ocr": False}),
                            },
                        }
                    ],
                }
            else:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [
                        {
                            "id": "call_multi_3_finished",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({"content": "三轮全部完成"}),
                            },
                        }
                    ],
                }

        planner = VLMPlanner(model="scene.vlm.operation.primary", transport=strict_transport)

        # Turn 1
        obs1 = Observation.from_value({
            "state_id": "state_turn_1",
            "display": {"width": 1000, "height": 1000},
            "xml": "<hierarchy><node text=\"页面一：立即购买\" /></hierarchy>",
            "image_base64": "Y3JvcDFfaW1hZ2U=",
            "extra": {"display": {"width": 1000, "height": 1000}},
        })
        call1 = asyncio.run(planner.one_step_tool_call("执行三轮完整操作", obs1))
        self.assertEqual(call1.name, "click")
        self.assertEqual(call1.arguments["x"], 120)

        # Turn 2: 携带 click 成功结果与上一动作
        obs2 = Observation.from_value({
            "state_id": "state_turn_2",
            "display": {"width": 1000, "height": 1000},
            "xml": "<hierarchy><node text=\"页面二：订单详情列表\" /></hierarchy>",
            "image_base64": "Y3JvcDJfaW1hZ2U=",
            "extra": {
                "display": {"width": 1000, "height": 1000},
                "previous_action": {"tool": call1.name, "args": call1.arguments},
                "previous_action_result": "succeeded",
                "previous_action_error": None,
            },
        })
        call2 = asyncio.run(planner.one_step_tool_call("执行三轮完整操作", obs2))
        self.assertEqual(call2.name, "get_state")

        # Turn 3: 携带 get_state 成功结果
        obs3 = Observation.from_value({
            "state_id": "state_turn_3",
            "display": {"width": 1000, "height": 1000},
            "xml": "<hierarchy><node text=\"页面三：确认支付完成\" /></hierarchy>",
            "image_base64": "Y3JvcDNfaW1hZ2U=",
            "extra": {
                "display": {"width": 1000, "height": 1000},
                "previous_action": {"tool": call2.name, "args": call2.arguments},
                "previous_action_result": "succeeded",
                "previous_action_error": None,
            },
        })
        call3 = asyncio.run(planner.one_step_tool_call("执行三轮完整操作", obs3))
        self.assertEqual(call3.name, "finished")
        self.assertEqual(len(transport_envelopes), 3)

        # 验证第三轮请求中，各轮文字观测均保留在历史 user 消息中
        turn_3_req_msgs = transport_envelopes[2]["request"]["messages"]
        all_user_texts = []
        for m in turn_3_req_msgs:
            if m.get("role") == "user":
                content = m.get("content", [])
                if isinstance(content, list):
                    for part in content:
                        if isinstance(part, dict) and part.get("type") == "text":
                            all_user_texts.append(part.get("text", ""))
                elif isinstance(content, str):
                    all_user_texts.append(content)
        combined_user_text = "\n".join(all_user_texts)
        self.assertIn("页面一：立即购买", combined_user_text)
        self.assertIn("页面二：订单详情列表", combined_user_text)
        self.assertIn("页面三：确认支付完成", combined_user_text)

        # -------------------------------------------------------------
        # 路径 B：失败反馈分支动作受阻 -> 仍然保持 strict transcript 配对
        # -------------------------------------------------------------
        fail_envelopes = []

        def fail_strict_transport(envelope):
            turn_idx = len(fail_envelopes) + 1
            label = f"Fail Turn {turn_idx}"
            req = envelope["request"]
            messages = req.get("messages", [])

            assert_strict_transcript(messages, label)
            fail_envelopes.append(envelope)

            if turn_idx == 1:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [
                        {
                            "id": "call_fail_click_1",
                            "type": "function",
                            "function": {
                                "name": "click",
                                "arguments": json.dumps({"x": 500, "y": 600}),
                            },
                        }
                    ],
                }
            else:
                return {
                    "requested_model": envelope["model"],
                    "tool_calls": [
                        {
                            "id": "call_fail_fin_2",
                            "type": "function",
                            "function": {
                                "name": "finished",
                                "arguments": json.dumps({"content": "已知失败并妥善退出"}),
                            },
                        }
                    ],
                }

        planner_fail = VLMPlanner(model="scene.vlm.operation.primary", transport=fail_strict_transport)

        obs_fail_1 = Observation.from_value({
            "state_id": "state_fail_1",
            "display": {"width": 1000, "height": 1000},
            "xml": "<hierarchy><node text=\"提交按钮\" /></hierarchy>",
            "image_base64": "Y3JvcF9mYWlsMV9pbWFnZQ==",
            "extra": {"display": {"width": 1000, "height": 1000}},
        })
        call_f1 = asyncio.run(planner_fail.one_step_tool_call("处理失败分支任务", obs_fail_1))
        self.assertEqual(call_f1.name, "click")

        # 第二轮：反馈动作执行失败
        obs_fail_2 = Observation.from_value({
            "state_id": "state_fail_2",
            "display": {"width": 1000, "height": 1000},
            "xml": "<hierarchy><node text=\"弹窗阻挡提交\" /></hierarchy>",
            "image_base64": "Y3JvcF9mYWlsMl9pbWFnZQ==",
            "extra": {
                "display": {"width": 1000, "height": 1000},
                "previous_action": {"tool": call_f1.name, "args": call_f1.arguments},
                "previous_action_result": "failed",
                "previous_action_error": "action_swipe_obstructed",
            },
        })
        call_f2 = asyncio.run(planner_fail.one_step_tool_call("处理失败分支任务", obs_fail_2))
        self.assertEqual(call_f2.name, "finished")
        self.assertEqual(len(fail_envelopes), 2)

        # 验证第二轮请求中含有失败 tool 反馈且与 call_fail_click_1 配对
        turn_2_fail_msgs = fail_envelopes[1]["request"]["messages"]
        fail_tool_msg = next(
            m for m in turn_2_fail_msgs
            if m.get("role") == "tool" and m.get("tool_call_id") == "call_fail_click_1"
        )
        fail_tool_payload = json.loads(fail_tool_msg["content"])
        self.assertFalse(fail_tool_payload["success"])
        self.assertEqual(fail_tool_payload["error"], "action_swipe_obstructed")

if __name__ == "__main__":
    unittest.main()
