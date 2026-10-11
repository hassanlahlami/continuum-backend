-- Jeu de test SoD statique (§2.2) — Oracle (D4). Parité avec iam-sod-seed.sql : l'utilisateur aa
-- détient write+validate+review (position de portée TENANT) ; règle STATIC/BLOCK write/validate.
-- UUID en RAW(16) via HEXTORAW ; booléens NUMBER(1).

INSERT INTO platform.tenant_module(tenant_id, module, enabled) VALUES (HEXTORAW('00000000000000000000000000000001'), 'FINDING', 1);

INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES (HEXTORAW('A0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'DIR', '{"fr":"Direction"}', 1);
INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label) VALUES (HEXTORAW('B0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('A0000000000000000000000000000001'), NULL, 'ROOT', '{"fr":"Racine"}');

INSERT INTO role(id, tenant_id, code, label, kind) VALUES (HEXTORAW('C0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'CUMUL', '{"fr":"Cumul"}', 'ORGANISATIONAL');
INSERT INTO role_feature(role_id, feature_code) VALUES (HEXTORAW('C0000000000000000000000000000001'), 'finding.write');
INSERT INTO role_feature(role_id, feature_code) VALUES (HEXTORAW('C0000000000000000000000000000001'), 'finding.validate');
INSERT INTO role_feature(role_id, feature_code) VALUES (HEXTORAW('C0000000000000000000000000000001'), 'finding.review');

INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES (HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'POLYVALENT', '{"fr":"Polyvalent"}', 20, 'TENANT');
INSERT INTO position_role(position_id, role_id) VALUES (HEXTORAW('D0000000000000000000000000000001'), HEXTORAW('C0000000000000000000000000000001'));
INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES (HEXTORAW('E0000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), HEXTORAW('000000000000000000000000000000AA'), HEXTORAW('D0000000000000000000000000000001'), NULL, 'HOLDER', CURRENT_DATE);

INSERT INTO sod_rule(id, tenant_id, feature_a, feature_b, type, severity) VALUES (HEXTORAW('90000000000000000000000000000001'), HEXTORAW('00000000000000000000000000000001'), 'finding.write', 'finding.validate', 'STATIC', 'BLOCK');
INSERT INTO sod_rule(id, tenant_id, feature_a, feature_b, type, severity) VALUES (HEXTORAW('90000000000000000000000000000002'), HEXTORAW('00000000000000000000000000000001'), 'finding.write', 'finding.review', 'STATIC', 'WARN');
INSERT INTO sod_rule(id, tenant_id, feature_a, feature_b, type, severity) VALUES (HEXTORAW('90000000000000000000000000000003'), HEXTORAW('00000000000000000000000000000001'), 'finding.validate', 'finding.review', 'DYNAMIC', 'BLOCK');
