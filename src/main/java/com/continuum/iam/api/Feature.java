package com.continuum.iam.api;

import static com.continuum.iam.api.FeatureModule.ADMIN;
import static com.continuum.iam.api.FeatureModule.AUDITEE;
import static com.continuum.iam.api.FeatureModule.CCM;
import static com.continuum.iam.api.FeatureModule.CHARTER;
import static com.continuum.iam.api.FeatureModule.EXTERNAL;
import static com.continuum.iam.api.FeatureModule.FINDING;
import static com.continuum.iam.api.FeatureModule.FOLLOWUP;
import static com.continuum.iam.api.FeatureModule.MISSION;
import static com.continuum.iam.api.FeatureModule.OPS;
import static com.continuum.iam.api.FeatureModule.PLAN;
import static com.continuum.iam.api.FeatureModule.QUALITY;
import static com.continuum.iam.api.FeatureModule.QUESTIONNAIRE;
import static com.continuum.iam.api.FeatureModule.RECOMMENDATION;
import static com.continuum.iam.api.FeatureModule.REPORT;
import static com.continuum.iam.api.FeatureModule.REPORTING;
import static com.continuum.iam.api.FeatureModule.REQUEST;
import static com.continuum.iam.api.FeatureModule.RESOURCE;
import static com.continuum.iam.api.FeatureModule.RISK;
import static com.continuum.iam.api.FeatureModule.SETTINGS;
import static com.continuum.iam.api.FeatureModule.SIGNATURE;
import static com.continuum.iam.api.FeatureModule.TIMESHEET;
import static com.continuum.iam.api.FeatureModule.UNIVERSE;
import static com.continuum.iam.api.FeatureModule.VERIFICATION;
import static com.continuum.iam.api.FeatureModule.WORKPAPER;

/**
 * Registre des fonctionnalités — <strong>source de vérité du code</strong> (plan §2.1.7).
 *
 * <p>Le moteur d'autorisation ne connaît que des fonctionnalités, jamais des positions
 * ({@code can("mission.order.approve", resource)}, §2.1). Ce registre est synchronisé vers la table
 * {@code feature} au démarrage ; une fonctionnalité retirée passe {@link #deprecated} (jamais
 * supprimée, pour l'historique, §2.1.7). Le script Flyway {@code R__feature_catalog.sql} du
 * {@code continuum-migrator} en est dérivé (§4.3.5).
 *
 * <p>Contenu issu du catalogue source de vérité `Instructions/docs/pages/FONCTIONNALITES.md`
 * (119 fonctionnalités, dont 35 {@code sensitive}). {@code sensitive = true} déclenche la double
 * validation à l'octroi et l'authentification renforcée à l'usage (§2.1.8).
 *
 * <p>{@code scopable} vaut {@code true} par défaut (§2.1.6) : la fonctionnalité s'évalue sur une
 * unité/mission. Le marquage fin par fonctionnalité est ⟨à valider⟩ lors des SFD IAM.
 */
public enum Feature {

