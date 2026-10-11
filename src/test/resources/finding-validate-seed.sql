-- Jeu de test : validation d'un constat (§2.1.4, §2.2).
-- Visibilité (finding.read) via affectation d'unité (portée UNIT_TREE sur IG) pour aa, bb, cc.
-- Droit de validation (finding.validate) via appartenance à l'équipe de mission (mission_member),
-- rôle de mission, pour aa et bb seulement (cc ne peut que lire).
-- aa est l'auteur du constat (le test journalise son finding.write) : la SoD dynamique doit lui
-- interdire de valider, alors que bb (non auteur) peut valider.

INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'FINDING', true);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'IG', '{"fr":"Inspection Générale"}', 'ig');

-- Rôle organisationnel : lecture des constats (visibilité).
INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'LECTEUR_CONSTAT', '{"fr":"Lecteur"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'finding.read');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'LECTEUR', '{"fr":"Lecteur"}', 20, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000aa', 'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE),
 ('e0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000bb', 'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE),
 ('e0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000cc', 'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE);

-- Rôle de mission : validation des constats (droit exercé sur la ressource de mission, §2.1.6).
INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001', 'VALIDATEUR_MISSION', '{"fr":"Validateur de mission"}', 'MISSION');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-0000000000a1', 'finding.validate');

INSERT INTO mission(id, tenant_id, org_unit_id, reference, title, mission_type_code, mission_type_label,
                    status_code, status_label, phase_code, phase_label) VALUES
 ('f0000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002',
  'MIS-A001', 'Inspection agence A001', 'INSPECTION', 'Inspection', 'EN_COURS', 'En cours', 'REALISATION', 'Réalisation');

-- aa et bb membres de l'équipe de mission avec le rôle validateur ; cc ne l'est pas.
INSERT INTO mission_member(mission_id, user_id, role_id, valid_from) VALUES
 ('f0000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000aa', 'c0000000-0000-0000-0000-0000000000a1', CURRENT_DATE),
 ('f0000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000bb', 'c0000000-0000-0000-0000-0000000000a1', CURRENT_DATE);

INSERT INTO finding(id, tenant_id, mission_id, org_unit_id, reference, title, status_code, status_label,
                    criticality_code, criticality_label, source, fact, criterion, cause, consequence) VALUES
 ('11110000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'f0000000-0000-0000-0000-0000000000a1',
  'b0000000-0000-0000-0000-000000000002', 'CST-A001-01', 'Cumul saisie/validation des virements',
  'OUVERT', 'Ouvert', 'HIGH', 'Élevée', 'MANUAL',
  'U003 a saisi et validé 3 virements.', 'Double validation obligatoire.',
  'Cumul des habilitations.', 'Risque de fraude.');

-- Utilisateur ee : droit de validation par la PORTÉE D'UNITÉ (position organisationnelle VALIDATEUR,
-- finding.validate en UNIT_TREE sur IG), SANS appartenance à l'équipe de mission — reproduit le flux
-- de démo où l'Admin Système octroie la position VALIDATEUR à l'inspecteur.
INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'VALIDATEUR_ORG', '{"fr":"Valideur"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000002', 'finding.read'),
 ('c0000000-0000-0000-0000-000000000002', 'finding.validate');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'VALIDATEUR', '{"fr":"Valideur"}', 40, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000002');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000ee', 'd0000000-0000-0000-0000-000000000002', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE);

-- Règle SoD dynamique : on ne valide pas un constat qu'on a écrit (même objet, §2.2).
INSERT INTO sod_rule(id, tenant_id, feature_a, feature_b, type, severity) VALUES
 ('d1d1d1d1-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'finding.write', 'finding.validate', 'DYNAMIC', 'BLOCK');
