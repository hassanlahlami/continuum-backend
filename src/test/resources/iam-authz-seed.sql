-- Jeu de test du moteur d'autorisation (§2.1.6).
-- Tables d'entité en schéma courant (public) ; catalogue partagé en platform.*.

-- Module MISSION activé pour le tenant.
INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'MISSION', true);

-- Type d'unité + arbre : root > dir_audit > div_fin ; branche sœur root > div_it.
INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES
 ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DIR', '{"fr":"Direction"}', 1);

INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES
 ('b0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL,                                     'ROOT',      '{"fr":"Racine"}',          'root'),
 ('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', 'DIR_AUDIT', '{"fr":"Audit"}',           'root.dir_audit'),
 ('b0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'DIV_FIN',   '{"fr":"Audit financier"}', 'root.dir_audit.div_fin'),
 ('b0000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', 'DIV_IT',    '{"fr":"DSI"}',             'root.div_it');

-- Rôle portant une fonctionnalité réelle du catalogue.
INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'APPROBATEUR', '{"fr":"Approbateur"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000001', 'mission.order.approve');

-- Position à portée UNIT_TREE, affectée à dir_audit.
INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES
 ('d0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'CHEF_DIR', '{"fr":"Chef de direction"}', 20, 'UNIT_TREE');
INSERT INTO position_role(position_id, role_id) VALUES
 ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001');
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES
 ('e0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000aa',
  'd0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE);

-- Portée mission (§2.1.1) : rôle de mission portant finding.write, membre sur MISSION1.
INSERT INTO platform.tenant_module(tenant_id, module, enabled)
VALUES ('00000000-0000-0000-0000-000000000001', 'FINDING', true);
INSERT INTO role(id, tenant_id, code, label, kind) VALUES
 ('c0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'CHEF_MISSION', '{"fr":"Chef de mission"}', 'MISSION');
INSERT INTO role_feature(role_id, feature_code) VALUES
 ('c0000000-0000-0000-0000-000000000002', 'finding.write');
INSERT INTO mission_member(mission_id, user_id, role_id, valid_from) VALUES
 ('f0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000aa', 'c0000000-0000-0000-0000-000000000002', CURRENT_DATE);
