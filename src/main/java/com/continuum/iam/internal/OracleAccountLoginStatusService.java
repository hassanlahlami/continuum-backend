package com.continuum.iam.internal;

import com.continuum.iam.api.AccountLoginStatusService;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Implémentation Oracle de {@link AccountLoginStatusService} (UUID en {@code RAW(16)}, booléens {@code NUMBER(1)}). */
public class OracleAccountLoginStatusService implements AccountLoginStatusService {

    private static final String TENANT_SQL = "SELECT active FROM platform.tenant WHERE id = ?";

    private static final String ASSIGNMENT_SQL = """
            SELECT count(*) FROM assignment a
            WHERE a.tenant_id = ? AND a.user_id = ?
              AND CURRENT_DATE >= a.valid_from
              AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
            """;

    private final JdbcTemplate jdbc;

    public OracleAccountLoginStatusService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Decision loginStatus(UUID tenantId, UUID userId) {
        List<Integer> tenant = jdbc.queryForList(TENANT_SQL, Integer.class, (Object) raw(tenantId));
        if (tenant.isEmpty()) {
            return Decision.deny(Reason.UNKNOWN_ACCOUNT);
        }
        if (tenant.get(0) == 0) {
            return Decision.deny(Reason.TENANT_SUSPENDED);
        }
        Integer active = jdbc.queryForObject(ASSIGNMENT_SQL, Integer.class, raw(tenantId), raw(userId));
        return active != null && active > 0 ? Decision.allow() : Decision.deny(Reason.NO_ACTIVE_ASSIGNMENT);
    }

    private static byte[] raw(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
