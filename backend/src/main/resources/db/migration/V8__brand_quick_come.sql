-- S2：通知模板里的占位品牌名 Forecast → Quick Come（Amos 2026-10-25：上线前沿用 Quick Come 名称和 quickcome.online 子域名，
-- 正式上线前改为独立品牌和域名；需求说明书 v1.2、架构方案 v1.1 第 15 节）。
-- 模板是运营可改的数据（项目地图约定 17）：只改仍等于 V3 原始文字的，后台改过的不动。前端的品牌名在 web/*/src/lib/brand.ts。
UPDATE `notify_template` SET `title_zh` = 'Quick Come 验证码' WHERE `code` = 'EMAIL_CODE' AND `title_zh` = 'Forecast 验证码';
UPDATE `notify_template` SET `title_en` = 'Quick Come verification code' WHERE `code` = 'EMAIL_CODE' AND `title_en` = 'Forecast verification code';
UPDATE `notify_template` SET `title_zh` = '欢迎来到 Quick Come' WHERE `code` = 'WELCOME' AND `title_zh` = '欢迎来到 Forecast';
UPDATE `notify_template` SET `title_en` = 'Welcome to Quick Come' WHERE `code` = 'WELCOME' AND `title_en` = 'Welcome to Forecast';
