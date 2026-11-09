package com.releasesentinel.review;

import com.releasesentinel.adapter.GitHubPullRequestFiles;
import com.releasesentinel.model.ChangedFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReviewContextBuilder {
    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    record Context(Map<String, Object> payload, Map<String, Set<Integer>> changedLines, Map<String, Set<Integer>> removedLines, Map<String, String> diffs,
                   Map<String, String> sourceContext, Set<String> paths) {}
    private ReviewContextBuilder() {}

    static Context build(String repository, int number, String title, String description, String baseSha, String headSha,
                         List<ChangedFile> files, Map<String, String> sourceContext, String pom,
                         List<?> deterministicFindings) {
        List<Map<String, Object>> filePayloads = new ArrayList<>();
        Map<String, Set<Integer>> changed = new LinkedHashMap<>();
        Map<String, Set<Integer>> removed = new LinkedHashMap<>();
        Map<String, String> diffs = new LinkedHashMap<>();
        Set<String> paths = new LinkedHashSet<>();
        int total = 0;
        for (ChangedFile file : files) {
            GitHubPullRequestFiles.validatePath(file.path());
            total += file.patch().length();
            if (total > 500_000) throw new IllegalArgumentException("review diff exceeds context limit");
            paths.add(file.path());
            changed.put(file.path(), addedLineNumbers(file.patch()));
            removed.put(file.path(), removedLineNumbers(file.patch()));
            diffs.put(file.path(), file.patch());
            filePayloads.add(Map.of("path", file.path(), "previousPath", file.previousPath() == null ? "" : file.previousPath(),
                    "status", file.status(), "diff", com.releasesentinel.analyzer.CiEventRedactor.redact(file.patch()),
                    "sourceContext", com.releasesentinel.analyzer.CiEventRedactor.redact(sourceContext.getOrDefault(file.path(), ""))));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repository", repository);
        payload.put("pullRequestNumber", number);
        payload.put("title", com.releasesentinel.analyzer.CiEventRedactor.redact(bound(title, 500)));
        payload.put("description", com.releasesentinel.analyzer.CiEventRedactor.redact(bound(description, 8_000)));
        payload.put("baseSha", baseSha);
        payload.put("headSha", headSha);
        payload.put("changedFiles", filePayloads);
        payload.put("testConventions", pom != null && (pom.contains("junit-jupiter") || pom.contains("spring-boot-starter-test")) ? "Java, Maven, JUnit 5" : "JUnit 5 unavailable or not detected; abstain from test generation");
        payload.put("testBuildConfiguration", pom == null ? "" : bound(pom, 10_000));
        payload.put("deterministicFindings", deterministicFindings);
        return new Context(Map.copyOf(payload), Map.copyOf(changed), Map.copyOf(removed), Map.copyOf(diffs), Map.copyOf(sourceContext), Set.copyOf(paths));
    }

    static Set<Integer> addedLineNumbers(String patch) {
        Set<Integer> lines = new LinkedHashSet<>();
        int newLine = 0;
        for (String line : patch.split("\\R", -1)) {
            if (line.startsWith("@@")) {
                Matcher m = HUNK.matcher(line);
                if (m.matches()) newLine = Integer.parseInt(m.group(2));
            } else if (line.startsWith("+++")) {
                continue;
            } else if (line.startsWith("+")) {
                lines.add(newLine++);
            } else if (line.startsWith("-")) {
                continue;
            } else if (line.startsWith(" ")) {
                newLine++;
            }
        }
        return Set.copyOf(lines);
    }

    static Set<Integer> removedLineNumbers(String patch) {
        Set<Integer> lines = new LinkedHashSet<>();
        int oldLine = 0;
        for (String line : patch.split("\\R", -1)) {
            if (line.startsWith("@@")) {
                Matcher m = HUNK.matcher(line);
                if (m.matches()) oldLine = Integer.parseInt(m.group(1));
            } else if (line.startsWith("---")) continue;
            else if (line.startsWith("-")) lines.add(oldLine++);
            else if (line.startsWith("+")) continue;
            else if (line.startsWith(" ")) oldLine++;
        }
        return Set.copyOf(lines);
    }

    private static String bound(String value, int max) { return value == null ? "" : value.substring(0, Math.min(value.length(), max)); }
}
