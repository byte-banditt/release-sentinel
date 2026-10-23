# release-sentinel

Java 21, Spring Boot `4.1.1`, single Maven module. This is a plain library: adapters produce validated `CiEvent` records, rules produce findings, and `ServiceGraph` computes dependent blast radius.

`service-graph.yaml` is a declared graph, not an inferred graph. Ownership exists only where paths are explicitly declared. Graph traversal is cycle-safe BFS over declared `dependsOn` edges.

There is no database, Kafka, Spring application, or runtime service in S1. `GitHubAdapter` verifies `X-Hub-Signature-256` using constant-time comparison; `GenericJsonAdapter` accepts only schema-valid `CiEvent` JSON.

Run tests with:

```bash
./mvnw test
```
