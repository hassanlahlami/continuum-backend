package com.continuum.admin.internal;

import com.continuum.admin.ErrorLogSource;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Wires the error log source: OpenSearch when {@code continuum.error-log.base-url} is set, otherwise empty. */
@Configuration
@EnableConfigurationProperties(ErrorLogProperties.class)
class ErrorLogConfig {

    @Bean
    ErrorLogSource errorLogSource(ErrorLogProperties props) {
        if (props.baseUrl().isBlank()) {
            return (from, to) -> List.<ErrorLogSource.Group>of();
        }
        RestClient.Builder builder = RestClient.builder().baseUrl(props.baseUrl());
        if (!props.username().isBlank()) {
            builder.defaultHeaders(h -> h.setBasicAuth(props.username(), props.password()));
        }
        return new OpenSearchErrorLogSource(builder.build(), props);
    }
}
