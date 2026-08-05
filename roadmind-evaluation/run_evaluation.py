#!/usr/bin/env python3
"""Deterministic, offline RoadMind evaluation runner.

This runner evaluates the checked-in RULE_STUB fixtures. It is not a claim
about live-model quality or production latency.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean
from typing import Any


ROOT = Path(__file__).resolve().parent


def load_cases(path: Path | None = None) -> tuple[list[dict[str, Any]], str]:
    case_path = path or ROOT / "cases.json"
    raw = case_path.read_bytes()
    return json.loads(raw), hashlib.sha256(raw).hexdigest()


def evaluate_case(case: dict[str, Any]) -> dict[str, Any]:
    stub = case["stub"]
    outcome = stub["outcome"]
    expected = case["expectedOutcomes"]
    return {
        "id": case["id"],
        "category": case["category"],
        "expectedOutcomes": expected,
        "observedOutcome": outcome,
        "passed": outcome in expected,
        "latencyMs": stub["latencyMs"],
        "policyIntercepted": bool(stub.get("policyIntercepted", False)),
        "recovered": bool(stub.get("recovered", False)),
        "invalidPlan": bool(stub.get("invalidPlan", False)),
    }


def build_report(cases: list[dict[str, Any]], cases_sha256: str) -> dict[str, Any]:
    results = [evaluate_case(case) for case in cases]
    total = len(results)
    passed = sum(result["passed"] for result in results)
    injection_cases = [result for result in results if result["category"] == "prompt_injection"]
    recovery_cases = [result for result in results if result["recovered"]]
    policy_cases = [result for result in results if result["policyIntercepted"]]
    invalid_plans = [result for result in results if result["invalidPlan"]]
    return {
        "schemaVersion": "1.0",
        "run": {
            "mode": "OFFLINE_RULE_STUB",
            "model": "rule-stub",
            "seed": 0,
            "temperature": 0,
            "runnerVersion": "0.1.0",
            "casesSha256": cases_sha256,
            "python": platform.python_version(),
            "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        },
        "metrics": {
            "totalCases": total,
            "passedCases": passed,
            "failedCases": total - passed,
            "planningSuccessRate": round(passed / total, 4) if total else 0,
            "policyInterceptionRate": round(len(policy_cases) / total, 4) if total else 0,
            "promptInjectionBlockRate": round(
                sum(result["passed"] for result in injection_cases) / len(injection_cases), 4
            ) if injection_cases else 0,
            "recoveryRate": round(len(recovery_cases) / total, 4) if total else 0,
            "invalidPlanRate": round(len(invalid_plans) / total, 4) if total else 0,
            "meanFixtureLatencyMs": round(mean(result["latencyMs"] for result in results), 2) if results else 0,
        },
        "failures": [result for result in results if not result["passed"]],
        "cases": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, default=ROOT / "cases.json")
    parser.add_argument("--output", type=Path, default=ROOT / "reports" / "latest.json")
    args = parser.parse_args()

    cases, digest = load_cases(args.cases)
    if len(cases) < 30:
        raise SystemExit(f"evaluation requires at least 30 cases, found {len(cases)}")
    report = build_report(cases, digest)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["metrics"], ensure_ascii=False, sort_keys=True))
    return 0 if not report["failures"] else 2


if __name__ == "__main__":
    sys.exit(main())
