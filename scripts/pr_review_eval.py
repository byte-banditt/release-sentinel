#!/usr/bin/env python3
"""Score saved PR-review predictions against eval/pr_review/labels.jsonl.

This deterministic scorer never calls an LLM and never executes generated code.
Predictions must be captured separately with provider/model/prompt metadata.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_LABELS = Path("eval/pr_review/labels.jsonl")


def read_jsonl(path: Path):
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    ids = [row.get("id") for row in rows]
    if None in ids or len(set(ids)) != len(ids):
        raise ValueError(f"{path}: missing or duplicate id")
    return rows


def ratios(tp: int, fp: int, fn: int):
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    return {"tp": tp, "fp": fp, "fn": fn, "precision": precision, "recall": recall,
            "f1": 2 * precision * recall / (precision + recall) if precision is not None and recall is not None and precision + recall else None}


def score(labels, predictions):
    by_id = {p["id"]: p for p in predictions}
    label_ids = {x["id"] for x in labels}
    if set(by_id) != label_ids:
        missing = sorted(label_ids - set(by_id))
        extra = sorted(set(by_id) - label_ids)
        raise ValueError(f"prediction ids must exactly match selected labels (missing={missing}, extra={extra})")
    tp = fp = fn = benign_fp = localized_file = localized_line = evidence_supported = severity_correct = 0
    unsupported = duplicate_count = 0
    seeded = [x for x in labels if x["defect_present"]]
    benign = [x for x in labels if not x["defect_present"]]
    finding_count = 0
    severity = Counter()
    categories = {}
    abstention_correct = 0
    for gold in labels:
        pred = by_id.get(gold["id"], {})
        findings = pred.get("findings", [])
        finding_count += len(findings)
        signatures = [(f.get("file_path"), f.get("start_line"), f.get("end_line"), f.get("severity"), f.get("title")) for f in findings]
        duplicate_count += len(signatures) - len(set(signatures))
        unsupported += sum(not any(isinstance(e, str) and e.strip() and e.strip() in gold["source"] for e in f.get("evidence", [])) for f in findings)
        if bool(pred.get("abstained")) == (not gold["defect_present"]):
            abstention_correct += 1
        expected_category = gold.get("expected_category") if gold["defect_present"] else None
        matched_category = False
        for finding in findings:
            predicted_category = finding.get("category") or "UNCLASSIFIED"
            bucket = categories.setdefault(predicted_category, [0, 0, 0])
            if expected_category == predicted_category and not matched_category:
                bucket[0] += 1
                matched_category = True
            else:
                bucket[1] += 1
        if gold["defect_present"]:
            expected_bucket = categories.setdefault(expected_category, [0, 0, 0])
            if not matched_category:
                expected_bucket[2] += 1
            if not findings:
                fn += 1
                continue
            # One seeded defect is the unit. First actionable finding is scored.
            hit = findings[0]
            tp += 1
            path_ok = hit.get("file_path") == gold["changed_file"]
            localized_file += path_ok
            lo, hi = gold["changed_lines"]
            localized_line += path_ok and isinstance(hit.get("start_line"), int) and isinstance(hit.get("end_line"), int) and hit["start_line"] <= hi and hit["end_line"] >= lo
            evidence = " ".join(str(x) for x in hit.get("evidence", []))
            evidence_supported += bool(gold["evidence_anchor"] and gold["evidence_anchor"] in evidence and gold["evidence_anchor"] in gold["source"])
            severity[(gold["expected_severity"], hit.get("severity"))] += 1
            severity_correct += hit.get("severity") == gold["expected_severity"]
            fp += max(0, len(findings) - 1)
        else:
            benign_fp += bool(findings)
            fp += len(findings)
    abstained = sum(bool(by_id.get(x["id"], {}).get("abstained")) for x in labels)
    return {
        "examples": len(labels), "seeded_defects": len(seeded), "benign_examples": len(benign),
        "actionable_findings": finding_count,
        "finding_precision_recall": ratios(tp, fp, fn),
        "false_negative_count": fn,
        "per_category_precision_recall": {name: ratios(values[0], values[1], values[2]) for name, values in sorted(categories.items())},
        "benign_false_positive_rate": benign_fp / len(benign) if benign else None,
        "file_localization_accuracy": localized_file / len(seeded) if seeded else None,
        "changed_line_localization_accuracy": localized_line / len(seeded) if seeded else None,
        "evidence_anchor_support_rate": evidence_supported / len(seeded) if seeded else None,
        "unsupported_finding_rate": unsupported / finding_count if finding_count else 0.0,
        "duplicate_finding_rate": duplicate_count / finding_count if finding_count else 0.0,
        "severity_accuracy": severity_correct / len(seeded) if seeded else None,
        "severity_counts_expected_actual": [{"expected": a, "actual": b, "count": n} for (a, b), n in sorted(severity.items())],
        "abstention_rate": abstained / len(labels) if labels else None,
        "abstention_accuracy": abstention_correct / len(labels) if labels else None,
        "missing_predictions": [],
    }


def test_generation_score(predictions):
    """Report observed test-generation execution facts without inferring quality."""
    rows = [p.get("test_generation") for p in predictions if p.get("test_generation") is not None]
    observed = [r for r in rows if all(k in r for k in ("syntax_compiles", "discovered", "passed_on_fixed", "fails_on_seeded_bug"))]
    if not rows:
        return {"observations": 0, "status": "NOT_MEASURED"}
    return {
        "observations": len(rows), "complete_observations": len(observed),
        "syntax_compilation_rate": sum(bool(r["syntax_compiles"]) for r in observed) / len(observed) if observed else None,
        "test_discovery_rate": sum(bool(r["discovered"]) for r in observed) / len(observed) if observed else None,
        "passes_corrected_implementation_rate": sum(bool(r["passed_on_fixed"]) for r in observed) / len(observed) if observed else None,
        "reproduces_seeded_defect_rate": sum(bool(r["fails_on_seeded_bug"]) for r in observed) / len(observed) if observed else None,
        "incomplete_observations": len(rows) - len(observed),
        "note": "Compilation and behavioral detection are separate measurements; unobserved results remain null.",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--labels", type=Path, default=DEFAULT_LABELS)
    parser.add_argument("--predictions", type=Path, required=True, help="JSONL raw predictions with run metadata")
    parser.add_argument("--split", default="DEV")
    parser.add_argument("--output", type=Path, help="write complete scored record")
    args = parser.parse_args()
    labels = [row for row in read_jsonl(args.labels) if row["split"] == args.split]
    if not labels:
        raise SystemExit("no labels selected")
    predictions = read_jsonl(args.predictions)
    meta_path = args.predictions.with_suffix(".meta.json")
    metadata = json.loads(meta_path.read_text()) if meta_path.exists() else {}
    required = ("repository_commit_sha", "provider", "model", "model_configuration", "prompt_version")
    missing_metadata = [key for key in required if key not in metadata]
    if missing_metadata:
        raise SystemExit(f"prediction metadata missing required fields: {', '.join(missing_metadata)}")
    result = {
        "dataset_version": labels[0]["dataset_version"],
        "split": args.split,
        "run_timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "prediction_file": str(args.predictions),
        "metadata": metadata,
        "review_metrics": score(labels, predictions),
        "test_generation_metrics": test_generation_score(predictions),
        "raw_predictions": predictions,
        "ground_truth": labels,
    }
    rendered = json.dumps(result, indent=2, sort_keys=True)
    if args.output:
        args.output.write_text(rendered + "\n")
    print(json.dumps({k: v for k, v in result.items() if k not in ("raw_predictions", "ground_truth")}, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
