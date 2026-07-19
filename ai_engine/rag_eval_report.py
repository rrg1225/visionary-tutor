"""Generate JSON + Markdown RAG evaluation reports from the gold QA dataset.

This module delegates to :mod:`rag_eval` and reads the dataset dynamically from
``ai_engine/eval_sets/rag_gold_qa.jsonl`` (currently 55 questions). No hard-coded
question cap remains in this script.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

# Ensure ai_engine is importable when invoked as `python ai_engine/rag_eval_report.py`.
ENGINE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = ENGINE_DIR.parent
if str(ENGINE_DIR) not in sys.path:
    sys.path.insert(0, str(ENGINE_DIR))

from rag_eval import (  # noqa: E402
    DEFAULT_REPORT_DIR,
    DEFAULT_SET,
    load_eval_set,
    EvaluationPreflightError,
    resolve_project_path,
    run_evaluation,
)


def load_env_properties() -> None:
    env_file = PROJECT_ROOT / "backend" / ".env.properties"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def main() -> int:
    load_env_properties()
    base_url = os.getenv("BACKEND_BASE_URL", "http://127.0.0.1:8080")
    eval_path = resolve_project_path(os.getenv("RAG_EVAL_SET", str(DEFAULT_SET.relative_to(PROJECT_ROOT))))
    output_dir = resolve_project_path(os.getenv("RAG_EVAL_REPORT_DIR", str(DEFAULT_REPORT_DIR.relative_to(PROJECT_ROOT))))
    timeout = int(os.getenv("RAG_EVAL_TIMEOUT", "20"))

    dataset = load_eval_set(eval_path)
    try:
        payload = run_evaluation(
            base_url,
            eval_path,
            timeout,
            write_reports=True,
            output_dir=output_dir,
        )
    except EvaluationPreflightError as exc:
        print(json.dumps({"status": "PREFLIGHT_FAILED", "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "summary": payload["summary"],
                "dataset_count": len(dataset),
                "json": payload.get("artifacts", {}).get("json"),
                "markdown": payload.get("artifacts", {}).get("markdown"),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if payload["quality_gate"]["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
