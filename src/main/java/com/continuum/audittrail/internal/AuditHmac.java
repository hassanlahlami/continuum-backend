package com.continuum.audittrail.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Canonicalisation et signature HMAC-SHA256 d'une entrée du journal d'audit (plan §2.3), partagées par
 * les implémentations PostgreSQL et Oracle pour garantir une empreinte <strong>identique</strong> quel
 * que soit le dialecte (le format signé ne doit jamais diverger entre bases).
 */
final class AuditHmac {

    /** Séparateur d'unités (US 0x1F, non ambigu) entre champs du message signé. */
    private static final String SEP = String.valueOf((char) 0x1F);
    private static final String HMAC_ALGO = "HmacSHA256";

    private AuditHmac() {
    }

    static String hash(byte[] key, long seq, Instant occurredAt, UUID actor, String action,
            String objectType, String objectId, String payload, String prevHash) {
        String canonical = String.join(SEP,
                Long.toString(seq),
                Long.toString(micros(occurredAt)),
                nullToEmpty(actor == null ? null : actor.toString()),
                nullToEmpty(action),
                nullToEmpty(objectType),
                nullToEmpty(objectId),
                nullToEmpty(payload),
                nullToEmpty(prevHash));
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Calcul HMAC impossible", e);
        }
    }

    private static long micros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
