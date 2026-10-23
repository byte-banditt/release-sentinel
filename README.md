# Release Sentinel

Release Sentinel is a Java 21 service that receives CI and pull-request
events, evaluates release-risk rules, optionally enriches the result with a
local or Amazon Bedrock AgentCore model, and delivers verdicts to tenant
webhooks.

It is a Spring Boot application backed by Apache Kafka. The core event model,
adapters, rules, service graph, analyzer, and delivery components are kept in
one Maven module.

## How it works

```text
GitHub / generic JSON
          |
          v
  authenticated ingest
          |
          v
       ci.events  --->  analyzer  --->  ci.verdicts  ---> tenant webhook
          |                 |
          v                 v
    ci.events.dlq     rules + optional agent
```

The pipeline is intentionally at-least-once. Ingest publishes only after an
`acks=all` broker acknowledgement. The analyzer publishes a verdict before
committing the consumed event offset. A crash between either operation can
produce a duplicate; receivers must deduplicate using
`X-Sentinel-Delivery-Id`.

The rules currently cover:

- removed configuration keys;
- dependency changes without an accompanying lockfile update;
- migration changes without rollback evidence; and
- deleted or weakened test coverage.

`service-graph.yaml` is an explicit dependency graph. Blast-radius traversal
uses cycle-safe BFS and does not infer ownership from repository layout.

## Requirements

- Java 21
- Docker Engine and Docker Compose plugin for Kafka or EC2 deployment
- Python 3.10+ only for the evaluation and AgentCore runtime tooling

## Build and test

Run the unit and integration test suite:

```bash
./mvnw test
```

The Kafka integration test uses Testcontainers and is skipped when Docker is
unavailable. Build the runnable jar with:

```bash
./mvnw package
```

## Run locally

Start the single-node Kafka profile:

```bash
docker compose --profile ec2 up -d kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 GITHUB_WEBHOOK_SECRET=dev-secret ./mvnw spring-boot:run
```

The application listens on `http://localhost:8080`. Management endpoints are
on `http://localhost:8081/internal/actuator`.

Submit a validated generic event:

```bash
curl -i -X POST http://localhost:8080/events \
  -H 'content-type: application/json' \
  --data '{"schemaVersion":1,"eventId":"local-1","tenantId":"demo","source":"generic","repo":"demo/repo","prNumber":1,"commitSha":"local","changedFiles":[],"logExcerpt":"build passed","receivedAt":"2026-01-01T00:00:00Z"}'
```

Successful ingest returns `202 Accepted` after the event is published.

## HTTP endpoints

| Endpoint | Purpose | Authentication |
| --- | --- | --- |
| `POST /events` | Accept a schema-valid generic `CiEvent` JSON body | Configure network access appropriately |
| `POST /webhook/github` | Accept a GitHub-shaped payload | `X-Hub-Signature-256` HMAC and `GITHUB_WEBHOOK_SECRET` |
| `GET /internal/actuator/health` | Health check | Keep management port private |
| `GET /internal/actuator/metrics` | Metrics | Keep management port private |

GitHub requests may also provide `X-Tenant-Id` and use
`X-GitHub-Delivery` as the event id. GitHub signature comparison is
constant-time. TLS is still required for confidentiality because HMAC signing
does not encrypt the request.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker address |
| `SERVER_PORT` | `8080` | Public application port |
| `MANAGEMENT_SERVER_PORT` | `8081` | Actuator port |
| `MANAGEMENT_SERVER_ADDRESS` | `0.0.0.0` | Actuator bind address; restrict it at deployment |
| `GITHUB_WEBHOOK_SECRET` | none | Required GitHub HMAC secret |
| `SENTINEL_AGENT_URL` | `http://127.0.0.1:11434/api/chat` | Ollama-compatible chat endpoint |
| `SENTINEL_AGENT_MODEL` | `qwen3:8b` | Local triage model |
| `SENTINEL_AGENT_TIMEOUT` | `8s` | Agent request timeout |
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
