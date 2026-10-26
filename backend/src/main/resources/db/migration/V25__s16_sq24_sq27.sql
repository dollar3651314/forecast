-- forecast S16：Amos 2026-10-26 对 SQ-24、SQ-27 的答复（"SQ 全部按建议"；开发记录/S16-等候页和机器人.md 第 2 节）。
-- 原编号 V40（S16 和 S15 同时开发时为避开 S15 的 V23 起编号）；整合 4 时从 V40～V42 改成 V25～V27（只改文件名和这段注释，内容不变；当时还没有在任何环境执行过），和 S15 的 V23、V24 接成 V1～V27 连续。
--
-- 1. SQ-24："尾盘大额"告警加单笔最低金额 risk.lateBet.minAmount，默认 100 USDT（参数设置页"风控"一组，betting_params:edit；0 = 不限）。
--    人少的时候截止前几十 USDT 的小注就占奖池的 30%，原来每笔都告警。
-- 2. SQ-27：注册成功后系统自动登录，也算"登录过"（开赛提醒 #23 的订阅资格，SQ-20）。注册时已经用验证码验证过邮箱，自动登录就是一次成功登录。
--    以后注册时就记登录时间（account/auth/AuthService.register）；已经注册、还没用邮箱密码登录过的客户按同一口径补上：
--    有登录会话（注册时的自动登录建的）就把 last_login_at 补成第一个会话的时间。没有任何会话的（不是从注册接口来的）不补。

INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('risk.lateBet.minAmount', '100', NULL);

UPDATE `account_user` u
  JOIN (SELECT `user_id`, MIN(`created_at`) AS `t` FROM `account_session` GROUP BY `user_id`) s ON s.`user_id` = u.`id`
SET u.`last_login_at` = s.`t`
WHERE u.`last_login_at` IS NULL;
