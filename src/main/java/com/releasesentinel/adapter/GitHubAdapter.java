package com.releasesentinel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.releasesentinel.model.ChangedFile;
import com.releasesentinel.model.CiEvent;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class GitHubAdapter implements SourceAdapter {
    private final ObjectMapper mapper;
    private final byte[] secret;

    public GitHubAdapter(ObjectMapper mapper, String secret) {
        if (secret == null || secret.isEmpty()) throw new IllegalArgumentException("secret is required");
        this.mapper = mapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CiEvent adapt(String rawInput, Map<String, String> headers) {
        verifySignature(rawInput, headers == null ? Map.of() : headers);
        try {
            JsonNode root = mapper.readTree(rawInput);
            JsonNode pull = root.path("pull_request");
            List<ChangedFile> files = new ArrayList<>();
            for (JsonNode file : root.path("changed_files")) {
                files.add(new ChangedFile(file.path("path").asText(), file.path("status").asText("MODIFIED"), file.path("patch").asText("")));
            }
            String repo = root.path("repository").path("full_name").asText();
            String tenant = headers.getOrDefault("X-Tenant-Id", repo.contains("/") ? repo.substring(0, repo.indexOf('/')) : repo);
            String eventId = headers.getOrDefault("X-GitHub-Delivery", root.path("id").asText());
            if (eventId.isBlank()) throw new IllegalArgumentException("GitHub delivery id is required");
            return new CiEvent(1, eventId, tenant, "github", repo, root.path("number").asInt(pull.path("number").asInt()),
                    pull.path("head").path("sha").asText(), files, root.path("log_excerpt").asText(""), Instant.now());
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("invalid GitHub payload", e);
        }
    }

    private void verifySignature(String payload, Map<String, String> headers) {
        String provided = headers.getOrDefault("X-Hub-Signature-256", "");
        if (!provided.startsWith("sha256=") || provided.length() != 71) throw new SecurityException("invalid GitHub signature");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII))) {
                throw new SecurityException("invalid GitHub signature");
            }
        } catch (SecurityException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("cannot verify GitHub signature", e); }
    }
}