    // --- ADMIN ---------------------------------------------------------------
    ADMIN_ALERTS_MANAGE("admin.alerts.manage", ADMIN, false),
    ADMIN_ASSIGNMENT_MANAGE("admin.assignment.manage", ADMIN, true),
    ADMIN_ASSIGNMENT_READ("admin.assignment.read", ADMIN, false),
    ADMIN_AUTHZ_SIMULATE("admin.authz.simulate", ADMIN, false),
    ADMIN_CACHE_EVICT("admin.cache.evict", ADMIN, false),
    ADMIN_CACHE_EVICT_ALL("admin.cache.evict.all", ADMIN, true),
    ADMIN_CACHE_READ("admin.cache.read", ADMIN, false),
    ADMIN_CHANGE_APPROVE("admin.change.approve", ADMIN, true),
    ADMIN_ENTITY_MANAGE("admin.entity.manage", ADMIN, true),
    ADMIN_ENTITY_READ("admin.entity.read", ADMIN, false),
    ADMIN_LOGS_EXPORT("admin.logs.export", ADMIN, true),
    ADMIN_LOGS_LEVEL_CHANGE("admin.logs.level.change", ADMIN, true),
    ADMIN_LOGS_SECURITY_READ("admin.logs.security.read", ADMIN, true),
    ADMIN_LOGS_TECH_READ("admin.logs.tech.read", ADMIN, false),
    ADMIN_ORG_MANAGE("admin.org.manage", ADMIN, true),
    ADMIN_ORG_READ("admin.org.read", ADMIN, false),
    ADMIN_OVERVIEW_READ("admin.overview.read", ADMIN, false),
    ADMIN_POSITION_MANAGE("admin.position.manage", ADMIN, true),
    ADMIN_POSITION_READ("admin.position.read", ADMIN, false),
    ADMIN_RECERTIFICATION_MANAGE("admin.recertification.manage", ADMIN, true),
    ADMIN_ROLE_MANAGE("admin.role.manage", ADMIN, true),
    ADMIN_ROLE_READ("admin.role.read", ADMIN, false),
    ADMIN_SOD_MANAGE("admin.sod.manage", ADMIN, true),
    ADMIN_SOD_READ("admin.sod.read", ADMIN, false),
    ADMIN_USER_MANAGE("admin.user.manage", ADMIN, true),
    ADMIN_USER_READ("admin.user.read", ADMIN, false),

    // --- AUDITEE -------------------------------------------------------------
    AUDITEE_SPACE("auditee.space", AUDITEE, false),

    // --- CCM -----------------------------------------------------------------
    CCM_CONTROL_CONFIGURE("ccm.control.configure", CCM, true),
    CCM_CONTROL_RUN("ccm.control.run", CCM, false),
    CCM_EXCEPTION_CONVERT("ccm.exception.convert", CCM, false),
    CCM_EXCEPTION_TRIAGE("ccm.exception.triage", CCM, false),
    CCM_POPULATION_EXPLORE("ccm.population.explore", CCM, false),
    CCM_READ("ccm.read", CCM, false),
    CCM_RECONCILIATION_READ("ccm.reconciliation.read", CCM, false),

    // --- CHARTER -------------------------------------------------------------
    CHARTER_APPROVE("charter.approve", CHARTER, true),

    // --- EXTERNAL ------------------------------------------------------------
    EXTERNAL_FINDING_MANAGE("external.finding.manage", EXTERNAL, false),
    EXTERNAL_FINDING_READ("external.finding.read", EXTERNAL, false),

    // --- FINDING -------------------------------------------------------------
    FINDING_CONSOLIDATE("finding.consolidate", FINDING, false),
    FINDING_READ("finding.read", FINDING, false),
    FINDING_REVIEW("finding.review", FINDING, false),
    FINDING_VALIDATE("finding.validate", FINDING, true),
    FINDING_WRITE("finding.write", FINDING, false),

    // --- FOLLOWUP ------------------------------------------------------------
    FOLLOWUP_MANAGE("followup.manage", FOLLOWUP, false),

    // --- MISSION -------------------------------------------------------------
    MISSION_CREATE("mission.create", MISSION, false),
    MISSION_DOCUMENT_WRITE("mission.document.write", MISSION, false),
    MISSION_MEETING_WRITE("mission.meeting.write", MISSION, false),
    MISSION_ORDER_APPROVE("mission.order.approve", MISSION, true),
    MISSION_ORDER_WRITE("mission.order.write", MISSION, false),
    MISSION_PROGRAM_MANAGE("mission.program.manage", MISSION, false),
    MISSION_READ("mission.read", MISSION, false),
    MISSION_SCHEDULE_MANAGE("mission.schedule.manage", MISSION, false),
    MISSION_TEAM_MANAGE("mission.team.manage", MISSION, false),
    MISSION_UPDATE("mission.update", MISSION, false),

