package com.releasesentinel.rules;

import com.releasesentinel.model.*;
import java.util.*;
import java.util.regex.Pattern;

public final class ConfigKeyRemovedRule implements Rule {
    private static final Pattern FILE = Pattern.compile("(?i).*(\\.properties|application[^/]*\\.ya?ml)$");
    private static final Pattern KEY = Pattern.compile("^\\s*(?:[-]?\\s*)?([A-Za-z0-9_.-]+)\\s*(?:=|:)\\s*.+$");
    public List<Finding> evaluate(CiEvent event) {
        List<Finding> out = new ArrayList<>();
        for (ChangedFile file : event.changedFiles()) if (FILE.matcher(file.path()).matches()) {
            Set<String> added = DiffLines.added(file).stream().map(this::key).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            for (String line : DiffLines.removed(file)) {
                String key = key(line);
                if (key != null && !added.contains(key)) out.add(new Finding("R1", Severity.WARN, file.path(), "removed config key: " + key));
            }
        }
        return out;
    }
    private String key(String line) { var m = KEY.matcher(line); return m.matches() ? m.group(1) : null; }
}
