package com.releasesentinel.model;

import java.util.List;

public record Verdict(int schemaVersion, String eventId, VerdictType verdict, List<Finding> findings,
                      DecidedBy decidedBy, long latencyMs) {
    public Verdict {
        if (schemaVersion < 1 || eventId == null || verdict == null || decidedBy == null || latencyMs < 0) {
            throw new IllegalArgumentException("verdict fields are invalid");
        }
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
