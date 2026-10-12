package com.continuum.ccm.api;

import com.continuum.ccm.api.CcmPopulationGateway.DormantAccountCriteria;
import com.continuum.ccm.api.CcmPopulationGateway.OutOfBoundsCriteria;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contrôles continus sur population CBS (CCM-2, CCM-3 ; plan §5, pack {@code CONTROLES-CCM.md}). Lit les
 * anomalies via {@link CcmPopulationGateway} puis les matérialise en <strong>exceptions</strong> (table
 * {@code ccm_exception}, D34) de façon <strong>idempotente</strong> (dé-doublonnage par référence stable),
 * à l'image du contrôle SoD ({@link SodMonitoring}).
 *
 * <p><strong>Audit par exception (D34, §5.3)</strong> : le moteur ne crée jamais de constat ; il produit
 * des exceptions qu'un auditeur habilité triera (retenir / écarter / affecter). Chaque exception porte sa
 * chaîne de preuve (paramètres d'exécution, source de données, enregistrement source du CBS).
 *
 * <p>Les seuils et durées sont des <strong>paramètres</strong> fournis par l'appelant (livrés par le
 * pack, paramétrés en base — CCM-03) ; aucune constante métier n'est écrite dans le code.
 */
public interface PopulationControlMonitoring {

    /**
     * Contrôle CCM-3 (comptes dormants) : une exception par mouvement anormal.
     *
     * @param controlCode       code du contrôle du pack à l'origine de l'exception (CCM-03)
     * @param largeOutflowAmount seuil de « sortie importante » élevant la criticité à {@code CRITICAL} (débit)
     * @return le nombre de <em>nouvelles</em> exceptions créées
     */
    int recordDormantExceptions(
            UUID tenantId, String controlCode, DormantAccountCriteria criteria, BigDecimal largeOutflowAmount);

    /**
     * Contrôle CCM-2 (écritures hors seuil / hors heures ouvrées) : une exception par mouvement, avec le motif.
     *
     * @param controlCode code du contrôle du pack à l'origine de l'exception (CCM-03)
     * @return le nombre de <em>nouvelles</em> exceptions créées
     */
    int recordOutOfBoundsExceptions(UUID tenantId, String controlCode, OutOfBoundsCriteria criteria);
}
