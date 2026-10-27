-- forecast S12b：告警邮件要能控制是否发送（Amos 2026-10-27："告警邮件，我可以在什么地方控制 是否发送，否则在测试阶段我收到很多 没有的邮件，不想这样"；
-- 开发记录/S12b-告警邮件控制.md）。
--
-- 1. 参数设置页"告警邮件"一组（权限点 alert_params:edit，高危；超管自动拥有，SQ-19；权限点不存表，见 admin/security/Permission.java）：
--    alert.mail.enabled 总开关，默认 true（关掉以后只写日志、不发监控告警邮件；健康检查 /api/health 的 alerts 不受影响）；
--    alert.mail.muted 不发邮件的告警项（健康检查 alerts 里的名字，逗号分隔），默认空。
-- 2. 新的员工邮件模板 OPS_STILL_DOWN："仍未恢复"提醒。一次故障只发一封告警，一直没恢复时每 forecast.alert.remind-every（默认 24 小时）提醒一次。
--    员工模板只有中英文（V17）；和 OPS_ALERT 一样按 FC_ALERT_EMAILS 直接发，staff_perms 为空。
-- 3. OPS_ALERT 正文里"同一项 30 分钟内不重复发送"改成新的规则。只改仍等于原始默认文字的（在后台改过的不覆盖，同 V8、V17 的做法）。

INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('alert.mail.enabled', 'true', NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('alert.mail.muted', '', NULL);

INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('OPS_STILL_DOWN','STAFF',NULL,'监控告警仍未恢复','Monitoring alert still active','仍未恢复：{itemZh}','Still down: {itemEn}','{itemZh}仍未恢复。\n说明：{detail}\n开始时间（UTC）：{since}\n一直没恢复时每 {hours} 小时提醒一次，恢复后会再发一封邮件。不想收这一项的邮件：后台"参数设置 → 告警邮件"。当前状态见健康检查 /api/health 的 alerts。','{itemEn} is still down.\nDetail: {detail}\nSince (UTC): {since}\nThis reminder repeats every {hours} hours until it recovers; a recovery email will follow. To stop emails for this item: Parameters → Alert emails in the admin console. Current state: alerts in /api/health.','itemZh,itemEn,detail,since,hours','EMAIL',1,1);

UPDATE `notify_template`
SET `body_zh` = '{itemZh}。\n说明：{detail}\n开始时间（UTC）：{since}\n恢复后会再发一封邮件；一直没恢复时每 24 小时提醒一次（同一项反复断开时 30 分钟内不重复发）。不想收这一项的邮件：后台"参数设置 → 告警邮件"。当前状态见健康检查 /api/health 的 alerts。'
WHERE `code` = 'OPS_ALERT' AND `body_zh` = '{itemZh}。\n说明：{detail}\n开始时间（UTC）：{since}\n恢复后会再发一封邮件；同一项 30 分钟内不重复发送。当前状态见健康检查 /api/health 的 alerts。';

UPDATE `notify_template`
SET `body_en` = '{itemEn}.\nDetail: {detail}\nSince (UTC): {since}\nA recovery email will follow; while it stays down a reminder is sent every 24 hours (repeated flapping is not re-sent within 30 minutes). To stop emails for this item: Parameters → Alert emails in the admin console. Current state: alerts in /api/health.'
WHERE `code` = 'OPS_ALERT' AND `body_en` = '{itemEn}.\nDetail: {detail}\nSince (UTC): {since}\nA recovery email will follow. The same alert is not repeated within 30 minutes. Current state: alerts in /api/health.';
