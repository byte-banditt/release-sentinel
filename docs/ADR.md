# Architecture decisions

1. **Rules before LLM.** Deterministic rules decide first; the agent may add
   evidence or raise severity, never lower a rules-derived `BLOCK`.
2. **Kafka is the seam and replay log.** `ci.events` and `ci.verdicts` isolate
   stages and permit controlled replay; delivery remains at-least-once.
3. **Receiver-side idempotency.** Crashes and retries can duplicate delivery;
   receivers deduplicate on `X-Sentinel-Delivery-Id`.
4. **Declared service graph.** Blast radius follows the committed graph; it is
   not inferred from repository structure.
5. **Modular monolith.** Ingest, analyzer, and egress are packages in one app,
   not independently deployed microservices.
