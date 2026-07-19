"""RAG gold-set evaluation for VisionaryTutor.

Metrics:
- citation_correctness
- answer_faithfulness (deterministic proxy)
- refusal_correctness
- top_k_recall
- p95 latency
- bm25_fallback_hit_rate
- unsupported_query_refusal_rate

Usage:
  python ai_engine/rag_eval.py
  python ai_engine/rag_eval.py --base-url http://127.0.0.1:8080
  python ai_engine/rag_eval.py --set ai_engine/eval_sets/rag_gold_qa.jsonl --write-reports
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import statistics
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SET = PROJECT_ROOT / "ai_engine" / "eval_sets" / "rag_gold_qa.jsonl"
DEFAULT_REPORT_DIR = PROJECT_ROOT / "reports"

LATIN_TERM = re.compile(r"[a-z0-9_+\-*/=]{2,}", re.IGNORECASE)
HAN_RUN = re.compile(r"[\u4e00-\u9fff]{2,}")


class EvaluationPreflightError(RuntimeError):
    """Raised when the evaluation backend is unavailable before a run starts."""


@dataclass(frozen=True)
class QualityThresholds:
    max_failures: int = 0
    min_citation_correctness: float = 0.88
    min_answer_faithfulness: float = 0.80
    min_refusal_correctness: float = 0.90
    min_top_k_recall: float = 0.88
    min_unsupported_query_refusal_rate: float = 0.90
    max_p95_latency_ms: float = 2500.0

    @classmethod
    def from_env(cls) -> "QualityThresholds":
        return cls(
            max_failures=int(os.getenv("RAG_EVAL_MAX_FAILURES", "0")),
            min_citation_correctness=float(os.getenv("RAG_EVAL_MIN_CITATION", "0.88")),
            min_answer_faithfulness=float(os.getenv("RAG_EVAL_MIN_FAITHFULNESS", "0.80")),
            min_refusal_correctness=float(os.getenv("RAG_EVAL_MIN_REFUSAL", "0.90")),
            min_top_k_recall=float(os.getenv("RAG_EVAL_MIN_TOP_K_RECALL", "0.88")),
            min_unsupported_query_refusal_rate=float(
                os.getenv("RAG_EVAL_MIN_UNSUPPORTED_REFUSAL", "0.90")
            ),
            max_p95_latency_ms=float(os.getenv("RAG_EVAL_MAX_P95_MS", "2500")),
        )


def resolve_project_path(path: str | Path) -> Path:
    candidate = Path(path)
    if candidate.is_absolute():
        return candidate
    return (PROJECT_ROOT / candidate).resolve()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_commit() -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(PROJECT_ROOT), "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
            timeout=5,
        ).strip()
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def check_backend_health(base_url: str, timeout: int) -> dict[str, Any]:
    url = f"{base_url.rstrip('/')}/api/health"
    try:
        with urllib.request.urlopen(url, timeout=min(max(timeout, 1), 10)) as response:
            if response.status < 200 or response.status >= 300:
                raise EvaluationPreflightError(
                    f"RAG evaluation backend health check returned HTTP {response.status}: {url}"
                )
            raw = response.read().decode("utf-8")
            payload = json.loads(raw) if raw.strip() else {}
            if payload.get("ragHaAvailable") is not True:
                raise EvaluationPreflightError(
                    "RAG evaluation backend is reachable but no RAG backend is ready "
                    f"(ragHaAvailable={payload.get('ragHaAvailable')!r}): {url}"
                )
            return payload
    except EvaluationPreflightError:
        raise
    except Exception as exc:
        raise EvaluationPreflightError(
            f"RAG evaluation backend is unavailable at {url}: {exc}"
        ) from exc


def tokenize(text: str) -> set[str]:
    normalized = re.sub(r"\bcite-[\w\u4e00-\u9fff_.:-]+\b", " ", (text or "").lower())
    normalized = re.sub(r"[^\w\s\u4e00-\u9fff]", " ", normalized)
    result: set[str] = set()
    for match in LATIN_TERM.finditer(normalized):
        result.add(match.group())
    for run in HAN_RUN.finditer(normalized):
        token = run.group()
        if len(token) <= 4:
            result.add(token)
        else:
            result.update(token[i : i + 2] for i in range(len(token) - 1))
    return result


def overlap_score(claim: str, evidence: str) -> float:
    claim_terms = tokenize(claim)
    if not claim_terms:
        return 0.0
    evidence_terms = tokenize(evidence)
    hits = sum(1 for term in claim_terms if term in evidence_terms)
    return hits / len(claim_terms)


def load_eval_set(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            item = json.loads(line)
            item.setdefault("id", f"row-{line_no}")
            item.setdefault("expected_terms", [])
            item.setdefault("expected_sources", [])
            item.setdefault("should_refuse", False)
            rows.append(item)
    if not rows:
        raise ValueError(f"Evaluation dataset is empty: {path}")
    return rows


def retrieve(base_url: str, query: str, timeout: int) -> dict[str, Any]:
    params = urllib.parse.urlencode({"query": query})
    url = f"{base_url.rstrip('/')}/api/admin/knowledge/rag-diagnostic?{params}"
    started = time.perf_counter()
    with urllib.request.urlopen(url, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    payload["_latency_ms"] = round((time.perf_counter() - started) * 1000, 2)
    return payload


def citation_correct(item: dict[str, Any], result: dict[str, Any]) -> bool:
    if item.get("should_refuse"):
        return True
    if not result.get("grounded"):
        return False
    result_text = json.dumps(result, ensure_ascii=False).lower()
    expected_sources = [str(s).lower() for s in item.get("expected_sources", []) if str(s).strip()]
    expected_terms = [str(t).lower() for t in item.get("expected_terms", []) if str(t).strip()]
    if expected_sources:
        return any(source in result_text for source in expected_sources)
    if not expected_terms:
        return bool(result.get("grounded"))
    return sum(1 for term in expected_terms if term in result_text) >= max(1, math.ceil(len(expected_terms) * 0.4))


def faithfulness_score(item: dict[str, Any], result: dict[str, Any]) -> float:
    reported = result.get("faithfulnessScore")
    if isinstance(reported, (int, float)):
        return max(0.0, min(1.0, float(reported)))

    citations = result.get("citations") or []
    evidence_parts = [result.get("contextPreview") or ""]
    for citation in citations:
        if isinstance(citation, dict):
            evidence_parts.append(str(citation.get("excerpt") or citation.get("preview") or ""))
        else:
            evidence_parts.append(str(citation))
    evidence = "\n".join(evidence_parts)
    return overlap_score(str(item.get("gold_answer", "")), evidence)


def refusal_correct(item: dict[str, Any], result: dict[str, Any]) -> bool:
    if not item.get("should_refuse"):
        return True
    status = str(result.get("status") or result.get("retrievalStatus") or "").upper()
    context_preview = str(result.get("contextPreview") or "").strip()
    if any(flag in status for flag in ("NO_EVIDENCE", "EMPTY", "TIMEOUT", "FAILED", "FALLBACK")):
        return True
    return not bool(result.get("grounded")) or not context_preview


def top_k_recall_correct(item: dict[str, Any], result: dict[str, Any]) -> bool:
    if item.get("should_refuse"):
        return True
    result_text = json.dumps(result, ensure_ascii=False).lower()
    expected_sources = [str(s).lower() for s in item.get("expected_sources", []) if str(s).strip()]
    expected_terms = [str(t).lower() for t in item.get("expected_terms", []) if str(t).strip()]
    if expected_sources:
        return any(source in result_text for source in expected_sources)
    if expected_terms:
        return any(term in result_text for term in expected_terms)
    return bool(result.get("grounded"))


def bm25_fallback_hit(result: dict[str, Any]) -> bool:
    result_text = json.dumps(result, ensure_ascii=False).lower()
    return ("bm25" in result_text or "fallback" in result_text) and bool(result.get("grounded"))


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(len(ordered) * pct) - 1))
    return round(ordered[index], 2)


def evaluate(
    base_url: str,
    eval_set: list[dict[str, Any]],
    timeout: int,
    *,
    dataset_path: Path,
) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    citation_scores: list[float] = []
    faithfulness_scores: list[float] = []
    refusal_scores: list[float] = []
    top_k_scores: list[float] = []
    bm25_fallback_scores: list[float] = []
    unsupported_refusal_scores: list[float] = []
    latencies: list[float] = []
    failures = 0

    for item in eval_set:
        try:
            result = retrieve(base_url, item["query"], timeout)
            latency = float(result.get("_latency_ms", 0.0))
            latencies.append(latency)
            citation = citation_correct(item, result)
            faithfulness = faithfulness_score(item, result)
            refusal = refusal_correct(item, result)
            top_k = top_k_recall_correct(item, result)
            bm25_hit = bm25_fallback_hit(result)
            citation_scores.append(1.0 if citation else 0.0)
            faithfulness_scores.append(faithfulness)
            refusal_scores.append(1.0 if refusal else 0.0)
            top_k_scores.append(1.0 if top_k else 0.0)
            bm25_fallback_scores.append(1.0 if bm25_hit else 0.0)
            if item.get("should_refuse"):
                unsupported_refusal_scores.append(1.0 if refusal else 0.0)
            rows.append({
                "id": item["id"],
                "query": item["query"],
                "should_refuse": item.get("should_refuse", False),
                "citation_correct": citation,
                "answer_faithfulness": round(faithfulness, 4),
                "refusal_correct": refusal,
                "top_k_recall": top_k,
                "bm25_fallback_hit": bm25_hit,
                "latency_ms": latency,
                "status": result.get("status") or result.get("retrievalStatus"),
                "grounded": result.get("grounded"),
            })
        except Exception as exc:  # noqa: BLE001
            failures += 1
            citation_scores.append(0.0)
            faithfulness_scores.append(0.0)
            refusal_scores.append(0.0)
            top_k_scores.append(0.0)
            bm25_fallback_scores.append(0.0)
            if item.get("should_refuse"):
                unsupported_refusal_scores.append(0.0)
            rows.append({
                "id": item.get("id"),
                "query": item.get("query"),
                "should_refuse": item.get("should_refuse", False),
                "error": str(exc),
                "citation_correct": False,
                "answer_faithfulness": 0.0,
                "refusal_correct": False,
                "top_k_recall": False,
                "bm25_fallback_hit": False,
                "latency_ms": None,
            })

    summary = {
        "evaluated_at": datetime.now().isoformat(timespec="seconds"),
        "backend": base_url,
        "dataset_path": str(dataset_path),
        "dataset_count": len(eval_set),
        "dataset_sha256": file_sha256(dataset_path),
        "git_commit": git_commit(),
        "queries": len(eval_set),
        "failures": failures,
        "citation_correctness": round(statistics.fmean(citation_scores), 4) if citation_scores else 0.0,
        "answer_faithfulness": round(statistics.fmean(faithfulness_scores), 4) if faithfulness_scores else 0.0,
        "answer_faithfulness_proxy": True,
        "refusal_correctness": round(statistics.fmean(refusal_scores), 4) if refusal_scores else 0.0,
        "top_k_recall": round(statistics.fmean(top_k_scores), 4) if top_k_scores else 0.0,
        "bm25_fallback_hit_rate": round(statistics.fmean(bm25_fallback_scores), 4) if bm25_fallback_scores else 0.0,
        "unsupported_query_refusal_rate": round(statistics.fmean(unsupported_refusal_scores), 4)
        if unsupported_refusal_scores else 1.0,
        "avg_latency_ms": round(statistics.fmean(latencies), 2) if latencies else 0.0,
        "p95_latency_ms": percentile(latencies, 0.95),
        "max_latency_ms": round(max(latencies), 2) if latencies else 0.0,
    }
    return {"summary": summary, "rows": rows}


def assess_quality(
    payload: dict[str, Any],
    thresholds: QualityThresholds,
) -> dict[str, Any]:
    summary = payload["summary"]
    checks = [
        ("failures", summary["failures"] <= thresholds.max_failures,
         summary["failures"], f"<= {thresholds.max_failures}"),
        ("citation_correctness", summary["citation_correctness"] >= thresholds.min_citation_correctness,
         summary["citation_correctness"], f">= {thresholds.min_citation_correctness}"),
        ("answer_faithfulness", summary["answer_faithfulness"] >= thresholds.min_answer_faithfulness,
         summary["answer_faithfulness"], f">= {thresholds.min_answer_faithfulness}"),
        ("refusal_correctness", summary["refusal_correctness"] >= thresholds.min_refusal_correctness,
         summary["refusal_correctness"], f">= {thresholds.min_refusal_correctness}"),
        ("top_k_recall", summary["top_k_recall"] >= thresholds.min_top_k_recall,
         summary["top_k_recall"], f">= {thresholds.min_top_k_recall}"),
        (
            "unsupported_query_refusal_rate",
            summary["unsupported_query_refusal_rate"] >= thresholds.min_unsupported_query_refusal_rate,
            summary["unsupported_query_refusal_rate"],
            f">= {thresholds.min_unsupported_query_refusal_rate}",
        ),
        ("p95_latency_ms", summary["p95_latency_ms"] <= thresholds.max_p95_latency_ms,
         summary["p95_latency_ms"], f"<= {thresholds.max_p95_latency_ms}"),
    ]
    result = {
        "passed": all(passed for _, passed, _, _ in checks),
        "thresholds": asdict(thresholds),
        "checks": [
            {"metric": metric, "passed": passed, "actual": actual, "expected": expected}
            for metric, passed, actual, expected in checks
        ],
    }
    payload["quality_gate"] = result
    return result


def render_markdown_report(payload: dict[str, Any]) -> str:
    summary = payload["summary"]
    rows = payload["rows"]
    lines = [
        "# VisionaryTutor RAG Evaluation Report",
        "",
        "## Dataset",
        f"- source: `{summary['dataset_path']}`",
        f"- questions: **{summary['dataset_count']}**",
        f"- evaluated_at: `{summary['evaluated_at']}`",
        f"- backend: `{summary['backend']}`",
        f"- git_commit: `{summary['git_commit']}`",
        f"- dataset_sha256: `{summary['dataset_sha256']}`",
        "",
        "## Metrics",
        "",
        f"- citation_correctness: **{summary['citation_correctness']}**",
        f"- answer_faithfulness: **{summary['answer_faithfulness']}** (proxy metric)",
        f"- refusal_correctness: **{summary['refusal_correctness']}**",
        f"- top_k_recall: **{summary['top_k_recall']}**",
        f"- bm25_fallback_hit_rate: **{summary['bm25_fallback_hit_rate']}**",
        f"- unsupported_query_refusal_rate: **{summary['unsupported_query_refusal_rate']}**",
        f"- avg_latency_ms: **{summary['avg_latency_ms']} ms**",
        f"- p95_latency_ms: **{summary['p95_latency_ms']} ms**",
        f"- max_latency_ms: **{summary['max_latency_ms']} ms**",
        f"- failures: **{summary['failures']}**",
        f"- quality_gate: **{'PASS' if payload.get('quality_gate', {}).get('passed') else 'FAIL'}**",
        "",
        "Faithfulness is a deterministic proxy based on backend `faithfulnessScore` or lexical overlap between gold answers and retrieved evidence.",
        "",
        "## Rows",
        "",
        "| # | ID | Query | Citation | Faithfulness | Refusal | Top-K | BM25 fallback | Latency(ms) |",
        "|---:|---|---|---:|---:|---:|---:|---:|---:|",
    ]
    for idx, row in enumerate(rows, start=1):
        query = str(row.get("query", "")).replace("|", "\\|")
        lines.append(
            f"| {idx} | {row.get('id', '')} | {query} | "
            f"{'Y' if row.get('citation_correct') else 'N'} | "
            f"{row.get('answer_faithfulness', 0)} | "
            f"{'Y' if row.get('refusal_correct') else 'N'} | "
            f"{'Y' if row.get('top_k_recall') else 'N'} | "
            f"{'Y' if row.get('bm25_fallback_hit') else 'N'} | "
            f"{row.get('latency_ms', '-')} |"
        )
    return "\n".join(lines) + "\n"


def write_reports(payload: dict[str, Any], output_dir: Path) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    json_path = output_dir / f"rag_eval_{stamp}.json"
    md_path = output_dir / f"rag_eval_{stamp}.md"
    json_payload = json.dumps(payload, ensure_ascii=False, indent=2)
    markdown = render_markdown_report(payload)
    json_path.write_text(json_payload, encoding="utf-8")
    md_path.write_text(markdown, encoding="utf-8")
    if payload.get("quality_gate", {}).get("passed"):
        (output_dir / "rag_eval_latest.json").write_text(json_payload, encoding="utf-8")
        (output_dir / "rag_eval_latest.md").write_text(markdown, encoding="utf-8")
    return json_path, md_path


def run_evaluation(
    base_url: str,
    eval_path: Path,
    timeout: int,
    *,
    write_reports: bool = False,
    output_dir: Path | None = None,
    thresholds: QualityThresholds | None = None,
    preflight: bool = True,
) -> dict[str, Any]:
    if preflight:
        check_backend_health(base_url, timeout)
    eval_set = load_eval_set(eval_path)
    payload = evaluate(base_url, eval_set, timeout, dataset_path=eval_path)
    assess_quality(payload, thresholds or QualityThresholds.from_env())
    if write_reports:
        target_dir = output_dir or DEFAULT_REPORT_DIR
        json_path, md_path = write_reports_to_dir(payload, target_dir)
        payload["artifacts"] = {"json": str(json_path), "markdown": str(md_path)}
    return payload


def write_reports_to_dir(payload: dict[str, Any], output_dir: Path) -> tuple[Path, Path]:
    return write_reports(payload, output_dir)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--set", default=str(DEFAULT_SET.relative_to(PROJECT_ROOT)), dest="eval_path")
    parser.add_argument("--timeout", default=20, type=int)
    parser.add_argument("--write-reports", action="store_true")
    parser.add_argument("--output-dir", default=str(DEFAULT_REPORT_DIR.relative_to(PROJECT_ROOT)))
    args = parser.parse_args()

    eval_path = resolve_project_path(args.eval_path)
    output_dir = resolve_project_path(args.output_dir)
    try:
        payload = run_evaluation(
            args.base_url,
            eval_path,
            args.timeout,
            write_reports=args.write_reports,
            output_dir=output_dir,
        )
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        if not payload["quality_gate"]["passed"]:
            return 1
        return 0
    except EvaluationPreflightError as exc:
        print(json.dumps({"status": "PREFLIGHT_FAILED", "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
