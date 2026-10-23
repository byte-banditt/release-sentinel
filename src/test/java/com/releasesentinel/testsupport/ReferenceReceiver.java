package com.releasesentinel.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReferenceReceiver implements AutoCloseable {
    private final HttpServer server;
    private final String secret;
    private final Set<String> deliveries = ConcurrentHashMap.newKeySet();
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile int failuresRemaining;
    private volatile String lastBody;

    public ReferenceReceiver(String secret, int failures) throws IOException {
        this.secret = secret; this.failuresRemaining = failures;
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = verify(exchange.getRequestHeaders().getFirst("X-Sentinel-Signature"), exchange.getRequestHeaders().getFirst("X-Sentinel-Delivery-Id"), body) ? (failuresRemaining-- > 0 ? 500 : 200) : 401;
            if (status == 200) { lastBody = body; deliveries.add(exchange.getRequestHeaders().getFirst("X-Sentinel-Delivery-Id")); }
            exchange.sendResponseHeaders(status, 0); exchange.getResponseBody().close();
        });
        server.start();
    }
    private boolean verify(String header, String deliveryId, String body) {
        if (header == null || deliveryId == null) return false;
        try {
            String[] fields = header.split(",", -1); long timestamp = Long.parseLong(fields[0].substring(2));
            if (Math.abs(Instant.now().getEpochSecond() - timestamp) > 300 || !fields[1].startsWith("v1=")) return false;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String actual = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), fields[1].substring(3).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) { return false; }
    }
    public String url() { return "http://localhost:" + server.getAddress().getPort() + "/"; }
    public int attempts() { return attempts.get(); }
    public int acceptedDeliveries() { return deliveries.size(); }
    public String lastBody() { return lastBody; }
    public void failNext(int count) { failuresRemaining = count; }
    @Override public void close() { server.stop(0); }
}
