package com.releasesentinel.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.releasesentinel.KafkaTopics;
import com.releasesentinel.model.CiEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CiEventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    public CiEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) { this.kafka = kafka; this.mapper = mapper; }
    public void publish(CiEvent event) {
        try {
            kafka.send(KafkaTopics.EVENTS, event.repo() + "#" + event.prNumber(), mapper.writeValueAsString(event)).get(30, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("cannot serialize CiEvent", e); }
        catch (Exception e) { throw new IllegalStateException("ci.events broker acknowledgement failed", e); }
    }
}
