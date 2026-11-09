package com.releasesentinel.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

@Repository
public class ReviewStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    public ReviewStore(JdbcTemplate jdbc, ObjectMapper mapper, MeterRegistry metrics) { this.jdbc = jdbc; this.mapper = mapper; this.metrics = metrics; }

    @Transactional
    public PullRequestReview save(PullRequestReview review) {
        try {
            jdbc.update("""
                    INSERT INTO pr_reviews(review_id,tenant_id,repository,pull_request_number,base_sha,head_sha,title,description,review_status,summary,overall_risk,findings_json,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (review_id) DO NOTHING
                    """, review.reviewId(), review.tenantId(), review.repository(), review.pullRequestNumber(), review.baseSha(), review.headSha(),
                    review.title(), review.description(), review.status(), review.summary(), review.overallRisk(), mapper.writeValueAsString(review.findings()), timestamp(review.createdAt()));
            var stored = findByHead(review.tenantId(), review.repository(), review.pullRequestNumber(), review.headSha());
            if (stored.isEmpty()) throw new IllegalStateException("review persistence failed");
            PullRequestReview saved = stored.get();
            if (!saved.reviewId().equals(review.reviewId())) return saved;
            for (TestSuggestion suggestion : review.testSuggestions()) insertSuggestion(saved, suggestion);
            return get(saved.tenantId(), saved.reviewId()).orElseThrow();
        } catch (Exception e) { throw new IllegalStateException("review persistence failed", e); }
    }

    private void insertSuggestion(PullRequestReview review, TestSuggestion suggestion) throws Exception {
        UUID id = UUID.nameUUIDFromBytes((review.reviewId() + ":" + suggestion.artifactPath()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hash = sha256(suggestion.artifactText());
        jdbc.update("""
                INSERT INTO test_suggestions(suggestion_id,tenant_id,review_id,status,title,target,scenario,rationale,expected_behavior,source_path,source_line,framework,artifact_path,artifact_text,artifact_sha256,assumptions_json,created_at)
                VALUES (?,?,?,'GENERATED',?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (suggestion_id) DO NOTHING
                """, id, review.tenantId(), review.reviewId(), suggestion.title(), suggestion.target(), suggestion.scenario(),
                suggestion.rationale(), suggestion.expectedBehavior(), suggestion.sourcePath(), suggestion.sourceLine(), suggestion.framework(), suggestion.artifactPath(), suggestion.artifactText(), hash,
                mapper.writeValueAsString(suggestion.assumptions()), timestamp(review.createdAt()));
    }

    public Optional<PullRequestReview> get(String tenant, UUID id) {
        var rows = jdbc.query("SELECT * FROM pr_reviews WHERE tenant_id=? AND review_id=?", (rs, n) -> new PullRequestReview(id,
                rs.getString("tenant_id"), rs.getString("repository"), rs.getInt("pull_request_number"), rs.getString("base_sha"),
                rs.getString("head_sha"), rs.getString("title"), rs.getString("description"), rs.getString("review_status"),
                rs.getString("summary"), rs.getString("overall_risk"), readJson(rs.getString("findings_json"), new TypeReference<List<ReviewFinding>>() {}),
                List.of(), rs.getTimestamp("created_at").toInstant()), tenant, id);
        if (rows.isEmpty()) return Optional.empty();
        PullRequestReview r = rows.getFirst();
        return Optional.of(new PullRequestReview(r.reviewId(), r.tenantId(), r.repository(), r.pullRequestNumber(), r.baseSha(), r.headSha(),
                r.title(), r.description(), r.status(), r.summary(), r.overallRisk(), r.findings(), getSuggestions(tenant, id), r.createdAt()));
    }

    private List<TestSuggestion> getSuggestions(String tenant, UUID reviewId) {
        return jdbc.query("SELECT suggestion_id,status,title,target,scenario,rationale,expected_behavior,source_path,source_line,framework,artifact_path,artifact_text,assumptions_json,decision_by,decision_reason,decision_at FROM test_suggestions WHERE tenant_id=? AND review_id=? ORDER BY created_at,suggestion_id",
                (rs, n) -> new TestSuggestion(rs.getObject("suggestion_id", UUID.class), rs.getString("status"), rs.getString("title"),
                        rs.getString("target"), rs.getString("scenario"), rs.getString("rationale"), rs.getString("expected_behavior"),
                        rs.getString("source_path"), rs.getInt("source_line"), rs.getString("framework"), rs.getString("artifact_path"),
                        rs.getString("artifact_text"), readJson(rs.getString("assumptions_json"), new TypeReference<List<String>>() {}),
                        rs.getString("decision_by"), rs.getString("decision_reason"), optionalTimestamp(rs.getTimestamp("decision_at"))), tenant, reviewId);
    }

    public Optional<PullRequestReview> findByHead(String tenant, String repo, int pr, String sha) {
        var rows = jdbc.query("SELECT review_id FROM pr_reviews WHERE tenant_id=? AND repository=? AND pull_request_number=? AND head_sha=? ORDER BY created_at DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), tenant, repo, pr, sha);
        return rows.isEmpty() ? Optional.empty() : get(tenant, rows.getFirst());
    }

    public List<PullRequestReview> list(String tenant, int limit, int offset) {
        return jdbc.query("SELECT review_id FROM pr_reviews WHERE tenant_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> rs.getObject(1, UUID.class), tenant, limit, offset).stream().map(id -> get(tenant, id).orElseThrow()).toList();
    }

    public record SuggestionRow(UUID id, UUID reviewId, String status, String artifactPath, String artifactText, String artifactHash) {}
    public Optional<SuggestionRow> suggestion(String tenant, UUID id) {
        var rows = jdbc.query("SELECT suggestion_id,review_id,status,artifact_path,artifact_text,artifact_sha256 FROM test_suggestions WHERE tenant_id=? AND suggestion_id=?",
                (rs, n) -> new SuggestionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), tenant, id);
        return rows.stream().findFirst();
    }

    @Transactional public void underReview(String tenant, String actor, UUID id) {
        if (jdbc.update("UPDATE test_suggestions SET status='UNDER_REVIEW',decision_by=?,decision_at=? WHERE tenant_id=? AND suggestion_id=? AND status='GENERATED'", actor, timestamp(Instant.now()), tenant, id) != 1)
            throw new IllegalStateException("suggestion is missing or cannot enter review");
    }

    @Transactional public void decide(String tenant, String actor, UUID id, boolean approve, String reason) {
        String next = approve ? "APPROVED" : "REJECTED";
        int changed = jdbc.update("UPDATE test_suggestions SET status=?,decision_at=?,decision_by=?,decision_reason=? WHERE tenant_id=? AND suggestion_id=? AND status IN ('GENERATED','UNDER_REVIEW')", next, timestamp(Instant.now()), actor, reason == null ? "" : reason, tenant, id);
        if (changed != 1) throw new IllegalStateException("suggestion is missing or no longer reviewable");
        metrics.counter(approve ? "sentinel.pr.test.suggestions.approved" : "sentinel.pr.test.suggestions.rejected").increment();
    }

    public String suggestionStatus(String tenant, UUID id) {
        var rows = jdbc.query("SELECT status FROM test_suggestions WHERE tenant_id=? AND suggestion_id=?", (rs, n) -> rs.getString(1), tenant, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("suggestion not found");
        return rows.getFirst();
    }

    public record Binding(String tenant, String repository, int pullNumber, String originalHeadSha, String commitSha,
                          List<UUID> suggestionIds, String artifactHash, Instant boundAt) {}

    @Transactional public Binding bind(String tenant, UUID reviewId, String commitSha, List<UUID> suggestionIds) {
        if (suggestionIds == null || suggestionIds.isEmpty() || suggestionIds.size() > 100 || suggestionIds.stream().distinct().count() != suggestionIds.size())
            throw new IllegalArgumentException("accepted suggestion ids are required and must be unique");
        PullRequestReview review = get(tenant, reviewId).orElseThrow(() -> new IllegalArgumentException("review not found"));
        String joined = String.join(",", suggestionIds.stream().map(UUID::toString).sorted().toList());
        StringBuilder artifacts = new StringBuilder();
        for (UUID id : suggestionIds) {
            SuggestionRow s = suggestion(tenant, id).orElseThrow(() -> new IllegalArgumentException("suggestion not found"));
            if (!s.reviewId().equals(reviewId) || !List.of("APPROVED", "EXPORTED").contains(s.status()))
                throw new IllegalStateException("only approved suggestions from this review can be bound");
            artifacts.append(id).append(':').append(s.artifactHash()).append('\n');
        }
        String artifactHash = sha256(artifacts.toString());
        Instant boundAt = Instant.now();
        jdbc.update("INSERT INTO validation_bindings(review_id,tenant_id,repository,pull_request_number,original_head_sha,commit_sha,suggestion_ids,artifact_hash,bound_at) VALUES (?,?,?,?,?,?,?::jsonb,?,?)",
                reviewId, tenant, review.repository(), review.pullRequestNumber(), review.headSha(), commitSha,
                toJson(suggestionIds), artifactHash, timestamp(boundAt));
        updateSuggestionStatuses(tenant, suggestionIds, "VALIDATION_PENDING", false);
        return new Binding(tenant, review.repository(), review.pullRequestNumber(), review.headSha(), commitSha, suggestionIds, artifactHash, boundAt);
    }

    public Optional<Binding> binding(String tenant, UUID reviewId) {
        var rows = jdbc.query("SELECT repository,pull_request_number,original_head_sha,commit_sha,suggestion_ids,artifact_hash,bound_at FROM validation_bindings WHERE tenant_id=? AND review_id=?",
                (rs, n) -> new Binding(tenant, rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        readJson(rs.getString(5), new TypeReference<List<UUID>>() {}), rs.getString(6), rs.getTimestamp(7).toInstant()), tenant, reviewId);
        return rows.stream().findFirst();
    }

    public record CiResult(long workflowRunId, UUID reviewId, String repository, int pullNumber, String commitSha,
                           String workflowUrl, String conclusion, Integer testsPassed, Integer testsFailed,
                           Integer testsSkipped, String testSuite, String workflowName, String workflowPath,
                           String testStepConclusion, Instant reportedAt) {}

    @Transactional public CiResult report(String tenant, CiResult result) {
        Binding binding = binding(tenant, result.reviewId()).orElseThrow(() -> new IllegalArgumentException("validation commit is not bound"));
        if (!binding.repository().equals(result.repository()) || binding.pullNumber() != result.pullNumber()
                || !binding.commitSha().equals(result.commitSha())) throw new IllegalArgumentException("CI result does not match bound repository, PR, and commit");
        var existing = jdbc.query("SELECT review_id,repository,pull_request_number,commit_sha,workflow_url,conclusion,tests_passed,tests_failed,tests_skipped,test_suite,workflow_name,workflow_path,test_step_conclusion FROM ci_results WHERE tenant_id=? AND workflow_run_id=?",
                (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), (Integer)rs.getObject(7), (Integer)rs.getObject(8), (Integer)rs.getObject(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13)}, tenant, result.workflowRunId());
        if (!existing.isEmpty()) {
            Object[] row = existing.getFirst();
            if (!row[0].equals(result.reviewId()) || !row[1].equals(result.repository()) || (int)row[2] != result.pullNumber()
                    || !row[3].equals(result.commitSha()) || !row[4].equals(result.workflowUrl()) || !row[5].equals(result.conclusion())
                    || !java.util.Objects.equals(row[6], result.testsPassed()) || !java.util.Objects.equals(row[7], result.testsFailed())
                    || !java.util.Objects.equals(row[8], result.testsSkipped()) || !row[9].equals(result.testSuite())
                    || !row[10].equals(result.workflowName()) || !row[11].equals(result.workflowPath()) || !row[12].equals(result.testStepConclusion()))
                throw new IllegalStateException("workflow run id was already reported with different data");
            return result;
        }
        int inserted = jdbc.update("INSERT INTO ci_results(tenant_id,workflow_run_id,review_id,repository,pull_request_number,commit_sha,workflow_url,status,conclusion,tests_passed,tests_failed,tests_skipped,test_suite,workflow_name,workflow_path,test_step_conclusion,reported_at) VALUES (?,?, ?,?,?,?,?, 'COMPLETED',?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id,workflow_run_id) DO NOTHING",
                tenant, result.workflowRunId(), result.reviewId(), result.repository(), result.pullNumber(), result.commitSha(),
                result.workflowUrl(), result.conclusion(), result.testsPassed(), result.testsFailed(), result.testsSkipped(), result.testSuite(),
                result.workflowName(), result.workflowPath(), result.testStepConclusion(), timestamp(result.reportedAt()));
        if (inserted == 0) {
            var duplicate = jdbc.queryForObject("SELECT review_id=? AND repository=? AND pull_request_number=? AND commit_sha=? AND workflow_url=? AND conclusion=? AND tests_passed IS NOT DISTINCT FROM ? AND tests_failed IS NOT DISTINCT FROM ? AND tests_skipped IS NOT DISTINCT FROM ? AND test_suite=? AND workflow_name=? AND workflow_path=? AND test_step_conclusion=? FROM ci_results WHERE tenant_id=? AND workflow_run_id=?",
                    Boolean.class, result.reviewId(), result.repository(), result.pullNumber(), result.commitSha(), result.workflowUrl(), result.conclusion(), result.testsPassed(), result.testsFailed(), result.testsSkipped(), result.testSuite(), result.workflowName(), result.workflowPath(), result.testStepConclusion(), tenant, result.workflowRunId());
            if (!Boolean.TRUE.equals(duplicate)) throw new IllegalStateException("workflow run id was already reported with different data");
            return result;
        }
        boolean passed = "success".equals(result.conclusion()) && "success".equals(result.testStepConclusion())
                && "Release Sentinel Tests".equals(result.workflowName()) && ".github/workflows/release-sentinel-tests.yml".equals(result.workflowPath())
                && result.testsFailed() != null && result.testsFailed() == 0
                && result.testsPassed() != null && result.testsPassed() > 0;
        boolean failed = !"success".equals(result.conclusion()) || !"success".equals(result.testStepConclusion())
                && !"skipped".equals(result.testStepConclusion())
                || result.testsFailed() != null && result.testsFailed() > 0;
        if (passed) updateSuggestionStatuses(tenant, binding.suggestionIds(), "VALIDATED", true);
        else if (failed) updateSuggestionStatuses(tenant, binding.suggestionIds(), "VALIDATION_FAILED", true);
        metrics.counter("sentinel.pr.validation.results", "result", passed ? "success" : failed ? "failure" : "pending").increment();
        return result;
    }

    public List<CiResult> results(String tenant, UUID reviewId) {
        return jdbc.query("SELECT workflow_run_id,review_id,repository,pull_request_number,commit_sha,workflow_url,conclusion,tests_passed,tests_failed,tests_skipped,test_suite,workflow_name,workflow_path,test_step_conclusion,reported_at FROM ci_results WHERE tenant_id=? AND review_id=? ORDER BY reported_at DESC",
                (rs, n) -> new CiResult(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        (Integer)rs.getObject(8), (Integer)rs.getObject(9), (Integer)rs.getObject(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getTimestamp(15).toInstant()), tenant, reviewId);
    }

    public List<Map<String, Object>> bindingsForCommit(String tenant, String repository, int pullNumber, String commitSha) {
        return jdbc.query("SELECT review_id,suggestion_ids,artifact_hash,bound_at FROM validation_bindings WHERE tenant_id=? AND repository=? AND pull_request_number=? AND commit_sha=? ORDER BY bound_at",
                (rs, n) -> Map.of("reviewId", rs.getObject(1, UUID.class), "suggestionIds", readJson(rs.getString(2), new TypeReference<List<UUID>>() {}),
                        "artifactHash", rs.getString(3), "boundAt", rs.getTimestamp(4).toInstant()), tenant, repository, pullNumber, commitSha);
    }

    public boolean export(String tenant, UUID id) {
        int updated = jdbc.update("UPDATE test_suggestions SET status='EXPORTED' WHERE tenant_id=? AND suggestion_id=? AND status='APPROVED'", tenant, id);
        if (updated == 1) metrics.counter("sentinel.pr.test.suggestions.exported").increment();
        return updated == 1 || "EXPORTED".equals(suggestionStatus(tenant, id));
    }

    private String toJson(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static java.sql.Timestamp timestamp(Instant value) { return java.sql.Timestamp.from(value); }
    private static Instant optionalTimestamp(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }
    private void updateSuggestionStatuses(String tenant, List<UUID> ids, String status, boolean preserveValidated) {
        for (UUID id : ids) {
            if (preserveValidated) jdbc.update("UPDATE test_suggestions SET status=? WHERE tenant_id=? AND suggestion_id=? AND status<>'VALIDATED'", status, tenant, id);
            else jdbc.update("UPDATE test_suggestions SET status=? WHERE tenant_id=? AND suggestion_id=?", status, tenant, id);
        }
    }

    static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("stored review JSON is invalid", e); }
    }
}
