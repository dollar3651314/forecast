-- forecast S14：分享卡片（需求说明书 v1.3.2 第 1.5 节 MF-5；第 5.2 节 P3、P4 和"分享卡片和挑战卡"；第 6.3 节；AC-86、AC-87；开发记录/S14-分享卡片.md）。
-- V1～V21 已合并，不再修改；新项目没有已有数据（需求第 14 节）。
--
-- market_share：客户自己生成的两种卡片的分享凭证（随机串，链接和图片地址里用它，不带客户的任何信息）：
--   CHALLENGE 挑战卡（下注成功后"我押了 X，谁敢押 Y？"）：生成时核对这个客户确实押了这个选项（有有效持仓），卡片上只有选项和两边的公开数字；
--   WIN 开奖卡（已结算的赢单）：生成时核对押中了；show_amount = 1（客户选择"显示金额"）时把当时的派彩记在 amount，卡片上才显示，默认不显示（AC-86）。
-- 市场卡、开奖记录卡、封盘倒计时卡、周报卡只用公开数据，不需要凭证。
-- 同一个客户、同一个市场、同一种卡、同一个选项、同一个"是否显示金额"只有一行（再次分享沿用同一个链接）；只增不改（MarketAppendOnlyTest）。
-- 表名以 market_ 开头，只由 market 模块读写。

CREATE TABLE `market_share` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `token` char(22) NOT NULL COMMENT '分享凭证（16 字节随机数的 base64url），公开链接 /m/{市场编号}?card=… 和图片地址里用它',
  `market_id` bigint NOT NULL,
  `user_id` bigint NOT NULL COMMENT '生成卡片的客户（只用于去重，任何公开接口都不返回）',
  `kind` varchar(12) NOT NULL COMMENT 'CHALLENGE 挑战卡 / WIN 开奖卡（赢单）',
  `option_id` bigint NOT NULL COMMENT '挑战卡：我押的选项；开奖卡：押中的选项',
  `show_amount` tinyint(1) NOT NULL DEFAULT '0' COMMENT '开奖卡：客户选择显示派彩金额（默认不显示）',
  `amount` decimal(24,8) DEFAULT NULL COMMENT '开奖卡且显示金额时：生成时本人的派彩（已扣抽成，来自结算明细）',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_market_share_token` (`token`),
  UNIQUE KEY `uk_market_share_once` (`user_id`, `market_id`, `kind`, `option_id`, `show_amount`),
  KEY `idx_market_share_market` (`market_id`),
  CONSTRAINT `ck_market_share_kind` CHECK ((`kind` in (_utf8mb4'CHALLENGE', _utf8mb4'WIN')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户生成的挑战卡、开奖卡的分享凭证（MF-5，只增不改）';
