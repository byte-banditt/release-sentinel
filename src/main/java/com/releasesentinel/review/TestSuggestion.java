package com.releasesentinel.review;

import java.util.List;
import java.time.Instant;

public record TestSuggestion(java.util.UUID suggestionId, String status, String title, String target, String scenario, String rationale, String expectedBehavior,
                             String sourcePath, int sourceLine, String framework, String artifactPath,
                             String artifactText, List<String> assumptions, String decisionBy, String decisionReason, Instant decisionAt) {
    public TestSuggestion { assumptions = assumptions == null ? List.of() : List.copyOf(assumptions); }
}
