package com.releasesentinel.ingest;

import com.releasesentinel.adapter.GenericJsonAdapter;
import com.releasesentinel.adapter.GitHubAdapter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import com.releasesentinel.EventContext;

@RestController
public class WebhookController {
    private final GitHubAdapter github;
    private final GenericJsonAdapter generic;
    private final CiEventPublisher publisher;
    public WebhookController(GitHubAdapter github, GenericJsonAdapter generic, CiEventPublisher publisher) { this.github = github; this.generic = generic; this.publisher = publisher; }

    @PostMapping("/webhook/github")
    ResponseEntity<Void> github(@RequestBody String body, @RequestHeader HttpHeaders headers) {
        var event = github.adapt(body, headers.toSingleValueMap());
        try (var ignored = EventContext.open(event.eventId())) { publisher.publish(event); }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/events")
    ResponseEntity<Void> generic(@RequestBody String body) {
        var event = generic.adapt(body, new HashMap<>());
        try (var ignored = EventContext.open(event.eventId())) { publisher.publish(event); }
        return ResponseEntity.accepted().build();
    }
}
