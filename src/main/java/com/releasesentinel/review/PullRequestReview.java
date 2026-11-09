package com.releasesentinel.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PullRequestReview(UUID reviewId, String tenantId, String repository, int pullRequestNumber,
                                String baseSha, String headSha, String title, String description,
                                String status, String summary, String overallRisk, List<ReviewFinding> findings,
                                List<TestSuggestion> testSuggestions, Instant createdAt) {
    public PullRequestReview {
        findings = findings == null ? List.of() : List.copyOf(findings);
        testSuggestions = testSuggestions == null ? List.of() : List.copyOf(testSuggestions);
    }
}
