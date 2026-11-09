package com.releasesentinel.review;

import java.util.List;
import java.util.Map;

public interface PullRequestReviewAgent {
    ReviewOutput review(Map<String, Object> context) throws Exception;
    List<TestSuggestion> suggestTests(Map<String, Object> context) throws Exception;

    record ReviewOutput(String summary, String overallRisk, List<ReviewFinding> findings) {
        public ReviewOutput { findings = findings == null ? List.of() : List.copyOf(findings); }
    }
}
