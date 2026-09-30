package com.continuum.tenancy.internal;

import com.continuum.tenancy.api.Tenant;
import com.continuum.tenancy.api.TenancyService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** Implémentation PostgreSQL de {@link TenancyService} (plan §4.7). */
@Service
public class JdbcTenancyService implements TenancyService {

    private static final RowMapper<Tenant> MAPPER = (rs, i) -> new Tenant(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("label"),
            rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public JdbcTenancyService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<Tenant> find(UUID tenantId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, code, label, active FROM platform.tenant WHERE id = ?", MAPPER, tenantId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Tenant> listActive() {
        return jdbc.query(
                "SELECT id, code, label, active FROM platform.tenant WHERE active ORDER BY code", MAPPER);
    }
}