    // --- OPS -----------------------------------------------------------------
    OPS_AI_MANAGE("ops.ai.manage", OPS, true),
    OPS_AUDITTRAIL_READ("ops.audittrail.read", OPS, false),
    OPS_AUDITTRAIL_VERIFY("ops.audittrail.verify", OPS, true),
    OPS_CONFIG_TRANSFER("ops.config.transfer", OPS, true),
    OPS_CONNECTOR_MANAGE("ops.connector.manage", OPS, true),
    OPS_CONNECTOR_READ("ops.connector.read", OPS, false),
    OPS_EXPORT_MANAGE("ops.export.manage", OPS, true),
    OPS_HEALTH_READ("ops.health.read", OPS, false),
    OPS_IMPORT_MANAGE("ops.import.manage", OPS, true),
    OPS_JOB_MANAGE("ops.job.manage", OPS, false),
    OPS_LICENSE_MANAGE("ops.license.manage", OPS, true),
    OPS_PACK_MANAGE("ops.pack.manage", OPS, true),
    OPS_RETENTION_MANAGE("ops.retention.manage", OPS, true),
    OPS_RETENTION_READ("ops.retention.read", OPS, false),
    OPS_SIGNATURE_PROVIDER_MANAGE("ops.signature-provider.manage", OPS, true),
    OPS_SUPPORT_APPROVE("ops.support.approve", OPS, true),
    OPS_SUPPORT_REQUEST("ops.support.request", OPS, false),
    OPS_THEME_MANAGE("ops.theme.manage", OPS, false),
    OPS_WORKFLOW_DEPLOY("ops.workflow.deploy", OPS, true),
    OPS_WORKFLOW_OPERATE("ops.workflow.operate", OPS, true),

    // --- PLAN ----------------------------------------------------------------
    PLAN_APPROVE("plan.approve", PLAN, true),
    PLAN_MANAGE("plan.manage", PLAN, false),
    PLAN_READ("plan.read", PLAN, false),

    // --- QUALITY -------------------------------------------------------------
    QUALITY_MANAGE("quality.manage", QUALITY, false),
    QUALITY_READ("quality.read", QUALITY, false),

    // --- QUESTIONNAIRE -------------------------------------------------------
    QUESTIONNAIRE_CAMPAIGN_MANAGE("questionnaire.campaign.manage", QUESTIONNAIRE, false),
    QUESTIONNAIRE_TEMPLATE_MANAGE("questionnaire.template.manage", QUESTIONNAIRE, false),

    // --- RECOMMENDATION ------------------------------------------------------
    RECOMMENDATION_CLOSE("recommendation.close", RECOMMENDATION, true),
    RECOMMENDATION_MANAGE("recommendation.manage", RECOMMENDATION, false),
    RECOMMENDATION_READ("recommendation.read", RECOMMENDATION, false),

    // --- REPORT --------------------------------------------------------------
    REPORT_CONTRADICTORY_MANAGE("report.contradictory.manage", REPORT, false),
    REPORT_DISTRIBUTE("report.distribute", REPORT, false),
    REPORT_FINAL_APPROVE("report.final.approve", REPORT, true),
    REPORT_FINAL_GENERATE("report.final.generate", REPORT, false),
    REPORT_READ("report.read", REPORT, false),
    REPORT_WRITE("report.write", REPORT, false),

    // --- REPORTING -----------------------------------------------------------
    REPORTING_COMMITTEE_MANAGE("reporting.committee.manage", REPORTING, false),
    REPORTING_DASHBOARD_READ("reporting.dashboard.read", REPORTING, false),
    REPORTING_EXPORT("reporting.export", REPORTING, false),
    REPORTING_REGULATOR_MANAGE("reporting.regulator.manage", REPORTING, false),

    // --- REQUEST -------------------------------------------------------------
    REQUEST_MANAGE("request.manage", REQUEST, false),
    REQUEST_READ("request.read", REQUEST, false),

