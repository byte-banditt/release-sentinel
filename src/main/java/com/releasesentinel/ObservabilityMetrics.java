package com.releasesentinel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class ObservabilityMetrics {
    private final MeterRegistry registry;
    private final Counter agentFallback;
    private final Counter egressRetry;
    private final Counter egressDlt;
    private final Timer verdictLatency;

    public ObservabilityMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.agentFallback = Counter.builder("sentinel.agent.fallback").description("Agent fallback decisions").register(registry);
        this.egressRetry = Counter.builder("sentinel.egress.retry").description("Retryable webhook delivery failures").register(registry);
        this.egressDlt = Counter.builder("sentinel.egress.dlt").description("Webhook verdicts sent to DLT").register(registry);
        this.verdictLatency = Timer.builder("sentinel.verdict.latency").description("Rule and agent verdict latency").publishPercentileHistogram().register(registry);
    }

    public void fallback() { agentFallback.increment(); }
    public void retry() { egressRetry.increment(); }
    public void dlt() { egressDlt.increment(); }
    public Timer.Sample startVerdictTimer() { return Timer.start(registry); }
    public long stopVerdictTimer(Timer.Sample sample) { return sample.stop(verdictLatency); }
}
