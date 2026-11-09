# ADR: Durable storage for AI PR reviews

- Status: accepted for the AI PR-review extension
- Date: 2026-11-09

## Context

The existing service uses Kafka for at-least-once event transport and has no
queryable durable store. Review findings, candidate test artifacts, reviewer
decisions, commit bindings, CI reports, webhook delivery receipts, and pending
outbox work must survive restart and be safe across application replicas.

## Decision

Use PostgreSQL with Spring JDBC and Flyway migrations. Store review metadata,
findings, suggestions, decisions, validation bindings, and CI run records in
transactional relational tables. Use JSONB only for bounded structured finding
and identifier lists. Keep Kafka as the event transport. Use a PostgreSQL
outbox/inbox receipt for durable webhook idempotency and dispatch.

The Compose profile includes a PostgreSQL service and named volume. PostgreSQL
is not used as a replacement for Kafka. The application does not execute
generated tests or source code.

## Migration and rollback

`V1__ai_pr_review.sql` only adds tables and indexes; it does not rewrite or
delete existing Kafka data or event records. Flyway applies it before the new
application accepts requests. Deploy PostgreSQL and its persistent volume
first, then roll out the application. Old application versions do not read the
new tables and may be restored while PostgreSQL remains intact. Do not remove
the volume during rollback. No contraction or automatic destructive migration
is included.

The application requires PostgreSQL once this version is deployed. A mixed
version rollout can therefore run old and new application instances against
the same Kafka topics, while only new instances use the review tables. The
outbox's event JSON preserves the existing `CiEvent` shape; `ChangedFile` adds
only an optional `previousPath` for renames.

## Operations and recovery

Back up the named volume with the deployment's normal PostgreSQL backup
process. A portable logical backup is:

```bash
docker compose --profile ec2 exec -T postgres pg_dump -U sentinel release_sentinel > release-sentinel.sql
```

Restore into a stopped application and a separately prepared database using
the normal PostgreSQL restore procedure. Review artifacts and delivery receipts
currently have no automatic expiry; operators must retain and back up the
database accordingly. Delivery receipts are small hashes and identifiers, but
their indefinite retention is a known storage-growth tradeoff.

## Consequences

PostgreSQL adds one required runtime service and backup responsibility. It
provides multi-instance locking, transactional state transitions, and durable
queryable artifacts without treating Kafka as a database. Flyway's append-only
migration history gives a forward-only schema change and leaves rollback data
intact.
