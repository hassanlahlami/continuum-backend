-- Jeu de test : frontière HTTP CcmController (contrat app/ccm.yaml, §5.3, D34).
-- L'utilisateur 00a1 détient ccm.read + ccm.exception.triage + ccm.exception.convert en UNIT_TREE sur
-- la racine IG ; la mission d'accueil est sur AG. Exceptions : une NEW (triage) et une RETENUE (convert).
-- L'étranger 00dd n'a aucun droit.

INSERT INTO platform.tenant_module(tenant_id, module, enabled) VALUES
 ('00000000-0000-0000-0000-000000000001', 'CCM', true),
 ('00000000-0000-0000-0000-000000000001', 'FINDING', true);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'IG', '{"fr":"Inspection Générale"}', 'ig'),
 ('b0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'AG', '{"fr":"Agence"}', 'ig.ag');

INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'AUDITEUR_CCM', '{"fr":"Auditeur CCM"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'ccm.read'),
 ('c0000000-0000-0000-0000-000000000001', 'ccm.exception.triage'),
 ('c0000000-0000-0000-0000-000000000001', 'ccm.exception.convert');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'AUDITEUR', '{"fr":"Auditeur"}', 30, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE);

INSERT INTO mission(id, tenant_id, org_unit_id, reference, title, mission_type_code, mission_type_label,
                    status_code, status_label, phase_code, phase_label) VALUES
 ('f0000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000003',
  'MIS-SC-2026', 'Surveillance continue 2026', 'SURVEILLANCE_CONTINUE', 'Surveillance continue',
  'EN_COURS', 'En cours', 'REALISATION', 'Réalisation');

INSERT INTO ccm_exception(id, tenant_id, control_code, org_unit_id, reference, title,
                          criticality_code, criticality_label, status_code, status_label, fact, criterion) VALUES
 ('ce000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-000000000001', 'COMPTES-DORMANTS',
  'b0000000-0000-0000-0000-000000000003', 'CCM-DORM-5', 'Mouvement sur compte dormant : 45000.00 MAD',
  'HIGH', 'Élevée', 'NEW', 'À trier', 'Mouvement n°5 sur compte dormant.', 'Pack CCM-3.'),
 ('ce000000-0000-0000-0000-0000000000e2', '00000000-0000-0000-0000-000000000001', 'SEUIL-HORAIRE',
  'b0000000-0000-0000-0000-000000000003', 'CCM-OOB-4', 'Écriture hors cadre (AMOUNT) : 2500000.00 MAD',
  'HIGH', 'Élevée', 'RETAINED', 'Retenue', 'Mouvement n°4 hors seuil.', 'Pack CCM-2.');
