-- forecast S15：分享链接的 ref 改用随机分享码（S14 的 SQ-36；开发记录/S15-自动化程序.md 第 2 节）。V1～V22 已合并，不再修改。
--
-- S14 的分享链接里 ref = 客户编号（递增的数字）：看到链接的人能大致推算注册人数，也能把几次分享对上同一个人。
-- 改成每个客户一个随机分享码：
--   - code：10 个字符，第一个是小写字母，其余是小写字母和数字（去掉容易看错的 i、l、o、0、1），由 SecureRandom 生成，唯一键兜底（撞了换一个再试）；
--     一定含字母，所以和旧链接里的纯数字客户编号区分得开（来源统计按"纯数字 + src=share"把旧链接归到那个客户，见 growth 模块）。
--   - 第一次分享时生成（没有就插入：先普通读、再 INSERT IGNORE，约定 34），之后不变；只增不改。
--   - 来源统计（account_attribution、growth_visit）照旧存链接上的原文；后台统计时把分享码换成客户编号（只在后台显示，客户端和公开接口都看不到）。
-- 表名以 account_ 开头，只由 account 模块读写。

CREATE TABLE `account_share_code` (
  `user_id` bigint NOT NULL,
  `code` varchar(16) NOT NULL COMMENT '分享码（随机，一定含字母；分享链接的 ref）',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_account_share_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户的分享码（S15，分享链接的 ref；只用于来源统计，不发任何奖励）';
