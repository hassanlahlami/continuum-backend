package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmPopulationGateway;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Lecture du CBS « Amplitude » (Oracle, D3) pour les contrôles continus sur population (§5). Interroge
 * les vues d'audit {@code V_AUDIT_MOUVEMENTS} / {@code V_AUDIT_COMPTES} en lecture seule (compte
 * {@code CBS_READER}) et applique les règles paramétrées des contrôles CCM-2 et CCM-3 du pack.
 */
public class OracleCcmPopulationGateway implements CcmPopulationGateway {

    private final NamedParameterJdbcTemplate jdbc;

    public OracleCcmPopulationGateway(DataSource cbsDataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(cbsDataSource);
    }

    @Override
    public java.util.List<MovementAnomaly> dormantAccountMovements(DormantAccountCriteria c) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.MVT_ID, m.COMPTE_ID, m.DATE_OPERATION, m.DATE_VALEUR, m.MONTANT, m.SENS,
                       m.CODE_OPERATION, m.UTILISATEUR_SAISIE, m.UTILISATEUR_VALIDATION, m.CANAL,
                       'DORMANT' AS TRIGGER_REASON
                FROM V_AUDIT_MOUVEMENTS m
                JOIN V_AUDIT_COMPTES c ON c.COMPTE_ID = m.COMPTE_ID
                WHERE (c.STATUT = 'DORMANT'
                       OR c.DATE_DERNIER_MVT_CLIENT < ADD_MONTHS(TRUNC(SYSDATE), -:dormancyMonths))
                  AND m.MONTANT >= :minAmount
                  AND m.DATE_OPERATION BETWEEN :from AND :to
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("dormancyMonths", c.dormancyMonths())
                .addValue("minAmount", c.minAmount())
                .addValue("from", java.sql.Date.valueOf(c.from()))
                .addValue("to", java.sql.Date.valueOf(c.to()));
        appendChannelExclusion(sql, params, c.excludeChannels());
        return jdbc.query(sql.toString(), params, MAPPER);
    }

    @Override
    public java.util.List<MovementAnomaly> outOfBoundsMovements(OutOfBoundsCriteria c) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.MVT_ID, m.COMPTE_ID, m.DATE_OPERATION, m.DATE_VALEUR, m.MONTANT, m.SENS,
                       m.CODE_OPERATION, m.UTILISATEUR_SAISIE, m.UTILISATEUR_VALIDATION, m.CANAL,
                       CASE
                         WHEN m.MONTANT > :threshold THEN 'AMOUNT'
                         WHEN UPPER(TO_CHAR(m.DATE_OPERATION, 'DY', 'NLS_DATE_LANGUAGE=ENGLISH')) IN (:nonWorkingDays) THEN 'NON_WORKING_DAY'
                         WHEN m.DATE_VALEUR < m.DATE_OPERATION - :maxBackValueDays THEN 'BACK_VALUE'
                       END AS TRIGGER_REASON
                FROM V_AUDIT_MOUVEMENTS m
                WHERE m.DATE_OPERATION BETWEEN :from AND :to
                  AND ( m.MONTANT > :threshold
                     OR UPPER(TO_CHAR(m.DATE_OPERATION, 'DY', 'NLS_DATE_LANGUAGE=ENGLISH')) IN (:nonWorkingDays)
                     OR m.DATE_VALEUR < m.DATE_OPERATION - :maxBackValueDays )
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("threshold", c.thresholdAmount())
                .addValue("nonWorkingDays", c.nonWorkingDays())
                .addValue("maxBackValueDays", c.maxBackValueDays())
                .addValue("from", java.sql.Date.valueOf(c.from()))
                .addValue("to", java.sql.Date.valueOf(c.to()));
        appendChannelExclusion(sql, params, c.excludeChannels());
        return jdbc.query(sql.toString(), params, MAPPER);
    }

    private static void appendChannelExclusion(StringBuilder sql, MapSqlParameterSource params,
            java.util.List<String> excludeChannels) {
        if (excludeChannels != null && !excludeChannels.isEmpty()) {
            sql.append("  AND m.CANAL NOT IN (:excludeChannels)\n");
            params.addValue("excludeChannels", excludeChannels);
        }
    }

    private static final RowMapper<MovementAnomaly> MAPPER = (ResultSet rs, int i) -> new MovementAnomaly(
            rs.getLong("MVT_ID"),
            rs.getString("COMPTE_ID"),
            toLocalDate(rs, "DATE_OPERATION"),
            toLocalDate(rs, "DATE_VALEUR"),
            rs.getBigDecimal("MONTANT"),
            rs.getString("SENS"),
            rs.getString("CODE_OPERATION"),
            rs.getString("UTILISATEUR_SAISIE"),
            rs.getString("UTILISATEUR_VALIDATION"),
            rs.getString("CANAL"),
            rs.getString("TRIGGER_REASON"));

    private static java.time.LocalDate toLocalDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}
