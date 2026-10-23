package com.releasesentinel.analyzer;

import java.util.List;

public record AgentResult(String classification, String rationale, List<String> evidenceLines) {
    public AgentResult {
        if (!List.of("PASS", "WARN", "BLOCK").contains(classification) || rationale == null || evidenceLines == null) {
            throw new IllegalArgumentException("invalid agent result schema");
        }
        evidenceLines = List.copyOf(evidenceLines);
    }
}
