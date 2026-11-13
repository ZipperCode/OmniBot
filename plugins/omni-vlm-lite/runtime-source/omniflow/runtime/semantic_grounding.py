from __future__ import annotations

from dataclasses import dataclass
import math
import re
import unicodedata
from typing import Any
import xml.etree.ElementTree as ET

from omniflow.core.model import Action, Observation

_BOUNDS = re.compile(
    r"^\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\]"
    r"\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\]$"
)
_SUPPORTED_TOOLS = {"click", "long_press", "input_text"}


@dataclass(frozen=True)
class SemanticGroundingResult:
    action: Action
    detail: dict[str, object] | None = None


def resolve_semantic_action(
    action: Action,
    observation: Observation,
) -> SemanticGroundingResult:
    target = str(action.args.get("target_description") or "").strip()
    explicit_node_id = str(action.args.get("node_id") or "").strip() or None
    explicit_resource_id = str(action.args.get("node_resource_id") or "").strip() or None
    has_selector = explicit_node_id is not None or explicit_resource_id is not None
    if action.tool not in _SUPPORTED_TOOLS or (not target and not has_selector):
        return SemanticGroundingResult(action)

    raw_extra = observation.extra if isinstance(observation.extra, dict) else {}
    display = raw_extra.get("display")
    if not isinstance(display, dict) and isinstance(raw_extra.get("auxiliaries"), dict):
        display = raw_extra["auxiliaries"].get("display")
    if not isinstance(display, dict):
        return _blocked(action, target, "display_missing", message="屏幕尺寸信息缺失")
    try:
        width = float(display.get("width") or 0)
        height = float(display.get("height") or 0)
    except (TypeError, ValueError):
        return _blocked(action, target, "display_invalid", message="屏幕尺寸参数非法")
    if width <= 0 or height <= 0:
        return _blocked(action, target, "display_invalid", message="屏幕尺寸非正数")
    # 1. 尝试解析无障碍 XML；空树或异常时不硬性阻断，保留 OCR 与视觉回退路径
    root: ET.Element | None = None
    xml_text = str(observation.xml or "").strip()
    xml_diagnostic: str | None = None
    if not xml_text:
        xml_diagnostic = "无障碍XML为空，进入OCR与视觉路径"
    else:
        try:
            root = ET.fromstring(xml_text)
        except ET.ParseError as err:
            xml_diagnostic = f"无障碍XML解析异常({err})，进入OCR与视觉路径"

    normalized_target = _normalize(target)
    parent_by_child = (
        {child: parent for parent in root.iter() for child in parent}
        if root is not None
        else {}
    )

    # 2. 显式 selector 优先处理：node_id 在本帧唯一；同时给 resource_id 必须同一节点符合；只给 resource_id 时必须唯一。
    # 缺失返回 target_selector_missing，冲突返回 target_selector_mismatch，多候选沿 target_ambiguous，均不回退坐标。
    if has_selector:
        if root is None:
            return _blocked(action, target, "target_selector_missing", message="无障碍XML不可用，显式选择器未匹配")
        matched_element: ET.Element | None = None
        if explicit_node_id is not None:
            id_matches = [
                elem for elem in root.iter()
                if str(elem.attrib.get("id") or "").strip() == explicit_node_id
            ]
            if len(id_matches) == 0:
                return _blocked(action, target, "target_selector_missing", message="未找到指定node_id")
            if len(id_matches) > 1:
                return _blocked(action, target, "target_ambiguous", candidate_count=len(id_matches), message="node_id不唯一")
            candidate = id_matches[0]
            if explicit_resource_id is not None:
                cand_res = str(candidate.attrib.get("resource-id") or candidate.attrib.get("resourceId") or "").strip()
                if cand_res != explicit_resource_id:
                    return _blocked(action, target, "target_selector_mismatch", message="node_id与resource_id冲突")
            matched_element = candidate
        else:
            res_matches = [
                elem for elem in root.iter()
                if str(elem.attrib.get("resource-id") or elem.attrib.get("resourceId") or "").strip() == explicit_resource_id
            ]
            if len(res_matches) == 0:
                return _blocked(action, target, "target_selector_missing", message="未找到指定resource_id")
            if len(res_matches) > 1:
                visible_matches = [
                    elem for elem in res_matches
                    if str(elem.attrib.get("visible") or "true").strip().casefold() not in {"0", "false"}
                    and str(elem.attrib.get("displayed") or "true").strip().casefold() not in {"0", "false"}
                    and str(elem.attrib.get("enabled") or "true").strip().casefold() not in {"0", "false"}
                ]
                if len(visible_matches) == 1:
                    matched_element = visible_matches[0]
                else:
                    return _blocked(action, target, "target_ambiguous", candidate_count=len(visible_matches or res_matches), message="resource_id不唯一")
            else:
                matched_element = res_matches[0]

        actionable = _nearest_actionable(
            matched_element,
            parent_by_child,
            tool=action.tool,
        )
        if actionable is None:
            return _blocked(action, target, "target_selector_missing", message="选择器节点及其祖先不可操作")
        bounds = _parse_bounds(actionable.attrib.get("bounds"))
        if bounds is None:
            return _blocked(action, target, "target_selector_missing", message="选择器节点无有效区域坐标")

        center_x = (bounds[0] + bounds[2]) / 2.0
        center_y = (bounds[1] + bounds[3]) / 2.0

        anc_node_id = str(actionable.attrib.get("id") or matched_element.attrib.get("id") or "").strip() or None
        anc_resource_id = str(
            actionable.attrib.get("resource-id")
            or actionable.attrib.get("resourceId")
            or matched_element.attrib.get("resource-id")
            or matched_element.attrib.get("resourceId")
            or ""
        ).strip() or None

        args = {k: v for k, v in action.args.items() if k != "target_description"}
        args["x"] = center_x / width * 1000.0
        args["y"] = center_y / height * 1000.0
        if anc_node_id:
            args["node_id"] = anc_node_id
        if anc_resource_id:
            args["node_resource_id"] = anc_resource_id

        return SemanticGroundingResult(
            Action(action.tool, args),
            {
                "schema_version": "omniflow.semantic-grounding.v1",
                "status": "resolved",
                "target_description": target,
                "match_source": "explicit_selector",
                "bounds": list(bounds),
                "node_id": anc_node_id,
                "node_resource_id": anc_resource_id,
            },
        )


    # 2. 在无障碍树中进行语义匹配（权威遮挡核验由宿主输入锁执行）
    if root is not None:
        parent_by_child = {
            child: parent for parent in root.iter() for child in parent
        }
        matches: dict[tuple[float, float, float, float], tuple[ET.Element, ET.Element]] = {}
        for element in root.iter():
            labels = {
                _normalize(element.attrib.get(name, ""))
                for name in ("text", "content-desc", "contentDescription")
            }
            if normalized_target not in labels:
                continue
            actionable = _nearest_actionable(
                element,
                parent_by_child,
                tool=action.tool,
            )
            if actionable is None:
                continue
            bounds = _parse_bounds(actionable.attrib.get("bounds"))
            if bounds is None:
                continue
            matches[bounds] = (actionable, element)

        # 语义 target 多匹配：blocked，不回退盲猜坐标（同名消歧拒绝）
        if len(matches) > 1:
            return _blocked(
                action,
                target,
                "target_ambiguous",
                candidate_count=len(matches),
                message=f"无障碍树中匹配到 {len(matches)} 个同名候选控件，拒绝盲猜",
            )

        # XML 唯一匹配成功：绑定 node_id 与资源属性
        if len(matches) == 1:
            bounds, (actionable, element) = next(iter(matches.items()))
            center_x = (bounds[0] + bounds[2]) / 2.0
            center_y = (bounds[1] + bounds[3]) / 2.0
            args = {
                **action.args,
                "x": center_x / width * 1000.0,
                "y": center_y / height * 1000.0,
            }
            node_id = str(actionable.attrib.get("id") or "").strip()
            resource_id = str(actionable.attrib.get("resource-id") or actionable.attrib.get("resourceId") or "").strip()
            if node_id:
                args["node_id"] = node_id
            if resource_id:
                args["node_resource_id"] = resource_id
            return SemanticGroundingResult(
                Action(action.tool, args),
                {
                    "schema_version": "omniflow.semantic-grounding.v1",
                    "status": "resolved",
                    "target_description": target,
                    "match_source": "accessibility_text_or_content_description",
                    "bounds": list(bounds),
                    "node_id": node_id or None,
                    "node_resource_id": resource_id or None,
                },
            )

    # 3. XML 无匹配：检查端侧 OCR 文字候选
    extra = observation.extra if isinstance(observation.extra, dict) else {}
    ocr_data = extra.get("ocr")
    ocr_matches: list[dict[str, Any]] = []

    # OCR 校验：status 必须为 ready
    if isinstance(ocr_data, dict) and str(ocr_data.get("status") or "").strip().lower() == "ready":
        raw_items = ocr_data.get("items")
        if isinstance(raw_items, list):
            for item in raw_items:
                if not isinstance(item, dict):
                    continue
                # source 必须为 ocr
                if str(item.get("source") or "").strip().lower() != "ocr":
                    continue
                conf = item.get("confidence")
                # 置信度必须为有效有限数字且达标 (>=0.5)
                if not _is_finite_number(conf) or float(conf) < 0.5:
                    continue
                raw_text = str(item.get("text") or "").strip()
                if _normalize(raw_text) != normalized_target:
                    continue
                parsed_b = _parse_viewport_bounds(item.get("bounds"), width, height)
                if parsed_b is None:
                    continue
                ocr_matches.append({**item, "_parsed_bounds": parsed_b})

    if ocr_matches:
        # OCR 仅供 click 与 long_press 坐标定位，input_text 不能将文字当输入框
        if action.tool == "input_text":
            return _blocked(
                action,
                target,
                "ocr_input_text_unsupported",
                candidate_count=len(ocr_matches),
                message="OCR识别文字无法作为可编辑输入控件",
            )

        # OCR 同名多框不猜，必须 blocked
        if len(ocr_matches) > 1:
            return _blocked(
                action,
                target,
                "ocr_target_ambiguous",
                candidate_count=len(ocr_matches),
                message=f"OCR识别到 {len(ocr_matches)} 个同名候选文本框，拒绝盲猜",
            )

        # OCR 唯一匹配：解析中心坐标，严格无控件属性（无 node_id / node_resource_id）
        ocr_item = ocr_matches[0]
        ocr_b = ocr_item["_parsed_bounds"]
        center_x = (ocr_b[0] + ocr_b[2]) / 2.0
        center_y = (ocr_b[1] + ocr_b[3]) / 2.0
        args = {k: v for k, v in action.args.items() if k not in ("node_id", "node_resource_id")}
        args["x"] = center_x / width * 1000.0
        args["y"] = center_y / height * 1000.0
        detail: dict[str, object] = {
            "schema_version": "omniflow.semantic-grounding.v1",
            "status": "resolved",
            "target_description": target,
            "match_source": "ocr",
            "bounds": list(ocr_b),
            "confidence": float(ocr_item["confidence"]),
        }
        if xml_diagnostic:
            detail["diagnostic"] = xml_diagnostic
        return SemanticGroundingResult(Action(action.tool, args), detail)

    # 4. XML 与 OCR 均未匹配：target_missing
    # 若模型提供了明确的图像全屏归一化坐标，仍允许执行，但提示可 inspect OCR
    x_val = action.args.get("x")
    y_val = action.args.get("y")
    if _is_valid_coord(x_val) and _is_valid_coord(y_val):
        detail = {
            "schema_version": "omniflow.semantic-grounding.v1",
            "status": "fallback",
            "target_description": target,
            "reason": "target_missing",
            "candidate_count": 0,
            "hint": "target_missing_inspect_ocr_available",
            "message": "无障碍树与OCR未匹配到目标文字，使用模型坐标继续执行；可调用 get_state(ocr=True) 重新检查文字",
        }
        if xml_diagnostic:
            detail["diagnostic"] = xml_diagnostic
        return SemanticGroundingResult(action, detail)

    # 既无匹配又无有效坐标：明确 blocked
    return _blocked(
        action,
        target,
        "target_missing_no_coordinates",
        candidate_count=0,
        message="目标文字未匹配且未提供有效坐标，拒绝执行",
    )


