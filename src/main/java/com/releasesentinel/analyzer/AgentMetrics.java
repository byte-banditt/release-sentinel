package com.releasesentinel.analyzer;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.releasesentinel.ObservabilityMetrics;

@Component
public class AgentMetrics {
    private final AtomicLong fallbackCount = new AtomicLong();
    private final ObservabilityMetrics metrics;
    @Autowired
    public AgentMetrics(ObservabilityMetrics metrics) { this.metrics = metrics; }
    public AgentMetrics() { this(new ObservabilityMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())); }
    public void recordFallback() { fallbackCount.incrementAndGet(); metrics.fallback(); }
    public long fallbackCount() { return fallbackCount.get(); }
}
