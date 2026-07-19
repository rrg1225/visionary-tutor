#!/usr/bin/env python3
"""
template_guard.py
模板防篡改门禁（运行时校验三件套完整性与一致性）

- 读取 manifest.json，重算 SHA-256
- 任何不匹配或缺失 → 抛出 TemplateTamperedError
- 供 CLI 与 Java Process 调用前置校验
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Dict, Tuple


class TemplateTamperedError(Exception):
    """自定义异常：模板文件被篡改或缺失。"""
    pass


def _compute_sha256(file_path: Path, *, normalize_newlines: bool = False) -> str:
    """内部工具：计算 SHA-256；文本元数据可先规范化换行符。"""
    if not file_path.exists():
        return ""
    if normalize_newlines:
        data = file_path.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
        return hashlib.sha256(data).hexdigest()
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_template_bundle(template_dir: str, base_name: str) -> bool:
    """
    校验模板三件套完整性。
    :param template_dir: 模板目录绝对路径
    :param base_name: 基础名，如 "visionary-deck-v1"
    :return: True 表示通过
    :raises TemplateTamperedError: 任何文件缺失或 SHA 不匹配
    """
    t_dir = Path(template_dir)
    pptx = t_dir / f"{base_name}.pptx"
    slots = t_dir / f"{base_name}.slots.json"
    mapping = t_dir / f"{base_name}.mapping.json"
    manifest = t_dir / f"{base_name}.manifest.json"

    # 1. 必须存在 manifest
    if not manifest.exists():
        raise TemplateTamperedError(f"manifest.json 不存在: {manifest}")

    # 2. 读取 manifest（安全解析）
    try:
        with open(manifest, "r", encoding="utf-8") as f:
            man: Dict[str, Any] = json.load(f)
    except Exception as e:
        raise TemplateTamperedError(f"manifest.json 解析失败: {e}")

    files_info: Dict[str, Any] = man.get("files", {})
    if not isinstance(files_info, dict):
        raise TemplateTamperedError("manifest.json 格式错误：files 字段缺失")

    # 3. 逐个校验存在性 + SHA
    required = [
        ("pptx", pptx, False),
        ("slots", slots, True),
        ("mapping", mapping, True)
    ]
    for key, path, normalize_newlines in required:
        info = files_info.get(key, {})
        expected_sha = info.get("sha256", "") if isinstance(info, dict) else ""
        actual_sha = _compute_sha256(path, normalize_newlines=normalize_newlines)

        if not path.exists():
            raise TemplateTamperedError(f"{key} 文件缺失: {path}")
        if not expected_sha:
            raise TemplateTamperedError(f"manifest 中 {key} 的 sha256 为空")
        if actual_sha != expected_sha:
            raise TemplateTamperedError(
                f"{key} SHA 不匹配！期望 {expected_sha[:8]}... 实际 {actual_sha[:8]}..."
            )

    # 4. 校验 templateVersion 格式
    version = man.get("templateVersion", "")
    if not isinstance(version, str) or not version:
        raise TemplateTamperedError(f"templateVersion 非法: {version}")

    # 5. 可选：spot-check requiredSlotIds 是否存在于 slots.json
    required_ids = man.get("requiredSlotIds", [])
    if isinstance(required_ids, list) and required_ids:
        try:
            with open(slots, "r", encoding="utf-8") as f:
                slots_data = json.load(f)
            if isinstance(slots_data, list):
                existing = {s.get("shape_id") for s in slots_data if isinstance(s, dict)}
                missing = [sid for sid in required_ids if sid not in existing]
                if missing:
                    raise TemplateTamperedError(
                        f"slots.json 缺少 requiredSlotIds: {missing[:5]}"
                    )
        except TemplateTamperedError:
            raise
        except Exception as e:
            raise TemplateTamperedError(f"slots.json spot-check 失败: {e}") from e

    return True