def _is_finite_number(value: Any) -> bool:
    return (
        not isinstance(value, bool)
        and isinstance(value, (int, float))
        and math.isfinite(float(value))
    )


def _is_valid_coord(value: Any) -> bool:
    if not _is_finite_number(value):
        return False
    val = float(value)
    return 0.0 <= val <= 1000.0


def semantic_target_at_point(
    xml: str,
    x: float,
    y: float,
) -> str:
    try:
        root = ET.fromstring(str(xml or ""))
    except ET.ParseError:
        return ""
    candidates: list[tuple[float, ET.Element]] = []
    for element in root.iter():
        if not _eligible(element, "click"):
            continue
        bounds = _parse_bounds(element.attrib.get("bounds"))
        if bounds is None or not (
            bounds[0] <= x <= bounds[2] and bounds[1] <= y <= bounds[3]
        ):
            continue
        area = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
        candidates.append((area, element))
    for _, element in sorted(candidates, key=lambda item: item[0]):
        labels = _element_labels(element)
        if len(labels) == 1:
            return labels[0]
    return ""


def _element_labels(element: ET.Element) -> list[str]:
    labels: list[str] = []
    normalized: set[str] = set()
    for candidate in element.iter():
        for name in ("text", "content-desc", "contentDescription"):
            value = str(candidate.attrib.get(name) or "").strip()
            key = _normalize(value)
            if value and key not in normalized:
                normalized.add(key)
                labels.append(value)
    return labels


