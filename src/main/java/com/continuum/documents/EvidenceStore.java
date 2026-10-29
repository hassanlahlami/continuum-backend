package com.continuum.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * Port de stockage des fichiers de preuve (ADR 0013). Le MVP stocke en base (BLOB) ; un connecteur GED
 * (L3) s'insere derriere le meme port. Le contenu ne doit jamais apparaitre dans les logs.
 */
public interface EvidenceStore {

    /** Metadonnees d'un contenu stocke ; {@code sha256} en hexadecimal minuscule (64 caracteres). */
    record Stored(String storageKey, String sha256, long sizeBytes, String mediaType) {
    }

    /** Contenu relu : {@code content} doit etre ferme par l'appelant. */
    record Content(Stored meta, InputStream content) {
    }

    /** Verification de l'integrite (sha256) echouee a la lecture. */
    class IntegrityException extends RuntimeException {
        public IntegrityException(String storageKey) {
            super("Evidence integrity check failed: " + storageKey);
        }
    }

    /** Limite de taille ou type MIME non autorise (parametres du tenant, ADR 0013 §4). */
    class RejectedContentException extends RuntimeException {
        public RejectedContentException(String message) {
            super(message);
        }
    }

    /** Depose le contenu (streaming), calcule sha256 et taille, applique les limites du tenant. */
    Stored put(UUID tenantId, String mediaType, InputStream content) throws IOException;

    /** Relit le contenu ; l'integrite sha256 est verifiee a la fin de la lecture. */
    Optional<Content> get(UUID tenantId, String storageKey);

    /** @return {@code false} si la cle est inconnue */
    boolean delete(UUID tenantId, String storageKey);
}
