-- Jeu de test : bibliothèque de contrôles CCM (cockpit, §5). L'utilisateur 00a1 détient ccm.read en
-- UNIT_TREE sur la racine IG ; l'étranger 00dd n'a aucun droit. 3 contrôles paramétrés : SOD-VIREMENTS
-- et SEUIL-HORAIRE activés, COMPTES-DORMANTS désactivé (pour le filtre `active`).

INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'CCM', true);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'IG', '{"fr":"Inspection Générale"}', 'ig');

INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'LECTEUR_CCM', '{"fr":"Lecteur CCM"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'ccm.read');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'LECTEUR', '{"fr":"Lecteur"}', 20, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE);

INSERT INTO ccm_control (tenant_id, control_code, enabled, params) VALUES
 ('00000000-0000-0000-0000-000000000001', 'SOD-VIREMENTS', true,
  '{"makerFunction":"SAISIE_VIREMENT","checkerFunction":"VALIDATION_VIREMENT","transferOperationCodes":["VIR"]}'),
 ('00000000-0000-0000-0000-000000000001', 'SEUIL-HORAIRE', true,
  '{"thresholdAmount":1000000,"nonWorkingDays":["SAT","SUN"],"maxBackValueDays":2,"excludeChannels":["BATCH"]}'),
 ('00000000-0000-0000-0000-000000000001', 'COMPTES-DORMANTS', false,
  '{"dormancyMonths":12,"minAmount":10000,"excludeChannels":[],"largeOutflowAmount":100000}');
