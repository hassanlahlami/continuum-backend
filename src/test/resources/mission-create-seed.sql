-- Jeu de test MIS-02 : création de mission. aa a mission.create/read + universe.read/manage sur l'unité IG
-- (portée UNIT) ; cc n'a aucune affectation. L'unité OTHER est hors de la portée de aa.
INSERT INTO platform.tenant_module(tenant_id, module, enabled) VALUES
 ('00000000-0000-0000-0000-000000000001', 'MISSION', true),
 ('00000000-0000-0000-0000-000000000001', 'UNIVERSE', true);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'IG',    '{"fr":"Inspection Générale"}', 'ig'),
 ('b0000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'OTHER', '{"fr":"Autre direction"}',     'other');

INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'PLANIFICATEUR', '{"fr":"Planificateur"}', 'ORGANISATIONAL'),
 ('c0000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001', 'AUDITEUR', '{"fr":"Auditeur"}', 'MISSION');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'mission.read'),
 ('c0000000-0000-0000-0000-000000000001', 'mission.create'),
 ('c0000000-0000-0000-0000-000000000001', 'universe.read'),
 ('c0000000-0000-0000-0000-000000000001', 'universe.manage');
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'PLANIF', '{"fr":"Planificateur"}', 30, 'UNIT');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000aa',
  'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE);

INSERT INTO mission_type(tenant_id, code, label, process_key, default_duration_days, active) VALUES
 ('00000000-0000-0000-0000-000000000001', 'INSPECTION', 'Inspection', 'mission-inspection', 15, TRUE),
 ('00000000-0000-0000-0000-000000000001', 'DORMANT', 'Type désactivé', NULL, NULL, FALSE);
