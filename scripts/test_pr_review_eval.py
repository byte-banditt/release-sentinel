import unittest

from pr_review_eval import score


class ScoreTest(unittest.TestCase):
    def setUp(self):
        self.labels = [
            {"id": "bug", "defect_present": True, "expected_category": "SECURITY",
             "expected_severity": "HIGH", "changed_file": "src/A.java", "changed_lines": [4, 4],
             "evidence_anchor": "authorize(id)", "source": "authorize(id);"},
            {"id": "clean", "defect_present": False, "category": "BENIGN", "source": "return name;"},
        ]
        self.predictions = [
            {"id": "bug", "abstained": False, "findings": [{"file_path": "src/A.java", "start_line": 4,
             "end_line": 4, "severity": "HIGH", "category": "SECURITY", "evidence": ["authorize(id);"]}]},
            {"id": "clean", "abstained": True, "findings": []},
        ]

    def test_complete_predictions_report_quality_and_localization(self):
        result = score(self.labels, self.predictions)
        self.assertEqual(result["finding_precision_recall"]["f1"], 1.0)
        self.assertEqual(result["file_localization_accuracy"], 1.0)
        self.assertEqual(result["abstention_accuracy"], 1.0)

    def test_incomplete_or_extra_predictions_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly match"):
            score(self.labels, self.predictions[:1])
        with self.assertRaisesRegex(ValueError, "exactly match"):
            score(self.labels, self.predictions + [{"id": "unknown", "findings": []}])


if __name__ == "__main__":
    unittest.main()
