#!/usr/bin/env python3
"""
content_normalizer.py
Premium 内容归一化器：从 mapping 容量表驱动硬截断 + Markdown 清理。

原则：排版不破 > 信息不全；所有 dict 访问使用 .get()。
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Union

from .errors import PremiumExportError


# Markdown 符号清理（不含单字符 `_`，避免破坏 visionary_global_knowledge 等标识符）
MD_PATTERN = re.compile(
    r"(\*\*|__|\*|#{1,6}|\[.*?\]\(.*?\)|!\[.*?\]\(.*?\)|<[^>]+>|`{1,3}|^\s*[-*+]\s+|^\s*\d+\.\s+)",
    re.MULTILINE,
)

# fill_plan 中按「多条目」合并的字段（check_plan 需按 max_items × max_chars 校验）
LIST_MULTI_FIELDS = frozenset({"handout", "quiz", "videoScript", "citations"})

# 内置默认容量表（mapping 缺失时 fallback）
DEFAULT_CAPACITY: Dict[str, Dict[str, Any]] = {
    "topic": {"max_items": 1, "max_chars": 40, "ellipsis": True},
    "subtitle": {"max_items": 1, "max_chars": 60, "ellipsis": True},
    "studentProfile": {"max_items": 1, "max_chars": 200, "max_lines": 4, "ellipsis": True},
    "handout": {"max_items": 6, "max_chars": 72, "ellipsis": True},
    "quiz": {"max_items": 5, "max_chars": 90, "ellipsis": True},
    "mindmap": {"max_items": 1, "max_chars": 150, "ellipsis": True},
    "videoScript": {"max_items": 4, "max_chars": 80, "ellipsis": True},
    "citations": {"max_items": 6, "max_chars": 100, "ellipsis": True},
    "speaker_notes": {"max_items": 1, "max_chars": 500, "ellipsis": True},
    "footerNote": {"max_items": 1, "max_chars": 80, "ellipsis": True},
}


def normalize_whitespace(text: str) -> str:
    """折叠多余空白，保留单个空格。"""
    return " ".join(text.split())


def strip_markdown(text: str) -> str:
    """去除 Markdown 符号，返回纯文本（最多 10 轮，防死循环）。"""
    if not isinstance(text, str):
        return ""
    cleaned = text
    for _ in range(10):
        new_cleaned = MD_PATTERN.sub("", cleaned)
        if new_cleaned == cleaned:
            break
        cleaned = new_cleaned
    return cleaned.strip()


def clamp(text: str, max_chars: int, *, ellipsis: bool = True) -> str:
    """
    硬截断单条文本。
    超过 max_chars 时截取并在 ellipsis=True 时用「…」结尾。
    """
    if not isinstance(text, str):
        return ""
    text = normalize_whitespace(strip_markdown(text))
    if len(text) <= max_chars:
        return text
    if ellipsis and max_chars > 1:
        return text[: max_chars - 1].rstrip() + "…"
    return text[:max_chars]


def clamp_lines(
    lines: List[str],
    max_items: int,
    max_chars: int,
    *,
    ellipsis: bool = True,
) -> List[str]:
    """取前 max_items 条，每条 clamp 到 max_chars。"""
    result: List[str] = []
    for line in lines[:max_items]:
        if not isinstance(line, str):
            line = str(line)
        stripped = line.strip()
        if not stripped:
            continue
        result.append(clamp(stripped, max_chars, ellipsis=ellipsis))
    return result


def _coerce_lines(value: Any) -> List[str]:
    """将 str / list 统一为行列表。"""
    if value is None:
        return []
    if isinstance(value, list):
        return [str(v) for v in value if v is not None and str(v).strip()]
    if isinstance(value, str):
        return [ln.strip() for ln in value.split("\n") if ln.strip()]
    return [str(value)]


def extract_quiz_stem(item: Any) -> str:
    """从 quiz 条目（dict / str）提取题干，与 Java extractQuizStems 对齐。"""
    if isinstance(item, dict):
        return str(item.get("question") or item.get("stem") or "").strip()
    return str(item).strip() if item is not None else ""


def coerce_quiz_lines(value: Any) -> List[str]:
    """quiz 字段 → 题干行列表。"""
    if value is None:
        return []
    if isinstance(value, list):
        return [s for s in (extract_quiz_stem(q) for q in value) if s]
    return _coerce_lines(value)


def clean_text_line(text: str) -> str:
    """单条 Markdown → 纯文本（标准版 / premium 共用）。"""
    return normalize_whitespace(strip_markdown(str(text)))


def clean_text_lines(value: Any) -> List[str]:
    """str / list → Markdown 清洗后的行列表。"""
    return [clean_text_line(line) for line in _coerce_lines(value) if clean_text_line(line)]


def list_field_total_budget(spec: Dict[str, Any]) -> int:
    """列表型占位符合并后的字符总预算（含编号前缀与换行）。"""
    max_items = int(spec.get("max_items", 1))
    max_chars = int(spec.get("max_chars", 200))
    prefix_overhead = 4  # "N. "
    return max_items * (max_chars + prefix_overhead) + max(0, max_items - 1)


def _clamp_profile(text: str, max_chars: int, max_lines: int) -> str:
    """profile 支持换行，最多 max_lines 行，总字符不超过 max_chars。"""
    text = strip_markdown(text)
    if not text:
        return ""
    # 先按句号/分号拆句，再合并到行
    parts = re.split(r"[。；;\n]", text)
    lines: List[str] = []
    for part in parts:
        part = part.strip()
        if not part:
            continue
        lines.append(part)
        if len(lines) >= max_lines:
            break
    joined = "\n".join(lines)
    return clamp(joined, max_chars, ellipsis=True)


def load_capacity_table(mapping_path: Path) -> Dict[str, Dict[str, Any]]:
    """从 mapping.json 读取 capacity 段，缺失则用 DEFAULT_CAPACITY。"""
    if not mapping_path.exists():
        return dict(DEFAULT_CAPACITY)
    try:
        with open(mapping_path, "r", encoding="utf-8") as f:
            data: Dict[str, Any] = json.load(f)
    except json.JSONDecodeError as e:
        raise PremiumExportError("invalid_mapping_json", str(e)) from e
    capacity = data.get("capacity", {})
    if not isinstance(capacity, dict) or not capacity:
        return dict(DEFAULT_CAPACITY)
    merged = dict(DEFAULT_CAPACITY)
    for key, spec in capacity.items():
        if isinstance(spec, dict):
            merged[key] = {**merged.get(key, {}), **spec}
    return merged


class PremiumNormalizer:
    """从 mapping 容量表驱动的内容归一化器。"""

    def __init__(self, capacity: Optional[Dict[str, Dict[str, Any]]] = None) -> None:
        self._capacity = capacity or dict(DEFAULT_CAPACITY)

    @classmethod
    def from_mapping(cls, template_dir: Path, base_name: str = "visionary-deck-v1") -> "PremiumNormalizer":
        mapping_path = template_dir / f"{base_name}.mapping.json"
        return cls(load_capacity_table(mapping_path))

    def _spec(self, field: str) -> Dict[str, Any]:
        return self._capacity.get(field, DEFAULT_CAPACITY.get(field, {}))

    def clamp(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """
        主入口：对 payload 执行严格归一化，返回新 dict。
        所有字段使用 .get() + 默认值。
        """
        if not isinstance(payload, dict):
            return {}

        result: Dict[str, Any] = {}

        # topic
        spec = self._spec("topic")
        result["topic"] = clamp(
            str(payload.get("topic") or "课程资源包"),
            int(spec.get("max_chars", 40)),
            ellipsis=bool(spec.get("ellipsis", True)),
        )

        # subtitle
        spec = self._spec("subtitle")
        result["subtitle"] = clamp(
            str(payload.get("subtitle") or ""),
            int(spec.get("max_chars", 60)),
            ellipsis=bool(spec.get("ellipsis", True)),
        )

        # studentProfile（支持多行）
        spec = self._spec("studentProfile")
        result["studentProfile"] = _clamp_profile(
            str(payload.get("studentProfile") or ""),
            int(spec.get("max_chars", 200)),
            int(spec.get("max_lines", 4)),
        )

        # handout
        spec = self._spec("handout")
        handout_lines = _coerce_lines(payload.get("handout"))
        result["handout"] = clamp_lines(
            handout_lines,
            int(spec.get("max_items", 6)),
            int(spec.get("max_chars", 72)),
            ellipsis=bool(spec.get("ellipsis", True)),
        )

        # quiz（仅题干）
        spec = self._spec("quiz")
        quiz_lines = coerce_quiz_lines(payload.get("quiz"))
        result["quiz"] = clamp_lines(
            quiz_lines,
            int(spec.get("max_items", 5)),
            int(spec.get("max_chars", 90)),
            ellipsis=bool(spec.get("ellipsis", True)),
        )

        # mindmap
        spec = self._spec("mindmap")
        result["mindmap"] = clamp(
            str(payload.get("mindmap") or ""),
            int(spec.get("max_chars", 150)),
            ellipsis=bool(spec.get("ellipsis", True)),
        )

        # videoScript
        spec = self._spec("videoScript")
        video_lines = _coerce_lines(payload.get("videoScript"))
        result["videoScript"] = clamp_lines(
            video_lines,
            int(spec.get("max_items", 4)),
            int(spec.get("max_chars", 80)),
            ellipsis=bool(spec.get("ellipsis", True)),
        )

        # citations
        spec = self._spec("citations")
        citation_lines = _coerce_lines(payload.get("citations"))
        result["citations"] = clamp_lines(
            citation_lines,
            int(spec.get("max_items", 6)),
            int(spec.get("max_chars", 100)),
            ellipsis=bool(spec.get("ellipsis", True)),
        )

        # footerNote
        spec = self._spec("footerNote")
        result["footerNote"] = clamp(
            str(payload.get("footerNote") or "完整讲义见智眸学伴资源卡片 · DocAgent"),
            int(spec.get("max_chars", 80)),
            ellipsis=bool(spec.get("ellipsis", True)),
        )

        # speaker_notes（旁白备注区可略长）
        spec = self._spec("speaker_notes")
        result["speaker_notes"] = clamp(
            str(payload.get("speaker_notes") or ""),
            int(spec.get("max_chars", 500)),
            ellipsis=bool(spec.get("ellipsis", True)),
        )

        # 透传 sessionId 等元数据
        for key in ("sessionId", "userId", "summary"):
            val = payload.get(key)
            if val is not None:
                result[key] = val

        return result