    // --- RESOURCE ------------------------------------------------------------
    RESOURCE_MANAGE("resource.manage", RESOURCE, false),
    RESOURCE_READ("resource.read", RESOURCE, false),

    // --- RISK ----------------------------------------------------------------
    RISK_ASSESSMENT_MANAGE("risk.assessment.manage", RISK, false),
    RISK_ASSESSMENT_READ("risk.assessment.read", RISK, false),

    // --- SETTINGS ------------------------------------------------------------
    SETTINGS_CHARTER_MANAGE("settings.charter.manage", SETTINGS, true),
    SETTINGS_INDEPENDENCE_MANAGE("settings.independence.manage", SETTINGS, false),
    SETTINGS_INDICATOR_MANAGE("settings.indicator.manage", SETTINGS, false),
    SETTINGS_NOTIFICATION_MANAGE("settings.notification.manage", SETTINGS, false),
    SETTINGS_PUBLICATION_APPROVE("settings.publication.approve", SETTINGS, true),
    SETTINGS_READ("settings.read", SETTINGS, false),
    SETTINGS_REFERENTIAL_MANAGE("settings.referential.manage", SETTINGS, false),
    SETTINGS_SIGNATURE_MANAGE("settings.signature.manage", SETTINGS, false),
    SETTINGS_TEMPLATE_MANAGE("settings.template.manage", SETTINGS, false),
    SETTINGS_UI_MANAGE("settings.ui.manage", SETTINGS, false),
    SETTINGS_WORKFLOW_MANAGE("settings.workflow.manage", SETTINGS, false),

    // --- SIGNATURE -----------------------------------------------------------
    SIGNATURE_INBOX("signature.inbox", SIGNATURE, false),
    SIGNATURE_REGISTER_READ("signature.register.read", SIGNATURE, false),

    // --- TIMESHEET -----------------------------------------------------------
    TIMESHEET_APPROVE("timesheet.approve", TIMESHEET, false),
    TIMESHEET_WRITE("timesheet.write", TIMESHEET, false),

    // --- UNIVERSE ------------------------------------------------------------
    UNIVERSE_MANAGE("universe.manage", UNIVERSE, false),
    UNIVERSE_READ("universe.read", UNIVERSE, false),

    // --- VERIFICATION --------------------------------------------------------
    VERIFICATION_EXECUTE("verification.execute", VERIFICATION, false),

    // --- WORKPAPER -----------------------------------------------------------
    WORKPAPER_REVIEW("workpaper.review", WORKPAPER, false),
    WORKPAPER_WRITE("workpaper.write", WORKPAPER, false);

    private final String code;
    private final FeatureModule module;
    private final boolean sensitive;
    private final boolean scopable;
    private final boolean deprecated;

    Feature(String code, FeatureModule module, boolean sensitive) {
        this(code, module, sensitive, true, false);
    }

    Feature(String code, FeatureModule module, boolean sensitive, boolean scopable, boolean deprecated) {
        this.code = code;
        this.module = module;
        this.sensitive = sensitive;
        this.scopable = scopable;
        this.deprecated = deprecated;
    }

    /** Code stable exposé aux contrats et au front (ex. {@code mission.order.approve}). */
    public String code() {
        return code;
    }

    /** Domaine fonctionnel, pour l'activation par module d'un tenant. */
    public FeatureModule module() {
        return module;
    }

    /** Double validation à l'octroi + authentification renforcée à l'usage (§2.1.8). */
    public boolean sensitive() {
        return sensitive;
    }

    /** S'évalue sur une unité/mission (§2.1.6). */
    public boolean scopable() {
        return scopable;
    }

    /** Retirée du produit mais conservée pour l'historique (§2.1.7). */
    public boolean deprecated() {
        return deprecated;
    }

    /** Clé i18n du libellé (résolue côté front / table {@code feature.label_key}). */
    public String labelKey() {
        return "feature." + code;
    }
}
