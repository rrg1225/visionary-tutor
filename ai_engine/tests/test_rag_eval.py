from __future__ import annotations

import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, patch
from urllib.error import URLError

import pytest


ENGINE_DIR = Path(__file__).resolve().parent.parent
if str(ENGINE_DIR) not in sys.path:
    sys.path.insert(0, str(ENGINE_DIR))

from rag_eval import (  # noqa: E402
    EvaluationPreflightError,
    QualityThresholds,
    assess_quality,
    check_backend_health,
    write_reports,
)


def payload(**overrides):
    summary = {
        "evaluated_at": "2026-07-12T00:00:00",
        "backend": "http://127.0.0.1:8080",
        "dataset_path": "gold.jsonl",
        "dataset_count": 55,
        "dataset_sha256": "abc123",
        "git_commit": "deadbeef",
        "queries": 55,
        "failures": 0,
        "citation_correctness": 0.90,
        "answer_faithfulness": 0.85,
        "refusal_correctness": 0.93,
        "top_k_recall": 0.91,
        "bm25_fallback_hit_rate": 0.0,
        "unsupported_query_refusal_rate": 0.93,
        "avg_latency_ms": 700.0,
        "p95_latency_ms": 1800.0,
        "max_latency_ms": 2200.0,
    }
    summary.update(overrides)
    return {"summary": summary, "rows": []}


def test_quality_gate_rejects_transport_failures_even_when_other_metrics_pass():
    result_payload = payload(failures=1)

    gate = assess_quality(result_payload, QualityThresholds())

    assert gate["passed"] is False
    assert next(item for item in gate["checks"] if item["metric"] == "failures")["passed"] is False


def test_quality_gate_rejects_metric_regression():
    result_payload = payload(top_k_recall=0.50)

    gate = assess_quality(result_payload, QualityThresholds())

    assert gate["passed"] is False
    assert next(item for item in gate["checks"] if item["metric"] == "top_k_recall")["passed"] is False


def test_failed_report_does_not_replace_latest_passing_baseline():
    result_payload = payload(failures=55, citation_correctness=0.0)
    assess_quality(result_payload, QualityThresholds())

    with TemporaryDirectory() as temp_dir:
        output_dir = Path(temp_dir)
        latest_json = output_dir / "rag_eval_latest.json"
        latest_markdown = output_dir / "rag_eval_latest.md"
        latest_json.write_text("passing-json", encoding="utf-8")
        latest_markdown.write_text("passing-markdown", encoding="utf-8")

        timestamped_json, timestamped_markdown = write_reports(result_payload, output_dir)

        assert timestamped_json.exists()
        assert timestamped_markdown.exists()
        assert latest_json.read_text(encoding="utf-8") == "passing-json"
        assert latest_markdown.read_text(encoding="utf-8") == "passing-markdown"


def test_preflight_surfaces_backend_unavailability():
    with patch("rag_eval.urllib.request.urlopen", side_effect=URLError("connection refused")):
        with pytest.raises(EvaluationPreflightError, match="backend is unavailable"):
            check_backend_health("http://127.0.0.1:8080", 1)


def test_preflight_rejects_reachable_backend_without_rag_readiness():
    response = MagicMock()
    response.status = 200
    response.read.return_value = b'{"status":"UP","ragHaAvailable":false}'
    response.__enter__.return_value = response

    with patch("rag_eval.urllib.request.urlopen", return_value=response):
        with pytest.raises(EvaluationPreflightError, match="no RAG backend is ready"):
            check_backend_health("http://127.0.0.1:8080", 1)
