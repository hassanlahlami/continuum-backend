package com.continuum.ccm.api;

import com.continuum.ccm.api.CcmPopulationGateway.DormantAccountCriteria;
import com.continuum.ccm.api.CcmPopulationGateway.OutOfBoundsCriteria;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contrôles continus sur population CBS (CCM-2, CCM-3 ; plan §5, pack {@code CONTROLES-CCM.md}). Lit les
 * anomalies via {@link CcmPopulationGateway} puis les matérialise en <strong>constats</strong> (source
 * {@code CCM}) de façon <strong>idempotente</strong> (dé-doublonnage par référence stable), à l'image du
 * contrôle SoD ({@link SodMonitoring}).
 *
 * <p>Les seuils et durées sont des <strong>paramètres</strong> fournis par l'appelant (livrés par le
 * pack, paramétrés en base — CCM-03) ; aucune constante métier n'est écrite dans le code.
 */
public interface PopulationControlMonitoring {

    /**
     * Contrôle CCM-3 (comptes dormants) : un constat par mouvement anormal.
     *
     * @param largeOutflowAmount seuil de « sortie importante » élevant la criticité à {@code CRITICAL} (débit)
     * @return le nombre de <em>nouveaux</em> constats créés
     */
    int recordDormantExceptions(UUID tenantId, DormantAccountCriteria criteria, BigDecimal largeOutflowAmount);

    /**
     * Contrôle CCM-2 (écritures hors seuil / hors heures ouvrées) : un constat par mouvement, avec le motif.
     *
     * @return le nombre de <em>nouveaux</em> constats créés
     */
    int recordOutOfBoundsExceptions(UUID tenantId, OutOfBoundsCriteria criteria);
}
