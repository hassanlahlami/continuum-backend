-- Jeu de test : contrôle SoD statique à l'affectation (§2.1.8).
-- L'utilisateur b2 détient déjà finding.write sur ROOT ; une règle BLOCK interdit de cumuler
-- finding.write et finding.validate. Affecter la position valideuse doit donc être refusé.

INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'FINDING', true);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL, 'ROOT', '{"fr":"Racine"}', 'root');

-- Rôles mono-fonctionnalité.
INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'R_WRITE',    '{"fr":"Rédacteur"}', 'ORGANISATIONAL'),
 ('c0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'R_VALIDATE', '{"fr":"Valideur"}',  'ORGANISATIONAL'),
 ('c0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'R_READ',     '{"fr":"Lecteur"}',   'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'finding.write'),
 ('c0000000-0000-0000-0000-000000000002', 'finding.validate'),
 ('c0000000-0000-0000-0000-000000000003', 'finding.read');

-- Positions de portée UNIT sur ROOT.
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'P_WRITE',    '{"fr":"Rédacteur"}', 20, 'UNIT'),
 ('d0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'P_VALIDATE', '{"fr":"Valideur"}',  20, 'UNIT'),
 ('d0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'P_READ',     '{"fr":"Lecteur"}',   20, 'UNIT');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001'),
 ('d0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000002'),
 ('d0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000003');

-- L'utilisateur b2 détient déjà finding.write sur ROOT (affectation préexistante).
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000b2',
  'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', 'HOLDER', CURRENT_DATE);

-- Règle SoD statique bloquante : rédaction vs validation d'un constat.
INSERT INTO sod_rule(id, tenant_id, feature_a, feature_b, type, severity) VALUES
 ('90000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'finding.write', 'finding.validate', 'STATIC', 'BLOCK');
