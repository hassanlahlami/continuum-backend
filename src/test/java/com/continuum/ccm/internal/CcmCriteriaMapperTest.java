package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.CcmControlDirectory.CcmControl;
import com.continuum.ccm.api.CcmPopulationGateway.DormantAccountCriteria;
import com.continuum.ccm.api.CcmPopulationGateway.OutOfBoundsCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Vérifie la traduction paramétrage (CCM-03) → critères du connecteur CBS (§5). Pur, sans base. */
class CcmCriteriaMapperTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Test
    void mapsDormantParams() {
        CcmControl control = new CcmControl("COMPTES-DORMANTS", true, Map.of(
                "dormancyMonths", 18, "minAmount", 25000, "excludeChannels", List.of("BATCH"),
                "largeOutflowAmount", 200000));

        DormantAccountCriteria c = CcmCriteriaMapper.dormant(control, FROM, TO);
        assertThat(c.dormancyMonths()).isEqualTo(18);
        assertThat(c.minAmount()).isEqualByComparingTo("25000");
        assertThat(c.excludeChannels()).containsExactly("BATCH");
        assertThat(c.from()).isEqualTo(FROM);
        assertThat(CcmCriteriaMapper.largeOutflow(control)).isEqualByComparingTo("200000");
    }

    @Test
    void mapsOutOfBoundsParamsWithDefaults() {
        CcmControl control = new CcmControl("SEUIL-HORAIRE", true, Map.of(
                "thresholdAmount", 1500000, "excludeChannels", List.of("BATCH")));

        OutOfBoundsCriteria c = CcmCriteriaMapper.outOfBounds(control, FROM, TO);
        assertThat(c.thresholdAmount()).isEqualByComparingTo("1500000");
        assertThat(c.nonWorkingDays()).containsExactly("SAT", "SUN"); // défaut quand absent
        assertThat(c.maxBackValueDays()).isEqualTo(2); // défaut du pack
        assertThat(c.excludeChannels()).containsExactly("BATCH");
    }
}
