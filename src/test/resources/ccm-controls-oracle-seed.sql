-- Jeu de test Oracle (CCM-03, D4) : paramétrage de 3 contrôles pour un tenant. RAW(16) via HEXTORAW ;
-- enabled NUMBER(1) ; params JSON (VARCHAR2 IS JSON). SOD-VIREMENTS et SEUIL-HORAIRE activés, DORMANTS non.
INSERT INTO ccm_control (tenant_id, control_code, enabled, params) VALUES (HEXTORAW('00000000000000000000000000000001'), 'SOD-VIREMENTS', 1, '{"makerFunction":"SAISIE_VIREMENT","checkerFunction":"VALIDATION_VIREMENT"}');
INSERT INTO ccm_control (tenant_id, control_code, enabled, params) VALUES (HEXTORAW('00000000000000000000000000000001'), 'SEUIL-HORAIRE', 1, '{"thresholdAmount":1000000,"excludeChannels":["BATCH"]}');
INSERT INTO ccm_control (tenant_id, control_code, enabled, params) VALUES (HEXTORAW('00000000000000000000000000000001'), 'COMPTES-DORMANTS', 0, '{"dormancyMonths":12,"largeOutflowAmount":100000}');
