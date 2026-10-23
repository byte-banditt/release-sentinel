#!/usr/bin/env python3
"""Mine real git history into append-only release-sentinel evaluation labels.

Classification is based only on the parent-to-commit diff. This script never
imports or executes release-sentinel rules. Existing corpus lines are copied
unchanged; reruns skip an already recorded source commit.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

CONFIG = re.compile(r"\.(?:properties|ya?ml|yaml|conf|ini)$", re.I)
DEPENDENCY = re.compile(r"(?:^|/)(?:pom\.xml|package\.json|requirements\.txt|[^/]+requirements[^/]*)$", re.I)
TEST = re.compile(r"(?:^|/)(?:test|tests|__tests__|spec)(?:/|[^/]*test|[^/]*spec)|(?:Test|Tests|Spec)\.[^.]+$", re.I)
MIGRATION = re.compile(r"(?:^|/)(?:db/)?migrations?/|(?:^|/)(?:db/)?migration/|(?:^|/)(?:migrate|migration)[^/]*\.(?:sql|sql\.gz)$", re.I)
VERSION = re.compile(r"(?<![\w.])(?:[=:]\s*[\"']?|==\s*|<version>\s*)(\d+)(?:\.\d+){0,2}")
KEY = re.compile(r"^\s*[-+]?\s*([A-Za-z_][\w.-]*)\s*(?:=|:|\s+).*$")


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True, stderr=subprocess.DEVNULL)


def commits(repo: Path):
    fmt = "%H%x00%aI%x00%s"
    for row in git(repo, "log", "--no-merges", f"--format={fmt}").splitlines():
        commit, authored, subject = row.split("\x00", 2)
        yield commit, authored, subject


def files_and_patch(repo: Path, commit: str):
    name_status = git(repo, "diff-tree", "--no-commit-id", "--root", "-r", "--name-status", "--no-renames", commit)
    entries = []
    for line in name_status.splitlines():
        fields = line.split("\t")
        if len(fields) < 2:
            continue
        status = {"A": "added", "D": "deleted", "M": "modified"}.get(fields[0][0], "modified")
        entries.append((fields[-1], status))
    # Avoid reading benchmark/artifact blobs; classification only needs these
    # source-file families, while clean events still retain their real paths.
    relevant = [entry for entry in entries if CONFIG.search(entry[0]) or DEPENDENCY.search(entry[0]) or TEST.search(entry[0]) or MIGRATION.search(entry[0])]
    selected = relevant if relevant else entries[:25]
    paths = [path for path, _ in selected]
    if not paths:
        return []
    try:
        raw = git(repo, "show", "--format=", "--unified=3", commit, "--", *paths)
    except subprocess.CalledProcessError:
        raw = ""
    patches = {}
    for block in raw.split("diff --git ")[1:]:
        header, *rest = block.splitlines()
        match = re.search(r" b/(.*)$", header)
        if match:
            patches[match.group(1)] = "\n".join(rest)[:12000]
    return [{"path": path, "status": status, "patch": patches.get(path, "")} for path, status in selected]


def removed_config(files):
    for item in files:
        if not CONFIG.search(item["path"]):
            continue
        removed = {m.group(1) for line in item["patch"].splitlines() if line.startswith("-") and not line.startswith("---") if (m := KEY.match(line[1:]))}
        added = {m.group(1) for line in item["patch"].splitlines() if line.startswith("+") and not line.startswith("+++") if (m := KEY.match(line[1:]))}
        if removed - added:
            return True
    return False


def dependency_change(files):
    for item in files:
        if not DEPENDENCY.search(item["path"]):
            continue
        removed = [line[1:] for line in item["patch"].splitlines() if line.startswith("-") and not line.startswith("---")]
        added = [line[1:] for line in item["patch"].splitlines() if line.startswith("+") and not line.startswith("+++")]
        if not removed:
            continue
        if not added:
            return True
        old = [int(m.group(1)) for line in removed if (m := VERSION.search(line))]
        new = [int(m.group(1)) for line in added if (m := VERSION.search(line))]
        if old and new and max(new) > max(old):
            return True
    return False


def test_deleted(files):
    return any(item["status"] == "deleted" and TEST.search(item["path"]) for item in files)


def migration_without_rollback(files):
    added_migrations = [x for x in files if x["status"] == "added" and MIGRATION.search(x["path"])]
    if not added_migrations:
        return False
    rollback = any(re.search(r"(?:down|rollback|undo|revert)", x["path"], re.I) for x in files)
    return not rollback


def classify(files):
    rules = []
    if removed_config(files): rules.append("R1")
    if dependency_change(files): rules.append("R2")
    if test_deleted(files): rules.append("R3")
    if migration_without_rollback(files): rules.append("R4")
    return rules


def event(repo: Path, commit: str, authored: str, subject: str, files, rules, ordinal: int):
    when = datetime.fromisoformat(authored).astimezone(timezone.utc).isoformat()
    return {"eventId": f"real-{ordinal:03d}-{commit[:12]}", "split": "REAL", "provenance": "REAL",
            "sourceRepo": str(repo), "sourceCommit": commit, "expected": "BLOCK" if "R3" in rules else ("WARN" if rules else "PASS"),
            "rules": rules, "repo": repo.name, "prNumber": 0, "commitSha": commit,
            "changedFiles": files, "logExcerpt": subject, "receivedAt": when}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("repos", nargs="*", type=Path, default=[Path("../flash-sale-engine"), Path("../cve_rag")])
    parser.add_argument("--output", type=Path, default=Path("corpus/labels.jsonl"))
    parser.add_argument("--faults", type=int, default=10)
    args = parser.parse_args()
    existing = [json.loads(line) for line in args.output.read_text().splitlines() if line.strip()] if args.output.exists() else []
    known = {(x.get("sourceRepo"), x.get("sourceCommit")) for x in existing if x.get("provenance") == "REAL"}
    candidates, ordinary = [], []
    for repo in args.repos:
        repo = repo.resolve()
        for commit, authored, subject in commits(repo):
            files = files_and_patch(repo, commit)
            if not files:
                continue
            rules = classify(files)
            record = (repo, commit, authored, subject, files, rules)
            (candidates if rules else ordinary).append(record)
    candidates = candidates[:args.faults]
    chosen = candidates + ordinary[:len(candidates)]
    start = 1 + max((int(re.search(r"real-(\d+)-", x.get("eventId", "real-0-" )).group(1)) for x in existing if x.get("split") == "REAL" and re.search(r"real-\d+-", x.get("eventId", ""))), default=0)
    fresh = []
    for offset, record in enumerate(chosen):
        repo, commit, authored, subject, files, rules = record
        if (str(repo), commit) in known:
            continue
        fresh.append(event(repo, commit, authored, subject, files, rules, start + offset))
    with args.output.open("a", encoding="utf-8") as stream:
        for item in fresh:
            stream.write(json.dumps(item, separators=(",", ":")) + "\n")
    print(json.dumps({"fault_candidates": len(candidates), "clean_selected": len(ordinary[:len(candidates)]), "appended": len(fresh), "output": str(args.output)}))


if __name__ == "__main__":
    main()
