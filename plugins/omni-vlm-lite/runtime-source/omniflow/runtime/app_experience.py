from __future__ import annotations

import collections
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any
import xml.etree.ElementTree as ET

from omniflow.core.model import Observation
from omniflow.runtime.semantic_grounding import iter_visible_nodes

SCHEMA_VERSION = "omniflow.app-experiences.v1"

RESOURCE_ID_REGEX = re.compile(r"^[A-Za-z_][A-Za-z0-9_.]*:id/[A-Za-z_][A-Za-z0-9_]*$")
PACKAGE_REGEX = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$")
IDENTIFIER_REGEX = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$")

MAX_STRING_LENGTH = 256
MAX_SLOTS = 500
MAX_VERSIONS_PER_SLOT = 8
MAX_PROCESSED_RUN_IDS = 200
MAX_HISTORY_RUN_IDS = 8

ALLOWED_ACTIONS = {"click", "long_press", "input_text"}

ATTRIBUTABLE_ERRORS = {
    "target_selector_missing",
    "target_selector_mismatch",
    "target_ambiguous",
    "action_target_missing",
    "action_target_ambiguous",
    "target_identity_mismatch",
    "no_progress_detected",
    "no_effect",
}

NEUTRAL_DONE_REASONS = {
    "cancelled",
    "waiting_input",
    "transport",
    "permission",
    "lockscreen",
}


def is_valid_resource_id(value: Any) -> bool:
    """校验 Android resource-id 格式，形如 package:id/name，长度 <= 256。"""
    if not isinstance(value, str):
        return False
    val = value.strip()
    return 0 < len(val) <= MAX_STRING_LENGTH and bool(RESOURCE_ID_REGEX.fullmatch(val))


def is_valid_package_name(value: Any) -> bool:
    """校验 Android 包名格式，由点分隔的标识符组成，长度 <= 256。"""
    if not isinstance(value, str):
        return False
    val = value.strip()
    return 0 < len(val) <= MAX_STRING_LENGTH and bool(PACKAGE_REGEX.fullmatch(val))


def is_valid_class_name(value: Any) -> bool:
    """校验 Android 类名标识符格式，长度 <= 256。"""
    if not isinstance(value, str):
        return False
    val = value.strip()
    return 0 < len(val) <= MAX_STRING_LENGTH and bool(IDENTIFIER_REGEX.fullmatch(val))


def is_valid_activity_name(value: Any) -> bool:
    """校验 Android Activity 格式，允许为空字符串或合法的标识符，长度 <= 256。"""
    if value is None or value == "":
        return True
    if not isinstance(value, str):
        return False
    val = value.strip()
    return len(val) <= MAX_STRING_LENGTH and bool(IDENTIFIER_REGEX.fullmatch(val))


