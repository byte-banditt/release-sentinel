package com.releasesentinel.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.releasesentinel.adapter.GenericJsonAdapter;
import com.releasesentinel.adapter.GitHubAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestConfiguration {
    @Bean GenericJsonAdapter genericJsonAdapter(ObjectMapper mapper) { return new GenericJsonAdapter(mapper); }
    @Bean GitHubAdapter githubAdapter(ObjectMapper mapper, @Value("${GITHUB_WEBHOOK_SECRET:unset}") String secret) { return new GitHubAdapter(mapper, secret); }
}
