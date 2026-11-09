# Release Sentinel

Release Sentinel is a Java 21 service that receives CI and pull-request
events, evaluates release-risk rules, optionally enriches the result with a
local or Amazon Bedrock AgentCore model, and delivers verdicts to tenant
webhooks.

It is a Spring Boot application backed by Apache Kafka and PostgreSQL. Alongside
release-risk verdicts, it can review GitHub pull requests with an Ollama-compatible
model, generate candidate JUnit 5 tests, and correlate human-approved tests with
trusted GitHub Actions runs.

## How it works

```text
GitHub / generic JSON --> PostgreSQL outbox --> Kafka --> rules --> verdict webhook
          |                                    |
          +--> durable PR job --> GitHub API --> Ollama review + candidate tests
                                                  |
                                      human approval / export
                                                  |
                                commit binding --> GitHub Actions --> validation
```

Kafka processing remains at-least-once. The PostgreSQL outbox makes accepted
events durable and suppresses duplicate GitHub delivery IDs; a crash after
Kafka acknowledgement but before recording it can still cause a duplicate.
The analyzer publishes a verdict before committing the consumed offset.
Receivers should deduplicate using `X-Sentinel-Delivery-Id`.

The rules currently cover:

- removed configuration keys;
- dependency changes without an accompanying lockfile update;
- migration changes without rollback evidence; and
- deleted or weakened test coverage.

`service-graph.yaml` is an explicit dependency graph. Blast-radius traversal
uses cycle-safe BFS and does not infer ownership from repository layout.

## Requirements

- Java 21
- Docker Engine and Docker Compose plugin for Kafka/PostgreSQL integration tests or deployment
- Python 3.10+ only for the evaluation and AgentCore runtime tooling

## Build and test

Run the unit and integration test suite:

```bash
./mvnw test
```

Kafka and PostgreSQL integration tests use Testcontainers and are skipped when
Docker is unavailable. Build the runnable jar with:

```bash
./mvnw package
```

## Run locally

Start Kafka and PostgreSQL (use a protected `.env` file):

```bash
export DATABASE_PASSWORD="$(openssl rand -hex 32)"
export GITHUB_WEBHOOK_SECRET="$(openssl rand -hex 32)"
docker compose --profile ec2 up -d kafka postgres
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
DATABASE_URL=jdbc:postgresql://localhost:5432/release_sentinel \
DATABASE_USER=sentinel DATABASE_PASSWORD="$DATABASE_PASSWORD" \
GITHUB_WEBHOOK_SECRET="$GITHUB_WEBHOOK_SECRET" ./mvnw spring-boot:run
```

The application listens on `http://localhost:8080`. Management endpoints are
on `http://localhost:8081/internal/actuator`.

Submit a validated generic event:

```bash
curl -i -X POST http://localhost:8080/events \
  -H 'content-type: application/json' \
  --data '{"schemaVersion":1,"eventId":"local-1","tenantId":"demo","source":"generic","repo":"demo/repo","prNumber":1,"commitSha":"local","changedFiles":[],"logExcerpt":"build passed","receivedAt":"2026-01-01T00:00:00Z"}'
```

Generic event ingest returns `202 Accepted` after the event is durably queued
and Kafka has acknowledged it. Standard GitHub PR webhooks are durably queued
and answered promptly; a background worker retrieves the PR, publishes its
release-risk event, and stores an independent review.

## HTTP endpoints

| Endpoint | Purpose | Authentication |
| --- | --- | --- |
| `POST /events` | Accept a schema-valid generic `CiEvent` JSON body | Configure network access appropriately |
| `POST /webhook/github` | Accept a GitHub-shaped payload | `X-Hub-Signature-256` HMAC and `GITHUB_WEBHOOK_SECRET` |
| `POST /api/reviews` | Analyze `{repository,pullRequestNumber}` | Tenant/reviewer bearer token |
| `GET /api/reviews`, `/api/reviews/{id}` | List/read review and test suggestions | Tenant/reviewer bearer token |
| `POST /api/test-suggestions/{id}/approve` or `/reject` | Record human decision | Tenant/reviewer bearer token |
| `GET /api/test-suggestions/{id}/export` | Export approved candidate test | Tenant/reviewer bearer token |
| `POST /api/reviews/{id}/bind-validation-commit` | Bind approved suggestions to a PR commit | Tenant/reviewer bearer token |
| `POST /api/validation-bindings/resolve` | Resolve binding by exact repo/PR/SHA | Timestamped tenant HMAC |
| `POST /api/reviews/{id}/ci-results` | Record completed trusted test run | Timestamped tenant HMAC |
| `GET /api/reviews/{id}/validation` | Read validation evidence | Tenant/reviewer bearer token |
| `GET /internal/actuator/health` | Health check | Keep management port private |
| `GET /internal/actuator/metrics` | Metrics | Keep management port private |

