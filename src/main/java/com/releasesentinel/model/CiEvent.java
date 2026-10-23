package com.releasesentinel.model;

import java.time.Instant;
import java.util.List;

public record CiEvent(int schemaVersion, String eventId, String tenantId, String source, String repo,
                      int prNumber, String commitSha, List<ChangedFile> changedFiles,
                      String logExcerpt, Instant receivedAt) {
    public CiEvent {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        if (eventId == null || eventId.isBlank() || tenantId == null || tenantId.isBlank()
                || source == null || source.isBlank() || repo == null || repo.isBlank()
                || commitSha == null || commitSha.isBlank() || receivedAt == null) {
            throw new IllegalArgumentException("event identity fields are required");
        }
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        logExcerpt = logExcerpt == null ? "" : logExcerpt;
    }
}
