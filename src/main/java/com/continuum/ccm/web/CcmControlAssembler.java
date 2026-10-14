package com.continuum.ccm.web;

import com.continuum.ccm.api.CcmControlCatalog;
import com.continuum.ccm.api.CcmControlCatalog.ControlDescriptor;
import com.continuum.ccm.api.CcmControlCatalog.ParamDef;
import com.continuum.ccm.api.CcmControlDirectory;
import gen.app.ccm.model.CcmControl;
import gen.app.ccm.model.CcmControlParameterDefinitionsInner;
import gen.app.ccm.model.CcmControlSettings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Assemble les modèles de contrôle CCM du contrat (§5) en fusionnant le <strong>catalogue du pack</strong>
 * ({@link CcmControlCatalog}) avec le paramétrage du tenant ({@link CcmControlDirectory}, CCM-03). Partagé
 * par la bibliothèque de contrôles ({@link CcmControlsController}) et la vue d'ensemble
 * ({@link CcmOverviewController}) pour une représentation identique. La santé reste {@code NEVER_RUN} tant
 * que les exécutions ({@code ccm_run}) ne sont pas historisées.
 */
@Component
class CcmControlAssembler {

    private final CcmControlDirectory controls;

    CcmControlAssembler(CcmControlDirectory controls) {
        this.controls = controls;
    }

    /** Liste des contrôles du tenant, filtrée (recherche libre, catégorie, activation ; tous optionnels). */
    List<CcmControl> list(UUID tenant, String q, String category, Boolean active) {
        Map<String, CcmControlDirectory.CcmControl> config = config(tenant);
        String needle = q == null ? null : q.strip().toLowerCase(Locale.ROOT);
        return CcmControlCatalog.all().stream()
                .map(d -> toModel(d, config.get(d.code())))
                .filter(c -> category == null || category.equals(c.getCategory().getValue()))
                .filter(c -> active == null || active.equals(c.getActive()))
                .filter(c -> needle == null || c.getCode().toLowerCase(Locale.ROOT).contains(needle)
                        || c.getLabel().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    /** Un contrôle par son identifiant, ou vide s'il n'est pas au catalogue. */
    Optional<CcmControl> byId(UUID tenant, UUID controlId) {
        return CcmControlCatalog.byId(controlId).map(d -> toModel(d, config(tenant).get(d.code())));
    }

    private Map<String, CcmControlDirectory.CcmControl> config(UUID tenant) {
        return controls.allControls(tenant).stream()
                .collect(Collectors.toMap(CcmControlDirectory.CcmControl::code, Function.identity()));
    }

    private static CcmControl toModel(ControlDescriptor d, CcmControlDirectory.CcmControl config) {
        boolean enabled = config != null && config.enabled();
        CcmControl model = new CcmControl(d.id(), d.code(), d.label(),
                CcmControl.CategoryEnum.fromValue(d.category()), enabled, CcmControl.HealthEnum.NEVER_RUN)
                .description(d.description())
                .normativeReference(d.normativeReference())
                .dataset(d.dataset())
                .parameterDefinitions(d.parameterDefinitions().stream().map(CcmControlAssembler::toParamDef).toList());
        if (config != null) {
            model.settings(new CcmControlSettings(1).parameters(config.params()));
        }
        return model;
    }

    private static CcmControlParameterDefinitionsInner toParamDef(ParamDef p) {
        return new CcmControlParameterDefinitionsInner(p.name(),
                CcmControlParameterDefinitionsInner.TypeEnum.fromValue(p.type()))
                .label(p.label())
                ._default(p.defaultValue());
    }
}
