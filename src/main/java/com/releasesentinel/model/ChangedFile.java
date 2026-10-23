package com.releasesentinel.model;

public record ChangedFile(String path, String status, String patch) {
    public ChangedFile {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        status = status == null ? "MODIFIED" : status;
        patch = patch == null ? "" : patch;
    }
}
