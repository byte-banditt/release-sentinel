package com.releasesentinel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.releasesentinel.model.ChangedFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Retrieves only the files changed by one PR; source code is never fetched from unrelated paths. */
@Component
public final class GitHubPullRequestFiles {
    private final ObjectMapper mapper;
    private final String token;
    private final int maxFiles;
    private final int maxDiffChars;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public record PullRequestData(String repository, int number, String title, String body, String baseSha, String headSha,
                                  List<ChangedFile> files, Map<String, String> sourceContext, boolean filesAvailable) {}

    public PullRequestData retrievePullRequest(String repository, int pullNumber) {
        validateRepository(repository, pullNumber);
        JsonNode pr = getJson("https://api.github.com/repos/" + repository + "/pulls/" + pullNumber);
        String title = pr.path("title").asText();
        String body = pr.path("body").asText("");
        String base = pr.path("base").path("sha").asText();
        String head = pr.path("head").path("sha").asText();
        if (title.isBlank() || !base.matches("[0-9a-fA-F]{40,64}") || !head.matches("[0-9a-fA-F]{40,64}"))
            throw new IllegalStateException("GitHub pull request metadata is incomplete");
        List<ChangedFile> files;
        boolean filesAvailable = true;
        try { files = retrieve(repository, pullNumber); }
        catch (IllegalStateException | IllegalArgumentException unavailable) { files = List.of(); filesAvailable = false; }
        Map<String, String> context = retrieveSourceContext(repository, files, head);
        return new PullRequestData(repository, pullNumber, title, body, base, head, files, context, filesAvailable);
    }

    public Map<String, String> retrieveSourceContext(String repository, List<ChangedFile> files, String ref) {
        validateRepository(repository, 1);
        if (ref == null || !ref.matches("[0-9a-fA-F]{40,64}")) throw new IllegalArgumentException("invalid source ref");
        Map<String, String> context = new LinkedHashMap<>();
        int remaining = 20_000;
        for (ChangedFile file : files) {
            if (!file.path().endsWith(".java") || context.size() >= 8 || remaining <= 0) continue;
            String source = retrieveSource(repository, file.path(), ref, Math.min(4_000, remaining));
            if (!source.isEmpty()) { context.put(file.path(), source); remaining -= source.length(); }
        }
        return Map.copyOf(context);
    }

    public GitHubPullRequestFiles(ObjectMapper mapper,
            @Value("${sentinel.github.token:}") String token,
            @Value("${sentinel.github.max-files:200}") int maxFiles,
            @Value("${sentinel.github.max-diff-chars:500000}") int maxDiffChars) {
        this.mapper = mapper;
        this.token = token;
        this.maxFiles = maxFiles;
        this.maxDiffChars = maxDiffChars;
        if (maxFiles < 1 || maxDiffChars < 1) throw new IllegalArgumentException("GitHub diff limits must be positive");
    }

    public List<ChangedFile> retrieve(String repository, int pullNumber) {
        if (token.isBlank()) throw new IllegalStateException("GitHub API token is not configured");
        validateRepository(repository, pullNumber);
        List<ChangedFile> files = new ArrayList<>();
        int diffChars = 0;
        for (int page = 1; page <= (maxFiles + 99) / 100; page++) {
            URI uri = URI.create("https://api.github.com/repos/" + repository + "/pulls/" + pullNumber + "/files?per_page=100&page=" + page);
            JsonNode pageFiles = getJson(uri.toString());
                if (!pageFiles.isArray()) throw new IllegalStateException("invalid GitHub files response");
                if (pageFiles.isEmpty()) break;
                for (JsonNode file : pageFiles) {
                    if (files.size() >= maxFiles) throw new IllegalStateException("pull request exceeds configured file limit");
                    String path = file.path("filename").asText();
                    validatePath(path);
                    String status = file.path("status").asText("modified").toUpperCase(java.util.Locale.ROOT);
                    String previousPath = file.path("previous_filename").asText(null);
                    if (previousPath != null) validatePath(previousPath);
                    String patch = file.path("patch").asText("");
                    if (!file.has("patch") || file.path("patch").isNull()) status += "_PATCH_UNAVAILABLE";
                    diffChars = Math.addExact(diffChars, patch.length());
                    if (diffChars > maxDiffChars) throw new IllegalStateException("pull request diff exceeds configured size limit");
                    files.add(new ChangedFile(path, status, patch, previousPath));
                }
            if (pageFiles.size() < 100) break;
        }
        return List.copyOf(files);
    }

    private static void validateRepository(String repository, int pullNumber) {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || pullNumber < 1)
            throw new IllegalArgumentException("invalid pull request identity");
    }

    private String retrieveSource(String repository, String path, String ref, int maxChars) {
        String encoded = java.util.Arrays.stream(path.split("/"))
                .map(part -> java.net.URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
        String url = "https://api.github.com/repos/" + repository + "/contents/" + encoded + "?ref=" + ref;
        JsonNode content;
        try { content = getJson(url); }
        catch (IllegalStateException unavailable) { return ""; }
        if (!"file".equals(content.path("type").asText()) || !content.path("encoding").asText().equals("base64")) return "";
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(content.path("content").asText());
            if (decoded.length > 200_000) return "";
            String text = new String(decoded, StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0) return "";
            return text.length() <= maxChars ? text : text.substring(0, maxChars);
        } catch (IllegalArgumentException invalidBase64) { return ""; }
    }

    public String retrieveRepositoryFile(String repository, String path, String ref, int maxChars) {
        validateRepository(repository, 1);
        validatePath(path);
        if (ref == null || !ref.matches("[0-9a-fA-F]{40,64}") || maxChars < 1 || maxChars > 100_000)
            throw new IllegalArgumentException("invalid source context request");
        return retrieveSource(repository, path, ref, maxChars);
    }

    public boolean commitBelongsToPullRequest(String repository, int pullNumber, String commitSha) {
        validateRepository(repository, pullNumber);
        if (commitSha == null || !commitSha.matches("[0-9a-fA-F]{40,64}")) return false;
        JsonNode prs = getJson("https://api.github.com/repos/" + repository + "/commits/" + commitSha + "/pulls?per_page=100");
        if (!prs.isArray()) throw new IllegalStateException("GitHub commit association response is invalid");
        for (JsonNode pr : prs) if (pr.path("number").asInt() == pullNumber) return true;
        return false;
    }

    private JsonNode getJson(String url) {
        if (token.isBlank()) throw new IllegalStateException("GitHub API token is not configured");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token).GET().build();
        try {
            HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                try (var ignored = response.body()) { }
                throw new IllegalStateException("GitHub API request failed with HTTP " + response.statusCode());
            }
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(2_000_001);
                if (bytes.length > 2_000_000) throw new IllegalStateException("GitHub API response exceeds size limit");
                return mapper.readTree(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub API request interrupted", e);
        } catch (java.io.IOException e) { throw new IllegalStateException("GitHub API request failed", e); }
    }

    public static void validatePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.indexOf('\\') >= 0
                || java.util.Arrays.stream(path.split("/", -1)).anyMatch(part -> part.equals("..") || part.equals(".")))
            throw new IllegalArgumentException("invalid GitHub file path");
    }
}
