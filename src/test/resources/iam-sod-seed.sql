-- Jeu de test SoD statique (§2.2). Tables d'entité en public, catalogue en platform.*.

INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'FINDING', true);

-- Une unité racine (position de portée TENANT : l'arbre importe peu ici).
INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'ROOT', '{"fr":"Racine"}', 'root');

-- Un rôle cumulant trois fonctionnalités.
INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'CUMUL', '{"fr":"Cumul"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'finding.write'),
 ('c0000000-0000-0000-0000-000000000001', 'finding.validate'),
 ('c0000000-0000-0000-0000-000000000001', 'finding.review');

-- Position de portée TENANT + affectation de l'utilisateur.
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'POLYVALENT', '{"fr":"Polyvalent"}', 20, 'TENANT');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000aa',
  'd0000000-0000-0000-0000-000000000001', NULL, 'HOLDER', CURRENT_DATE);

-- Règles SoD : 2 statiques (BLOCK, WARN) + 1 dynamique (doit être ignorée par le contrôle statique).
INSERT INTO sod_rule(id, tenant_id, feature_a, feature_b, type, severity) VALUES
 ('90000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'finding.write',    'finding.validate', 'STATIC',  'BLOCK'),
 ('90000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'finding.write',    'finding.review',   'STATIC',  'WARN'),
 ('90000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'finding.validate', 'finding.review',   'DYNAMIC', 'BLOCK');
