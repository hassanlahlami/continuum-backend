package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmRunDirectory;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Historique des exécutions CCM ({@code ccm_run}), PostgreSQL. Écrit une ligne par passage de contrôle
 * (statut dérivé du problème) et lit la dernière exécution par contrôle (santé du cockpit, §5). Sélectionné
 * par dialecte dans {@link CcmRunConfig}.
 */
public class JdbcCcmRunDirectory implements CcmRunDirectory {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCcmRunDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public void record(RunRecord run) {
        String status = (run.problemDetail() == null ? Status.SUCCEEDED : Status.FAILED).name();
        jdbc.update("""
                INSERT INTO ccm_run (id, tenant_id, control_code, status, trigger_type, started_at,
                                     finished_at, population_size, exceptions_count, data_source,
                                     parameters_used, problem_detail)
                VALUES (gen_random_uuid(), :tenant, :code, :status, :trigger, :started, :finished,
                        :population, :exceptions, :dataSource, CAST(:params AS jsonb), :problem)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant", run.tenantId())
                        .addValue("code", run.controlCode())
                        .addValue("status", status)
                        .addValue("trigger", run.trigger().name())
                        .addValue("started", run.startedAt() == null ? OffsetDateTime.now() : run.startedAt())
                        .addValue("finished", run.finishedAt())
                        .addValue("population", run.populationSize())
                        .addValue("exceptions", run.exceptionsCount())
                        .addValue("dataSource", run.dataSource())
                        .addValue("params", CcmExceptionJson.object(run.parametersUsed()))
                        .addValue("problem", run.problemDetail()));
    }

    @Override
    public Map<String, LatestRun> latestByControl(UUID tenantId) {
        Map<String, LatestRun> latest = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (control_code)
                       control_code, status, finished_at, population_size, exceptions_count
                FROM ccm_run
                WHERE tenant_id = :tenant
                ORDER BY control_code, started_at DESC
                """,
                new MapSqlParameterSource("tenant", tenantId),
                rs -> {
                    String code = rs.getString("control_code");
                    latest.put(code, new LatestRun(
                            code,
                            Status.valueOf(rs.getString("status")),
                            rs.getObject("finished_at", OffsetDateTime.class),
                            rs.getObject("population_size", Long.class),
                            rs.getObject("exceptions_count", Integer.class)));
                });
        return latest;
    }
}
