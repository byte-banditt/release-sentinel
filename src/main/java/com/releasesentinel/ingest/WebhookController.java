package com.releasesentinel.ingest;

import com.releasesentinel.adapter.GenericJsonAdapter;
import com.releasesentinel.adapter.GitHubAdapter;
import com.releasesentinel.adapter.GitHubPullRequestFiles;
import com.releasesentinel.model.CiEvent;
import com.releasesentinel.review.ReviewJobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.releasesentinel.EventContext;

@RestController
public class WebhookController {
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final GitHubAdapter github;
    private final GenericJsonAdapter generic;
    private final CiEventOutbox outbox;
    private final GitHubPullRequestFiles pullRequestFiles;
    private final ObjectMapper mapper;
    private final int maxPayloadBytes;
    private final ReviewJobStore reviewJobs;
    public WebhookController(GitHubAdapter github, GenericJsonAdapter generic, CiEventOutbox outbox,
                             GitHubPullRequestFiles pullRequestFiles, ObjectMapper mapper,
                             @Value("${sentinel.github.max-webhook-bytes:1048576}") int maxPayloadBytes,
                             ReviewJobStore reviewJobs) {
        this.github = github; this.generic = generic; this.outbox = outbox;
        this.pullRequestFiles = pullRequestFiles; this.mapper = mapper; this.maxPayloadBytes = maxPayloadBytes;
        this.reviewJobs = reviewJobs;
    }

    @PostMapping("/webhook/github")
    ResponseEntity<Void> github(@RequestBody String body, @RequestHeader HttpHeaders headers) {
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxPayloadBytes)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "GitHub webhook exceeds configured limit");
        var singleHeaders = headers.toSingleValueMap();
        var event = github.adapt(body, singleHeaders);
        com.fasterxml.jackson.databind.JsonNode root = null;
        try {
            root = mapper.readTree(body);
            if (!root.has("changed_files")) {
                if ("pull_request".equals(singleHeaders.get("X-GitHub-Event"))) {
                    reviewJobs.enqueue(event.tenantId(), event.eventId(), CiEventOutbox.payloadHash(body), event.repo(), event.prNumber(), event.commitSha());
                    return ResponseEntity.accepted().build();
                }
                var files = pullRequestFiles.retrieve(event.repo(), event.prNumber());
                event = new CiEvent(event.schemaVersion(), event.eventId(), event.tenantId(), event.source(), event.repo(), event.prNumber(), event.commitSha(), files, event.logExcerpt(), event.receivedAt());
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (root == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid GitHub webhook payload", e);
            if (!"pull_request".equals(singleHeaders.get("X-GitHub-Event")))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub pull request files are unavailable", e);
            log.warn("PR file retrieval unavailable eventId={} cause={}", event.eventId(), e.getClass().getSimpleName());
            event = new CiEvent(event.schemaVersion(), event.eventId(), event.tenantId(), event.source(), event.repo(),
                    event.prNumber(), event.commitSha(), java.util.List.of(), event.logExcerpt(), event.receivedAt());
        }
        try (var ignored = EventContext.open(event.eventId())) { outbox.accept(event, body); }
        if ("pull_request".equals(singleHeaders.get("X-GitHub-Event"))) {
            reviewJobs.enqueue(event.tenantId(), event.eventId(), CiEventOutbox.payloadHash(body), event.repo(), event.prNumber(), event.commitSha());
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/events")
    ResponseEntity<Void> generic(@RequestBody String body) {
        var event = generic.adapt(body, new HashMap<>());
        try (var ignored = EventContext.open(event.eventId())) { outbox.accept(event, body); }
        return ResponseEntity.accepted().build();
    }
}