GitHub requests may also provide `X-Tenant-Id` and use
`X-GitHub-Delivery` as the event id. GitHub signature comparison is
constant-time. TLS is still required for confidentiality because HMAC signing
does not encrypt the request.

Standard `pull_request` webhooks are accepted for `opened`, `synchronize`,
`reopened`, and `ready_for_review`. GitHub does not include file patches in
that payload; configure a read-only GitHub token so the worker can retrieve
metadata, changed files, patches, and bounded source context through the GitHub API. Deployments that send the legacy
`changed_files` extension continue to use those supplied files.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker address |
| `SERVER_PORT` | `8080` | Public application port |
| `MANAGEMENT_SERVER_PORT` | `8081` | Actuator port |
| `MANAGEMENT_SERVER_ADDRESS` | `0.0.0.0` | Actuator bind address; restrict it at deployment |
| `GITHUB_WEBHOOK_SECRET` | none | Required GitHub HMAC secret |
| `GITHUB_READ_TOKEN` | none | Read-only token for retrieving PR changed files |
| `GITHUB_MAX_PR_FILES` | `200` | Maximum changed files retrieved per PR |
| `GITHUB_MAX_DIFF_CHARS` | `500000` | Maximum combined textual patch size |
| `GITHUB_MAX_WEBHOOK_BYTES` | `1048576` | Maximum webhook payload size |
| `DATABASE_URL` | local PostgreSQL URL | Durable review/outbox database |
| `DATABASE_USER` | `sentinel` | Database user |
| `DATABASE_PASSWORD` | local-only default | Database password; set a strong secret outside local development |
| `SENTINEL_API_TOKENS` | `{}` | JSON map `tenant/reviewer` to bearer secret (32+ characters) |
| `SENTINEL_CI_TOKENS` | `{}` | JSON map tenant to CI HMAC secret (32+ characters) |
| `SENTINEL_AGENT_URL` | `http://127.0.0.1:11434/api/chat` | Ollama-compatible chat endpoint |
| `SENTINEL_AGENT_MODEL` | `qwen3:8b` | Local triage model |
| `SENTINEL_AGENT_TIMEOUT` | `8s` | Agent request timeout |
| `SENTINEL_REVIEW_URL` | `SENTINEL_AGENT_URL` | Ollama-compatible PR-review endpoint |
| `SENTINEL_REVIEW_MODEL` | `SENTINEL_AGENT_MODEL` | PR-review/test-generation model |
| `SENTINEL_REVIEW_TIMEOUT` | `30s` | Per-call PR-review timeout |
| `SENTINEL_WEBHOOK_URL_<TENANT>` | none | Required verdict destination |
| `SENTINEL_WEBHOOK_SECRET_<TENANT>` | none | Verdict signing secret |
| `SENTINEL_WEBHOOK_SECRET_FILE_<TENANT>` | none | Alternative secret-file location |

Tenant ids are normalized to uppercase environment-variable suffixes. For
example, tenant `acme-prod` uses `SENTINEL_WEBHOOK_URL_ACME_PROD`.

Secrets must be supplied through the runtime environment or a protected secret
file. Do not commit them.

## Optional agent enrichment

The Java analyzer calls the configured `TriageAgent` for failing logs or WARN
rules. It sends only a redacted event subset. Agent timeouts and invalid
responses preserve the rules verdict and mark the decision as
`RULES_FALLBACK`; an agent cannot lower a rules-derived `BLOCK`.

The `agent/` directory contains a stateless Amazon Bedrock AgentCore runtime.
It validates model output as `PASS`, `WARN`, or `BLOCK`, redacts token,
secret, password, authorization, JWT, and AWS-key patterns, and treats CI
text as untrusted data. Deployment is manual:

```bash
cd agent
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export AWS_REGION=us-east-1
export BEDROCK_MODEL_ID=<model-id>
./deploy-real-runtime.sh
```

Review the AWS budget, model-access, IAM, and regional pricing requirements in
[`agent/README.md`](agent/README.md) before deploying.

## AI PR review and generated tests

PR review status is separate from PASS/WARN/BLOCK release verdicts. Findings
must cite actual changed lines and exact evidence; insufficient context
abstains. Generated candidates follow JUnit 5 conventions, remain artifacts,
and require an authenticated human decision before export. Only explicitly
approved candidates can be bound to a GitHub-associated commit. Trusted Actions
reports use a separate HMAC secret and can mark candidates validated only when
the exact protected test workflow and test step succeeded with passing tests.
Release Sentinel never applies or executes candidate code. See
[`docs/AI_PR_REVIEW_ARCHITECTURE.md`](docs/AI_PR_REVIEW_ARCHITECTURE.md),
[`docs/TEST_GENERATION_WORKFLOW.md`](docs/TEST_GENERATION_WORKFLOW.md),
[`docs/GITHUB_ACTIONS_VALIDATION.md`](docs/GITHUB_ACTIONS_VALIDATION.md), and
[`docs/ADR_PR_REVIEW_PERSISTENCE.md`](docs/ADR_PR_REVIEW_PERSISTENCE.md).

