package com.releasesentinel.review;

import com.releasesentinel.analyzer.CiEventRedactor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ReviewResultValidator {
    private ReviewResultValidator() {}

    static List<ReviewFinding> findings(List<ReviewFinding> input, ReviewContextBuilder.Context context) {
        Set<String> ids = new HashSet<>();
        for (ReviewFinding f : input) {
            if (f.findingId() == null || f.findingId().isBlank() || !ids.add(f.findingId())
                    || !Set.of("FACT", "HYPOTHESIS").contains(f.assessment()) || !Set.of("LEFT", "RIGHT").contains(f.side())
                    || f.category() == null || f.severity() == null || !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(f.severity().name())
                    || empty(f.title()) || empty(f.description()) || empty(f.impact()) || empty(f.suggestedFix())
                    || !Set.of("LOW", "MEDIUM", "HIGH").contains(f.confidence()) || f.evidence().isEmpty())
                throw new IllegalArgumentException("invalid review finding schema");
            if (!context.paths().contains(f.filePath())) throw new IllegalArgumentException("finding references a file outside the PR diff");
            var lines = "RIGHT".equals(f.side()) ? context.changedLines().getOrDefault(f.filePath(), Set.of())
                    : context.removedLines().getOrDefault(f.filePath(), Set.of());
            if (f.startLine() < 1 || f.endLine() < f.startLine() || f.endLine() - f.startLine() > 10
                    || !lines.contains(f.startLine()) || !lines.contains(f.endLine()))
                throw new IllegalArgumentException("finding line range is not in changed lines");
            String diff = context.diffs().get(f.filePath());
            Set<String> diffTextLines = diff.lines().filter(line -> line.startsWith("+") || line.startsWith("-") || line.startsWith(" "))
                    .map(line -> line.substring(1).trim()).collect(java.util.stream.Collectors.toSet());
            Set<String> sourceTextLines = "RIGHT".equals(f.side()) ? context.sourceContext().getOrDefault(f.filePath(), "").lines().map(String::trim).collect(java.util.stream.Collectors.toSet()) : Set.of();
            for (String evidence : f.evidence()) {
                if (empty(evidence) || evidence.length() > 1000
                        || evidence.lines().filter(line -> !line.isBlank()).anyMatch(line -> !diffTextLines.contains(line.trim()) && !sourceTextLines.contains(line.trim())))
                    throw new IllegalArgumentException("finding evidence is not present in retrieved PR context");
            }
        }
        return input.stream().map(f -> new ReviewFinding(f.findingId(), f.assessment(), f.category(), f.severity(),
                CiEventRedactor.redact(f.title()), CiEventRedactor.redact(f.description()), f.filePath(), f.side(), f.startLine(), f.endLine(),
                f.evidence().stream().map(CiEventRedactor::redact).toList(), CiEventRedactor.redact(f.impact()),
                CiEventRedactor.redact(f.suggestedFix()), f.confidence())).toList();
    }

    static List<TestSuggestion> suggestions(List<TestSuggestion> input, ReviewContextBuilder.Context context,
                                            boolean junit5Available, boolean mockitoAvailable) {
        if (!junit5Available) return List.of();
        Set<String> paths = new HashSet<>();
        for (TestSuggestion s : input) {
            if (!"JUNIT5".equals(s.framework()) || empty(s.title()) || empty(s.target()) || empty(s.scenario())
                    || empty(s.rationale()) || empty(s.expectedBehavior()) || empty(s.sourcePath())
                    || !context.paths().contains(s.sourcePath()) || s.sourceLine() < 1
                    || !context.changedLines().getOrDefault(s.sourcePath(), Set.of()).contains(s.sourceLine())
                    || s.artifactPath() == null || !s.artifactPath().matches("src/test/java/[A-Za-z0-9_./-]+Test\\.java")
                    || s.artifactPath().contains("..") || !paths.add(s.artifactPath())
                    || s.artifactText() == null || s.artifactText().length() > 50_000
                    || !s.artifactText().contains("@Test") || !s.artifactText().contains("org.junit.jupiter.api.Test"))
                throw new IllegalArgumentException("invalid test suggestion or candidate artifact");
            if (!mockitoAvailable && s.artifactText().contains("org.mockito"))
                throw new IllegalArgumentException("candidate uses Mockito not found in project dependencies");
        }
        return input.stream().map(s -> {
            if (!s.artifactText().equals(CiEventRedactor.redact(s.artifactText())))
                throw new IllegalArgumentException("candidate artifact contains a secret-like value");
            return new TestSuggestion(null, "GENERATED", CiEventRedactor.redact(s.title()), s.target(), s.scenario(),
                    CiEventRedactor.redact(s.rationale()), CiEventRedactor.redact(s.expectedBehavior()), s.sourcePath(), s.sourceLine(),
                    s.framework(), s.artifactPath(), s.artifactText(), s.assumptions().stream().map(CiEventRedactor::redact).toList(), null, null, null);
        }).toList();
    }

    private static boolean empty(String s) { return s == null || s.isBlank(); }
}
