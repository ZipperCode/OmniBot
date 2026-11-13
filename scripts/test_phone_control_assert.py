#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""手机控制只读断言脚本（scripts/assert-phone-control.py）行为边界与确证单元测试。

验证范围：
- 消费者可见真实成功（Exit 0 / passed）
- 各种假成功拦截：纯模型声明、短按冒充长按、多次重复点击加载、未封存 RunLog、主屏前台抢占（Exit 1 / failed）
- 多屏采集全量 50 编号与 2 条独立重复行及截断模式校验
- 应用经验 3 轮试用转正、Sidecar 严禁泄露输入值、预先存在经验阻塞、退役与回放
- 中止取消迟到点击防护与后继恢复
- 未完成或加载中正确返回 pending（Exit 2），缺少前置或证据不足返回 blocked（Exit 3）
- 绝不调用实际设备 ADB 或执行 git 写操作
"""

import importlib.util
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

# 确保加载 assert-phone-control.py 模块
_pca_path = Path(__file__).resolve().parent / 'assert-phone-control.py'
_pca_spec = importlib.util.spec_from_file_location('assert_phone_control', _pca_path)
pca = importlib.util.module_from_spec(_pca_spec)
_pca_spec.loader.exec_module(pca)

# 加载 runtime-source 与规范 Schema 以复用真实的 canonicalize_action
_repo_root = Path(__file__).resolve().parent.parent
_tests_dir = _repo_root / 'plugins' / 'omni-vlm-lite' / 'tests'
if str(_tests_dir) not in sys.path:
    sys.path.insert(0, str(_tests_dir))
import test_screen_perception_behavior as perception_module


class PhoneControlAssertTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        perception_module.ScreenPerceptionBehaviorTest.setUpClass()
        from omniflow.core.schemas import canonicalize_action
        cls.canonicalize_action = staticmethod(canonicalize_action)

    @classmethod
    def tearDownClass(cls):
        perception_module.ScreenPerceptionBehaviorTest.tearDownClass()

    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute(
            'CREATE TABLE agent_conversation_entries('
            'id INTEGER PRIMARY KEY, conversationId INTEGER, entryType TEXT, payloadJson TEXT)'
        )
        self.files = {}
        self.fixture_state = {
            'schema_version': 'omnibot.gui-fixture.v1',
            'marker': 'OOB_LIVE_GUI_DEFAULT',
            'display_id': 2,
            'status_flags': [],
            'counts': {
                'tap': 0,
                'long_press': 0,
                'load': 0,
                'confirm': 0,
                'checkout': 0,
                'submit': 0,
                'canvas': 0
            },
            'form_values': {'first': '', 'second': ''},
            'persistent_loading': False,
            'entry_disabled': False
        }
        self.dumpsys_text = (
            "Display #0 (activities from top to bottom):\n"
            "  Stack #1:\n"
            "    Task #1:\n"
            "      mResumedActivity: ActivityRecord{123 cn.com.omnimind.bot/.MainActivity}\n"
        )

    def mock_adb_runner(self, args):
        if args[:4] == ['exec-out', 'run-as', 'cn.com.omnimind.bot', 'cat']:
            rel_path = args[4]
            return self.files.get(rel_path, '')
        if args[:3] == ['shell', 'content', 'query']:
            return f'Row: 0 json={json.dumps(self.fixture_state)}'
        if args[:3] == ['shell', 'dumpsys', 'activity']:
            return self.dumpsys_text
        return ''

    def add_user(self, marker: str, conversation_id: int = 1):
        payload = {
            'content': {
                'text': f'Execute test command\n\nEnd your final reply with {marker}_DONE.'
            }
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (conversation_id, 'user_message', json.dumps(payload))
        )

    def add_assistant(self, marker: str, text: str = None, stop_reason: str = 'end_turn', is_loading: bool = False, conversation_id: int = 1):
        if text is None:
            text = f'Completed task. {marker}_DONE'
        payload = {
            'content': {'text': text},
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1', 'stopReason': stop_reason},
            'isLoading': is_loading
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (conversation_id, 'assistant_message', json.dumps(payload))
        )

    def add_vlm_task(self, result_dict: dict, success: bool = True, conversation_id: int = 1):
        payload = {
            'toolName': 'vlm_task',
            'success': success,
            'resultPreviewJson': json.dumps(result_dict),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (conversation_id, 'tool_event', json.dumps(payload))
        )
    def add_tool_event(self, tool_name: str, payload_dict: dict = None, success: bool = True, conversation_id: int = 1, raw_json_str: str = None):
        """插入指定 toolName 的 tool_event 记录，支持字典或原生 JSON 字符串。"""
        raw = raw_json_str if raw_json_str is not None else (json.dumps(payload_dict) if payload_dict is not None else '{}')
        payload = {
            'toolName': tool_name,
            'success': success,
            'resultPreviewJson': raw,
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (conversation_id, 'tool_event', json.dumps(payload))
        )

    def store_sealed_run(self, run_id, steps=None):
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'finished_at_ms': 1773000010000,
            'success': True, 'status': 'succeeded', 'steps': steps or [],
        })

    def store_collection_state(self, state_id):
        stem = f'files/run_logs/states/{pca.safe_hash(state_id)}_{pca.safe_file_part(state_id)}'
        self.files[stem + '.json'] = json.dumps({
            'state_id': state_id, 'package_name': 'cn.com.omnimind.androidgui.test',
            'display': {'width': 1000, 'height': 1000},
        })
        self.files[stem + '.xml'] = '<hierarchy><node resource-id="cn.com.omnimind.androidgui.test:id/scroll_collection" scrollable="true" bounds="[50,50][950,950]"/></hierarchy>'


    def setup_valid_source_function_turn(self, source_marker: str, src_run_id: str, fn_id: str, conversation_id: int = 1):
        """构造包含合法 save_function 与 get_function 确证记录的来源 Turn 及封存 RunLog。"""
        self.add_user(source_marker, conversation_id=conversation_id)
        self.add_vlm_task({'run_id': src_run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}}, conversation_id=conversation_id)
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}
        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '表单填写与提交',
            'description': '回放源表单',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': self.canonicalize_action(raw_act0, persisted_only=True)},
                {'step_index': 1, 'source_state_id': 's2', 'action': self.canonicalize_action(raw_act1, persisted_only=True)},
                {'step_index': 2, 'source_state_id': 's3', 'action': self.canonicalize_action(raw_act2, persisted_only=True)}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        save_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': src_run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (conversation_id, 'tool_event', json.dumps(save_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (conversation_id, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(source_marker, conversation_id=conversation_id)
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(src_run_id, steps=source_steps)
    def test_collection_rejects_swipe_outside_recorded_scroll_region(self):
        state_id = 'collection-region-counterexample'
        self.store_collection_state(state_id)
        run = {'steps': [{'before_state_id': state_id, 'action': {'tool': 'swipe', 'args': {
            'x1': 500, 'y1': 800, 'x2': 500, 'y2': 20,
        }}, 'result': {'success': True}}]}
        with self.assertRaises(pca.FailedOutcome):
            pca.verify_collection_region('DEVICE_TEST', run, {'pages_scanned': 2, 'items': []}, self.mock_adb_runner)

    def test_collection_missing_state_is_blocked_not_verified(self):
        run = {'steps': [{'before_state_id': 'missing', 'action': {'tool': 'swipe'}, 'result': {'success': True}}]}
        with self.assertRaises(pca.BlockedOutcome):
            pca.verify_collection_region('DEVICE_TEST', run, {'pages_scanned': 2, 'items': []}, self.mock_adb_runner)

    def test_collection_rejects_item_from_another_package(self):
        state_id = 'collection-other-package'
        self.store_collection_state(state_id)
        stem = f'files/run_logs/states/{pca.safe_hash(state_id)}_{pca.safe_file_part(state_id)}'
        state = json.loads(self.files[stem + '.json'])
        state['package_name'] = 'test.other.application'
        self.files[stem + '.json'] = json.dumps(state)
        with self.assertRaises(pca.FailedOutcome):
            pca.verify_collection_region('DEVICE_TEST', {'steps': []}, {'pages_scanned': 1,
                'items': [{'state_id': state_id, 'bounds': [100, 100, 900, 200]}]}, self.mock_adb_runner)

    # ==========================================
    # 1. 基础手势与等待交互 (interaction) 测试用例
    # ==========================================

    def test_interaction_success(self):
        marker = 'OOB_LIVE_GUI_INTERACTION_1'
        run_id = 'gui-run-0001'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'status': 'succeeded',
            'finished_evidence': {
                'status': 'matched',
                'source': 'xml',
                'observation_id': 'obs_1'
            }
        })
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'status': 'succeeded',
            'finished_at_ms': 1773000005000,
            'steps': [
                {
                    'step_index': 0,
                    'action': {'tool': 'long_press', 'args': {'duration_ms': 800}},
                    'result': {'success': True},
                    'metadata': {'action_result': {'success': True, 'diagnostics': {'isTap': 'false'}}}
                },
                {
                    'step_index': 1,
                    'action': {'tool': 'wait', 'args': {'duration_ms': 2500, 'condition': 'text_present', 'target_description': 'LOAD_OK'}},
                    'result': {'success': True},
                    'metadata': {'action_result': {'success': True, 'diagnostics': {'wait_condition': 'text_present', 'condition_met': 'true', 'elapsed_ms': '2520'}}}
                }
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)

        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['LONG_PRESS_OK', 'LOAD_OK', 'CHECKOUT_OK'],
            'counts': {'long_press': 1, 'load': 1, 'confirm': 1, 'checkout': 1}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['status'], 'passed')
        self.assertEqual(out['category'], 'ok')

    def test_interaction_model_asserted_evidence_fails(self):
        """假成功：模型自身声称完成，但无实际控件匹配证据。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_2'
        run_id = 'gui-run-0002'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'status': 'succeeded',
            'finished_evidence': {
                'status': 'model_asserted',
                'source': 'model'
            }
        })
        self.add_assistant(marker)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertFalse(out['passed'])
        self.assertEqual(out['category'], 'finished_evidence_model_asserted')

    def test_interaction_tap_pretending_long_press_fails(self):
        """假成功：短按冒充长按，计数器 long_press 为 0。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_3'
        run_id = 'gui-run-0003'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'status': 'succeeded',
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {
                    'action': {'tool': 'long_press', 'args': {'duration_ms': 800}},
                    'result': {'success': True}
                },
                {
                    'action': {'tool': 'wait', 'args': {'condition': 'text_present'}},
                    'result': {'success': True},
                    'metadata': {'action_result': {'diagnostics': {'condition_met': 'true'}}}
                }
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)

        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['LONG_PRESS_OK', 'LOAD_OK', 'CHECKOUT_OK'],
            'counts': {'long_press': 0, 'tap': 1, 'load': 1, 'confirm': 1, 'checkout': 1}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'fixture_long_press_count_not_one')

    def test_interaction_load_clicked_multiple_times_fails(self):
        """加载被多次触发，违反只允许单次点击规则。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_4'
        run_id = 'gui-run-0004'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'status': 'succeeded',
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}},
                {'action': {'tool': 'wait', 'args': {'condition': 'text_present'}}, 'result': {'success': True}, 'metadata': {'action_result': {'diagnostics': {'condition_met': 'true'}}}}
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)

        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['LONG_PRESS_OK', 'LOAD_OK', 'CHECKOUT_OK'],
            'counts': {'long_press': 1, 'load': 2, 'confirm': 1, 'checkout': 1}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'fixture_load_count_not_one')

    def test_interaction_unsealed_runlog_returns_pending(self):
        """Turn 已执行，但 RunLog 尚未封存 (finished_at_ms 为空)，可继续只读重试，返回 pending (Exit 2)。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_5'
        run_id = 'gui-run-0005'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'status': 'succeeded',
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': None,
            'steps': []
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 2)
        self.assertEqual(out['status'], 'pending')
        self.assertEqual(out['category'], 'waiting_for_sealed_run_log')
    def test_interaction_main_display_fails(self):
        """动作在主屏（Display 0）执行，侵占主屏判定失败。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_6'
        run_id = 'gui-run-0006'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'status': 'succeeded',
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {
                    'action': {'tool': 'long_press', 'args': {'duration_ms': 800}},
                    'result': {'success': True},
                    'metadata': {'action_result': {'diagnostics': {'targetDisplayId': '0'}}}
                },
                {
                    'action': {'tool': 'wait', 'args': {'duration_ms': 2500, 'condition': 'text_present', 'target_description': 'LOAD_OK'}},
                    'result': {'success': True},
                    'metadata': {'action_result': {'diagnostics': {'condition_met': 'true', 'targetDisplayId': '0'}}}
                }
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'step_targeted_main_display')

    def test_display0_other_app_resumed_fails(self):
        """主屏前台处于其他非 bot 应用，断言失败。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_OTHER_APP'
        run_id = 'gui-run-other-app'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}},
                {'action': {'tool': 'wait', 'args': {'condition': 'text_present'}}, 'result': {'success': True}, 'metadata': {'action_result': {'diagnostics': {'condition_met': 'true'}}}}
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)
        self.fixture_state.update({
            'marker': marker, 'display_id': 2,
            'status_flags': ['LONG_PRESS_OK', 'LOAD_OK', 'CHECKOUT_OK'],
            'counts': {'long_press': 1, 'load': 1, 'confirm': 1, 'checkout': 1}
        })
        self.dumpsys_text = (
            "Display #0 (activities from top to bottom):\n"
            "  Stack #1:\n"
            "    mResumedActivity: ActivityRecord{123 u0 com.android.launcher3/.Launcher t1}\n"
        )

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'display0_top_activity_not_bot')

    def test_display0_unobservable_blocks(self):
        """主屏无法确证顶层 resumed Activity 时，返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_UNOBSERVABLE'
        run_id = 'gui-run-unobservable'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}},
                {'action': {'tool': 'wait', 'args': {'condition': 'text_present'}}, 'result': {'success': True}, 'metadata': {'action_result': {'diagnostics': {'condition_met': 'true'}}}}
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)
        self.fixture_state.update({
            'marker': marker, 'display_id': 2,
            'status_flags': ['LONG_PRESS_OK', 'LOAD_OK', 'CHECKOUT_OK'],
            'counts': {'long_press': 1, 'load': 1, 'confirm': 1, 'checkout': 1}
        })
        self.dumpsys_text = "Display #0:\n  No activities found."

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'display0_top_activity_unobservable')

    def test_database_snapshot_unavailable_blocks(self):
        """目标设备未安装主应用或无法创建 SQLite 快照时，返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_NO_APP'
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST_NO_APP', marker, 'interaction', db=None
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'database_snapshot_unavailable')

    def test_interaction_long_press_duration_too_short_fails(self):
        """长按时长不足 800ms，断言失败。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_SHORT'
        run_id = 'gui-run-short'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 700}}, 'result': {'success': True}}
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'long_press_duration_too_short')

    def test_interaction_long_press_step_not_successful_fails(self):
        """长按步骤自身未成功（result.success=False），断言失败。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_LP_FAIL'
        run_id = 'gui-run-lp-fail'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': False}}
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'long_press_step_not_successful')

    def test_interaction_wait_condition_not_met_fails(self):
        """等待条件未满足（condition_met 为 false），断言失败。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_WAIT_FAIL'
        run_id = 'gui-run-wait-fail'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}},
                {
                    'action': {'tool': 'wait', 'args': {'condition': 'text_present'}},
                    'result': {'success': True},
                    'metadata': {'action_result': {'diagnostics': {'condition_met': 'false'}}}
                }
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'wait_condition_not_met')

    def test_interaction_repeat_operation_fails(self):
        """确认操作被重复触发（confirm 计数为 2），断言失败。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_REPEAT'
        run_id = 'gui-run-repeat'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        self.add_assistant(marker)

        run_log = {
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}},
                {
                    'action': {'tool': 'wait', 'args': {'condition': 'text_present'}},
                    'result': {'success': True},
                    'metadata': {'action_result': {'diagnostics': {'condition_met': 'true'}}}
                }
            ]
        }
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps(run_log)

        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['LONG_PRESS_OK', 'LOAD_OK', 'CHECKOUT_OK'],
            'counts': {'long_press': 1, 'load': 1, 'confirm': 2, 'checkout': 1}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'fixture_confirm_count_not_one')

    # ==========================================
    # 2. 多屏采集 (collection) 测试用例
    # ==========================================

    def test_collection_full_success(self):
        marker = 'OOB_LIVE_GUI_COLLECTION_1'
        run_id = 'gui-run-coll-1'
        self.add_user(marker)

        # 构建 50 个固定编号以及 2 个相同文字独立位置的 DUPLICATE_ROW
        state_id = 'collection-state-1'
        self.store_collection_state(state_id)
        items = [{'text': f'ITEM_{i:02d}', 'page_index': i // 10, 'bounds': [100, 100 + (i % 10) * 50, 900, 140 + (i % 10) * 50], 'state_id': state_id} for i in range(1, 51)]
        items.append({'text': 'DUPLICATE_ROW', 'page_index': 0, 'bounds': [100, 650, 900, 690], 'state_id': state_id})
        items.append({'text': 'DUPLICATE_ROW', 'page_index': 2, 'bounds': [100, 700, 900, 740], 'state_id': state_id})

        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'collection': {
                'items': items,
                'pages_scanned': 9,
                'truncated': False,
                'stop_reason': 'no_new_content'
            }
        })
        self.add_assistant(marker, text=f'已采集 50 个编号（首项 ITEM_01，末项 ITEM_50）与 2 条 DUPLICATE_ROW。{marker}_DONE')

        self.store_sealed_run(run_id, [{
            'before_state_id': state_id, 'after_state_id': state_id,
            'action': {'tool': 'swipe', 'args': {'x1': 500, 'y1': 800, 'x2': 500, 'y2': 200}},
            'result': {'success': True},
        }])

        self.fixture_state.update({'marker': marker, 'display_id': 2})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'collection', phase='full', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['items_count'], 52)

    def test_collection_full_missing_numbered_items_fails(self):
        """50 个编号中缺少项，断言失败。"""
        marker = 'OOB_LIVE_GUI_COLLECTION_2'
        run_id = 'gui-run-coll-2'
        self.add_user(marker)

        # 缺少 ITEM_50
        items = [{'text': f'ITEM_{i:02d}', 'page_index': 0} for i in range(1, 50)]
        items.append({'text': 'DUPLICATE_ROW', 'page_index': 0, 'bounds': [0, 0, 10, 10]})
        items.append({'text': 'DUPLICATE_ROW', 'page_index': 1, 'bounds': [0, 10, 10, 20]})

        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'collection': {
                'items': items,
                'pages_scanned': 5,
                'truncated': False,
                'stop_reason': 'no_new_content'
            }
        })
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'finished_at_ms': 1773000010000, 'steps': []
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'collection', phase='full', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)

    def test_collection_full_duplicate_rows_deduped_fails(self):
        """重复文本行被错误全局去重（仅剩 1 条），断言失败。"""
        marker = 'OOB_LIVE_GUI_COLLECTION_3'
        run_id = 'gui-run-coll-3'
        self.add_user(marker)

        items = [{'text': f'ITEM_{i:02d}'} for i in range(1, 51)]
        items.append({'text': 'DUPLICATE_ROW', 'page_index': 0, 'bounds': [0, 0, 10, 10]})

        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'collection': {
                'items': items,
                'pages_scanned': 5,
                'truncated': False,
                'stop_reason': 'no_new_content'
            }
        })
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'finished_at_ms': 1773000010000, 'steps': []
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'collection', phase='full', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)

    def test_collection_truncated_success(self):
        marker = 'OOB_LIVE_GUI_COLLECTION_4'
        run_id = 'gui-run-coll-4'
        self.add_user(marker)

        state_id = 'collection-state-truncated'
        self.store_collection_state(state_id)
        items = [{'text': f'ITEM_{i:02d}', 'state_id': state_id, 'bounds': [100, 100 + i * 50, 900, 140 + i * 50]} for i in range(1, 11)]

        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'collection': {
                'items': items,
                'pages_scanned': 1,
                'truncated': True,
                'stop_reason': 'page_limit'
            }
        })
        self.add_assistant(marker, text=f'受单页限制仅读取前 10 项。{marker}_DONE')

        self.store_sealed_run(run_id)
        self.fixture_state.update({'marker': marker, 'display_id': 2})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'collection', phase='truncated', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['stop_reason'], 'page_limit')

    def test_collection_truncated_falsely_claiming_full_fails(self):
        """截断模式下助手虚假宣称已读取全部，断言失败。"""
        marker = 'OOB_LIVE_GUI_COLLECTION_5'
        run_id = 'gui-run-coll-5'
        self.add_user(marker)

        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'collection': {
                'items': [{'text': 'ITEM_01'}],
                'pages_scanned': 1,
                'truncated': True,
                'stop_reason': 'page_limit'
            }
        })
        self.add_assistant(marker, text=f'已全部读完包括 ITEM_50。{marker}_DONE')

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'finished_at_ms': 1773000010000, 'steps': []
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'collection', phase='truncated', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'assistant_falsely_claimed_complete_collection')

    # ==========================================
    # 3. 应用经验 (experience) 测试用例
    # ==========================================

    def test_experience_round1_success(self):
        marker = 'OOB_LIVE_GUI_EXP_R1'
        run_id = 'gui-exp-run-01'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [run_id],
            'items': [
                {
                    'id': 'exp_rule_submit_01',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'activity_name': '.fixture.TestGuiFixtureActivity',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': run_id,
                    'source_step_index': 2,
                    'rule': {
                        'action': 'click',
                        'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit',
                        'target_class': 'android.widget.Button',
                        'after_activity': '.fixture.TestGuiFixtureActivity'
                    },
                    'successful_run_ids': [],
                    'failed_run_ids': [],
                    'consecutive_failures': 0,
                    'previous_verified_id': None
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.store_sealed_run(run_id)
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1',
            expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['experience_status'], 'trial')
        self.assertEqual(out['details']['resource_id'], 'cn.com.omnimind.androidgui.test:id/btn_form_submit')
        self.assertEqual(out['details']['action'], 'click')
        self.assertEqual(out['details']['source_run_id'], run_id)

    def test_experience_round1_prioritizes_submit_over_input_text(self):
        """Round 1 必须优先选择 btn_form_submit 的 click 规则，防止 input_text 导致 rollback 误归因。"""
        marker = 'OOB_LIVE_GUI_EXP_R1_PRIO'
        run_id = 'gui-exp-run-01-prio'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [run_id],
            'items': [
                {
                    'id': 'exp_rule_input_01',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': run_id,
                    'rule': {
                        'action': 'input_text',
                        'target_resource_id': 'cn.com.omnimind.androidgui.test:id/edt_first'
                    },
                    'successful_run_ids': []
                },
                {
                    'id': 'exp_rule_submit_prio',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': run_id,
                    'rule': {
                        'action': 'click',
                        'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'
                    },
                    'successful_run_ids': []
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.store_sealed_run(run_id)
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertEqual(out['details']['experience_id'], 'exp_rule_submit_prio')
        self.assertEqual(out['details']['resource_id'], 'cn.com.omnimind.androidgui.test:id/btn_form_submit')
        self.assertEqual(out['details']['action'], 'click')

    def test_experience_round1_canonical_persisted_function_without_node_resource_id_succeeds(self):
        """最小回归：真实持久化 Function 经 canonicalize_action 移除 node_resource_id 后，断言必须通过来源 RunLog 确证目标控件并成功提取 ID。"""
        marker = 'OOB_LIVE_GUI_EXP_CANONICAL_PERSISTED'
        run_id = 'gui-exp-run-canonical-01'
        fn_id = 'fn_submit_form_canonical_01'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        # 构造原始动作并经真实的 canonicalize_action(persisted_only=True) 处理为持久化动作
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': 'SYNTHETIC_FIRST_01', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': 'SYNTHETIC_SECOND_01', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        canonical_act0 = self.canonicalize_action(raw_act0, persisted_only=True)
        canonical_act1 = self.canonicalize_action(raw_act1, persisted_only=True)
        canonical_act2 = self.canonicalize_action(raw_act2, persisted_only=True)

        # 确证运行时规范确实将非持久字段（node_resource_id、x、y 等）剥离
        self.assertNotIn('node_resource_id', canonical_act0['args'])
        self.assertNotIn('node_resource_id', canonical_act1['args'])
        self.assertNotIn('node_resource_id', canonical_act2['args'])

        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '表单填写与提交',
            'description': '真实持久化表单Function',
            'input_schema': {
                'type': 'object',
                'properties': {
                    'first': {'type': 'string'},
                    'second': {'type': 'string'}
                },
                'required': ['first', 'second'],
                'additionalProperties': False
            },
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 'state_form_01', 'action': canonical_act0},
                {'step_index': 1, 'source_state_id': 'state_form_02', 'action': canonical_act1},
                {'step_index': 2, 'source_state_id': 'state_form_03', 'action': canonical_act2}
            ],
            'checker_rules': [],
            'agent_visible': True
        }

        save_result = {
            'payload': {
                'success': True,
                'function_ids': [fn_id],
                'functions': [form_fn]
            }
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps(save_result),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(tool_payload))
        )
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(get_payload))
        )
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [run_id],
            'items': [
                {
                    'id': 'exp_rule_submit_canonical',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': run_id,
                    'rule': {
                        'action': 'click',
                        'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'
                    },
                    'successful_run_ids': []
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)

        # 来源 RunLog 记录真实的完整动作（含 node_resource_id）及 before_state_id
        source_steps = [
            {'step_index': 0, 'before_state_id': 'state_form_01', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 'state_form_02', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 'state_form_03', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1',
            expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['registered_function_id'], fn_id)
    def test_experience_round1_plural_and_wrapper_save_function_response_extracts_function_id(self):
        """Round 1 增强注册支持复数 function_ids 及 wrapper 结构 (payload/result) 解析，由 get_function 确证双参数绑定。"""
        marker = 'OOB_LIVE_GUI_EXP_PLURAL_RESP'
        run_id = 'gui-exp-run-plural'
        fn_id = 'fn_submit_form_plural_01'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': 'SYNTHETIC_FIRST_01', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': 'SYNTHETIC_SECOND_01', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        canonical_act0 = self.canonicalize_action(raw_act0, persisted_only=True)
        canonical_act1 = self.canonicalize_action(raw_act1, persisted_only=True)
        canonical_act2 = self.canonicalize_action(raw_act2, persisted_only=True)

        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '表单填写与提交',
            'description': '输入两个参数并提交表单',
            'input_schema': {
                'type': 'object',
                'properties': {
                    'first': {'type': 'string'},
                    'second': {'type': 'string'}
                },
                'required': ['first', 'second'],
                'additionalProperties': False
            },
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': canonical_act0},
                {'step_index': 1, 'source_state_id': 's2', 'action': canonical_act1},
                {'step_index': 2, 'source_state_id': 's3', 'action': canonical_act2}
            ],
            'checker_rules': [],
            'agent_visible': True
        }
        # Python bridge save_function 真实返回结构：wrapper 中返回成功与 function_ids
        save_result = {
            'payload': {
                'success': True,
                'function_ids': [fn_id]
            }
        }
        # sameTurn save_function args.run_id 严格匹配 source GUI run_id
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps(save_result),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(tool_payload))
        )
        # 真实小万流程通过 get_function 确认该授权 ID 的完整 artifact 详情
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(get_payload))
        )
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [run_id],
            'items': [
                {
                    'id': 'exp_rule_submit_plural',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': run_id,
                    'rule': {
                        'action': 'click',
                        'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'
                    },
                    'successful_run_ids': []
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1',
            expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['registered_function_id'], fn_id)
    def test_experience_round1_split_multiple_functions_selects_form_with_two_bindings(self):
        """Split 产出多个 Function 时，断言绝不能盲目提取 function_ids[0]，必须确证唯一表单双参数绑定来源。"""
        marker = 'OOB_LIVE_GUI_EXP_SPLIT_MULTI'
        run_id = 'gui-exp-run-split'
        nav_fn_id = 'fn_nav_step_01'
        form_fn_id = 'fn_form_step_02'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        raw_nav = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_menu_form', 'x': 100, 'y': 100}}
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': 'SYNTHETIC_FIRST_01', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': 'SYNTHETIC_SECOND_01', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        canonical_nav = self.canonicalize_action(raw_nav, persisted_only=True)
        canonical_act0 = self.canonicalize_action(raw_act0, persisted_only=True)
        canonical_act1 = self.canonicalize_action(raw_act1, persisted_only=True)
        canonical_act2 = self.canonicalize_action(raw_act2, persisted_only=True)

        nav_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': nav_fn_id,
            'name': '导航与标记设置',
            'description': '返回主页并设置 marker',
            'input_schema': {'type': 'object', 'properties': {}, 'required': [], 'additionalProperties': False},
            'bindings': [],
            'steps': [
                {'step_index': 0, 'source_state_id': 's0', 'action': canonical_nav}
            ],
            'checker_rules': [],
            'agent_visible': True
        }
        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': form_fn_id,
            'name': '表单录入提交',
            'description': '参数化输入并提交',
            'input_schema': {
                'type': 'object',
                'properties': {
                    'first': {'type': 'string'},
                    'second': {'type': 'string'}
                },
                'required': ['first', 'second'],
                'additionalProperties': False
            },
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': canonical_act0},
                {'step_index': 1, 'source_state_id': 's2', 'action': canonical_act1},
                {'step_index': 2, 'source_state_id': 's3', 'action': canonical_act2}
            ],
            'checker_rules': [],
            'agent_visible': True
        }
        # save_function 返回导航与表单两个 Function 的 ID 列表，导航排在第一位
        save_result = {
            'success': True,
            'function_ids': [nav_fn_id, form_fn_id]
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps(save_result),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(tool_payload))
        )
        # 随后针对两个 ID 分别调用 get_function，断言必须通过参数绑定与排除导航确证目标表单
        get_nav_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': nav_fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': nav_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(get_nav_payload))
        )
        get_form_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': form_fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(get_form_payload))
        )
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [run_id],
            'items': [
                {
                    'id': 'exp_rule_submit_split',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': run_id,
                    'rule': {
                        'action': 'click',
                        'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'
                    },
                    'successful_run_ids': []
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        source_steps = [
            {'step_index': 0, 'before_state_id': 's0', 'action': raw_nav, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 3, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1',
            expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        # 必须选中具备两个输入绑定的表单 Function，而不是导航 Function
        self.assertEqual(out['details']['registered_function_id'], form_fn_id)
    def test_experience_round1_split_multiple_ambiguous_functions_blocks(self):
        """Split 产出多个 Function 且存在歧义（多个或零个具备表单双输入绑定）时，严禁盲目猜测，必须判定 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_SPLIT_AMBIGUOUS'
        run_id = 'gui-exp-run-amb'
        fn_a = 'fn_candidate_a'
        fn_b = 'fn_candidate_b'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': 'A', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': 'B', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        canonical_act0 = self.canonicalize_action(raw_act0, persisted_only=True)
        canonical_act1 = self.canonicalize_action(raw_act1, persisted_only=True)
        canonical_act2 = self.canonicalize_action(raw_act2, persisted_only=True)

        fn_artifact_a = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_a,
            'name': '表单候选A',
            'description': '候选A',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': canonical_act0},
                {'step_index': 1, 'source_state_id': 's2', 'action': canonical_act1},
                {'step_index': 2, 'source_state_id': 's3', 'action': canonical_act2},
            ],
            'checker_rules': [], 'agent_visible': True
        }
        fn_artifact_b = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_b,
            'name': '表单候选B',
            'description': '候选B',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': canonical_act0},
                {'step_index': 1, 'source_state_id': 's2', 'action': canonical_act1},
                {'step_index': 2, 'source_state_id': 's3', 'action': canonical_act2},
            ],
            'checker_rules': [], 'agent_visible': True
        }
        save_result = {
            'success': True,
            'function_ids': [fn_a, fn_b]
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps(save_result),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(tool_payload))
        )
        # 随后调用 get_function 确认两个候选均为有效表单，造成歧义
        for f_id, f_art in ((fn_a, fn_artifact_a), (fn_b, fn_artifact_b)):
            get_p = {
                'toolName': 'get_function',
                'argsJson': json.dumps({'function_id': f_id}),
                'success': True,
                'resultPreviewJson': json.dumps({'success': True, 'function': f_art}),
                'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
            }
            self.db.execute(
                'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
                (1, 'tool_event', json.dumps(get_p))
            )
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [run_id],
            'items': [
                {
                    'id': 'exp_rule_amb',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': run_id,
                    'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'},
                    'successful_run_ids': []
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1',
            expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'form_function_ambiguous')
    def test_experience_round1_confirms_bindings_via_get_function(self):
        """当 save_function 仅返回 id 列表时，通过随后的 get_function 工具调用确认表单双参数绑定。"""
        marker = 'OOB_LIVE_GUI_EXP_CONFIRM_GET_FN'
        run_id = 'gui-exp-run-getfn'
        fn_id = 'fn_confirmed_via_get'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        canonical_act0 = self.canonicalize_action(raw_act0, persisted_only=True)
        canonical_act1 = self.canonicalize_action(raw_act1, persisted_only=True)
        canonical_act2 = self.canonicalize_action(raw_act2, persisted_only=True)

        # save_function 仅包含 function_ids
        save_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(save_payload))
        )
        # 随后调用 get_function 确认 Function 详情
        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '确认表单录入',
            'description': '带双参数绑定的表单',
            'input_schema': {
                'type': 'object',
                'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}},
                'required': ['first', 'second'],
                'additionalProperties': False
            },
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': canonical_act0},
                {'step_index': 1, 'source_state_id': 's2', 'action': canonical_act1},
                {'step_index': 2, 'source_state_id': 's3', 'action': canonical_act2}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(get_payload))
        )
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [run_id],
            'items': [
                {
                    'id': 'exp_rule_getfn',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': run_id,
                    'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'},
                    'successful_run_ids': []
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1',
            expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['registered_function_id'], fn_id)

    def test_experience_round1_wrong_source_run_id_or_run_gui_function_id_blocks(self):
        """当 save_function 的 run_id 与 source GUI run_id 不匹配、调用失败或 vlm_task 携带 function_id='run_gui' 时，绝不短路或授权产出，严格判定 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_WRONG_SOURCE'
        actual_gui_run_id = 'gui-exp-run-actual-01'
        mismatched_run_id = 'gui-exp-run-unrelated-99'
        fn_id = 'fn_unauthorized_source'

        self.add_user(marker)
        # vlm_task 携带真实的 actual_gui_run_id，同时携带真实的 function_id='run_gui'，断言绝不能短路返回 'run_gui'
        self.add_vlm_task({
            'run_id': actual_gui_run_id,
            'function_id': 'run_gui',
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })

        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        canonical_act0 = self.canonicalize_action(raw_act0, persisted_only=True)
        canonical_act1 = self.canonicalize_action(raw_act1, persisted_only=True)
        canonical_act2 = self.canonicalize_action(raw_act2, persisted_only=True)

        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '未授权来源表单',
            'description': '来源不匹配',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': canonical_act0},
                {'step_index': 1, 'source_state_id': 's2', 'action': canonical_act1},
                {'step_index': 2, 'source_state_id': 's3', 'action': canonical_act2}
            ],
            'checker_rules': [], 'agent_visible': True
        }

        # save_function 的 argsJson 中的 run_id 为不匹配的 mismatched_run_id
        save_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': mismatched_run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(save_payload))
        )
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(get_payload))
        )
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [actual_gui_run_id],
            'items': [
                {
                    'id': 'exp_rule_wrong_src',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': actual_gui_run_id,
                    'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'},
                    'successful_run_ids': []
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(actual_gui_run_id, steps=source_steps)
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1',
            expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'form_function_ambiguous')

    def test_experience_round1_invalid_bindings_or_non_form_steps_blocks(self):
        """当 Function 包含错误 source 路径、错误 target 路径/控件类型、缺少提交按钮或包含导航控件时，严禁识别为表单 Function，判定 blocked (Exit 3)。"""
        marker_base = 'OOB_LIVE_GUI_EXP_INVALID_BIND'
        run_id_base = 'gui-exp-run-inv'

        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}
        raw_nav = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_menu_form', 'x': 100, 'y': 100}}

        can_act0 = self.canonicalize_action(raw_act0, persisted_only=True)
        can_act1 = self.canonicalize_action(raw_act1, persisted_only=True)
        can_act2 = self.canonicalize_action(raw_act2, persisted_only=True)
        can_nav = self.canonicalize_action(raw_nav, persisted_only=True)

        invalid_cases = [
            # 1. 错误的 source 参数名（如 $.arguments.unknown 而非 $.arguments.first）
            (
                'wrong_source',
                [
                    {'source': '$.arguments.unknown', 'target': '$.steps[0].action.args.text'},
                    {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
                ],
                [
                    {'step_index': 0, 'source_state_id': 's1', 'action': can_act0},
                    {'step_index': 1, 'source_state_id': 's2', 'action': can_act1},
                    {'step_index': 2, 'source_state_id': 's3', 'action': can_act2}
                ]
            ),
            # 2. 错误的 target 属性（指向 args.other 而非 args.text）
            (
                'wrong_target_prop',
                [
                    {'source': '$.arguments.first', 'target': '$.steps[0].action.args.other'},
                    {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
                ],
                [
                    {'step_index': 0, 'source_state_id': 's1', 'action': can_act0},
                    {'step_index': 1, 'source_state_id': 's2', 'action': can_act1},
                    {'step_index': 2, 'source_state_id': 's3', 'action': can_act2}
                ]
            ),
            # 3. 目标 step 并非对应 input_first 控件
            (
                'wrong_target_node',
                [
                    {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                    {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
                ],
                [
                    {'step_index': 0, 'source_state_id': 's_wrong', 'action': can_act0},
                    {'step_index': 1, 'source_state_id': 's2', 'action': can_act1},
                    {'step_index': 2, 'source_state_id': 's3', 'action': can_act2}
                ]
            ),
            # 4. 缺少 btn_form_submit 的点击步骤
            (
                'missing_submit',
                [
                    {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                    {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
                ],
                [
                    {'step_index': 0, 'source_state_id': 's1', 'action': can_act0},
                    {'step_index': 1, 'source_state_id': 's2', 'action': can_act1}
                ]
            ),
            # 5. 步骤中违规包含了导航按钮 btn_menu_form
            (
                'contains_nav',
                [
                    {'source': '$.arguments.first', 'target': '$.steps[1].action.args.text'},
                    {'source': '$.arguments.second', 'target': '$.steps[2].action.args.text'}
                ],
                [
                    {'step_index': 0, 'source_state_id': 's0', 'action': can_nav},
                    {'step_index': 1, 'source_state_id': 's1', 'action': can_act0},
                    {'step_index': 2, 'source_state_id': 's2', 'action': can_act1},
                    {'step_index': 3, 'source_state_id': 's3', 'action': can_act2}
                ]
            ),
            # 6. 违规使用非规范 target_resource_id 字段
            (
                'non_canonical_field',
                [
                    {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                    {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
                ],
                [
                    {'step_index': 0, 'source_state_id': 's1', 'action': {'tool': 'input_text', 'args': {'target_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1'}}},
                    {'step_index': 1, 'source_state_id': 's2', 'action': {'tool': 'input_text', 'args': {'target_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2'}}},
                    {'step_index': 2, 'source_state_id': 's3', 'action': {'tool': 'click', 'args': {'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}}}
                ]
            ),
        ]

        for case_name, bindings, steps in invalid_cases:
            with self.subTest(case=case_name):
                marker = f'{marker_base}_{case_name.upper()}'
                run_id = f'{run_id_base}-{case_name}'
                fn_id = f'fn_invalid_{case_name}'
                self.setUp()  # 重置数据库与状态
                self.add_user(marker)
                self.add_vlm_task({
                    'run_id': run_id,
                    'success': True,
                    'finished_evidence': {'status': 'matched', 'source': 'xml'}
                })

                fn_artifact = {
                    'schema_version': 'omniflow.function.v2',
                    'function_id': fn_id,
                    'name': f'测试_{case_name}',
                    'description': case_name,
                    'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
                    'bindings': bindings,
                    'steps': steps,
                    'checker_rules': [], 'agent_visible': True
                }

                save_payload = {
                    'toolName': 'save_function',
                    'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
                    'success': True,
                    'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
                    'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
                }
                self.db.execute(
                    'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
                    (1, 'tool_event', json.dumps(save_payload))
                )
                get_payload = {
                    'toolName': 'get_function',
                    'argsJson': json.dumps({'function_id': fn_id}),
                    'success': True,
                    'resultPreviewJson': json.dumps({'success': True, 'function': fn_artifact}),
                    'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
                }
                self.db.execute(
                    'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
                    (1, 'tool_event', json.dumps(get_payload))
                )
                self.add_assistant(marker)

                sidecar = {
                    'schema_version': 'omniflow.app-experiences.v1',
                    'revision': 1,
                    'processed_run_ids': [run_id],
                    'items': [
                        {
                            'id': f'exp_rule_{case_name}',
                            'package_name': 'cn.com.omnimind.androidgui.test',
                            'status': 'trial',
                            'enabled': True,
                            'source_run_id': run_id,
                            'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'},
                            'successful_run_ids': []
                        }
                    ]
                }
                self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
                source_steps = [
                    {'step_index': 0, 'before_state_id': 's0', 'action': raw_nav, 'result': {'success': True}},
                    {'step_index': 1, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
                    {'step_index': 2, 'before_state_id': 's_wrong', 'action': {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/wrong_input', 'text': '1', 'x': 500, 'y': 200}}, 'result': {'success': True}},
                    {'step_index': 3, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
                    {'step_index': 4, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
                ]
                self.store_sealed_run(run_id, steps=source_steps)
                self.fixture_state.update({
                    'marker': marker,
                    'display_id': 2,
                    'status_flags': ['FORM_OK'],
                    'counts': {'submit': 1},
                    'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
                })

                out, code = pca.run_assert_phone_control(
                    'DEVICE_TEST', marker, 'experience', phase='round1',
                    expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01',
                    db=self.db, adb_runner=self.mock_adb_runner
                )
                self.assertEqual(code, 3)
                self.assertEqual(out['status'], 'blocked')
                self.assertEqual(out['category'], 'form_function_ambiguous')

    def test_experience_round1_get_function_for_other_unauthorized_id_blocks(self):
        """当 save_function 返回某 ID，但随后的 get_function 调取了无关或未授权的 ID 时，严禁采纳该详情，判定 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_GET_OTHER_ID'
        run_id = 'gui-exp-run-get-other'
        saved_fn_id = 'fn_authorized_by_save'
        unrelated_fn_id = 'fn_unrelated_other'

        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })

        # save_function 产出 saved_fn_id
        save_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [saved_fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(save_payload))
        )

        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        canonical_act0 = self.canonicalize_action(raw_act0, persisted_only=True)
        canonical_act1 = self.canonicalize_action(raw_act1, persisted_only=True)
        canonical_act2 = self.canonicalize_action(raw_act2, persisted_only=True)

        # get_function 却调取了无关的 unrelated_fn_id，哪怕该 artifact 符合双参数表单规范
        unrelated_form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': unrelated_fn_id,
            'name': '无关表单录入',
            'description': '不属于 save_function 的 ID',
            'input_schema': {
                'type': 'object',
                'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}},
                'required': ['first', 'second'],
                'additionalProperties': False
            },
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': canonical_act0},
                {'step_index': 1, 'source_state_id': 's2', 'action': canonical_act1},
                {'step_index': 2, 'source_state_id': 's3', 'action': canonical_act2}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': unrelated_fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': unrelated_form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(get_payload))
        )
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [run_id],
            'items': [
                {
                    'id': 'exp_rule_get_other',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'status': 'trial',
                    'enabled': True,
                    'source_run_id': run_id,
                    'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'},
                    'successful_run_ids': []
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1',
            expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'form_function_ambiguous')

    def test_experience_round1_source_state_not_in_run_blocks(self):
        """当 Function step 的 source_state_id 不属于当前来源 RunLog 时，严禁通过确证，判定 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_STATE_NOT_IN_RUN'
        run_id = 'gui-exp-run-state-missing'
        fn_id = 'fn_state_missing'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}
        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '状态不存在表单',
            'description': 'step0 指向外部未关联 state',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 'unrelated_state_999', 'action': self.canonicalize_action(raw_act0, persisted_only=True)},
                {'step_index': 1, 'source_state_id': 's2', 'action': self.canonicalize_action(raw_act1, persisted_only=True)},
                {'step_index': 2, 'source_state_id': 's3', 'action': self.canonicalize_action(raw_act2, persisted_only=True)}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(tool_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(marker)
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'revision': 1, 'processed_run_ids': [run_id],
            'items': [{'id': 'exp_missing_state', 'package_name': 'cn.com.omnimind.androidgui.test', 'status': 'trial', 'enabled': True, 'source_run_id': run_id, 'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}, 'successful_run_ids': []}]
        })
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}, 'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}})
        out, code = pca.run_assert_phone_control('DEVICE_TEST', marker, 'experience', phase='round1', expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01', db=self.db, adb_runner=self.mock_adb_runner)
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'form_function_ambiguous')

    def test_experience_round1_swapped_input_resource_id_blocks(self):
        """当来源动作的输入控件资源 ID 并非目标的 input_first/input_second 时，严格判定 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_SWAP_RID'
        run_id = 'gui-exp-run-swap-rid'
        fn_id = 'fn_swap_rid'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        # 步骤 0 对应 source 动作的控件被替换为 wrong_field
        raw_act0_wrong = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/wrong_field', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '输入控件ID不符表单',
            'description': 'step0 映射控件非 input_first',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': self.canonicalize_action(raw_act0_wrong, persisted_only=True)},
                {'step_index': 1, 'source_state_id': 's2', 'action': self.canonicalize_action(raw_act1, persisted_only=True)},
                {'step_index': 2, 'source_state_id': 's3', 'action': self.canonicalize_action(raw_act2, persisted_only=True)}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(tool_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(marker)
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'revision': 1, 'processed_run_ids': [run_id],
            'items': [{'id': 'exp_swap_rid', 'package_name': 'cn.com.omnimind.androidgui.test', 'status': 'trial', 'enabled': True, 'source_run_id': run_id, 'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}, 'successful_run_ids': []}]
        })
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0_wrong, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}, 'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}})
        out, code = pca.run_assert_phone_control('DEVICE_TEST', marker, 'experience', phase='round1', expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01', db=self.db, adb_runner=self.mock_adb_runner)
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'form_function_ambiguous')

    def test_experience_round1_mismatched_submit_coordinates_blocks(self):
        """当 Function 持久化动作与来源 run 在同一 state 的持久化参数（如坐标）不一致时，严禁通过，判定 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_ACTION_MISMATCH'
        run_id = 'gui-exp-run-mismatch'
        fn_id = 'fn_mismatch'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}
        # 故意将 Function 的提交点击坐标改为 (999, 999)，与来源 run 的 (500, 800) 不匹配
        mismatched_act2 = {'tool': 'click', 'args': {'x': 999, 'y': 999}}
        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '动作参数不符表单',
            'description': 'click 坐标不匹配',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': self.canonicalize_action(raw_act0, persisted_only=True)},
                {'step_index': 1, 'source_state_id': 's2', 'action': self.canonicalize_action(raw_act1, persisted_only=True)},
                {'step_index': 2, 'source_state_id': 's3', 'action': mismatched_act2}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(tool_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(marker)
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'revision': 1, 'processed_run_ids': [run_id],
            'items': [{'id': 'exp_mismatch', 'package_name': 'cn.com.omnimind.androidgui.test', 'status': 'trial', 'enabled': True, 'source_run_id': run_id, 'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}, 'successful_run_ids': []}]
        })
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}, 'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}})
        out, code = pca.run_assert_phone_control('DEVICE_TEST', marker, 'experience', phase='round1', expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01', db=self.db, adb_runner=self.mock_adb_runner)
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'form_function_ambiguous')

    def test_experience_round1_source_action_resource_conflict_blocks(self):
        """当同一 source_state 存在多个匹配动作但对应不同控件资源时，严格判定冲突，返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_RES_CONFLICT'
        run_id = 'gui-exp-run-conflict'
        fn_id = 'fn_conflict'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        raw_act0_a = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act0_b = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/other_input', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '资源冲突表单',
            'description': '同一状态存在多个不同控件动作',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': self.canonicalize_action(raw_act0_a, persisted_only=True)},
                {'step_index': 1, 'source_state_id': 's2', 'action': self.canonicalize_action(raw_act1, persisted_only=True)},
                {'step_index': 2, 'source_state_id': 's3', 'action': self.canonicalize_action(raw_act2, persisted_only=True)}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(tool_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(marker)
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'revision': 1, 'processed_run_ids': [run_id],
            'items': [{'id': 'exp_conflict', 'package_name': 'cn.com.omnimind.androidgui.test', 'status': 'trial', 'enabled': True, 'source_run_id': run_id, 'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}, 'successful_run_ids': []}]
        })
        # 来源 run 中 s1 状态存在两个不同的动作资源：raw_act0_a 和 raw_act0_b
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0_a, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's1', 'action': raw_act0_b, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 3, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}, 'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}})
        out, code = pca.run_assert_phone_control('DEVICE_TEST', marker, 'experience', phase='round1', expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01', db=self.db, adb_runner=self.mock_adb_runner)
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'source_action_resource_conflict')

    def test_experience_round1_state_xml_fallback_resolves_resource_id(self):
        """当来源动作缺乏 node_resource_id 时，通过只读 State XML 与真实坐标确证目标控件。"""
        marker = 'OOB_LIVE_GUI_EXP_XML_FALLBACK'
        run_id = 'gui-exp-run-xml'
        fn_id = 'fn_xml_fallback'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})

        # 来源动作无 node_resource_id，仅有坐标和 text
        raw_act0 = {'tool': 'input_text', 'args': {'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'x': 500, 'y': 800}}

        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': 'XML确证表单',
            'description': '来源无node_resource_id，经State XML成功确证',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[0].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[1].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 'state_xml_01', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 1, 'source_state_id': 'state_xml_02', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 2, 'source_state_id': 'state_xml_03', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 800}}}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(tool_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(marker)
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'revision': 1, 'processed_run_ids': [run_id],
            'items': [{'id': 'exp_xml', 'package_name': 'cn.com.omnimind.androidgui.test', 'status': 'trial', 'enabled': True, 'source_run_id': run_id, 'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}, 'successful_run_ids': []}]
        })

        # 构造沙盒 State JSON 与 XML 文件供回退解析
        for sid, rid, bounds in [
            ('state_xml_01', 'cn.com.omnimind.androidgui.test:id/input_first', '[400,150][600,250]'),
            ('state_xml_02', 'cn.com.omnimind.androidgui.test:id/input_second', '[400,250][600,350]'),
            ('state_xml_03', 'cn.com.omnimind.androidgui.test:id/btn_form_submit', '[400,750][600,850]'),
        ]:
            stem = f"files/run_logs/states/{pca.safe_hash(sid)}_{pca.safe_file_part(sid)}"
            self.files[stem + '.json'] = json.dumps({
                'state_id': sid, 'package_name': 'cn.com.omnimind.androidgui.test',
                'display': {'width': 1000, 'height': 1000}
            })
            self.files[stem + '.xml'] = f'<hierarchy><node package="cn.com.omnimind.androidgui.test" resource-id="{rid}" bounds="{bounds}"/></hierarchy>'

        source_steps = [
            {'step_index': 0, 'before_state_id': 'state_xml_01', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 'state_xml_02', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 'state_xml_03', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}, 'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}})
        out, code = pca.run_assert_phone_control('DEVICE_TEST', marker, 'experience', phase='round1', expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01', db=self.db, adb_runner=self.mock_adb_runner)
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['registered_function_id'], fn_id)

    def test_experience_round1_5steps_production_projection_and_trajectory_split(self):
        """真实生产投影：save_function 拆分产出 11 步完整轨迹与 5 步表单 Function，双输入绑定 steps[1]/steps[3] 且末尾 step[4] 提交，确证排除轨迹并唯一识别表单 Function。"""
        marker = 'OOB_LIVE_GUI_EXP_PROD_5STEPS'
        run_id = 'gui-d89ca6e9-production-run'
        fn_traj_id = 'fn_complete_trajectory_11steps'
        fn_form_id = 'fn_submit_form_5steps'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })

        # 来源 run 中的 3 个 canonical 动作 (无临时 node_resource_id，仅依靠 source_state_id 与坐标)
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': 'ALPHA', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': 'BETA', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        # 11 步完整轨迹 Function (包含导航按钮，应被排除)
        fn_traj = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_traj_id,
            'name': '完整导航轨迹',
            'description': '包含菜单导航与标记输入的全局轨迹',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[4].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[6].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's_nav', 'action': {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_menu_form', 'x': 500, 'y': 100}}},
                {'step_index': 1, 'source_state_id': 's_nav', 'action': {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_back', 'x': 100, 'y': 100}}},
                {'step_index': 2, 'source_state_id': 's_nav', 'action': {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_menu_form', 'x': 500, 'y': 100}}},
                {'step_index': 3, 'source_state_id': 's1', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 200}}},
                {'step_index': 4, 'source_state_id': 's1', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 5, 'source_state_id': 's2', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 300}}},
                {'step_index': 6, 'source_state_id': 's2', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 7, 'source_state_id': 's3', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 800}}},
                {'step_index': 8, 'source_state_id': 's_end', 'action': {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_back', 'x': 100, 'y': 100}}},
                {'step_index': 9, 'source_state_id': 's_end', 'action': {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_back', 'x': 100, 'y': 100}}},
                {'step_index': 10, 'source_state_id': 's_end', 'action': {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_back', 'x': 100, 'y': 100}}}
            ],
            'checker_rules': [], 'agent_visible': True
        }

        # 5 步真实生产表单 Function：focus click + text-only input x 2 + submit click
        fn_form = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_form_id,
            'name': '提交表单',
            'description': '生产投影生成的5步聚焦点击+文本输入+提交表单',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[1].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[3].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 200}}},
                {'step_index': 1, 'source_state_id': 's1', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 2, 'source_state_id': 's2', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 300}}},
                {'step_index': 3, 'source_state_id': 's2', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 4, 'source_state_id': 's3', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 800}}}
            ],
            'checker_rules': [], 'agent_visible': True
        }

        # save_function 返回了两个 function_ids
        save_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_traj_id, fn_form_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(save_payload)))

        # get_function 确认了两者
        get_payload_traj = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_traj_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': fn_traj}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload_traj)))
        get_payload_form = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_form_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': fn_form}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload_form)))
        self.add_assistant(marker)

        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'revision': 1, 'processed_run_ids': [run_id],
            'items': [{'id': 'exp_prod_rule', 'package_name': 'cn.com.omnimind.androidgui.test', 'status': 'trial', 'enabled': True, 'source_run_id': run_id, 'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}, 'successful_run_ids': []}]
        })
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({
            'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}
        })
        out, code = pca.run_assert_phone_control('DEVICE_TEST', marker, 'experience', phase='round1', expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01', db=self.db, adb_runner=self.mock_adb_runner)
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['registered_function_id'], fn_form_id)

    def test_experience_round1_coordinate_epsilon_legal(self):
        """坐标微小浮点误差 (abs delta <= 1e-9) 符合边界规范，确证通过 (Exit 0)。"""
        marker = 'OOB_LIVE_GUI_EXP_COORD_EPS'
        run_id = 'gui-exp-run-coord-eps'
        fn_id = 'fn_coord_eps'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        # Function 中坐标携带 <= 1e-9 的微小浮点差
        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': 'Epsilon坐标表单',
            'description': '坐标在 1e-9 容差内',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[1].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[3].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': {'tool': 'click', 'args': {'x': 500.0000000005, 'y': 200.0}}},
                {'step_index': 1, 'source_state_id': 's1', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 2, 'source_state_id': 's2', 'action': {'tool': 'click', 'args': {'x': 500.0, 'y': 299.9999999995}}},
                {'step_index': 3, 'source_state_id': 's2', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 4, 'source_state_id': 's3', 'action': {'tool': 'click', 'args': {'x': 500.0000000008, 'y': 800.0}}}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(tool_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(marker)
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'revision': 1, 'processed_run_ids': [run_id],
            'items': [{'id': 'exp_coord_eps', 'package_name': 'cn.com.omnimind.androidgui.test', 'status': 'trial', 'enabled': True, 'source_run_id': run_id, 'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}, 'successful_run_ids': []}]
        })
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}, 'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}})
        out, code = pca.run_assert_phone_control('DEVICE_TEST', marker, 'experience', phase='round1', expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01', db=self.db, adb_runner=self.mock_adb_runner)
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['registered_function_id'], fn_id)

    def test_experience_round1_coordinate_real_offset_rejected(self):
        """坐标真实偏移 (> 1e-9) 严禁通过，判定 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_COORD_REAL_OFFSET'
        run_id = 'gui-exp-run-coord-offset'
        fn_id = 'fn_coord_offset'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        # Function 中聚焦点击产生实质坐标偏移 (x=505 与来源 500 差距 5.0 >> 1e-9)
        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '偏移坐标表单',
            'description': '聚焦点击产生真实偏移',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[1].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[3].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': {'tool': 'click', 'args': {'x': 505.0, 'y': 200.0}}},
                {'step_index': 1, 'source_state_id': 's1', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 2, 'source_state_id': 's2', 'action': {'tool': 'click', 'args': {'x': 500.0, 'y': 300.0}}},
                {'step_index': 3, 'source_state_id': 's2', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 4, 'source_state_id': 's3', 'action': {'tool': 'click', 'args': {'x': 500.0, 'y': 800.0}}}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(tool_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(marker)
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'revision': 1, 'processed_run_ids': [run_id],
            'items': [{'id': 'exp_coord_offset', 'package_name': 'cn.com.omnimind.androidgui.test', 'status': 'trial', 'enabled': True, 'source_run_id': run_id, 'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}, 'successful_run_ids': []}]
        })
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}, 'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}})
        out, code = pca.run_assert_phone_control('DEVICE_TEST', marker, 'experience', phase='round1', expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01', db=self.db, adb_runner=self.mock_adb_runner)
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'form_function_ambiguous')

    def test_experience_round1_arbitrary_click_cannot_graft_to_input(self):
        """孤立或非同状态配对的任意 click 动作严禁嫁接为 input 聚焦点击，严格判定 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_CLICK_GRAFT'
        run_id = 'gui-exp-run-click-graft'
        fn_id = 'fn_click_graft'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        # Function 中在不同状态放入一个 click，企图嫁接为 input_first 的聚焦动作
        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '嫁接点击表单',
            'description': '非同状态的孤立点击',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[1].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[3].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 'other_state_unrelated', 'action': {'tool': 'click', 'args': {'x': 500.0, 'y': 200.0}}},
                {'step_index': 1, 'source_state_id': 's1', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 2, 'source_state_id': 's2', 'action': {'tool': 'click', 'args': {'x': 500.0, 'y': 300.0}}},
                {'step_index': 3, 'source_state_id': 's2', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 4, 'source_state_id': 's3', 'action': {'tool': 'click', 'args': {'x': 500.0, 'y': 800.0}}}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        tool_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(tool_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(marker)
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'revision': 1, 'processed_run_ids': [run_id],
            'items': [{'id': 'exp_graft', 'package_name': 'cn.com.omnimind.androidgui.test', 'status': 'trial', 'enabled': True, 'source_run_id': run_id, 'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}, 'successful_run_ids': []}]
        })
        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(run_id, steps=source_steps)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}, 'form_values': {'first': 'SYNTHETIC_FIRST_01', 'second': 'SYNTHETIC_SECOND_01'}})
        out, code = pca.run_assert_phone_control('DEVICE_TEST', marker, 'experience', phase='round1', expected_form_first='SYNTHETIC_FIRST_01', expected_form_second='SYNTHETIC_SECOND_01', db=self.db, adb_runner=self.mock_adb_runner)
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'form_function_ambiguous')

    def test_experience_subsequent_phases_missing_expected_experience_id_blocks(self):
        """后续阶段若未传递 expected_experience_id，严格返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_R2_NO_ID'
        run_id = 'gui-run-r2-noid'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'success': True, 'finished_at_ms': 1773000000000, 'steps': []
        })
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1',
            'items': []
        })
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round2',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'expected_experience_id_required')

    def test_experience_subsequent_phases_nonexistent_experience_id_fails(self):
        """后续阶段若指定的 expected_experience_id 在 Sidecar 中不存在，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_EXP_R2_NOT_FOUND'
        run_id = 'gui-run-r2-nf'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'success': True, 'finished_at_ms': 1773000000000, 'steps': []
        })
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1',
            'items': [
                {'id': 'other_rule', 'rule': {'target_resource_id': 'other'}}
            ]
        })
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round2',
            expected_experience_id='target_does_not_exist',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'round2_target_rule_not_found')
    def test_experience_round1_preexisting_rule_blocks(self):
        """Sidecar 中已存在匹配规则，独立测试环境不可污染，严格返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_R1_DIRTY'
        run_id = 'gui-exp-run-01-new'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        # 来源 run_id 为旧 run
        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': ['gui-exp-run-00-old'],
            'items': [
                {
                    'id': 'exp_rule_submit_old',
                    'package_name': 'cn.com.omnimind.androidgui.test',
                    'enabled': True,
                    'source_run_id': 'gui-exp-run-00-old',
                    'rule': {'action': 'click', 'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}})
        self.store_sealed_run(run_id)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'preexisting_experience_rule_in_sidecar')

    def test_experience_sidecar_contains_forbidden_value_fails(self):
        """Sidecar 中违规持久化了输入文本值，断言明确失败。"""
        marker = 'OOB_LIVE_GUI_EXP_LEAK'
        run_id = 'gui-exp-run-leak'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}})
        self.add_assistant(marker)

        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'items': [
                {
                    'id': 'exp_leak',
                    'rule': {
                        'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit',
                        'value': 'SECRET_USER_INPUT'  # 严禁存在的字段
                    }
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK']})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='round1', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'sidecar_rule_contains_forbidden_field')

    def test_experience_round2_and_round3_promotion(self):
        """测试 Round 2 累计 1 次成功与 Round 3 累计 2 次转为 verified。"""
        marker_r2 = 'OOB_LIVE_GUI_EXP_R2'
        run_id_r2 = 'gui-exp-run-02'
        self.add_user(marker_r2)
        self.add_vlm_task({
            'run_id': run_id_r2,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker_r2)

        exp_id = 'exp_rule_submit_01'
        self.files[pca.get_run_log_rel_path(run_id_r2)] = json.dumps({
            'run_id': run_id_r2,
            'success': True,
            'finished_at_ms': 1773000020000,
            'steps': [
                {'action': {'tool': 'click'}, 'metadata': {'app_experience_ids': [exp_id]}}
            ]
        })
        sidecar_r2 = {
            'schema_version': 'omniflow.app-experiences.v1',
            'items': [
                {
                    'id': exp_id,
                    'status': 'trial',
                    'successful_run_ids': [run_id_r2],
                    'rule': {'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar_r2)
        self.fixture_state.update({
            'marker': marker_r2, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker_r2, 'experience', phase='round2', expected_experience_id=exp_id,
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertEqual(out['details']['experience_status'], 'trial')

        # Round 3
        marker_r3 = 'OOB_LIVE_GUI_EXP_R3'
        run_id_r3 = 'gui-exp-run-03'
        self.add_user(marker_r3, conversation_id=2)
        self.add_vlm_task({
            'run_id': run_id_r3,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(marker_r3, conversation_id=2)

        self.files[pca.get_run_log_rel_path(run_id_r3)] = json.dumps({
            'run_id': run_id_r3,
            'success': True,
            'finished_at_ms': 1773000030000,
            'steps': [
                {'action': {'tool': 'click'}, 'metadata': {'app_experience_ids': [exp_id]}}
            ]
        })
        sidecar_r3 = {
            'schema_version': 'omniflow.app-experiences.v1',
            'items': [
                {
                    'id': exp_id,
                    'status': 'verified',
                    'successful_run_ids': [run_id_r2, run_id_r3],
                    'rule': {'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar_r3)
        self.fixture_state.update({
            'marker': marker_r3, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker_r3, 'experience', phase='round3', expected_experience_id=exp_id,
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertEqual(out['details']['experience_status'], 'verified')

    def test_experience_disabled_rule_not_used(self):
        """经验停用后，执行正常成功但 RunLog 不包含该经验 ID。"""
        marker = 'OOB_LIVE_GUI_EXP_DISABLED'
        run_id = 'gui-exp-run-dis'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        exp_id = 'exp_rule_submit_01'
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000040000,
            'steps': [
                {'action': {'tool': 'click'}, 'metadata': {'app_experience_ids': []}}
            ]
        })
        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'items': [
                {
                    'id': exp_id,
                    'enabled': False,
                    'rule': {'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.fixture_state.update({
            'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='disabled', expected_experience_id=exp_id,
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertFalse(out['details']['experience_enabled'])

    def test_experience_rollback_phases(self):
        """连续两次归因失败触发退役 (retired)。"""
        marker = 'OOB_LIVE_GUI_EXP_ROLLBACK'
        run_id = 'gui-exp-run-rb'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True})
        self.add_assistant(marker)

        exp_id = 'exp_rule_submit_01'
        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'items': [
                {
                    'id': exp_id,
                    'status': 'retired',
                    'consecutive_failures': 2,
                    'rule': {'target_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}
                }
            ]
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'finished_at_ms': 1773000000000, 'steps': []
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'entry_disabled': True})
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='rollback2', expected_experience_id=exp_id,
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertEqual(out['details']['experience_status'], 'retired')

    def test_experience_replay_success(self):
        """测试 Function 回放阶段确证：必须关联源 Run 注册的 Function ID 与回放执行证据。"""
        source_marker = 'OOB_LIVE_GUI_EXP_SRC'
        src_run_id = 'gui-exp-run-src'
        fn_id = 'fn_submit_form_01'
        self.setup_valid_source_function_turn(source_marker, src_run_id, fn_id)

        replay_marker = 'OOB_LIVE_GUI_EXP_REPLAY'
        replay_run_id = 'gui-exp-run-replay'
        self.add_user(replay_marker, conversation_id=2)
        self.add_vlm_task({
            'run_id': replay_run_id,
            'success': True,
            'function_id': fn_id,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)

        self.files[pca.get_run_log_rel_path(replay_run_id)] = json.dumps({
            'run_id': replay_run_id,
            'success': True,
            'finished_at_ms': 1773000045000,
            'steps': [
                {
                    'action': {'tool': 'input_text', 'args': {'text': 'SYNTHETIC_PARAM_A', 'x': 500, 'y': 200}},
                    'result': {'success': True},
                    'metadata': {'function_id': fn_id}
                },
                {
                    'action': {'tool': 'input_text', 'args': {'text': 'SYNTHETIC_PARAM_B', 'x': 500, 'y': 300}},
                    'result': {'success': True},
                    'metadata': {'function_id': fn_id}
                },
                {
                    'action': {'tool': 'click', 'args': {'x': 500, 'y': 800}},
                    'result': {'success': True},
                    'metadata': {'function_id': fn_id}
                }
            ]
        })
        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [replay_run_id],
            'items': []
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.fixture_state.update({
            'marker': replay_marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'SYNTHETIC_PARAM_A', 'second': 'SYNTHETIC_PARAM_B'}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            expected_form_first='SYNTHETIC_PARAM_A', expected_form_second='SYNTHETIC_PARAM_B',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['source_function_id'], fn_id)
        self.assertTrue(out['details']['replay_verified'])

    def test_experience_replay_5steps_production_success(self):
        """测试生产投影 5 步 Function 真实回放确证：聚焦点击 + 2个输入绑定 + 提交全部成功执行并关联 Function ID。"""
        source_marker = 'OOB_LIVE_GUI_EXP_SRC_5S'
        src_run_id = 'gui-exp-run-src-5s'
        fn_id = 'fn_submit_form_5s'
        self.setup_valid_source_function_turn(source_marker, src_run_id, fn_id)

        replay_marker = 'OOB_LIVE_GUI_EXP_REPLAY_5S'
        replay_run_id = 'gui-exp-run-replay-5s'
        self.add_user(replay_marker, conversation_id=2)
        self.add_vlm_task({
            'run_id': replay_run_id,
            'success': True,
            'function_id': fn_id,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)

        self.files[pca.get_run_log_rel_path(replay_run_id)] = json.dumps({
            'run_id': replay_run_id,
            'success': True,
            'finished_at_ms': 1773000045000,
            'steps': [
                {'action': {'tool': 'click', 'args': {'x': 500, 'y': 200}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}},
                {'action': {'tool': 'input_text', 'args': {'text': 'PROD_PARAM_A', 'x': 500, 'y': 200}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}},
                {'action': {'tool': 'click', 'args': {'x': 500, 'y': 300}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}},
                {'action': {'tool': 'input_text', 'args': {'text': 'PROD_PARAM_B', 'x': 500, 'y': 300}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}},
                {'action': {'tool': 'click', 'args': {'x': 500, 'y': 800}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}}
            ]
        })
        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [replay_run_id],
            'items': []
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.fixture_state.update({
            'marker': replay_marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'PROD_PARAM_A', 'second': 'PROD_PARAM_B'}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            expected_form_first='PROD_PARAM_A', expected_form_second='PROD_PARAM_B',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['source_function_id'], fn_id)
        self.assertTrue(out['details']['replay_verified'])

    def test_experience_replay_failed_step_with_vlm_fallback_fails(self):
        """回放过程中若 Function 步骤执行失败 (如 omnitransfer_error、origin='blocked') 导致回退普通 VLM，哪怕整体 FORM_OK 且 diagnostics 记录了 Function，严禁冒充回放成功，断言明确失败 (Exit 1)。"""
        source_marker = 'OOB_LIVE_GUI_SRC_TRANSFER_FAIL'
        src_run_id = 'gui-exp-run-src-tf'
        fn_id = 'submit_form'
        self.setup_valid_source_function_turn(source_marker, src_run_id, fn_id)

        replay_marker = 'OOB_LIVE_GUI_REPLAY_TRANSFER_FAIL'
        replay_run_id = 'gui-02923646-replay-fail'
        self.add_user(replay_marker, conversation_id=2)
        self.add_vlm_task({
            'run_id': replay_run_id,
            'success': True,
            'function_id': fn_id,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)

        # 真实场景：step 5 回放失败 (origin=blocked, success=False)，后续 3 步由普通 VLM 接管完成
        self.files[pca.get_run_log_rel_path(replay_run_id)] = json.dumps({
            'run_id': replay_run_id,
            'success': True,
            'finished_at_ms': 1773000045000,
            'diagnostics': {'source': 'function', 'function_id': fn_id},
            'steps': [
                {
                    'step_index': 5,
                    'action': {'tool': 'click', 'args': {'x': 500, 'y': 200}},
                    'result': {'success': False, 'error': "omnitransfer_error:rank_action_candidates() got an unexpected keyword argument 'source_package_name'"},
                    'metadata': {'origin': 'blocked', 'function_id': fn_id}
                },
                {
                    'step_index': 6,
                    'action': {'tool': 'input_text', 'args': {'text': 'FALLBACK_A', 'x': 500, 'y': 200}},
                    'result': {'success': True},
                    'metadata': {'origin': 'vlm'}
                },
                {
                    'step_index': 7,
                    'action': {'tool': 'input_text', 'args': {'text': 'FALLBACK_B', 'x': 500, 'y': 300}},
                    'result': {'success': True},
                    'metadata': {'origin': 'vlm'}
                },
                {
                    'step_index': 8,
                    'action': {'tool': 'click', 'args': {'x': 500, 'y': 800}},
                    'result': {'success': True},
                    'metadata': {'origin': 'vlm'}
                }
            ]
        })
        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [replay_run_id],
            'items': []
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.fixture_state.update({
            'marker': replay_marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'FALLBACK_A', 'second': 'FALLBACK_B'}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            expected_form_first='FALLBACK_A', expected_form_second='FALLBACK_B',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertFalse(out.get('passed', False))
        self.assertEqual(out['category'], 'replay_not_associated_with_source_function')

    def test_experience_replay_incomplete_function_steps_fails(self):
        """若 Function 回放仅成功执行部分步骤 (如仅完成 1 次输入，缺少第 2 次输入或提交)，严禁判定回放成功 (Exit 1)。"""
        source_marker = 'OOB_LIVE_GUI_SRC_INCOMPLETE'
        src_run_id = 'gui-exp-run-src-inc'
        fn_id = 'fn_submit_form_inc'
        self.setup_valid_source_function_turn(source_marker, src_run_id, fn_id)

        replay_marker = 'OOB_LIVE_GUI_REPLAY_INCOMPLETE'
        replay_run_id = 'gui-exp-run-replay-inc'
        self.add_user(replay_marker, conversation_id=2)
        self.add_vlm_task({
            'run_id': replay_run_id,
            'success': True,
            'function_id': fn_id,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)

        # 仅包含 1 次关联 Function 的 input_text，缺少完整表单双输入与提交
        self.files[pca.get_run_log_rel_path(replay_run_id)] = json.dumps({
            'run_id': replay_run_id,
            'success': True,
            'finished_at_ms': 1773000045000,
            'steps': [
                {
                    'action': {'tool': 'input_text', 'args': {'text': 'PARAM_A', 'x': 500, 'y': 200}},
                    'result': {'success': True},
                    'metadata': {'function_id': fn_id}
                }
            ]
        })
        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [replay_run_id],
            'items': []
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.fixture_state.update({
            'marker': replay_marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'PARAM_A', 'second': 'PARAM_B'}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            expected_form_first='PARAM_A', expected_form_second='PARAM_B',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'replay_not_associated_with_source_function')

    def test_experience_replay_missing_source_marker_blocks(self):
        """Replay 阶段未传 source_marker 前置标记，严格返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_EXP_REPLAY_NO_SRC'
        run_id = 'gui-run-rep-no-src'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'success': True, 'finished_at_ms': 1773000000000, 'steps': []
        })
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'items': []
        })
        self.fixture_state.update({
            'marker': marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'experience', phase='replay',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'replay_source_marker_missing')
    def test_experience_replay_unregistered_source_function_blocks(self):
        """来源 Turn 存在但未注册 Function ID，回放前置证据不成立，返回 blocked (Exit 3)。"""
        source_marker = 'OOB_LIVE_GUI_SRC_NO_FN'
        self.add_user(source_marker, conversation_id=1)
        self.add_vlm_task({'run_id': 'run-src-no-fn', 'success': True}, conversation_id=1)
        self.add_assistant(source_marker, conversation_id=1)

        replay_marker = 'OOB_LIVE_GUI_REPLAY_NO_FN'
        run_id = 'gui-run-rep-no-fn'
        self.add_user(replay_marker, conversation_id=2)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'success': True, 'finished_at_ms': 1773000000000, 'steps': []
        })
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'items': []
        })
        self.fixture_state.update({
            'marker': replay_marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'source_function_not_registered')
    def test_experience_replay_ordinary_run_cannot_impersonate_replay_fails(self):
        """普通 GUI run 即使 FORM_OK 且 run 成功，若未关联真实 Function 回放 metadata，断言明确失败 (Exit 1)。"""
        source_marker = 'OOB_LIVE_GUI_SRC_VALID'
        fn_id = 'fn_valid_01'
        self.setup_valid_source_function_turn(source_marker, 'run-src-valid', fn_id)

        replay_marker = 'OOB_LIVE_GUI_REPLAY_ORDINARY'
        run_id = 'gui-run-ordinary'
        self.add_user(replay_marker, conversation_id=2)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)
        # 普通 run 的 steps 没有任何 function_id 关联
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000045000,
            'steps': [
                {'action': {'tool': 'click'}, 'result': {'success': True}, 'metadata': {}}
            ]
        })
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'items': []
        })
        self.fixture_state.update({
            'marker': replay_marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'PARAM_A', 'second': 'PARAM_B'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            expected_form_first='PARAM_A', expected_form_second='PARAM_B',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'replay_not_associated_with_source_function')
    def test_experience_replay_ordinary_vlm_inputs_with_top_level_function_id_fails(self):
        """普通 VLM 输入即便在 vlm_task 顶层携带 function_id，若 RunLog step 缺乏真实回放 metadata，必须判定失败 (Exit 1)。"""
        source_marker = 'OOB_LIVE_GUI_SRC_VALID_REPLAY'
        fn_id = 'fn_valid_replay_01'
        self.setup_valid_source_function_turn(source_marker, 'run-src-valid-replay', fn_id)

        replay_marker = 'OOB_LIVE_GUI_REPLAY_ORDINARY_VLM'
        run_id = 'gui-run-ordinary-vlm'
        self.add_user(replay_marker, conversation_id=2)
        # 顶层 vlm_task 虽包含 function_id，但 step 没有任何 function_id 绑定，属于普通 VLM 输入冒充
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'function_id': fn_id,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000045000,
            'steps': [
                {'action': {'tool': 'click', 'args': {'x': 100, 'y': 200, 'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit'}}, 'result': {'success': True}, 'metadata': {}}
            ]
        })
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'items': []
        })
        self.fixture_state.update({
            'marker': replay_marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'PARAM_A', 'second': 'PARAM_B'}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            expected_form_first='PARAM_A', expected_form_second='PARAM_B',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'replay_not_associated_with_source_function')

    def test_experience_replay_independent_source_registration_turn_success(self):
        """独立注册专用 Turn 支持：来源 Turn 仅包含 save_function + get_function（无 vlm_task），成功 save_function.run_id 唯一非空时读取 exact sealed source 确证 Function，并在回放阶段成功完成断言 (Exit 0)。"""
        source_marker = 'OOB_LIVE_GUI_EXP_INDEP_REG_SRC'
        src_run_id = 'gui-d89ca6e9-sealed-src'
        fn_id = 'submit_form'

        # 来源 Turn 仅有用户消息与 tool_event (无 vlm_task)，模拟真实独立注册 Turn
        self.add_user(source_marker, conversation_id=1)
        raw_act0 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_first', 'text': '1', 'x': 500, 'y': 200}}
        raw_act1 = {'tool': 'input_text', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/input_second', 'text': '2', 'x': 500, 'y': 300}}
        raw_act2 = {'tool': 'click', 'args': {'node_resource_id': 'cn.com.omnimind.androidgui.test:id/btn_form_submit', 'x': 500, 'y': 800}}

        form_fn = {
            'schema_version': 'omniflow.function.v2',
            'function_id': fn_id,
            'name': '提交表单',
            'description': '独立注册表单',
            'input_schema': {'type': 'object', 'properties': {'first': {'type': 'string'}, 'second': {'type': 'string'}}, 'required': ['first', 'second'], 'additionalProperties': False},
            'bindings': [
                {'source': '$.arguments.first', 'target': '$.steps[1].action.args.text'},
                {'source': '$.arguments.second', 'target': '$.steps[3].action.args.text'}
            ],
            'steps': [
                {'step_index': 0, 'source_state_id': 's1', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 200}}},
                {'step_index': 1, 'source_state_id': 's1', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 2, 'source_state_id': 's2', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 300}}},
                {'step_index': 3, 'source_state_id': 's2', 'action': {'tool': 'input_text', 'args': {'text': ''}}},
                {'step_index': 4, 'source_state_id': 's3', 'action': {'tool': 'click', 'args': {'x': 500, 'y': 800}}}
            ],
            'checker_rules': [], 'agent_visible': True
        }
        save_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': src_run_id, 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(save_payload)))
        get_payload = {
            'toolName': 'get_function',
            'argsJson': json.dumps({'function_id': fn_id}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function': form_fn}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(get_payload)))
        self.add_assistant(source_marker, conversation_id=1)

        source_steps = [
            {'step_index': 0, 'before_state_id': 's1', 'action': raw_act0, 'result': {'success': True}},
            {'step_index': 1, 'before_state_id': 's2', 'action': raw_act1, 'result': {'success': True}},
            {'step_index': 2, 'before_state_id': 's3', 'action': raw_act2, 'result': {'success': True}}
        ]
        self.store_sealed_run(src_run_id, steps=source_steps)

        # 回放 Turn
        replay_marker = 'OOB_LIVE_GUI_REPLAY_INDEP_SUCCESS'
        replay_run_id = 'gui-b4af531d-1d55-48e4-b563-bee08f5c0146'
        self.add_user(replay_marker, conversation_id=2)
        self.add_vlm_task({
            'run_id': replay_run_id,
            'success': True,
            'function_id': fn_id,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)

        self.files[pca.get_run_log_rel_path(replay_run_id)] = json.dumps({
            'run_id': replay_run_id,
            'success': True,
            'finished_at_ms': 1773000045000,
            'steps': [
                {'action': {'tool': 'click', 'args': {'x': 500, 'y': 200}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}},
                {'action': {'tool': 'input_text', 'args': {'text': 'NEW_VAL_A', 'x': 500, 'y': 200}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}},
                {'action': {'tool': 'click', 'args': {'x': 500, 'y': 300}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}},
                {'action': {'tool': 'input_text', 'args': {'text': 'NEW_VAL_B', 'x': 500, 'y': 300}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}},
                {'action': {'tool': 'click', 'args': {'x': 500, 'y': 800}}, 'result': {'success': True}, 'metadata': {'function_id': fn_id}}
            ]
        })
        sidecar = {
            'schema_version': 'omniflow.app-experiences.v1',
            'revision': 1,
            'processed_run_ids': [replay_run_id],
            'items': []
        }
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps(sidecar)
        self.fixture_state.update({
            'marker': replay_marker,
            'display_id': 2,
            'status_flags': ['FORM_OK'],
            'counts': {'submit': 1},
            'form_values': {'first': 'NEW_VAL_A', 'second': 'NEW_VAL_B'}
        })
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            expected_form_first='NEW_VAL_A', expected_form_second='NEW_VAL_B',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertEqual(out['details']['source_run_id'], src_run_id)
        self.assertEqual(out['details']['source_function_id'], fn_id)
        self.assertTrue(out['details']['replay_verified'])

    def test_experience_replay_independent_source_turn_multiple_different_sources_rejected(self):
        """独立注册专用 Turn 若存在多个指向不同 run_id 的 save_function，严格判定来源歧义拒绝 (Exit 3)。"""
        source_marker = 'OOB_LIVE_GUI_EXP_INDEP_MULTI_SRC'
        fn_id = 'submit_form_multi'
        self.add_user(source_marker, conversation_id=1)

        # 存在两个不同的 save_function，分别指向 run-A 和 run-B
        save_payload_a = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': 'run-src-A', 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        save_payload_b = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': 'run-src-B', 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(save_payload_a)))
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(save_payload_b)))
        self.add_assistant(source_marker, conversation_id=1)

        replay_marker = 'OOB_LIVE_GUI_REPLAY_MULTI_SRC'
        run_id = 'gui-run-replay-multi'
        self.add_user(replay_marker, conversation_id=2)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'success': True, 'finished_at_ms': 1773000000000, 'steps': []
        })
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'items': []
        })
        self.fixture_state.update({
            'marker': replay_marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertIn(out['category'], ('source_function_not_registered', 'form_function_ambiguous'))

    def test_experience_replay_gui_turn_mismatched_save_run_id_rejected(self):
        """GUI Turn 中 save_function 的 run_id 与当前 GUI run_id 不一致时，严格判定来源歧义拒绝 (Exit 3)。"""
        source_marker = 'OOB_LIVE_GUI_EXP_MISMATCH_SRC'
        gui_run_id = 'gui-run-active'
        fn_id = 'submit_form_mismatch'
        self.add_user(source_marker, conversation_id=1)
        self.add_vlm_task({'run_id': gui_run_id, 'success': True}, conversation_id=1)

        # save_function 却指向了另一个 run_id: other-run-id
        save_payload = {
            'toolName': 'save_function',
            'argsJson': json.dumps({'run_id': 'other-run-id', 'enhance': True}),
            'success': True,
            'resultPreviewJson': json.dumps({'success': True, 'function_ids': [fn_id]}),
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)', (1, 'tool_event', json.dumps(save_payload)))
        self.add_assistant(source_marker, conversation_id=1)

        replay_marker = 'OOB_LIVE_GUI_REPLAY_MISMATCH_SRC'
        run_id = 'gui-run-replay-mismatch'
        self.add_user(replay_marker, conversation_id=2)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        }, conversation_id=2)
        self.add_assistant(replay_marker, conversation_id=2)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'success': True, 'finished_at_ms': 1773000000000, 'steps': []
        })
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1', 'items': []
        })
        self.fixture_state.update({
            'marker': replay_marker, 'display_id': 2, 'status_flags': ['FORM_OK'], 'counts': {'submit': 1}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', replay_marker, 'experience', phase='replay',
            source_marker=source_marker,
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertIn(out['category'], ('source_function_not_registered', 'form_function_ambiguous'))
    # ==========================================
    # 4. 中止取消与恢复 (cancel / recovery) 测试用例
    # ==========================================

    def test_active_wait_admission_requires_canonical_running_action_for_this_turn(self):
        """实际 consumer 真实投影：外层 running、内部 in_progress、各层身份一致且未终态时才准入。"""
        marker = 'OOB_LIVE_GUI_CANCEL_ADMISSION'
        self.add_user(marker)

        # 真实 ACP Reducer 投影形状（参考 agent://WaitEvidenceProjectionMap）
        inner_item = {
            'id': 'call-vlm-1',
            'toolCallId': 'call-vlm-1',
            'sessionId': 'sess_1',
            'turnId': 'turn_1',
            'status': 'in_progress',
            'title': '视觉操作',
            'content': [{'type': 'content', 'content': {'type': 'text', 'text': 'wait'}}],
            'rawOutput': {
                'run_id': 'gui-cfd830b7',
                'display_id': 47,
                'action': {'tool': 'wait', 'args': {'duration_ms': '10000'}}
            },
            'run_id': 'gui-cfd830b7'
        }
        payload = {
            'toolName': 'vlm_task',
            'status': 'running',
            'toolCallId': 'call-vlm-1',
            'turnId': 'turn_1',
            'taskId': '0ece5136f2',
            'sessionId': 'sess_1',
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'},
            'resultPreviewJson': json.dumps([{'type': 'content', 'content': {'type': 'text', 'text': 'wait'}}]),
            'rawResultJson': json.dumps(inner_item),
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(payload)),
        )
        # 1. 正在执行规范 wait 且各层身份一致：准入成功
        self.assertTrue(pca.wait_action_admitted(self.db, marker))

        # 2. 当前 Turn 一旦出现终态（如 cancelled），立即失去准入资格
        self.add_assistant(marker, stop_reason='cancelled')
        self.assertFalse(pca.wait_action_admitted(self.db, marker))

        # 3. 错轮/新消息：未发起的后继 Turn 绝不准入，已终态的前一 Turn 也绝不准入
        self.add_user('OOB_LIVE_GUI_CANCEL_NEXT')
        self.assertFalse(pca.wait_action_admitted(self.db, 'OOB_LIVE_GUI_CANCEL_NEXT'))
        self.assertFalse(pca.wait_action_admitted(self.db, marker))

    def test_wait_admission_rejects_terminal_wrong_identity_and_free_text(self):
        """覆盖跨身份冲突、身份缺失、终态与自由文字/旧 rootfake 负例。"""
        marker = 'OOB_LIVE_GUI_CANCEL_BAD_ADMISSION'
        self.add_user(marker)

        canonical_inner = {
            'id': 'call-current',
            'toolCallId': 'call-current',
            'status': 'in_progress',
            'run_id': 'gui-current',
            'rawOutput': {
                'run_id': 'gui-current',
                'action': {'tool': 'wait', 'args': {'duration_ms': 10000}}
            },
        }
        canonical_outer = {
            'toolName': 'vlm_task',
            'status': 'running',
            'toolCallId': 'call-current',
            'turnId': 'turn-current',
            'sessionId': 'session-current',
            'streamMeta': {'sessionId': 'session-current', 'turnId': 'turn-current'},
        }

        # 1. 跨身份与身份缺失负例
        identity_cases = [
            # 外部 sessionId 冲突
            ('outer_session_conflict', {**canonical_outer, 'sessionId': 'session-other'}, canonical_inner),
            # 内部 sessionId 冲突
            ('inner_session_conflict', canonical_outer, {**canonical_inner, 'sessionId': 'session-other'}),
            # 外部 turnId 错轮冲突
            ('outer_turn_conflict', {**canonical_outer, 'turnId': 'turn-other'}, canonical_inner),
            # 内部 turnId 错轮冲突
            ('inner_turn_conflict', canonical_outer, {**canonical_inner, 'turnId': 'turn-other'}),
            # 外部 toolCallId 缺失
            ('missing_outer_tool_call_id', {**canonical_outer, 'toolCallId': ''}, canonical_inner),
            # 内部 toolCallId 缺失
            ('missing_inner_tool_call_id', canonical_outer, {**canonical_inner, 'toolCallId': '', 'id': ''}),
            # toolCallId 跨调用冲突
            ('mismatched_tool_call_id', canonical_outer, {**canonical_inner, 'toolCallId': 'call-other', 'id': 'call-other'}),
            # run_id 内部与 rawOutput 冲突
            ('mismatched_run_id_output', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-other', 'action': {'tool': 'wait', 'args': {'duration_ms': 10000}}}}),
            # 外部 run_id 与内部 run_id 冲突
            ('mismatched_outer_run_id', {**canonical_outer, 'run_id': 'gui-other'}, canonical_inner),
            # 内部缺少 run_id
            ('missing_run_id', canonical_outer, {**canonical_inner, 'run_id': '', 'rawOutput': {'run_id': '', 'action': {'tool': 'wait', 'args': {'duration_ms': 10000}}}}),
            # 全 Turn 缺少 session 身份
            ('missing_session_identity', {k: v for k, v in canonical_outer.items() if k not in ('sessionId', 'streamMeta')}, canonical_inner),
            # 全 Turn 缺少 turn 身份
            ('missing_turn_identity', {k: v for k, v in canonical_outer.items() if k not in ('turnId', 'streamMeta')}, canonical_inner),
            # 仅有 taskId 但缺失真实 ACP turnId（taskId 不能替代缺失的 turnId）
            ('missing_acp_turn_with_task_id', {**{k: v for k, v in canonical_outer.items() if k not in ('turnId', 'streamMeta')}, 'taskId': 'task-only-123'}, canonical_inner),
        ]
        for name, outer, inner in identity_cases:
            with self.subTest(case=f'identity_{name}'):
                self.db.execute("DELETE FROM agent_conversation_entries WHERE entryType='tool_event'")
                payload = {**outer, 'rawResultJson': json.dumps(inner)}
                self.db.execute(
                    'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
                    (1, 'tool_event', json.dumps(payload)),
                )
                self.assertFalse(pca.wait_action_admitted(self.db, marker))

        # 2. 终态与覆写状态负例
        terminal_cases = [
            # 外层 status 为 completed 终态
            ('outer_completed', {**canonical_outer, 'status': 'completed'}, canonical_inner),
            # 外层 status 为 error
            ('outer_error', {**canonical_outer, 'status': 'error'}, canonical_inner),
            # 内部 item status 为 completed
            ('inner_completed', canonical_outer, {**canonical_inner, 'status': 'completed'}),
            # 终态覆盖 action（真实落盘后 rawResultJson 变为最终 result）
            ('overwritten_result', canonical_outer, {'run_id': 'gui-current', 'result': {'success': True}}),
        ]
        for name, outer, inner in terminal_cases:
            with self.subTest(case=f'terminal_{name}'):
                self.db.execute("DELETE FROM agent_conversation_entries WHERE entryType='tool_event'")
                payload = {**outer, 'rawResultJson': json.dumps(inner)}
                self.db.execute(
                    'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
                    (1, 'tool_event', json.dumps(payload)),
                )
                self.assertFalse(pca.wait_action_admitted(self.db, marker))

        # 3. 自由文字与旧 rootfake 负例
        freeword_cases = [
            # 旧 rootfake：action 直接置于外层 payload，未嵌套于 rawResultJson
            ('old_rootfake_action', {**canonical_outer, 'action': {'tool': 'wait', 'args': {'duration_ms': 10000}}}, None),
            # 旧 rootfake：rawOutput 直接置于外层 payload，未嵌套于 rawResultJson
            ('old_rootfake_raw_output', {**canonical_outer, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': 10000}}}}, None),
            # 外层伪装 in_progress 状态（无真实 reducer 规范化 running 证据）
            ('outer_in_progress', {**canonical_outer, 'status': 'in_progress'}, canonical_inner),
            # 仅有自由文字进展文案，无规范 action
            ('free_text_progress', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'progress': 'wait 10000', 'summary': '等待 10 秒'}}),
            # 非 wait 工具动作（如 click）
            ('non_wait_tool', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'click', 'args': {'x': 100, 'y': 200}}}}),
            # wait 时长不足 10000ms（整数及数字字符串）
            ('insufficient_duration_int', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': 5000}}}}),
            ('insufficient_duration_str', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': '5000'}}}}),
            # wait 时长超过合理上限 60000ms
            ('excessive_duration_int', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': 70000}}}}),
            ('excessive_duration_str', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': '60001'}}}}),
            # float string 形式（如 '10000.0'）
            ('float_string_duration', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': '10000.0'}}}}),
            # float 数值（如 10000.0）
            ('float_number_duration', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': 10000.0}}}}),
            # 布尔型
            ('boolean_duration', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': True}}}}),
            # NaN 字符串
            ('nan_string_duration', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': 'NaN'}}}}),
            # 空字符串
            ('empty_string_duration', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': ''}}}}),
            # 自由文本/字母词
            ('freeword_duration', canonical_outer, {**canonical_inner, 'rawOutput': {'run_id': 'gui-current', 'action': {'tool': 'wait', 'args': {'duration_ms': 'wait10000'}}}}),
        ]
        for name, outer, inner in freeword_cases:
            with self.subTest(case=f'freeword_{name}'):
                self.db.execute("DELETE FROM agent_conversation_entries WHERE entryType='tool_event'")
                payload = dict(outer)
                if inner is not None:
                    payload['rawResultJson'] = json.dumps(inner)
                self.db.execute(
                    'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
                    (1, 'tool_event', json.dumps(payload)),
                )
                self.assertFalse(pca.wait_action_admitted(self.db, marker))

    def test_cancel_success_and_no_late_confirm(self):
        marker = 'OOB_LIVE_GUI_CANCEL_1'
        run_id = 'gui-run-cancel-1'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': False})
        payload = {
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1', 'stopReason': 'cancelled'},
            'isLoading': False
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'assistant_message', json.dumps(payload))
        )
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'status': 'cancelled', 'success': False, 'finished_at_ms': 1773000005000, 'steps': []
        })
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1',
            'processed_run_ids': ['other-run-id'],
            'items': []
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'counts': {'confirm': 0}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='cancel', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])
        self.assertTrue(out['details']['app_experiences_verified'])
        self.assertEqual(out['details']['app_experiences_status'], 'verified_no_new_experience')

    def test_cancel_generated_candidate_rule_fails(self):
        """Cancelled 的 run 若产生了 Sidecar 候选规则，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_CANCEL_RULE_LEAK'
        run_id = 'gui-run-cancel-leak'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': False})
        payload = {
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1', 'stopReason': 'cancelled'},
            'isLoading': False
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'assistant_message', json.dumps(payload))
        )
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'status': 'cancelled', 'success': False, 'finished_at_ms': 1773000005000, 'steps': []
        })
        # 违规：产生以本 cancelled run_id 为源的经验条目
        self.files['workspace/.omnibot/omniflow/app-experiences.json'] = json.dumps({
            'schema_version': 'omniflow.app-experiences.v1',
            'processed_run_ids': [],
            'items': [{'id': 'exp-cancelled', 'source_run_id': run_id, 'rule': {}}]
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'counts': {'confirm': 0}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='cancel', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'cancelled_run_generated_experience_rule')

    def test_cancel_sidecar_missing_sets_not_checked(self):
        """Sidecar 不可用时，返回 not_checked，绝不冒充已验证无新经验。"""
        marker = 'OOB_LIVE_GUI_CANCEL_NO_SIDECAR'
        run_id = 'gui-run-cancel-no-sc'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': False})
        payload = {
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1', 'stopReason': 'cancelled'},
            'isLoading': False
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'assistant_message', json.dumps(payload))
        )
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'status': 'cancelled', 'success': False, 'finished_at_ms': 1773000005000, 'steps': []
        })
        # 无 sidecar 文件
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'counts': {'confirm': 0}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='cancel', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'cancel_experience_evidence_insufficient')
        self.assertFalse(out['details']['app_experiences_verified'])
        self.assertEqual(out['details']['app_experiences_status'], 'not_checked')
    def test_cancel_missing_vlm_task_blocks(self):
        """取消时无任何 vlm_task 准入记录，无法作为手机控制取消证据，返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_CANCEL_NO_RUN'
        self.add_user(marker)
        payload = {
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1', 'stopReason': 'cancelled'},
            'isLoading': False
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'assistant_message', json.dumps(payload))
        )
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'counts': {'confirm': 0}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='cancel', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'cancel_run_evidence_missing')

    def test_cancel_unsealed_runlog_returns_pending(self):
        """取消后 RunLog 尚未封存完成，返回 pending (Exit 2)。"""
        marker = 'OOB_LIVE_GUI_CANCEL_UNSEALED'
        run_id = 'gui-run-cancel-unsealed'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': False})
        payload = {
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1', 'stopReason': 'cancelled'},
            'isLoading': False
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'assistant_message', json.dumps(payload))
        )
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'status': 'cancelled', 'finished_at_ms': None
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'counts': {'confirm': 0}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='cancel', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 2)
        self.assertEqual(out['category'], 'waiting_for_sealed_cancelled_run_log')

    def test_cancel_marked_succeeded_fails(self):
        """已取消的 RunLog 被错误标记为 succeeded，断言失败。"""
        marker = 'OOB_LIVE_GUI_CANCEL_SUCCEEDED'
        run_id = 'gui-run-cancel-bad'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': False})
        payload = {
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1', 'stopReason': 'cancelled'},
            'isLoading': False
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'assistant_message', json.dumps(payload))
        )
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'status': 'succeeded', 'success': True, 'finished_at_ms': 1773000005000
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'counts': {'confirm': 0}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='cancel', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'cancel_run_log_marked_succeeded')

    def test_cancel_late_confirm_fails(self):
        """取消后 fixture 记录到了迟到的确认点击，断言失败。"""
        marker = 'OOB_LIVE_GUI_CANCEL_2'
        run_id = 'gui-run-cancel-late'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': False})
        payload = {
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1', 'stopReason': 'cancelled'},
            'isLoading': False
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'assistant_message', json.dumps(payload))
        )
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'status': 'cancelled', 'success': False, 'finished_at_ms': 1773000005000, 'steps': []
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'counts': {'confirm': 1}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='cancel', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'cancel_has_late_confirm')

    def test_cancel_recovery_keeps_waiting_before_marker_is_set(self):
        marker = 'OOB_LIVE_GUI_RECOVERY_RUNNING'
        self.add_user(marker)
        self.fixture_state['marker'] = 'OOB_LIVE_GUI_CANCEL_PREVIOUS'

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='recovery',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 2)
        self.assertEqual(out['status'], 'pending')

    def test_cancel_recovery_completed_with_wrong_marker_still_fails(self):
        marker = 'OOB_LIVE_GUI_RECOVERY_WRONG_MARKER'
        self.add_user(marker)
        self.add_vlm_task({'run_id': 'gui-recovery-wrong-marker', 'success': True})
        self.add_assistant(marker)
        self.fixture_state['marker'] = 'OOB_LIVE_GUI_CANCEL_PREVIOUS'

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='recovery',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'fixture_marker_mismatch')

    def test_cancel_recovery_success(self):
        marker = 'OOB_LIVE_GUI_RECOVERY_1'
        run_id = 'gui-run-recovery-1'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True})
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'success': True, 'finished_at_ms': 1773000050000,
            'steps': [{'action': {'tool': 'click'}, 'result': {'success': True}}]
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'counts': {'confirm': 1}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='recovery', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertTrue(out['passed'])

    def test_cancel_recovery_reused_run_id_fails(self):
        """恢复阶段复用了被取消 Turn 的旧 run_id，断言失败。"""
        src_marker = 'OOB_LIVE_GUI_CANCEL_SRC'
        rec_marker = 'OOB_LIVE_GUI_CANCEL_REC_REUSE'
        run_id = 'gui-run-same-id'
        self.add_user(src_marker, conversation_id=1)
        self.add_vlm_task({'run_id': run_id, 'success': False}, conversation_id=1)
        self.add_user(rec_marker, conversation_id=2)
        self.add_vlm_task({'run_id': run_id, 'success': True}, conversation_id=2)
        self.add_assistant(rec_marker, conversation_id=2)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'success': True, 'finished_at_ms': 1773000050000,
            'steps': [{'action': {'tool': 'click'}, 'result': {'success': True}}]
        })
        self.fixture_state.update({'marker': rec_marker, 'display_id': 2, 'counts': {'confirm': 1}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', rec_marker, 'cancel', phase='recovery', source_marker=src_marker,
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'recovery_reused_cancelled_run_id')

    def test_cancel_recovery_no_action_fails(self):
        """恢复阶段虽然 success=True 但 fixture 未观察到任何动作或标志，断言失败。"""
        marker = 'OOB_LIVE_GUI_RECOVERY_NO_ACT'
        run_id = 'gui-run-recovery-no-act'
        self.add_user(marker)
        self.add_vlm_task({'run_id': run_id, 'success': True})
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id, 'success': True, 'finished_at_ms': 1773000050000,
            'steps': [{'action': {'tool': 'click'}, 'result': {'success': True}}]
        })
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'counts': {'confirm': 0, 'tap': 0, 'long_press': 0, 'submit': 0},
            'status_flags': []
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'cancel', phase='recovery', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'recovery_no_fixture_action_observed')

    # ==========================================
    # 5. 模型路由与 Token (model) 测试用例
    # ==========================================

    def test_model_configuration_evidence_insufficient_blocks(self):
        """真机端到端在完成真实手势与前台校验后，对整体 model 配置因缺少 providerProfileId/budget 证据，严格返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_MODEL_1'
        run_id = 'gui-run-model-1'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'resolved_model': 'qwen3-vl-235b',
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000060000,
            'steps': [
                {
                    'action': {'tool': 'long_press', 'args': {'x': 500, 'y': 500, 'duration_ms': 800}},
                    'result': {'success': True},
                    'metadata': {'action_result': {'diagnostics': {'targetDisplayId': 2}}}
                }
            ],
            'diagnostics': {
                'token_usage': {
                    'resolved_model': 'qwen3-vl-235b',
                    'prompt_tokens': 500,
                    'completion_tokens': 50,
                    'total_tokens': 550
                }
            }
        })
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['LONG_PRESS_OK'],
            'counts': {'long_press': 1}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'model_configuration_evidence_insufficient')
        self.assertEqual(out['details']['resolved_model'], 'qwen3-vl-235b')
        self.assertEqual(out['details']['prompt_tokens'], 500)
        self.assertEqual(out['details']['completion_tokens'], 50)
        self.assertTrue(out['details']['route_verified'])
        self.assertTrue(out['details']['gesture_verified'])

    def test_model_missing_long_press_step_fails(self):
        """模型用例中若缺少真实的 >=800ms long_press 步骤，绝不以 token 消耗冒充已执行，断言失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_NO_STEP'
        run_id = 'gui-run-model-no-step'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'resolved_model': 'qwen3-vl-235b',
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        # steps 为空，缺少动作
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000060000,
            'steps': [],
            'diagnostics': {
                'token_usage': {'resolved_model': 'qwen3-vl-235b', 'prompt_tokens': 500, 'completion_tokens': 50}
            }
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['LONG_PRESS_OK'], 'counts': {'long_press': 1}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'missing_long_press_step')

    def test_model_model_asserted_finished_evidence_fails(self):
        """模型用例完成证据若是纯模型断言 (model_asserted)，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_FAKE_EVID'
        run_id = 'gui-run-model-fake-evid'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'resolved_model': 'qwen3-vl-235b',
            'finished_evidence': {'status': 'model_asserted', 'source': 'model'}
        })
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000060000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}}
            ],
            'diagnostics': {
                'token_usage': {'resolved_model': 'qwen3-vl-235b', 'prompt_tokens': 500, 'completion_tokens': 50}
            }
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['LONG_PRESS_OK'], 'counts': {'long_press': 1}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'finished_evidence_model_asserted')

    def test_model_short_long_press_duration_fails(self):
        """模型长按动作时长小于 800ms，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_SHORT_LP'
        run_id = 'gui-run-model-short-lp'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'resolved_model': 'qwen3-vl-235b',
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000060000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 500}}, 'result': {'success': True}}
            ],
            'diagnostics': {
                'token_usage': {'resolved_model': 'qwen3-vl-235b', 'prompt_tokens': 500, 'completion_tokens': 50}
            }
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['LONG_PRESS_OK'], 'counts': {'long_press': 1}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'long_press_duration_too_short')

    def test_model_fixture_long_press_count_mismatch_fails(self):
        """模型用例在 fixture 中长按计数不为 1，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_COUNT_FAIL'
        run_id = 'gui-run-model-count-fail'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'resolved_model': 'qwen3-vl-235b',
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000060000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}}
            ],
            'diagnostics': {
                'token_usage': {'resolved_model': 'qwen3-vl-235b', 'prompt_tokens': 500, 'completion_tokens': 50}
            }
        })
        # 计数为 0
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['LONG_PRESS_OK'], 'counts': {'long_press': 0}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'fixture_long_press_count_not_one')

    def test_model_mismatch_fails(self):
        """实际解析模型与预期模型不一致，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_MISMATCH'
        run_id = 'gui-run-model-mismatch'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'resolved_model': 'qwen3-vl-235b',
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000060000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}}
            ],
            'diagnostics': {
                'token_usage': {
                    'resolved_model': 'qwen3-vl-235b',
                    'prompt_tokens': 500,
                    'completion_tokens': 50
                }
            }
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['LONG_PRESS_OK'], 'counts': {'long_press': 1}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='expected-other-model',
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_mismatch')

    def test_model_evidence_insufficient_blocks(self):
        """缺少 resolved_model 证据，返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_MODEL_2'
        run_id = 'gui-run-model-2'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000060000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}}
            ],
            'diagnostics': {}
        })
        self.fixture_state.update({'marker': marker, 'display_id': 2, 'status_flags': ['LONG_PRESS_OK'], 'counts': {'long_press': 1}})

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'model_route_evidence_insufficient')
    # ==========================================
    # 6. 等待与前置阻塞 (pending / blocked) 测试用例
    # ==========================================

    def test_turn_loading_returns_pending(self):
        """Turn 仍处于 isLoading=True，返回 pending (Exit 2)。"""
        marker = 'OOB_LIVE_GUI_PENDING_1'
        self.add_user(marker)
        self.add_assistant(marker, is_loading=True)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 2)
        self.assertEqual(out['status'], 'pending')
        self.assertEqual(out['category'], 'turn_still_loading')

    def test_missing_user_message_returns_pending(self):
        """用户消息尚未写入 SQLite，返回 pending (Exit 2)。"""
        marker = 'OOB_LIVE_GUI_NOT_YET_SENT'
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 2)
        self.assertEqual(out['status'], 'pending')
        self.assertEqual(out['category'], 'waiting_for_user_message')

    def test_fixture_provider_unreachable_returns_blocked(self):
        """无法查询 ContentProvider 时返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_INTERACTION_BLOCKED'
        run_id = 'gui-run-blocked'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id, 'success': True, 'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}},
                {'action': {'tool': 'wait', 'args': {'condition': 'text_present'}}, 'result': {'success': True}, 'metadata': {'action_result': {'diagnostics': {'condition_met': 'true'}}}}
            ]
        })

        def broken_runner(args):
            if args[:3] == ['shell', 'content', 'query']:
                return ''  # 返回空代表无法访问
            return self.mock_adb_runner(args)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=broken_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'fixture_provider_unreachable')


    # ==========================================
    # 7. 模型执行配置与运行时来源确证反例与实质通过测试
    # ==========================================

    def _valid_model_config(self, route_source: str = 'conversation', model_id: str = 'qwen3-vl-235b') -> dict:
        return {
            'schema_version': 'omniflow.model-execution-config.v1',
            'provider_profile_sha256': '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
            'resolved_model': model_id,
            'route_source': route_source,
            'context_capacity': 128000,
            'output_budget': 4096,
            'max_completion_tokens': 2048,
            'reasoning_effort': 'none',
            'enable_thinking': False,
            'thinking_type': 'disabled',
            'reasoning_supported': True,
            'reasoning_can_disable': True,
            'supported_reasoning_levels': ['none', 'low', 'medium', 'high'],
            'default_reasoning_level': 'none',
            'omitted_fields': ['max_tokens'],
        }

    def _valid_runtime_provenance(self, source: str = 'packaged') -> dict:
        h = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
        return {
            'schema_version': 'omniflow.runtime-provenance.v1',
            'runtime_version': '2.1.9',
            'protocol_version': '1',
            'source': source,
            'manifest_source_sha256': h,
            'contract_sha256': h,
            'loaded_modules': {
                name: {'source_sha256': h, 'code_sha256': h}
                for name in pca._RUNTIME_MODULES
            },
        }

    def _valid_expected_runtime(self) -> dict:
        h = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
        return {
            'runtime_version': '2.1.9',
            'protocol_version': '1',
            'manifest_source_sha256': h,
            'contract_sha256': h,
            'loaded_modules': {
                name: {'source_sha256': h}
                for name in pca._RUNTIME_MODULES
            },
        }

    def _setup_model_run(
        self, marker: str, run_id: str, model_config: dict, runtime_prov: dict,
        model_id: str = 'qwen3-vl-235b', token_usage_by_call: list = None
    ):
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'resolved_model': model_id,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        calls = token_usage_by_call if token_usage_by_call is not None else [
            {'call_index': 0, 'success': True, 'execution_config': model_config}
        ]

        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000060000,
            'steps': [
                {
                    'action': {'tool': 'long_press', 'args': {'x': 500, 'y': 500, 'duration_ms': 800}},
                    'result': {'success': True},
                    'metadata': {'action_result': {'diagnostics': {'targetDisplayId': 2}}}
                }
            ],
            'diagnostics': {
                'token_usage': {
                    'resolved_model': model_id,
                    'prompt_tokens': 500,
                    'completion_tokens': 50,
                    'total_tokens': 550
                },
                'model_execution_config': model_config,
                'token_usage_by_call': calls,
                'runtime_provenance': runtime_prov,
            }
        })
        self.fixture_state.update({
            'marker': marker,
            'display_id': 2,
            'status_flags': ['LONG_PRESS_OK'],
            'counts': {'long_press': 1}
        })

    def test_model_substantive_success_with_runtime_provenance(self):
        """模型执行配置、7个加载模块以及 APK 归档预期一致，完整断言实质通过 (Exit 0 / passed)。"""
        marker = 'OOB_LIVE_GUI_MODEL_OK'
        run_id = 'gui-run-model-ok'
        m_cfg = self._valid_model_config()
        r_prov = self._valid_runtime_provenance()
        exp_rt = self._valid_expected_runtime()
        self._setup_model_run(marker, run_id, m_cfg, r_prov)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=exp_rt, db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertEqual(out['status'], 'passed')
        self.assertTrue(out['details']['configuration_verified'])
        self.assertTrue(out['details']['runtime_verified'])
        self.assertEqual(out['details']['route_source'], 'conversation')
        self.assertEqual(out['details']['output_budget'], 4096)
        self.assertEqual(out['details']['module_count'], 7)
        raw_str = json.dumps(out)
        self.assertNotIn('apiKey', raw_str)
        self.assertNotIn('password', raw_str)
        self.assertNotIn('prompt', out['details'])
        self.assertNotIn('http://', raw_str)
        self.assertNotIn('https://', raw_str)

    def test_model_substantive_success_with_omitted_thinking_fields(self):
        """模型调用省略思考参数，omitted_fields 完整声明，断言实质通过。"""
        marker = 'OOB_LIVE_GUI_MODEL_OMITTED_THINKING'
        run_id = 'gui-run-model-omitted'
        m_cfg = {
            'schema_version': 'omniflow.model-execution-config.v1',
            'provider_profile_sha256': '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
            'resolved_model': 'qwen3-vl-235b',
            'route_source': 'conversation',
            'context_capacity': 128000,
            'output_budget': 4096,
            'max_completion_tokens': 2048,
            'omitted_fields': [
                'max_tokens',
                'reasoning_effort',
                'enable_thinking',
                'thinking_type',
                'reasoning_supported',
                'reasoning_can_disable',
                'supported_reasoning_levels',
                'default_reasoning_level',
            ],
        }
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertEqual(out['status'], 'passed')
        self.assertTrue(out['details']['configuration_verified'])

    def test_model_route_precedence_and_binding_phase(self):
        """binding 阶段必须与 gui_binding 严格匹配，不符合则判定失败。"""
        marker = 'OOB_LIVE_GUI_MODEL_BINDING'
        run_id = 'gui-run-model-binding'
        m_cfg = self._valid_model_config(route_source='gui_binding')
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        # 阶段 binding 且路由 gui_binding -> 成功
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', phase='binding', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertEqual(out['status'], 'passed')

        # 阶段 default 却出现 gui_binding -> 失败
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', phase='default', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_route_precedence_mismatch')

        # 阶段 binding 却出现 conversation 路由 -> 失败
        marker2 = 'OOB_LIVE_GUI_MODEL_BINDING2'
        run_id2 = 'gui-run-model-binding2'
        m_cfg2 = self._valid_model_config(route_source='conversation')
        self._setup_model_run(marker2, run_id2, m_cfg2, self._valid_runtime_provenance())
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker2, 'model', phase='binding', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_route_precedence_mismatch')

    def test_model_wrong_provider_profile_hash_fails(self):
        """Provider SHA256 格式错误或非法哈希值，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_BAD_HASH'
        run_id = 'gui-run-model-bad-hash'
        m_cfg = self._valid_model_config()
        m_cfg['provider_profile_sha256'] = 'invalid_sha_string_not_64_hex'
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['status'], 'failed')
        self.assertEqual(out['category'], 'model_provider_hash_invalid')

    def test_model_wrong_budget_and_output_limits_fail(self):
        """输出预算大于容量或单步限制大于预算，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_BUDGET'
        run_id = 'gui-run-model-budget'
        m_cfg = self._valid_model_config()
        m_cfg['output_budget'] = 200000  # 超过 capacity 128000
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_output_budget_invalid')

        # max_completion_tokens 超过 output_budget
        marker2 = 'OOB_LIVE_GUI_MODEL_LIMIT'
        run_id2 = 'gui-run-model-limit'
        m_cfg2 = self._valid_model_config()
        m_cfg2['max_completion_tokens'] = 8192  # 超过 budget 4096
        self._setup_model_run(marker2, run_id2, m_cfg2, self._valid_runtime_provenance())
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker2, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_actual_output_limit_invalid')

        # limits 全为 None
        marker3 = 'OOB_LIVE_GUI_MODEL_NO_LIMIT'
        run_id3 = 'gui-run-model-no-limit'
        m_cfg3 = self._valid_model_config()
        m_cfg3['max_completion_tokens'] = None
        m_cfg3['max_tokens'] = None
        m_cfg3['omitted_fields'] = ['max_completion_tokens', 'max_tokens']
        self._setup_model_run(marker3, run_id3, m_cfg3, self._valid_runtime_provenance())
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker3, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_actual_output_limit_invalid')

    def test_model_nonreasoning_parameters_sent_fails(self):
        """不支持思考的模型违规下发思考参数，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_NONREASON'
        run_id = 'gui-run-model-nonreason'
        m_cfg = self._valid_model_config()
        m_cfg['reasoning_supported'] = False
        m_cfg['enable_thinking'] = True
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_nonreasoning_parameters_sent')

    def test_model_reasoning_disable_unsupported_fails(self):
        """不支持关闭思考的模型下发了关闭思考意图，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_NO_DISABLE'
        run_id = 'gui-run-model-no-dis'
        m_cfg = self._valid_model_config()
        m_cfg['reasoning_can_disable'] = False
        m_cfg['reasoning_effort'] = 'none'
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_reasoning_disable_unsupported')

    def test_model_reasoning_level_unsupported_fails(self):
        """指定的思考档位不在已知 supported_reasoning_levels 中，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_UNSUPP_LEVEL'
        run_id = 'gui-run-model-unsup-lvl'
        m_cfg = self._valid_model_config()
        m_cfg['supported_reasoning_levels'] = ['low', 'medium']
        m_cfg['reasoning_effort'] = 'high'
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_reasoning_level_unsupported')

    def test_model_call_configuration_not_frozen_fails(self):
        """多次成功调用中 execution_config 发生分歧未保持冻结，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_NOT_FROZEN'
        run_id = 'gui-run-model-not-frozen'
        m_cfg1 = self._valid_model_config()
        m_cfg2 = dict(m_cfg1)
        m_cfg2['output_budget'] = 2048  # 发生漂移
        calls = [
            {'call_index': 0, 'success': True, 'execution_config': m_cfg1},
            {'call_index': 1, 'success': True, 'execution_config': m_cfg2},
        ]
        self._setup_model_run(marker, run_id, m_cfg1, self._valid_runtime_provenance(), token_usage_by_call=calls)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_call_configuration_not_frozen')

    def test_model_reasoning_key_missing_without_omitted_declaration_blocks(self):
        """必要思考字段在 config 中缺失且未在 omitted_fields 中声明，返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_MODEL_KEY_MISSING'
        run_id = 'gui-run-model-key-missing'
        m_cfg = self._valid_model_config()
        del m_cfg['reasoning_effort']
        m_cfg['omitted_fields'] = ['max_tokens']  # 未声明 reasoning_effort
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'model_reasoning_configuration_missing')

    def test_model_omitted_field_conflict_fails(self):
        """字段显式存在非空值（如 none/false），但同时声明在 omitted_fields 中，判定冲突失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_CONFLICT'
        run_id = 'gui-run-model-conflict'
        m_cfg = self._valid_model_config()
        m_cfg['reasoning_effort'] = 'none'
        m_cfg['omitted_fields'] = ['max_tokens', 'reasoning_effort']  # 冲突
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_omitted_field_conflict')

    def test_model_invalid_value_cannot_escape_via_omitted_declaration_fails(self):
        """非法值绝不能借声明省略逃脱校验，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_ESCAPE'
        run_id = 'gui-run-model-escape'
        m_cfg = self._valid_model_config()
        m_cfg['reasoning_effort'] = 'illegal_effort_xyz'
        m_cfg['omitted_fields'] = ['max_tokens', 'reasoning_effort']
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertIn(out['category'], ('model_omitted_field_conflict', 'model_reasoning_configuration_invalid'))

    def test_model_unknown_omitted_field_fails(self):
        """omitted_fields 中包含未知字段名，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_UNKNOWN_OMITTED'
        run_id = 'gui-run-model-unk-omitted'
        m_cfg = self._valid_model_config()
        m_cfg['omitted_fields'] = ['max_tokens', 'unknown_field_123']
        self._setup_model_run(marker, run_id, m_cfg, self._valid_runtime_provenance())

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'model_omitted_field_unknown')

    def test_model_missing_expected_runtime_blocks(self):
        """模型用例必须传递 expected_runtime，否则严格返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_MODEL_NO_EXPECTED_RT'
        run_id = 'gui-run-model-no-exp-rt'
        m_cfg = self._valid_model_config()
        r_prov = self._valid_runtime_provenance()
        self._setup_model_run(marker, run_id, m_cfg, r_prov)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=None, db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['status'], 'blocked')
        self.assertEqual(out['category'], 'expected_runtime_identity_missing')

    def test_runtime_provenance_missing_loaded_module_blocks(self):
        """7个核心模块若任一在 loaded_modules 中缺失或为 missing，返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_MODEL_MISSING_MOD'
        run_id = 'gui-run-model-missing-mod'
        m_cfg = self._valid_model_config()
        r_prov = self._valid_runtime_provenance()
        r_prov['loaded_modules']['runtime.engine'] = {'status': 'missing'}
        self._setup_model_run(marker, run_id, m_cfg, r_prov)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'runtime_loaded_module_evidence_missing')

    def test_runtime_provenance_mismatched_archive_module_fails(self):
        """实际已载入模块 source_sha256 与 APK 预期归档不一致，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_MOD_MISMATCH'
        run_id = 'gui-run-model-mod-mismatch'
        m_cfg = self._valid_model_config()
        r_prov = self._valid_runtime_provenance()
        exp_rt = self._valid_expected_runtime()
        exp_rt['loaded_modules']['runtime.engine']['source_sha256'] = 'f' * 64
        self._setup_model_run(marker, run_id, m_cfg, r_prov)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=exp_rt, db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'runtime_loaded_module_mismatch')

    def test_runtime_provenance_developer_override_source_blocks(self):
        """运行时来源为 developer_override 而非 packaged，返回 blocked (Exit 3)。"""
        marker = 'OOB_LIVE_GUI_MODEL_OVERRIDE_SRC'
        run_id = 'gui-run-model-override-src'
        m_cfg = self._valid_model_config()
        r_prov = self._valid_runtime_provenance(source='developer_override')
        self._setup_model_run(marker, run_id, m_cfg, r_prov)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=self._valid_expected_runtime(), db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 3)
        self.assertEqual(out['category'], 'runtime_not_loaded_from_packaged_source')

    def test_runtime_provenance_mismatched_expected_artifact_identity_fails(self):
        """运行时版本、协议或 Manifest 摘要与本次 APK 归档预期不一致，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_ART_MISMATCH'
        run_id = 'gui-run-model-art-mismatch'
        m_cfg = self._valid_model_config()
        r_prov = self._valid_runtime_provenance()
        exp_rt = self._valid_expected_runtime()
        exp_rt['manifest_source_sha256'] = 'e' * 64  # 不匹配
        self._setup_model_run(marker, run_id, m_cfg, r_prov)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=exp_rt, db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'runtime_identity_mismatch')

    def test_run_assert_interaction_verifies_runtime_provenance_when_expected_runtime_provided(self):
        """在 interaction 用例中，若提供了 expected_runtime，自动提取封存 RunLog 校验 runtime_provenance。"""
        marker = 'OOB_LIVE_GUI_INT_RT'
        run_id = 'gui-run-int-rt'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        r_prov = self._valid_runtime_provenance()
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'finished_at_ms': 1773000005000,
            'steps': [
                {'action': {'tool': 'long_press', 'args': {'duration_ms': 800}}, 'result': {'success': True}, 'metadata': {'action_result': {'diagnostics': {'targetDisplayId': 2}}}},
                {'action': {'tool': 'wait', 'args': {'condition': 'text_present'}}, 'result': {'success': True}, 'metadata': {'action_result': {'diagnostics': {'condition_met': 'true', 'targetDisplayId': 2}}}},
                {'action': {'tool': 'click', 'args': {'x': 100, 'y': 200}}, 'result': {'success': True}, 'metadata': {'action_result': {'diagnostics': {'targetDisplayId': 2}}}}
            ],
            'diagnostics': {
                'runtime_provenance': r_prov,
            }
        })
        self.fixture_state.update({
            'marker': marker, 'display_id': 2,
            'status_flags': ['LONG_PRESS_OK', 'LOAD_OK', 'CHECKOUT_OK'],
            'counts': {'long_press': 1, 'load': 1, 'confirm': 1, 'checkout': 1}
        })

        # 传递 matching expected_runtime -> 成功且包含 runtime_verified
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', expected_runtime=self._valid_expected_runtime(),
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertEqual(out['status'], 'passed')
        self.assertTrue(out['details']['runtime_verified'])

        # 传递 mismatched expected_runtime -> 失败
        bad_exp = self._valid_expected_runtime()
        bad_exp['loaded_modules']['bridge']['source_sha256'] = '0' * 64
        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', expected_runtime=bad_exp,
            db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['category'], 'runtime_loaded_module_mismatch')

    def test_expected_runtime_file_cli_and_invalid_file(self):
        """通过 JSON 文件路径传递 expected_runtime，支持文件读取；若文件不存在或非法返回 blocked。"""
        marker = 'OOB_LIVE_GUI_MODEL_FILE_RT'
        run_id = 'gui-run-model-file-rt'
        m_cfg = self._valid_model_config()
        r_prov = self._valid_runtime_provenance()
        exp_rt = self._valid_expected_runtime()
        self._setup_model_run(marker, run_id, m_cfg, r_prov)

        with tempfile.TemporaryDirectory() as td:
            valid_file = Path(td) / 'expected_runtime.json'
            valid_file.write_text(json.dumps(exp_rt), encoding='utf-8')

            # 正确文件路径
            out, code = pca.run_assert_phone_control(
                'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
                expected_runtime=str(valid_file), db=self.db, adb_runner=self.mock_adb_runner
            )
            self.assertEqual(code, 0)
            self.assertEqual(out['status'], 'passed')
            self.assertTrue(out['details']['runtime_verified'])

            # 非法文件路径
            bad_file = Path(td) / 'nonexistent_file.json'
            out, code = pca.run_assert_phone_control(
                'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
                expected_runtime=str(bad_file), db=self.db, adb_runner=self.mock_adb_runner
            )
            self.assertEqual(code, 3)
            self.assertEqual(out['status'], 'blocked')
            self.assertEqual(out['category'], 'expected_runtime_identity_invalid')

    def test_sealed_log_missing_when_expected_runtime_provided_blocks(self):
        """提供了 expected_runtime 但由于封存日志尚未完成读取失败，返回 pending，绝不假成功。"""
        marker = 'OOB_LIVE_GUI_INT_SEALED_MISS'
        run_id = 'gui-run-int-sealed-miss'
        self.add_user(marker)
        self.add_vlm_task({
            'run_id': run_id,
            'success': True,
            'finished_evidence': {'status': 'matched', 'source': 'xml'}
        })
        self.add_assistant(marker)

        # 文件内容没有 finished_at_ms (未封存完成)
        self.files[pca.get_run_log_rel_path(run_id)] = json.dumps({
            'run_id': run_id,
            'success': True,
            'steps': []
        })
        self.fixture_state.update({
            'marker': marker, 'display_id': 2,
            'status_flags': ['LONG_PRESS_OK'],
            'counts': {'long_press': 1}
        })

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', expected_runtime=self._valid_expected_runtime(),
            db=self.db, adb_runner=self.mock_adb_runner
        )
        # interaction 基础断言首先在读取封存日志时会检查 finished_at_ms
        self.assertEqual(code, 2)
        self.assertEqual(out['status'], 'pending')

    def test_runtime_provenance_installed_source_with_matching_hashes_passes(self):
        """正式 APK SkillIndexService 记录 source=installed 时，只要源码与 Manifest 摘要完全一致，实质通过 (Exit 0 / passed)。"""
        marker = 'OOB_LIVE_GUI_MODEL_INSTALLED_OK'
        run_id = 'gui-run-model-inst-ok'
        m_cfg = self._valid_model_config()
        r_prov = self._valid_runtime_provenance(source='installed')
        exp_rt = self._valid_expected_runtime()
        self._setup_model_run(marker, run_id, m_cfg, r_prov)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=exp_rt, db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 0)
        self.assertEqual(out['status'], 'passed')
        self.assertTrue(out['details']['runtime_verified'])

    def test_runtime_provenance_installed_source_with_wrong_hash_fails(self):
        """source=installed 不能凭名字当证据，若模块哈希与本次 APK 归档不一致，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_MODEL_INSTALLED_BAD_HASH'
        run_id = 'gui-run-model-inst-bad'
        m_cfg = self._valid_model_config()
        r_prov = self._valid_runtime_provenance(source='installed')
        exp_rt = self._valid_expected_runtime()
        exp_rt['loaded_modules']['runtime.engine']['source_sha256'] = 'f' * 64
        self._setup_model_run(marker, run_id, m_cfg, r_prov)

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'model', expected_model='qwen3-vl-235b',
            expected_runtime=exp_rt, db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['status'], 'failed')
        self.assertEqual(out['category'], 'runtime_loaded_module_mismatch')

    def test_turn_pending_approval_ui_card_returns_pending(self):
        """无官方终态且存在待审批 ui_card (status=pending) 时，必须返回 pending (Exit 2 / waiting_for_approval)。"""
        marker = 'OOB_LIVE_GUI_PENDING_APPROVAL_CARD'
        self.add_user(marker)
        card_payload = {
            'content': {
                'cardData': {
                    'type': 'agent_request',
                    'requestKind': 'approval',
                    'status': 'pending',
                    'requestId': 'req-approval-01'
                }
            },
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'ui_card', json.dumps(card_payload))
        )

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 2)
        self.assertEqual(out['status'], 'pending')
        self.assertEqual(out['category'], 'waiting_for_approval')

    def test_turn_pending_approval_terminal_tool_returns_pending(self):
        """无官方终态且 agent.terminal 待用户审批时，必须返回 pending (Exit 2 / waiting_for_approval)。"""
        marker = 'OOB_LIVE_GUI_PENDING_TERMINAL_TOOL'
        self.add_user(marker)
        tool_payload = {
            'toolName': 'agent.terminal',
            'status': 'pending',
            'approvalStatus': 'pending',
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'tool_event', json.dumps(tool_payload))
        )

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 2)
        self.assertEqual(out['status'], 'pending')
        self.assertEqual(out['category'], 'waiting_for_approval')

    def test_turn_running_without_terminal_stop_reason_returns_pending(self):
        """助手已生成部分消息但 stopReason 未结束且 isLoading=False 时，不能当完成抽取，返回 pending (Exit 2 / turn_still_running)。"""
        marker = 'OOB_LIVE_GUI_RUNNING_NO_STOP_REASON'
        self.add_user(marker)
        assistant_payload = {
            'content': {'text': 'I am working on the task...'},
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1', 'stopReason': None},
            'isLoading': False
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'assistant_message', json.dumps(assistant_payload))
        )

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 2)
        self.assertEqual(out['status'], 'pending')
        self.assertEqual(out['category'], 'turn_still_running')

    def test_turn_ended_with_retained_pending_card_fails_not_pending(self):
        """Turn 已有官方收尾终态 (stopReason=end_turn) 时，残留待审批卡片绝不能借 pending 掩盖错误，断言明确失败 (Exit 1)。"""
        marker = 'OOB_LIVE_GUI_ENDED_WITH_PENDING_CARD'
        self.add_user(marker)
        card_payload = {
            'content': {
                'cardData': {
                    'type': 'agent_request',
                    'requestKind': 'approval',
                    'status': 'pending',
                    'requestId': 'req-dangling-01'
                }
            },
            'streamMeta': {'sessionId': 'sess_1', 'turnId': 'turn_1'}
        }
        self.db.execute(
            'INSERT INTO agent_conversation_entries(conversationId, entryType, payloadJson) VALUES(?, ?, ?)',
            (1, 'ui_card', json.dumps(card_payload))
        )
        self.add_assistant(marker, stop_reason='end_turn')

        out, code = pca.run_assert_phone_control(
            'DEVICE_TEST', marker, 'interaction', db=self.db, adb_runner=self.mock_adb_runner
        )
        self.assertEqual(code, 1)
        self.assertEqual(out['status'], 'failed')
        self.assertEqual(out['category'], 'turn_verification_failed')

if __name__ == '__main__':
    unittest.main()
