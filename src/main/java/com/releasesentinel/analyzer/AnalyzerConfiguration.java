package com.releasesentinel.analyzer;

import com.releasesentinel.graph.ServiceGraph;
import com.releasesentinel.rules.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class AnalyzerConfiguration {
    @Bean List<Rule> rules() { return List.of(new ConfigKeyRemovedRule(), new DependencyChangeRule(), new TestFileDeletedRule(), new MigrationRollbackRule()); }
    @Bean ServiceGraph serviceGraph() {
        try (var input = new ClassPathResource("service-graph.yaml").getInputStream()) {
            return ServiceGraph.fromYaml(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("cannot load service-graph.yaml", e); }
    }
}
