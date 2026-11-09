package com.releasesentinel.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class CiReportAuthenticatorTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final CiReportAuthenticator authenticator = new CiReportAuthenticator(
            new ObjectMapper(), "{\"acme\":\"" + SECRET + "\"}");

    @Test void acceptsExactTenantSignedBody() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String body = "{\"repository\":\"acme/repo\"}";
        assertEquals("acme", authenticator.authenticate(body, timestamp, signature(timestamp, body)));
    }

    @Test void rejectsChangedBodyAndInvalidSignature() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = signature(timestamp, "{}");
        assertThrows(SecurityException.class, () -> authenticator.authenticate("{ }", timestamp, signature));
        assertThrows(SecurityException.class, () -> authenticator.authenticate("{}", timestamp, "sha256=" + "0".repeat(64)));
    }

    @Test void rejectsStaleTimestamp() throws Exception {
        String timestamp = Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        String body = "{}";
        assertThrows(SecurityException.class, () -> authenticator.authenticate(body, timestamp, signature(timestamp, body)));
    }

    private static String signature(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
}
