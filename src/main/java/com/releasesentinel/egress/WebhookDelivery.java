package com.releasesentinel.egress;

import com.releasesentinel.KafkaTopics;
import com.releasesentinel.model.Verdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static com.releasesentinel.egress.DeliveryExceptions.*;
import com.releasesentinel.EventContext;
import com.releasesentinel.ObservabilityMetrics;

@Service
public class WebhookDelivery {
    private final ObjectMapper mapper;
    private final TenantWebhookRegistry registry;
    private final RestClient client;
    private final ObservabilityMetrics metrics;

    public WebhookDelivery(ObjectMapper mapper, TenantWebhookRegistry registry, RestClient.Builder builder, ObservabilityMetrics metrics) { this.mapper = mapper; this.registry = registry; this.client = builder.build(); this.metrics = metrics; }

    @RetryableTopic(attempts = "4", backOff = @BackOff(delay = 250, multiplier = 2.0), exclude = PermanentDeliveryException.class, dltTopicSuffix = ".DLT")
    @KafkaListener(topics = KafkaTopics.VERDICTS, groupId = "release-sentinel-egress")
    public void deliver(String rawVerdict, @Header("X-Sentinel-Tenant") String tenantId) {
        try {
            Verdict verdict = mapper.readValue(rawVerdict, Verdict.class);
            try (var ignored = EventContext.open(verdict.eventId())) {
            var destination = registry.forTenant(tenantId);
            long timestamp = Instant.now().getEpochSecond();
            String signature = sign(destination.secret(), timestamp + "." + rawVerdict);
            client.post().uri(destination.url()).header("X-Sentinel-Delivery-Id", verdict.eventId())
                    .header("X-Sentinel-Signature", "t=" + timestamp + ",v1=" + signature)
                    .body(rawVerdict).retrieve().toBodilessEntity();
            }
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status >= 400 && status < 500 && status != 408 && status != 429) throw new PermanentDeliveryException("webhook rejected with HTTP " + status);
            metrics.retry();
            throw new RetryableDeliveryException("webhook unavailable with HTTP " + status, e);
        } catch (PermanentDeliveryException e) { throw e; }
        catch (Exception e) { metrics.retry(); throw new RetryableDeliveryException("webhook delivery failed", e); }
    }

    @DltHandler
    public void permanentFailure(String ignored) { metrics.dlt(); }

    static String sign(String secret, String message) {
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("cannot sign webhook", e); }
    }
}
