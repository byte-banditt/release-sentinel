package com.releasesentinel.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public class ReviewJobStore {
    private final JdbcTemplate jdbc;
    public ReviewJobStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional public void enqueue(String tenant, String deliveryId, String payloadHash, String repository, int pr, String headSha) {
        jdbc.update("INSERT INTO pr_review_jobs(tenant_id,delivery_id,payload_sha256,repository,pull_request_number,head_sha,state,available_at) VALUES (?,?,?,?,?,?,'PENDING',?) ON CONFLICT DO NOTHING",
                tenant, deliveryId, payloadHash, repository, pr, headSha, ts(Instant.now()));
        String existingHash = jdbc.queryForObject("SELECT payload_sha256 FROM pr_review_jobs WHERE tenant_id=? AND delivery_id=?", String.class, tenant, deliveryId);
        if (!existingHash.equals(payloadHash)) throw new IllegalStateException("delivery id was reused with a different payload");
    }

    @Transactional public Optional<Job> claim() {
        var rows = jdbc.query("SELECT tenant_id,delivery_id,payload_sha256,repository,pull_request_number,head_sha FROM pr_review_jobs WHERE state='PENDING' AND available_at<=? AND (lease_until IS NULL OR lease_until<?) ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                (rs, n) -> new Job(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6)), ts(Instant.now()), ts(Instant.now()));
        if (rows.isEmpty()) return Optional.empty();
        Job job = rows.getFirst();
        jdbc.update("UPDATE pr_review_jobs SET lease_until=? WHERE tenant_id=? AND delivery_id=?", ts(Instant.now().plusSeconds(240)), job.tenant(), job.deliveryId());
        return Optional.of(job);
    }

    @Transactional public void complete(Job job) {
        jdbc.update("UPDATE pr_review_jobs SET state='COMPLETE',lease_until=NULL,last_error=NULL WHERE tenant_id=? AND delivery_id=?", job.tenant(), job.deliveryId());
    }

    @Transactional public boolean retry(Job job, Exception error) {
        jdbc.update("UPDATE pr_review_jobs SET attempts=attempts+1,available_at=clock_timestamp() + LEAST(300,5*power(2,LEAST(attempts,6))) * interval '1 second',lease_until=NULL,last_error=? WHERE tenant_id=? AND delivery_id=? AND state='PENDING'",
                error.getClass().getSimpleName(), job.tenant(), job.deliveryId());
        int attempts = jdbc.queryForObject("SELECT attempts FROM pr_review_jobs WHERE tenant_id=? AND delivery_id=?", Integer.class, job.tenant(), job.deliveryId());
        if (attempts < 10) return false;
        jdbc.update("UPDATE pr_review_jobs SET state='FAILED' WHERE tenant_id=? AND delivery_id=?", job.tenant(), job.deliveryId());
        return true;
    }

    public record Job(String tenant, String deliveryId, String payloadHash, String repository, int pullNumber, String headSha) {}
    private static java.sql.Timestamp ts(Instant value) { return java.sql.Timestamp.from(value); }
}
