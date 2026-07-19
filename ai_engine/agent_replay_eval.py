"""Replay inspectable per-agent generation scenarios against a real backend."""

from __future__ import annotations

import argparse
import json
import os
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_CASES = Path(__file__).parent / "eval_sets" / "agent_replay_cases.jsonl"


def load_cases(path: Path) -> list[dict[str, Any]]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    required = {"id", "agent", "resource_type", "topic", "required_markers", "forbidden_markers"}
    ids: set[str] = set()
    for index, row in enumerate(rows, start=1):
        missing = required.difference(row)
        if missing:
            raise ValueError(f"case {index} missing fields: {sorted(missing)}")
        if row["id"] in ids:
            raise ValueError(f"duplicate case id: {row['id']}")
        ids.add(row["id"])
    return rows


def evaluate_case(case: dict[str, Any], response: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    artifacts = response.get("artifacts") or []
    matching = [item for item in artifacts if item.get("artifactType") == case["resource_type"]]
    if not matching:
        failures.append(f"missing artifact type {case['resource_type']}")
    searchable = json.dumps(response, ensure_ascii=False)
    for marker in case["required_markers"]:
        if marker not in searchable:
            failures.append(f"required marker absent: {marker}")
    for marker in case["forbidden_markers"]:
        if marker in searchable:
            failures.append(f"forbidden marker present: {marker}")
    for artifact in matching:
        try:
            envelope = json.loads(artifact.get("contentJson") or "{}")
        except json.JSONDecodeError:
            failures.append("contentJson is malformed")
            continue
        if envelope.get("schema_version") != "1.0":
            failures.append("contentJson schema_version is not 1.0")
        if envelope.get("origin") == "DEMO":
            failures.append("live replay returned DEMO content")
    return failures


def post_json(url: str, token: str, payload: dict[str, Any], timeout: int) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--base-url", default=os.getenv("BACKEND_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--token", default=os.getenv("AGENT_REPLAY_TOKEN"))
    parser.add_argument("--session-id", type=int, default=os.getenv("AGENT_REPLAY_SESSION_ID"))
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    if not args.token or not args.session_id:
        raise SystemExit("AGENT_REPLAY_TOKEN and AGENT_REPLAY_SESSION_ID are required")

    failures: list[str] = []
    for case in load_cases(args.cases):
        response = post_json(
            f"{args.base_url.rstrip('/')}/api/resources/generate",
            args.token,
            {
                "learningSessionId": args.session_id,
                "requestId": f"agent-replay-{case['id']}",
                "topic": case["topic"],
                "learnerProfileSnapshot": case.get("profile", ""),
                "weakPointsSnapshot": case.get("weak_points", ""),
                "resourceTypes": [case["resource_type"]],
            },
            args.timeout,
        )
        failures.extend(f"{case['id']}: {failure}" for failure in evaluate_case(case, response))
    print(json.dumps({"cases": len(load_cases(args.cases)), "failures": failures}, ensure_ascii=False, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