def _nearest_actionable(
    element: ET.Element,
    parent_by_child: dict[ET.Element, ET.Element],
    *,
    tool: str,
) -> ET.Element | None:
    current: ET.Element | None = element
    while current is not None:
        if _eligible(current, tool):
            return current
        current = parent_by_child.get(current)
    return None


def _eligible(element: ET.Element, tool: str) -> bool:
    attributes = element.attrib
    visible = str(attributes.get("visible") or "true").strip().casefold()
    displayed = str(attributes.get("displayed") or "true").strip().casefold()
    enabled = str(attributes.get("enabled") or "true").strip().casefold()
    if visible in {"0", "false"} or displayed in {"0", "false"} or enabled in {"0", "false"}:
        return False
    if tool == "input_text":
        return (
            attributes.get("editable", "false").lower() == "true"
            or str(attributes.get("class") or "").endswith("EditText")
        )
    return (
        attributes.get("clickable", "false").lower() == "true"
        or attributes.get("checkable", "false").lower() == "true"
    )


def _parse_bounds(value: str | None) -> tuple[float, float, float, float] | None:
    match = _BOUNDS.fullmatch(str(value or "").strip())
    if match is None:
        return None
    bounds = tuple(float(item) for item in match.groups())
    if bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
        return None
    return bounds


