package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmPopulationGateway.DormantAccountCriteria;
import com.continuum.ccm.api.CcmPopulationGateway.MovementAnomaly;
import com.continuum.ccm.api.CcmPopulationGateway.OutOfBoundsCriteria;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sérialisation JSON de la <strong>chaîne de preuve</strong> d'une exception CCM (D34, §5.3) :
 * l'enregistrement source du CBS ({@code source_record}) et les paramètres d'exécution
 * ({@code parameters_used}, pour la rejouabilité). Champs du modèle canonique du pack (pas de SQL
 * dialecte-spécifique). Partagé par les implémentations PostgreSQL et Oracle.
 */
final class CcmExceptionJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CcmExceptionJson() {
    }

    /** Enregistrement source : le mouvement CBS tel que remonté par le connecteur (§5.3). */
    static String movement(MovementAnomaly a) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("movementId", a.movementId());
        record.put("accountId", a.accountId());
        record.put("operationDate", str(a.operationDate()));
        record.put("valueDate", str(a.valueDate()));
        record.put("amount", str(a.amount()));
        record.put("direction", a.direction());
        record.put("operationCode", a.operationCode());
        record.put("enteredBy", a.enteredBy());
        record.put("validatedBy", a.validatedBy());
        record.put("channel", a.channel());
        record.put("triggerReason", a.triggerReason());
        return write(record);
    }

    static String dormantParameters(DormantAccountCriteria c, BigDecimal largeOutflowAmount) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dormancyMonths", c.dormancyMonths());
        p.put("minAmount", str(c.minAmount()));
        p.put("excludeChannels", c.excludeChannels());
        p.put("largeOutflowAmount", str(largeOutflowAmount));
        p.put("from", str(c.from()));
        p.put("to", str(c.to()));
        return write(p);
    }

    static String outOfBoundsParameters(OutOfBoundsCriteria c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("thresholdAmount", str(c.thresholdAmount()));
        p.put("nonWorkingDays", c.nonWorkingDays());
        p.put("maxBackValueDays", c.maxBackValueDays());
        p.put("excludeChannels", c.excludeChannels());
        p.put("from", str(c.from()));
        p.put("to", str(c.to()));
        return write(p);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String write(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Sérialisation JSON de l'exception CCM impossible", e);
        }
    }
}
