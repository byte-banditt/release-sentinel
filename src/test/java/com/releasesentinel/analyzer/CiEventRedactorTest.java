package com.releasesentinel.analyzer;

import com.releasesentinel.model.ChangedFile;
import com.releasesentinel.model.CiEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CiEventRedactorTest {
    @Test void stripsSecretsFromLogAndPatch() {
        CiEvent event = new CiEvent(1, "e", "tenant", "generic", "repo", 1, "sha",
                List.of(new ChangedFile("a.txt", "MODIFIED", "password=hunter2\nAKIA1234567890ABCDEF")),
                "Authorization: Bearer secret-token", Instant.now());
        String safe = CiEventRedactor.payload(event).toString();
        assertFalse(safe.contains("hunter2"));
        assertFalse(safe.contains("secret-token"));
        assertFalse(safe.contains("AKIA1234567890ABCDEF"));
        assertTrue(safe.contains("<REDACTED>"));
    }
}
