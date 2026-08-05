import json
import unittest
from pathlib import Path

from run_evaluation import build_report, load_cases


class EvaluationRunnerTest(unittest.TestCase):
    def test_has_thirty_reproducible_cases_and_recomputable_metrics(self):
        cases, digest = load_cases(Path(__file__).with_name("cases.json"))
        report = build_report(cases, digest)
        metrics = report["metrics"]
        self.assertGreaterEqual(metrics["totalCases"], 30)
        self.assertEqual(metrics["passedCases"] + metrics["failedCases"], metrics["totalCases"])
        self.assertEqual(report["run"]["casesSha256"], digest)
        self.assertEqual(report["failures"], [])


if __name__ == "__main__":
    unittest.main()
