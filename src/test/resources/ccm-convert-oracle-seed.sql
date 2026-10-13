-- Jeu de test Oracle (§5.3, D4) : conversion d'une exception retenue en constat candidat. RAW(16) via
-- HEXTORAW ; portée UNIT_TREE via table de fermeture. Parité avec ccm-convert-seed.sql. Le convertisseur
-- (00A1) détient ccm.exception.convert en UNIT_TREE sur IG ; la mission est rattachée à AG.

INSERT INTO platform.tenant_module(tenant_id, module, enabled) VALUES (HEXTORAW('00000000000000000000000000000001'), 'CCM', 1);
INSERT INTO platform.tenant_module(tenant_id, module, enabled) VALUES (HEXTORAW('00000000000000000000000000000001'), 'FINDING', 1);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES (HEXTORAW('A0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label) VALUES (HEXTORAW('B0000000000000000000000000000002'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('A0000000000000000000000000000001'), NULL, 'IG', '{"fr":"Inspection Générale"}');
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label) VALUES (HEXTORAW('B0000000000000000000000000000003'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('A0000000000000000000000000000001'), HEXTORAW('B0000000000000000000000000000002'), 'AG', '{"fr":"Agence"}');
INSERT INTO org_unit_closure(ancestor_id, descendant_id, depth) VALUES (HEXTORAW('B0000000000000000000000000000002'), HEXTORAW('B0000000000000000000000000000002'), 0);
INSERT INTO org_unit_closure(ancestor_id, descendant_id, depth) VALUES (HEXTORAW('B0000000000000000000000000000003'), HEXTORAW('B0000000000000000000000000000003'), 0);
INSERT INTO org_unit_closure(ancestor_id, descendant_id, depth) VALUES (HEXTORAW('B0000000000000000000000000000002'), HEXTORAW('B0000000000000000000000000000003'), 1);

INSERT INTO role(id, tenant_id, code, label, kind) VALUES (HEXTORAW('C0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'CONVERTISSEUR_CCM', '{"fr":"Convertisseur CCM"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES (HEXTORAW('C0000000000000000000000000000001'), 'ccm.read');
INSERT INTO role_feature(role_id, feature_code) VALUES (HEXTORAW('C0000000000000000000000000000001'), 'ccm.exception.convert');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES (HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'CONVERTISSEUR', '{"fr":"Convertisseur"}', 30, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES (HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('C0000000000000000000000000000001'));
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES (HEXTORAW('E0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('000000000000000000000000000000A1'), HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('B0000000000000000000000000000002'), 'HOLDER', CURRENT_DATE);

INSERT INTO mission(id, tenant_id, org_unit_id, reference, title, mission_type_code, mission_type_label, status_code, status_label, phase_code, phase_label, progress_percent) VALUES (HEXTORAW('F00000000000000000000000000000A1'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('B0000000000000000000000000000003'), 'MIS-SC-2026', 'Surveillance continue 2026', 'SURVEILLANCE_CONTINUE', 'Surveillance continue', 'EN_COURS', 'En cours', 'REALISATION', 'Réalisation', 0);

INSERT INTO ccm_exception(id, tenant_id, control_code, org_unit_id, reference, title, criticality_code, criticality_label, status_code, status_label, fact, criterion, data_source) VALUES (HEXTORAW('CE0000000000000000000000000000E1'), HEXTORAW('00000000000000000000000000000001'), 'SEUIL-HORAIRE', HEXTORAW('B0000000000000000000000000000003'), 'CCM-OOB-4', 'Écriture hors cadre (AMOUNT) : 2500000.00 MAD', 'HIGH', 'Élevée', 'RETAINED', 'Retenue', 'Mouvement n°4 (2500000.00 MAD, canal GUICHET).', 'Opération hors seuil (pack CCM-2).', 'amplitude (JDBC, vues V_AUDIT_*)');
INSERT INTO ccm_exception(id, tenant_id, control_code, org_unit_id, reference, title, criticality_code, criticality_label, status_code, status_label, fact, criterion, data_source) VALUES (HEXTORAW('CE0000000000000000000000000000E2'), HEXTORAW('00000000000000000000000000000001'), 'COMPTES-DORMANTS', HEXTORAW('B0000000000000000000000000000003'), 'CCM-DORM-5', 'Mouvement sur compte dormant : 45000.00 MAD', 'HIGH', 'Élevée', 'NEW', 'À trier', 'Mouvement n°5 (45000.00 MAD, canal BATCH).', 'Mouvement sur compte dormant (pack CCM-3).', 'amplitude (JDBC, vues V_AUDIT_*)');
