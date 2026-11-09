package com.releasesentinel.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.releasesentinel.model.CiEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class CiEventOutbox {
    private static final Logger log = LoggerFactory.getLogger(CiEventOutbox.class);
    private final CiEventOutboxStore store;
    private final CiEventPublisher publisher;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public CiEventOutbox(CiEventOutboxStore store, CiEventPublisher publisher, ObjectMapper mapper, MeterRegistry metrics) {
        this.store = store; this.publisher = publisher; this.mapper = mapper; this.metrics = metrics;
    }

    public void accept(CiEvent event, String rawPayload) {
        try {
            String serialized = mapper.writeValueAsString(event);
            String payloadHash = payloadHash(rawPayload);
            boolean pending = store.enqueue(event.tenantId(), event.eventId(), payloadHash, serialized);
            if (!pending) { metrics.counter("sentinel.webhook.deliveries", "result", "duplicate").increment(); return; }
            var item = store.claim(event.tenantId(), event.eventId());
            if (item.isPresent()) dispatch(item.get());
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) throw ise;
            throw new IllegalStateException("CI event could not be durably queued", e);
        }
    }

    public static String payloadHash(String rawPayload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawPayload.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("cannot hash webhook payload", e); }
    }

    @Scheduled(fixedDelayString = "${sentinel.outbox.poll-delay:2000}")
    public void drainOne() {
        try { store.claimNext().ifPresent(this::dispatch); }
        catch (Exception e) { log.warn("CI event outbox poll failed: {}", e.getClass().getSimpleName()); }
    }

    private void dispatch(CiEventOutboxStore.OutboxItem item) {
        try {
            publisher.publish(mapper.readValue(item.json(), CiEvent.class));
            store.delivered(item);
            metrics.counter("sentinel.webhook.deliveries", "result", "published").increment();
        } catch (Exception e) {
            store.retry(item, e);
            metrics.counter("sentinel.webhook.deliveries", "result", "retry").increment();
            log.warn("CI event publish deferred eventId={} cause={}", item.eventId(), e.getClass().getSimpleName());
            throw new IllegalStateException("CI event is durably queued for retry", e);
        }
    }
}
