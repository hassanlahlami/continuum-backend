-- Jeu de test : lecture des missions scindée par périmètre (§2.1.6, mission.read).
-- L'utilisateur aa a mission.read en portée UNIT_TREE sur IG. Une mission est sur IG (visible),
-- une autre sur une branche sœur OTHER (non visible).

INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'MISSION', true);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'IG',    '{"fr":"Inspection Générale"}', 'ig'),
 ('b0000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'OTHER', '{"fr":"Autre direction"}',     'other');

INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'LECTEUR_MISSION', '{"fr":"Lecteur"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'mission.read');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'LECTEUR', '{"fr":"Lecteur"}', 20, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000aa',
  'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE);

INSERT INTO mission(id, tenant_id, org_unit_id, reference, title, mission_type_code, mission_type_label,
                    status_code, status_label, phase_code, phase_label, progress_percent) VALUES
 ('f0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002',
  'MIS-A001', 'Inspection agence A001', 'INSPECTION', 'Inspection', 'EN_COURS', 'En cours', 'REALISATION', 'Réalisation', 40),
 ('f0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000009',
  'MIS-B002', 'Audit hors périmètre', 'AUDIT', 'Audit', 'PLANIFIEE', 'Planifiée', 'PREPARATION', 'Préparation', 0);
