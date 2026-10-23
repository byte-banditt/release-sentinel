package com.releasesentinel.analyzer;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class AgentMetrics {
    private final AtomicLong fallbackCount = new AtomicLong();
    public void recordFallback() { fallbackCount.incrementAndGet(); }
    public long fallbackCount() { return fallbackCount.get(); }
}