## EC2 deployment

The `ec2` Compose profile runs the application with a pinned single-node KRaft
Kafka broker. Create a protected `.env` file with deployment-specific values,
then run:

```bash
chmod 600 .env
./scripts/deploy-ec2.sh
```

The complete smoke test, recovery commands, Kafka DLT inspection, and teardown
procedure are in [`docs/DEPLOY_EC2.md`](docs/DEPLOY_EC2.md). The deployment is
single-broker and is not high-availability evidence.

## Evaluation

AI review/test-generation evaluation is separate from the existing rules-only
event replay. Its synthetic labelled corpus, reproducible scorer, and explicit
unrun status are documented in
[`docs/AI_REVIEW_EVALUATION.md`](docs/AI_REVIEW_EVALUATION.md). No model-quality
or end-to-end GitHub Actions benchmark is claimed until actual runs are saved.

Replay the frozen fixture corpus with:

```bash
python3 scripts/replay_eval.py --split TEST
python3 scripts/replay_eval.py --offline --split TEST
```

Reports and corpus details:

- [`eval/TEST_REPORT.md`](eval/TEST_REPORT.md)
- [`eval/REAL_REPORT.md`](eval/REAL_REPORT.md)
- [`eval/S3_LITE_REPORT.md`](eval/S3_LITE_REPORT.md)
- [`corpus/labels.jsonl`](corpus/labels.jsonl)

The corpus is small and partly author-built. The available repository history
did not provide the requested 10 faulty and 10 clean REAL events, so evaluation
results are directional rather than production-quality accuracy evidence.

## Implementation status

| Requirement | Existing | Added | Verified |
| --- | --- | --- | --- |
| PR ingestion | GitHub webhook adapter and HMAC | PR API retrieval, bounded diffs, durable queued processing | Focused Testcontainers integration tests |
| AI code review | Ollama-compatible client patterns | Separate PR reviewer, strict JSON validation, evidence and line grounding | Validator/provider tests; no live Ollama benchmark claimed |
| Evidence grounding | — | Changed-file and changed-line checks; abstention on unavailable context | Focused unit tests |
| Test generation | JUnit 5 project conventions | JUnit 5 candidate artifacts with secret/path checks | Artifact validation tests; generated code is not claimed compiled |
| Human approval | — | Tenant-scoped review, approve/reject, export gates | PostgreSQL integration test |
| CI integration | GitHub Actions CI | Documented unprivileged test + trusted `workflow_run` reporting pattern | Report correlation integration test; external Actions run not executed |
| Commit correlation | — | GitHub PR commit association, immutable SHA binding and HMAC reports | PostgreSQL integration test |
| Security | Existing webhook HMAC and redaction | Tenant-scoped review APIs, bounded inputs, CI HMAC, no server code execution | Focused tests; security assessment is not an external audit |
| Evaluation | Rules-only event replay | Separate labelled PR-review scorer and seeded corpus | Python syntax/scorer smoke checks; no model metrics reported |

The CI workflow under `docs/GITHUB_ACTIONS_VALIDATION.md` must be installed and
protected in the developer's repository before its reports can count as CI
evidence. This workspace has not deployed the service or run that workflow.
There is no isolated candidate-patch runner in this repository: the documented
workflow runs the project's ordinary test suite and does not apply, compile, or
execute a Release Sentinel candidate artifact. Reports also require a trusted
workflow definition; the HMAC alone does not attest workflow contents.

## Repository guide

- `src/main/java/com/releasesentinel/adapter` — GitHub and generic event adapters
- `src/main/java/com/releasesentinel/rules` — deterministic risk rules
- `src/main/java/com/releasesentinel/analyzer` — rules and optional agent analysis
- `src/main/java/com/releasesentinel/ingest` — HTTP ingest and Kafka publishing
- `src/main/java/com/releasesentinel/egress` — signed, retried webhook delivery
- `src/main/java/com/releasesentinel/graph` — declared dependency graph traversal
- `agent/` — optional Bedrock AgentCore runtime
- `docs/` — architecture and deployment records
- `scripts/` — deployment, corpus, and evaluation utilities

See [`docs/ADR.md`](docs/ADR.md) for the decisions behind the rules, Kafka
boundaries, idempotency, graph, and modular-monolith design.
