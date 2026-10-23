# LOG classification evaluation

`gh run list` found only two failed GitHub Actions runs in the listed repos:
two in `byte-banditt/flash-sale-engine` and none in `byte-banditt/cve-rag`.
Thus the requested N>=10 was unavailable; LOG uses the two real job logs that
were available. Labels were assigned by reading the downloaded logs. N is
small and labels are mine.

The fixed baseline is regex-only and was defined before running Ollama:
dependency-resolution, timeout-flaky, config-env, build-error, then
test-failure precedence. The fixed Ollama classification prompt is recorded in
`scripts/log_classifier_eval.py`; it was not changed after seeing LOG results.

Raw source records are in [`corpus/log_labels.jsonl`](../corpus/log_labels.jsonl).
The real Ollama run output is recorded below after execution.

## Connectivity

Ollama was used at `127.0.0.1:11434`; it was not bound to `0.0.0.0`. A direct real call returned this raw response:

```text
{"model":"qwen3:8b","created_at":"2026-10-23T11:41:19.094958027Z","message":{"role":"assistant","content":"{\"classification\":\"build-error\",\"rationale\":\"connectivity\",\"evidenceLines\":[]}","thinking":"Okay, the user wants me to return exactly the JSON: {\"classification\":\"build-error\",\"rationale\":\"connectivity\",\"evidenceLines\":[]}. Let me make sure I understand the request correctly. They specified the structure and the content exactly as provided. \n\nFirst, I need to check if there are any additional instructions or if there's a possibility of misunderstanding. The user mentioned \"Return exactly JSON,\" so I should ensure that the output is strictly the given JSON without any extra text or formatting. \n\nI should verify each part of the JSON. The classification is in the allowed set, the rationale is a string, and evidenceLines is an empty array.\n","done":true,"done_reason":"stop","total_duration":8977644400,"load_duration":1129531,"prompt_eval_count":354,"prompt_eval_duration":143485000,"eval_count":18,"eval_duration":384423000}
```

## Raw evaluation output

```text
events=2 baseline_correct=2 baseline_incorrect=0
baseline_predictions=log-001-flash-39030384395:config-env,log-002-flash-39030049243:build-error
run=1 agent_correct=1 agent_incorrect=0 invalid_json=0 timeout=1 predictions=log-001-flash-39030384395:timeout,log-002-flash-39030049243:build-error
run=2 agent_correct=1 agent_incorrect=1 invalid_json=0 timeout=0 predictions=log-001-flash-39030384395:test-failure,log-002-flash-39030049243:build-error
run=3 agent_correct=1 agent_incorrect=1 invalid_json=0 timeout=0 predictions=log-001-flash-39030384395:test-failure,log-002-flash-39030049243:build-error
```

The baseline accuracy was `2/2`. Agent accuracy was `1/2`, `1/2`, and `1/2`; invalid JSON was `0` in all runs, and timeouts were `1`, `0`, and `0`. The build-error event agreed across all 3 runs. The config-env event timed out once and was classified as test-failure in the other 2 runs, so complete three-run agreement was `1/2` events.

No prompt tuning was done after seeing LOG results. N is small and labels are mine.
