package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmRunDirectory;
import java.nio.ByteBuffer;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Historique des exécutions CCM ({@code ccm_run}) — dialecte Oracle (D4), à parité avec
 * {@link JdbcCcmRunDirectory}. UUID {@code RAW(16)} ({@code SYS_GUID()} pour la clé), JSON en
 * {@code VARCHAR2 IS JSON}, dernière exécution par contrôle via {@code ROW_NUMBER()}. Les liaisons nulles
 * portent un type SQL explicite (évite les binds non typés rejetés par Oracle).
 */
public class OracleCcmRunDirectory implements CcmRunDirectory {

    private static final String VIEW_COLUMNS = """
            SELECT id, control_code, status, trigger_type, started_at, finished_at,
                   population_size, exceptions_count, data_source, parameters_used, problem_detail
            FROM ccm_run
            """;

    private static final RowMapper<RunView> VIEW = (rs, i) -> new RunView(
            uuid(rs.getBytes("id")),
            rs.getString("control_code"),
            Status.valueOf(rs.getString("status")),
            Trigger.valueOf(rs.getString("trigger_type")),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class),
            rs.getObject("population_size", Long.class),
            rs.getObject("exceptions_count", Integer.class),
            rs.getString("data_source"),
            rs.getString("parameters_used"),
            rs.getString("problem_detail"));

    private final NamedParameterJdbcTemplate jdbc;

    public OracleCcmRunDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public void record(RunRecord run) {
        String status = (run.problemDetail() == null ? Status.SUCCEEDED : Status.FAILED).name();
        jdbc.update("""
                INSERT INTO ccm_run (id, tenant_id, control_code, status, trigger_type, started_at,
                                     finished_at, population_size, exceptions_count, data_source,
                                     parameters_used, problem_detail)
                VALUES (SYS_GUID(), :tenant, :code, :status, :trigger, :started, :finished,
                        :population, :exceptions, :dataSource, :params, :problem)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant", raw(run.tenantId()))
                        .addValue("code", run.controlCode())
                        .addValue("status", status)
                        .addValue("trigger", run.trigger().name())
                        .addValue("started", run.startedAt() == null ? OffsetDateTime.now() : run.startedAt())
                        .addValue("finished", run.finishedAt(), Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("population", run.populationSize(), Types.BIGINT)
                        .addValue("exceptions", run.exceptionsCount())
                        .addValue("dataSource", run.dataSource(), Types.VARCHAR)
                        .addValue("params", CcmExceptionJson.object(run.parametersUsed()), Types.VARCHAR)
                        .addValue("problem", run.problemDetail(), Types.VARCHAR));
    }

    @Override
    public Map<String, LatestRun> latestByControl(UUID tenantId) {
        Map<String, LatestRun> latest = new HashMap<>();
        jdbc.query("""
                SELECT control_code, status, finished_at, population_size, exceptions_count FROM (
                    SELECT control_code, status, finished_at, population_size, exceptions_count,
                           ROW_NUMBER() OVER (PARTITION BY control_code ORDER BY started_at DESC) rn
                    FROM ccm_run
                    WHERE tenant_id = :tenant
                ) WHERE rn = 1
                """,
                new MapSqlParameterSource("tenant", raw(tenantId)),
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

    @Override
    public Optional<RunView> findById(UUID tenantId, UUID runId) {
        return jdbc.query(VIEW_COLUMNS + " WHERE tenant_id = :tenant AND id = :id",
                new MapSqlParameterSource().addValue("tenant", raw(tenantId)).addValue("id", raw(runId)),
                VIEW).stream().findFirst();
    }

    @Override
    public RunPage listByControl(UUID tenantId, String controlCode, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId)).addValue("code", controlCode);
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM ccm_run WHERE tenant_id = :tenant AND control_code = :code",
                params, Integer.class);
        params.addValue("size", size).addValue("offset", (long) page * size);
        List<RunView> items = jdbc.query(
                VIEW_COLUMNS + " WHERE tenant_id = :tenant AND control_code = :code"
                        + " ORDER BY started_at DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                params, VIEW);
        return new RunPage(items, total == null ? 0 : total);
    }

    private static byte[] raw(UUID id) {
        if (id == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
