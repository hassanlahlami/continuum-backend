package com.continuum.iam.internal;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.AssignPositionCommand;
import com.continuum.iam.api.AssignmentService;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Écriture des affectations (plan §2.1.3, §2.1.8), PostgreSQL. Chaque octroi est journalisé dans le
 * journal d'audit signé (§2.3) via l'API du module {@code audittrail}.
 */
@Service
public class JdbcAssignmentService implements AssignmentService {

    private final JdbcTemplate jdbc;
    private final AuditTrailService auditTrail;

    public JdbcAssignmentService(DataSource dataSource, AuditTrailService auditTrail) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional
    public UUID assign(AssignPositionCommand command) {
        if (command.actorUserId().equals(command.targetUserId())) {
            throw new SelfGrantNotAllowedException();
        }

        UUID assignmentId = UUID.randomUUID();
        LocalDate validFrom = command.validFrom() == null ? LocalDate.now() : command.validFrom();
        jdbc.update("""
                INSERT INTO assignment (id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                assignmentId, command.tenantId(), command.targetUserId(), command.positionId(),
                command.orgUnitId(), command.kind(), validFrom);

        String payload = "{\"targetUser\":\"%s\",\"position\":\"%s\",\"unit\":%s,\"kind\":\"%s\"}".formatted(
                command.targetUserId(), command.positionId(),
                command.orgUnitId() == null ? "null" : "\"" + command.orgUnitId() + "\"", command.kind());
        auditTrail.append(new AuditEntry(command.tenantId(), command.actorUserId(),
                "admin.assignment.manage", "ASSIGNMENT", assignmentId.toString(), payload));

        return assignmentId;
    }
}
