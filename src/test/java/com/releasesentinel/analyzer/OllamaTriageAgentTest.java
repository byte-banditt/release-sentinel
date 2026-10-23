package com.releasesentinel.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OllamaTriageAgentTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void validatesOnlyTheExactOutputSchema() throws Exception {
        AgentResult result = OllamaTriageAgent.validate(mapper,
                "{\"classification\":\"WARN\",\"rationale\":\"failure\",\"evidenceLines\":[\"line 1\"]}");
        assertEquals("WARN", result.classification());
        assertThrows(IllegalArgumentException.class, () -> OllamaTriageAgent.validate(mapper,
                "{\"classification\":\"PASS\",\"rationale\":\"ok\",\"evidenceLines\":[],\"extra\":1}"));
        assertThrows(IllegalArgumentException.class, () -> OllamaTriageAgent.validate(mapper,
                "{\"classification\":\"MAYBE\",\"rationale\":\"ok\",\"evidenceLines\":[]}"));
    }
}
