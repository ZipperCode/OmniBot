from __future__ import annotations

from dataclasses import dataclass
import math
import re
from typing import Any
import xml.etree.ElementTree as ET

_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
_TRUE = {"1", "true"}
_STOPWORDS = {
    "to", "a", "an", "the", "in", "on", "at", "of", "for", "and", "or",
    "is", "it", "by", "with", "as", "from", "into", "onto",
}


@dataclass(frozen=True)
class PageContext:
    evidence: str = ""
    useful: bool = False
    clickable: bool = False
    editable: bool = False
    scrollable: bool = False
    focused: bool = False


def analyze_page_context(
    state: dict[str, Any],
    *,
    goal: str = "",
    limit: int = 32,
) -> PageContext:
    display = state.get("display") if isinstance(state.get("display"), dict) else {}
    width = _positive_number(display.get("width"))
    height = _positive_number(display.get("height"))

    xml_text = str(state.get("xml") or "").strip()
    root: ET.Element | None = None
    if xml_text:
        try:
            root = ET.fromstring(xml_text)
            if width is None:
                width = _positive_number(root.get("width"))
            if height is None:
                height = _positive_number(root.get("height"))
        except ET.ParseError:
            root = None

    records: list[dict[str, Any]] = []
    seen: set[tuple[str, ...]] = set()
    clickable = editable = scrollable = focused = False

    if root is not None:
        actionable_nodes = {node for node in root.iter() if _is_actionable(node)}

        # 实际可点击父标签可聚合子控件展示文本，但不可吞掉独立子控件（含 clickable / focusable）
        aggregated_labels: dict[ET.Element, str] = {}
        absorbed_nodes: set[ET.Element] = set()

        for parent in actionable_nodes:
            child_texts: list[str] = []

            def _collect_and_mark(element: ET.Element) -> None:
                for child in element:
                    # 跳过不可见或已禁用的子树，避免吸收旧隐藏文本
                    if not _is_visible(child.attrib) or not _is_enabled(child.attrib):
                        continue
                    if child in actionable_nodes:
                        # 独立可交互/可聚焦子控件不吞，保留其独立性
                        continue
                    child_text = _clean(child.attrib.get("text"))
                    child_desc = _clean(
                        child.attrib.get("content-desc")
                        or child.attrib.get("contentDescription")
                    )
                    if child_text:
                        child_texts.append(child_text)
                    elif child_desc:
                        child_texts.append(child_desc)
                    absorbed_nodes.add(child)
                    _collect_and_mark(child)

            _collect_and_mark(parent)
            if child_texts:
                aggregated_labels[parent] = " ".join(child_texts)

        original_index = 0
        for node in root.iter():
            if node is root and len(root) > 0:
                continue
            original_index += 1
            attrs = node.attrib

            # 可见性与启用状态检查
            if not _is_visible(attrs) or not _is_enabled(attrs):
                continue

            bounds = _bounds(attrs.get("bounds"))
            # 视口与有效正面积检查
            if bounds is not None:
                x1, y1, x2, y2 = bounds
                if x2 <= x1 or y2 <= y1:
                    continue
                if x2 <= 0 or y2 <= 0:
                    continue
                if width is not None and x1 >= width:
                    continue
                if height is not None and y1 >= height:
                    continue

            class_name = str(attrs.get("class") or "").strip()
            text = _clean(attrs.get("text"))
            description = _clean(
                attrs.get("content-desc") or attrs.get("contentDescription")
            )
            resource_id = _clean(attrs.get("resource-id") or attrs.get("resourceId"))
            node_id = _clean(attrs.get("id") or attrs.get("index"))

            node_clickable = _flag(attrs, "clickable") or _flag(attrs, "checkable")
            node_editable = _flag(attrs, "editable") or class_name.endswith("EditText")
            node_scrollable = _flag(attrs, "scrollable")
            node_focused = _flag(attrs, "focused")
            node_actionable = _is_actionable(node)

            # 若是非可操作子节点，且文本已被可点击父标签聚合，则去冗余跳过
            if node in absorbed_nodes and not node_actionable:
                continue

            # 若可点击父标签自身缺少文本，使用聚合文本补全
            if node_actionable and not text and node in aggregated_labels:
                text = aggregated_labels[node]

            if not (text or description or resource_id or node_actionable):
                continue

            has_label = bool(text or description)
            is_leaf = len(node) == 0

            # 去冗余：无任何文本或描述标签且不是叶子节点的纯容器（非滚动交互）直接跳过，避免空布局占用证据预算
            if not has_label and not is_leaf and not node_scrollable:
                continue
            # 保留同名不同项：去重键包含 node_id/index 与 bounds
            key = (node_id, text, description, resource_id, class_name, str(bounds))
            if key in seen:
                continue
            seen.add(key)

            clickable = clickable or node_clickable
            editable = editable or node_editable
            scrollable = scrollable or node_scrollable
            focused = focused or node_focused

            parts: list[str] = []
            if node_id:
                parts.append(f"node_id={node_id}")
            if text:
                parts.append(f'text="{text}"')
            if description and description != text:
                parts.append(f'desc="{description}"')
            if resource_id:
                parts.append(f'id="{resource_id}"')
            if class_name:
                parts.append(f"class={class_name.rsplit('.', 1)[-1]}")
            if bounds is not None:
                parts.append(f"bounds={_normalized_bounds(bounds, width, height)}")

            flags = [
                name
                for name, enabled in (
                    ("clickable", node_clickable),
                    ("editable", node_editable),
                    ("scrollable", node_scrollable),
                    ("focused", node_focused),
                    ("focusable", _flag(attrs, "focusable")),
                    ("checked", _flag(attrs, "checked")),
                    ("selected", _flag(attrs, "selected")),
                )
                if enabled
            ]
            if flags:
                parts.append("flags=" + ",".join(flags))

            combined_str = f"{text} {description} {resource_id}"
            is_goal_relevant = _is_relevant(combined_str, goal)

            records.append(
                {
                    "is_goal_relevant": is_goal_relevant,
                    "has_label": has_label,
                    "is_leaf_or_focused": is_leaf or node_focused,
                    "actionable": node_actionable,
                    "original_index": original_index,
                    "text": " ".join(parts),
                    "source": "xml",
                }
            )

    # 提取端侧 OCR 识别文字作为独立候选条目
    extra = state.get("extra") if isinstance(state.get("extra"), dict) else {}
    ocr_data = extra.get("ocr")
    ocr_items: list[dict[str, Any]] = []
    if isinstance(ocr_data, dict) and ocr_data.get("status") == "ready":
        raw_items = ocr_data.get("items")
        if isinstance(raw_items, list):
            ocr_items = raw_items

    ocr_index = 0
    safe_w = width if width is not None and width > 0 else 1000.0
    safe_h = height if height is not None and height > 0 else 1000.0
    for item in ocr_items:
        if not isinstance(item, dict):
            continue
        if str(item.get("source") or "").strip().lower() != "ocr":
            continue
        ocr_text = _clean(item.get("text"))
        if not ocr_text:
            continue
        conf = item.get("confidence")
        if not _is_finite_number(conf) or float(conf) < 0.5:
            continue

        raw_bounds = item.get("bounds")
        parsed_bounds = _parse_viewport_bounds(raw_bounds, safe_w, safe_h)
        if parsed_bounds is None:
            continue

        ocr_index += 1
        source = "ocr"

        # OCR 候选格式化，严格不虚构 clickable
        parts = [f'text="{ocr_text}"']
        parts.append(f"bounds={_normalized_bounds(parsed_bounds, width, height)}")
        parts.append(f"source={source}")
        parts.append(f"confidence={float(conf):.2f}")

        is_goal_relevant = _is_relevant(ocr_text, goal)
        records.append(
            {
                "is_goal_relevant": is_goal_relevant,
                "has_label": True,
                "is_leaf_or_focused": True,
                "actionable": False,
                "original_index": 10000 + ocr_index,
                "text": " ".join(parts),
                "source": "ocr",
            }
        )

    # 排序：目标相关优先、有文本/描述标签优先、叶子控件/获焦控件优先、可操作优先、原始顺序
    selected = sorted(
        records,
        key=lambda item: (
            not item["is_goal_relevant"],
            not item["has_label"],
            not item["is_leaf_or_focused"],
            not item["actionable"],
            item["original_index"],
        ),
    )[: max(1, int(limit))]

    visible_elements = [
        f"[{index}] {item['text']}"
        for index, item in enumerate(selected, start=1)
    ]
    useful = bool(visible_elements)
    evidence = (
        "Primary UI grounding evidence from the latest accessibility XML "
        "(bounds use normalized 0..1000 coordinates):\n"
        + "\n".join(visible_elements)
    )
    return PageContext(
        evidence=evidence,
        useful=useful,
        clickable=clickable or any("clickable" in r["text"] for r in selected),
        editable=editable,
        scrollable=scrollable,
        focused=focused,
    )


