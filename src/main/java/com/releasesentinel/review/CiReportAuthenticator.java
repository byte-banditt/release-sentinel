package com.releasesentinel.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

@Component
public final class CiReportAuthenticator {
    private final Map<String, String> tenantSecrets;
    public CiReportAuthenticator(ObjectMapper mapper, @Value("${sentinel.review.ci-tokens:{}}") String configured) {
        try {
            tenantSecrets = Map.copyOf(mapper.readValue(configured, new TypeReference<Map<String, String>>() {}));
            if (tenantSecrets.entrySet().stream().anyMatch(entry -> !entry.getKey().matches("[A-Za-z0-9_.-]{1,128}") || entry.getValue() == null || entry.getValue().length() < 32)
                    || tenantSecrets.values().stream().distinct().count() != tenantSecrets.size())
                throw new IllegalArgumentException("each CI signing secret must contain at least 32 characters");
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("SENTINEL_CI_TOKENS must be a JSON tenant-to-secret object", e); }
    }

    public String authenticate(String body, String timestampHeader, String signatureHeader) {
        try {
            long timestamp = Long.parseLong(timestampHeader);
            long now = Instant.now().getEpochSecond();
            if (timestamp < now - 300 || timestamp > now + 300) throw new SecurityException("stale CI report");
            if (signatureHeader == null || !signatureHeader.matches("sha256=[0-9a-f]{64}")) throw new SecurityException("invalid CI report signature");
            byte[] supplied = HexFormat.of().parseHex(signatureHeader.substring(7));
            for (var entry : tenantSecrets.entrySet()) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(entry.getValue().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] expected = mac.doFinal((timestampHeader + "." + body).getBytes(StandardCharsets.UTF_8));
                if (MessageDigest.isEqual(expected, supplied)) return entry.getKey();
            }
            throw new SecurityException("invalid CI report credentials");
        } catch (SecurityException e) { throw e; }
        catch (Exception e) { throw new SecurityException("invalid CI report authentication", e); }
    }
}
