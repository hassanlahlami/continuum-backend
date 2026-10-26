package com.continuum.iam.internal;

import com.continuum.iam.api.AccountLoginStatusService;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Implémentation PostgreSQL de {@link AccountLoginStatusService}. */
public class JdbcAccountLoginStatusService implements AccountLoginStatusService {

    private static final String TENANT_SQL = "SELECT active FROM platform.tenant WHERE id = ?";

    private static final String ASSIGNMENT_SQL = """
            SELECT count(*) FROM assignment a
            WHERE a.tenant_id = ? AND a.user_id = ?
              AND CURRENT_DATE >= a.valid_from
              AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
            """;

    private final JdbcTemplate jdbc;

    public JdbcAccountLoginStatusService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Decision loginStatus(UUID tenantId, UUID userId) {
        List<Boolean> tenant = jdbc.queryForList(TENANT_SQL, Boolean.class, tenantId);
        if (tenant.isEmpty()) {
            return Decision.deny(Reason.UNKNOWN_ACCOUNT);
        }
        if (!tenant.get(0)) {
            return Decision.deny(Reason.TENANT_SUSPENDED);
        }
        Integer active = jdbc.queryForObject(ASSIGNMENT_SQL, Integer.class, tenantId, userId);
        return active != null && active > 0 ? Decision.allow() : Decision.deny(Reason.NO_ACTIVE_ASSIGNMENT);
    }
}
