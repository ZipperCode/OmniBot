from __future__ import annotations

import collections
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

tests_dir = Path(__file__).resolve().parent
if str(tests_dir) not in sys.path:
    sys.path.insert(0, str(tests_dir))

import test_screen_perception_behavior as perception_module


class AppExperienceBehaviorTest(unittest.TestCase):
    """应用经验存储、候选提取与反馈状态机行为测试套件。

    复用既有 ScreenPerceptionBehaviorTest 加载 fixture，不继承其 TestCase 避免重复执行原用例。
    """

    @classmethod
    def setUpClass(cls) -> None:
        perception_module.ScreenPerceptionBehaviorTest.setUpClass()

    @classmethod
    def tearDownClass(cls) -> None:
        perception_module.ScreenPerceptionBehaviorTest.tearDownClass()

    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp(prefix="app_exp_store_test_"))
        self.store_path = self.test_dir / "app-experiences.json"

    def tearDown(self) -> None:
        if self.test_dir.exists():
            shutil.rmtree(self.test_dir, ignore_errors=True)

    def _make_xml(self, nodes: list[dict[str, str]]) -> str:
        xml_parts = ['<?xml version="1.0" encoding="utf-8"?><hierarchy rotation="0">']
        for n in nodes:
            res_id = n.get("resource-id", "")
            cls_name = n.get("class", "android.widget.Button")
            txt = n.get("text", "")
            xml_parts.append(
                f'<node resource-id="{res_id}" class="{cls_name}" text="{txt}" '
                f'visible="true" displayed="true" password="false" />'
            )
        xml_parts.append("</hierarchy>")
        return "".join(xml_parts)

    def test_validators(self) -> None:
        from omniflow.runtime.app_experience import (
            is_valid_activity_name,
            is_valid_class_name,
            is_valid_package_name,
            is_valid_resource_id,
        )

        self.assertTrue(is_valid_resource_id("com.example.app:id/submit_button"))
        self.assertFalse(is_valid_resource_id("submit_button"))
        self.assertFalse(is_valid_resource_id("com.example.app:string/submit"))
        self.assertFalse(is_valid_resource_id("a" * 300))

        self.assertTrue(is_valid_package_name("com.example.app"))
        self.assertFalse(is_valid_package_name("com"))
        self.assertFalse(is_valid_package_name("123.example"))

        self.assertTrue(is_valid_class_name("android.widget.Button"))
        self.assertTrue(is_valid_class_name("CustomView"))
        self.assertFalse(is_valid_class_name("android..Button"))

        self.assertTrue(is_valid_activity_name(""))
        self.assertTrue(is_valid_activity_name(None))
        self.assertTrue(is_valid_activity_name("com.example.app.MainActivity"))
        self.assertFalse(is_valid_activity_name("Main Activity"))

    def test_real_file_persistence_and_reload(self) -> None:
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id

        store = AppExperienceStore(self.store_path)
        self.assertEqual(store.revision, 0)
        self.assertEqual(store.items, [])
        self.assertEqual(store.processed_run_ids, [])

        rule = {
            "action": "click",
            "target_resource_id": "com.example.app:id/login_btn",
            "target_class": "android.widget.Button",
            "after_activity": "com.example.app.HomeActivity",
        }
        item_id = compute_experience_id("com.example.app", "com.example.app.LoginActivity", rule)
        item = {
            "id": item_id,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.LoginActivity",
            "status": "trial",
            "enabled": True,
            "source_run_id": "run_init",
            "source_step_index": 0,
            "rule": rule,
            "successful_run_ids": [],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": None,
        }
        store.items.append(item)
        store.revision = 1
        store.processed_run_ids.append("run_init")
        store._save()

        self.assertTrue(self.store_path.is_file())

        store2 = AppExperienceStore(self.store_path)
        self.assertEqual(store2.revision, 1)
        self.assertEqual(len(store2.items), 1)
        self.assertEqual(store2.items[0]["id"], item_id)
        self.assertEqual(store2.processed_run_ids, ["run_init"])

    def test_corrupt_store_raises_and_does_not_overwrite(self) -> None:
        from omniflow.runtime.app_experience import AppExperienceStore

        bad_data = {
            "schema_version": "omniflow.app-experiences.v1",
            "revision": True,  # 非法 bool
            "items": [
                {
                    "id": "123456789012345678901234",
                    "package_name": "com.example.app",
                    "activity_name": "com.example.app.MainActivity",
                    "status": "unknown_status",
                    "enabled": "yes",
                    "source_run_id": "run_1",
                    "source_step_index": 0,
                    "rule": {"text": "自由文本非法字段"},
                    "successful_run_ids": [],
                    "failed_run_ids": [],
                    "consecutive_failures": 0,
                    "previous_verified_id": None,
                }
            ],
            "processed_run_ids": [],
        }
        raw_bytes = json.dumps(bad_data).encode("utf-8")
        self.store_path.write_bytes(raw_bytes)

        with self.assertRaises(ValueError) as cm:
            AppExperienceStore(self.store_path)
        self.assertIn("app_experience_store_invalid", str(cm.exception))

        # 文件内容绝不覆写，原 bytes 严格保持一致
        self.assertEqual(self.store_path.read_bytes(), raw_bytes)

    def test_derive_candidates_ignores_input_text_values_and_requires_unique_id(self) -> None:
        from omniflow.core.model import Observation
        from omniflow.runtime.app_experience import derive_candidates

        xml_before = self._make_xml([
            {"resource-id": "com.example.app:id/input_box", "class": "android.widget.EditText", "text": ""},
        ])
        xml_after = self._make_xml([
            {"resource-id": "com.example.app:id/input_box", "class": "android.widget.EditText", "text": "sensitive_password"},
        ])

        states = {
            "state_before": Observation(
                package_name="com.example.app",
                activity_name="com.example.app.MainActivity",
                xml=xml_before,
            ),
            "state_after": Observation(
                package_name="com.example.app",
                activity_name="com.example.app.MainActivity",
                xml=xml_after,
            ),
        }

        run_log = {
            "run_id": "run_test_input",
            "finished_at_ms": 100000,
            "success": True,
            "diagnostics": {
                "finished_evidence": {"status": "matched"},
            },
            "steps": [
                {
                    "step_index": 0,
                    "action": {
                        "action_type": "input_text",
                        "text": "sensitive_password",
                    },
                    "observation": {
                        "auxiliaries": {
                            "state_id": "state_before",
                            "package_name": "com.example.app",
                            "activity_name": "com.example.app.MainActivity",
                        }
                    },
                    "next_observation": {
                        "auxiliaries": {
                            "state_id": "state_after",
                            "package_name": "com.example.app",
                            "activity_name": "com.example.app.MainActivity",
                        }
                    },
                    "result": {
                        "success": True,
                    },
                    "metadata": {
                        "transfer": {
                            "semantic_grounding": {
                                "status": "resolved",
                                "node_resource_id": "com.example.app:id/input_box",
                            }
                        },
                        "action_result": {
                            "diagnostics": {
                                "guard_status": "target_confirmed",
                                "target_resource_id": "com.example.app:id/input_box",
                                "target_class": "android.widget.EditText",
                            }
                        },
                    },
                }
            ],
        }

        candidates = derive_candidates(run_log, states)
        self.assertEqual(len(candidates), 1)
        cand = candidates[0]
        self.assertEqual(cand["rule"]["action"], "input_text")
        self.assertEqual(cand["rule"]["target_resource_id"], "com.example.app:id/input_box")
        self.assertEqual(cand["rule"]["target_class"], "android.widget.EditText")
        self.assertEqual(cand["rule"]["after_activity"], "")

        # 绝不保存用户输入的密码文本或坐标
        cand_json_str = json.dumps(cand)
        self.assertNotIn("sensitive_password", cand_json_str)
        self.assertNotIn("coordinates", cand_json_str)

    def test_select_shadowing_and_unique_visible_ids(self) -> None:
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id

        store = AppExperienceStore(self.store_path)

        rule = {
            "action": "click",
            "target_resource_id": "com.example.app:id/submit_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        id1 = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule)
        old_verified = {
            "id": id1,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "verified",
            "enabled": True,
            "source_run_id": "run_0",
            "source_step_index": 0,
            "rule": rule,
            "successful_run_ids": ["run_prev1", "run_prev2"],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": None,
        }

        rule2 = dict(rule, target_class="com.example.app.MyCustomButton")
        id2 = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule2)
        new_trial = {
            "id": id2,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "trial",
            "enabled": True,
            "source_run_id": "run_1",
            "source_step_index": 0,
            "rule": rule2,
            "successful_run_ids": [],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": id1,
        }

        store.items.extend([old_verified, new_trial])
        store._save()

        # 1. 目标可见且唯一：启用的 trial 遮蔽旧 verified
        selected = store.select(
            "com.example.app",
            "com.example.app.MainActivity",
            ["com.example.app:id/submit_btn"],
        )
        self.assertEqual(len(selected), 1)
        self.assertEqual(selected[0]["id"], id2)

        # 2. 目标在当前页面不可见：trial 依然遮蔽 verified，旧 verified 绝不回注
        selected_hidden = store.select(
            "com.example.app",
            "com.example.app.MainActivity",
            ["com.example.app:id/other_btn"],
        )
        self.assertEqual(selected_hidden, [])

        # 3. 目标重复出现（非唯一）：不被选取
        selected_dup = store.select(
            "com.example.app",
            "com.example.app.MainActivity",
            ["com.example.app:id/submit_btn", "com.example.app:id/submit_btn"],
        )
        self.assertEqual(selected_dup, [])

        # 4. 当 trial 被禁用时，disabled trial 不遮蔽 verified
        new_trial["enabled"] = False
        selected_fallback = store.select(
            "com.example.app",
            "com.example.app.MainActivity",
            ["com.example.app:id/submit_btn"],
        )
        self.assertEqual(len(selected_fallback), 1)
        self.assertEqual(selected_fallback[0]["id"], id1)

    def test_two_successes_upgrade_trial_to_verified(self) -> None:
        from omniflow.core.model import Observation
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id

        store = AppExperienceStore(self.store_path)

        rule = {
            "action": "click",
            "target_resource_id": "com.example.app:id/target_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        item_id = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule)
        trial_item = {
            "id": item_id,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "trial",
            "enabled": True,
            "source_run_id": "run_source",
            "source_step_index": 0,
            "rule": rule,
            "successful_run_ids": [],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": None,
        }
        store.items.append(trial_item)
        store._save()

        xml1 = self._make_xml([{"resource-id": "com.example.app:id/target_btn", "class": "android.widget.Button"}])
        xml2 = self._make_xml([{"resource-id": "com.example.app:id/target_btn", "class": "android.widget.Button"}, {"text": "changed"}])
        states = {
            "s_before": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml1),
            "s_after": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml2),
        }

        def make_run(run_id: str, action_type: str = "click") -> dict:
            return {
                "run_id": run_id,
                "finished_at_ms": 123456,
                "success": True,
                "diagnostics": {"finished_evidence": {"status": "matched"}},
                "steps": [
                    {
                        "step_index": 0,
                        "action": {"action_type": action_type},
                        "observation": {"auxiliaries": {"state_id": "s_before", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                        "next_observation": {"auxiliaries": {"state_id": "s_after", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                        "result": {"success": True},
                        "metadata": {
                            "app_experience_ids": [item_id],
                            "transfer": {"semantic_grounding": {"status": "resolved"}},
                            "action_result": {
                                "diagnostics": {
                                    "guard_status": "target_confirmed",
                                    "target_resource_id": "com.example.app:id/target_btn",
                                    "target_class": "android.widget.Button",
                                }
                            },
                        },
                    }
                ],
            }

        # 来源 run 不计成功
        res0 = store.update_from_run(make_run("run_source"), states)
        self.assertTrue(res0["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == item_id)
        self.assertEqual(cur["successful_run_ids"], [])
        self.assertEqual(cur["status"], "trial")

        # 反例：动作不匹配（input_text 对 click 规则），不计成功
        res_mismatch = store.update_from_run(make_run("run_mismatch", action_type="input_text"), states)
        self.assertTrue(res_mismatch["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == item_id)
        self.assertEqual(cur["successful_run_ids"], [])

        # 第 1 次后续运行成功
        res1 = store.update_from_run(make_run("run_success_1"), states)
        self.assertTrue(res1["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == item_id)
        self.assertEqual(cur["successful_run_ids"], ["run_success_1"])
        self.assertEqual(cur["status"], "trial")

        # 第 2 次后续运行成功：转正为 verified
        res2 = store.update_from_run(make_run("run_success_2"), states)
        self.assertTrue(res2["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == item_id)
        self.assertEqual(cur["successful_run_ids"], ["run_success_1", "run_success_2"])
        self.assertEqual(cur["status"], "verified")

    def test_two_failures_retires_trial_and_restores_old_verified(self) -> None:
        from omniflow.core.model import Observation
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id

        store = AppExperienceStore(self.store_path)

        rule_old = {
            "action": "click",
            "target_resource_id": "com.example.app:id/btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        old_id = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule_old)
        old_item = {
            "id": old_id,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "retired",
            "enabled": True,
            "source_run_id": "run_old",
            "source_step_index": 0,
            "rule": rule_old,
            "successful_run_ids": ["run_old_1", "run_old_2"],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": None,
        }

        rule_new = dict(rule_old, target_class="com.example.app.NewButton")
        new_id = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule_new)
        new_item = {
            "id": new_id,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "trial",
            "enabled": True,
            "source_run_id": "run_new",
            "source_step_index": 0,
            "rule": rule_new,
            "successful_run_ids": [],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": old_id,
        }
        store.items.extend([old_item, new_item])
        store._save()

        xml_btn = self._make_xml([{"resource-id": "com.example.app:id/btn", "class": "com.example.app.NewButton"}])
        states = {
            "s_fail": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml_btn),
        }

        def make_failed_run(run_id: str, action_type: str = "click", state_id: str = "s_fail") -> dict:
            return {
                "run_id": run_id,
                "finished_at_ms": 200000,
                "success": False,
                "steps": [
                    {
                        "step_index": 0,
                        "action": {"action_type": action_type},
                        "observation": {"auxiliaries": {"state_id": state_id, "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                        "result": {
                            "success": False,
                            "error": "action_target_missing",
                        },
                        "metadata": {
                            "app_experience_ids": [new_id],
                            "action_result": {
                                "diagnostics": {"reason": "action_target_missing"}
                            },
                        },
                    }
                ],
            }

        # 反例 1：states={} 无 before state，中性不罚
        store.update_from_run(make_failed_run("run_no_state", state_id="s_missing"), {})
        reloaded = AppExperienceStore(self.store_path)
        cur_new = next(it for it in reloaded.items if it["id"] == new_id)
        self.assertEqual(cur_new["consecutive_failures"], 0)

        # 反例 2：动作不匹配（input_text 对 click 规则），中性不罚
        store.update_from_run(make_failed_run("run_action_mismatch", action_type="input_text"), states)
        reloaded = AppExperienceStore(self.store_path)
        cur_new = next(it for it in reloaded.items if it["id"] == new_id)
        self.assertEqual(cur_new["consecutive_failures"], 0)

        # 第 1 次有效归因失败
        store.update_from_run(make_failed_run("run_fail_1"), states)
        reloaded = AppExperienceStore(self.store_path)
        cur_new = next(it for it in reloaded.items if it["id"] == new_id)
        cur_old = next(it for it in reloaded.items if it["id"] == old_id)
        self.assertEqual(cur_new["consecutive_failures"], 1)
        self.assertEqual(cur_new["status"], "trial")
        self.assertEqual(cur_old["status"], "retired")

        # 中性取消运行：不打断失败连击
        cancel_run = {
            "run_id": "run_cancel",
            "finished_at_ms": 250000,
            "status": "cancelled",
            "done_reason": "cancelled",
            "steps": [],
        }
        store.update_from_run(cancel_run, {})
        reloaded = AppExperienceStore(self.store_path)
        cur_new = next(it for it in reloaded.items if it["id"] == new_id)
        self.assertEqual(cur_new["consecutive_failures"], 1)

        # 第 2 次有效归因失败：trial 退役，仍启用的旧 verified 恢复
        store.update_from_run(make_failed_run("run_fail_2"), states)
        reloaded = AppExperienceStore(self.store_path)
        cur_new = next(it for it in reloaded.items if it["id"] == new_id)
        cur_old = next(it for it in reloaded.items if it["id"] == old_id)
        self.assertEqual(cur_new["consecutive_failures"], 2)
        self.assertEqual(cur_new["status"], "retired")
        self.assertEqual(cur_old["status"], "verified")

    def test_idempotency_and_processed_run_ids_cap(self) -> None:
        from omniflow.runtime.app_experience import AppExperienceStore

        store = AppExperienceStore(self.store_path)

        run = {
            "run_id": "run_repeat",
            "finished_at_ms": 300000,
            "status": "cancelled",
            "done_reason": "cancelled",
        }
        res1 = store.update_from_run(run, {})
        self.assertTrue(res1["success"])
        self.assertTrue(res1.get("processed", False))

        res2 = store.update_from_run(run, {})
        self.assertTrue(res2["success"])
        self.assertTrue(res2.get("idempotent", False))

        for i in range(210):
            store.update_from_run({
                "run_id": f"run_seq_{i}",
                "finished_at_ms": 300000 + i,
                "status": "cancelled",
                "done_reason": "cancelled",
            }, {})

        reloaded = AppExperienceStore(self.store_path)
        self.assertEqual(len(reloaded.processed_run_ids), 200)

    def test_set_enabled_and_revision_conflict(self) -> None:
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id

        store = AppExperienceStore(self.store_path)
        rule = {
            "action": "click",
            "target_resource_id": "com.example.app:id/toggle_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        exp_id = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule)
        store.items.append({
            "id": exp_id,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "trial",
            "enabled": True,
            "source_run_id": "run_init",
            "source_step_index": 0,
            "rule": rule,
            "successful_run_ids": [],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": None,
        })
        store.revision = 5
        store._save()

        # 版本号冲突拒绝
        conflict_res = store.set_enabled(exp_id, False, expected_revision=4)
        self.assertFalse(conflict_res["success"])
        self.assertEqual(conflict_res["error_code"], "app_experience_revision_conflict")

        # 成功禁用
        ok_res = store.set_enabled(exp_id, False, expected_revision=5)
        self.assertTrue(ok_res["success"])
        self.assertFalse(ok_res["item"]["enabled"])

        reloaded = AppExperienceStore(self.store_path)
        self.assertEqual(reloaded.revision, 6)
        cur = next(it for it in reloaded.items if it["id"] == exp_id)
        self.assertFalse(cur["enabled"])

    def test_capacity_limits(self) -> None:
        from omniflow.core.model import Observation
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id

        store = AppExperienceStore(self.store_path)

        slot_items = []
        rule_base = {
            "action": "click",
            "target_resource_id": "com.example.app:id/cap_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        for i in range(8):
            rule_v = dict(rule_base, target_class=f"android.widget.ButtonV{i}")
            exp_id = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule_v)
            slot_items.append({
                "id": exp_id,
                "package_name": "com.example.app",
                "activity_name": "com.example.app.MainActivity",
                "status": "retired",
                "enabled": True,
                "source_run_id": f"run_v{i}",
                "source_step_index": 0,
                "rule": rule_v,
                "successful_run_ids": [],
                "failed_run_ids": [],
                "consecutive_failures": 0,
                "previous_verified_id": None,
            })
        store.items = slot_items
        store._save()

        xml1 = self._make_xml([{"resource-id": "com.example.app:id/cap_btn", "class": "android.widget.ButtonV9"}])
        xml2 = self._make_xml([{"resource-id": "com.example.app:id/cap_btn", "class": "android.widget.ButtonV9"}, {"text": "chg"}])
        states = {
            "b": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml1),
            "a": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml2),
        }
        run = {
            "run_id": "run_v9",
            "finished_at_ms": 400000,
            "success": True,
            "diagnostics": {"finished_evidence": {"status": "matched"}},
            "steps": [
                {
                    "step_index": 0,
                    "action": {"action_type": "click"},
                    "observation": {"auxiliaries": {"state_id": "b", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "next_observation": {"auxiliaries": {"state_id": "a", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "result": {"success": True},
                    "metadata": {
                        "transfer": {"semantic_grounding": {"status": "resolved"}},
                        "action_result": {
                            "diagnostics": {
                                "guard_status": "target_confirmed",
                                "target_resource_id": "com.example.app:id/cap_btn",
                                "target_class": "android.widget.ButtonV9",
                            }
                        },
                    },
                }
            ],
        }

        res = store.update_from_run(run, states)
        self.assertTrue(res["success"])
        self.assertTrue(res.get("capacity_reached", False))
        reloaded = AppExperienceStore(self.store_path)
        self.assertEqual(len(reloaded.items), 8)

    def test_no_progress_detected_retires_used_step_without_effect_and_preserves_neutral_control(self) -> None:
        """验证无效果步骤在无进展终态时正确归因失败并最终退役，且对延迟发生页面变化的步骤保持中性对照"""
        from omniflow.core.model import Observation
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id

        store = AppExperienceStore(self.store_path)
        rule = {
            "action": "click",
            "target_resource_id": "com.example.app:id/submit_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        exp_id = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule)
        store.items.append({
            "id": exp_id,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "trial",
            "enabled": True,
            "source_run_id": "run_source",
            "source_step_index": 0,
            "rule": rule,
            "successful_run_ids": [],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": None,
        })
        store._save()

        xml_btn = self._make_xml([{"resource-id": "com.example.app:id/submit_btn", "class": "android.widget.Button"}])
        xml_loaded = self._make_xml([
            {"resource-id": "com.example.app:id/submit_btn", "class": "android.widget.Button"},
            {"text": "loaded_content"},
        ])
        states = {
            "s_init": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml_btn),
            "s_same": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml_btn),
            "s_loaded": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml_loaded),
        }

        # 1. 第 1 次归因失败：派发成功但无效果（前后 XML/pkg/act 完全相同），随后 no_progress_detected 终止
        run_fail_1 = {
            "run_id": "run_noprog_1",
            "finished_at_ms": 100000,
            "success": False,
            "done_reason": "no_progress_detected",
            "final_state_id": "s_same",
            "diagnostics": {
                "done_reason": "no_progress_detected",
                "planner": {
                    "app_experience_failure_step_indexes": [0],
                },
            },
            "steps": [
                {
                    "step_index": 0,
                    "action": {"action_type": "click"},
                    "observation": {"auxiliaries": {"state_id": "s_init", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "next_observation": {"auxiliaries": {"state_id": "s_same", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "result": {"success": True},
                    "metadata": {
                        "app_experience_ids": [exp_id],
                        "action_result": {
                            "diagnostics": {
                                "guard_status": "target_confirmed",
                                "target_resource_id": "com.example.app:id/submit_btn",
                                "target_class": "android.widget.Button",
                            }
                        },
                    },
                },
                {
                    "step_index": 1,
                    "action": {"action_type": "get_state"},
                    "observation": {"auxiliaries": {"state_id": "s_same", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "next_observation": {"auxiliaries": {"state_id": "s_same", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "result": {"success": True},
                },
            ],
        }
        res1 = store.update_from_run(run_fail_1, states)
        self.assertTrue(res1["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == exp_id)
        self.assertEqual(cur["consecutive_failures"], 1)
        self.assertEqual(cur["status"], "trial")

        # 2. 中性对照：点击后虽即刻无变化，但后续加载导致页面实质变化，随后另行停滞终止 -> 绝不回溯惩罚原经验
        run_neutral = {
            "run_id": "run_neutral_delayed",
            "finished_at_ms": 200000,
            "success": False,
            "done_reason": "no_progress_detected",
            "final_state_id": "s_loaded",
            "diagnostics": {
                "done_reason": "no_progress_detected",
                "planner": {
                    "app_experience_failure_step_indexes": [],
                },
            },
            "steps": [
                {
                    "step_index": 0,
                    "action": {"action_type": "click"},
                    "observation": {"auxiliaries": {"state_id": "s_init", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "next_observation": {"auxiliaries": {"state_id": "s_same", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "result": {"success": True},
                    "metadata": {
                        "app_experience_ids": [exp_id],
                        "action_result": {
                            "diagnostics": {
                                "guard_status": "target_confirmed",
                                "target_resource_id": "com.example.app:id/submit_btn",
                                "target_class": "android.widget.Button",
                            }
                        },
                    },
                },
                {
                    "step_index": 1,
                    "action": {"action_type": "get_state"},
                    "observation": {"auxiliaries": {"state_id": "s_same", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "next_observation": {"auxiliaries": {"state_id": "s_loaded", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "result": {"success": True},
                },
                {
                    "step_index": 2,
                    "action": {"action_type": "get_state"},
                    "observation": {"auxiliaries": {"state_id": "s_loaded", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "next_observation": {"auxiliaries": {"state_id": "s_loaded", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "result": {"success": True},
                },
            ],
        }
        res_neutral = store.update_from_run(run_neutral, states)
        self.assertTrue(res_neutral["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == exp_id)
        self.assertEqual(cur["consecutive_failures"], 1)
        self.assertEqual(cur["status"], "trial")


        # 2b. 中性对照：若 diagnostics 缺失 app_experience_failure_step_indexes，保持中性不罚
        run_neutral_missing = {
            "run_id": "run_neutral_missing_indexes",
            "finished_at_ms": 250000,
            "success": False,
            "done_reason": "no_progress_detected",
            "diagnostics": {"done_reason": "no_progress_detected"},
            "steps": [run_fail_1["steps"][0]],
        }
        res_missing = store.update_from_run(run_neutral_missing, states)
        self.assertTrue(res_missing["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == exp_id)
        self.assertEqual(cur["consecutive_failures"], 1)
        self.assertEqual(cur["status"], "trial")
        # 3. 第 2 次归因失败：再次无效果导致 no_progress_detected，累积达到 2 次失败而退役
        run_fail_2 = dict(run_fail_1, run_id="run_noprog_2", finished_at_ms=300000)
        res2 = store.update_from_run(run_fail_2, states)
        self.assertTrue(res2["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == exp_id)
        self.assertEqual(cur["consecutive_failures"], 2)
        self.assertEqual(cur["status"], "retired")

    def test_transport_and_planner_failures_are_neutral_and_preserve_experience_status(self) -> None:
        """验证规划器与传输层异常作为中性运行，不误归因步骤失败，不泄露异常凭据"""
        from omniflow.core.model import Observation
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id

        store = AppExperienceStore(self.store_path)
        rule = {
            "action": "click",
            "target_resource_id": "com.example.app:id/submit_btn",
            "target_class": "android.widget.Button",
            "after_activity": "",
        }
        exp_id = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule)
        store.items.append({
            "id": exp_id,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "trial",
            "enabled": True,
            "source_run_id": "run_source",
            "source_step_index": 0,
            "rule": rule,
            "successful_run_ids": [],
            "failed_run_ids": ["run_legit_fail_1"],
            "consecutive_failures": 1,
            "previous_verified_id": None,
        })
        store._save()

        xml_btn = self._make_xml([{"resource-id": "com.example.app:id/submit_btn", "class": "android.widget.Button"}])
        states = {
            "s_btn": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml_btn),
        }

        # 1. 步骤虽有关联但终态发生规划器/传输中断（error_code VLM_PLANNER_FAILED，failure_kind planner_failed）
        run_transport = {
            "run_id": "run_transport_fail",
            "finished_at_ms": 200000,
            "success": False,
            "status": "failed",
            "done_reason": "error",
            "error_code": "VLM_PLANNER_FAILED",
            "error_message": "vlm_planner_failed:ConnectionError('SYNTHETIC_TRANSPORT_CONNECTION_LOST')",
            "diagnostics": {
                "planner": {
                    "failure_kind": "planner_failed",
                },
            },
            "steps": [
                {
                    "step_index": 0,
                    "action": {"action_type": "click"},
                    "observation": {"auxiliaries": {"state_id": "s_btn", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                    "result": {"success": False, "error": "action_target_missing"},
                    "metadata": {
                        "app_experience_ids": [exp_id],
                        "action_result": {"diagnostics": {"reason": "action_target_missing"}},
                    },
                }
            ],
        }
        res = store.update_from_run(run_transport, states)
        self.assertTrue(res["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == exp_id)
        # 保持中性：连击仍为 1，状态仍为 trial，不被退役
        self.assertEqual(cur["consecutive_failures"], 1)
        self.assertEqual(cur["status"], "trial")
        self.assertNotIn("run_transport_fail", cur["failed_run_ids"])

        # 检查 sidecar 未泄露任何模型/异常文案
        with open(self.store_path, "r", encoding="utf-8") as f:
            content = f.read()
            self.assertNotIn("SYNTHETIC_TRANSPORT_CONNECTION_LOST", content)
            self.assertNotIn("ConnectionError", content)

        # 2. 传输明确 done_reason="transport"
        run_transport_done = {
            "run_id": "run_transport_done",
            "finished_at_ms": 250000,
            "success": False,
            "status": "failed",
            "done_reason": "transport",
            "steps": [],
        }
        res2 = store.update_from_run(run_transport_done, states)
        self.assertTrue(res2["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur = next(it for it in reloaded.items if it["id"] == exp_id)
        self.assertEqual(cur["consecutive_failures"], 1)
        self.assertEqual(cur["status"], "trial")

    def test_retired_restart_clears_old_predecessor_and_prevents_stale_resurrection(self) -> None:
        """验证退役版本重新启用时清除历史旧引用，避免两度退役后复活早先退役或已禁用的版本"""
        from omniflow.core.model import Observation
        from omniflow.runtime.app_experience import AppExperienceStore, compute_experience_id

        store = AppExperienceStore(self.store_path)

        rule_a = {
            "action": "click",
            "target_resource_id": "com.example.app:id/submit_btn",
            "target_class": "android.widget.Button",
            "after_activity": "com.example.app.ActivityA",
        }
        rule_b = {
            "action": "click",
            "target_resource_id": "com.example.app:id/submit_btn",
            "target_class": "android.widget.Button",
            "after_activity": "com.example.app.ActivityB",
        }
        id_a = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule_a)
        id_b = compute_experience_id("com.example.app", "com.example.app.MainActivity", rule_b)

        # A 初始为 verified
        item_a = {
            "id": id_a,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "verified",
            "enabled": True,
            "source_run_id": "run_a_src",
            "source_step_index": 0,
            "rule": rule_a,
            "successful_run_ids": ["run_a_1", "run_a_2"],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": None,
        }
        # B 初始为 trial，引用了 A
        item_b = {
            "id": id_b,
            "package_name": "com.example.app",
            "activity_name": "com.example.app.MainActivity",
            "status": "trial",
            "enabled": True,
            "source_run_id": "run_b_src",
            "source_step_index": 0,
            "rule": rule_b,
            "successful_run_ids": [],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": id_a,
        }
        store.items.extend([item_a, item_b])
        store._save()

        xml_btn = self._make_xml([{"resource-id": "com.example.app:id/submit_btn", "class": "android.widget.Button"}])
        states = {
            "s_btn": Observation(package_name="com.example.app", activity_name="com.example.app.MainActivity", xml=xml_btn),
            "s_done_b": Observation(package_name="com.example.app", activity_name="com.example.app.ActivityB", xml=xml_btn),
        }

        def make_b_success_run(run_id: str) -> dict:
            return {
                "run_id": run_id,
                "finished_at_ms": 100000,
                "success": True,
                "diagnostics": {"finished_evidence": {"status": "matched"}},
                "steps": [
                    {
                        "step_index": 0,
                        "action": {"action_type": "click"},
                        "observation": {"auxiliaries": {"state_id": "s_btn", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                        "next_observation": {"auxiliaries": {"state_id": "s_done_b", "package_name": "com.example.app", "activity_name": "com.example.app.ActivityB"}},
                        "result": {"success": True},
                        "metadata": {
                            "app_experience_ids": [id_b],
                            "transfer": {"semantic_grounding": {"status": "resolved"}},
                            "action_result": {
                                "diagnostics": {
                                    "guard_status": "target_confirmed",
                                    "target_resource_id": "com.example.app:id/submit_btn",
                                    "target_class": "android.widget.Button",
                                }
                            },
                        },
                    }
                ],
            }

        def make_b_fail_run(run_id: str) -> dict:
            return {
                "run_id": run_id,
                "finished_at_ms": 200000,
                "success": False,
                "steps": [
                    {
                        "step_index": 0,
                        "action": {"action_type": "click"},
                        "observation": {"auxiliaries": {"state_id": "s_btn", "package_name": "com.example.app", "activity_name": "com.example.app.MainActivity"}},
                        "result": {"success": False, "error": "action_target_missing"},
                        "metadata": {
                            "app_experience_ids": [id_b],
                            "action_result": {"diagnostics": {"reason": "action_target_missing"}},
                        },
                    }
                ],
            }

        # 1. B 经过 2 次使用转正为 verified，同时 A 转为 retired
        store.update_from_run(make_b_success_run("run_b_succ_1"), states)
        store.update_from_run(make_b_success_run("run_b_succ_2"), states)
        reloaded = AppExperienceStore(self.store_path)
        cur_a = next(it for it in reloaded.items if it["id"] == id_a)
        cur_b = next(it for it in reloaded.items if it["id"] == id_b)
        self.assertEqual(cur_b["status"], "verified")
        self.assertEqual(cur_a["status"], "retired")

        # 2. B 自身经历 2 次失败退役 -> 此时 A 和 B 均处于 retired
        store.update_from_run(make_b_fail_run("run_b_fail_1"), states)
        store.update_from_run(make_b_fail_run("run_b_fail_2"), states)
        reloaded = AppExperienceStore(self.store_path)
        cur_a = next(it for it in reloaded.items if it["id"] == id_a)
        cur_b = next(it for it in reloaded.items if it["id"] == id_b)
        self.assertEqual(cur_b["status"], "retired")
        self.assertEqual(cur_a["status"], "retired")

        # 3. 用户重新启用退役版本 B：由于当前槽内无 verified 版本，B 的 previous_verified_id 必须被置空
        res_enable = store.set_enabled(id_b, True, reloaded.revision)
        self.assertTrue(res_enable["success"])
        reloaded = AppExperienceStore(self.store_path)
        cur_b = next(it for it in reloaded.items if it["id"] == id_b)
        self.assertEqual(cur_b["status"], "trial")
        self.assertIsNone(cur_b["previous_verified_id"])

        # 4. B 再次经历 2 次失败退役：由于 previous_verified_id 为 None，早已 retired 的 A 绝不会被复活
        store.update_from_run(make_b_fail_run("run_b_fail_3"), states)
        store.update_from_run(make_b_fail_run("run_b_fail_4"), states)
        reloaded = AppExperienceStore(self.store_path)
        cur_a = next(it for it in reloaded.items if it["id"] == id_a)
        cur_b = next(it for it in reloaded.items if it["id"] == id_b)
        self.assertEqual(cur_b["status"], "retired")
        self.assertEqual(cur_a["status"], "retired")


if __name__ == "__main__":
    unittest.main()
