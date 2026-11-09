package com.releasesentinel.review;

import java.util.List;

public record ReviewFinding(String findingId, String assessment, ReviewCategory category, ReviewSeverity severity, String title,
                            String description, String filePath, String side, int startLine, int endLine,
                            List<String> evidence, String impact, String suggestedFix, String confidence) {
    public ReviewFinding { evidence = evidence == null ? List.of() : List.copyOf(evidence); }
}
