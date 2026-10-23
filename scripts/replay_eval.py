#!/usr/bin/env python3
"""Replay corpus/labels.jsonl through local Kafka and report raw evaluation counts.

Use --offline in CI to validate the fixed fixture without Kafka or AWS.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

RULES = ("R1", "R2", "R3", "R4")


def load(path: Path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def event(item):
    return {"schemaVersion": 1, "eventId": item["eventId"], "tenantId": "eval", "source": "eval",
            "repo": item["repo"], "prNumber": item["prNumber"], "commitSha": item["commitSha"],
            "changedFiles": item["changedFiles"], "logExcerpt": item["logExcerpt"],
            "receivedAt": datetime.now(timezone.utc).isoformat()}


def run(cmd, stdin=None):
    return subprocess.run(cmd, input=stdin, text=True, capture_output=True, check=True).stdout


def replay(items, timeout_ms):
    lines = "".join(f"{x['repo']}#{x['prNumber']}\t{json.dumps(event(x), separators=(',', ':'))}\n" for x in items)
    producer = ["docker", "compose", "--profile", "ec2", "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
                "--bootstrap-server", "localhost:9092", "--topic", "ci.events",
                "--property", "parse.key=true", "--property", "key.separator=\t"]
    run(producer, lines)
    time.sleep(1)
    consumer = ["docker", "compose", "--profile", "ec2", "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh",
                "--bootstrap-server", "localhost:9092", "--topic", "ci.verdicts", "--from-beginning",
                "--timeout-ms", str(timeout_ms)]
    raw = run(consumer)
    wanted = {x["eventId"] for x in items}
    return [json.loads(line) for line in raw.splitlines() if line.strip() and json.loads(line).get("eventId") in wanted]


def report(items, verdicts, label, baseline=None):
    by_id = {v.get("eventId"): v for v in verdicts}
    print(f"run={label}")
    print(f"events={len(items)} verdicts={len(by_id)} missing={len(items) - len(by_id)}")
    for rule in RULES:
        tp = fp = fn = 0
        for item in items:
            expected = rule in item["rules"]
            found = any(f.get("ruleId") == rule for f in by_id.get(item["eventId"], {}).get("findings", []))
            tp += expected and found
            fp += (not expected) and found
            fn += expected and (not found)
        print(f"{rule}: TP={tp} FP={fp} FN={fn} precision={tp}/{tp + fp} recall={tp}/{tp + fn}")
    clean = [x for x in items if not x["rules"]]
    clean_fp = sum(any(f.get("ruleId") in RULES for f in by_id.get(x["eventId"], {}).get("findings", [])) for x in clean)
    print(f"clean_events={len(clean)} clean_false_positives={clean_fp}")
    modes = Counter(v.get("decidedBy", "MISSING") for v in by_id.values())
    print("modes=" + ",".join(f"{key}:{value}" for key, value in sorted(modes.items())))
    changes = []
    for item in items:
        actual = by_id.get(item["eventId"], {}).get("verdict")
        before = (baseline or {}).get(item["eventId"], {}).get("verdict")
        expected = item.get("expected")
        if actual and before and actual != before:
            changes.append((item["eventId"], before, expected, actual, actual == expected))
    print(f"verdict_changes={len(changes)}")
    for event_id, before, expected, actual, correct in changes:
        print(f"verdict_change eventId={event_id} rules={before} expected={expected} agent={actual} correct={str(correct).lower()}")
    latencies = sorted(v["latencyMs"] for v in by_id.values() if isinstance(v.get("latencyMs"), int))
    if latencies:
        p50 = latencies[(len(latencies) - 1) * 50 // 100]
        p95 = latencies[(len(latencies) - 1) * 95 // 100]
        print(f"verdict_latency_ms_count={len(latencies)} p50={p50} p95={p95}")
    else:
        print("verdict_latency_ms_count=0 p50=NA p95=NA")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", default="corpus/labels.jsonl", type=Path)
    parser.add_argument("--split", choices=("DEV", "TEST", "REAL"), default="TEST")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--timeout-ms", type=int, default=15000)
    parser.add_argument("--label", default="unknown", help="rules-only or agent run label")
    parser.add_argument("--baseline", type=Path, help="JSON verdict map from the rules-only run")
    parser.add_argument("--json-out", type=Path, help="write matched verdicts for later comparison")
    args = parser.parse_args()
    items = [x for x in load(args.corpus) if x["split"] == args.split]
    if not items:
        raise SystemExit("no corpus events selected")
    if args.offline:
        print(f"offline_fixture_events={len(items)}")
        print(f"offline_fixture_clean={sum(not x['rules'] for x in items)}")
        print("offline_fixture_rule_events=" + ",".join(f"{rule}:{sum(rule in x['rules'] for x in items)}" for rule in RULES))
        print("offline mode: no Kafka publish, no AWS calls")
        return
    verdicts = replay(items, args.timeout_ms)
    if args.json_out:
        args.json_out.write_text(json.dumps({v["eventId"]: v for v in verdicts}, indent=2) + "\n")
    baseline = json.loads(args.baseline.read_text()) if args.baseline else None
    report(items, verdicts, args.label, baseline)


if __name__ == "__main__":
    main()
