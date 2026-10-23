package com.releasesentinel;

import org.slf4j.MDC;

public final class EventContext implements AutoCloseable {
    private EventContext(String eventId) { MDC.put("eventId", eventId); }

    public static EventContext open(String eventId) {
        return new EventContext(eventId == null || eventId.isBlank() ? "unknown" : eventId);
    }

    @Override public void close() { MDC.remove("eventId"); }
}
