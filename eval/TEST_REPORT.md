# Replay evaluation

Rules freeze: `833740a`. Corpus: `corpus/labels.jsonl`. All 30 events are
`INJECTED`; no `REAL` commit-history event was available for this fixture.
The replay below ran against local Compose Kafka and the app with
`SENTINEL_AGENT_URL` empty, so it is the rules-only run. No AWS call occurred.

## TEST

Command: `python3 scripts/replay_eval.py --split TEST --timeout-ms 5000`

```text
events=20 verdicts=20 missing=0
R1: TP=2 FP=0 FN=1 precision=2/2 recall=2/3
R2: TP=2 FP=0 FN=1 precision=2/2 recall=2/3
R3: TP=3 FP=0 FN=0 precision=3/3 recall=3/3
R4: TP=3 FP=0 FN=0 precision=3/3 recall=3/3
clean_events=10 clean_false_positives=0
modes=RULES:20
verdict_latency_ms_count=20 p50=0 p95=1
```

Rules+agent: NOT RUN. The real AgentCore endpoint is manual and excluded from
CI; no fake agent result was substituted into this report.

## DEV

Command: `python3 scripts/replay_eval.py --split DEV --timeout-ms 5000`

```text
events=10 verdicts=10 missing=0
R1: TP=1 FP=0 FN=1 precision=1/1 recall=1/2
R2: TP=0 FP=0 FN=1 precision=0/0 recall=0/1
R3: TP=1 FP=0 FN=0 precision=1/1 recall=1/1
R4: TP=1 FP=0 FN=0 precision=1/1 recall=1/1
clean_events=5 clean_false_positives=0
modes=RULES:10
verdict_latency_ms_count=10 p50=0 p95=0
```
