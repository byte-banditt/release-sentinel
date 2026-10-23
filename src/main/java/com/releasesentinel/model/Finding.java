package com.releasesentinel.model;

public record Finding(String ruleId, Severity severity, String path, String evidence) {
    public Finding {
        if (ruleId == null || ruleId.isBlank() || severity == null || path == null || evidence == null) {
            throw new IllegalArgumentException("finding fields are required");
        }
    }
}
