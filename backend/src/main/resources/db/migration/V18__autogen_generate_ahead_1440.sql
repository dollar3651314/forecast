-- F-I2-1（整合 2 时 PM 定的结论，以已审核的 AC-80"接下来 24 小时内的场次都已生成"为准）：
-- 默认加密自动生成模板（BTC 1 小时、每天 13:00 UTC）的"提前多久生成"从 60 分钟改成 1440 分钟。
-- 只改仍是原始默认值 60 的那一行：运营在后台改过的不覆盖（启用中的模板在后台改不了，这里同样只在关闭时改，免得绕过"启用前检查"的约定）。
UPDATE `market_autogen`
SET `generate_ahead_min` = 1440, `version` = `version` + 1, `updated_at` = UTC_TIMESTAMP(3)
WHERE `public_no` = 1000000000000000002 AND `generate_ahead_min` = 60 AND `enabled` = 0;
