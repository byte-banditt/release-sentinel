package com.releasesentinel.rules;

import com.releasesentinel.model.*;
import java.util.List;

public final class TestFileDeletedRule implements Rule {
    public List<Finding> evaluate(CiEvent event) {
        return event.changedFiles().stream().filter(f -> f.status().equalsIgnoreCase("DELETED") && isTest(f.path()))
                .map(f -> new Finding("R3", Severity.BLOCK, f.path(), "test file deleted")).toList();
    }
    private boolean isTest(String path) { return path.matches("(?i).*(^|[/\\\\])((test|tests)[/\\\\].*|.*(Test|Tests|Spec)\\.[^.]+)$"); }
}