def _is_actionable(node: ET.Element) -> bool:
    attrs = node.attrib
    class_name = str(attrs.get("class") or "").strip()
    return bool(
        _flag(attrs, "clickable")
        or _flag(attrs, "checkable")
        or _flag(attrs, "editable")
        or class_name.endswith("EditText")
        or _flag(attrs, "scrollable")
        or _flag(attrs, "focusable")
    )


def _is_visible(attrs: dict[str, str]) -> bool:
    visible = str(attrs.get("visible") or "true").strip().casefold()
    displayed = str(attrs.get("displayed") or "true").strip().casefold()
    return visible not in {"0", "false"} and displayed not in {"0", "false"}


def _is_enabled(attrs: dict[str, str]) -> bool:
    enabled = str(attrs.get("enabled") or "true").strip().casefold()
    return enabled not in {"0", "false"}


def _is_relevant(target_str: str, goal: str) -> bool:
    if not goal or not target_str:
        return False
    goal_lower = goal.casefold()
    text_lower = target_str.casefold()

    if len(text_lower) >= 2 and (text_lower in goal_lower or goal_lower in text_lower):
        return True

    target_words = set(re.findall(r"[a-z0-9_]+", text_lower))
    goal_words = set(re.findall(r"[a-z0-9_]+", goal_lower))
    meaningful_goal_words = goal_words - _STOPWORDS
    if meaningful_goal_words and any(
        w in target_words for w in meaningful_goal_words if len(w) >= 2
    ):
        return True

    goal_cjk = "".join(c for c in goal_lower if "\u4e00" <= c <= "\u9fff")
    if goal_cjk:
        for i in range(len(goal_cjk)):
            if len(goal_cjk) >= 2 and i + 1 < len(goal_cjk):
                two_gram = goal_cjk[i : i + 2]
                if two_gram in text_lower:
                    return True
            single = goal_cjk[i]
            if single in text_lower:
                return True

    return False


