package com.continuum.iam.internal;

import com.continuum.iam.api.AssignmentDirectory;
import com.continuum.iam.api.AssignmentView;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Implémentation PostgreSQL de {@link AssignmentDirectory} (plan §2.1.3). Le libellé de position est
 * extrait du JSON i18n {@code position.label} pour la locale demandée (repli sur {@code fr}).
 */
@Service
public class JdbcAssignmentDirectory implements AssignmentDirectory {

    private static final String SQL = """
            SELECT p.code AS position_code,
                   COALESCE(p.label ->> :locale, p.label ->> 'fr') AS position_label,
                   ou.id AS unit_id,
                   ou.code AS unit_code,
                   a.kind AS kind,
                   a.valid_to AS valid_to
            FROM assignment a
            JOIN position p         ON p.id = a.position_id AND p.active
            LEFT JOIN org_unit ou   ON ou.id = a.org_unit_id
            WHERE a.tenant_id = :tenant AND a.user_id = :user
              AND CURRENT_DATE >= a.valid_from
              AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
            ORDER BY p.rank, p.code
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAssignmentDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public List<AssignmentView> activeAssignments(UUID tenantId, UUID userId, String locale) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("user", userId)
                .addValue("locale", locale == null ? "fr" : locale);
        return jdbc.query(SQL, params, (rs, i) -> new AssignmentView(
                rs.getString("position_code"),
                rs.getString("position_label"),
                rs.getObject("unit_id", UUID.class),
                rs.getString("unit_code"),
                rs.getString("kind"),
                rs.getObject("valid_to", java.time.LocalDate.class)));
    }
}
