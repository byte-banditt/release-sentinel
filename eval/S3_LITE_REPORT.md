# S3-lite local Ollama evaluation

The application now has `TriageAgent` and `OllamaTriageAgent`. The model is
configured as `qwen3:8b`, temperature `0`, and the default timeout is 8 seconds.
The Java payload is redacted before HTTP submission and the response schema is
strictly validated.

Rules-only baseline, raw output:

```text
run=rules-only
TEST: events=20 verdicts=20 missing=0; modes=RULES:20; verdict_changes=0
REAL: events=12 verdicts=12 missing=0; modes=RULES:12; verdict_changes=0
```

Full per-rule counts were unchanged from the frozen rules: TEST R1 `TP=2 FP=0
FN=1`, R2 `TP=2 FP=0 FN=1`, R3 `TP=3 FP=0 FN=0`, R4 `TP=3 FP=0 FN=0`;
REAL R1 `TP=1 FP=0 FN=1`, R2 `TP=1 FP=1 FN=0`, R3 `TP=3 FP=0 FN=0`, R4
`TP=0 FP=0 FN=0`.

Agent-mode runs (three each) all reached the application but could not reach
the host Ollama service from the container. Therefore every eligible call
fell back; no LLM output is claimed and agreement is fallback agreement, not
model agreement.

```text
TEST run 1: events=20 verdicts=20 missing=0; modes=RULES:16,RULES_FALLBACK:4; verdict_changes=0
TEST run 2: events=20 verdicts=20 missing=0; modes=RULES:16,RULES_FALLBACK:4; verdict_changes=0
TEST run 3: events=20 verdicts=20 missing=0; modes=RULES:16,RULES_FALLBACK:4; verdict_changes=0
REAL run 1: events=12 verdicts=12 missing=0; modes=RULES:10,RULES_FALLBACK:2; verdict_changes=0
REAL run 2: events=12 verdicts=12 missing=0; modes=RULES:10,RULES_FALLBACK:2; verdict_changes=0
REAL run 3: events=12 verdicts=12 missing=0; modes=RULES:10,RULES_FALLBACK:2; verdict_changes=0
```

Every agent-vs-rules verdict change: none. Thus there are no changed events to
classify as correct/incorrect. No rule or prompt was changed in response to
these results.

The local Ollama endpoint was confirmed on the host with `qwen3:8b` installed,
but it listened only on `127.0.0.1:11434`; Docker could not reach it through
the host gateway. A reachable host binding or a host-run application is still
needed for a real three-run LLM comparison.
