-- Jeu de test Oracle (§6, D4) pour les read models missions/constats. Schéma courant = PLATFORM.
-- UUID → RAW(16) via HEXTORAW ; booléens NUMBER(1) ; table de fermeture pour UNIT_TREE.
-- L'utilisateur aa a mission.read + finding.read en portée UNIT_TREE sur IG. Une mission et un
-- constat sont sur IG (visibles), un de chaque sur OTHER (non visibles).

INSERT INTO platform.tenant_module(tenant_id, module, enabled) VALUES (HEXTORAW('00000000000000000000000000000001'), 'MISSION', 1);
INSERT INTO platform.tenant_module(tenant_id, module, enabled) VALUES (HEXTORAW('00000000000000000000000000000001'), 'FINDING', 1);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES (HEXTORAW('A0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label) VALUES (HEXTORAW('B0000000000000000000000000000002'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('A0000000000000000000000000000001'), NULL, 'IG', '{"fr":"Inspection Générale"}');
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label) VALUES (HEXTORAW('B0000000000000000000000000000009'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('A0000000000000000000000000000001'), NULL, 'OTHER', '{"fr":"Autre direction"}');
INSERT INTO org_unit_closure(ancestor_id, descendant_id, depth) VALUES (HEXTORAW('B0000000000000000000000000000002'), HEXTORAW('B0000000000000000000000000000002'), 0);
INSERT INTO org_unit_closure(ancestor_id, descendant_id, depth) VALUES (HEXTORAW('B0000000000000000000000000000009'), HEXTORAW('B0000000000000000000000000000009'), 0);

INSERT INTO role(id, tenant_id, code, label, kind) VALUES (HEXTORAW('C0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'LECTEUR', '{"fr":"Lecteur"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES (HEXTORAW('C0000000000000000000000000000001'), 'mission.read');
INSERT INTO role_feature(role_id, feature_code) VALUES (HEXTORAW('C0000000000000000000000000000001'), 'finding.read');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES (HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'LECTEUR', '{"fr":"Lecteur"}', 20, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES (HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('C0000000000000000000000000000001'));
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES (HEXTORAW('E0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('000000000000000000000000000000AA'), HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('B0000000000000000000000000000002'), 'HOLDER', CURRENT_DATE);

INSERT INTO mission(id, tenant_id, org_unit_id, reference, title, mission_type_code, mission_type_label, status_code, status_label, phase_code, phase_label, progress_percent) VALUES (HEXTORAW('F00000000000000000000000000000A1'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('B0000000000000000000000000000002'), 'MIS-A001', 'Inspection agence A001', 'INSPECTION', 'Inspection', 'EN_COURS', 'En cours', 'REALISATION', 'Réalisation', 40);
INSERT INTO mission(id, tenant_id, org_unit_id, reference, title, mission_type_code, mission_type_label, status_code, status_label, phase_code, phase_label, progress_percent) VALUES (HEXTORAW('F00000000000000000000000000000A2'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('B0000000000000000000000000000009'), 'MIS-B002', 'Audit hors périmètre', 'AUDIT', 'Audit', 'PLANIFIEE', 'Planifiée', 'PREPARATION', 'Préparation', 0);

INSERT INTO finding(id, tenant_id, mission_id, org_unit_id, reference, title, status_code, status_label, criticality_code, criticality_label, source) VALUES (HEXTORAW('11110000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('F00000000000000000000000000000A1'), HEXTORAW('B0000000000000000000000000000002'), 'CST-A001-01', 'Cumul saisie/validation', 'OUVERT', 'Ouvert', 'HIGH', 'Élevée', 'CCM');
INSERT INTO finding(id, tenant_id, mission_id, org_unit_id, reference, title, status_code, status_label, criticality_code, criticality_label, source) VALUES (HEXTORAW('11110000000000000000000000000002'), HEXTORAW('00000000000000000000000000000001'), NULL, HEXTORAW('B0000000000000000000000000000009'), 'CST-B002-01', 'Constat hors périmètre', 'OUVERT', 'Ouvert', 'LOW', 'Faible', 'MANUAL');
