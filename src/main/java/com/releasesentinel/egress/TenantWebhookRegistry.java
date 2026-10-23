package com.releasesentinel.egress;

import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Component
public class TenantWebhookRegistry {
    private final Environment environment;
    public TenantWebhookRegistry(Environment environment) { this.environment = environment; }
    public Destination forTenant(String tenantId) {
        String suffix = tenantId.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
        String url = required("SENTINEL_WEBHOOK_URL_" + suffix);
        String secret = configured("SENTINEL_WEBHOOK_SECRET_" + suffix);
        if (secret == null || secret.isBlank()) {
            String file = configured("SENTINEL_WEBHOOK_SECRET_FILE_" + suffix);
            if (file != null && !file.isBlank()) try { secret = Files.readString(Path.of(file), StandardCharsets.UTF_8).trim(); } catch (Exception e) { throw new IllegalStateException("cannot read webhook secret file", e); }
        }
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("webhook secret is not configured for tenant " + tenantId);
        return new Destination(url, secret);
    }
    private String required(String name) { String value = configured(name); if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is not configured"); return value; }
    private String configured(String name) { String value = System.getenv(name); return value == null ? environment.getProperty(name) : value; }
    public record Destination(String url, String secret) { }
}
