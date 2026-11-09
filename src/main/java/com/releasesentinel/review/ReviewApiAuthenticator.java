package com.releasesentinel.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
public final class ReviewApiAuthenticator {
    public record Principal(String tenantId, String actorId) {}
    private final Map<String, String> tenantTokens;
    public ReviewApiAuthenticator(ObjectMapper mapper, @Value("${sentinel.review.api-tokens:{}}") String configured) {
        try {
            tenantTokens = Map.copyOf(mapper.readValue(configured, new TypeReference<Map<String, String>>() {}));
            if (tenantTokens.entrySet().stream().anyMatch(entry -> !entry.getKey().matches("[A-Za-z0-9_.-]{1,128}/[A-Za-z0-9_.-]{1,128}") || entry.getValue() == null || entry.getValue().length() < 32)
                    || tenantTokens.values().stream().distinct().count() != tenantTokens.size())
                throw new IllegalArgumentException("each API token must contain at least 32 characters");
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("SENTINEL_API_TOKENS must be a JSON tenant-to-token object", e); }
    }

    public String tenant(HttpServletRequest request) {
        return principal(request).tenantId();
    }

    public Principal principal(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new SecurityException("review API authentication required");
        byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        for (var entry : tenantTokens.entrySet()) {
            if (MessageDigest.isEqual(supplied, entry.getValue().getBytes(StandardCharsets.UTF_8))) {
                String[] identity = entry.getKey().split("/", 2);
                return new Principal(identity[0], identity[1]);
            }
        }
        throw new SecurityException("invalid review API credentials");
    }
}
