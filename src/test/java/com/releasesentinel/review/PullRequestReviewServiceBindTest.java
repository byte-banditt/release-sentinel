package com.releasesentinel.review;

import com.releasesentinel.adapter.GitHubPullRequestFiles;
import com.releasesentinel.graph.ServiceGraph;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PullRequestReviewServiceBindTest {
    private static final String SHA = "a".repeat(40);
    private static final String ARTIFACT = "package example;\nclass CandidateTest {}\n";

    @Test void bindsOnlyWhenApprovedArtifactIsPresentVerbatimAtThePrSha() {
        var fixture = fixture("APPROVED", ARTIFACT);
        fixture.service().bind("acme", fixture.reviewId(), SHA, List.of(fixture.suggestionId()));
        verify(fixture.github()).retrieveRepositoryFile("acme/repo", "src/test/java/example/CandidateTest.java", SHA, 50_000);
        verify(fixture.store()).bind("acme", fixture.reviewId(), SHA, List.of(fixture.suggestionId()));
    }

    @Test void rejectsCandidateArtifactMissingOrChangedAtTheBoundSha() {
        var fixture = fixture("APPROVED", ARTIFACT + "// changed\n");
        when(fixture.github().retrieveRepositoryFile("acme/repo", "src/test/java/example/CandidateTest.java", SHA, 50_000))
                .thenReturn(ARTIFACT);
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().bind("acme", fixture.reviewId(), SHA, List.of(fixture.suggestionId())));
        verify(fixture.store(), never()).bind(anyString(), any(), anyString(), anyList());
    }

    @Test void rejectsUnapprovedSuggestionsBeforeFetchingCandidateContent() {
        var fixture = fixture("REJECTED", ARTIFACT);
        assertThrows(IllegalStateException.class,
                () -> fixture.service().bind("acme", fixture.reviewId(), SHA, List.of(fixture.suggestionId())));
        verify(fixture.github(), never()).retrieveRepositoryFile(anyString(), anyString(), anyString(), anyInt());
    }

    private static Fixture fixture(String status, String storedArtifact) {
        GitHubPullRequestFiles github = mock(GitHubPullRequestFiles.class);
        ReviewStore store = mock(ReviewStore.class);
        UUID reviewId = UUID.randomUUID(), suggestionId = UUID.randomUUID();
        var review = new PullRequestReview(reviewId, "acme", "acme/repo", 7, "b".repeat(40), SHA,
                "title", "body", "REVIEW_FINDINGS", "summary", "WARN", List.of(), List.of(), Instant.now());
        when(store.get("acme", reviewId)).thenReturn(Optional.of(review));
        when(github.commitBelongsToPullRequest("acme/repo", 7, SHA)).thenReturn(true);
        when(store.suggestion("acme", suggestionId)).thenReturn(Optional.of(new ReviewStore.SuggestionRow(
                suggestionId, reviewId, status, "src/test/java/example/CandidateTest.java", storedArtifact, "hash")));
        when(github.retrieveRepositoryFile("acme/repo", "src/test/java/example/CandidateTest.java", SHA, 50_000)).thenReturn(ARTIFACT);
        return new Fixture(new PullRequestReviewService(github, mock(PullRequestReviewAgent.class), store,
                List.of(), mock(ServiceGraph.class), new SimpleMeterRegistry()), github, store, reviewId, suggestionId);
    }

    private record Fixture(PullRequestReviewService service, GitHubPullRequestFiles github, ReviewStore store,
                           UUID reviewId, UUID suggestionId) {}
}
