#!/usr/bin/env python3
"""
fill_plan_builder.py
将归一化后的 payload 转为 fill_plan（占位符 -> 文本值映射）。
所有读取使用 .get()，缺失 slot 静默跳过。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List

from .errors import PremiumExportError


def load_mapping(template_dir: Path, base_name: str) -> Dict[str, Any]:
    """安全加载 mapping.json。"""
    path = template_dir / f"{base_name}.mapping.json"
    if not path.exists():
        raise PremiumExportError("mapping_missing", str(path))
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        raise PremiumExportError("invalid_mapping_json", str(e)) from e
    return data if isinstance(data, dict) else {}


def _format_list_as_bullets(items: List[str]) -> str:
    """多条目字段合并为带编号的单行文本块。"""
    lines: List[str] = []
    for idx, item in enumerate(items, start=1):
        if item and str(item).strip():
            lines.append(f"{idx}. {item}")
    return "\n".join(lines)


def build_fill_plan(
    normalized: Dict[str, Any],
    mapping: Dict[str, Any],
) -> Dict[str, str]:
    """
    根据 field_mappings 将 normalized 字段映射到占位符文本。
    返回 { "{{topic}}": "实际文本", ... }
    """
    field_mappings: Dict[str, Any] = mapping.get("field_mappings", {})
    if not isinstance(field_mappings, dict):
        field_mappings = {}

    # 字段 -> 渲染值的转换规则
    renderers: Dict[str, Any] = {
        "topic": lambda d: str(d.get("topic") or ""),
        "subtitle": lambda d: str(d.get("subtitle") or ""),
        "studentProfile": lambda d: str(d.get("studentProfile") or ""),
        "handout": lambda d: _format_list_as_bullets(d.get("handout") or []),
        "quiz": lambda d: _format_list_as_bullets(d.get("quiz") or []),
        "mindmap": lambda d: str(d.get("mindmap") or ""),
        "videoScript": lambda d: _format_list_as_bullets(d.get("videoScript") or []),
        "citations": lambda d: _format_list_as_bullets(d.get("citations") or []),
        "footerNote": lambda d: str(d.get("footerNote") or ""),
        "speaker_notes": lambda d: str(d.get("speaker_notes") or ""),
    }

    plan: Dict[str, str] = {}
    for field, placeholder in field_mappings.items():
        if not placeholder or not isinstance(placeholder, str):
            continue
        renderer = renderers.get(field)
        if renderer is None:
            # 未知字段：尝试直接从 normalized 取同名 key
            val = normalized.get(field, "")
            if isinstance(val, list):
                text = _format_list_as_bullets(val)
            else:
                text = str(val) if val is not None else ""
        else:
            text = renderer(normalized)
        plan[placeholder] = text

    return plan
