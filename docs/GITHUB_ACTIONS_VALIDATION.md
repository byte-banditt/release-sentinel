# GitHub Actions validation integration

Release Sentinel records only externally executed workflow evidence. It never
runs candidate tests. The sample uses an unprivileged PR workflow and a
`workflow_run` reporter that executes on the base repository's default branch
without checking out the triggering PR or running its code.

Protect `.github/workflows/release-sentinel-tests.yml` with code-owner review
and branch protection. A passing run is evidence for the intended suite only
while that workflow definition remains protected. Pin third-party Actions to
reviewed commit SHAs before production use; tags below match this repository's
existing examples.

The reporter currently trusts GitHub run metadata plus the HMAC-authenticated
report fields; the service does not independently fetch and attest the workflow
definition or test logs. A PR can change a workflow to retain the same name,
path, and test-step name while reporting fabricated counts. Therefore these
reports are not trustworthy until repository rules prevent unreviewed changes
to the workflow and an independent control verifies any workflow-definition
change. The HMAC proves which configured reporter sent a report, not that the
reported test code was trustworthy. The example below is a setup template,
not an installed or externally verified integration.

Configure these protected values:

- `RELEASE_SENTINEL_URL`: service base URL.
- `RELEASE_SENTINEL_TENANT`: tenant name used in `SENTINEL_CI_TOKENS`.
- `RELEASE_SENTINEL_CI_TOKEN`: at least 32 random characters; never expose it
  to the test job or PR code.
- `RELEASE_SENTINEL_REVIEW_ID` is not needed; the reporter resolves exact
  bindings using repository, PR, and commit SHA.

The service's `SENTINEL_CI_TOKENS` is a JSON object mapping tenant to HMAC
secret, for example `{"acme":"<32-character-or-longer-secret>"}`. The same
secret is stored as the GitHub Actions secret. API bearer keys use a separate
`SENTINEL_API_TOKENS` map keyed by `tenant/reviewer`.

## Unprivileged project test workflow

Add this file to the developer repository as
`.github/workflows/release-sentinel-tests.yml`. It checks out the exact PR head
SHA and executes the repository's real test suite. It receives no Release
Sentinel credential. The report artifact contains only aggregate counts and is
treated as untrusted input by the reporter.

```yaml
name: Release Sentinel Tests

on:
  pull_request:
  push:
    branches: [main]

permissions:
  contents: read

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.head.sha || github.sha }}
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - name: Run project tests
        id: project-tests
        run: ./mvnw -B test
      - name: Capture Maven test counts
        if: always()
        run: |
          python3 - <<'PY'
          import glob, json, xml.etree.ElementTree as ET
          paths = glob.glob("target/surefire-reports/TEST-*.xml")
          totals = {"passed": 0, "failed": 0, "skipped": 0}
          for path in paths:
              root = ET.parse(path).getroot()
              totals["failed"] += int(root.attrib.get("failures", 0)) + int(root.attrib.get("errors", 0))
              totals["skipped"] += int(root.attrib.get("skipped", 0))
              totals["passed"] += int(root.attrib.get("tests", 0)) - int(root.attrib.get("failures", 0)) - int(root.attrib.get("errors", 0)) - int(root.attrib.get("skipped", 0))
          if not paths:
              totals = {"passed": None, "failed": None, "skipped": None}
          with open("maven-test-counts.json", "w", encoding="utf-8") as f:
              json.dump(totals, f, separators=(",", ":"))
          PY
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: maven-test-counts
          path: maven-test-counts.json
          if-no-files-found: ignore
          retention-days: 1
```

## Trusted `workflow_run` reporter

Add this second workflow on the developer repository's default branch. It
checks the completed run and test step through GitHub's API. It downloads only
the counts JSON; it does not check out, install, or execute code from the
triggering PR. Do not change the report step to run shell scripts from the
artifact.

