package com.releasesentinel.rules;

import com.releasesentinel.model.*;
import java.util.*;

public final class MigrationRollbackRule implements Rule {
    public List<Finding> evaluate(CiEvent event) {
        Set<String> paths = event.changedFiles().stream().map(ChangedFile::path).map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
        return event.changedFiles().stream().filter(f -> f.status().equalsIgnoreCase("ADDED") && isMigration(f.path()))
                .filter(f -> !hasRollback(f.path(), paths)).map(f -> new Finding("R4", Severity.BLOCK, f.path(), "migration added without rollback/down script")).toList();
    }
    private boolean isMigration(String path) { return path.matches("(?i).*(migrations?|db/migrate|flyway|liquibase)[/\\\\].*\\.(sql|xml|yaml|yml)$"); }
    private boolean hasRollback(String path, Set<String> paths) {
        String lower = path.toLowerCase(); int slash = lower.lastIndexOf('/'); String dir = slash < 0 ? "" : lower.substring(0, slash + 1);
        String base = lower.substring(slash + 1).replaceFirst("\\.[^.]+$", "");
        return paths.stream().anyMatch(p -> p.startsWith(dir) && (p.contains("down") || p.contains("rollback") || p.contains("undo") || p.startsWith(dir + base + ".down.")));
    }
}
