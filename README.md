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

Start the local single-node KRaft broker with `docker compose up -d`.
