package com.continuum.ccm.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalogue des contrôles CCM livré par le pack (plan §5, pack {@code docs/pack-banque/CONTROLES-CCM.md}).
 * Métadonnées <strong>déclarées par le pack</strong> (libellé, catégorie, référence normative, jeu de
 * données, définitions de paramètres), jamais inventées — seule la règle est du code. L'activation et les
 * valeurs de paramètres par tenant vivent en base ({@code ccm_control}, CCM-03) ; ce catalogue fournit le
 * reste pour {@code listCcmControls}/{@code getCcmControl}. Analogue au catalogue de fonctionnalités (iam).
 *
 * <p>Les seuils/durées par défaut sont ⟨à valider⟩ avec la première banque (cf. pack).
 */
public final class CcmControlCatalog {

    /** Définition d'un paramètre métier d'un contrôle (type parmi MONEY/NUMBER/BOOLEAN/STRING/DURATION). */
    public record ParamDef(String name, String label, String type, String defaultValue) {
    }

    /** Descripteur d'un contrôle du pack. {@code category} ∈ CONFIGURATION/MASTER_DATA/TRANSACTIONS/ACCESS/RECONCILIATION. */
    public record ControlDescriptor(
            UUID id,
            String code,
            String label,
            String category,
            String description,
            String normativeReference,
            String dataset,
            List<ParamDef> parameterDefinitions) {
    }

    private CcmControlCatalog() {
    }

    /** Identifiant stable dérivé du code (le catalogue n'a pas d'identité en base). */
    public static UUID idOf(String code) {
        return UUID.nameUUIDFromBytes(("ccm-control:" + code).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final List<ControlDescriptor> CONTROLS = List.of(
            new ControlDescriptor(
                    idOf("SOD-VIREMENTS"), "SOD-VIREMENTS",
                    "Cumul saisie / validation des virements",
                    "ACCESS",
                    "Détecte les virements saisis et validés par la même personne, et les cumuls "
                            + "d'habilitations incompatibles (maker-checker).",
                    "Principe maker-checker (préparateur ≠ valideur) ; COSO principe 10 ; matrices de conflits de tâches.",
                    "ENTITLEMENT",
                    List.of(
                            new ParamDef("makerFunction", "Fonction « préparer »", "STRING", "SAISIE_VIREMENT"),
                            new ParamDef("checkerFunction", "Fonction « valider »", "STRING", "VALIDATION_VIREMENT"),
                            new ParamDef("transferOperationCodes", "Codes d'opération concernés", "STRING", "VIR"),
                            new ParamDef("minAmount", "Seuil d'alerte", "MONEY", "0"))),
            new ControlDescriptor(
                    idOf("SEUIL-HORAIRE"), "SEUIL-HORAIRE",
                    "Écritures hors seuil / hors heures ouvrées",
                    "TRANSACTIONS",
                    "Repère les opérations hors cadre : montant au-delà du seuil, saisie un jour non ouvré, "
                            + "ou date de valeur trop antérieure à la date comptable.",
                    "Contrôles analytiques sur population complète ; catégorie Bâle « exécution, livraison et gestion des processus ».",
                    "MOVEMENT",
                    List.of(
                            new ParamDef("thresholdAmount", "Seuil de montant", "MONEY", "1000000"),
                            new ParamDef("nonWorkingDays", "Jours non ouvrés", "STRING", "SAT,SUN"),
                            new ParamDef("maxBackValueDays", "Antériorité de date de valeur tolérée", "NUMBER", "2"),
                            new ParamDef("excludeChannels", "Canaux exclus", "STRING", "BATCH"))),
            new ControlDescriptor(
                    idOf("COMPTES-DORMANTS"), "COMPTES-DORMANTS",
                    "Mouvements sur comptes dormants",
                    "MASTER_DATA",
                    "Détecte tout mouvement sur un compte dormant, en particulier les réactivations suivies "
                            + "de sorties de fonds importantes.",
                    "Réactivation d'un compte dormant suivie de mouvements importants = signal LCB-FT ; loi 43-05 modifiée par la loi 12-18.",
                    "ACCOUNT",
                    List.of(
                            new ParamDef("dormancyMonths", "Durée d'inactivité (mois)", "NUMBER", "12"),
                            new ParamDef("minAmount", "Montant d'alerte", "MONEY", "10000"),
                            new ParamDef("excludeChannels", "Canaux exclus (écritures automatiques)", "STRING", "BATCH"),
                            new ParamDef("largeOutflowAmount", "Seuil de « sortie importante »", "MONEY", "100000"))));

    /** Tous les contrôles du pack. */
    public static List<ControlDescriptor> all() {
        return CONTROLS;
    }

    /** Un contrôle par son identifiant stable. */
    public static Optional<ControlDescriptor> byId(UUID id) {
        return CONTROLS.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** Un contrôle par son code. */
    public static Optional<ControlDescriptor> byCode(String code) {
        return CONTROLS.stream().filter(c -> c.code().equals(code)).findFirst();
    }
}
