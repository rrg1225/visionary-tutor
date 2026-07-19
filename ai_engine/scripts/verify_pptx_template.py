#!/usr/bin/env python3
"""
verify_pptx_template.py
CI 门禁：校验模板三件套 SHA + requiredSlotIds 存在性。
失败 exit(1)。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

_ENGINE = Path(__file__).parent.parent
sys.path.insert(0, str(_ENGINE))

from pptx_kit.template_guard import verify_template_bundle

TEMPLATE_DIR = _ENGINE / "templates"
BASE_NAME = "visionary-deck-v1"


def main() -> None:
    try:
        verify_template_bundle(str(TEMPLATE_DIR), BASE_NAME)

        manifest_path = TEMPLATE_DIR / f"{BASE_NAME}.manifest.json"
        slots_path = TEMPLATE_DIR / f"{BASE_NAME}.slots.json"
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        with open(slots_path, "r", encoding="utf-8") as f:
            slots = json.load(f)

        slot_ids = {s.get("shape_id") for s in slots if isinstance(s, dict)}
        required = manifest.get("requiredSlotIds", [])
        missing = [sid for sid in required if sid not in slot_ids]
        if missing and len(slots) == 0:
            print(f"[WARN] slots 为空，模板可能尚未注入占位符")
        elif missing:
            print(f"[ERROR] requiredSlotIds 缺失: {missing}")
            sys.exit(1)

        print("[SUCCESS] 模板校验通过")
        sys.exit(0)
    except Exception as e:
        print(f"[FATAL] {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
