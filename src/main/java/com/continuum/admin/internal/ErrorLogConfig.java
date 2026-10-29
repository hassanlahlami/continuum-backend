package com.continuum.admin.internal;

import com.continuum.admin.ErrorLogSource;
import com.continuum.admin.TraceSource;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Wires the log sources: OpenSearch when {@code continuum.error-log.base-url} is set, otherwise empty. */
@Configuration
@EnableConfigurationProperties(ErrorLogProperties.class)
class ErrorLogConfig {

    @Bean
    ErrorLogSource errorLogSource(ErrorLogProperties props) {
        if (props.baseUrl().isBlank()) {
            return (from, to) -> List.<ErrorLogSource.Group>of();
        }
        return new OpenSearchErrorLogSource(client(props), props);
    }

    @Bean
    TraceSource traceSource(ErrorLogProperties props) {
        if (props.baseUrl().isBlank()) {
            return traceId -> Optional.empty();
        }
        return new OpenSearchTraceSource(client(props), props);
    }

    private static RestClient client(ErrorLogProperties props) {
        RestClient.Builder builder = RestClient.builder().baseUrl(props.baseUrl());
        if (!props.username().isBlank()) {
            builder.defaultHeaders(h -> h.setBasicAuth(props.username(), props.password()));
        }
        return builder.build();
    }
}
