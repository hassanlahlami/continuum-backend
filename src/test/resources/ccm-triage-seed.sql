-- Jeu de test : triage d'une exception CCM (§5.3, D34).
-- Un utilisateur « trieur » (00a1) détient ccm.exception.triage par la PORTÉE UNIT_TREE sur la racine
-- (IG) : il peut trier une exception rattachée à une sous-unité comme une exception sans unité (contrôle
-- population, rattachée à la racine du tenant). Un « étranger » (00dd) n'a aucun droit. Module CCM activé.

INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'CCM', true);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'IG', '{"fr":"Inspection Générale"}', 'ig'),
 ('b0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'AG', '{"fr":"Agence"}', 'ig.ag');

-- Rôle + position portant ccm.exception.triage en UNIT_TREE sur la racine, affecté au trieur 00a1.
INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'TRIEUR_CCM', '{"fr":"Trieur CCM"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'ccm.read'),
 ('c0000000-0000-0000-0000-000000000001', 'ccm.exception.triage');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'TRIEUR', '{"fr":"Trieur"}', 30, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE);

-- Deux exceptions à trier : une rattachée à la sous-unité, une sans unité (contrôle population).
INSERT INTO ccm_exception(id, tenant_id, control_code, org_unit_id, reference, title,
                          criticality_code, criticality_label, status_code, status_label,
                          fact, criterion, source_record, parameters_used, data_source) VALUES
 ('ce000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-000000000001', 'SEUIL-HORAIRE',
  'b0000000-0000-0000-0000-000000000003', 'CCM-OOB-4', 'Écriture hors cadre (AMOUNT) : 2500000.00 MAD',
  'HIGH', 'Élevée', 'NEW', 'À trier',
  'Mouvement n°4 (2500000.00 MAD, canal GUICHET).', 'Opération hors seuil (pack CCM-2).',
  '{"movementId":4,"accountId":"A001000000002"}'::jsonb, '{"thresholdAmount":"1000000"}'::jsonb,
  'amplitude (JDBC, vues V_AUDIT_*)'),
 ('ce000000-0000-0000-0000-0000000000e2', '00000000-0000-0000-0000-000000000001', 'COMPTES-DORMANTS',
  NULL, 'CCM-DORM-5', 'Mouvement sur compte dormant : 45000.00 MAD',
  'HIGH', 'Élevée', 'NEW', 'À trier',
  'Mouvement n°5 (45000.00 MAD, canal BATCH).', 'Mouvement sur compte dormant (pack CCM-3).',
  '{"movementId":5,"accountId":"A002000000003"}'::jsonb, '{"dormancyMonths":12}'::jsonb,
  'amplitude (JDBC, vues V_AUDIT_*)');
