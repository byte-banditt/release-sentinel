# AI PR review and test-generation evaluation

This evaluation is separate from the existing rules-only `corpus/labels.jsonl`
replay. The PR-review seed set lives at `eval/pr_review/labels.jsonl`; the
scorer is `scripts/pr_review_eval.py`. The current seed set is synthetic and
small, intended to exercise the measurement format rather than support claims
about model quality.

## Reproduce a scored run

1. Save one JSON object per example to a prediction JSONL file. Each object
   needs `id`, `findings`, and may include `abstained` and `test_generation`.
   Finding objects use `file_path`, `start_line`, `end_line`, `severity`, and
   `evidence` (an array of strings). Keep this file as the raw prediction
   artifact; do not edit it after scoring.
2. Save a sidecar with the same basename and `.meta.json` suffix. Record
   `repository_commit_sha`, `provider`, `model`, `model_configuration`, and
   `prompt_version`.
3. Run:

   ```sh
   python3 scripts/pr_review_eval.py \
     --labels eval/pr_review/labels.jsonl \
     --predictions /path/to/predictions.jsonl \
     --split DEV \
     --output /path/to/scored-run.json
   ```

The output retains the raw predictions and selected ground truth alongside
the timestamp, dataset version, metadata, and metrics. The scorer performs no
model calls and does not execute generated code. Never substitute mocked
predictions for a real Ollama run in a model benchmark.

Predictions must contain exactly one row per selected label, with no missing
or extra IDs. The scorer rejects incomplete datasets instead of counting
missing rows as false negatives. It reports precision, recall, F1, per-category
precision/recall, false negatives, localization, evidence support, duplicate
and unsupported finding rates, and abstention accuracy. Test-execution and
operational fields remain unmeasured unless explicitly recorded per example.

## Seed categories and measures

Seeded changes cover null handling, boundary conditions, partial transaction
failure, races, retry idempotency, authorization, API behavior, missing
regression coverage, and a benign change. The scorer reports finding
precision/recall/F1, false-positive rate on benign examples, severity accuracy,
per-category precision/recall, file and changed-line localization,
evidence-anchor support, unsupported and duplicate finding rates, and
abstention accuracy.
Evidence support is a deterministic anchor check against the seed, not human
judgment of explanation quality. One first finding is matched to each seeded
defect; extra findings count as false positives.

The optional `test_generation` observation fields are booleans:
`syntax_compiles`, `discovered`, `passed_on_fixed`, and `fails_on_seeded_bug`.
Compilation, discovery, passing the corrected implementation, and reproducing
the seeded defect are reported separately. An absent observation remains
unmeasured. Do not infer generated-test quality from syntax success alone.
Testing whether a candidate is duplicate/redundant requires a separately
reviewed label and is not currently scored by this runner.

## Current result and limitations

No complete prediction file or scored run is available. On 2026-11-09, Ollama
0.32.15 was present with `qwen3:8b` (ID `500a1f067a9f`) and
`qwen3:1.7b` (ID `8f68893c685c`). A call through the production
`OllamaPullRequestReviewAgent` and `ReviewResultValidator` exceeded 60 seconds
for `qwen3:8b`. With `qwen3:1.7b`, one fixture returned no accepted finding;
the next call exceeded 180 seconds. No complete predictions, raw responses,
or metrics were retained. This is an execution failure, not a model-quality
result. Do not claim precision, recall, compilation rate, defect detection, or
CI validation from it.

The synthetic seed corpus has 9 DEV examples and is too small for meaningful
generalization. It lacks several required security, migration, dependency,
cross-service, prompt-injection, dangerous suggestion, and abstention cases.
It has no runnable, immutable fixture repositories, so Java test compilation,
test discovery, and generated-test effectiveness remain unmeasured.

False-positive/false-negative examples are not reported as observed model
behavior because no complete model run exists. When a run is added, keep the raw
prediction file, sidecar, scored JSON, and any test-run logs together under a
run-specific directory outside this source tree. Include failed and abstained
examples; do not select examples after seeing results.
