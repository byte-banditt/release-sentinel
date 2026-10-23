# REAL replay evaluation

Rules freeze: `833740a6358128f3fb093db18652114e01dc97b5`.
The miner read parent-to-commit diffs from `/home/ayussh/projects/flash-sale-engine`
and `/home/ayussh/projects/cve_rag`. Labels were assigned from those diffs; the
release-sentinel rules were not used to create labels. Every event in this split
has `provenance=REAL`, `sourceRepo`, and `sourceCommit`.

The available histories produced 6 faulty and 6 clean events, below the 10/10
target. No additional listed repository was supplied. REAL is balanced after
excluding a false migration-name match (`faiss_completion_..._migrated.json`),
which was not a database migration.

Command:

```text
python3 scripts/replay_eval.py --split REAL --timeout-ms 5000
```

Raw output:

```text
events=12 verdicts=12 missing=0
R1: TP=1 FP=0 FN=1 precision=1/1 recall=1/2
R2: TP=1 FP=1 FN=0 precision=1/2 recall=1/1
R3: TP=3 FP=0 FN=0 precision=3/3 recall=3/3
R4: TP=0 FP=0 FN=0 precision=0/0 recall=0/0
clean_events=6 clean_false_positives=0
modes=RULES:12
verdict_latency_ms_count=12 p50=0 p95=0
```

The current corpus has 12 REAL rows, 6 faulty and 6 clean. Current-corpus raw
labels are: R1=2, R2=1, R3=3, R4=0; clean=6.
Rules-only is the measured mode. Rules+agent was not run: the AgentCore runtime
is manual and no AWS call is made by this evaluation. REAL misses are reported,
not used to change any frozen rule.

Known REAL misses/false positives include one R1 miss and one R2 false positive
in the replay. Their exact behavior is retained as evidence for future rule
work; this task does not patch rules.
