package com.releasesentinel.review;

import com.releasesentinel.model.ChangedFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReviewResultValidatorTest {
    @Test void tracksAddedLinesAndAcceptsEvidenceFromChangedLineOnly() {
        String diff = "@@ -8,2 +8,3 @@\n context\n-oldCall();\n+newCall(null);\n+next();";
        var context = ReviewContextBuilder.build("acme/repo", 1, "title", "desc", "a".repeat(40), "b".repeat(40),
                List.of(new ChangedFile("src/Service.java", "MODIFIED", diff)), Map.of(), "<pom/>", List.of());
        assertEquals(Set.of(9, 10), context.changedLines().get("src/Service.java"));
        var finding = new ReviewFinding("f1", "HYPOTHESIS", ReviewCategory.CORRECTNESS, ReviewSeverity.HIGH,
                "Null passed", "This call may fail", "src/Service.java", "RIGHT", 9, 9,
                List.of("newCall(null);"), "Possible null dereference", "Check for null", "MEDIUM");
        assertEquals(1, ReviewResultValidator.findings(List.of(finding), context).size());
    }

    @Test void rejectsUnchangedLinesAndEvidenceNotInPatch() {
        String diff = "@@ -8 +8 @@\n oldCall();\n+newCall();";
        var context = ReviewContextBuilder.build("acme/repo", 1, "title", "", "a".repeat(40), "b".repeat(40),
                List.of(new ChangedFile("src/Service.java", "MODIFIED", diff)), Map.of(), "", List.of());
        var wrongLine = new ReviewFinding("f1", "FACT", ReviewCategory.CORRECTNESS, ReviewSeverity.MEDIUM,
                "Issue", "Description", "src/Service.java", "RIGHT", 7, 7, List.of("newCall();"), "Impact", "Fix", "HIGH");
        assertThrows(IllegalArgumentException.class, () -> ReviewResultValidator.findings(List.of(wrongLine), context));
        var wrongEvidence = new ReviewFinding("f2", "FACT", ReviewCategory.CORRECTNESS, ReviewSeverity.MEDIUM,
                "Issue", "Description", "src/Service.java", "RIGHT", 8, 8, List.of("made up"), "Impact", "Fix", "HIGH");
        assertThrows(IllegalArgumentException.class, () -> ReviewResultValidator.findings(List.of(wrongEvidence), context));
    }

    @Test void acceptsOnlySafeJUnitArtifactConventions() {
        String diff = "@@ -1 +1 @@\n-old();\n+new();";
        var context = ReviewContextBuilder.build("acme/repo", 1, "title", "", "a".repeat(40), "b".repeat(40),
                List.of(new ChangedFile("src/main/java/a/S.java", "MODIFIED", diff)), Map.of(), "", List.of());
        var good = new TestSuggestion(null, null, "boundary", "S#run", "null input", "avoid NPE", "throws IllegalArgumentException",
                "src/main/java/a/S.java", 1, "JUNIT5", "src/test/java/a/STest.java",
                "import org.junit.jupiter.api.Test; class STest { @Test void x() {} }", List.of(), null, null, null);
        assertEquals("GENERATED", ReviewResultValidator.suggestions(List.of(good), context, true, false).getFirst().status());
        var unsafe = new TestSuggestion(null, null, "bad", "S#run", "case", "why", "outcome", "src/main/java/a/S.java", 1,
                "JUNIT5", "src/test/java/../../BadTest.java", good.artifactText(), List.of(), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> ReviewResultValidator.suggestions(List.of(unsafe), context, true, false));
    }

    @Test void validatesDeletedLineReferencesAgainstBaseSideDiff() {
        String diff = "@@ -40,2 +40,1 @@\n kept();\n-removedUnsafeCall();";
        var context = ReviewContextBuilder.build("acme/repo", 1, "title", "", "a".repeat(40), "b".repeat(40),
                List.of(new ChangedFile("src/main/java/a/S.java", "MODIFIED", diff)), Map.of(), "", List.of());
        var finding = new ReviewFinding("deleted", "FACT", ReviewCategory.CORRECTNESS, ReviewSeverity.HIGH,
                "Removed required behavior", "Deletion changes behavior", "src/main/java/a/S.java", "LEFT", 41, 41,
                List.of("removedUnsafeCall();"), "Regression", "Keep behavior", "HIGH");
        assertEquals(1, ReviewResultValidator.findings(List.of(finding), context).size());
    }
}
