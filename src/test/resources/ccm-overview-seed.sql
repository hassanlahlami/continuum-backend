-- Jeu de test : vue d'ensemble CCM (cockpit, contrat app/ccm.yaml getCcmOverview, §5).
-- L'utilisateur 00a1 détient ccm.read en UNIT_TREE sur la racine IG ; l'étranger 00dd n'a aucun droit.
-- 3 contrôles paramétrés (SOD-VIREMENTS + SEUIL-HORAIRE activés, COMPTES-DORMANTS désactivé) → 2 actifs.
-- 4 exceptions : 2 NEW + 1 RETAINED (ouvertes) + 1 DISMISSED (fermée) sur 2 jours (tendance).

INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'CCM', true);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'IG', '{"fr":"Inspection Générale"}', 'ig'),
 ('b0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'AG', '{"fr":"Agence"}', 'ig.ag');

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
 ('00000000-0000-0000-0000-000000000001', 'SOD-VIREMENTS', true, '{}'),
 ('00000000-0000-0000-0000-000000000001', 'SEUIL-HORAIRE', true, '{}'),
 ('00000000-0000-0000-0000-000000000001', 'COMPTES-DORMANTS', false, '{}');

INSERT INTO ccm_exception(id, tenant_id, control_code, org_unit_id, reference, title,
                          criticality_code, criticality_label, status_code, status_label, fact, criterion, detected_at) VALUES
 ('ce000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-000000000001', 'SEUIL-HORAIRE',
  'b0000000-0000-0000-0000-000000000003', 'CCM-OV-1', 'Écriture hors cadre #1', 'HIGH', 'Élevée',
  'NEW', 'À trier', 'f1', 'c1', TIMESTAMP '2026-09-10 08:00:00+00'),
 ('ce000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-000000000001', 'SEUIL-HORAIRE',
  'b0000000-0000-0000-0000-000000000003', 'CCM-OV-2', 'Écriture hors cadre #2', 'MEDIUM', 'Moyenne',
  'NEW', 'À trier', 'f2', 'c2', TIMESTAMP '2026-09-10 09:30:00+00'),
 ('ce000000-0000-0000-0000-0000000000f3', '00000000-0000-0000-0000-000000000001', 'SOD-VIREMENTS',
  'b0000000-0000-0000-0000-000000000003', 'CCM-OV-3', 'Cumul saisie/validation', 'HIGH', 'Élevée',
  'RETAINED', 'Retenue', 'f3', 'c3', TIMESTAMP '2026-09-11 10:00:00+00'),
 ('ce000000-0000-0000-0000-0000000000f4', '00000000-0000-0000-0000-000000000001', 'COMPTES-DORMANTS',
  'b0000000-0000-0000-0000-000000000003', 'CCM-OV-4', 'Compte dormant réactivé', 'LOW', 'Faible',
  'DISMISSED', 'Écartée', 'f4', 'c4', TIMESTAMP '2026-09-11 11:15:00+00');
