package com.continuum.admin.internal;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Connection used for platform-scope writes (ADR 0012: the entity account is read-only on {@code platform.*}).
 * Wrapped in {@link PlatformWrite} so it does not make the auto-configured primary {@link DataSource} back off.
 * Without {@code continuum.platform.datasource.url} (PostgreSQL single-schema dev) the primary DataSource is used.
 */
@Configuration
class PlatformWriteConfig {

    record PlatformWrite(DataSource dataSource) {
    }

    @Bean
    PlatformWrite platformWrite(DataSource primary,
            @Value("${continuum.platform.datasource.url:}") String url,
            @Value("${continuum.platform.datasource.username:}") String username,
            @Value("${continuum.platform.datasource.password:}") String password) {
        if (!StringUtils.hasText(url)) {
            return new PlatformWrite(primary);
        }
        return new PlatformWrite(DataSourceBuilder.create().url(url).username(username).password(password).build());
    }
}
