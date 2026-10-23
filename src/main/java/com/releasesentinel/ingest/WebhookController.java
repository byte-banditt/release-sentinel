package com.releasesentinel.ingest;

import com.releasesentinel.adapter.GenericJsonAdapter;
import com.releasesentinel.adapter.GitHubAdapter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;

@RestController
public class WebhookController {
    private final GitHubAdapter github;
    private final GenericJsonAdapter generic;
    private final CiEventPublisher publisher;
    public WebhookController(GitHubAdapter github, GenericJsonAdapter generic, CiEventPublisher publisher) { this.github = github; this.generic = generic; this.publisher = publisher; }

    @PostMapping("/webhook/github")
    ResponseEntity<Void> github(@RequestBody String body, @RequestHeader HttpHeaders headers) {
        publisher.publish(github.adapt(body, headers.toSingleValueMap()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/events")
    ResponseEntity<Void> generic(@RequestBody String body) {
        publisher.publish(generic.adapt(body, new HashMap<>()));
        return ResponseEntity.accepted().build();
    }
}
