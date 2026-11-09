package com.releasesentinel.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import com.releasesentinel.model.ChangedFile;
import static org.junit.jupiter.api.Assertions.*;

class AdaptersTest {
    @Test void genericValidatesCiEventShape() {
        var json = "{\"schemaVersion\":1,\"eventId\":\"e\",\"tenantId\":\"t\",\"source\":\"generic\",\"repo\":\"r\",\"prNumber\":1,\"commitSha\":\"s\",\"changedFiles\":[],\"logExcerpt\":\"\",\"receivedAt\":\"2026-01-01T00:00:00Z\"}";
        assertEquals("generic", new GenericJsonAdapter(new ObjectMapper()).adapt(json, Map.of()).source());
    }

    @Test void githubRequiresValidSignature() throws Exception {
        String body = new String(getClass().getResourceAsStream("/fixtures/github-pull-request.json").readAllBytes(), StandardCharsets.UTF_8);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        var event = new GitHubAdapter(new ObjectMapper(), "secret").adapt(body, Map.of("X-Hub-Signature-256", sig));
        assertEquals("github", event.source());
        assertEquals("src/main/resources/application.yml", event.changedFiles().getFirst().path());
        assertThrows(SecurityException.class, () -> new GitHubAdapter(new ObjectMapper(), "secret").adapt(body, Map.of("X-Hub-Signature-256", "sha256=" + "0".repeat(64))));
    }

    @Test void githubAcceptsOnlySupportedPullRequestActionsWhenEventHeaderIsPresent() throws Exception {
        String body = "{\"action\":\"synchronize\",\"number\":7,\"repository\":{\"full_name\":\"acme/repo\"},\"pull_request\":{\"head\":{\"sha\":\"abc\"}}}";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        var headers = Map.of("X-Hub-Signature-256", sig, "X-GitHub-Event", "pull_request", "X-GitHub-Delivery", "delivery-1");
        assertEquals("abc", new GitHubAdapter(new ObjectMapper(), "secret").adapt(body, headers).commitSha());
        String unsupported = body.replace("synchronize", "labeled");
        String badSig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(unsupported.getBytes(StandardCharsets.UTF_8)));
        var badHeaders = Map.of("X-Hub-Signature-256", badSig, "X-GitHub-Event", "pull_request", "X-GitHub-Delivery", "delivery-2");
        assertThrows(IllegalArgumentException.class, () -> new GitHubAdapter(new ObjectMapper(), "secret").adapt(unsupported, badHeaders));
    }

    @Test void githubPathsRejectTraversalAndAllowRenamedFiles() {
        GitHubPullRequestFiles.validatePath("src/new-name.java");
        assertThrows(IllegalArgumentException.class, () -> GitHubPullRequestFiles.validatePath("src/../../secret"));
        assertThrows(IllegalArgumentException.class, () -> GitHubPullRequestFiles.validatePath("/etc/passwd"));
        var renamed = new ChangedFile("src/new.java", "RENAMED", "", "src/old.java");
        assertEquals("RENAMED", renamed.status());
        assertEquals("src/old.java", renamed.previousPath());
    }
}
