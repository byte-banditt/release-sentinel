package com.releasesentinel.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public class CiEventOutboxStore {
    private final JdbcTemplate jdbc;
    public CiEventOutboxStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional public boolean enqueue(String tenant, String eventId, String payloadHash, String json) {
        jdbc.update("INSERT INTO github_deliveries(tenant_id,delivery_id,payload_sha256,state,available_at,received_at) VALUES (?,?,?,'PENDING',?,?) ON CONFLICT DO NOTHING",
                tenant, eventId, payloadHash, ts(Instant.now()), ts(Instant.now()));
        var existing = jdbc.query("SELECT payload_sha256,state FROM github_deliveries WHERE tenant_id=? AND delivery_id=? FOR UPDATE",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, tenant, eventId);
        if (existing.isEmpty()) throw new IllegalStateException("delivery idempotency record unavailable");
        if (!existing.getFirst()[0].equals(payloadHash)) throw new IllegalStateException("delivery id was reused with a different payload");
        if ("DELIVERED".equals(existing.getFirst()[1])) return false;
        jdbc.update("INSERT INTO ci_event_outbox(tenant_id,delivery_id,event_json) VALUES (?,?,?::jsonb) ON CONFLICT DO NOTHING", tenant, eventId, json);
        return true;
    }

    @Transactional public Optional<OutboxItem> claim(String tenant, String eventId) {
        var rows = jdbc.query("SELECT d.tenant_id,d.delivery_id,o.event_json::text FROM github_deliveries d JOIN ci_event_outbox o USING(tenant_id,delivery_id) WHERE d.tenant_id=? AND d.delivery_id=? AND d.state='PENDING' AND d.available_at<=? AND (d.lease_until IS NULL OR d.lease_until<?) FOR UPDATE OF d SKIP LOCKED",
                (rs, n) -> new OutboxItem(rs.getString(1), rs.getString(2), rs.getString(3)), tenant, eventId, ts(Instant.now()), ts(Instant.now()));
        if (rows.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE github_deliveries SET lease_until=? WHERE tenant_id=? AND delivery_id=?", ts(Instant.now().plusSeconds(60)), tenant, eventId);
        return Optional.of(rows.getFirst());
    }

    @Transactional public Optional<OutboxItem> claimNext() {
        var rows = jdbc.query("SELECT d.tenant_id,d.delivery_id,o.event_json::text FROM github_deliveries d JOIN ci_event_outbox o USING(tenant_id,delivery_id) WHERE d.state='PENDING' AND d.available_at<=? AND (d.lease_until IS NULL OR d.lease_until<?) ORDER BY d.available_at LIMIT 1 FOR UPDATE OF d SKIP LOCKED",
                (rs, n) -> new OutboxItem(rs.getString(1), rs.getString(2), rs.getString(3)), ts(Instant.now()), ts(Instant.now()));
        if (rows.isEmpty()) return Optional.empty();
        OutboxItem item = rows.getFirst();
        jdbc.update("UPDATE github_deliveries SET lease_until=? WHERE tenant_id=? AND delivery_id=?", ts(Instant.now().plusSeconds(60)), item.tenant(), item.eventId());
        return Optional.of(item);
    }

    @Transactional public void delivered(OutboxItem item) {
        jdbc.update("DELETE FROM ci_event_outbox WHERE tenant_id=? AND delivery_id=?", item.tenant(), item.eventId());
        jdbc.update("UPDATE github_deliveries SET state='DELIVERED',lease_until=NULL,last_error=NULL WHERE tenant_id=? AND delivery_id=?", item.tenant(), item.eventId());
    }

    @Transactional public void retry(OutboxItem item, Exception failure) {
        jdbc.update("UPDATE github_deliveries SET attempts=attempts+1,available_at=? ,lease_until=NULL,last_error=? WHERE tenant_id=? AND delivery_id=? AND state='PENDING'",
                ts(Instant.now().plusSeconds(5)), failure.getClass().getSimpleName(), item.tenant(), item.eventId());
    }

    public record OutboxItem(String tenant, String eventId, String json) {}
    private static java.sql.Timestamp ts(Instant value) { return java.sql.Timestamp.from(value); }
}
