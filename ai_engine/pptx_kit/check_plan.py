#!/usr/bin/env python3
"""
check_plan.py
Premium 次防线：对照 mapping 容量表校验 fill_plan 文本长度。
warning → 自动再截断；error → 抛 PremiumExportError 触发 fallback。
"""

from __future__ import annotations

import re
import unicodedata
from typing import Any, Dict, List

from .content_normalizer import (
    DEFAULT_CAPACITY,
    LIST_MULTI_FIELDS,
    clamp,
    clamp_lines,
    list_field_total_budget,
    load_capacity_table,
)
from .errors import PremiumExportError
from .fill_plan_builder import _format_list_as_bullets

_NUMBERED_LINE = re.compile(r"^\d+\.\s*")


def _visual_width(text: str) -> float:
    """CJK 宽度估算：全角字符按 2 单位，半角按 1 单位。"""
    width = 0.0
    for char in text:
        east = unicodedata.east_asian_width(char)
        if east in {"F", "W"}:
            width += 2.0
        elif east == "A":
            width += 1.5
        else:
            width += 1.0
    return width


def _field_for_placeholder(placeholder: str, field_mappings: Dict[str, Any]) -> str:
    for field, ph in field_mappings.items():
        if ph == placeholder:
            return field
    return ""


def _parse_numbered_block(text: str) -> List[str]:
    """将 fill_plan 中编号合并块还原为条目列表。"""
    items: List[str] = []
    for line in (text or "").split("\n"):
        stripped = _NUMBERED_LINE.sub("", line.strip())
        if stripped:
            items.append(stripped)
    if not items and (text or "").strip():
        items = [(text or "").strip()]
    return items


def _trim_list_field(text: str, spec: Dict[str, Any]) -> str:
    """
    列表型字段：按 max_items × max_chars 逐条截断后重新编号，
    而非对合并块使用单条 max_chars。
    """
    max_items = int(spec.get("max_items", 6))
    max_chars = int(spec.get("max_chars", 72))
    ellipsis = bool(spec.get("ellipsis", True))
    items = _parse_numbered_block(text)
    trimmed_items = clamp_lines(items, max_items, max_chars, ellipsis=ellipsis)
    return _format_list_as_bullets(trimmed_items)


def check_and_trim_plan(
    fill_plan: Dict[str, str],
    capacity: Dict[str, Dict[str, Any]],
    field_mappings: Dict[str, Any],
) -> Dict[str, str]:
    """
    校验 fill_plan 各占位符文本是否超出容量。
    - 列表型字段：逐条 clamp + 总预算 visual width 校验
    - 标量字段：单条 clamp + visual width 校验
    返回修剪后的 fill_plan。
    """
    trimmed: Dict[str, str] = {}
    errors: List[str] = []

    for placeholder, text in fill_plan.items():
        field = _field_for_placeholder(placeholder, field_mappings)
        spec = capacity.get(field, DEFAULT_CAPACITY.get(field, {}))
        max_chars = int(spec.get("max_chars", 200))
        ellipsis = bool(spec.get("ellipsis", True))

        if field in LIST_MULTI_FIELDS:
            new_text = _trim_list_field(text, spec)
            visual_limit = list_field_total_budget(spec) * 1.2
        else:
            new_text = clamp(text, max_chars, ellipsis=ellipsis)
            visual_limit = max_chars * 1.2

        if _visual_width(new_text) > visual_limit * 2:
            errors.append(f"{placeholder}:visual_overflow")
            if field in LIST_MULTI_FIELDS:
                shrink_spec = {**spec, "max_chars": max(1, max_chars // 2)}
                new_text = _trim_list_field(text, shrink_spec)
            else:
                new_text = clamp(new_text, max(1, max_chars // 2), ellipsis=True)
        elif _visual_width(new_text) > visual_limit:
            if field in LIST_MULTI_FIELDS:
                shrink_spec = {**spec, "max_chars": max(1, int(max_chars * 0.85))}
                new_text = _trim_list_field(text, shrink_spec)
            else:
                new_text = clamp(new_text, max(1, int(max_chars * 0.85)), ellipsis=True)

        trimmed[placeholder] = new_text

    if errors:
        raise PremiumExportError("check_plan_failed", ";".join(errors))

    return trimmed
