"""Audit RAG gold-set scale, annotation provenance, diversity and adversarial coverage."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any


BASE_REQUIRED = {"id", "query", "gold_answer", "expected_terms", "expected_sources", "should_refuse"}
ANNOTATION_REQUIRED = {"chapter", "difficulty", "scenario_type", "annotation_status", "annotator_role"}
ALLOWED_DIFFICULTY = {"easy", "medium", "hard"}
ALLOWED_STATUS = {"draft", "teacher_reviewed", "adjudicated"}


def load_rows(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def audit_rows(rows: list[dict[str, Any]], minimum_cases: int = 150) -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []
    ids: set[str] = set()
    queries: set[str] = set()
    enriched = 0
    reviewed = 0
    scenario_counts: Counter[str] = Counter()
    chapter_counts: Counter[str] = Counter()

    for index, row in enumerate(rows, start=1):
        missing = BASE_REQUIRED.difference(row)
        if missing:
            errors.append(f"row {index} missing base fields: {sorted(missing)}")
        case_id = str(row.get("id", ""))
        query = str(row.get("query", "")).strip().lower()
        if case_id in ids:
            errors.append(f"duplicate id: {case_id}")
        if query and query in queries:
            errors.append(f"duplicate query: {row.get('query')}")
        ids.add(case_id)
        queries.add(query)

        if ANNOTATION_REQUIRED.issubset(row):
            enriched += 1
            scenario_counts[str(row["scenario_type"])] += 1
            chapter_counts[str(row["chapter"])] += 1
            if row["difficulty"] not in ALLOWED_DIFFICULTY:
                errors.append(f"{case_id}: invalid difficulty {row['difficulty']}")
            if row["annotation_status"] not in ALLOWED_STATUS:
                errors.append(f"{case_id}: invalid annotation_status {row['annotation_status']}")
            if row["annotation_status"] in {"teacher_reviewed", "adjudicated"}:
                reviewed += 1

    if len(rows) < minimum_cases:
        warnings.append(f"dataset has {len(rows)} cases; target is at least {minimum_cases}")
    if enriched < len(rows):
        warnings.append(f"{len(rows) - enriched} cases lack annotation provenance fields")
    if reviewed < max(1, len(rows) // 2):
        warnings.append("fewer than half of cases are teacher-reviewed or adjudicated")
    if len(chapter_counts) < 3:
        warnings.append("cross-chapter coverage is below 3 chapters")
    for required_scenario in ("grounded", "unsupported", "prompt_injection", "wrong_citation"):
        if scenario_counts[required_scenario] == 0:
            warnings.append(f"missing scenario_type={required_scenario}")

    return {
        "case_count": len(rows),
        "enriched_count": enriched,
        "teacher_reviewed_count": reviewed,
        "chapters": dict(chapter_counts),
        "scenarios": dict(scenario_counts),
        "errors": errors,
        "warnings": warnings,
        "strict_ready": not errors and not warnings,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--minimum-cases", type=int, default=150)
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    result = audit_rows(load_rows(args.dataset), args.minimum_cases)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result["errors"] or (args.strict and result["warnings"]):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
