-- S2：单笔提现最小金额默认值 10 → 100 USDT（需求说明书 RQ-16、第 3.11 节、AC-61；Amos 2026-10-24 原话"默认100，让我可以在后台调整"）。
-- 基线 V1 沿用了复制源的 10（开发记录/S1-复制骨架.md 第 2.4 节）。只改从没被后台改过的值（updated_by 为空且仍是 10），
-- 管理员已经改过的不覆盖。保存时"必须大于每条链的提现手续费"的校验在 admin.system.SystemService（ConfigKey.WITHDRAW_MIN_AMOUNT）。
UPDATE `common_config` SET `value` = '100' WHERE `config_key` = 'withdraw.minAmount' AND `value` = '10' AND `updated_by` IS NULL;