def compute_experience_id(
    package_name: str,
    activity_name: str | None,
    rule: dict[str, Any],
) -> str:
    """按槽身份与规范化规则 JSON 的 SHA256 前24位计算经验 ID。"""
    pkg = str(package_name or "").strip()
    act = str(activity_name or "").strip()
    action = str(rule.get("action") or "").strip()
    target_res = str(rule.get("target_resource_id") or "").strip()
    target_cls = str(rule.get("target_class") or "").strip()
    after_act = str(rule.get("after_activity") or "").strip()

    clean_rule = {
        "action": action,
        "after_activity": after_act,
        "target_class": target_cls,
        "target_resource_id": target_res,
    }
    canonical_rule_json = json.dumps(
        clean_rule,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    slot_id = f"{pkg}|{act}|{target_res}|{action}"
    raw = f"{slot_id}|{canonical_rule_json}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def slot_key_of(item: dict[str, Any]) -> tuple[str, str, str, str]:
    """提取经验条目的槽身份 (package, activity, resource_id, action)。"""
    pkg = str(item.get("package_name") or "").strip()
    act = str(item.get("activity_name") or "").strip()
    rule = item.get("rule") or {}
    res = str(rule.get("target_resource_id") or "").strip()
    action = str(rule.get("action") or "").strip()
    return (pkg, act, res, action)


def _extract_step_result(step: dict[str, Any]) -> tuple[bool, str]:
    """从规范 step.result 读取执行成功布尔值与错误信息。"""
    res = step.get("result")
    if not isinstance(res, dict):
        return False, ""
    is_success = bool(res.get("success") is True)
    err_msg = res.get("error") or res.get("message") or ""
    return is_success, str(err_msg).strip()


def _extract_step_diagnostics(step: dict[str, Any]) -> dict[str, Any]:
    """从 step.metadata.action_result.diagnostics 提取宿主确证诊断。"""
    metadata = step.get("metadata")
    if isinstance(metadata, dict):
        act_res = metadata.get("action_result")
        if isinstance(act_res, dict):
            diag = act_res.get("diagnostics")
            if isinstance(diag, dict):
                return diag
    act_res = step.get("action_result")
    if isinstance(act_res, dict):
        diag = act_res.get("diagnostics")
        if isinstance(diag, dict):
            return diag
    return {}
def _match_step_baseline(
    item: dict[str, Any],
    step: dict[str, Any],
    states: dict[str, Observation | dict[str, Any]],
) -> tuple[bool, Any, Any]:
    """校验 step 是否与 item 的槽身份（动作、包名、Activity、基线可见唯一控件与类）精确匹配。"""
    rule = item.get("rule") or {}
    expected_action = rule.get("action")
    action_obj = step.get("action") or {}
    action_type = str(action_obj.get("action_type") or action_obj.get("tool") or "").strip()
    if action_type != expected_action:
        return False, None, None

    before_id = (
        (step.get("observation") or {}).get("auxiliaries", {}).get("state_id")
        or step.get("before_state_id")
        or (step.get("before") or {}).get("state_id")
    )
    after_id = (
        (step.get("next_observation") or {}).get("auxiliaries", {}).get("state_id")
        or step.get("after_state_id")
        or (step.get("after") or {}).get("state_id")
    )
    if not before_id:
        return False, None, None

    before_obs = states.get(before_id)
    if before_obs is None:
        return False, None, None

    after_obs = states.get(after_id) if after_id else None

    pkg = getattr(before_obs, "package_name", None)
    if not pkg and isinstance(before_obs, dict):
        pkg = before_obs.get("package_name")
    if not pkg:
        pkg = (step.get("observation") or {}).get("auxiliaries", {}).get("package_name")
    pkg = str(pkg or "").strip()
    if pkg != str(item.get("package_name") or "").strip():
        return False, None, None

    act = getattr(before_obs, "activity_name", None)
    if not act and isinstance(before_obs, dict):
        act = before_obs.get("activity_name")
    if not act:
        act = (step.get("observation") or {}).get("auxiliaries", {}).get("activity_name")
    act = str(act or "").strip()
    item_act = str(item.get("activity_name") or "").strip()
    if act != item_act:
        return False, None, None

    before_xml = getattr(before_obs, "xml", None)
    if before_xml is None and isinstance(before_obs, dict):
        before_xml = before_obs.get("xml")
    if not before_xml:
        return False, None, None

    try:
        root = ET.fromstring(before_xml)
    except Exception:
        return False, None, None

    item_res = str(rule.get("target_resource_id") or "").strip()
    item_class = str(rule.get("target_class") or "").strip()

    visible_nodes = list(iter_visible_nodes(root))
    matching_nodes = [
        n for n in visible_nodes
        if str(n.attrib.get("resource-id") or n.attrib.get("resourceId") or "").strip() == item_res
    ]
    if len(matching_nodes) != 1:
        return False, None, None

    target_node = matching_nodes[0]
    node_class = str(
        target_node.attrib.get("class")
        or target_node.attrib.get("className")
        or target_node.tag
        or ""
    ).strip()
    if node_class != item_class:
        return False, None, None

    return True, before_obs, after_obs

def _is_step_no_effect(before_obs: Any, after_obs: Any) -> bool:
    """检查前后观测证据是否表明页面无任何实质改变。"""
    if before_obs is None:
        return False
    if after_obs is None:
        return True

    before_xml = str(getattr(before_obs, "xml", None) or (before_obs.get("xml") if isinstance(before_obs, dict) else "") or "").strip()
    after_xml = str(getattr(after_obs, "xml", None) or (after_obs.get("xml") if isinstance(after_obs, dict) else "") or "").strip()
    before_pkg = str(getattr(before_obs, "package_name", None) or (before_obs.get("package_name") if isinstance(before_obs, dict) else "") or "").strip()
    after_pkg = str(getattr(after_obs, "package_name", None) or (after_obs.get("package_name") if isinstance(after_obs, dict) else "") or "").strip()
    before_act = str(getattr(before_obs, "activity_name", None) or (before_obs.get("activity_name") if isinstance(before_obs, dict) else "") or "").strip()
    after_act = str(getattr(after_obs, "activity_name", None) or (after_obs.get("activity_name") if isinstance(after_obs, dict) else "") or "").strip()

    if before_pkg != after_pkg or before_act != after_act or before_xml != after_xml:
        return False
    return True


def validate_store_data(data: Any) -> None:
    """严格校验经验存储数据结构，发现任何未知字段、破坏格式或非法逻辑均抛出异常。"""
    if not isinstance(data, dict):
        raise ValueError("app_experience_store_invalid")

    expected_top_keys = {"schema_version", "revision", "items", "processed_run_ids"}
    if set(data.keys()) != expected_top_keys:
        raise ValueError("app_experience_store_invalid")

    if data.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("app_experience_store_invalid")

    revision = data.get("revision")
    if isinstance(revision, bool) or not isinstance(revision, int) or revision < 0:
        raise ValueError("app_experience_store_invalid")

    processed = data.get("processed_run_ids")
    if not isinstance(processed, list) or len(processed) > MAX_PROCESSED_RUN_IDS:
        raise ValueError("app_experience_store_invalid")
    for r in processed:
        if not isinstance(r, str) or len(r) == 0 or len(r) > MAX_STRING_LENGTH:
            raise ValueError("app_experience_store_invalid")

    items = data.get("items")
    if not isinstance(items, list):
        raise ValueError("app_experience_store_invalid")

    expected_item_keys = {
        "id",
        "package_name",
        "activity_name",
        "status",
        "enabled",
        "source_run_id",
        "source_step_index",
        "rule",
        "successful_run_ids",
        "failed_run_ids",
        "consecutive_failures",
        "previous_verified_id",
    }
    expected_rule_keys = {
        "action",
        "target_resource_id",
        "target_class",
        "after_activity",
    }

    slots_count: dict[tuple[str, str, str, str], int] = collections.defaultdict(int)
    trial_per_slot: dict[tuple[str, str, str, str], int] = collections.defaultdict(int)

    for it in items:
        if not isinstance(it, dict):
            raise ValueError("app_experience_store_invalid")
        if set(it.keys()) != expected_item_keys:
            raise ValueError("app_experience_store_invalid")

        pkg = it.get("package_name")
        if not is_valid_package_name(pkg):
            raise ValueError("app_experience_store_invalid")

        act = it.get("activity_name")
        if not is_valid_activity_name(act):
            raise ValueError("app_experience_store_invalid")

        status = it.get("status")
        if status not in ("trial", "verified", "retired"):
            raise ValueError("app_experience_store_invalid")

        enabled = it.get("enabled")
        if not isinstance(enabled, bool):
            raise ValueError("app_experience_store_invalid")

        source_run = it.get("source_run_id")
        if not isinstance(source_run, str) or len(source_run) == 0 or len(source_run) > MAX_STRING_LENGTH:
            raise ValueError("app_experience_store_invalid")

        source_idx = it.get("source_step_index")
        if isinstance(source_idx, bool) or not isinstance(source_idx, int) or source_idx < 0:
            raise ValueError("app_experience_store_invalid")

        rule = it.get("rule")
        if not isinstance(rule, dict) or set(rule.keys()) != expected_rule_keys:
            raise ValueError("app_experience_store_invalid")

        action = rule.get("action")
        if action not in ALLOWED_ACTIONS:
            raise ValueError("app_experience_store_invalid")

        res_id = rule.get("target_resource_id")
        if not is_valid_resource_id(res_id):
            raise ValueError("app_experience_store_invalid")

        cls_name = rule.get("target_class")
        if not is_valid_class_name(cls_name):
            raise ValueError("app_experience_store_invalid")

        after_act = rule.get("after_activity")
        if not is_valid_activity_name(after_act):
            raise ValueError("app_experience_store_invalid")

        exp_id = it.get("id")
        if not isinstance(exp_id, str) or len(exp_id) != 24 or not all(c in "0123456789abcdef" for c in exp_id):
            raise ValueError("app_experience_store_invalid")
        if exp_id != compute_experience_id(pkg, act, rule):
            raise ValueError("app_experience_store_invalid")

        succ_runs = it.get("successful_run_ids")
        if not isinstance(succ_runs, list) or len(succ_runs) > MAX_HISTORY_RUN_IDS:
            raise ValueError("app_experience_store_invalid")
        for sr in succ_runs:
            if not isinstance(sr, str) or len(sr) == 0 or len(sr) > MAX_STRING_LENGTH:
                raise ValueError("app_experience_store_invalid")

        fail_runs = it.get("failed_run_ids")
        if not isinstance(fail_runs, list) or len(fail_runs) > MAX_HISTORY_RUN_IDS:
            raise ValueError("app_experience_store_invalid")
        for fr in fail_runs:
            if not isinstance(fr, str) or len(fr) == 0 or len(fr) > MAX_STRING_LENGTH:
                raise ValueError("app_experience_store_invalid")

        consec = it.get("consecutive_failures")
        if isinstance(consec, bool) or not isinstance(consec, int) or consec < 0:
            raise ValueError("app_experience_store_invalid")

        prev_id = it.get("previous_verified_id")
        if prev_id is not None:
            if not isinstance(prev_id, str) or len(prev_id) != 24 or not all(c in "0123456789abcdef" for c in prev_id):
                raise ValueError("app_experience_store_invalid")

        slot = slot_key_of(it)
        slots_count[slot] += 1
        if slots_count[slot] > MAX_VERSIONS_PER_SLOT:
            raise ValueError("app_experience_store_invalid")

        if status == "trial":
            trial_per_slot[slot] += 1
            if trial_per_slot[slot] > 1:
                raise ValueError("app_experience_store_invalid")

    if len(slots_count) > MAX_SLOTS:
        raise ValueError("app_experience_store_invalid")


def derive_candidates(
    run_log: dict[str, Any],
    states: dict[str, Observation | dict[str, Any]],
) -> list[dict[str, Any]]:
    """从封存的运行日志和状态提取已验证的候选经验规则。"""
    if not isinstance(run_log, dict):
        return []

    # 必须为已封存且具有正数 finished_at_ms 的运行
    finished_at_ms = run_log.get("finished_at_ms")
    if (
        finished_at_ms is None
        or isinstance(finished_at_ms, bool)
        or not isinstance(finished_at_ms, (int, float))
        or finished_at_ms <= 0
    ):
        return []

    if run_log.get("success") is not True:
        return []

    status = str(run_log.get("status") or "")
    if status in ("cancelled", "failed", "interrupted"):
        return []

    done_reason = str(
        run_log.get("done_reason")
        or (run_log.get("diagnostics") or {}).get("done_reason")
        or ""
    )
    if any(nr in done_reason for nr in NEUTRAL_DONE_REASONS):
        return []

    ev = (
        (run_log.get("diagnostics") or {}).get("finished_evidence")
        or run_log.get("finished_evidence")
    )
    if not isinstance(ev, dict) or ev.get("status") != "matched":
        return []

    run_id = str(run_log.get("run_id") or run_log.get("id") or "").strip()
    if not run_id:
        return []

    steps = run_log.get("steps") or []
    if not isinstance(steps, list):
        return []

    candidates: list[dict[str, Any]] = []
    seen_ids: set[str] = set()

    for step_idx, step in enumerate(steps):
        if not isinstance(step, dict):
            continue

        step_success, _ = _extract_step_result(step)
        if not step_success:
            continue

        action_obj = step.get("action") or {}
        action_type = str(action_obj.get("action_type") or action_obj.get("tool") or "").strip()
        if action_type not in ALLOWED_ACTIONS:
            continue

        before_id = (
            (step.get("observation") or {}).get("auxiliaries", {}).get("state_id")
            or step.get("before_state_id")
            or (step.get("before") or {}).get("state_id")
        )
        after_id = (
            (step.get("next_observation") or {}).get("auxiliaries", {}).get("state_id")
            or step.get("after_state_id")
            or (step.get("after") or {}).get("state_id")
        )
        if not before_id or not after_id:
            continue

        before_obs = states.get(before_id)
        after_obs = states.get(after_id)
        if before_obs is None or after_obs is None:
            continue

        diag = _extract_step_diagnostics(step)
        guard_status = diag.get("guard_status")
        if guard_status != "target_confirmed":
            continue

        host_res_id = str(diag.get("target_resource_id") or "").strip()
        host_class = str(diag.get("target_class") or "").strip()
        if not is_valid_resource_id(host_res_id) or not is_valid_class_name(host_class):
            continue

        metadata = step.get("metadata") or {}
        transfer = metadata.get("transfer") or {}
        sg = transfer.get("semantic_grounding") or metadata.get("semantic_grounding") or {}
        if sg.get("status") != "resolved":
            continue
        sg_res_id = str(sg.get("node_resource_id") or sg.get("target_resource_id") or "").strip()
        if sg_res_id and sg_res_id != host_res_id:
            continue

        before_xml = getattr(before_obs, "xml", None)
        if before_xml is None and isinstance(before_obs, dict):
            before_xml = before_obs.get("xml")
        if not before_xml:
            continue
        try:
            root = ET.fromstring(before_xml)
        except Exception:
            continue

        visible_nodes = list(iter_visible_nodes(root))
        matching_nodes = [
            n for n in visible_nodes
            if str(n.attrib.get("resource-id") or n.attrib.get("resourceId") or "").strip() == host_res_id
        ]
        if len(matching_nodes) != 1:
            continue

        target_node = matching_nodes[0]
        node_class = str(
            target_node.attrib.get("class")
            or target_node.attrib.get("className")
            or target_node.tag
            or ""
        ).strip()
        if node_class != host_class:
            continue

        pkg = getattr(before_obs, "package_name", None)
        if not pkg and isinstance(before_obs, dict):
            pkg = before_obs.get("package_name")
        if not pkg:
            pkg = (step.get("observation") or {}).get("auxiliaries", {}).get("package_name")
        pkg = str(pkg or "").strip()
        if not is_valid_package_name(pkg):
            continue

        act = getattr(before_obs, "activity_name", None)
        if not act and isinstance(before_obs, dict):
            act = before_obs.get("activity_name")
        if not act:
            act = (step.get("observation") or {}).get("auxiliaries", {}).get("activity_name")
        act = str(act or "").strip()
        if act and not is_valid_activity_name(act):
            continue

        after_act = getattr(after_obs, "activity_name", None)
        if not after_act and isinstance(after_obs, dict):
            after_act = after_obs.get("activity_name")
        if not after_act:
            after_act = (step.get("next_observation") or {}).get("auxiliaries", {}).get("activity_name")
        after_act = str(after_act or "").strip()
        if after_act and not is_valid_activity_name(after_act):
            continue

        after_pkg = getattr(after_obs, "package_name", None)
        if not after_pkg and isinstance(after_obs, dict):
            after_pkg = after_obs.get("package_name")
        if not after_pkg:
            after_pkg = (step.get("next_observation") or {}).get("auxiliaries", {}).get("package_name")
        after_pkg = str(after_pkg or "").strip()

        after_xml = getattr(after_obs, "xml", None)
        if after_xml is None and isinstance(after_obs, dict):
            after_xml = after_obs.get("xml")

        state_changed = False
        if after_pkg != pkg:
            state_changed = True
        elif after_act and after_act != act:
            state_changed = True
        elif before_xml and after_xml and before_xml.strip() != after_xml.strip():
            state_changed = True

        if not state_changed:
            continue

        rule_after_act = after_act if after_act != act else ""
        rule = {
            "action": action_type,
            "target_resource_id": host_res_id,
            "target_class": host_class,
            "after_activity": rule_after_act,
        }

        cand_id = compute_experience_id(pkg, act, rule)
        if cand_id in seen_ids:
            continue
        seen_ids.add(cand_id)

        step_index = step.get("step_index")
        if step_index is None or isinstance(step_index, bool) or not isinstance(step_index, int) or step_index < 0:
            continue
        candidate = {
            "id": cand_id,
            "package_name": pkg,
            "activity_name": act,
            "status": "trial",
            "enabled": True,
            "source_run_id": run_id,
            "source_step_index": step_index,
            "rule": rule,
            "successful_run_ids": [],
            "failed_run_ids": [],
            "consecutive_failures": 0,
            "previous_verified_id": None,
        }
        candidates.append(candidate)

    return candidates


class AppExperienceStore:
    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)
        self.schema_version = SCHEMA_VERSION
        self.revision = 0
        self.items: list[dict[str, Any]] = []
        self.processed_run_ids: list[str] = []
        self.reload()

    def reload(self) -> None:
        """从磁盘加载经验数据，若文件损坏或校验失败则抛出异常且绝不覆写。"""
        if not self.path.is_file():
            self.schema_version = SCHEMA_VERSION
            self.revision = 0
            self.items = []
            self.processed_run_ids = []
            return

        try:
            with open(self.path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            raise ValueError("app_experience_store_invalid") from e

        validate_store_data(data)

        self.schema_version = data["schema_version"]
        self.revision = data["revision"]
        self.items = data["items"]
        self.processed_run_ids = data["processed_run_ids"]

    def _save(self) -> None:
        """原子写入当前状态至磁盘文件。"""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "schema_version": self.schema_version,
            "revision": self.revision,
            "items": self.items,
            "processed_run_ids": self.processed_run_ids,
        }
        validate_store_data(data)

        temp_dir = self.path.parent
        fd, tmp_file = tempfile.mkstemp(
            prefix="app_experiences_",
            suffix=".tmp",
            dir=str(temp_dir),
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            os.replace(tmp_file, str(self.path))
        except Exception:
            if os.path.exists(tmp_file):
                try:
                    os.remove(tmp_file)
                except OSError:
                    pass
            raise

    def select(
        self,
        package_name: str,
        activity_name: str | None,
        visible_resource_ids: list[str],
    ) -> list[dict[str, Any]]:
        """为当前页面选取最多5条经验：同槽有启用trial时遮蔽旧verified，余按verified优先及id排序。"""
        act_filter = str(activity_name or "").strip()
        id_counts = collections.Counter(visible_resource_ids or [])
        unique_ids = {rid for rid, cnt in id_counts.items() if cnt == 1}

        slots: dict[tuple[str, str, str, str], list[dict[str, Any]]] = collections.defaultdict(list)
        for item in self.items:
            if item.get("package_name") != package_name:
                continue
            item_act = str(item.get("activity_name") or "").strip()
            if item_act != act_filter:
                continue
            slot = slot_key_of(item)
            slots[slot].append(item)

        selected: list[dict[str, Any]] = []
        for slot, slot_items in slots.items():
            enabled_trial: dict[str, Any] | None = None
            verified_items: list[dict[str, Any]] = []

            for it in slot_items:
                status = it.get("status")
                enabled = it.get("enabled", True)
                if status == "trial" and enabled:
                    enabled_trial = it
                elif status == "verified" and enabled:
                    verified_items.append(it)

            if enabled_trial is not None:
                # 启用的 trial 遮蔽同槽 verified，即使该 trial 目标当前不可见也不注入旧版
                target_res = (enabled_trial.get("rule") or {}).get("target_resource_id")
                if target_res in unique_ids:
                    selected.append(enabled_trial)
            else:
                for v_it in verified_items:
                    target_res = (v_it.get("rule") or {}).get("target_resource_id")
                    if target_res in unique_ids:
                        selected.append(v_it)

        selected.sort(key=lambda it: (0 if it.get("status") == "verified" else 1, it.get("id", "")))
        return selected[:5]

    def list_items(
        self,
        limit: int = 20,
        offset: int = 0,
        package_name: str | None = None,
    ) -> dict[str, Any]:
        """分页获取经验条目，支持按包名过滤。"""
        try:
            self.reload()
        except ValueError:
            return {
                "success": False,
                "error_code": "app_experience_store_invalid",
                "error": "app_experience_store_invalid",
                "revision": -1,
                "items": [],
                "total": 0,
                "next_offset": None,
            }

        limit = max(1, min(100, int(limit)))
        offset = max(0, int(offset))

        if package_name:
            filtered = [it for it in self.items if it.get("package_name") == package_name]
        else:
            filtered = list(self.items)

        total = len(filtered)
        sliced = filtered[offset : offset + limit]
        next_offset = offset + limit if offset + limit < total else None

        return {
            "success": True,
            "revision": self.revision,
            "items": sliced,
            "total": total,
            "next_offset": next_offset,
        }

    list = list_items

    def set_enabled(
        self,
        experience_id: str,
        enabled: bool,
        expected_revision: int | None = None,
    ) -> dict[str, Any]:
        """启停经验；对已退役版本重新启用时原子切换为唯一trial并清空计数。"""
        try:
            self.reload()
        except ValueError:
            return {
                "success": False,
                "error_code": "app_experience_store_invalid",
                "error": "app_experience_store_invalid",
                "revision": -1,
            }

        if expected_revision is not None and self.revision != expected_revision:
            return {
                "success": False,
                "error_code": "app_experience_revision_conflict",
                "error": "app_experience_revision_conflict",
                "revision": self.revision,
            }

        target_item = None
        for it in self.items:
            if it.get("id") == experience_id:
                target_item = it
                break

        if target_item is None:
            return {
                "success": False,
                "error_code": "app_experience_not_found",
                "error": "app_experience_not_found",
                "revision": self.revision,
            }

        if not enabled:
            target_item["enabled"] = False
            self.revision += 1
            self._save()
            return {"success": True, "revision": self.revision, "item": target_item}

        cur_status = target_item.get("status")
        if cur_status in ("trial", "verified"):
            target_item["enabled"] = True
            self.revision += 1
            self._save()
            return {"success": True, "revision": self.revision, "item": target_item}

        if cur_status == "retired":
            slot = slot_key_of(target_item)
            # 必须先清除历史旧引用，避免因槽内无当前已验证版本而残留已退役前任
            target_item["previous_verified_id"] = None
            for other in self.items:
                if other.get("id") == target_item.get("id"):
                    continue
                if slot_key_of(other) == slot:
                    if other.get("status") == "trial":
                        other["status"] = "retired"
                    elif other.get("status") == "verified":
                        # 仅关联当时仍处于 enabled 且 status 为 verified 的真实前任
                        if other.get("enabled", True):
                            target_item["previous_verified_id"] = other.get("id")

            target_item["status"] = "trial"
            target_item["enabled"] = True
            target_item["successful_run_ids"] = []
            target_item["failed_run_ids"] = []
            target_item["consecutive_failures"] = 0
            self.revision += 1
            self._save()
            return {"success": True, "revision": self.revision, "item": target_item}
        return {"success": False, "error_code": "invalid_status", "revision": self.revision}

    def update_from_run(
        self,
        run_log: dict[str, Any],
        states: dict[str, Observation | dict[str, Any]],
    ) -> dict[str, Any]:
        """处理运行反馈并原子更新候选；先重新加载最新版本防并发冲突。"""
        try:
            self.reload()
        except ValueError:
            return {
                "success": False,
                "error_code": "app_experience_store_invalid",
                "error": "app_experience_store_invalid",
                "revision": -1,
            }

        finished_at_ms = run_log.get("finished_at_ms")
        if (
            finished_at_ms is None
            or isinstance(finished_at_ms, bool)
            or not isinstance(finished_at_ms, (int, float))
            or finished_at_ms <= 0
        ):
            return {
                "success": False,
                "error_code": "run_log_not_finished",
                "error": "run_log_not_finished",
                "revision": self.revision,
            }

        run_id = str(run_log.get("run_id") or run_log.get("id") or "").strip()
        if not run_id:
            return {"success": False, "error_code": "invalid_run_log", "revision": self.revision}

        if run_id in self.processed_run_ids:
            return {
                "success": True,
                "revision": self.revision,
                "idempotent": True,
                "processed": False,
            }

        status = str(run_log.get("status") or "")
        diagnostics = run_log.get("diagnostics") if isinstance(run_log.get("diagnostics"), dict) else {}
        done_reason = str(
            run_log.get("done_reason")
            or diagnostics.get("done_reason")
            or ""
        )
        planner_diag = (
            diagnostics.get("planner")
            or run_log.get("planner_diagnostics")
            or {}
        )
        if not isinstance(planner_diag, dict):
            planner_diag = {}
        planner_failure_kind = str(planner_diag.get("failure_kind") or "").strip()
        err_code = str(run_log.get("error_code") or diagnostics.get("error_code") or "").strip()
        err_msg = str(run_log.get("error_message") or run_log.get("error") or "").strip()

        is_neutral_run = (
            status in ("cancelled", "interrupted")
            or any(nr in done_reason for nr in NEUTRAL_DONE_REASONS)
            or planner_failure_kind == "planner_failed"
            or err_code == "VLM_PLANNER_FAILED"
            or err_msg.startswith("vlm_planner_failed:")
        )

        run_is_no_progress = (
            run_log.get("success") is not True
            and (
                done_reason in ("no_progress_detected", "NO_PROGRESS_DETECTED")
                or str(diagnostics.get("done_reason") or "") == "no_progress_detected"
                or err_code == "NO_PROGRESS_DETECTED"
                or err_msg == "no_progress_detected"
            )
        )
        failure_step_indices = planner_diag.get("app_experience_failure_step_indexes")

        capacity_reached = False
        items_by_id = {it["id"]: it for it in self.items}

        if not is_neutral_run:
            # 1. 对本 run 中实际使用的经验进行成败归因反馈
            exp_steps_map: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
            for step in run_log.get("steps") or []:
                if not isinstance(step, dict):
                    continue
                metadata = step.get("metadata") or {}
                exp_ids = metadata.get("app_experience_ids") or step.get("app_experience_ids") or []
                if isinstance(exp_ids, list):
                    for eid in exp_ids:
                        exp_steps_map[str(eid)].append(step)

            for exp_id, steps in exp_steps_map.items():
                item = items_by_id.get(exp_id)
                if not item:
                    continue

                rule = item.get("rule") or {}
                item_res = rule.get("target_resource_id")
                item_class = rule.get("target_class")
                item_after_act = rule.get("after_activity")

                has_failure = False
                has_success = False

                for step in steps:
                    matched, before_obs, after_obs = _match_step_baseline(item, step, states)
                    if not matched:
                        continue

                    step_success, step_err = _extract_step_result(step)
                    diag = _extract_step_diagnostics(step)
                    diag_reason = str(diag.get("reason") or diag.get("error") or "").strip()
                    diag_guard_status = str(diag.get("guard_status") or "").strip()

                    # 失败判定：步骤未成功且归因于选择器缺失/歧义/身份不符/无进展
                    # 失败判定：
                    # 1) 步骤未成功且归因于选择器缺失/歧义/身份不符等
                    if not step_success and (
                        step_err in ATTRIBUTABLE_ERRORS
                        or diag_reason in ATTRIBUTABLE_ERRORS
                        or diag_guard_status in ATTRIBUTABLE_ERRORS
                    ):
                        has_failure = True
                        break

                    # 2) 步骤派发成功但证据表明无效果，且后续同屏停滞最终导致 no_progress_detected 终止
                    # 2) 步骤派发成功但证据表明无效果，且由 engine 明确记录于最后停滞周期的实际使用步骤
                    if (
                        step_success
                        and run_is_no_progress
                        and isinstance(failure_step_indices, list)
                    ):
                        step_idx = step.get("step_index")
                        if (
                            step_idx is not None
                            and step_idx in failure_step_indices
                            and _is_step_no_effect(before_obs, after_obs)
                        ):
                            has_failure = True
                            break
                    run_success = run_log.get("success") is True
                    ev = (
                        (run_log.get("diagnostics") or {}).get("finished_evidence")
                        or run_log.get("finished_evidence")
                        or {}
                    )
                    ev_matched = (ev.get("status") == "matched")

                    if (
                        step_success
                        and run_success
                        and ev_matched
                        and run_id != item.get("source_run_id")
                        and diag.get("guard_status") == "target_confirmed"
                        and diag.get("target_resource_id") == item_res
                        and diag.get("target_class") == item_class
                    ):
                        metadata = step.get("metadata") or {}
                        transfer = metadata.get("transfer") or {}
                        sg = transfer.get("semantic_grounding") or metadata.get("semantic_grounding") or {}
                        if sg.get("status") == "resolved" and after_obs:
                            after_act = getattr(after_obs, "activity_name", None)
                            if not after_act and isinstance(after_obs, dict):
                                after_act = after_obs.get("activity_name")
                            if not after_act:
                                after_act = (step.get("next_observation") or {}).get("auxiliaries", {}).get("activity_name")
                            after_act = str(after_act or "").strip()

                            if item_after_act:
                                if after_act == item_after_act:
                                    has_success = True
                            else:
                                before_xml_s = str(getattr(before_obs, "xml", "") or "").strip()
                                after_xml_s = str(getattr(after_obs, "xml", "") or "").strip()
                                before_pkg = getattr(before_obs, "package_name", "")
                                after_pkg = getattr(after_obs, "package_name", "")
                                if (
                                    after_act != (getattr(before_obs, "activity_name", "") or "")
                                    or after_pkg != before_pkg
                                    or before_xml_s != after_xml_s
                                ):
                                    has_success = True

                if has_failure:
                    outcome = "failure"
                elif has_success:
                    outcome = "success"
                else:
                    outcome = "neutral"

                if outcome == "failure":
                    if run_id not in item["failed_run_ids"]:
                        item["failed_run_ids"].append(run_id)
                        item["failed_run_ids"] = item["failed_run_ids"][-MAX_HISTORY_RUN_IDS:]
                    item["consecutive_failures"] += 1
                    if item["status"] == "trial":
                        if item["consecutive_failures"] >= 2:
                            item["status"] = "retired"
                            prev_id = item.get("previous_verified_id")
                            if prev_id and prev_id in items_by_id:
                                prev_item = items_by_id[prev_id]
                                if prev_item.get("enabled", True):
                                    prev_item["status"] = "verified"
                    elif item["status"] == "verified":
                        if item["consecutive_failures"] >= 2:
                            item["status"] = "retired"
                elif outcome == "success":
                    item["consecutive_failures"] = 0
                    if run_id not in item["successful_run_ids"]:
                        item["successful_run_ids"].append(run_id)
                        item["successful_run_ids"] = item["successful_run_ids"][-MAX_HISTORY_RUN_IDS:]
                    if item["status"] == "trial":
                        if len(item["successful_run_ids"]) >= 2:
                            item["status"] = "verified"
                            prev_id = item.get("previous_verified_id")
                            if prev_id and prev_id in items_by_id:
                                items_by_id[prev_id]["status"] = "retired"

            # 2. 从本运行提取新经验候选
            new_cands = derive_candidates(run_log, states)
            for cand in new_cands:
                cand_id = cand["id"]
                cand_slot = slot_key_of(cand)

                if cand_id in items_by_id:
                    continue

                slot_has_trial = any(
                    slot_key_of(it) == cand_slot and it.get("status") == "trial"
                    for it in self.items
                )
                if slot_has_trial:
                    continue

                all_slots = {slot_key_of(it) for it in self.items}
                slot_items = [it for it in self.items if slot_key_of(it) == cand_slot]
                if cand_slot not in all_slots and len(all_slots) >= MAX_SLOTS:
                    capacity_reached = True
                    continue
                if len(slot_items) >= MAX_VERSIONS_PER_SLOT:
                    capacity_reached = True
                    continue

                for it in self.items:
                    if slot_key_of(it) == cand_slot and it.get("status") == "verified":
                        cand["previous_verified_id"] = it.get("id")
                        break

                self.items.append(cand)
                items_by_id[cand_id] = cand

        self.processed_run_ids.append(run_id)
        self.processed_run_ids = self.processed_run_ids[-MAX_PROCESSED_RUN_IDS:]
        self.revision += 1
        self._save()

        res: dict[str, Any] = {
            "success": True,
            "revision": self.revision,
            "processed": True,
        }
        if capacity_reached:
            res["warning"] = "app_experience_capacity_reached"
            res["capacity_reached"] = True
        return res
