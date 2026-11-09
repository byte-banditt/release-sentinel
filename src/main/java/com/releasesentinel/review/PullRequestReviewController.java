package com.releasesentinel.review;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PullRequestReviewController {
    private final PullRequestReviewService reviews;
    private final ReviewStore store;
    private final ReviewApiAuthenticator apiAuth;
    private final CiReportAuthenticator ciAuth;
    private final ObjectMapper mapper;

    public PullRequestReviewController(PullRequestReviewService reviews, ReviewApiAuthenticator apiAuth,
                                       CiReportAuthenticator ciAuth, ObjectMapper mapper) {
        this.reviews = reviews; this.store = reviews.store(); this.apiAuth = apiAuth; this.ciAuth = ciAuth; this.mapper = mapper;
    }

    public record ReviewRequest(String repository, int pullRequestNumber) {}
    public record DecisionRequest(String reason) {}
    public record BindRequest(String commitSha, List<UUID> suggestionIds) {}
    public record CiReportRequest(String reviewId, String repository, int pullRequestNumber, String commitSha,
                                  long workflowRunId, String workflowUrl, String status, String conclusion,
                                  Integer testsPassed, Integer testsFailed, Integer testsSkipped, String testSuite,
                                  String workflowName, String workflowPath, String testStepConclusion) {}
    public record ResolveRequest(String repository, int pullRequestNumber, String commitSha) {}

    @PostMapping("/reviews")
    public ResponseEntity<PullRequestReview> create(@RequestBody ReviewRequest request, HttpServletRequest http) {
        String tenant = apiAuth.tenant(http);
        validateRepository(request.repository(), request.pullRequestNumber());
        return ResponseEntity.ok(reviews.analyze(tenant, request.repository(), request.pullRequestNumber(), false));
    }

    @GetMapping("/reviews")
    public List<PullRequestReview> list(@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset, HttpServletRequest http) {
        return reviews.list(apiAuth.tenant(http), limit, offset);
    }

    @GetMapping("/reviews/{id}")
    public PullRequestReview get(@PathVariable UUID id, HttpServletRequest http) { return reviews.get(apiAuth.tenant(http), id); }

    @PostMapping("/reviews/{id}/reanalyze")
    public ResponseEntity<PullRequestReview> reanalyze(@PathVariable UUID id, HttpServletRequest http) {
        String tenant = apiAuth.tenant(http);
        PullRequestReview old = reviews.get(tenant, id);
        return ResponseEntity.ok(reviews.analyze(tenant, old.repository(), old.pullRequestNumber(), true));
    }

    @GetMapping("/reviews/{id}/findings")
    public List<ReviewFinding> findings(@PathVariable UUID id, HttpServletRequest http) { return reviews.get(apiAuth.tenant(http), id).findings(); }

    @GetMapping("/reviews/{id}/test-suggestions")
    public List<TestSuggestion> suggestions(@PathVariable UUID id, HttpServletRequest http) { return reviews.get(apiAuth.tenant(http), id).testSuggestions(); }

    @PostMapping("/test-suggestions/{id}/under-review")
    public Map<String, String> underReview(@PathVariable UUID id, HttpServletRequest http) {
        var principal = apiAuth.principal(http);
        store.underReview(principal.tenantId(), principal.actorId(), id); return Map.of("suggestionId", id.toString(), "status", "UNDER_REVIEW");
    }

    @PostMapping("/test-suggestions/{id}/approve")
    public Map<String, String> approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest request, HttpServletRequest http) {
        var principal = apiAuth.principal(http);
        store.decide(principal.tenantId(), principal.actorId(), id, true, request == null ? "" : request.reason());
        return Map.of("suggestionId", id.toString(), "status", "APPROVED");
    }

    @PostMapping("/test-suggestions/{id}/reject")
    public Map<String, String> reject(@PathVariable UUID id, @RequestBody DecisionRequest request, HttpServletRequest http) {
        if (request == null || request.reason() == null || request.reason().isBlank() || request.reason().length() > 2000)
            throw new IllegalArgumentException("rejection reason is required");
        var principal = apiAuth.principal(http);
        store.decide(principal.tenantId(), principal.actorId(), id, false, request.reason());
        return Map.of("suggestionId", id.toString(), "status", "REJECTED");
    }

    @GetMapping("/test-suggestions/{id}/artifact")
    public ResponseEntity<String> artifact(@PathVariable UUID id, HttpServletRequest http) {
        var suggestion = store.suggestion(apiAuth.tenant(http), id).orElseThrow(() -> new IllegalArgumentException("suggestion not found"));
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(suggestion.artifactText());
    }

    @GetMapping("/test-suggestions/{id}/export")
    public ResponseEntity<String> export(@PathVariable UUID id, HttpServletRequest http) {
        String tenant = apiAuth.tenant(http);
        var suggestion = store.suggestion(tenant, id).orElseThrow(() -> new IllegalArgumentException("suggestion not found"));
        if (!store.export(tenant, id)) throw new IllegalStateException("only APPROVED suggestions may be exported");
        String filename = suggestion.artifactPath().substring(suggestion.artifactPath().lastIndexOf('/') + 1);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.TEXT_PLAIN).body(suggestion.artifactText());
    }

    @PostMapping("/reviews/{id}/bind-validation-commit")
    public Map<String, Object> bind(@PathVariable UUID id, @RequestBody BindRequest request, HttpServletRequest http) {
        String tenant = apiAuth.tenant(http);
        if (request.commitSha() == null || !request.commitSha().matches("[0-9a-fA-F]{40,64}")) throw new IllegalArgumentException("invalid commit SHA");
        var binding = reviews.bind(tenant, id, request.commitSha(), request.suggestionIds());
        return Map.of("reviewId", id, "repository", binding.repository(), "pullRequestNumber", binding.pullNumber(),
                "originalHeadSha", binding.originalHeadSha(), "commitSha", binding.commitSha(), "suggestionIds", binding.suggestionIds(),
                "artifactHash", binding.artifactHash(), "boundAt", binding.boundAt().toString());
    }

    @PostMapping("/reviews/{id}/ci-results")
    public ReviewStore.CiResult ciResult(@PathVariable UUID id, @RequestBody String body,
                                         @RequestHeader("X-Sentinel-Timestamp") String timestamp,
                                         @RequestHeader("X-Sentinel-Signature") String signature) {
        String tenant = ciAuth.authenticate(body, timestamp, signature);
        try {
            if (body.length() > 64_000) throw new IllegalArgumentException("CI report exceeds size limit");
            CiReportRequest request = mapper.readerFor(CiReportRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);
            if (!id.toString().equals(request.reviewId()) || request.status() == null || !request.status().equals("COMPLETED")
                    || request.workflowRunId() < 1 || request.workflowUrl() == null
                    || !request.workflowUrl().matches("https://github\\.com/" + java.util.regex.Pattern.quote(request.repository()) + "/actions/runs/" + request.workflowRunId())
                    || request.testsPassed() != null && request.testsPassed() < 0
                    || request.testsFailed() != null && request.testsFailed() < 0
                    || request.testsSkipped() != null && request.testsSkipped() < 0
                    || !"maven-test".equals(request.testSuite()) || !"Release Sentinel Tests".equals(request.workflowName())
                    || !".github/workflows/release-sentinel-tests.yml".equals(request.workflowPath())
                    || !List.of("success", "failure", "skipped", "cancelled").contains(request.testStepConclusion()))
                throw new IllegalArgumentException("invalid completed test report");
            if (!List.of("success", "failure", "cancelled", "timed_out", "action_required", "stale", "startup_failure", "neutral", "skipped").contains(request.conclusion()))
                throw new IllegalArgumentException("invalid workflow conclusion");
            return store.report(tenant, new ReviewStore.CiResult(request.workflowRunId(), id, request.repository(),
                    request.pullRequestNumber(), request.commitSha(), request.workflowUrl(), request.conclusion(), request.testsPassed(),
                    request.testsFailed(), request.testsSkipped(), request.testSuite(), request.workflowName(), request.workflowPath(),
                    request.testStepConclusion(), Instant.now()));
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("invalid CI report JSON", e); }
    }

    @GetMapping("/reviews/{id}/validation")
    public Map<String, Object> validation(@PathVariable UUID id, HttpServletRequest http) {
        String tenant = apiAuth.tenant(http);
        PullRequestReview review = reviews.get(tenant, id);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("binding", store.binding(tenant, id).orElse(null));
        response.put("results", store.results(tenant, id));
        response.put("suggestions", review.testSuggestions().stream().map(s -> Map.of("suggestionId", s.suggestionId(), "status", s.status())).toList());
        return response;
    }

    @PostMapping("/validation-bindings/resolve")
    public List<Map<String, Object>> resolveBindings(@RequestBody String body,
            @RequestHeader("X-Sentinel-Timestamp") String timestamp,
            @RequestHeader("X-Sentinel-Signature") String signature) {
        String tenant = ciAuth.authenticate(body, timestamp, signature);
        try {
            if (body.length() > 64_000) throw new IllegalArgumentException("resolve request exceeds size limit");
            ResolveRequest request = mapper.readerFor(ResolveRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);
            validateRepository(request.repository(), request.pullRequestNumber());
            if (request.commitSha() == null || !request.commitSha().matches("[0-9a-fA-F]{40,64}")) throw new IllegalArgumentException("invalid commit SHA");
            return store.bindingsForCommit(tenant, request.repository(), request.pullRequestNumber(), request.commitSha());
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("invalid binding resolve JSON", e); }
    }

    private static void validateRepository(String repository, int number) {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || number < 1)
            throw new IllegalArgumentException("invalid pull request identity");
    }
}
