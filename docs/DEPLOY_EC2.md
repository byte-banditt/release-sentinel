# EC2 deployment

This follows the `flash-sale-engine/scripts/deploy-ec2.sh` pattern. The
repository's existing sizing record contains no EC2 instance type or RAM; it
only records `JAVA_TOOL_OPTIONS=-Xmx256m` and Kafka `-Xms128m -Xmx256m`.
Therefore these are defaults, not an instance-sizing claim. Verify host RAM
before deployment and adjust `.env` deliberately.

Install Java 21, Docker Engine, and the Compose plugin. Restrict SSH to the
operator IP and expose only the webhook port required by the deployment.
Kafka is on the private Compose network; Compose publishes management only on
the EC2 loopback at `127.0.0.1:8081`.

```bash
cp .env.example .env
chmod 600 .env
./scripts/deploy-ec2.sh
docker compose --profile ec2 --env-file .env ps
```

## Smoke test

Run on the instance. It covers application reachability, event acceptance, a
broker publish acknowledgement, analysis, and verdict publication. It does
not measure load, throughput, failover, durability, or availability.

```bash
curl -i -X POST http://127.0.0.1:8080/events \
  -H 'content-type: application/json' \
  --data '{"schemaVersion":1,"eventId":"smoke-1","tenantId":"smoke","source":"generic","repo":"smoke/repo","prNumber":1,"commitSha":"smoke","changedFiles":[],"logExcerpt":"build passed","receivedAt":"2026-01-01T00:00:00Z"}'
docker compose --profile ec2 --env-file .env logs --tail=100 app kafka
curl -s http://127.0.0.1:8081/internal/actuator/health
```

## Recovery

Restart Kafka without deleting its KRaft volume:

```bash
docker compose --profile ec2 --env-file .env restart kafka
docker compose --profile ec2 --env-file .env logs --tail=100 kafka app
```

Inspect malformed events and verdict delivery failures locally in the broker:

```bash
docker compose --profile ec2 --env-file .env exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic ci.events.dlq --from-beginning --max-messages 20
docker compose --profile ec2 --env-file .env exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic ci.verdicts.DLT --from-beginning --max-messages 20
```

Replay only after inspecting the cause. Re-publish a corrected event with
`scripts/replay_eval.py`; receiver-side delivery idempotency makes duplicate
effects possible but safe when the receiver honors `X-Sentinel-Delivery-Id`.
Keep `kafka_data` for recovery. Teardown removes containers; volume deletion
is a separate, destructive operator action:

```bash
docker compose --profile ec2 --env-file .env down
```

## Observability

Every stage places `eventId` in SLF4J MDC. Actuator is not public:
Compose binds management to host loopback; its container listener uses the
internal interface. The base path is `/internal/actuator`, port `8081`.
Metrics include the verdict latency histogram, agent
fallback, egress retry and DLT counters. Spring Kafka's resolved
`MicrometerConsumerListener`/`MicrometerProducerListener` bind Kafka client
metrics, including consumer lag metrics exposed by the client.

Spring Kafka `4.1.1` exposes observation setters on `ContainerProperties`, but
the resolved Spring Boot configuration metadata does not expose a stable Kafka
observation property binding here, and no OTel exporter is configured. Kafka
OpenTelemetry tracing is therefore intentionally skipped; this is not a claim
that tracing is deployed.
