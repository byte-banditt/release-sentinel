package com.releasesentinel.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.releasesentinel.model.CiEvent;
import java.util.Map;

public final class GenericJsonAdapter implements SourceAdapter {
    private final ObjectMapper mapper;

    public GenericJsonAdapter(ObjectMapper mapper) {
        this.mapper = mapper.copy().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    @Override
    public CiEvent adapt(String rawInput, Map<String, String> headers) {
        try {
            CiEvent event = mapper.readValue(rawInput, CiEvent.class);
            if (event.schemaVersion() != 1) throw new IllegalArgumentException("unsupported schemaVersion: " + event.schemaVersion());
            return event;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid CiEvent schema", e);
        }
    }
}
