package com.releasesentinel.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.releasesentinel.KafkaTopics;
import com.releasesentinel.graph.ServiceGraph;
import com.releasesentinel.model.*;
import com.releasesentinel.rules.Rule;
import org.springframework.kafka.annotation.KafkaListener;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.releasesentinel.EventContext;
import com.releasesentinel.ObservabilityMetrics;
import io.micrometer.core.instrument.Timer;

@Service
public class AnalyzerListener {
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final List<Rule> rules;
    private final ServiceGraph graph;
    private final AgentEnricher agent;
    private final ObservabilityMetrics metrics;

    @Autowired
    public AnalyzerListener(ObjectMapper mapper, KafkaTemplate<String, String> kafka, List<Rule> rules, ServiceGraph graph, AgentEnricher agent, ObservabilityMetrics metrics) {
        this.mapper = mapper; this.kafka = kafka; this.rules = rules; this.graph = graph; this.agent = agent; this.metrics = metrics;
    }

    public AnalyzerListener(ObjectMapper mapper, KafkaTemplate<String, String> kafka, List<Rule> rules, ServiceGraph graph, AgentEnricher agent) {
        this(mapper, kafka, rules, graph, agent,
                new ObservabilityMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @KafkaListener(topics = KafkaTopics.EVENTS, groupId = "release-sentinel-analyzer", containerFactory = "ciEventsKafkaListenerContainerFactory")
    public void analyze(String rawEvent, Acknowledgment acknowledgment) throws Exception {
        CiEvent event = mapper.readValue(rawEvent, CiEvent.class);
        try (var ignored = EventContext.open(event.eventId())) {
        Timer.Sample timer = metrics.startVerdictTimer();
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) findings.addAll(rule.evaluate(event));
        findings.addAll(graph.blastRadius(event));
        boolean rulesWarn = findings.stream().anyMatch(f -> f.severity() == Severity.WARN);
        boolean failingLog = event.logExcerpt() != null && event.logExcerpt().matches("(?is).*\\b(fail|failed|failure|error|exception|fatal)\\b.*");
        DecidedBy decidedBy = DecidedBy.RULES;
        if (agent.enabled() && (rulesWarn || failingLog)) {
            try {
                AgentResult result = agent.invoke(event);
                if (!result.classification().equals("PASS")) findings.add(new Finding("AI_AGENT", result.classification().equals("BLOCK") ? Severity.BLOCK : Severity.WARN, "logExcerpt", result.rationale() + " | " + String.join("; ", result.evidenceLines())));
                decidedBy = DecidedBy.RULES_AGENT;
            } catch (Exception agentFailure) {
                agent.recordFallback();
                decidedBy = DecidedBy.RULES_FALLBACK;
            }
        }
        VerdictType type = findings.stream().anyMatch(f -> f.severity() == Severity.BLOCK) ? VerdictType.BLOCK : findings.isEmpty() ? VerdictType.PASS : VerdictType.WARN;
        long latencyMs = metrics.stopVerdictTimer(timer) / 1_000_000;
        Verdict verdict = new Verdict(1, event.eventId(), type, findings, decidedBy, latencyMs);
        String rawVerdict = mapper.writeValueAsString(verdict);
        var record = new ProducerRecord<String, String>(KafkaTopics.VERDICTS, event.eventId(), rawVerdict);
        record.headers().add("X-Sentinel-Tenant", event.tenantId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kafka.send(record).get(30, TimeUnit.SECONDS);
        acknowledgment.acknowledge();
        }
    }
}
