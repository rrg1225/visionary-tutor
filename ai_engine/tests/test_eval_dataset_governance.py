import json
import sys
from pathlib import Path

ENGINE_DIR = Path(__file__).resolve().parent.parent
if str(ENGINE_DIR) not in sys.path:
    sys.path.insert(0, str(ENGINE_DIR))

from agent_replay_eval import evaluate_case, load_cases
from rag_dataset_audit import audit_rows


def test_agent_replay_cases_cover_each_specialist():
    path = Path(__file__).parents[1] / "eval_sets" / "agent_replay_cases.jsonl"
    cases = load_cases(path)
    assert len(cases) == 7
    assert {case["agent"] for case in cases} == {
        "DocAgent", "QuizAgent", "MindMapAgent", "PathAgent", "CodingAgent",
        "ReadingAgent", "VisualizationAgent",
    }


def test_agent_replay_evaluator_rejects_demo_and_unversioned_content():
    case = {
        "resource_type": "HANDOUT",
        "required_markers": ["HANDOUT"],
        "forbidden_markers": ["DEMO_MODE"],
    }
    response = {
        "artifacts": [{"artifactType": "HANDOUT", "contentJson": json.dumps({"origin": "DEMO"})}],
        "mode": "DEMO_MODE",
    }
    failures = evaluate_case(case, response)
    assert "contentJson schema_version is not 1.0" in failures
    assert "live replay returned DEMO content" in failures
    assert "forbidden marker present: DEMO_MODE" in failures


def test_rag_audit_requires_scale_teacher_review_and_attack_coverage():
    rows = [{
        "id": "one", "query": "What is padding?", "gold_answer": "Padding extends input borders.",
        "expected_terms": ["padding"], "expected_sources": ["cnn.md"], "should_refuse": False,
    }]
    result = audit_rows(rows, minimum_cases=150)
    assert result["errors"] == []
    assert result["strict_ready"] is False
    assert any("target is at least 150" in warning for warning in result["warnings"])
    assert any("prompt_injection" in warning for warning in result["warnings"])
