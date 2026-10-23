package com.releasesentinel.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
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
}