```yaml
name: Report Release Sentinel CI

on:
  workflow_run:
    workflows: [Release Sentinel Tests]
    types: [completed]

permissions:
  actions: read
  contents: read

jobs:
  report:
    if: github.event.workflow_run.conclusion != ''
    runs-on: ubuntu-latest
    steps:
      - name: Download bounded aggregate test counts
        continue-on-error: true
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPOSITORY: ${{ github.repository }}
          RUN_ID: ${{ github.event.workflow_run.id }}
        run: gh run download "$RUN_ID" --repo "$GH_REPOSITORY" --name maven-test-counts --dir "$RUNNER_TEMP/maven-test-counts"
      - name: Report trusted run metadata
        env:
          GH_TOKEN: ${{ github.token }}
          RS_HMAC: ${{ secrets.RELEASE_SENTINEL_CI_TOKEN }}
          API_URL: ${{ vars.RELEASE_SENTINEL_URL }}
          GH_REPOSITORY: ${{ github.repository }}
          RUN_ID: ${{ github.event.workflow_run.id }}
        shell: bash
        run: |
          set -euo pipefail
          run_json="$(gh api "repos/${GH_REPOSITORY}/actions/runs/${RUN_ID}")"
          jobs_json="$(gh api "repos/${GH_REPOSITORY}/actions/runs/${RUN_ID}/jobs?per_page=100")"
          name="$(jq -er '.name' <<<"$run_json")"
          path="$(jq -er '.path | sub("@.*$"; "")' <<<"$run_json")"
          conclusion="$(jq -er '.conclusion' <<<"$run_json")"
          commit_sha="$(jq -er '.head_sha' <<<"$run_json")"
          workflow_url="$(jq -er '.html_url' <<<"$run_json")"
          pr_number="$(jq -er '[.pull_requests[]?.number] | if length == 1 then .[0] else error("PR association missing or ambiguous") end' <<<"$run_json")"
          test_step="$(jq -er '[.jobs[].steps[] | select(.name == "Run project tests") | .conclusion] | if length == 1 then .[0] else error("test step missing or ambiguous") end' <<<"$jobs_json")"
          test_counts="$(python3 - "$RUNNER_TEMP/maven-test-counts/maven-test-counts.json" <<'PY'
          import json, os, sys
          if not os.path.exists(sys.argv[1]):
              print('{"passed":null,"failed":null,"skipped":null}')
              raise SystemExit(0)
          with open(sys.argv[1], encoding="utf-8") as f:
              value = json.load(f)
          keys = ("passed", "failed", "skipped")
          if set(value) != set(keys) or any(value[k] is not None and (type(value[k]) is not int or value[k] < 0 or value[k] > 1000000) for k in keys):
              raise SystemExit("invalid test counts")
          print(json.dumps(value, separators=(",", ":")))
          PY
          )"
          resolve_body="$(jq -cn --arg repository "$GH_REPOSITORY" --argjson pullRequestNumber "$pr_number" --arg commitSha "$commit_sha" '{repository:$repository,pullRequestNumber:$pullRequestNumber,commitSha:$commitSha}')"
          sign_and_post() {
            local endpoint="$1" body="$2" timestamp signature
            timestamp="$(date +%s)"
            signature="$(printf '%s.%s' "$timestamp" "$body" | python3 -c 'import hashlib,hmac,os,sys; print(hmac.new(os.environ["RS_HMAC"].encode(),sys.stdin.buffer.read(),hashlib.sha256).hexdigest())')"
            curl --fail-with-body --silent --show-error -X POST \
              -H "X-Sentinel-Timestamp: ${timestamp}" \
              -H "X-Sentinel-Signature: sha256=${signature}" \
              -H 'Content-Type: application/json' --data-binary "$body" "${API_URL}${endpoint}"
          }
          bindings="$(sign_and_post /api/validation-bindings/resolve "$resolve_body")"
          jq -e 'type == "array"' <<<"$bindings" >/dev/null
          while IFS= read -r review_id; do
            report="$(jq -cn --arg reviewId "$review_id" --arg repository "$GH_REPOSITORY" \
              --arg commitSha "$commit_sha" --arg workflowUrl "$workflow_url" --arg conclusion "$conclusion" \
              --arg workflowName "$name" --arg workflowPath "$path" --arg testStepConclusion "$test_step" \
              --argjson pullRequestNumber "$pr_number" --argjson workflowRunId "$RUN_ID" --argjson counts "$test_counts" \
              '{reviewId:$reviewId,repository:$repository,pullRequestNumber:$pullRequestNumber,commitSha:$commitSha,
                workflowRunId:$workflowRunId,workflowUrl:$workflowUrl,status:"COMPLETED",conclusion:$conclusion,
                testsPassed:$counts.passed,testsFailed:$counts.failed,testsSkipped:$counts.skipped,testSuite:"maven-test",
                workflowName:$workflowName,workflowPath:$workflowPath,testStepConclusion:$testStepConclusion}')"
            sign_and_post "/api/reviews/${review_id}/ci-results" "$report" >/dev/null
          done < <(jq -r '.[].reviewId' <<<"$bindings")
```

The reporting workflow fails closed if GitHub does not identify exactly one
PR, the trusted run/test-step metadata is missing, or the SHA has no matching
approved binding. The HMAC key is available only in this base-repository
workflow. `pull_request_target` is not used.

## Correlation and validation rules

`POST /api/reviews/{id}/bind-validation-commit` requires approved suggestion
IDs and a SHA that GitHub associates with that exact pull request. A unique
binding stores the review ID, repository, PR number, original review SHA,
validation SHA, accepted IDs, artifact aggregate hash, and bind time. Rebinding
is rejected.

The trusted reporter resolves all tenant bindings for the exact repository,
PR, and head SHA. Its HMAC report includes GitHub's canonical run URL and ID,
workflow name/path, conclusion, named test-step conclusion, test suite, and
counts. The API rejects mismatched repository/PR/commit, non-completed runs,
unknown workflow identity, invalid counts, stale timestamps, and conflicting
reuse of a run ID. Identical repeated events are idempotent.

Only `success` workflow conclusion + `success` test-step conclusion + the
protected expected workflow name/path + at least one passing test + zero test
failures can mark suggestions `VALIDATED`. A failed workflow is recorded as
failed. A successful build with no executed/passing tests remains
`VALIDATION_PENDING`; it cannot be validated. If a report artifact is absent,
the trusted reporter still reports the run with null counts and the result
remains pending (unless the workflow itself failed). Existing workflow
artifacts are untrusted; the reporting job parses only bounded JSON and never
executes downloaded content.
