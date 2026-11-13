#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只读断言脚本：小万手机控制（OmniFlow / VLM Task）端到端自动化验收。

退出码定义：
  0: passed (所有验收断言真实通过)
  1: failed (断言明确失败、状态不匹配或假成功)
  2: pending (执行中未封存、正在加载中，可只读重试轮询)
  3: blocked (前置条件不满足、环境不可见、证据缺失)
"""

import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import time
from pathlib import Path
import math
import xml.etree.ElementTree as ET

# 保证当前脚本所在目录位于 sys.path
_scripts_dir = str(Path(__file__).resolve().parent)
if _scripts_dir not in sys.path:
    sys.path.insert(0, _scripts_dir)

from agent_test_database import agent_database_snapshot

# 动态加载官方 assert-agent-turn-outcome.py 中的 verify 方法
_outcome_spec = importlib.util.spec_from_file_location(
    'outcome', Path(__file__).resolve().parent / 'assert-agent-turn-outcome.py'
)
_outcome_mod = importlib.util.module_from_spec(_outcome_spec)
_outcome_spec.loader.exec_module(_outcome_mod)
_official_verify = _outcome_mod.verify


class AssertOutcome(Exception):
    """断言结果基类。"""
    def __init__(self, category: str, details: dict = None):
        super().__init__(category)
        self.category = category
        self.details = details or {}


class PendingOutcome(AssertOutcome):
    """运行中或尚未完成封存，可安全进行只读重试。"""
    pass


class BlockedOutcome(AssertOutcome):
    """前置环境条件不满足或缺少必要可读证据。"""
    pass


class FailedOutcome(AssertOutcome):
    """断言明确失败。"""
    pass


def safe_hash(val: str, length: int = 16) -> str:
    """计算受限哈希值，防止日志泄露长标识符。"""
    if not val:
        return ""
    return hashlib.sha256(val.encode('utf-8')).hexdigest()[:length]


def safe_file_part(val: str) -> str:
    """生成合规的文件路径后缀。"""
    return re.sub(r'[^A-Za-z0-9._-]', '_', val)[:80] or "run"


def get_run_log_rel_path(run_id: str) -> str:
    """获取 RunLog 相对应用沙盒 filesDir 的确切路径。"""
    h16 = safe_hash(run_id, 16)
    part = safe_file_part(run_id)
    return f"files/run_logs/{h16}_{part}.json"


def adb_exec(serial: str, cmd_args: list, adb_runner=None, timeout: int = 15) -> str:
    """执行 ADB 命令；在单元测试中可通过 adb_runner 注入。"""
    if adb_runner:
        return adb_runner(cmd_args)
    full_cmd = ['adb', '-s', serial] + cmd_args
    try:
        res = subprocess.run(full_cmd, capture_output=True, text=True, timeout=timeout)
        if res.returncode != 0:
            return ""
        return res.stdout
    except Exception:
        return ""


def adb_cat_file(serial: str, rel_path: str, adb_runner=None) -> str:
    """以 run-as 方式只读提取沙盒内文件。"""
    cmd = ['exec-out', 'run-as', 'cn.com.omnimind.bot', 'cat', rel_path]
    return adb_exec(serial, cmd, adb_runner=adb_runner)


def query_fixture_provider(serial: str, adb_runner=None) -> dict:
    """查询独立测试 APK 的合成状态只读 ContentProvider。"""
    cmd = ['shell', 'content', 'query', '--uri', 'content://cn.com.omnimind.androidgui.test.evidence/state']
    output = adb_exec(serial, cmd, adb_runner=adb_runner)
    if not output or not output.strip():
        raise BlockedOutcome('fixture_provider_unreachable')
    match = re.search(r'(\{[\s\S]*\})', output)
    if not match:
        raise BlockedOutcome('fixture_provider_invalid_format')
    try:
        data = json.loads(match.group(1))
    except Exception:
        raise BlockedOutcome('fixture_provider_json_error')
    if data.get('schema_version') != 'omnibot.gui-fixture.v1':
        raise BlockedOutcome('fixture_provider_unsupported_schema')
    return data


def verify_fixture_base(serial: str, marker: str, adb_runner=None) -> dict:
    """校验 Fixture 基础证据：标记精确吻合且位于后台虚拟屏 (display_id > 0)。"""
    fixture = query_fixture_provider(serial, adb_runner=adb_runner)
    if fixture.get('marker') != marker:
        raise FailedOutcome('fixture_marker_mismatch')
    if fixture.get('display_id', 0) <= 0:
        raise FailedOutcome('fixture_not_on_virtual_display')
    return fixture


def read_sealed_run_log(serial: str, run_id: str, adb_runner=None) -> dict:
    """读取并校验已封存的 RunLog，未封存或写入中返回 pending。"""
    if not run_id:
        raise FailedOutcome('missing_run_id_in_vlm_task')
    run_rel = get_run_log_rel_path(run_id)
    run_raw = adb_cat_file(serial, run_rel, adb_runner=adb_runner)
    if not run_raw or not run_raw.strip():
        raise PendingOutcome('waiting_for_sealed_run_log')
    try:
        run_log = json.loads(run_raw)
    except Exception:
        raise FailedOutcome('run_log_invalid_json')

    finished_at = run_log.get('finished_at_ms')
    if not finished_at or finished_at <= 0:
        raise PendingOutcome('waiting_for_sealed_run_log')
    if run_log.get('run_id') != run_id:
        raise FailedOutcome('run_id_mismatch')
    return run_log


def extract_vlm_task_run_id(vlm_task_result: dict, vlm_task_event: dict) -> str:
    """从结构化 context 或 tool event payload 中解析提取真实的 run_id。"""
    res = vlm_task_result if isinstance(vlm_task_result, dict) else {}
    evt = vlm_task_event if isinstance(vlm_task_event, dict) else {}
    run_id = (
        res.get('run_id')
        or res.get('run_log_id')
        or res.get('requested_run_id')
        or evt.get('run_id')
        or evt.get('run_log_id')
        or evt.get('requested_run_id')
    )
    if not run_id and isinstance(res, dict):
        for k in ('result', 'payload', 'data'):
            inner = res.get(k)
            if isinstance(inner, dict):
                run_id = inner.get('run_id') or inner.get('run_log_id') or inner.get('requested_run_id')
                if run_id:
                    break
    if not run_id and 'argsJson' in evt:
        try:
            raw_args = evt['argsJson']
            args = json.loads(raw_args) if isinstance(raw_args, str) else raw_args
            if isinstance(args, dict):
                run_id = args.get('run_id') or args.get('requested_run_id')
        except Exception:
            pass
    if not run_id and isinstance(evt, dict):
        for preview_key in ('resultPreviewJson', 'rawResultJson'):
            raw_preview = evt.get(preview_key)
            if raw_preview:
                try:
                    pdata = json.loads(raw_preview) if isinstance(raw_preview, str) else raw_preview
                    if isinstance(pdata, dict):
                        run_id = pdata.get('run_id') or pdata.get('run_log_id') or pdata.get('requested_run_id')
                        if not run_id:
                            for k in ('result', 'payload', 'data'):
                                inner = pdata.get(k)
                                if isinstance(inner, dict):
                                    run_id = inner.get('run_id') or inner.get('run_log_id') or inner.get('requested_run_id')
                                    if run_id:
                                        break
                    if run_id:
                        break
                except Exception:
                    pass
    return str(run_id).strip() if run_id else ""


def check_display0_foreground(serial: str, adb_runner=None) -> None:
    """验证主屏幕（Display 0）最上层前台仍然是小万主程序，未被测试界面抢占。"""
    cmd = ['shell', 'dumpsys', 'activity', 'activities']
    output = adb_exec(serial, cmd, adb_runner=adb_runner)
    if not output or not output.strip():
        raise BlockedOutcome('display_dumpsys_unreadable')

    # 提取 Display 0 专属区域
    parts = re.split(r'Display\s*#?0\b', output)
    if len(parts) < 2:
        raise BlockedOutcome('display0_not_found')
    disp0_section = parts[1]
    next_disp = re.split(r'Display\s*#?[1-9]\d*\b', disp0_section)
    disp0_text = next_disp[0]

    # 严格匹配 Display 0 实际顶层 resumed / focused Activity 组件
    resumed_match = re.search(
        r'(?:mResumedActivity|topResumedActivity|resumedActivity|mFocusedActivity|mFocusedApp)[:=]\s*'
        r'ActivityRecord\{[^\}]*\s+([A-Za-z0-9._]+)/',
        disp0_text
    )
    if not resumed_match:
        resumed_match = re.search(r'mResumedActivity[:=].*?\b([A-Za-z0-9._]+)/', disp0_text)

    if not resumed_match:
        raise BlockedOutcome('display0_top_activity_unobservable')

    top_pkg = resumed_match.group(1)
    if top_pkg == 'cn.com.omnimind.androidgui.test':
        raise FailedOutcome('fixture_activity_in_foreground_on_display_0')
    if top_pkg != 'cn.com.omnimind.bot':
        raise FailedOutcome('display0_top_activity_not_bot')

def extract_turn_data(db, marker: str, *, allow_running: bool = False) -> dict:
    """按标记提取严格隔离的单 Turn 上下文。"""
    candidates = db.execute(
        "SELECT id, conversationId, payloadJson FROM agent_conversation_entries "
        "WHERE entryType='user_message' AND instr(payloadJson, ?) > 0",
        (marker,)
    ).fetchall()

    live_scenario = marker.startswith('OOB_LIVE_')
    def matches_user(payload):
        text = payload.get('content', {}).get('text', '')
        return text == 'Reply ' + marker or (
            live_scenario and text.endswith(f'End your final reply with {marker}_DONE.')
        )

    selected = [r for r in candidates if matches_user(json.loads(r[2]))]
    if not selected:
        raise PendingOutcome('waiting_for_user_message')
    if len(selected) > 1:
        raise FailedOutcome('duplicate_user_message')

    start_id, conv_id, _ = selected[0]
    next_user_row = db.execute(
        "SELECT min(id) FROM agent_conversation_entries "
        "WHERE conversationId=? AND id>? AND entryType='user_message'",
        (conv_id, start_id)
    ).fetchone()
    end_id = next_user_row[0] if next_user_row and next_user_row[0] is not None else 2**63 - 1

    turn_entries = db.execute(
        "SELECT entryType, payloadJson FROM agent_conversation_entries "
        "WHERE conversationId=? AND id>? AND id<? ORDER BY id ASC",
        (conv_id, start_id, end_id)
    ).fetchall()

    parsed_entries = []
    for entry_type, raw_json in turn_entries:
        try:
            payload = json.loads(raw_json)
        except Exception:
            payload = {}
        parsed_entries.append((entry_type, payload))

    # 检查当前 Turn 是否仍在加载
    if not allow_running and any(p.get('isLoading', False) for _, p in parsed_entries):
        raise PendingOutcome('turn_still_loading')

    vlm_task_result = None
    vlm_task_event = None
    for entry_type, p in parsed_entries:
        if entry_type == 'tool_event' and p.get('toolName') == 'vlm_task':
            vlm_task_event = p
            if 'resultPreviewJson' in p and p['resultPreviewJson']:
                raw = p['resultPreviewJson']
                vlm_task_result = json.loads(raw) if isinstance(raw, str) else raw
            elif 'rawResultJson' in p and p['rawResultJson']:
                raw = p['rawResultJson']
                raw_dict = json.loads(raw) if isinstance(raw, str) else raw
                if isinstance(raw_dict, dict) and 'result' in raw_dict:
                    vlm_task_result = raw_dict['result']
                else:
                    vlm_task_result = raw_dict
            break

    assistant_replies = [
        p.get('content', {}).get('text', '')
        for entry_type, p in parsed_entries
        if entry_type == 'assistant_message'
    ]
    assistant_reply = assistant_replies[-1] if assistant_replies else ''

    return {
        'start_id': start_id,
        'conv_id': conv_id,
        'entries': parsed_entries,
        'vlm_task': vlm_task_result,
        'vlm_task_event': vlm_task_event,
        'assistant_reply': assistant_reply
    }


def _resolve_canonical_turn_identities(entries):
    """提取并校验当前 Turn 的唯一规范 ACP Session ID 与 Turn ID。"""
    session_ids = set()
    turn_ids = set()
    for entry_type, payload in entries:
        if not isinstance(payload, dict):
            continue
        sm = payload.get('streamMeta') if isinstance(payload.get('streamMeta'), dict) else {}
        for sid in (sm.get('sessionId'), payload.get('sessionId')):
            if isinstance(sid, str) and sid.strip():
                session_ids.add(sid.strip())
        for tid in (sm.get('turnId'), payload.get('turnId')):
            if isinstance(tid, str) and tid.strip():
                turn_ids.add(tid.strip())
        if entry_type == 'tool_event' and payload.get('toolName') == 'vlm_task':
            raw = payload.get('rawResultJson')
            try:
                item = json.loads(raw) if isinstance(raw, str) else (raw if isinstance(raw, dict) else None)
            except (TypeError, ValueError):
                item = None
            if isinstance(item, dict):
                inner_sid = item.get('sessionId')
                if isinstance(inner_sid, str) and inner_sid.strip():
                    session_ids.add(inner_sid.strip())
                inner_tid = item.get('turnId')
                if isinstance(inner_tid, str) and inner_tid.strip():
                    turn_ids.add(inner_tid.strip())
    if len(session_ids) != 1 or len(turn_ids) != 1:
        return None, None
    return next(iter(session_ids)), next(iter(turn_ids))


def wait_action_admitted(db, marker: str) -> bool:
    """按 ACP reducer 真实持久化层次解析并确证当前 Turn 正在执行规范等待动作。"""
    try:
        turn = extract_turn_data(db, marker, allow_running=True)
    except AssertOutcome:
        return False
    entries = turn.get('entries', [])
    if not entries:
        return False

    # 若当前会话已有后继 user_message，说明当前 Turn 已结束并被后续 Turn 取代，绝非活跃等待
    has_next_user = db.execute(
        "SELECT 1 FROM agent_conversation_entries "
        "WHERE conversationId=? AND id>? AND entryType='user_message' LIMIT 1",
        (turn['conv_id'], turn['start_id'])
    ).fetchone()
    if has_next_user:
        return False

    # 1. 终态排除：若当前 Turn 出现任何官方终态标记或错误，拒绝准入
    for entry_type, payload in entries:
        if not isinstance(payload, dict):
            continue
        sm = payload.get('streamMeta') if isinstance(payload.get('streamMeta'), dict) else {}
        stop_reason = sm.get('stopReason') or payload.get('stopReason')
        if stop_reason in ('end_turn', 'cancelled', 'error'):
            return False
        if payload.get('interruptedBy') or payload.get('interruptionReason'):
            return False
        if entry_type == 'tool_event' and payload.get('toolName') == 'agent.status' and payload.get('status') == 'error':
            return False
        if entry_type == 'tool_event' and payload.get('toolName') == 'vlm_task' and payload.get('status') in ('completed', 'error', 'cancelled'):
            return False

    # 2. ACP Session 与 Turn 身份逐层解析与一致性校验（缺失、冲突或错轮均拒绝）
    canonical_session, canonical_turn = _resolve_canonical_turn_identities(entries)
    if not canonical_session or not canonical_turn:
        return False

    # 3. 遍历确证规范 active wait 动作：外层 running、内部 in_progress/rawOutput.action
    admitted_calls = set()
    for entry_type, payload in entries:
        if entry_type != 'tool_event' or not isinstance(payload, dict):
            continue
        if payload.get('toolName') != 'vlm_task':
            continue
        # 外层卡片状态必须为 running（真实 ACP reducer 规范化结果），不兼容假 fake 状态
        if payload.get('status') != 'running':
            continue

        tool_call_id = payload.get('toolCallId')
        if not isinstance(tool_call_id, str) or not tool_call_id.strip():
            continue
        tool_call_id = tool_call_id.strip()

        # 外层 session 与 turn 身份必须与 canonical 一致
        p_sid = payload.get('sessionId')
        if p_sid and (not isinstance(p_sid, str) or p_sid.strip() != canonical_session):
            continue
        sm = payload.get('streamMeta') if isinstance(payload.get('streamMeta'), dict) else {}
        sm_sid = sm.get('sessionId')
        if sm_sid and (not isinstance(sm_sid, str) or sm_sid.strip() != canonical_session):
            continue

        p_tid = payload.get('turnId')
        if p_tid and (not isinstance(p_tid, str) or p_tid.strip() != canonical_turn):
            continue
        sm_tid = sm.get('turnId')
        if sm_tid and (not isinstance(sm_tid, str) or sm_tid.strip() != canonical_turn):
            continue

        # 内部 item 来自 rawResultJson 字符串序列化
        raw = payload.get('rawResultJson')
        if not isinstance(raw, str) or not raw.strip():
            continue
        try:
            item = json.loads(raw)
        except (TypeError, ValueError):
            continue
        if not isinstance(item, dict):
            continue

        # 内部 ACP 原始状态必须为 in_progress
        if item.get('status') != 'in_progress':
            continue

        # 内部 toolCallId 必须非空且与外层严格一致
        inner_call_id = item.get('toolCallId') or item.get('id')
        if not isinstance(inner_call_id, str) or inner_call_id.strip() != tool_call_id:
            continue

        # 内部 session / turn 身份（若存在）必须严格一致
        inner_sid = item.get('sessionId')
        if inner_sid and (not isinstance(inner_sid, str) or inner_sid.strip() != canonical_session):
            continue
        inner_tid = item.get('turnId')
        if inner_tid and (not isinstance(inner_tid, str) or inner_tid.strip() != canonical_turn):
            continue

        # 运行 run_id 校验：内部 item 与 rawOutput 均必须携带且一致
        item_run_id = item.get('run_id')
        if not isinstance(item_run_id, str) or not item_run_id.strip():
            continue
        item_run_id = item_run_id.strip()

        p_run_id = payload.get('run_id')
        if p_run_id and (not isinstance(p_run_id, str) or p_run_id.strip() != item_run_id):
            continue

        output = item.get('rawOutput')
        if not isinstance(output, dict):
            continue

        output_run_id = output.get('run_id')
        if not isinstance(output_run_id, str) or output_run_id.strip() != item_run_id:
            continue

        # 动作校验：必须在 rawOutput.action 内，不识别自由文字进展文案
        action = output.get('action')
        if not isinstance(action, dict) or action.get('tool') != 'wait':
            continue

        args = action.get('args')
        if not isinstance(args, dict):
            continue

        raw_duration = args.get('duration_ms')
        parsed_duration = None
        if isinstance(raw_duration, int) and not isinstance(raw_duration, bool):
            parsed_duration = raw_duration
        elif isinstance(raw_duration, str) and raw_duration.isascii() and raw_duration.isdigit():
            parsed_duration = int(raw_duration)

        # 严格规范整数（>=10000，合理上限按现canonical无条件60000，模型wait10000；不接受floatstring/布尔/NaN/空/自由word）
        if parsed_duration is None or not (10000 <= parsed_duration <= 60000):
            continue

        admitted_calls.add(tool_call_id)

    return len(admitted_calls) == 1


def safe_outcome_verify(db, marker: str, expected: str) -> dict:
    """包装官方验证，在当前 Turn 作用域内严格区分 pending 与 failed。"""
    turn_data = extract_turn_data(db, marker)
    entries = turn_data['entries']

    # 作用域内校验加载状态
    if any(p.get('isLoading', False) for _, p in entries):
        raise PendingOutcome('turn_still_loading')

    # 检查当前 Turn 是否已有官方终态（end_turn, cancelled, error）
    has_terminal = any(
        p.get('streamMeta', {}).get('stopReason') in ('end_turn', 'cancelled', 'error')
        or (entry_type == 'tool_event' and p.get('toolName') == 'agent.status' and p.get('status') == 'error')
        for entry_type, p in entries
    )

    if not has_terminal:
        # 1. 结构化待审批卡片检查 (ui_card: status == 'pending')
        for entry_type, p in entries:
            if entry_type == 'ui_card':
                card_data = p.get('content', {}).get('cardData') or p.get('cardData') or {}
                if isinstance(card_data, dict) and card_data.get('status') == 'pending':
                    raise PendingOutcome('waiting_for_approval')

        # 2. 工具待审批/待执行检查 (tool_event: agent.terminal, device_display_action 等)
        for entry_type, p in entries:
            if entry_type == 'tool_event':
                status = p.get('status')
                approval = p.get('approvalStatus')
                tool_name = p.get('toolName')
                if status == 'pending' or approval == 'pending':
                    raise PendingOutcome('waiting_for_approval')
                if tool_name in ('agent.terminal', 'device_display_action', 'vlm_task') and status in ('pending', 'running', None):
                    raise PendingOutcome('waiting_for_approval' if approval == 'pending' else 'waiting_for_tool_execution')

        # 3. 尚未有助手回复时，处于等待助手回复中
        assistants = [p for t, p in entries if t == 'assistant_message']
        if not assistants:
            raise PendingOutcome('waiting_for_assistant_message')

        # 4. 虽有助手消息但所有 stopReason 为空/非终态，模型仍在运行中
        raise PendingOutcome('turn_still_running')

    # 终态收尾防掩盖检查：已产生收尾终态却残留待审批卡片或未决工具调用，必须明确判定失败
    for entry_type, p in entries:
        if entry_type == 'ui_card':
            card_data = p.get('content', {}).get('cardData') or p.get('cardData') or {}
            if isinstance(card_data, dict) and card_data.get('status') == 'pending':
                raise FailedOutcome('turn_verification_failed')
        elif entry_type == 'tool_event':
            status = p.get('status')
            approval = p.get('approvalStatus')
            if status == 'pending' or approval == 'pending':
                raise FailedOutcome('turn_verification_failed')

    # 调用官方 verify
    try:
        return _official_verify(db, marker, expected)
    except AssertionError as e:
        msg = str(e)
        if 'Turn still loading' in msg:
            raise PendingOutcome('turn_still_loading')
        if 'Missing canonical completion' in msg:
            assistants = [p for t, p in entries if t == 'assistant_message']
            if not assistants:
                raise PendingOutcome('waiting_for_assistant_message')
            raise FailedOutcome('missing_canonical_completion')
        if 'Missing canonical cancellation' in msg:
            cancels = [p for t, p in entries if p.get('streamMeta', {}).get('stopReason') == 'cancelled']
            if not cancels:
                raise PendingOutcome('waiting_for_cancellation')
            raise FailedOutcome('missing_canonical_cancellation')
        raise FailedOutcome('turn_verification_failed')


def read_app_experiences_sidecar(serial: str, adb_runner=None) -> dict:
    """读取应用经验 Sidecar 存储文件。"""
    raw = adb_cat_file(serial, 'workspace/.omnibot/omniflow/app-experiences.json', adb_runner=adb_runner)
    if not raw or not raw.strip():
        raise BlockedOutcome('app_experience_sidecar_missing')
    try:
        data = json.loads(raw)
    except Exception:
        raise BlockedOutcome('app_experience_sidecar_corrupted')
    if data.get('schema_version') != 'omniflow.app-experiences.v1':
        raise BlockedOutcome('app_experience_invalid_schema')
    return data

def extract_registered_function_id(
    turn_data: dict,
    run_log: dict = None,
    serial: str = None,
    adb_runner=None
) -> str:
    """提取当前 Turn 或已封存日志中确证注册的 Function ID。

    核心契约与防歧义保障：
    1. 同一 Turn 内显式调用 save_function(enhance=true) 并通过 get_function 确认参数模式；
    2. 支持 Python bridge 真实返回的复数 function_ids、functions 列表及 payload/result 包装结构；
    3. Split 拆分可能产生多个 Function（如导航、表单等）：断言绝不能盲目提取 function_ids[0]，
       必须依据步骤控件双参数绑定（first/second）与提交按钮确证唯一的表单 Function；
    4. 不再从持久化 action 读取临时 selector（canonicalize_action persisted_only=true 会移除 node_resource_id），
       应使用 source_state_id 匹配已封存来源 run 中的 before_state_id 及对应真实 action，
       核对持久化 args 一致，再从该动作原 node_resource_id 或引用 State XML（按真实坐标与 package）确证目标；
    5. 只有同 Turn 成功执行且 run_id 严格匹配来源 GUI run_id 的 save_function 才能授权 ID 与 artifact，
       get_function 详情必须归属于该已授权的 saved IDs；
    6. 若候选存在歧义（多个或零个符合双参数表单）或来源 run_id 不匹配，严格抛出 BlockedOutcome；
    7. 绝不在 Function v2 artifact 中强加不存在的 source_run_id 字段，严禁将 vlm_task.function_id='run_gui' 短路为注册函数。
    """
    vlm_task = turn_data.get('vlm_task') or {}
    vlm_event = turn_data.get('vlm_task_event') or {}
    source_gui_run_id = str(extract_vlm_task_run_id(vlm_task, vlm_event) or (run_log.get('run_id') if run_log else '') or '').strip()

    # 扫描同 Turn 中所有成功 save_function 调用的 run_id 实参
    save_call_run_ids = set()
    for entry_type, p in turn_data.get('entries', []):
        if entry_type == 'tool_event' and p.get('toolName') == 'save_function' and p.get('success'):
            args_raw = p.get('argsJson')
            if args_raw:
                try:
                    args_data = json.loads(args_raw) if isinstance(args_raw, str) else args_raw
                    if isinstance(args_data, dict):
                        cid = str(args_data.get('run_id') or '').strip()
                        if cid:
                            save_call_run_ids.add(cid)
                except Exception:
                    pass

    # 严格来源确证契约：
    # 1. 若同 Turn 存在多个不同 run_id 的 save_function，严格判定来源歧义拒绝
    if len(save_call_run_ids) > 1:
        source_gui_run_id = ""
    # 2. 若存在已知 GUI run_id，所有 save_function.run_id 必须与之完全一致
    elif source_gui_run_id:
        if save_call_run_ids and save_call_run_ids != {source_gui_run_id}:
            source_gui_run_id = ""
    # 3. 独立注册专用 Turn 支持：仅当无 GUI run_id 且成功 save_function.run_id 唯一非空时，作为来源读取 exact sealed source
    elif len(save_call_run_ids) == 1:
        source_gui_run_id = next(iter(save_call_run_ids))

    # 注册调用只授权 ID；完整定义必须由同 Turn 的 get_function 确认。
    save_id_list = []      # list of function_id strings
    get_fn_artifacts = {}  # function_id -> function dict
    has_save_function_call = False

    for entry_type, p in turn_data.get('entries', []):
        if entry_type != 'tool_event':
            continue
        tool_name = p.get('toolName', '')

        if tool_name == 'save_function':
            has_save_function_call = True
            # 必须为成功调用的 tool_event
            if not p.get('success'):
                continue

            # 校验同 Turn save_function 的 run_id 必须与 source GUI run_id 严格一致
            args_raw = p.get('argsJson')
            if not args_raw:
                continue
            try:
                args_data = json.loads(args_raw) if isinstance(args_raw, str) else args_raw
                if not isinstance(args_data, dict):
                    continue
                call_run_id = str(args_data.get('run_id') or '').strip()
            except Exception:
                continue

            if not source_gui_run_id or call_run_id != source_gui_run_id:
                continue

            raw = p.get('resultPreviewJson') or p.get('rawResultJson')
            if raw:
                try:
                    data = json.loads(raw) if isinstance(raw, str) else raw
                    # 解包常见 wrapper (payload / result / data)
                    if isinstance(data, dict):
                        inner = data.get('payload') or data.get('result') or data.get('data')
                        if isinstance(inner, dict) and any(k in inner for k in ('function_ids', 'functions', 'function_id', 'registered_function_id')):
                            data = inner

                    # 提取 functions 列表
                    raw_functions = data.get('functions')
                    if isinstance(raw_functions, list):
                        for item in raw_functions:
                            if isinstance(item, dict) and item.get('function_id'):
                                fid = str(item['function_id']).strip()
                                if fid:
                                    save_id_list.append(fid)
                    # 提取 function_ids 列表
                    raw_ids = data.get('function_ids')
                    if isinstance(raw_ids, list):
                        for fid in raw_ids:
                            if fid and str(fid).strip():
                                save_id_list.append(str(fid).strip())

                    # 单数字段回退
                    single_id = data.get('function_id') or data.get('registered_function_id')
                    if single_id and str(single_id).strip():
                        save_id_list.append(str(single_id).strip())
                except Exception:
                    pass

    save_id_set = {str(fid).strip() for fid in save_id_list if str(fid).strip()}

    for entry_type, p in turn_data.get('entries', []):
        if entry_type != 'tool_event':
            continue
        tool_name = p.get('toolName', '')
        if tool_name == 'get_function':
            if not p.get('success'):
                continue
            args_raw = p.get('argsJson')
            call_fn_id = ""
            if args_raw:
                try:
                    args_data = json.loads(args_raw) if isinstance(args_raw, str) else args_raw
                    if isinstance(args_data, dict):
                        call_fn_id = str(args_data.get('function_id') or '').strip()
                except Exception:
                    pass

            raw = p.get('resultPreviewJson') or p.get('rawResultJson')
            if raw:
                try:
                    data = json.loads(raw) if isinstance(raw, str) else raw
                    if isinstance(data, dict):
                        inner = data.get('payload') or data.get('result') or data.get('data')
                        if isinstance(inner, dict) and ('function' in inner or 'function_id' in inner):
                            data = inner
                        fn_obj = data.get('function') if isinstance(data.get('function'), dict) else (data if data.get('function_id') else None)
                        if isinstance(fn_obj, dict) and fn_obj.get('function_id'):
                            fid = str(fn_obj['function_id']).strip()
                            # 契约：get_function 确认的 Function ID 必须归属于同 Turn 成功 save_function 产出的官方 saved IDs
                            if fid and fid in save_id_set and call_fn_id == fid:
                                get_fn_artifacts[fid] = fn_obj
                except Exception:
                    pass

    authorized_candidates = list(get_fn_artifacts.values())
    if not authorized_candidates:
        if has_save_function_call:
            raise BlockedOutcome('form_function_ambiguous')
        return ''
    if not run_log and serial and source_gui_run_id:
        run_log = read_sealed_run_log(serial, source_gui_run_id, adb_runner=adb_runner)
    if not run_log or run_log.get('run_id') != source_gui_run_id or not run_log.get('finished_at_ms'):
        raise BlockedOutcome('source_run_log_missing_for_function_verification')

    schema_path = Path(__file__).resolve().parent.parent / 'plugins/omni-vlm-lite/schemas/oob/oob_canonical_actions.v1.json'
    action_specs = {tool['name']: tool['args'] for tool in json.loads(schema_path.read_text(encoding='utf-8'))['tools']}

    COORD_FIELDS = {'x', 'y', 'x1', 'y1', 'x2', 'y2'}

    def _coord_equal(v1, v2) -> bool:
        try:
            return abs(float(v1) - float(v2)) <= 1e-9
        except (TypeError, ValueError):
            return False

    def _lookup_resource_from_state_xml(state_id: str, r_act: dict) -> str:
        """从已封存 State 文件中只读解析 XML，根据坐标与 package 确证目标 resource-id。"""
        if not serial and not adb_runner:
            raise BlockedOutcome('source_state_evidence_missing')
        stem = f"files/run_logs/states/{safe_hash(state_id)}_{safe_file_part(state_id)}"
        try:
            state_raw = adb_cat_file(serial, stem + '.json', adb_runner=adb_runner)
            if not state_raw or not state_raw.strip():
                raise BlockedOutcome('source_state_evidence_missing')
            state = json.loads(state_raw)
            xml = state.get('xml') or adb_cat_file(serial, stem + '.xml', adb_runner=adb_runner)
            if not xml or not xml.strip():
                raise BlockedOutcome('source_state_evidence_missing')
            root = ET.fromstring(xml)
            width = state.get('display', {}).get('width', 1000)
            height = state.get('display', {}).get('height', 1000)
        except BlockedOutcome:
            raise
        except Exception:
            raise BlockedOutcome('source_state_evidence_missing')

        if state.get('state_id') != state_id or state.get('package_name') != 'cn.com.omnimind.androidgui.test':
            raise BlockedOutcome('source_state_package_mismatch')

        r_args = r_act.get('args') or {}
        if 'x' not in r_args or 'y' not in r_args:
            raise BlockedOutcome('source_action_coordinates_missing')
        try:
            px = float(r_args['x']) / 1000.0 * width
            py = float(r_args['y']) / 1000.0 * height
        except (ValueError, TypeError):
            raise BlockedOutcome('source_action_coordinates_missing')

        matched_nodes = []
        for node in root.iter('node'):
            pkg = node.get('package', '')
            if pkg and pkg != 'cn.com.omnimind.androidgui.test':
                continue
            bounds_str = node.get('bounds', '')
            coords = [int(v) for v in re.findall(r'-?\d+', bounds_str)]
            if len(coords) == 4:
                x1, y1, x2, y2 = coords
                if x1 <= px <= x2 and y1 <= py <= y2:
                    rid = node.get('resource-id', '')
                    if rid:
                        matched_nodes.append(((x2 - x1) * (y2 - y1), rid))

        if not matched_nodes:
            raise BlockedOutcome('source_state_target_node_missing')
        matched_nodes.sort(key=lambda item: item[0])
        return matched_nodes[0][1]

    def _resolve_step_resource_id(s: dict, is_text_bound: bool, fn_steps: list = None) -> str | None:
        """结合已封存来源 run_log，通过 source_state_id 与规范持久化动作确证步骤的目标 resource_id。"""
        source_state_id = s.get('source_state_id')
        if not source_state_id or not isinstance(source_state_id, str):
            return None
        if not run_log or not isinstance(run_log.get('steps'), list):
            raise BlockedOutcome('source_run_log_missing_for_function_verification')

        steps_in_run = run_log.get('steps', [])

        fn_act = s.get('action') or {}
        fn_tool = str(fn_act.get('tool') or '').strip().lower()
        fn_args = fn_act.get('args') or {}
        specs = action_specs.get(fn_tool)
        if specs is None or not isinstance(fn_args, dict):
            return None
        if set(fn_args) - {spec['name'] for spec in specs}:
            return None

        # 检查是否为由 project_androidworld_step_actions 投影出的聚焦点击 (focus click)
        # 契约：必须在 Function 步骤中同 source_state 存在配对的 input_text；严禁将任意 click 嫁接为 input。
        is_focus_click = False
        if fn_tool == 'click' and fn_steps:
            is_focus_click = any(
                isinstance(other, dict)
                and other is not s
                and other.get('source_state_id') == source_state_id
                and (other.get('action') or {}).get('tool') == 'input_text'
                for other in fn_steps
            )

        matching_source_steps = []
        for run_step in steps_in_run:
            if not isinstance(run_step, dict):
                continue
            if run_step.get('before_state_id') != source_state_id:
                continue
            if run_step.get('result', {}).get('success') is not True:
                continue
            r_act = run_step.get('action') or {}
            r_tool = str(r_act.get('tool') or '').strip().lower()
            r_args = r_act.get('args') or {}

            if is_focus_click:
                # 聚焦点击来源于原 source input_text，坐标必须精确一致 (abs delta <= 1e-9)
                if r_tool != 'input_text':
                    continue
                if 'x' not in fn_args or 'y' not in fn_args or 'x' not in r_args or 'y' not in r_args:
                    continue
                if not _coord_equal(fn_args['x'], r_args['x']) or not _coord_equal(fn_args['y'], r_args['y']):
                    continue
                matching_source_steps.append(run_step)
            elif fn_tool == 'input_text':
                if r_tool != 'input_text':
                    continue
                # 5-step projection 中 input_text 为 text-only，不携带 x, y；3-step synthetic 中可能携带 x, y
                if 'x' in fn_args and 'x' in r_args and not _coord_equal(fn_args['x'], r_args['x']):
                    continue
                if 'y' in fn_args and 'y' in r_args and not _coord_equal(fn_args['y'], r_args['y']):
                    continue
                # 非参数绑定的 text 必须等值
                if not is_text_bound and fn_args.get('text') != r_args.get('text'):
                    continue
                matching_source_steps.append(run_step)
            elif fn_tool == r_tool:
                # 其他常规动作（如 click 提交、wait 等）
                stored = {spec['name'] for spec in specs if spec.get('persisted') is not False}
                args_match = True
                for name in stored:
                    if name in COORD_FIELDS:
                        if name in fn_args and name in r_args:
                            if not _coord_equal(fn_args[name], r_args[name]):
                                args_match = False
                                break
                        elif name in fn_args or name in r_args:
                            args_match = False
                            break
                    else:
                        if fn_args.get(name) != r_args.get(name):
                            args_match = False
                            break
                if args_match:
                    matching_source_steps.append(run_step)

        if not matching_source_steps:
            return None

        # 提取候选动作确证的 resource_id
        res_candidates = set()
        for ms in matching_source_steps:
            r_act = ms.get('action') or {}
            r_args = r_act.get('args') or {}
            # 1. 来源 action 具备 node_resource_id 时直接等值确证
            rid = str(r_args.get('node_resource_id') or '').strip()
            if not rid:
                # 2. 来源 action 缺 node_resource_id 时，只读引用 State XML 确证目标
                rid = _lookup_resource_from_state_xml(source_state_id, r_act)
            if rid:
                res_candidates.add(rid)

        # 同 source_state 多个同 canonical action 却对应不同资源时，严格判定冲突，禁止盲目首取
        if len(res_candidates) > 1:
            raise BlockedOutcome('source_action_resource_conflict')
        if not res_candidates:
            return None
        return next(iter(res_candidates))
    def _is_form_function(fn: dict) -> bool:
        """检验 Function 是否为排除导航/标记后、具备表单双参数绑定的目标 Function。"""
        if not isinstance(fn, dict):
            return False

        # 1. 检查 schema_version
        if fn.get('schema_version') != 'omniflow.function.v2':
            return False

        # 2. 检查 input_schema 必须包含 first 与 second 参数属性定义
        schema = fn.get('input_schema') or {}
        props = schema.get('properties') if isinstance(schema, dict) else {}
        if not isinstance(props, dict) or 'first' not in props or 'second' not in props:
            return False

        # 3. 检查 bindings 必须精确包含 2 个有效绑定，分别对应 first 与 second
        bindings = fn.get('bindings') or []
        if not isinstance(bindings, list) or len(bindings) != 2:
            return False

        first_target = None
        second_target = None
        for b in bindings:
            if not isinstance(b, dict):
                return False
            src = str(b.get('source') or '').strip()
            tgt = str(b.get('target') or '').strip()
            if src == '$.arguments.first':
                first_target = tgt
            elif src == '$.arguments.second':
                second_target = tgt
            else:
                return False

        if not first_target or not second_target:
            return False

        # 4. 解析 bindings 指向的 steps 索引并确证对应 input_text 目标
        m_first = re.match(r'^\$\.steps\[(\d+)\]\.action\.args\.text$', first_target)
        m_second = re.match(r'^\$\.steps\[(\d+)\]\.action\.args\.text$', second_target)
        if not m_first or not m_second:
            return False

        first_idx = int(m_first.group(1))
        second_idx = int(m_second.group(1))
        if first_idx == second_idx:
            return False

        steps = fn.get('steps') or []
        if not isinstance(steps, list) or not steps:
            return False

        def get_step(idx: int):
            for s in steps:
                if isinstance(s, dict) and s.get('step_index') == idx:
                    return s
            if 0 <= idx < len(steps) and isinstance(steps[idx], dict):
                return steps[idx]
            return None

        step_first = get_step(first_idx)
        step_second = get_step(second_idx)
        if not step_first or not step_second:
            return False

        act_first = step_first.get('action') or {}
        args_first = act_first.get('args') or {}
        if act_first.get('tool') != 'input_text':
            return False
        if 'target_resource_id' in args_first:
            return False
        res_first = _resolve_step_resource_id(step_first, is_text_bound=True, fn_steps=steps)
        if res_first != 'cn.com.omnimind.androidgui.test:id/input_first':
            return False

        act_second = step_second.get('action') or {}
        args_second = act_second.get('args') or {}
        if act_second.get('tool') != 'input_text':
            return False
        if 'target_resource_id' in args_second:
            return False
        res_second = _resolve_step_resource_id(step_second, is_text_bound=True, fn_steps=steps)
        if res_second != 'cn.com.omnimind.androidgui.test:id/input_second':
            return False

        # 5. 校验步骤必须包含唯一的 btn_form_submit click 动作，所有步骤资源归属表单且无非规范/导航控件
        submit_clicks = []
        forbidden_ids = {
            'cn.com.omnimind.androidgui.test:id/btn_menu_form',
            'cn.com.omnimind.androidgui.test:id/btn_back',
            'cn.com.omnimind.androidgui.test:id/input_marker',
            'cn.com.omnimind.androidgui.test:id/btn_set_marker',
        }
        allowed_form_ids = {
            'cn.com.omnimind.androidgui.test:id/input_first',
            'cn.com.omnimind.androidgui.test:id/input_second',
            'cn.com.omnimind.androidgui.test:id/btn_form_submit',
        }
        for s in steps:
            if not isinstance(s, dict):
                return False
            act = s.get('action') or {}
            args = act.get('args') or {}
            if 'target_resource_id' in args:
                return False
            is_bound = (s.get('step_index') in (first_idx, second_idx) or s is step_first or s is step_second)
            res_id = _resolve_step_resource_id(s, is_text_bound=is_bound, fn_steps=steps)
            if not res_id:
                return False
            if res_id in forbidden_ids or res_id not in allowed_form_ids:
                return False
            if act.get('tool') == 'click' and res_id == 'cn.com.omnimind.androidgui.test:id/btn_form_submit':
                submit_clicks.append(s)

        # 契约：提交点击必须唯一
        if len(submit_clicks) != 1:
            return False

        return True

    # 若本 Turn 显式调用了 save_function，必须由成功 save_function 及其确认的表单 artifact 确证，严禁盲目取 [0]
    if has_save_function_call or save_id_set:
        form_matches = [f for f in authorized_candidates if _is_form_function(f)]
        if len(form_matches) == 1:
            return str(form_matches[0]['function_id']).strip()
        # 多个候选表单匹配数不为 1，或者仅有 ID 无完整 artifact 证明表单双绑定与提交，严格判定歧义
        raise BlockedOutcome('form_function_ambiguous')

    # 无成功调用 save_function 与 get_function 详情时不予授权任何 Function ID，严禁遗留伪 ID bypass
    return ""



def verify_replay_association(turn_data: dict, run_log: dict, expected_function_id: str) -> bool:
    """证明回放 run 真实关联且成功执行了 expected_function_id，防止普通 GUI run 冒充或回放失败回退 VLM 误报。

    核心防冒充与回放成功契约：
    1. 不能仅凭一个 failed step、diagnostics 记录或顶层 vlm_task 标签就判定真实回放；
    2. 必须在底层 RunLog steps 中确证属于 expected_function_id 的执行步骤；
    3. 属于 expected_function_id 的每个步骤必须实际执行成功 (success=True 且无 error，origin != 'blocked')；
       若发生任何 step 失败（如 omnitransfer_error、origin='blocked'）导致回退普通 VLM，严格判定未回放成功；
    4. 确证成功回放的步骤必须实际覆盖表单的双输入绑定与提交动作（至少 2 次 input_text 与 1 次 click 提交）。
    """
    if not expected_function_id or not isinstance(run_log, dict):
        return False

    steps = run_log.get('steps', [])
    if not isinstance(steps, list) or not steps:
        return False

    # 提取所有属于 expected_function_id 的执行步骤
    fn_steps = []
    for s in steps:
        if not isinstance(s, dict):
            continue
        meta = s.get('metadata') or {}
        step_fn = meta.get('function_id') or meta.get('replay_function_id')
        if not step_fn:
            transfer = meta.get('transfer')
            if isinstance(transfer, dict):
                step_fn = transfer.get('function_id')
        if step_fn and str(step_fn).strip() == expected_function_id:
            fn_steps.append(s)

    # 必须存在属于该 Function 的真实执行步骤（严禁仅凭 diagnostics 或顶层 vlm_task 冒充）
    if not fn_steps:
        return False

    # 契约：所有属于该 Function 的步骤必须全部成功执行，严禁失败步骤
    for s in fn_steps:
        res = s.get('result') or {}
        if res.get('success') is not True:
            return False
        if res.get('error'):
            return False
        meta = s.get('metadata') or {}
        if meta.get('origin') == 'blocked':
            return False

    # 契约：确证实际完成了表单双输入与提交（至少 2 个成功 input_text 与至少 1 个成功 click）
    input_count = sum(1 for s in fn_steps if (s.get('action') or {}).get('tool') == 'input_text')
    click_count = sum(1 for s in fn_steps if (s.get('action') or {}).get('tool') == 'click')
    if input_count < 2 or click_count < 1:
        return False

    return True

def assert_interaction(serial: str, marker: str, phase: str, db, adb_runner=None) -> dict:
    """Case 1: 基础手势、条件等待与状态完成断言。"""
    safe_outcome_verify(db, marker, 'done')
    turn_data = extract_turn_data(db, marker)
    vlm_task = turn_data.get('vlm_task')
    vlm_event = turn_data.get('vlm_task_event')
    if not vlm_task and not vlm_event:
        raise FailedOutcome('missing_vlm_task')

    run_id = extract_vlm_task_run_id(vlm_task, vlm_event)
    if not run_id:
        raise FailedOutcome('missing_run_id_in_vlm_task')

    if vlm_task and not vlm_task.get('success'):
        raise FailedOutcome('vlm_task_not_success')

    finished_ev = (vlm_task or {}).get('finished_evidence', {})
    if finished_ev.get('status') == 'model_asserted':
        raise FailedOutcome('finished_evidence_model_asserted')
    if finished_ev.get('status') != 'matched':
        raise FailedOutcome('finished_evidence_not_matched')
    if finished_ev.get('source') not in ('xml', 'ocr'):
        raise FailedOutcome('invalid_evidence_source')

    run_log = read_sealed_run_log(serial, run_id, adb_runner=adb_runner)
    if not run_log.get('success'):
        raise FailedOutcome('run_log_status_failed')

    steps = run_log.get('steps', [])
    lp_steps = [s for s in steps if s.get('action', {}).get('tool') == 'long_press']
    if not lp_steps:
        raise FailedOutcome('missing_long_press_step')
    for lp in lp_steps:
        dur = lp.get('action', {}).get('args', {}).get('duration_ms', 800)
        if dur < 800:
            raise FailedOutcome('long_press_duration_too_short')
        if not lp.get('result', {}).get('success', False):
            raise FailedOutcome('long_press_step_not_successful')

    wait_steps = [
        s for s in steps
        if s.get('action', {}).get('tool') == 'wait' and s.get('action', {}).get('args', {}).get('condition')
    ]
    if not wait_steps:
        raise FailedOutcome('missing_condition_wait_step')
    for ws in wait_steps:
        if not ws.get('result', {}).get('success', False):
            raise FailedOutcome('wait_step_not_successful')
        diag = ws.get('metadata', {}).get('action_result', {}).get('diagnostics', {})
        cond_met = diag.get('condition_met')
        if cond_met is not True and str(cond_met).lower() != 'true':
            raise FailedOutcome('wait_condition_not_met')

    for s in steps:
        diag = s.get('metadata', {}).get('action_result', {}).get('diagnostics', {})
        target_disp = diag.get('targetDisplayId') or diag.get('display_id')
        if target_disp is not None and str(target_disp) == '0':
            raise FailedOutcome('step_targeted_main_display')

    fixture = verify_fixture_base(serial, marker, adb_runner=adb_runner)
    flags = set(fixture.get('status_flags', []))
    for required_flag in ('LONG_PRESS_OK', 'LOAD_OK', 'CHECKOUT_OK'):
        if required_flag not in flags:
            raise FailedOutcome('missing_fixture_flags')

    counts = fixture.get('counts', {})
    if counts.get('long_press', 0) != 1:
        raise FailedOutcome('fixture_long_press_count_not_one')
    if counts.get('load', 0) != 1:
        raise FailedOutcome('fixture_load_count_not_one')
    if counts.get('confirm', 0) != 1:
        raise FailedOutcome('fixture_confirm_count_not_one')
    if counts.get('checkout', 0) != 1:
        raise FailedOutcome('fixture_checkout_count_not_one')

    check_display0_foreground(serial, adb_runner=adb_runner)

    return {
        'run_id': run_id,
        'counts': counts,
        'flags': list(flags),
        'display_id': fixture.get('display_id'),
        'display_triangle_verified': True,
    }


def verify_collection_region(serial: str, run_log: dict, collection: dict, adb_runner=None) -> None:
    """按引用的真实 State 验证采集来源与滚动路径，不用额外显示屏字段猜测。"""
    regions = {}

    def region_for(state_id):
        if not isinstance(state_id, str) or not state_id:
            raise BlockedOutcome('collection_state_evidence_missing')
        if state_id in regions:
            return regions[state_id]
        stem = f'files/run_logs/states/{safe_hash(state_id)}_{safe_file_part(state_id)}'
        try:
            state = json.loads(adb_cat_file(serial, stem + '.json', adb_runner=adb_runner))
            xml = state.get('xml') or adb_cat_file(serial, stem + '.xml', adb_runner=adb_runner)
            root = ET.fromstring(xml)
            width, height = state['display']['width'], state['display']['height']
        except Exception:
            raise BlockedOutcome('collection_state_evidence_missing')
        if state.get('state_id') != state_id or state.get('package_name') != 'cn.com.omnimind.androidgui.test':
            raise FailedOutcome('collection_state_identity_mismatch')
        if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0 for value in (width, height)):
            raise BlockedOutcome('collection_display_size_unavailable')
        nodes = [node for node in root.iter('node') if node.get('resource-id') == 'cn.com.omnimind.androidgui.test:id/scroll_collection'
                 and node.get('scrollable') == 'true' and node.get('password') != 'true']
        if len(nodes) != 1:
            raise BlockedOutcome('collection_scroll_region_unobservable')
        raw = [int(value) for value in re.findall(r'-?\d+', nodes[0].get('bounds', ''))]
        if len(raw) != 4 or raw[2] <= raw[0] or raw[3] <= raw[1]:
            raise BlockedOutcome('collection_scroll_region_unobservable')
        region = (raw[0] / width * 1000, raw[1] / height * 1000, raw[2] / width * 1000, raw[3] / height * 1000)
        regions[state_id] = region
        return region

    swipes = [step for step in run_log.get('steps', []) if step.get('action', {}).get('tool') == 'swipe']
    if collection.get('pages_scanned', 0) > 1 and not swipes:
        raise FailedOutcome('collection_scroll_steps_missing')
    for step in swipes:
        if step.get('result', {}).get('success') is not True:
            raise FailedOutcome('collection_swipe_not_successful')
        left, top, right, bottom = region_for(step.get('before_state_id'))
        args = step['action'].get('args', {})
        for x_key, y_key in (('x1', 'y1'), ('x2', 'y2')):
            x, y = args.get(x_key), args.get(y_key)
            if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) for value in (x, y)):
                raise FailedOutcome('collection_swipe_coordinates_invalid')
            if not (left <= x <= right and top <= y <= bottom):
                raise FailedOutcome('collection_swipe_outside_region')
    for item in collection.get('items', []):
        left, top, right, bottom = region_for(item.get('state_id'))
        box = item.get('bounds', [])
        if len(box) != 4 or any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) for value in box):
            raise FailedOutcome('collection_item_bounds_invalid')
        if min(box[2], right) <= max(box[0], left) or min(box[3], bottom) <= max(box[1], top):
            raise FailedOutcome('collection_item_outside_region')


def assert_collection(serial: str, marker: str, phase: str, db, adb_runner=None) -> dict:
    """Case 2: 多屏采集与有界截断断言。"""
    safe_outcome_verify(db, marker, 'done')
    turn_data = extract_turn_data(db, marker)
    vlm_task = turn_data.get('vlm_task')
    vlm_event = turn_data.get('vlm_task_event')
    if not vlm_task and not vlm_event:
        raise FailedOutcome('missing_vlm_task')

    run_id = extract_vlm_task_run_id(vlm_task, vlm_event)
    collection = (vlm_task or {}).get('collection')
    if not collection:
        raise FailedOutcome('missing_collection_result')

    items = collection.get('items', [])
    pages_scanned = collection.get('pages_scanned', 0)
    truncated = collection.get('truncated', False)
    stop_reason = collection.get('stop_reason', '')

    run_log = read_sealed_run_log(serial, run_id, adb_runner=adb_runner)
    fixture = verify_fixture_base(serial, marker, adb_runner=adb_runner)

    if phase == 'truncated':
        if not truncated:
            raise FailedOutcome('collection_not_truncated')
        if stop_reason != 'page_limit':
            raise FailedOutcome('invalid_truncation_stop_reason')
        if pages_scanned != 1:
            raise FailedOutcome('truncated_pages_scanned_not_one')
        reply = turn_data.get('assistant_reply', '')
        if 'ITEM_50' in reply and '全部' in reply:
            raise FailedOutcome('assistant_falsely_claimed_complete_collection')
    else:  # full
        if truncated:
            raise FailedOutcome('collection_unexpectedly_truncated')
        if stop_reason != 'no_new_content':
            raise FailedOutcome('collection_stop_reason_not_no_new_content')

        texts = [it.get('text', '').strip() for it in items]
        item_nums = [t for t in texts if re.match(r'^ITEM_\d{2}$', t)]
        # 1. 严格 50 个编号且各出现一次（无遗漏、无重复编号）
        if len(item_nums) != 50:
            raise FailedOutcome('numbered_items_count_not_50')
        expected_items = {f'ITEM_{i:02d}' for i in range(1, 51)}
        if set(item_nums) != expected_items:
            raise FailedOutcome('numbered_items_set_mismatch')

        if item_nums[0] != 'ITEM_01' or item_nums[-1] != 'ITEM_50':
            raise FailedOutcome('items_order_mismatch')

        # 2. 严格 2 条同文重复行且处于不同物理位置
        dup_rows = [it for it in items if 'DUPLICATE_ROW' in it.get('text', '')]
        if len(dup_rows) != 2:
            raise FailedOutcome('duplicate_rows_count_not_two')

        dup_locs = {(it.get('page_index'), tuple(it.get('bounds', []))) for it in dup_rows}
        if len(dup_locs) != 2:
            raise FailedOutcome('duplicate_rows_not_at_distinct_locations')

        # 3. 50 编号 + 2 重复行 = 至少 52 条内容项
        if len(items) < 52:
            raise FailedOutcome('collection_total_items_insufficient')

        # 4. 父答必须同时包含 50、首尾（ITEM_01 / ITEM_50）及重复 2 行事实
        reply = turn_data.get('assistant_reply', '')
        has_50 = '50' in reply
        has_first = 'ITEM_01' in reply
        has_last = 'ITEM_50' in reply
        has_dup = ('2' in reply or '两' in reply) and ('重复' in reply or 'DUPLICATE_ROW' in reply)
        if not (has_50 and has_first and has_last and has_dup):
            raise FailedOutcome('assistant_reply_lacks_collection_grounding')

    if run_log.get('success') is not True or (vlm_task or {}).get('success') is not True or collection.get('partial') is True:
        raise FailedOutcome('collection_run_not_successful')
    check_display0_foreground(serial, adb_runner=adb_runner)
    verify_collection_region(serial, run_log, collection, adb_runner=adb_runner)
    return {
        'run_id': run_id,
        'items_count': len(items),
        'pages_scanned': pages_scanned,
        'truncated': truncated,
        'stop_reason': stop_reason,
        'display_id': fixture.get('display_id'),
        'swipe_region_verified': True,
    }


def assert_experience(
    serial: str,
    marker: str,
    phase: str,
    db,
    adb_runner=None,
    source_marker: str = None,
    expected_experience_id: str = None,
    expected_form_first: str = None,
    expected_form_second: str = None
) -> dict:
    """Case 3: 应用经验试用、转正、停用、退役与 Function 回放断言。"""
    safe_outcome_verify(db, marker, 'done')
    turn_data = extract_turn_data(db, marker)
    vlm_task = turn_data.get('vlm_task')
    vlm_event = turn_data.get('vlm_task_event')
    if not vlm_task and not vlm_event:
        raise FailedOutcome('missing_vlm_task')

    run_id = extract_vlm_task_run_id(vlm_task, vlm_event)
    sidecar = read_app_experiences_sidecar(serial, adb_runner=adb_runner)
    items = sidecar.get('items', [])
    fixture = verify_fixture_base(serial, marker, adb_runner=adb_runner)
    check_display0_foreground(serial, adb_runner=adb_runner)

    flags = set(fixture.get('status_flags', []))
    counts = fixture.get('counts', {})
    form_vals = fixture.get('form_values', {})

    # 存储隐私独立于场景成功：出现输入值字段立即拒绝。
    for item in items:
        for container in (item, item.get('rule', {})):
            if any(key in container for key in ('value', 'text', 'input_value', 'form_value', 'target_description')):
                raise FailedOutcome('sidecar_rule_contains_forbidden_field')
    # 针对非 rollback 的所有执行阶段，严格确证真实 sealed、run success、FORM_OK、表单合成值与计数
    if phase not in ('rollback1', 'rollback2'):
        if vlm_task and not vlm_task.get('success'):
            raise FailedOutcome(f'{phase}_vlm_task_failed')
        finished_ev = (vlm_task or {}).get('finished_evidence', {})
        if finished_ev.get('status') == 'model_asserted':
            raise FailedOutcome(f'{phase}_finished_evidence_model_asserted')
        if finished_ev.get('status') != 'matched':
            raise FailedOutcome(f'{phase}_finished_evidence_not_matched')

        if 'FORM_OK' not in flags:
            raise FailedOutcome(f'{phase}_missing_FORM_OK')

        if expected_form_first and form_vals.get('first') != expected_form_first:
            raise FailedOutcome(f'{phase}_form_first_value_mismatch')
        if expected_form_second and form_vals.get('second') != expected_form_second:
            raise FailedOutcome(f'{phase}_form_second_value_mismatch')

        if counts.get('submit', 0) != 1:
            raise FailedOutcome(f'{phase}_submit_count_not_one')

        run_log = read_sealed_run_log(serial, run_id, adb_runner=adb_runner)
        if not run_log.get('success'):
            raise FailedOutcome(f'{phase}_run_not_succeeded')
    else:
        # rollback 阶段：动作预期无效果，vlm_task 允许失败，run success 除外，但 RunLog 必须已真实封存
        run_log = read_sealed_run_log(serial, run_id, adb_runner=adb_runner)

    if phase == 'round1':
        # 严格筛选当前 run 的目标应用规则：必须完整匹配包名、资源 ID 及 click 动作
        expected_pkg = 'cn.com.omnimind.androidgui.test'
        expected_rid = 'cn.com.omnimind.androidgui.test:id/btn_form_submit'
        expected_action = 'click'

        rule_item = None
        for it in items:
            if (
                it.get('source_run_id') == run_id
                and it.get('package_name') == expected_pkg
                and it.get('rule', {}).get('target_resource_id') == expected_rid
                and it.get('rule', {}).get('action') == expected_action
            ):
                rule_item = it
                break

        if not rule_item:
            has_preexisting = any(
                it.get('source_run_id') != run_id
                and it.get('package_name') == expected_pkg
                and it.get('rule', {}).get('target_resource_id') == expected_rid
                and it.get('rule', {}).get('action') == expected_action
                and it.get('enabled', True)
                for it in items
            )
            if has_preexisting:
                raise BlockedOutcome('preexisting_experience_rule_in_sidecar')
            raise FailedOutcome('round1_no_experience_rule_created')

        if rule_item.get('status') != 'trial':
            raise FailedOutcome('round1_rule_status_not_trial')
        if not rule_item.get('enabled', False):
            raise FailedOutcome('round1_rule_disabled')
        if len(rule_item.get('successful_run_ids', [])) != 0:
            raise FailedOutcome('round1_successful_run_ids_not_empty')
        if run_id not in sidecar.get('processed_run_ids', []):
            raise FailedOutcome('round1_run_id_not_in_processed_run_ids')

        rule = rule_item.get('rule', {})
        res_id = rule.get('target_resource_id', '')
        action = rule.get('action', '')

        registered_fn_id = extract_registered_function_id(turn_data, run_log, serial=serial, adb_runner=adb_runner)

        return {
            'run_id': run_id,
            'source_run_id': run_id,
            'experience_id': rule_item.get('id'),
            'experience_status': rule_item.get('status'),
            'resource_id': res_id,
            'action': action,
            'registered_function_id': registered_fn_id or None,
            'flags': list(flags),
            'display_id': fixture.get('display_id'),
        }

    elif phase == 'round2':
        if not expected_experience_id:
            raise BlockedOutcome('expected_experience_id_required')
        rule_item = next((it for it in items if it.get('id') == expected_experience_id), None)
        if not rule_item:
            raise FailedOutcome('round2_target_rule_not_found')

        steps = run_log.get('steps', [])
        rule_used = any(rule_item['id'] in s.get('metadata', {}).get('app_experience_ids', []) for s in steps)
        if not rule_used:
            raise FailedOutcome('round2_experience_not_used_in_step')

        if rule_item.get('status') != 'trial':
            raise FailedOutcome('round2_rule_status_not_trial')
        if run_id not in rule_item.get('successful_run_ids', []):
            raise FailedOutcome('round2_run_id_not_in_successful_runs')
        if len(rule_item.get('successful_run_ids', [])) != 1:
            raise FailedOutcome('round2_successful_run_count_not_one')

        return {
            'run_id': run_id,
            'experience_id': rule_item.get('id'),
            'experience_status': rule_item.get('status'),
            'display_id': fixture.get('display_id'),
        }

    elif phase == 'round3':
        if not expected_experience_id:
            raise BlockedOutcome('expected_experience_id_required')
        rule_item = next((it for it in items if it.get('id') == expected_experience_id), None)
        if not rule_item:
            raise FailedOutcome('round3_target_rule_not_found')

        steps = run_log.get('steps', [])
        rule_used = any(rule_item['id'] in s.get('metadata', {}).get('app_experience_ids', []) for s in steps)
        if not rule_used:
            raise FailedOutcome('round3_experience_not_used_in_step')

        if rule_item.get('status') != 'verified':
            raise FailedOutcome('round3_rule_not_verified')
        if len(rule_item.get('successful_run_ids', [])) != 2:
            raise FailedOutcome('round3_successful_run_count_not_two')

        return {
            'run_id': run_id,
            'experience_id': rule_item.get('id'),
            'experience_status': rule_item.get('status'),
            'display_id': fixture.get('display_id'),
        }

    elif phase == 'disabled':
        if not expected_experience_id:
            raise BlockedOutcome('expected_experience_id_required')
        rule_item = next((it for it in items if it.get('id') == expected_experience_id), None)
        if not rule_item:
            raise FailedOutcome('disabled_target_rule_not_found')
        if rule_item.get('enabled', True):
            raise FailedOutcome('disabled_rule_still_enabled_in_sidecar')

        steps = run_log.get('steps', [])
        rule_used = any(rule_item['id'] in s.get('metadata', {}).get('app_experience_ids', []) for s in steps)
        if rule_used:
            raise FailedOutcome('disabled_rule_was_used_in_execution')

        return {
            'run_id': run_id,
            'experience_id': rule_item.get('id'),
            'experience_enabled': rule_item.get('enabled'),
            'display_id': fixture.get('display_id'),
        }

    elif phase == 'rollback1':
        if not fixture.get('entry_disabled', False):
            raise FailedOutcome('rollback_entry_not_disabled_in_fixture')
        if not expected_experience_id:
            raise BlockedOutcome('expected_experience_id_required')
        rule_item = next((it for it in items if it.get('id') == expected_experience_id), None)
        if not rule_item:
            raise FailedOutcome('rollback1_target_rule_not_found')
        if rule_item.get('consecutive_failures', 0) != 1:
            raise FailedOutcome('rollback1_failures_not_one')
        return {
            'run_id': run_id,
            'experience_id': rule_item.get('id'),
            'consecutive_failures': rule_item.get('consecutive_failures'),
            'display_id': fixture.get('display_id'),
        }

    elif phase == 'rollback2':
        if not fixture.get('entry_disabled', False):
            raise FailedOutcome('rollback_entry_not_disabled_in_fixture')
        if not expected_experience_id:
            raise BlockedOutcome('expected_experience_id_required')
        rule_item = next((it for it in items if it.get('id') == expected_experience_id), None)
        if not rule_item:
            raise FailedOutcome('rollback2_target_rule_not_found')
        if rule_item.get('consecutive_failures', 0) < 2:
            raise FailedOutcome('rollback2_failures_insufficient')
        if rule_item.get('status') != 'retired':
            raise FailedOutcome('rollback2_rule_not_retired')
        return {
            'run_id': run_id,
            'experience_id': rule_item.get('id'),
            'experience_status': rule_item.get('status'),
            'display_id': fixture.get('display_id'),
        }

    elif phase == 'replay':
        if not source_marker:
            raise BlockedOutcome('replay_source_marker_missing')

        src_turn = extract_turn_data(db, source_marker)
        src_vlm = src_turn.get('vlm_task')
        src_evt = src_turn.get('vlm_task_event')
        src_run_id = extract_vlm_task_run_id(src_vlm, src_evt)
        if not src_run_id and src_turn:
            save_rids = set()
            for entry_type, p in src_turn.get('entries', []):
                if entry_type == 'tool_event' and p.get('toolName') == 'save_function' and p.get('success'):
                    args_raw = p.get('argsJson')
                    if args_raw:
                        try:
                            args_data = json.loads(args_raw) if isinstance(args_raw, str) else args_raw
                            if isinstance(args_data, dict):
                                cid = str(args_data.get('run_id') or '').strip()
                                if cid:
                                    save_rids.add(cid)
                        except Exception:
                            pass
            if len(save_rids) == 1:
                src_run_id = next(iter(save_rids))
        src_run_log = None
        if src_run_id:
            try:
                src_run_log = read_sealed_run_log(serial, src_run_id, adb_runner=adb_runner)
            except Exception:
                pass

        source_function_id = extract_registered_function_id(src_turn, src_run_log, serial=serial, adb_runner=adb_runner)
        if not source_function_id:
            raise BlockedOutcome('source_function_not_registered')

        flags = set(fixture.get('status_flags', []))
        if 'FORM_OK' not in flags:
            raise FailedOutcome('replay_missing_FORM_OK')

        # 确证 Function 真实注册与回放参数
        form_vals = fixture.get('form_values', {})
        if expected_form_first and form_vals.get('first') != expected_form_first:
            raise FailedOutcome('replay_form_first_value_mismatch')
        if expected_form_second and form_vals.get('second') != expected_form_second:
            raise FailedOutcome('replay_form_second_value_mismatch')

        # 核查 RunLog 回放执行证据
        run_log = read_sealed_run_log(serial, run_id, adb_runner=adb_runner)
        if not run_log.get('success'):
            raise FailedOutcome('replay_run_not_succeeded')

        # 核心防冒充检查：必须证明回放关联了 source_function_id
        if not verify_replay_association(turn_data, run_log, source_function_id):
            raise FailedOutcome('replay_not_associated_with_source_function')

        return {
            'run_id': run_id,
            'source_run_id': src_run_id,
            'source_function_id': source_function_id,
            'replay_verified': True,
            'flags': list(flags),
            'display_id': fixture.get('display_id'),
        }
    raise FailedOutcome('unrecognized_experience_phase')


def assert_cancel(
    serial: str,
    marker: str,
    phase: str,
    db,
    adb_runner=None,
    source_marker: str = None
) -> dict:
    """Case 4: 用户中止取消与后继恢复断言。"""
    safe_outcome_verify(db, marker, 'done' if phase == 'recovery' else 'cancelled')
    fixture = verify_fixture_base(serial, marker, adb_runner=adb_runner)

    if phase == 'recovery':
        turn_data = extract_turn_data(db, marker)
        vlm_task = turn_data.get('vlm_task')
        vlm_event = turn_data.get('vlm_task_event')
        if not vlm_task and not vlm_event:
            raise FailedOutcome('recovery_missing_vlm_task')

        run_id = extract_vlm_task_run_id(vlm_task, vlm_event)
        if not run_id:
            raise FailedOutcome('recovery_run_id_unresolvable')

        # 校验与来源取消 Turn 的身份隔离
        if source_marker:
            try:
                src_data = extract_turn_data(db, source_marker)
                src_run_id = extract_vlm_task_run_id(src_data.get('vlm_task'), src_data.get('vlm_task_event'))
                if src_run_id and src_run_id == run_id:
                    raise FailedOutcome('recovery_reused_cancelled_run_id')
                src_stream = (src_data.get('vlm_task_event') or {}).get('streamMeta', {})
                cur_stream = (vlm_event or {}).get('streamMeta', {})
                if src_stream.get('turnId') and src_stream.get('turnId') == cur_stream.get('turnId'):
                    raise FailedOutcome('recovery_reused_cancelled_turn_id')
            except PendingOutcome:
                pass

        run_log = read_sealed_run_log(serial, run_id, adb_runner=adb_runner)
        if not run_log.get('success'):
            raise FailedOutcome('recovery_run_not_succeeded')

        steps = run_log.get('steps', [])
        if not steps:
            raise FailedOutcome('recovery_no_steps_executed')
        if not any(s.get('result', {}).get('success', False) for s in steps):
            raise FailedOutcome('recovery_steps_not_successful')

        counts = fixture.get('counts', {})
        flags = fixture.get('status_flags', [])
        has_action = (
            counts.get('confirm', 0) >= 1
            or counts.get('tap', 0) >= 1
            or counts.get('long_press', 0) >= 1
            or counts.get('submit', 0) >= 1
            or len(flags) > 0
        )
        if not has_action:
            raise FailedOutcome('recovery_no_fixture_action_observed')

        return {
            'run_id': run_id,
            'counts': counts,
            'flags': flags,
            'display_id': fixture.get('display_id'),
        }

    # 主阶段 cancel 断言

    # 1. 确证 vlm_task 必须已被准入（admitted）
    turn_data = extract_turn_data(db, marker)
    vlm_task = turn_data.get('vlm_task')
    vlm_event = turn_data.get('vlm_task_event')
    if not vlm_task and not vlm_event:
        raise BlockedOutcome('cancel_run_evidence_missing')

    run_id = extract_vlm_task_run_id(vlm_task, vlm_event)
    if not run_id:
        raise BlockedOutcome('cancel_run_id_unresolvable')

    # 2. 封存 RunLog 身份与 cancelled 终态检查
    run_rel = get_run_log_rel_path(run_id)
    run_raw = adb_cat_file(serial, run_rel, adb_runner=adb_runner)
    if not run_raw or not run_raw.strip():
        raise PendingOutcome('waiting_for_sealed_cancelled_run_log')
    try:
        run_log = json.loads(run_raw)
    except Exception:
        raise FailedOutcome('cancel_run_log_invalid_json')

    finished_at = run_log.get('finished_at_ms')
    if not finished_at or finished_at <= 0:
        raise PendingOutcome('waiting_for_sealed_cancelled_run_log')

    if run_log.get('run_id') != run_id:
        raise FailedOutcome('run_id_mismatch')

    if run_log.get('status') == 'succeeded' and run_log.get('success') is True:
        raise FailedOutcome('cancel_run_log_marked_succeeded')

    if run_log.get('status') not in ('cancelled', 'failed'):
        raise FailedOutcome('cancel_run_log_status_not_cancelled')

    # 3. 确证未写入应用经验 Sidecar
    app_experiences_verified = False
    app_experiences_status = 'not_checked'
    try:
        sidecar = read_app_experiences_sidecar(serial, adb_runner=adb_runner)
        if run_id in sidecar.get('processed_run_ids', []):
            raise FailedOutcome('cancelled_run_processed_into_app_experiences')
        cancelled_candidates = [
            it for it in sidecar.get('items', [])
            if it.get('source_run_id') == run_id
        ]
        if cancelled_candidates:
            raise FailedOutcome('cancelled_run_generated_experience_rule')
        app_experiences_verified = True
        app_experiences_status = 'verified_no_new_experience'
    except BlockedOutcome:
        app_experiences_verified = False
        app_experiences_status = 'not_checked'

    # 4. 迟到点击防护检查：当前确认数为 0
    counts = fixture.get('counts', {})
    if counts.get('confirm', 0) != 0:
        raise FailedOutcome('cancel_has_late_confirm')

    # 只读等待 1 秒后复查，防范延迟异步点击
    time.sleep(1.0)
    fixture_after = verify_fixture_base(serial, marker, adb_runner=adb_runner)
    counts_after = fixture_after.get('counts', {})
    if counts_after.get('confirm', 0) != 0:
        raise FailedOutcome('cancel_delayed_late_confirm')
    if not app_experiences_verified:
        raise BlockedOutcome('cancel_experience_evidence_insufficient', details={
            'run_id': run_id,
            'counts': counts_after,
            'display_id': fixture.get('display_id'),
            'app_experiences_verified': False,
            'app_experiences_status': 'not_checked',
            'cancel_verified': True,
        })

    return {
        'run_id': run_id,
        'counts': counts_after,
        'display_id': fixture.get('display_id'),
        'app_experiences_verified': True,
        'app_experiences_status': 'verified_no_new_experience',
    }

_RUNTIME_MODULES = ('bridge', 'runtime.engine', 'vlm.planner', 'core.schemas', 'runtime.app_experience', 'runtime.screen_collection', 'runlog')
_SHA256 = re.compile(r'^[a-f0-9]{64}$')
_REASONING_LEVELS = {'none', 'off', 'no', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'}
_KNOWN_NULLABLE_FIELDS = {
    'max_completion_tokens',
    'max_tokens',
    'reasoning_effort',
    'enable_thinking',
    'thinking_type',
    'reasoning_supported',
    'reasoning_can_disable',
    'supported_reasoning_levels',
    'default_reasoning_level',
}


def verify_runtime_provenance(run_log: dict, expected_runtime: dict | None) -> dict:
    if not expected_runtime:
        raise BlockedOutcome('expected_runtime_identity_missing')
    if not isinstance(expected_runtime, dict):
        raise BlockedOutcome('expected_runtime_identity_invalid')
    provenance = run_log.get('diagnostics', {}).get('runtime_provenance')
    if not isinstance(provenance, dict) or provenance.get('schema_version') != 'omniflow.runtime-provenance.v1':
        raise BlockedOutcome('runtime_provenance_missing')
    source = provenance.get('source')
    if source not in ('packaged', 'installed'):
        raise BlockedOutcome('runtime_not_loaded_from_packaged_source')
    for key in ('runtime_version', 'protocol_version', 'manifest_source_sha256', 'contract_sha256'):
        if provenance.get(key) != expected_runtime.get(key):
            raise FailedOutcome('runtime_identity_mismatch')
    loaded = provenance.get('loaded_modules', {})
    expected = expected_runtime.get('loaded_modules', {})
    for name in _RUNTIME_MODULES:
        actual_module = loaded.get(name, {})
        expected_module = expected.get(name, {})
        if (
            not isinstance(actual_module, dict)
            or actual_module.get('status') == 'missing'
            or not all(_SHA256.fullmatch(str(actual_module.get(key, ''))) for key in ('source_sha256', 'code_sha256'))
        ):
            raise BlockedOutcome('runtime_loaded_module_evidence_missing')
        # code摘要用于同实例身份；跨Python版本仅比较正式归档源码摘要。
        if (
            not isinstance(expected_module, dict)
            or not _SHA256.fullmatch(str(expected_module.get('source_sha256', '')))
            or actual_module['source_sha256'] != expected_module['source_sha256']
        ):
            raise FailedOutcome('runtime_loaded_module_mismatch')
    return {'runtime_verified': True, 'runtime_version': provenance.get('runtime_version'), 'module_count': len(_RUNTIME_MODULES)}


def verify_model_execution_config(run_log: dict, resolved_model: str, phase: str) -> dict:
    diagnostics = run_log.get('diagnostics', {})
    config = diagnostics.get('model_execution_config')
    calls = diagnostics.get('token_usage_by_call')
    if not isinstance(config, dict) or not isinstance(calls, list) or not calls:
        raise BlockedOutcome('model_configuration_evidence_insufficient')
    if config.get('schema_version') != 'omniflow.model-execution-config.v1':
        raise BlockedOutcome('model_configuration_evidence_insufficient')
    raw_hash = config.get('provider_profile_sha256')
    if raw_hash is None or raw_hash == '':
        raise BlockedOutcome('model_configuration_evidence_insufficient')
    if not _SHA256.fullmatch(str(raw_hash)):
        raise FailedOutcome('model_provider_hash_invalid')
    if config.get('resolved_model') != resolved_model:
        raise FailedOutcome('model_configuration_route_mismatch')
    route = config.get('route_source')
    if route not in ('gui_binding', 'conversation', 'shared_scene') or (phase == 'binding') != (route == 'gui_binding'):
        raise FailedOutcome('model_route_precedence_mismatch')

    def positive_integer(value):
        return isinstance(value, int) and not isinstance(value, bool) and value > 0

    capacity, budget = config.get('context_capacity'), config.get('output_budget')
    if not positive_integer(capacity) or not positive_integer(budget) or budget > capacity:
        raise FailedOutcome('model_output_budget_invalid')
    limits = [config.get('max_completion_tokens'), config.get('max_tokens')]
    if not any(value is not None for value in limits) or any(
        value is not None and (not positive_integer(value) or value > budget) for value in limits
    ):
        raise FailedOutcome('model_actual_output_limit_invalid')

    omitted = config.get('omitted_fields')
    if omitted is not None:
        if not isinstance(omitted, list) or not all(isinstance(x, str) for x in omitted):
            raise FailedOutcome('model_reasoning_configuration_invalid')
        if any(f not in _KNOWN_NULLABLE_FIELDS for f in omitted):
            raise FailedOutcome('model_omitted_field_unknown')
    omitted_set = set(omitted) if omitted is not None else set()

    for field in _KNOWN_NULLABLE_FIELDS:
        if config.get(field) is not None and field in omitted_set:
            raise FailedOutcome('model_omitted_field_conflict')

    required_reasoning = ('reasoning_effort', 'enable_thinking', 'thinking_type', 'reasoning_supported', 'reasoning_can_disable')
    for key in required_reasoning:
        if config.get(key) is None and key not in omitted_set:
            raise BlockedOutcome('model_reasoning_configuration_missing')

    effort = config.get('reasoning_effort')
    enabled = config.get('enable_thinking')
    thinking = config.get('thinking_type')
    reasoning_supported = config.get('reasoning_supported')
    reasoning_can_disable = config.get('reasoning_can_disable')
    default_level = config.get('default_reasoning_level')
    levels = config.get('supported_reasoning_levels')

    if effort is not None and effort not in _REASONING_LEVELS:
        raise FailedOutcome('model_reasoning_configuration_invalid')
    if enabled is not None and not isinstance(enabled, bool):
        raise FailedOutcome('model_reasoning_configuration_invalid')
    if thinking is not None and thinking not in ('enabled', 'disabled'):
        raise FailedOutcome('model_reasoning_configuration_invalid')
    if reasoning_supported is not None and not isinstance(reasoning_supported, bool):
        raise FailedOutcome('model_reasoning_configuration_invalid')
    if reasoning_can_disable is not None and not isinstance(reasoning_can_disable, bool):
        raise FailedOutcome('model_reasoning_configuration_invalid')
    if default_level is not None and default_level not in _REASONING_LEVELS:
        raise FailedOutcome('model_reasoning_configuration_invalid')
    if levels is not None and (not isinstance(levels, list) or not all(isinstance(lvl, str) and lvl in _REASONING_LEVELS for lvl in levels)):
        raise FailedOutcome('model_reasoning_configuration_invalid')

    if reasoning_supported is False and any(value is not None for value in (effort, enabled, thinking)):
        raise FailedOutcome('model_nonreasoning_parameters_sent')
    if reasoning_can_disable is False and (effort in ('none', 'off', 'no') or enabled is False or thinking == 'disabled'):
        raise FailedOutcome('model_reasoning_disable_unsupported')
    if levels and effort is not None and effort not in levels and effort not in ('none', 'off', 'no'):
        raise FailedOutcome('model_reasoning_level_unsupported')

    successful = [call for call in calls if call.get('success') is True]
    if not successful or any(call.get('execution_config') != config for call in successful):
        raise FailedOutcome('model_call_configuration_not_frozen')
    return {'configuration_verified': True, 'provider_profile_sha256': config['provider_profile_sha256'],
            'route_source': route, 'output_budget': budget, 'max_completion_tokens': limits[0], 'max_tokens': limits[1],
            'reasoning_effort': effort, 'enable_thinking': enabled, 'thinking_type': thinking}

def assert_model(
    serial: str,
    marker: str,
    phase: str,
    db,
    adb_runner=None,
    expected_model: str = None,
    expected_runtime: dict = None,
) -> dict:
    """Case 5: 模型路由、Token 消耗与单步预算日志证据断言。"""
    safe_outcome_verify(db, marker, 'done')
    turn_data = extract_turn_data(db, marker)
    vlm_task = turn_data.get('vlm_task')
    vlm_event = turn_data.get('vlm_task_event')
    if not vlm_task and not vlm_event:
        raise FailedOutcome('missing_vlm_task')

    if vlm_task and not vlm_task.get('success'):
        raise FailedOutcome('vlm_task_not_success')

    run_id = extract_vlm_task_run_id(vlm_task, vlm_event)
    if not run_id:
        raise FailedOutcome('missing_run_id_in_vlm_task')

    # 1. 确证 finished_evidence 必须为 xml/ocr 真实匹配，不能为 model_asserted
    finished_ev = (vlm_task or {}).get('finished_evidence', {})
    if finished_ev.get('status') == 'model_asserted':
        raise FailedOutcome('finished_evidence_model_asserted')
    if finished_ev.get('status') != 'matched':
        raise FailedOutcome('finished_evidence_not_matched')
    if finished_ev.get('source') not in ('xml', 'ocr'):
        raise FailedOutcome('invalid_evidence_source')

    # 2. 确证已封存 RunLog 且执行成功
    run_log = read_sealed_run_log(serial, run_id, adb_runner=adb_runner)
    if not run_log.get('success'):
        raise FailedOutcome('run_log_status_failed')

    # 3. 确证必须包含真实成功的 >= 800ms long_press step
    steps = run_log.get('steps', [])
    lp_steps = [s for s in steps if s.get('action', {}).get('tool') == 'long_press']
    if not lp_steps:
        raise FailedOutcome('missing_long_press_step')
    for lp in lp_steps:
        dur = lp.get('action', {}).get('args', {}).get('duration_ms', 800)
        if dur < 800:
            raise FailedOutcome('long_press_duration_too_short')
        if not lp.get('result', {}).get('success', False):
            raise FailedOutcome('long_press_step_not_successful')

    # 4. 验证动作未向主屏注入
    for s in steps:
        diag = s.get('metadata', {}).get('action_result', {}).get('diagnostics', {})
        target_disp = diag.get('targetDisplayId') or diag.get('display_id')
        if target_disp is not None and str(target_disp) == '0':
            raise FailedOutcome('step_targeted_main_display')

    # 5. 确证后台虚拟屏 Fixture 基础证据与 LONG_PRESS_OK 计数
    fixture = verify_fixture_base(serial, marker, adb_runner=adb_runner)
    flags = set(fixture.get('status_flags', []))
    if 'LONG_PRESS_OK' not in flags:
        raise FailedOutcome('missing_fixture_flags')

    counts = fixture.get('counts', {})
    if counts.get('long_press', 0) != 1:
        raise FailedOutcome('fixture_long_press_count_not_one')

    # 6. 验证主屏（Display 0）最上层前台依然是小万主程序
    check_display0_foreground(serial, adb_runner=adb_runner)
    diag = run_log.get('diagnostics', {})
    token_usage = diag.get('token_usage', {})
    resolved_model = (
        token_usage.get('resolved_model')
        or (diag.get('resolved_models', [None])[0])
        or (vlm_task or {}).get('resolved_model')
    )

    if not resolved_model:
        raise BlockedOutcome('model_route_evidence_insufficient')

    if expected_model and resolved_model != expected_model:
        raise FailedOutcome('model_mismatch')

    prompt_tokens = token_usage.get('prompt_tokens', 0)
    completion_tokens = token_usage.get('completion_tokens', 0)
    if prompt_tokens <= 0 or completion_tokens <= 0:
        raise FailedOutcome('model_tokens_not_recorded')

    model_base_details = {
        'run_id': run_id,
        'resolved_model': resolved_model,
        'prompt_tokens': prompt_tokens,
        'completion_tokens': completion_tokens,
        'route_verified': True,
        'gesture_verified': True,
        'flags': list(flags),
        'counts': counts,
        'display_id': fixture.get('display_id'),
    }

    try:
        verified_config = verify_model_execution_config(run_log, resolved_model, phase)
        verified_runtime = verify_runtime_provenance(run_log, expected_runtime)
    except AssertOutcome as e:
        merged_details = dict(model_base_details)
        merged_details.update(e.details)
        e.details = merged_details
        raise e

    return {
        **model_base_details,
        **verified_config,
        **verified_runtime,
    }


def normalize_phase(case: str, phase: str = None) -> str:
    """规范化阶段标识。"""
    if not phase or not phase.strip():
        defaults = {
            'interaction': 'default',
            'collection': 'full',
            'experience': 'round1',
            'cancel': 'cancel',
            'model': 'default',
        }
        return defaults.get(case, 'default')
    return phase.strip().lower()


def run_assert_phone_control(
    serial: str,
    marker: str,
    case: str,
    phase: str = None,
    source_marker: str = None,
    expected_experience_id: str = None,
    expected_model: str = None,
    expected_form_first: str = None,
    expected_form_second: str = None,
    expected_runtime: dict = None,
    out_dir: str = None,
    db=None,
    adb_runner=None
) -> tuple[dict, int]:
    """执行断言并生成结构化结果。"""
    normalized_phase = normalize_phase(case, phase)
    res_details = {}
    res_counts = {}
    res_flags = []
    run_id = ""

    def execute_with_db(target_db):
        nonlocal res_details, res_counts, res_flags, run_id
        if case == 'interaction':
            ret = assert_interaction(serial, marker, normalized_phase, target_db, adb_runner=adb_runner)
        elif case == 'collection':
            ret = assert_collection(serial, marker, normalized_phase, target_db, adb_runner=adb_runner)
        elif case == 'experience':
            ret = assert_experience(
                serial, marker, normalized_phase, target_db, adb_runner=adb_runner,
                source_marker=source_marker, expected_experience_id=expected_experience_id,
                expected_form_first=expected_form_first, expected_form_second=expected_form_second
            )
        elif case == 'cancel':
            ret = assert_cancel(
                serial, marker, normalized_phase, target_db, adb_runner=adb_runner,
                source_marker=source_marker
            )
        elif case == 'model':
            ret = assert_model(
                serial, marker, normalized_phase, target_db, adb_runner=adb_runner,
                expected_model=expected_model, expected_runtime=expected_runtime
            )
        else:
            raise FailedOutcome('unknown_case')

        run_id = ret.get('run_id', '')
        res_counts = ret.get('counts', {})
        res_flags = ret.get('flags', [])
        res_details = ret

        # 若提供了 expected_runtime 且当前非 model 用例（model 用例已在 assert_model 内部校验），
        # 需在所有成功 case 依据 ret.run_id 查验已封存 RunLog 的 runtime_provenance
        if expected_runtime is not None and case != 'model':
            if not run_id:
                raise BlockedOutcome('runtime_provenance_missing')
            sealed_log = read_sealed_run_log(serial, run_id, adb_runner=adb_runner)
            runtime_res = verify_runtime_provenance(sealed_log, expected_runtime)
            res_details.update(runtime_res)

    try:
        if isinstance(expected_runtime, (str, Path)):
            try:
                expected_runtime = json.loads(Path(expected_runtime).read_text(encoding='utf-8'))
            except Exception:
                raise BlockedOutcome('expected_runtime_identity_invalid')
        if db is not None:
            execute_with_db(db)
        else:
            # 独立进入快照上下文，若主应用未安装或数据库文件无法读取则映射为环境阻塞
            try:
                snapshot_ctx = agent_database_snapshot(serial)
                target_db = snapshot_ctx.__enter__()
            except Exception:
                raise BlockedOutcome('database_snapshot_unavailable')

            try:
                execute_with_db(target_db)
            finally:
                try:
                    snapshot_ctx.__exit__(None, None, None)
                except Exception:
                    pass
        output = {
            "passed": True,
            "status": "passed",
            "case": case,
            "phase": normalized_phase,
            "category": "ok",
            "marker": marker,
            "counts": res_counts,
            "flags": res_flags,
            "hashed_run_id": safe_hash(run_id) if run_id else "",
            "details": res_details
        }
        exit_code = 0

    except PendingOutcome as e:
        output = {
            "passed": False,
            "status": "pending",
            "case": case,
            "phase": normalized_phase,
            "category": e.category,
            "marker": marker,
            "counts": res_counts,
            "flags": res_flags,
            "hashed_run_id": safe_hash(run_id) if run_id else "",
            "details": e.details
        }
        exit_code = 2

    except BlockedOutcome as e:
        output = {
            "passed": False,
            "status": "blocked",
            "case": case,
            "phase": normalized_phase,
            "category": e.category,
            "marker": marker,
            "counts": res_counts,
            "flags": res_flags,
            "hashed_run_id": safe_hash(run_id) if run_id else "",
            "details": e.details
        }
        exit_code = 3

    except FailedOutcome as e:
        output = {
            "passed": False,
            "status": "failed",
            "case": case,
            "phase": normalized_phase,
            "category": e.category,
            "marker": marker,
            "counts": res_counts,
            "flags": res_flags,
            "hashed_run_id": safe_hash(run_id) if run_id else "",
            "details": e.details
        }
        exit_code = 1

    except Exception:
        output = {
            "passed": False,
            "status": "failed",
            "case": case,
            "phase": normalized_phase,
            "category": "unexpected_error",
            "marker": marker,
            "counts": res_counts,
            "flags": res_flags,
            "hashed_run_id": safe_hash(run_id) if run_id else "",
            "details": {}
        }
        exit_code = 1

    if out_dir:
        out_path = Path(out_dir) / f"assert-{case}-{normalized_phase}.json"
        try:
            out_path.parent.mkdir(parents=True, exist_ok=True)
            out_path.write_text(json.dumps(output, indent=2, ensure_ascii=False), encoding='utf-8')
        except Exception:
            pass

    return output, exit_code


def main():
    parser = argparse.ArgumentParser(description="Phone control read-only assertion")
    parser.add_argument("serial", help="Target device serial")
    parser.add_argument("marker", help="Synthetic test marker (e.g. OOB_LIVE_GUI_...)")
    parser.add_argument("case", choices=["interaction", "collection", "experience", "cancel", "model"])
    parser.add_argument("--phase", default=None, help="Phase of test scenario")
    parser.add_argument("--out", default=None, help="Output directory for JSON result")
    parser.add_argument("--source-marker", default=None, help="Source marker for experience/replay/cancel recovery")
    parser.add_argument("--expected-experience-id", default=None, help="Expected experience ID")
    parser.add_argument("--expected-model", default=None, help="Expected resolved model name")
    parser.add_argument("--expected-form-first", default=None, help="Expected first form value")
    parser.add_argument("--expected-form-second", default=None, help="Expected second form value")
    parser.add_argument('--expected-runtime', default=None, help='本次正式归档身份JSON文件')

    args = parser.parse_args()
    expected_runtime = None
    if args.expected_runtime:
        try:
            expected_runtime = json.loads(Path(args.expected_runtime).read_text(encoding='utf-8'))
        except Exception:
            print(json.dumps({'passed': False, 'status': 'blocked', 'case': args.case, 'phase': normalize_phase(args.case, args.phase),
                              'category': 'expected_runtime_identity_invalid', 'marker': args.marker}))
            sys.exit(3)
    output, exit_code = run_assert_phone_control(
        serial=args.serial,
        marker=args.marker,
        case=args.case,
        phase=args.phase,
        source_marker=args.source_marker,
        expected_experience_id=args.expected_experience_id,
        expected_model=args.expected_model,
        expected_form_first=args.expected_form_first,
        expected_form_second=args.expected_form_second,
        expected_runtime=expected_runtime,
        out_dir=args.out
    )
    print(json.dumps(output, ensure_ascii=False))
    sys.exit(exit_code)


if __name__ == '__main__':
    main()
