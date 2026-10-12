-- Jeu de test Oracle (§5.3, D4) : triage d'une exception CCM. UUID → RAW(16) via HEXTORAW ; booléens
-- NUMBER(1) ; portée UNIT_TREE via org_unit_closure. Parité avec ccm-triage-seed.sql. Le trieur (00A1)
-- détient ccm.exception.triage en UNIT_TREE sur la racine IG ; il trie l'exception de la sous-unité AG
-- comme l'exception sans unité (racine du tenant). L'étranger (00DD) n'a aucun droit.

INSERT INTO platform.tenant_module(tenant_id, module, enabled) VALUES (HEXTORAW('00000000000000000000000000000001'), 'CCM', 1);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES (HEXTORAW('A0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label) VALUES (HEXTORAW('B0000000000000000000000000000002'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('A0000000000000000000000000000001'), NULL, 'IG', '{"fr":"Inspection Générale"}');
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label) VALUES (HEXTORAW('B0000000000000000000000000000003'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('A0000000000000000000000000000001'), HEXTORAW('B0000000000000000000000000000002'), 'AG', '{"fr":"Agence"}');
INSERT INTO org_unit_closure(ancestor_id, descendant_id, depth) VALUES (HEXTORAW('B0000000000000000000000000000002'), HEXTORAW('B0000000000000000000000000000002'), 0);
INSERT INTO org_unit_closure(ancestor_id, descendant_id, depth) VALUES (HEXTORAW('B0000000000000000000000000000003'), HEXTORAW('B0000000000000000000000000000003'), 0);
INSERT INTO org_unit_closure(ancestor_id, descendant_id, depth) VALUES (HEXTORAW('B0000000000000000000000000000002'), HEXTORAW('B0000000000000000000000000000003'), 1);

INSERT INTO role(id, tenant_id, code, label, kind) VALUES (HEXTORAW('C0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'TRIEUR_CCM', '{"fr":"Trieur CCM"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES (HEXTORAW('C0000000000000000000000000000001'), 'ccm.read');
INSERT INTO role_feature(role_id, feature_code) VALUES (HEXTORAW('C0000000000000000000000000000001'), 'ccm.exception.triage');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES (HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'TRIEUR', '{"fr":"Trieur"}', 30, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES (HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('C0000000000000000000000000000001'));
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES (HEXTORAW('E0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('000000000000000000000000000000A1'), HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('B0000000000000000000000000000002'), 'HOLDER', CURRENT_DATE);

INSERT INTO ccm_exception(id, tenant_id, control_code, org_unit_id, reference, title, criticality_code, criticality_label, status_code, status_label, fact, criterion, source_record, parameters_used, data_source) VALUES (HEXTORAW('CE0000000000000000000000000000E1'), HEXTORAW('00000000000000000000000000000001'), 'SEUIL-HORAIRE', HEXTORAW('B0000000000000000000000000000003'), 'CCM-OOB-4', 'Écriture hors cadre (AMOUNT) : 2500000.00 MAD', 'HIGH', 'Élevée', 'NEW', 'À trier', 'Mouvement n°4 (2500000.00 MAD, canal GUICHET).', 'Opération hors seuil (pack CCM-2).', '{"movementId":4,"accountId":"A001000000002"}', '{"thresholdAmount":"1000000"}', 'amplitude (JDBC, vues V_AUDIT_*)');
INSERT INTO ccm_exception(id, tenant_id, control_code, org_unit_id, reference, title, criticality_code, criticality_label, status_code, status_label, fact, criterion, source_record, parameters_used, data_source) VALUES (HEXTORAW('CE0000000000000000000000000000E2'), HEXTORAW('00000000000000000000000000000001'), 'COMPTES-DORMANTS', NULL, 'CCM-DORM-5', 'Mouvement sur compte dormant : 45000.00 MAD', 'HIGH', 'Élevée', 'NEW', 'À trier', 'Mouvement n°5 (45000.00 MAD, canal BATCH).', 'Mouvement sur compte dormant (pack CCM-3).', '{"movementId":5,"accountId":"A002000000003"}', '{"dormancyMonths":12}', 'amplitude (JDBC, vues V_AUDIT_*)');
