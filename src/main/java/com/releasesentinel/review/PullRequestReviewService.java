package com.releasesentinel.review;

import com.releasesentinel.adapter.GitHubPullRequestFiles;
import com.releasesentinel.model.CiEvent;
import com.releasesentinel.model.Finding;
import com.releasesentinel.model.Verdict;
import com.releasesentinel.graph.ServiceGraph;
import com.releasesentinel.rules.Rule;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PullRequestReviewService {
    private final GitHubPullRequestFiles github;
    private final PullRequestReviewAgent agent;
    private final ReviewStore store;
    private final List<Rule> rules;
    private final ServiceGraph graph;
    private final MeterRegistry metrics;

    public PullRequestReviewService(GitHubPullRequestFiles github, PullRequestReviewAgent agent, ReviewStore store,
                                   List<Rule> rules, ServiceGraph graph, MeterRegistry metrics) {
        this.github = github; this.agent = agent; this.store = store; this.rules = rules; this.graph = graph; this.metrics = metrics;
    }

    public PullRequestReview analyze(String tenant, String repository, int number, boolean force) {
        var pr = github.retrievePullRequest(repository, number);
        return analyze(tenant, pr, force);
    }

    public PullRequestReview analyze(String tenant, GitHubPullRequestFiles.PullRequestData pr, boolean force) {
        String repository = pr.repository();
        if (!force) {
            var existing = store.findByHead(tenant, repository, pr.number(), pr.headSha());
            if (existing.isPresent()) return existing.get();
        }
        CiEvent event = new CiEvent(1, "review-" + UUID.randomUUID(), tenant, "github", repository, pr.number(),
                pr.headSha(), pr.files(), "", Instant.now());
        List<Finding> deterministic = new ArrayList<>();
        for (Rule rule : rules) deterministic.addAll(rule.evaluate(event));
        deterministic.addAll(graph.blastRadius(event));
        Map<String, String> source = pr.sourceContext();
        String pom = github.retrieveRepositoryFile(repository, "pom.xml", pr.headSha(), 40_000);
        var context = ReviewContextBuilder.build(repository, pr.number(), pr.title(), pr.body(), pr.baseSha(), pr.headSha(),
                pr.files(), source, pom, deterministic);
        if (!pr.filesAvailable()) context = new ReviewContextBuilder.Context(context.payload(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
        return analyzeContext(tenant, repository, pr.number(), pr.title(), pr.body(), pr.baseSha(), pr.headSha(), context,
                supportsJunit5(pom), supportsMockito(pom));
    }

    public PullRequestReview recordUnavailable(String tenant, String repository, int number, String headSha) {
        var existing = store.findByHead(tenant, repository, number, headSha);
        if (existing.isPresent()) return existing.get();
        PullRequestReview unavailable = new PullRequestReview(UUID.randomUUID(), tenant, repository, number, "", headSha, "", "",
                "REVIEW_INSUFFICIENT_CONTEXT", "GitHub PR context was unavailable; no code findings were produced.", "WARN",
                List.of(), List.of(), Instant.now());
        PullRequestReview saved = store.save(unavailable);
        metrics.counter("sentinel.pr.reviews", "status", "REVIEW_INSUFFICIENT_CONTEXT").increment();
        return saved;
    }

    public PullRequestReview recordUnavailable(String tenant, GitHubPullRequestFiles.PullRequestData pr) {
        PullRequestReview unavailable = new PullRequestReview(UUID.randomUUID(), tenant, pr.repository(), pr.number(),
                pr.baseSha(), pr.headSha(), pr.title(), pr.body(), "REVIEW_INSUFFICIENT_CONTEXT",
                "GitHub changed-file patches are unavailable; no code findings were produced.", "WARN", List.of(), List.of(), Instant.now());
        PullRequestReview existing = store.findByHead(tenant, pr.repository(), pr.number(), pr.headSha()).orElse(null);
        if (existing != null) return existing;
        PullRequestReview saved = store.save(unavailable);
        metrics.counter("sentinel.pr.reviews", "status", "REVIEW_INSUFFICIENT_CONTEXT").increment();
        return saved;
    }

    private PullRequestReview analyzeContext(String tenant, String repository, int number, String title, String description,
                                            String baseSha, String headSha, ReviewContextBuilder.Context context,
                                            boolean junit5, boolean mockito) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        boolean enough = !context.paths().isEmpty() && context.changedLines().values().stream().anyMatch(lines -> !lines.isEmpty());
        String status = "REVIEW_INSUFFICIENT_CONTEXT";
        String summary = "Insufficient changed-line context for evidence-backed analysis.";
        String risk = "WARN";
        List<ReviewFinding> findings = List.of();
        List<TestSuggestion> suggestions = List.of();
        if (enough) {
            try {
                var output = agent.review(context.payload());
                findings = ReviewResultValidator.findings(output.findings(), context);
                status = findings.isEmpty() ? "REVIEW_CLEAN" : "REVIEW_FINDINGS";
                summary = com.releasesentinel.analyzer.CiEventRedactor.redact(output.summary());
                risk = output.overallRisk();
                if (junit5) {
                    try { suggestions = ReviewResultValidator.suggestions(agent.suggestTests(context.payload()), context, true, mockito); }
                    catch (Exception testGenerationFailure) {
                        metrics.counter("sentinel.pr.test.suggestions.generation", "outcome", "failure").increment();
                        summary += " Test suggestions unavailable after validation failure.";
                    }
                }
            } catch (Exception failure) {
                status = "REVIEW_FAILED";
                summary = "Review provider or deterministic output validation failed; no AI findings were accepted.";
                risk = "WARN";
                findings = List.of();
                suggestions = List.of();
            }
        }
        PullRequestReview review = new PullRequestReview(id, tenant, repository, number, baseSha, headSha,
                title == null ? "" : title, description == null ? "" : description, status, summary, risk,
                findings, suggestions, now);
        PullRequestReview saved = store.save(review);
        metrics.counter("sentinel.pr.reviews", "status", status).increment();
        findings.forEach(f -> metrics.counter("sentinel.pr.findings", "severity", f.severity().name(), "category", f.category().name()).increment());
        if (!suggestions.isEmpty()) metrics.counter("sentinel.pr.test.suggestions.generated").increment(suggestions.size());
        return saved;
    }

    public PullRequestReview get(String tenant, UUID id) { return store.get(tenant, id).orElseThrow(() -> new ReviewNotFoundException("review not found")); }
    public ReviewStore.Binding bind(String tenant, UUID id, String commitSha, List<UUID> suggestionIds) {
        PullRequestReview review = get(tenant, id);
        if (!github.commitBelongsToPullRequest(review.repository(), review.pullRequestNumber(), commitSha))
            throw new IllegalArgumentException("validation commit is not associated with the pull request");
        if (suggestionIds == null || suggestionIds.isEmpty() || suggestionIds.size() > 30
                || suggestionIds.stream().distinct().count() != suggestionIds.size())
            throw new IllegalArgumentException("accepted suggestion ids are required and must be unique");
        for (UUID suggestionId : suggestionIds) {
            ReviewStore.SuggestionRow suggestion = store.suggestion(tenant, suggestionId)
                    .orElseThrow(() -> new IllegalArgumentException("suggestion not found"));
            if (!suggestion.reviewId().equals(id) || !List.of("APPROVED", "EXPORTED").contains(suggestion.status()))
                throw new IllegalStateException("only approved suggestions from this review can be bound");
            String committedArtifact = github.retrieveRepositoryFile(review.repository(), suggestion.artifactPath(), commitSha, 50_000);
            if (!suggestion.artifactText().equals(committedArtifact))
                throw new IllegalArgumentException("validation commit does not contain the approved candidate artifact");
        }
        return store.bind(tenant, id, commitSha, suggestionIds);
    }
    private static boolean supportsJunit5(String pom) { return pom != null && (pom.contains("junit-jupiter") || pom.contains("spring-boot-starter-test")); }
    private static boolean supportsMockito(String pom) { return pom != null && (pom.contains("mockito") || pom.contains("spring-boot-starter-test")); }
    public List<PullRequestReview> list(String tenant, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100_000) throw new IllegalArgumentException("invalid pagination");
        return store.list(tenant, limit, offset);
    }
    public ReviewStore store() { return store; }
}
