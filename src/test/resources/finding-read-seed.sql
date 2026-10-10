-- Jeu de test : lecture des constats scindée par périmètre (§2.1.6, finding.read).
-- L'utilisateur aa a finding.read en portée UNIT_TREE sur IG. Un constat est sur IG (visible),
-- un autre sur une branche sœur OTHER (non visible).

INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'FINDING', true);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'IG',    '{"fr":"Inspection Générale"}', 'ig'),
 ('b0000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'OTHER', '{"fr":"Autre direction"}',     'other');

INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'LECTEUR_CONSTAT', '{"fr":"Lecteur"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'finding.read');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'LECTEUR', '{"fr":"Lecteur"}', 20, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000aa',
  'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE);

INSERT INTO mission(id, tenant_id, org_unit_id, reference, title, mission_type_code, mission_type_label,
                    status_code, status_label, phase_code, phase_label) VALUES
 ('f0000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002',
  'MIS-A001', 'Inspection agence A001', 'INSPECTION', 'Inspection', 'EN_COURS', 'En cours', 'REALISATION', 'Réalisation');

INSERT INTO finding(id, tenant_id, mission_id, org_unit_id, reference, title, status_code, status_label,
                    criticality_code, criticality_label, source, fact, criterion, cause, consequence) VALUES
 ('11110000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'f0000000-0000-0000-0000-0000000000a1',
  'b0000000-0000-0000-0000-000000000002', 'CST-A001-01', 'Cumul saisie/validation des virements',
  'OUVERT', 'Ouvert', 'HIGH', 'Élevée', 'CCM',
  'U003 a saisi et validé 3 virements.', 'Double validation obligatoire (§4.2).',
  'Cumul des habilitations saisie + validation.', 'Risque de virement frauduleux non détecté.');
INSERT INTO finding(id, tenant_id, mission_id, org_unit_id, reference, title, status_code, status_label,
                    criticality_code, criticality_label, source) VALUES
 ('11110000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', NULL,
  'b0000000-0000-0000-0000-000000000009', 'CST-B002-01', 'Constat hors périmètre',
  'OUVERT', 'Ouvert', 'LOW', 'Faible', 'MANUAL');
