package com.releasesentinel.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.releasesentinel.model.CiEvent;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Legacy AgentCore-compatible test adapter; production wiring uses OllamaTriageAgent. */
public class AgentEnricher implements TriageAgent {
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    private final Aws4Signer signer = Aws4Signer.create();
    private final DefaultCredentialsProvider credentials = DefaultCredentialsProvider.create();
    private final DefaultAwsRegionProviderChain regions = new DefaultAwsRegionProviderChain();

    public AgentEnricher(ObjectMapper mapper, AgentMetrics metrics,
                         @Value("${sentinel.agent.url:}") String url,
                         @Value("${sentinel.agent.timeout:8s}") Duration timeout) {
        this.mapper = mapper; this.metrics = metrics; this.endpoint = url.isBlank() ? null : URI.create(url); this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override public boolean enabled() { return endpoint != null; }
    @Override
    public AgentResult invoke(CiEvent event) throws Exception {
        if (endpoint == null) throw new IllegalStateException("agent endpoint is not configured");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.eventId()); payload.put("repo", event.repo()); payload.put("commitSha", event.commitSha());
        payload.put("changedFiles", event.changedFiles()); payload.put("logExcerpt", event.logExcerpt());
        byte[] body = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        var request = request(body);
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("agent HTTP " + response.statusCode());
        return validate(response.body());
    }

    private HttpRequest request(byte[] body) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !isLocalTestEndpoint(endpoint)) {
            throw new IllegalArgumentException("agent endpoint must use HTTPS");
        }
        var builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if ("https".equalsIgnoreCase(endpoint.getScheme())) {
            var unsigned = SdkHttpFullRequest.builder().uri(endpoint).method(SdkHttpMethod.POST)
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Accept", "application/json")
                    .contentStreamProvider(ContentStreamProvider.fromByteArray(body)).build();
            var signed = signer.sign(unsigned, Aws4SignerParams.builder()
                    .awsCredentials(credentials.resolveCredentials())
                    .signingName("bedrock-agentcore")
                    .signingRegion(regions.getRegion()).build());
            signed.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        }
        return builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private boolean isLocalTestEndpoint(URI uri) {
        return "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
    }

    AgentResult validate(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        if (!root.isObject() || !root.path("classification").isTextual() || !root.path("rationale").isTextual() || !root.path("evidenceLines").isArray()) throw new IllegalArgumentException("invalid agent result schema");
        List<String> evidence = new java.util.ArrayList<>();
        for (JsonNode line : root.path("evidenceLines")) { if (!line.isTextual()) throw new IllegalArgumentException("invalid agent evidenceLines"); evidence.add(line.textValue()); }
        return new AgentResult(root.path("classification").textValue(), root.path("rationale").textValue(), evidence);
    }
    @Override public void recordFallback() { metrics.recordFallback(); }
}
