-- forecast S18（P3 + P9，台湾第一期）：通知模板的繁体改用台湾用语（市场/v1-台湾第一期推广.md P3；对照表 市场/v1-台湾用语对照表-待校对.md）。
-- 研发用 node scripts/tw-terms.mjs --localize（OpenCC 的台湾用语表 + 自定义词表 tw-terms.txt）逐条检查了全部客户模板的繁体，再人工看过：
-- 只有 WAITLIST_CONFIRM 有要换的词（郵箱 → 電子信箱）；员工模板只有中英文（繁体空着，按简体发），不动。
-- 模板是运营可改的数据（项目地图约定 17）：只改仍等于 V26 原始文字的，后台改过的不动。
UPDATE `notify_template` SET `body_tw` = '你在 Quick Come 的資料頁留下了這個電子信箱。請打開下面的連結，點「確認訂閱」（7 天內有效）：\n{confirm}\n\n確認以後，我們只會在正式上線時給你發一封通知，不發其他郵件。\n如果不是你本人提交的，忽略這封郵件即可，不會訂閱。\n不想再收到：{unsubscribe}'
WHERE `code` = 'WAITLIST_CONFIRM'
  AND `body_tw` = '你在 Quick Come 的資料頁留下了這個郵箱。請打開下面的連結，點「確認訂閱」（7 天內有效）：\n{confirm}\n\n確認以後，我們只會在正式上線時給你發一封通知，不發其他郵件。\n如果不是你本人提交的，忽略這封郵件即可，不會訂閱。\n不想再收到：{unsubscribe}';
