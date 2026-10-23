# release-sentinel

Java 21, Spring Boot `4.1.1`, single Maven module. This is a plain library: adapters produce validated `CiEvent` records, rules produce findings, and `ServiceGraph` computes dependent blast radius.

`service-graph.yaml` is a declared graph, not an inferred graph. Ownership exists only where paths are explicitly declared. Graph traversal is cycle-safe BFS over declared `dependsOn` edges.

There is no database, Kafka, Spring application, or runtime service in S1. `GitHubAdapter` verifies `X-Hub-Signature-256` using constant-time comparison; `GenericJsonAdapter` accepts only schema-valid `CiEvent` JSON.

## S2 pipeline

The single Spring Boot app uses `ci.events` and `ci.verdicts` as seams. HTTP ingest returns `202` only after an `acks=all` broker acknowledgement; analyzer sends the verdict before acknowledging the event offset; malformed events recover to `ci.events.dlq`; egress uses Spring Kafka non-blocking retry topics and `ci.verdicts.DLT`.

Delivery is at-least-once end to end. A crash after a publish and before an offset commit can duplicate a `CiEvent` or `Verdict`; the receiver deduplicates by `X-Sentinel-Delivery-Id`.

Configure `SENTINEL_WEBHOOK_URL_<TENANT>` plus either `SENTINEL_WEBHOOK_SECRET_<TENANT>` or `SENTINEL_WEBHOOK_SECRET_FILE_<TENANT>`; values are never committed. `GITHUB_WEBHOOK_SECRET` authenticates GitHub input.

### Threat model

Signing proves the body was produced by a holder of the shared secret.
The delivery id lets receivers reject duplicate effects after retries or crashes.
The timestamp limits replay of an otherwise valid signed request to 5 minutes.
Signing does not provide confidentiality; use TLS for confidentiality in transit.
The secret is symmetric and must be protected by both parties.
Signing is not asymmetric identity, authorization, or sender compromise protection.

Run tests with:

```bash
./mvnw test
```

## S4 operations and evaluation

The EC2 Compose profile runs this app and pinned single-node KRaft Kafka:

```bash
cp .env.example .env
./scripts/deploy-ec2.sh
```

See [`docs/DEPLOY_EC2.md`](docs/DEPLOY_EC2.md) for deployment, smoke test,
Kafka restart, DLT inspection, replay, and teardown. Actuator is bound to
host loopback `127.0.0.1:8081/internal/actuator`; it is not a public route. The app puts
`eventId` in logging MDC at ingest, analyze, and egress. Kafka client metrics,
verdict latency histogram, agent fallback, egress retry, and DLT counters are
available through Actuator.

The corpus is [`corpus/labels.jsonl`](corpus/labels.jsonl). It contains the
unchanged 30 injected DEV/TEST events plus a `REAL` split mined from actual
commit history. REAL events carry `provenance=REAL`, `sourceRepo`, and
`sourceCommit`; labels come from reading the actual parent-to-commit diff, not
from running the rules. The available histories yielded 6 faulty and 6 clean
REAL events, below the requested 10/10 target. See
[`eval/REAL_REPORT.md`](eval/REAL_REPORT.md).
Rules were frozen at commit `833740a`; TEST events were created after that
freeze. Replay/report:

```bash
python3 scripts/replay_eval.py --split TEST
python3 scripts/replay_eval.py --offline --split TEST
```

CI runs unit tests, Testcontainers tests, and the fixed offline fixture. It
does not call AWS.

See [`docs/ADR.md`](docs/ADR.md) for the rules/Kafka/idempotency/graph/
modular-monolith decisions.

## S3-lite local triage

Analyzer enrichment uses the `TriageAgent` interface and local Ollama
(`qwen3:8b`, temperature 0) through `OllamaTriageAgent`. Only a redacted
subset of each event is sent. Enrichment runs for failing logs or WARN rules;
timeouts and invalid responses preserve the rules verdict and use
`RULES_FALLBACK`. A rules BLOCK cannot be lowered. Configure
`SENTINEL_AGENT_URL`, `SENTINEL_AGENT_MODEL`, and `SENTINEL_AGENT_TIMEOUT`.
The evaluation and its unavailable-container Ollama result are in
[`eval/S3_LITE_REPORT.md`](eval/S3_LITE_REPORT.md).

## Limitations

Evaluation N is small. Labels are author-built. Injected faults share the
author's assumptions. REAL history was insufficient for 10 faulty and 10 clean
events; REAL rule misses and false positives remain uncorrected. Kafka
deployment is a single broker. The EC2 check is a smoke test only; it is not
load or availability evidence. Real AgentCore deployment is manual and
excluded from CI.

Start the local single-node KRaft broker with `docker compose up -d`.
