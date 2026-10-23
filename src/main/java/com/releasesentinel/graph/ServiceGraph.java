package com.releasesentinel.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.releasesentinel.model.*;
import java.util.*;

public final class ServiceGraph {
    private final List<Service> services;
    public ServiceGraph(List<Service> services) { this.services = List.copyOf(services); }
    public static ServiceGraph fromYaml(String yaml) {
        try {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(yaml);
            List<Service> services = new ArrayList<>();
            for (JsonNode n : root.path("services")) { List<String> paths = new ArrayList<>(); n.path("paths").forEach(x -> paths.add(x.asText())); List<String> deps = new ArrayList<>(); n.path("dependsOn").forEach(x -> deps.add(x.asText())); services.add(new Service(n.path("name").asText(), paths, deps)); }
            return new ServiceGraph(services);
        } catch (Exception e) { throw new IllegalArgumentException("invalid service graph YAML", e); }
    }
    public List<Finding> blastRadius(CiEvent event) {
        Set<String> owners = new LinkedHashSet<>();
        for (ChangedFile f : event.changedFiles()) for (Service s : services) if (s.paths().stream().anyMatch(p -> matches(p, f.path()))) owners.add(s.name());
        Map<String, List<String>> dependents = new HashMap<>();
        for (Service service : services) for (String dependency : service.dependsOn()) dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(service.name());
        Set<String> impacted = new LinkedHashSet<>(owners);
        Deque<String> queue = new ArrayDeque<>(owners);
        while (!queue.isEmpty()) for (String dependent : dependents.getOrDefault(queue.removeFirst(), List.of())) if (impacted.add(dependent)) queue.addLast(dependent);
        return impacted.stream().filter(s -> !owners.contains(s)).map(s -> new Finding("GRAPH_BLAST_RADIUS", Severity.INFO, s, "declared dependent of changed service(s): " + owners)).toList();
    }
    private boolean matches(String pattern, String path) { if (pattern.endsWith("/**")) return path.startsWith(pattern.substring(0, pattern.length() - 3)); return pattern.equals(path) || path.startsWith(pattern.endsWith("/") ? pattern : pattern + "/"); }
    public record Service(String name, List<String> paths, List<String> dependsOn) { public Service { paths = List.copyOf(paths); dependsOn = List.copyOf(dependsOn); } }
}
