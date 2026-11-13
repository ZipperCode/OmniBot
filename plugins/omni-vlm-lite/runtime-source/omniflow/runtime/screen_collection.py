from __future__ import annotations

import math
from typing import Any
import xml.etree.ElementTree as ET

from omniflow.core.model import Observation
from omniflow.runtime.semantic_grounding import (
    iter_visible_nodes,
    normalize_text,
    parse_bounds,
    parse_viewport_bounds,
)


def _is_finite_number(value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if not isinstance(value, (int, float)):
        return False
    return math.isfinite(float(value))


def _get_display_dimensions(observation: Observation) -> tuple[float, float] | None:
    extra = observation.extra if isinstance(getattr(observation, "extra", None), dict) else {}
    display = extra.get("display")
    if not isinstance(display, dict) and isinstance(extra.get("auxiliaries"), dict):
        display = extra["auxiliaries"].get("display")
    if not isinstance(display, dict):
        obs_display = getattr(observation, "display", None)
        if isinstance(obs_display, dict):
            display = obs_display
    if isinstance(display, dict):
        try:
            w_raw = display.get("width")
            h_raw = display.get("height")
            if _is_finite_number(w_raw) and _is_finite_number(h_raw):
                w = float(w_raw)
                h = float(h_raw)
                if w > 0 and h > 0:
                    return (w, h)
        except (TypeError, ValueError):
            pass
    return None


def _box_overlap_ratio(
    b1: list[float] | tuple[float, ...],
    b2: list[float] | tuple[float, ...],
) -> float:
    """计算两矩形重叠率，定义为交集面积 / min(area1, area2)。"""
    if len(b1) != 4 or len(b2) != 4:
        return 0.0
    ix1 = max(b1[0], b2[0])
    iy1 = max(b1[1], b2[1])
    ix2 = min(b1[2], b2[2])
    iy2 = min(b1[3], b2[3])
    if ix2 <= ix1 or iy2 <= iy1:
        return 0.0
    int_area = (ix2 - ix1) * (iy2 - iy1)
    a1 = (b1[2] - b1[0]) * (b1[3] - b1[1])
    a2 = (b2[2] - b2[0]) * (b2[3] - b2[1])
    min_area = min(a1, a2)
    if min_area <= 0:
        return 0.0
    return int_area / min_area


def _intersects(
    b: list[float] | tuple[float, ...],
    r: tuple[float, float, float, float],
) -> bool:
    """判断两矩形是否有严格正面积交集。"""
    if len(b) != 4 or len(r) != 4:
        return False
    ix1 = max(b[0], r[0])
    iy1 = max(b[1], r[1])
    ix2 = min(b[2], r[2])
    iy2 = min(b[3], r[3])
    return ix2 > ix1 and iy2 > iy1


def extract_page_lines(
    observation: Observation,
    region: tuple[float, float, float, float],
    include_ocr: bool,
) -> list[dict[str, Any]]:
    """提取单页相交于 region 的可见非密码文本项，XML 优先并与 OCR 去重。

    返回项格式为 {"text": str, "bounds": list[float], "source": "xml"|"ocr", "state_id": str}。
    按 top, left 排序。
    """
    if len(region) != 4 or region[0] >= region[2] or region[1] >= region[3]:
        return []

    dims = _get_display_dimensions(observation)
    if dims is None:
        return []
    width, height = dims

    raw_extra = getattr(observation, "extra", None)
    state_id = ""
    if isinstance(raw_extra, dict):
        state_id = str(raw_extra.get("state_id") or "")
    if not state_id:
        state_id = str(getattr(observation, "state_id", "") or "")

    xml_items: list[dict[str, Any]] = []
    raw_xml = getattr(observation, "xml", None)
    if isinstance(raw_xml, str) and raw_xml.strip():
        try:
            root = ET.fromstring(raw_xml.strip())
            for node in iter_visible_nodes(root):
                attrib = node.attrib
                text_val = str(attrib.get("text") or "").strip()
                if not text_val:
                    text_val = str(
                        attrib.get("content-desc")
                        or attrib.get("contentDescription")
                        or ""
                    ).strip()
                if not text_val:
                    continue
                parsed_b = parse_bounds(attrib.get("bounds"))
                if parsed_b is None:
                    continue
                bx1, by1, bx2, by2 = parsed_b
                cx1 = max(0.0, min(1000.0, bx1 / width * 1000.0))
                cy1 = max(0.0, min(1000.0, by1 / height * 1000.0))
                cx2 = max(0.0, min(1000.0, bx2 / width * 1000.0))
                cy2 = max(0.0, min(1000.0, by2 / height * 1000.0))
                if cx2 <= cx1 or cy2 <= cy1:
                    continue
                if not _intersects((cx1, cy1, cx2, cy2), region):
                    continue
                xml_items.append(
                    {
                        "text": text_val,
                        "bounds": [cx1, cy1, cx2, cy2],
                        "source": "xml",
                        "state_id": state_id,
                    }
                )
        except ET.ParseError:
            pass

    ocr_items: list[dict[str, Any]] = []
    if include_ocr:
        extra = observation.extra if isinstance(getattr(observation, "extra", None), dict) else {}
        ocr_data = extra.get("ocr")
        if isinstance(ocr_data, dict) and str(ocr_data.get("status") or "").strip().lower() == "ready":
            raw_items = ocr_data.get("items")
            if isinstance(raw_items, list):
                for item in raw_items:
                    if not isinstance(item, dict):
                        continue
                    if str(item.get("source") or "ocr").strip().lower() != "ocr":
                        continue
                    conf = item.get("confidence")
                    if not _is_finite_number(conf) or float(conf) < 0.5:
                        continue
                    text_val = str(item.get("text") or "").strip()
                    if not text_val:
                        continue
                    parsed_b = parse_viewport_bounds(item.get("bounds"), width, height)
                    if parsed_b is None:
                        continue
                    bx1, by1, bx2, by2 = parsed_b
                    cx1 = max(0.0, min(1000.0, bx1 / width * 1000.0))
                    cy1 = max(0.0, min(1000.0, by1 / height * 1000.0))
                    cx2 = max(0.0, min(1000.0, bx2 / width * 1000.0))
                    cy2 = max(0.0, min(1000.0, by2 / height * 1000.0))
                    if cx2 <= cx1 or cy2 <= cy1:
                        continue
                    if not _intersects((cx1, cy1, cx2, cy2), region):
                        continue
                    ocr_items.append(
                        {
                            "text": text_val,
                            "bounds": [cx1, cy1, cx2, cy2],
                            "source": "ocr",
                            "state_id": state_id,
                        }
                    )

    # 页内去重：相同文本且重叠 >= 80% 的 XML/OCR 项只保留一个，XML 优先；同文不同位置保留
    accepted_xml: list[dict[str, Any]] = []
    for x_item in xml_items:
        norm_t = normalize_text(x_item["text"])
        duplicate = False
        for acc in accepted_xml:
            if normalize_text(acc["text"]) == norm_t and _box_overlap_ratio(acc["bounds"], x_item["bounds"]) >= 0.8:
                duplicate = True
                break
        if not duplicate:
            accepted_xml.append(x_item)

    accepted_ocr: list[dict[str, Any]] = []
    for o_item in ocr_items:
        norm_t = normalize_text(o_item["text"])
        # XML 优先：若与已保留 XML 同文且重叠 >= 80%，丢弃该 OCR 项
        duplicate = False
        for acc_x in accepted_xml:
            if normalize_text(acc_x["text"]) == norm_t and _box_overlap_ratio(acc_x["bounds"], o_item["bounds"]) >= 0.8:
                duplicate = True
                break
        if duplicate:
            continue
        # OCR 自身内部去重
        for acc_o in accepted_ocr:
            if normalize_text(acc_o["text"]) == norm_t and _box_overlap_ratio(acc_o["bounds"], o_item["bounds"]) >= 0.8:
                duplicate = True
                break
        if not duplicate:
            accepted_ocr.append(o_item)

    all_items = accepted_xml + accepted_ocr
    # 单页按 top, left 排序 (bounds[1] 是 top, bounds[0] 是 left)
    all_items.sort(key=lambda it: (it["bounds"][1], it["bounds"][0]))
    return all_items


def merge_page_lines(
    previous: list[dict[str, Any]],
    current: list[dict[str, Any]],
    direction: str,
) -> tuple[list[dict[str, Any]], bool]:
    """合并跨页提取项，返回 (本页新增项列表, overlap_uncertain)。

    基于相邻页最大有序后缀/前缀重叠（至少 2 项匹配）去重，不做全局文本集合去重；
    固定同位置头尾仅作为边界排除。
    若整页内容相同判无新项，仍返回 ([], False)。
    """
    if not current:
        return ([], False)
    if not previous:
        return (list(current), False)

    norm_dir = str(direction or "down").strip().lower()
    if norm_dir not in ("down", "up"):
        norm_dir = "down"

    # 若一页整串相同判无新，仍 bool uncertain 可 false
    if len(previous) == len(current) and all(
        normalize_text(p.get("text", "")) == normalize_text(c.get("text", ""))
        for p, c in zip(previous, current)
    ):
        return ([], False)

    # 固定头尾只同位置文本的连续前/后边界，不按全局同文本去重
    # 头部连续固定项
    i = 0
    max_h = min(len(previous), len(current))
    while i < max_h:
        p = previous[i]
        c = current[i]
        if (
            normalize_text(p.get("text", "")) == normalize_text(c.get("text", ""))
            and _box_overlap_ratio(p.get("bounds", ()), c.get("bounds", ())) >= 0.8
        ):
            i += 1
        else:
            break
    header_len = i

    # 尾部连续固定项
    j = 0
    max_f = min(len(previous) - header_len, len(current) - header_len)
    while j < max_f:
        p = previous[len(previous) - 1 - j]
        c = current[len(current) - 1 - j]
        if (
            normalize_text(p.get("text", "")) == normalize_text(c.get("text", ""))
            and _box_overlap_ratio(p.get("bounds", ()), c.get("bounds", ())) >= 0.8
        ):
            j += 1
        else:
            break
    footer_len = j

    prev_scroll = previous[header_len : len(previous) - footer_len] if footer_len > 0 else previous[header_len:]
    curr_scroll = current[header_len : len(current) - footer_len] if footer_len > 0 else current[header_len:]

    if not curr_scroll:
        return ([], False)
    if not prev_scroll:
        return (list(curr_scroll), False)

    # 滚动内容整串相同判无新
    if len(prev_scroll) == len(curr_scroll) and all(
        normalize_text(p.get("text", "")) == normalize_text(c.get("text", ""))
        for p, c in zip(prev_scroll, curr_scroll)
    ):
        return ([], False)

    M = len(prev_scroll)
    N = len(curr_scroll)

    if norm_dir == "down":
        # down（内容向后阅读，手指上滑）：previous 尾部与 current 头部匹配
        best_k = 0
        for k in range(min(M, N), 1, -1):  # 至少 2 项匹配
            match = True
            for step in range(k):
                if normalize_text(prev_scroll[M - k + step].get("text", "")) != normalize_text(curr_scroll[step].get("text", "")):
                    match = False
                    break
            if match:
                best_k = k
                break
        if best_k >= 2:
            return (list(curr_scroll[best_k:]), False)
        else:
            return (list(curr_scroll), True)
    else:
        # up（内容向前阅读，手指下滑）：previous 头部与 current 尾部匹配
        best_k = 0
        for k in range(min(M, N), 1, -1):  # 至少 2 项匹配
            match = True
            for step in range(k):
                if normalize_text(prev_scroll[step].get("text", "")) != normalize_text(curr_scroll[N - k + step].get("text", "")):
                    match = False
                    break
            if match:
                best_k = k
                break
        if best_k >= 2:
            return (list(curr_scroll[: N - best_k]), False)
        else:
            return (list(curr_scroll), True)


__all__ = [
    "extract_page_lines",
    "merge_page_lines",
]
