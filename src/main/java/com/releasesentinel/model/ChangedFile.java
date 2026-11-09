package com.releasesentinel.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangedFile(String path, String status, String patch, String previousPath) {
    public ChangedFile(String path, String status, String patch) { this(path, status, patch, null); }
    public ChangedFile {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        status = status == null ? "MODIFIED" : status;
        patch = patch == null ? "" : patch;
    }
}
