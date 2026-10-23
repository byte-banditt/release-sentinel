package com.releasesentinel;

public final class KafkaTopics {
    public static final String EVENTS = "ci.events";
    public static final String VERDICTS = "ci.verdicts";
    public static final String EVENTS_DLQ = "ci.events.dlq";
    private KafkaTopics() { }
}
