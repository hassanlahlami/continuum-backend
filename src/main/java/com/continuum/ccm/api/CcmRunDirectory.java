package com.continuum.ccm.api;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Historique des <strong>exécutions</strong> de contrôles CCM (plan §5, contrat {@code app/ccm.yaml}
 * {@code CcmRun}, table {@code ccm_run}). Le moteur ({@code CcmScheduler}) enregistre une exécution par
 * passage d'un contrôle ; cet historique donne l'état de <strong>santé réel</strong> d'un contrôle et les
 * métriques du cockpit (§5), en remplacement du {@code NEVER_RUN} par défaut. Instancié par dialecte
 * (D4) via {@link com.continuum.ccm.internal.CcmRunConfig}.
 */
public interface CcmRunDirectory {

    /** Comment un contrôle a été déclenché. */
    enum Trigger { SCHEDULE, EVENT, MANUAL }

    /** Statut d'une exécution (dérivé : {@code FAILED} si un problème est renseigné, sinon {@code SUCCEEDED}). */
    enum Status { RUNNING, SUCCEEDED, FAILED }

    /**
     * Une exécution terminée à enregistrer. {@code populationSize} peut être {@code null} (contrôle non
     * fondé sur une population, ex. cumul d'habilitations). {@code problemDetail} non nul ⇒ {@code FAILED}.
     */
    record RunRecord(
            UUID tenantId,
            String controlCode,
            Trigger trigger,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long populationSize,
            int exceptionsCount,
            String dataSource,
            Map<String, Object> parametersUsed,
            String problemDetail) {
    }

    /** Dernière exécution connue d'un contrôle (base de la santé du cockpit). */
    record LatestRun(
            String controlCode,
            Status status,
            OffsetDateTime finishedAt,
            Long populationSize,
            Integer exceptionsCount) {
    }

    /** Enregistre une exécution terminée. */
    void record(RunRecord run);

    /** La dernière exécution par code de contrôle, pour un tenant (clé = {@code control_code}). */
    Map<String, LatestRun> latestByControl(UUID tenantId);
}
