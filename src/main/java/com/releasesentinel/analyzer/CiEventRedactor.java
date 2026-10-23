package com.releasesentinel.analyzer;

import com.releasesentinel.model.ChangedFile;
import com.releasesentinel.model.CiEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds the only payload allowed to cross the local model boundary. */
public final class CiEventRedactor {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|token|secret|api[_-]?key|authorization)\\b(\\s*[:=]\\s*)((?:Bearer\\s+)?[^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern AWS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");

    private CiEventRedactor() {}

    public static Map<String, Object> payload(CiEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", event.eventId());
        result.put("repo", event.repo());
        result.put("commitSha", event.commitSha());
        List<Map<String, String>> files = new ArrayList<>();
        for (ChangedFile file : event.changedFiles()) {
            files.add(Map.of("path", redact(file.path()), "status", redact(file.status()), "patch", redact(file.patch())));
        }
        result.put("changedFiles", files);
        result.put("logExcerpt", redact(event.logExcerpt()));
        return result;
    }

    public static String redact(String value) {
        if (value == null) return null;
        String safe = ASSIGNMENT.matcher(value).replaceAll("$1$2<REDACTED>");
        safe = BEARER.matcher(safe).replaceAll("Bearer <REDACTED>");
        safe = JWT.matcher(safe).replaceAll("<REDACTED_JWT>");
        return AWS_KEY.matcher(safe).replaceAll("<REDACTED_AWS_KEY>");
    }
}
