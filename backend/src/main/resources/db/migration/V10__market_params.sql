-- forecast S3：第一期参数的初始值（需求说明书第 6.2 节"参数设置"；技术方案/v1-架构方案.md 第 3.3、3.4 节原计划的 V11__params，S3 起顺延编号为 V10）
-- 和费率修改通知 #22 的模板。键的定义、取值范围和权限点在 common/config/ConfigKey.java（Params.FEE → fee:edit，Params.BETTING → betting_params:edit）。
-- 单笔最小提现 withdraw.minAmount 已在 V1 / V7（默认 100），S3 只是把它从系统配置页搬到参数设置页（代码里改，不动数据）。
-- 争议处理、提议、确认的超时时限在 S7 用到时再加（开发记录/S3-市场.md"遗留"）。

-- 品类默认费率：盈利部分的 5%（RQ-4），存百分比
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('market.fee.FOOTBALL','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('market.fee.NBA','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('market.fee.CRYPTO_15M','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('market.fee.CRYPTO_1H','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('market.fee.MACRO','5',NULL);
-- 下注限额（需求第 3.3 节）
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('bet.minAmount','1',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('bet.maxAmount','5000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('bet.userCap.FOOTBALL','10000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('bet.userCap.NBA','10000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('bet.userCap.CRYPTO_15M','2000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('bet.userCap.CRYPTO_1H','2000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('bet.userCap.MACRO','10000',NULL);
-- 加密市场：窗口开始前 30 秒截止；15 分钟提前 60 分钟、1 小时提前 3 小时开放（第 3.7 节、RQ-13）
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('crypto.closeLeadSeconds','30',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('crypto.openLeadMinutes.CRYPTO_15M','60',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('crypto.openLeadMinutes.CRYPTO_1H','180',NULL);
-- 争议窗口（平台裁决的市场，RQ-9）：体育 2 小时、时事 24 小时
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('dispute.windowMinutes.FOOTBALL','120',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('dispute.windowMinutes.NBA','120',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('dispute.windowMinutes.MACRO','1440',NULL);
-- KYC 阈值：累计充值 > 1,000 USDT 需要 L1（RQ-10）
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('kyc.depositThreshold','1000',NULL);

-- 通知 #22：我有下注的市场修改了费率（需求第 7 节、第 3.8 节第 4 条、AC-37）。只发站内信。
-- 文案是研发起草的初稿，PM 在 S9 定稿（和其他 21 个客户模板一起，含繁体中文）。market 是简体题目、marketEn 是英文题目，old / new 是百分比，at 是 UTC 时间。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('FEE_CHANGED','USER','system','市场费率修改','Market fee changed','你下注的市场调整了抽成费率','The fee of a market you bet on has changed','你有下注的市场「{market}」在 {at} 把平台抽成费率从 {old} 调整为 {new}，结算时按新费率计算。修改记录可以在市场详情的规则区查看。','The platform fee of "{marketEn}", a market you bet on, was changed from {old} to {new} at {at}. Settlement will use the new rate. The change history is shown in the rules section of the market.','market,marketEn,old,new,at','INBOX',1,1);
