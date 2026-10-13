package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmControlDirectory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Lecture du paramétrage des contrôles CCM (plan §5, CCM-03) — <strong>dialecte Oracle</strong> (D4), à
 * parité avec {@link JdbcCcmControlDirectory}. Spécifique au dialecte : {@code tenant_id} en {@code RAW(16)},
 * {@code enabled} en {@code NUMBER(1)}, {@code params} en {@code VARCHAR2}/{@code IS JSON} désérialisé en
 * {@code Map}.
 */
public class OracleCcmControlDirectory implements CcmControlDirectory {

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OracleCcmControlDirectory(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.mapper = mapper;
    }

    @Override
    public List<CcmControl> activeControls(UUID tenantId) {
        return jdbc.query(
                "SELECT control_code, params FROM ccm_control WHERE tenant_id = :tenant AND enabled = 1",
                new MapSqlParameterSource("tenant", raw(tenantId)),
                (rs, i) -> new CcmControl(rs.getString("control_code"), true, parse(rs.getString("params"))));
    }

    @Override
    public List<CcmControl> allControls(UUID tenantId) {
        return jdbc.query(
                "SELECT control_code, enabled, params FROM ccm_control WHERE tenant_id = :tenant",
                new MapSqlParameterSource("tenant", raw(tenantId)),
                (rs, i) -> new CcmControl(rs.getString("control_code"), rs.getInt("enabled") == 1,
                        parse(rs.getString("params"))));
    }

    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, PARAMS);
        } catch (Exception e) {
            throw new IllegalStateException("Paramètres de contrôle CCM illisibles", e);
        }
    }

    private static byte[] raw(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }
}
