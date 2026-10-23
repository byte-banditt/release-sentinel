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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AnalyzerListener {
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final List<Rule> rules;
    private final ServiceGraph graph;

    public AnalyzerListener(ObjectMapper mapper, KafkaTemplate<String, String> kafka, List<Rule> rules, ServiceGraph graph) {
        this.mapper = mapper; this.kafka = kafka; this.rules = rules; this.graph = graph;
    }

    @KafkaListener(topics = KafkaTopics.EVENTS, groupId = "release-sentinel-analyzer", containerFactory = "ciEventsKafkaListenerContainerFactory")
    public void analyze(String rawEvent, Acknowledgment acknowledgment) throws Exception {
        long started = System.nanoTime();
        CiEvent event = mapper.readValue(rawEvent, CiEvent.class);
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) findings.addAll(rule.evaluate(event));
        findings.addAll(graph.blastRadius(event));
        VerdictType type = findings.stream().anyMatch(f -> f.severity() == Severity.BLOCK) ? VerdictType.BLOCK : findings.isEmpty() ? VerdictType.PASS : VerdictType.WARN;
        Verdict verdict = new Verdict(1, event.eventId(), type, findings, DecidedBy.RULES, (System.nanoTime() - started) / 1_000_000);
        String rawVerdict = mapper.writeValueAsString(verdict);
        var record = new ProducerRecord<String, String>(KafkaTopics.VERDICTS, event.eventId(), rawVerdict);
        record.headers().add("X-Sentinel-Tenant", event.tenantId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kafka.send(record).get(30, TimeUnit.SECONDS);
        acknowledgment.acknowledge();
    }
}
