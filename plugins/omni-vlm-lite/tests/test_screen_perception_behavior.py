from __future__ import annotations

import asyncio
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

        # 与正式组包一致，规范动作契约随源码一并叠加。
        schema_name = "oob_canonical_actions.v1.json"
        schema_source = repo_root / "plugins" / "omni-vlm-lite" / "schemas" / "oob" / schema_name
        schema_target = cls.temp_dir / "schemas" / "oob" / schema_name
        schema_target.parent.mkdir(parents=True, exist_ok=True)
        schema_target.write_bytes(schema_source.read_bytes())

        sys.path.insert(0, str(cls.temp_dir))
        for mod in list(sys.modules.keys()):
            if mod.startswith("omniflow"):
                del sys.modules[mod]

    @classmethod
    def tearDownClass(cls) -> None:
        if cls.temp_dir is not None and cls.temp_dir.is_dir():
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
        # system(0) + (assistant, tool)*3 + user(7) = 8 条消息
        self.assertEqual(len(turn_4_msgs), 8)
        self.assertEqual(turn_4_msgs[0]["role"], "system")

        # 第 1 对：get_state
        self.assertEqual(turn_4_msgs[1]["role"], "assistant")
        self.assertEqual(turn_4_msgs[1]["tool_calls"][0]["id"], "call_1_get_state")
        self.assertEqual(turn_4_msgs[2]["role"], "tool")
        self.assertEqual(turn_4_msgs[2]["tool_call_id"], "call_1_get_state")
        get_state_res = json.loads(turn_4_msgs[2]["content"])
        self.assertTrue(get_state_res["success"])
        self.assertEqual(get_state_res["tool"], "get_state")

        # 第 2 对：click 2
        self.assertEqual(turn_4_msgs[3]["role"], "assistant")
        self.assertEqual(turn_4_msgs[3]["tool_calls"][0]["id"], "call_2_click_2")
        self.assertEqual(turn_4_msgs[4]["role"], "tool")
        self.assertEqual(turn_4_msgs[4]["tool_call_id"], "call_2_click_2")
        click_2_res = json.loads(turn_4_msgs[4]["content"])
        self.assertTrue(click_2_res["success"])
        self.assertEqual(click_2_res["tool"], "click")

        # 第 3 对：click 3
        self.assertEqual(turn_4_msgs[5]["role"], "assistant")
        self.assertEqual(turn_4_msgs[5]["tool_calls"][0]["id"], "call_3_click_3")
        self.assertEqual(turn_4_msgs[6]["role"], "tool")
        self.assertEqual(turn_4_msgs[6]["tool_call_id"], "call_3_click_3")
        click_3_res = json.loads(turn_4_msgs[6]["content"])
        self.assertTrue(click_3_res["success"])
        self.assertEqual(click_3_res["tool"], "click")

        # 最新快照仅作为最后的 user 消息呈现，不累积旧图
        self.assertEqual(turn_4_msgs[7]["role"], "user")

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
        self.assertEqual(len(turn_2_msgs), 5)
        self.assertEqual(turn_2_msgs[0]["role"], "system")

        # 单条 assistant 包含两个原生 tool_calls
        self.assertEqual(turn_2_msgs[1]["role"], "assistant")
        calls = turn_2_msgs[1]["tool_calls"]
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0]["id"], "call_click_a")
        self.assertEqual(calls[1]["id"], "call_click_b")

        # 首个 tool A 真实成功
        self.assertEqual(turn_2_msgs[2]["tool_call_id"], "call_click_a")
        res_a = json.loads(turn_2_msgs[2]["content"])
        self.assertTrue(res_a["success"])
        self.assertEqual(res_a["tool"], "click")

        # 尾部 tool B 明确未执行错误
        self.assertEqual(turn_2_msgs[3]["tool_call_id"], "call_click_b")
        res_b = json.loads(turn_2_msgs[3]["content"])
        self.assertFalse(res_b["success"])
        self.assertFalse(res_b["executed"])
        self.assertEqual(res_b["error"], "not_executed_observation_refresh_required")
        self.assertEqual(res_b["tool"], "click")

        # 结尾的 user 消息已是包含 C 的新屏幕状态
        self.assertEqual(turn_2_msgs[4]["role"], "user")
        self.assertIn("btn_c", str(turn_2_msgs[4]["content"]))

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
        self.assertEqual(len(turn_2_msgs), 5)

        self.assertEqual(turn_2_msgs[2]["tool_call_id"], "call_fail_a")
        res_a = json.loads(turn_2_msgs[2]["content"])
        self.assertFalse(res_a["success"])
        self.assertIn("element_click_intercepted", res_a.get("error", ""))

        self.assertEqual(turn_2_msgs[3]["tool_call_id"], "call_tail_b")
        res_b = json.loads(turn_2_msgs[3]["content"])
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
        self.assertEqual(turn_2_msgs[2]["tool_call_id"], "call_get_state_1")
        res_state = json.loads(turn_2_msgs[2]["content"])
        self.assertTrue(res_state["success"])
        self.assertEqual(res_state["tool"], "get_state")
        self.assertEqual(res_state["ocr"]["status"], "ready")

        self.assertEqual(turn_2_msgs[3]["tool_call_id"], "call_click_tail_2")
        res_click = json.loads(turn_2_msgs[3]["content"])
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
        self.assertEqual(turn_2_msgs[3]["tool_call_id"], "call_fin_tail")
        tail_res = json.loads(turn_2_msgs[3]["content"])
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
if __name__ == "__main__":
    unittest.main()
