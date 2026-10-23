package com.releasesentinel.testsupport;

import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceReceiverTest {
    @Test void rejectsTamperWrongSecretAndOldReplayAndDedupes() throws Exception {
        try (var receiver = new ReferenceReceiver("secret", 0)) {
            String body = "{\"ok\":true}";
            assertEquals(200, send(receiver.url(), "id-1", body, "secret", Instant.now().getEpochSecond()));
            assertEquals(200, send(receiver.url(), "id-1", body, "secret", Instant.now().getEpochSecond()));
            assertEquals(1, receiver.acceptedDeliveries());
            assertEquals(401, sendTampered(receiver.url(), "id-2", body + "x", body, "secret", Instant.now().getEpochSecond()));
            assertEquals(401, send(receiver.url(), "id-3", body, "wrong", Instant.now().getEpochSecond()));
            assertEquals(401, send(receiver.url(), "id-4", body, "secret", Instant.now().minusSeconds(301).getEpochSecond()));
        }
    }

    private int send(String url, String id, String body, String secret, long timestamp) throws Exception {
        return sendTampered(url, id, body, body, secret, timestamp);
    }
    private int sendTampered(String url, String id, String body, String signedBody, String secret, long timestamp) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + signedBody).getBytes(StandardCharsets.UTF_8)));
        var request = HttpRequest.newBuilder(URI.create(url)).header("X-Sentinel-Delivery-Id", id).header("X-Sentinel-Signature", signature).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