def _parse_viewport_bounds(
    value: Any,
    width: float,
    height: float,
) -> tuple[float, float, float, float] | None:
    coords: tuple[float, float, float, float] | None = None
    if isinstance(value, (list, tuple)) and len(value) == 4:
        if all(_is_finite_number(item) for item in value):
            coords = (
                float(value[0]),
                float(value[1]),
                float(value[2]),
                float(value[3]),
            )
    elif isinstance(value, str):
        b = _parse_bounds(value)
        if b is not None:
            coords = b
    if coords is None:
        return None
    x1, y1, x2, y2 = coords
    if x2 <= x1 or y2 <= y1:
        return None
    cx1 = max(0.0, min(width, x1))
    cy1 = max(0.0, min(height, y1))
    cx2 = max(0.0, min(width, x2))
    cy2 = max(0.0, min(height, y2))
    if cx2 <= cx1 or cy2 <= cy1:
        return None
    return (cx1, cy1, cx2, cy2)


def _normalize(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", str(value or "")).casefold()
    return " ".join(normalized.split())


def _blocked(
    action: Action,
    target: str,
    reason: str,
    *,
    candidate_count: int = 0,
    message: str = "",
) -> SemanticGroundingResult:
    detail: dict[str, object] = {
        "schema_version": "omniflow.semantic-grounding.v1",
        "status": "blocked",
        "target_description": target,
        "reason": reason,
        "candidate_count": candidate_count,
    }
    if message:
        detail["message"] = message
    return SemanticGroundingResult(action, detail)

def normalize_text(value: str) -> str:
    return _normalize(value)


def parse_bounds(value: str | None) -> tuple[float, float, float, float] | None:
    return _parse_bounds(value)


def parse_viewport_bounds(
    value: Any,
    width: float,
    height: float,
) -> tuple[float, float, float, float] | None:
    return _parse_viewport_bounds(value, width, height)


def iter_visible_nodes(root: ET.Element):
    """遍历可见且非密码的 XML 节点，递归剪枝隐藏祖先与密码子树，且跳过 hierarchy 容器根。"""
    def _walk(element: ET.Element):
        attrib = element.attrib
        is_password = str(attrib.get("password") or "false").strip().casefold() in {"1", "true"}
        if is_password:
            return
        visible = str(attrib.get("visible") or "true").strip().casefold()
        displayed = str(attrib.get("displayed") or "true").strip().casefold()
        visible_to_user = str(
            attrib.get("visible-to-user")
            or attrib.get("visibleToUser")
            or "true"
        ).strip().casefold()
        if (
            visible in {"0", "false"}
            or displayed in {"0", "false"}
            or visible_to_user in {"0", "false"}
        ):
            return
        if element.tag != "hierarchy":
            yield element
        for child in element:
            yield from _walk(child)

    yield from _walk(root)


def match_expected_text_in_observation(
    observation: Observation,
    expected_text: str,
) -> tuple[str, str | None] | None:
    norm_expected = _normalize(expected_text)
    if not norm_expected:
        return None

    xml_str = observation.xml
    extra = observation.extra if isinstance(observation.extra, dict) else {}
    display = extra.get("display") if isinstance(extra.get("display"), dict) else {}
    try:
        display_w = float(display.get("width") or 1000.0)
        display_h = float(display.get("height") or 1000.0)
    except (TypeError, ValueError):
        display_w, display_h = 1000.0, 1000.0

    if isinstance(xml_str, str) and xml_str.strip():
        try:
            root = ET.fromstring(xml_str)
            for elem in iter_visible_nodes(root):
                attrib = elem.attrib
                bounds = _parse_bounds(attrib.get("bounds"))
                if bounds is not None:
                    if bounds[2] <= 0 or bounds[0] >= display_w or bounds[3] <= 0 or bounds[1] >= display_h:
                        continue
                labels = {
                    _normalize(attrib.get(name, ""))
                    for name in ("text", "content-desc", "contentDescription")
                }
                if norm_expected in labels:
                    node_id = str(attrib.get("id") or "").strip() or None
                    return ("xml", node_id)
        except ET.ParseError:
            pass

    ocr_data = extra.get("ocr")
    if isinstance(ocr_data, dict) and str(ocr_data.get("status") or "").strip().lower() == "ready":
        items = ocr_data.get("items")
        if isinstance(items, list):
            for item in items:
                if not isinstance(item, dict):
                    continue
                confidence = item.get("confidence")
                if isinstance(confidence, bool) or not isinstance(confidence, (int, float)):
                    continue
                conf_float = float(confidence)
                if not math.isfinite(conf_float) or conf_float < 0.5:
                    continue
                if _normalize(item.get("text") or "") == norm_expected:
                    return ("ocr", None)

    return None


__all__ = [
    "SemanticGroundingResult",
    "iter_visible_nodes",
    "match_expected_text_in_observation",
    "normalize_text",
    "resolve_semantic_action",
    "parse_bounds",
    "parse_viewport_bounds",
    "semantic_target_at_point",
]
