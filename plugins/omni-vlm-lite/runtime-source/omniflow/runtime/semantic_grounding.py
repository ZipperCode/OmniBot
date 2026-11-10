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
    if action.tool not in _SUPPORTED_TOOLS or not target:
        return SemanticGroundingResult(action)

    display = observation.extra.get("display") if isinstance(observation.extra, dict) else None
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
            node_id = str(actionable.attrib.get("id") or actionable.attrib.get("index") or "").strip()
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


__all__ = [
    "SemanticGroundingResult",
    "resolve_semantic_action",
    "semantic_target_at_point",
]
