package com.releasesentinel.egress;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WebClientConfiguration {
    @Bean RestClient.Builder restClientBuilder() { return RestClient.builder(); }
}
