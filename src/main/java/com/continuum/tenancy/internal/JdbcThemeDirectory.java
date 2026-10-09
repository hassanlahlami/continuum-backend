package com.continuum.tenancy.internal;

import com.continuum.tenancy.api.ThemeDirectory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Lecture du thème d'un tenant (plan §1.6), PostgreSQL. Résout le tenant par son code puis charge ses
 * tokens {@code --ct-*} (colonne JSON {@code tenant_theme.tokens}).
 */
@Service
public class JdbcThemeDirectory implements ThemeDirectory {

    private static final TypeReference<LinkedHashMap<String, String>> TOKENS =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcThemeDirectory(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mapper = mapper;
    }

    @Override
    public Optional<TenantTheme> findByEntityCode(String entityCode) {
        if (entityCode == null || entityCode.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT th.tokens, th.version
                    FROM tenant_theme th
                    JOIN platform.tenant t ON t.id = th.tenant_id
                    WHERE t.code = ? AND t.active
                    """,
                    (rs, i) -> new TenantTheme(parse(rs.getString("tokens")), rs.getInt("version")),
                    entityCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Map<String, String> parse(String json) {
        try {
            return mapper.readValue(json, TOKENS);
        } catch (Exception e) {
            throw new IllegalStateException("Tokens de thème illisibles", e);
        }
    }
}
