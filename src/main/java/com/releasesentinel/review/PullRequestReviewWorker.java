package com.releasesentinel.review;

import io.micrometer.core.instrument.MeterRegistry;
import com.releasesentinel.adapter.GitHubPullRequestFiles;
import com.releasesentinel.ingest.CiEventOutbox;
import com.releasesentinel.model.CiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PullRequestReviewWorker {
    private static final Logger log = LoggerFactory.getLogger(PullRequestReviewWorker.class);
    private final ReviewJobStore jobs;
    private final PullRequestReviewService reviews;
    private final MeterRegistry metrics;
    private final GitHubPullRequestFiles github;
    private final CiEventOutbox outbox;
    public PullRequestReviewWorker(ReviewJobStore jobs, PullRequestReviewService reviews, MeterRegistry metrics,
                                   GitHubPullRequestFiles github, CiEventOutbox outbox) {
        this.jobs = jobs; this.reviews = reviews; this.metrics = metrics; this.github = github; this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${sentinel.review.worker-delay:1000}")
    public void processOne() {
        jobs.claim().ifPresent(job -> {
            try {
                var pr = github.retrievePullRequest(job.repository(), job.pullNumber());
                if (!pr.filesAvailable()) {
                    reviews.recordUnavailable(job.tenant(), pr);
                    jobs.complete(job);
                    return;
                }
                var event = new CiEvent(1, job.deliveryId(), job.tenant(), "github", job.repository(), job.pullNumber(),
                        pr.headSha(), pr.files(), "", java.time.Instant.now());
                outbox.accept(event, job.payloadHash());
                reviews.analyze(job.tenant(), pr, false);
                jobs.complete(job);
            } catch (Exception e) {
                boolean exhausted = jobs.retry(job, e);
                metrics.counter("sentinel.pr.reviews", "status", exhausted ? "REVIEW_FAILED" : "RETRY").increment();
                log.warn("PR review job deferred deliveryId={} cause={}", job.deliveryId(), e.getClass().getSimpleName());
                if (exhausted) reviews.recordUnavailable(job.tenant(), job.repository(), job.pullNumber(), job.headSha());
            }
        });
    }
}
