package com.releasesentinel.rules;

import com.releasesentinel.model.*;
import java.util.*;
import java.util.regex.Pattern;

public final class DependencyChangeRule implements Rule {
    private static final Pattern FILE = Pattern.compile("(?i).*(pom\\.xml|package\\.json|requirements\\.txt)$");
    private static final Pattern VERSION = Pattern.compile("(?<![\\w])([0-9]+)(?:\\.[0-9A-Za-z-]+)*");
    public List<Finding> evaluate(CiEvent event) {
        List<Finding> out = new ArrayList<>();
        for (ChangedFile file : event.changedFiles()) if (FILE.matcher(file.path()).matches()) {
            if (file.status().equalsIgnoreCase("DELETED")) { out.add(new Finding("R2", Severity.BLOCK, file.path(), "dependency manifest removed")); continue; }
            List<String> removed = DiffLines.removed(file), added = DiffLines.added(file);
            for (String line : removed) {
                String name = dependencyName(line);
                if (name == null) continue;
                String addedLine = added.stream().filter(a -> name.equals(dependencyName(a))).findFirst().orElse(null);
                if (addedLine == null) out.add(new Finding("R2", Severity.WARN, file.path(), "dependency removed: " + name));
                else if (major(line) != null && major(addedLine) != null && !major(line).equals(major(addedLine)))
                    out.add(new Finding("R2", Severity.WARN, file.path(), "dependency major version changed: " + name));
            }
        }
        return out;
    }
    private String dependencyName(String line) {
        var json = Pattern.compile("^\\s*[\\\"']([^\\\"']+)[\\\"']\\s*[:=]").matcher(line);
        if (json.find()) return json.group(1);
        var xml = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(line);
        if (xml.find()) return xml.group(1);
        var req = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*(?:==|>=|<=|~=|=|$)").matcher(line);
        return req.find() ? req.group(1) : null;
    }
    private String major(String line) { var m = VERSION.matcher(line); return m.find() ? m.group(1) : null; }
}
