package com.releasesentinel.analyzer;

import com.releasesentinel.model.CiEvent;

/** Pluggable, strictly validated CI-triage provider. */
public interface TriageAgent {
    boolean enabled();
    AgentResult invoke(CiEvent event) throws Exception;
    void recordFallback();
}
