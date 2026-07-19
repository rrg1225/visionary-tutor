#!/usr/bin/env python3
"""安全加载 session JSON payload。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict

from .errors import PremiumExportError


def load_session_json(path: Path) -> Dict[str, Any]:
    """
    安全加载 export payload JSON。
    失败抛 PremiumExportError，触发 Java fallback。
    """
    if not path.exists():
        raise PremiumExportError("session_json_missing", str(path))
    try:
        raw = path.read_text(encoding="utf-8")
        data = json.loads(raw)
    except json.JSONDecodeError as e:
        raise PremiumExportError("invalid_session_json", str(e)) from e
    if not isinstance(data, dict):
        raise PremiumExportError("session_json_not_object")
    return data


def try_parse_artifact_json(content_json: str | None) -> Dict[str, Any]:
    """
    可选解析 LLM 产出的 contentJson。
    失败静默返回 {}，不阻断主路径。
    """
    if not content_json or not content_json.strip():
        return {}
    try:
        parsed = json.loads(content_json)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        return {}
