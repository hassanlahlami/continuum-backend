package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmControlDirectory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lecture du paramétrage des contrôles CCM (plan §5, CCM-03), PostgreSQL. Les paramètres sont stockés
 * en JSON ({@code ccm_control.params}) et désérialisés en {@code Map} pour le moteur. Le bean est
 * sélectionné par dialecte dans {@link CcmControlDirectoryConfig} (variante Oracle : {@code RAW(16)},
 * {@code NUMBER(1)}, {@code VARCHAR2 IS JSON}).
 */
public class JdbcCcmControlDirectory implements CcmControlDirectory {

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcCcmControlDirectory(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mapper = mapper;
    }

    @Override
    public List<CcmControl> activeControls(UUID tenantId) {
        return jdbc.query(
                "SELECT control_code, params FROM ccm_control WHERE tenant_id = ? AND enabled = true",
                (rs, i) -> new CcmControl(rs.getString("control_code"), true, parse(rs.getString("params"))),
                tenantId);
    }

    @Override
    public List<CcmControl> allControls(UUID tenantId) {
        return jdbc.query(
                "SELECT control_code, enabled, params FROM ccm_control WHERE tenant_id = ?",
                (rs, i) -> new CcmControl(rs.getString("control_code"), rs.getBoolean("enabled"),
                        parse(rs.getString("params"))),
                tenantId);
    }

    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, PARAMS);
        } catch (Exception e) {
            throw new IllegalStateException("Paramètres de contrôle CCM illisibles", e);
        }
    }
}
