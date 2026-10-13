package com.continuum.ccm.api;

import java.util.UUID;

/**
 * Conversion d'une exception CCM <strong>retenue</strong> en <strong>constat candidat</strong> rattaché à
 * une mission (plan §5.3, D34 ; contrat {@code app/ccm.yaml} {@code convertCcmExceptionToFinding}). C'est
 * le pivot de l'audit par exception : aucun constat n'est créé par le moteur ; seul un auditeur habilité,
 * après triage, promeut une exception retenue en constat (FRAP pré-remplie à partir du fait et du critère
 * de l'exception). Le serveur décide : droit {@code ccm.exception.convert} (§2.1.6), exception bien
 * {@code RETAINED} et non déjà convertie, mission existante ; journalisation signée (§2.3).
 */
public interface CcmExceptionConversionService {

    /** Code d'action / fonctionnalité journalisé. */
    String FEATURE_CONVERT = "ccm.exception.convert";

    /**
     * Convertit une exception retenue en constat candidat dans la mission indiquée.
     *
     * @return l'identifiant du constat créé et celui de la mission
     * @throws ExceptionNotVisibleException l'exception n'existe pas pour ce tenant → 404
     * @throws ConvertNotPermittedException droit {@code ccm.exception.convert} absent → 403
     * @throws NotRetainedException         l'exception n'est pas au statut {@code RETAINED} → 409
     * @throws AlreadyConvertedException    l'exception porte déjà un constat → 409
     * @throws MissionNotFoundException     la mission n'existe pas pour ce tenant → 409
     */
    ConversionResult convert(ConvertCommand command);

    /**
     * Commande de conversion.
     *
     * @param tenantId     entité
     * @param actorUserId  auteur de la conversion
     * @param exceptionId  exception retenue à promouvoir
     * @param missionId    mission d'accueil du constat candidat (§5.3 : tout constat est rattaché à une mission)
     */
    record ConvertCommand(UUID tenantId, UUID actorUserId, UUID exceptionId, UUID missionId) {
    }

    /** Résultat : le constat candidat créé et sa mission. */
    record ConversionResult(UUID findingId, UUID missionId) {
    }

    /** Exception inconnue pour ce tenant → 404. */
    class ExceptionNotVisibleException extends RuntimeException {
        public ExceptionNotVisibleException() {
            super("Exception inconnue ou hors périmètre.");
        }
    }

    /** Droit {@code ccm.exception.convert} absent sur cette exception (§2.1.6) → 403. */
    class ConvertNotPermittedException extends RuntimeException {
        public ConvertNotPermittedException() {
            super("Conversion refusée : fonctionnalité ccm.exception.convert absente sur cette exception (§2.1.6).");
        }
    }

    /** Seule une exception retenue peut devenir un constat (§5.3) → 409. */
    class NotRetainedException extends RuntimeException {
        public NotRetainedException(String status) {
            super("Seule une exception retenue devient un constat ; statut actuel : " + status + ".");
        }
    }

    /** L'exception a déjà été convertie en constat → 409. */
    class AlreadyConvertedException extends RuntimeException {
        public AlreadyConvertedException() {
            super("Cette exception a déjà été convertie en constat.");
        }
    }

    /** Mission d'accueil inconnue pour ce tenant → 409. */
    class MissionNotFoundException extends RuntimeException {
        public MissionNotFoundException() {
            super("Mission d'accueil inconnue pour ce tenant.");
        }
    }
}