def _flag(attrs: dict[str, str], name: str) -> bool:
    return str(attrs.get(name) or "").strip().casefold() in _TRUE


def _clean(value: Any) -> str:
    return " ".join(str(value or "").split())[:240]


def _positive_number(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if number > 0 else None


def _is_finite_number(value: Any) -> bool:
    return (
        not isinstance(value, bool)
        and isinstance(value, (int, float))
        and math.isfinite(float(value))
    )


def _bounds(value: Any) -> tuple[int, int, int, int] | None:
    match = _BOUNDS.fullmatch(str(value or "").strip())
    if match is None:
        return None
    return tuple(int(item) for item in match.groups())


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
        b = _bounds(value)
        if b is not None:
            coords = (float(b[0]), float(b[1]), float(b[2]), float(b[3]))
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


def _normalized_bounds(
    bounds: tuple[float, float, float, float] | tuple[int, int, int, int],
    width: float | None,
    height: float | None,
) -> str:
    if width is None or height is None:
        return f"[{int(bounds[0])},{int(bounds[1])}][{int(bounds[2])},{int(bounds[3])}]"
    x1, y1, x2, y2 = bounds
    normalized = (
        round(max(0, min(1000, x1 * 1000 / width))),
        round(max(0, min(1000, y1 * 1000 / height))),
        round(max(0, min(1000, x2 * 1000 / width))),
        round(max(0, min(1000, y2 * 1000 / height))),
    )
    return f"[{normalized[0]},{normalized[1]}][{normalized[2]},{normalized[3]}]"


__all__ = [
    "PageContext",
    "analyze_page_context",
]
