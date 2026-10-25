-- forecast S9：通知 22 类 + 后台页面（开发记录/S9-后台和通知.md）。V1～V16 不改。
-- 1. 通知模板加繁体（RQ-18：客户端三种语言）、需求第 7 节编号、员工模板的收件人权限点（SQ-14：员工提醒按角色发，告警邮箱兜底）。
-- 2. 客户模板文案定稿（PM，简体、英文、繁体）：只改仍等于原始默认文字的字段（运营在后台改过的不覆盖，同 V8 的做法）；繁体是新列，直接写。
-- 3. 新模板：#4 充值低于最小金额、#7 累计充值达到认证门槛、#18 补充说明、#23 订阅的场次提醒（ANY，按邮箱发）、员工 MARKET_SUBMITTED（草稿待上架）。
-- 4. 后台菜单（需求第 6.1 节，S9 范围内的部分）：admin_menu 写入新菜单的默认顺序，和 admin/security/Menus.java、web/admin/src/config/menu.tsx 一致。

ALTER TABLE `notify_template`
  ADD COLUMN `title_tw` varchar(200) NOT NULL DEFAULT '' COMMENT '繁体标题（S9）；为空时按简体发（员工模板只有中英文）' AFTER `body_en`,
  ADD COLUMN `body_tw` varchar(1000) NOT NULL DEFAULT '' COMMENT '繁体正文（S9）' AFTER `title_tw`,
  ADD COLUMN `req_no` varchar(8) DEFAULT NULL COMMENT '需求说明书第 7 节的编号（#1～#23），后台显示用；员工模板为空' AFTER `audience`,
  ADD COLUMN `staff_perms` varchar(200) NOT NULL DEFAULT '' COMMENT '员工模板的收件人：有其中任一权限点的在用管理员（逗号分隔）；为空或没有这样的管理员时发告警邮箱 FC_RISK_ALERT_EMAILS' AFTER `channels`;

ALTER TABLE `notify_pref` MODIFY COLUMN `lang` varchar(8) NOT NULL DEFAULT 'zh-CN' COMMENT '推送和邮件用的语言：zh-CN / zh-TW / en（S9 起有繁体）';

-- 新的客户模板（#4、#7、#18、#23）
INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('DEPOSIT_BELOW_MIN', 'USER', '#4', 'deposit', '充值低于最小金额未入账', 'Deposit below minimum not credited', '充值未入账：低于最小金额', 'Deposit not credited: below the minimum', '你通过 {chain} 转入的 {amount} USDT 低于最小充值金额 {min} USDT，这笔不会自动入账。\n需要处理请联系客服；以后每次请至少转入 {min} USDT。', 'Your transfer of {amount} USDT via {chain} is below the minimum deposit of {min} USDT and will not be credited automatically.\nPlease contact support if you need help. Send at least {min} USDT each time.', '充值未入帳：低於最小金額', '你透過 {chain} 轉入的 {amount} USDT 低於最小充值金額 {min} USDT，這筆不會自動入帳。\n需要處理請聯絡客服；以後每次請至少轉入 {min} USDT。', 'amount,chain,min', 'INBOX', '', 1, 1);
INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('KYC_REQUIRED', 'USER', '#7', 'kyc', '累计充值达到认证门槛', 'Verification required to keep betting', '完成身份认证后才能继续下注', 'Verify your identity to keep betting', '你的累计充值已达到 {total} USDT，超过 {threshold} USDT 的认证门槛。按平台规则，需要先在"我的 → 身份认证"完成认证才能继续下注；已有的下注和余额不受影响。审核一般 1 个工作日内完成。', 'Your total deposits have reached {total} USDT, above the verification threshold of {threshold} USDT. Under the platform rules, please verify your identity under Me → Identity verification before placing more bets. Your existing bets and balance are not affected. Reviews usually finish within one business day.', '完成身分認證後才能繼續下注', '你的累計充值已達到 {total} USDT，超過 {threshold} USDT 的認證門檻。按平台規則，需要先在「我的 → 身分認證」完成認證才能繼續下注；已有的下注和餘額不受影響。審核一般 1 個工作日內完成。', 'threshold,total', 'INBOX,PUSH,EMAIL', '', 1, 1);
INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('MARKET_CLARIFIED', 'USER', '#18', 'system', '市场发布补充说明', 'Market clarification', '「{market}」发布了补充说明', 'Clarification for "{marketEn}"', '你下注的市场「{market}」发布了补充说明：\n{text}\n补充说明只解释规则，不改变题目、选项和截止时间。完整内容在市场详情的规则区。', 'A clarification was published for "{marketEn}", a market you bet on:\n{textEn}\nClarifications only explain the rules; they do not change the question, the options or the close time. See the rules section of the market for details.', '「{market}」發布了補充說明', '你下注的市場「{market}」發布了補充說明：\n{text}\n補充說明只解釋規則，不改變題目、選項和截止時間。完整內容在市場詳情的規則區。', 'market,marketEn,text,textEn', 'INBOX', '', 1, 1);
INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('MATCH_REMINDER', 'ANY', '#23', NULL, '订阅的场次开场提醒', 'Subscribed match reminder', '场次提醒：{match}', 'Match reminder: {match}', '你订阅的场次「{match}」：\n开放下注：{openAt}\n截止下注：{closeAt}\n查看场次：{link}\n\n你收到这封邮件，是因为订阅了这个场次的提醒。不想再收到：{unsubscribe}', 'The match you subscribed to, "{match}":\nBetting opens: {openAt}\nBetting closes: {closeAt}\nView: {link}\n\nYou are receiving this email because you subscribed to reminders for this match. Unsubscribe: {unsubscribe}', '場次提醒：{match}', '你訂閱的場次「{match}」：\n開放下注：{openAt}\n截止下注：{closeAt}\n查看場次：{link}\n\n你收到這封郵件，是因為訂閱了這個場次的提醒。不想再收到：{unsubscribe}', 'match,openAt,closeAt,link,unsubscribe', 'EMAIL', '', 1, 1);

-- 新的员工模板
INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('MARKET_SUBMITTED', 'STAFF', NULL, NULL, '草稿待上架', 'Draft awaiting listing', '市场 {marketNo} 已提交上架，待检查', 'Market {marketNo} submitted for listing', '市场 {marketNo}（{market}，{category}）已提交上架，等待上架检查。\n请到后台"市场管理 → 待上架"按清单检查后上架或退回（退回要写理由）。', 'Market {marketNo} ({marketEn}, {category}) has been submitted for listing and is waiting for the listing check.\nPlease check it against the checklist in the admin console (Markets → Awaiting listing) and list it or return it with a reason.', '', '', 'marketNo,market,marketEn,category', 'EMAIL', 'market:publish', 1, 1);

-- 已有客户模板：需求编号、繁体；简体和英文只改仍是原始默认文字的
-- #1 WELCOME
UPDATE `notify_template` SET `req_no` = '#1', `title_tw` = '歡迎來到 Quick Come', `body_tw` = '在這裡，你可以押一件事的結果：足球、NBA、加密貨幣漲跌、經濟時事。\n1. 怎麼算：每個選項有自己的獎池。開獎後，輸的選項的錢按押注比例分給押中的人；平台只對贏家的盈利部分抽成（預設 5%），沒押中不收費。下注後不能撤銷。\n2. 身分認證：累計充值超過認證門檻、以及提現前，需要在「我的 → 身分認證」完成認證，一般 1 個工作日內審核完。\n3. 充值：在「資產 → 充值」選網路，複製你的專屬地址，從交易所或錢包轉入 USDT。\n隨時可以在「我的 → 新手引導」重新查看。' WHERE `code` = 'WELCOME';
UPDATE `notify_template` SET `body_zh` = '在这里，你可以押一件事的结果：足球、NBA、加密货币涨跌、经济时事。\n1. 怎么算：每个选项有自己的奖池。开奖后，输的选项的钱按押注比例分给押中的人；平台只对赢家的盈利部分抽成（默认 5%），没押中不收费。下注后不能撤销。\n2. 身份认证：累计充值超过认证门槛、以及提现前，需要在"我的 → 身份认证"完成认证，一般 1 个工作日内审核完。\n3. 充值：在"资产 → 充值"选网络，复制你的专属地址，从交易所或钱包转入 USDT。\n随时可以在"我的 → 新手引导"重新查看。' WHERE `code` = 'WELCOME' AND `body_zh` = '开始使用前：\n1. 充值 USDT：在"资产 → 充值"选择网络，复制你的专属地址，从交易所或钱包转入 USDT。\n2. 完成身份认证：在"我的 → 身份认证"上传证件照片，一般 1 个工作日内审核完，结果会发到站内信和邮箱。提现前必须完成身份认证。\n随时可以在"我的 → 新手引导"重新查看。';
UPDATE `notify_template` SET `body_en` = 'Here you can bet on how things turn out: football, NBA, crypto up/down and macro news.\n1. How it works: each option has its own pool. When the result is in, money on the losing options is shared among the winners by stake. The platform only takes a fee from winners\' profit (5% by default); there is no fee if you lose. Bets cannot be cancelled.\n2. Identity verification: once your total deposits pass the verification threshold, and before any withdrawal, verify your identity under Me → Identity verification. Reviews usually finish within one business day.\n3. Deposit: go to Assets → Deposit, choose a network, copy your own address and send USDT from an exchange or wallet.\nYou can open the guide again from Me → Getting started.' WHERE `code` = 'WELCOME' AND `body_en` = 'Before you start:\n1. Deposit USDT: go to Assets → Deposit, choose a network, copy your own address and send USDT from an exchange or wallet.\n2. Verify your identity: upload photos of your ID under Me → Identity verification. Reviews usually finish within one business day; the result is sent to your inbox and email. Withdrawals require identity verification.\nYou can open the guide again from Me → Getting started.';
-- #2 EMAIL_CODE
UPDATE `notify_template` SET `req_no` = '#2', `title_tw` = 'Quick Come 驗證碼', `body_tw` = '你的驗證碼是 {code}，10 分鐘內有效。請不要把驗證碼告訴任何人，我們的工作人員不會向你索取。' WHERE `code` = 'EMAIL_CODE';
UPDATE `notify_template` SET `body_zh` = '你的验证码是 {code}，10 分钟内有效。请不要把验证码告诉任何人，我们的工作人员不会向你索要。' WHERE `code` = 'EMAIL_CODE' AND `body_zh` = '您的验证码是 {code}，10 分钟内有效。请勿告诉任何人。';
UPDATE `notify_template` SET `body_en` = 'Your code is {code}. It expires in 10 minutes. Do not share it with anyone; our staff will never ask for it.' WHERE `code` = 'EMAIL_CODE' AND `body_en` = 'Your code is {code}. It expires in 10 minutes. Do not share it with anyone.';
-- #3 DEPOSIT_CREDITED
UPDATE `notify_template` SET `req_no` = '#3', `title_tw` = '充值已到帳', `body_tw` = '你透過 {chain} 充值的 {amount} USDT 已到帳，已計入可用餘額。' WHERE `code` = 'DEPOSIT_CREDITED';
UPDATE `notify_template` SET `body_zh` = '你通过 {chain} 充值的 {amount} USDT 已到账，已计入可用余额。' WHERE `code` = 'DEPOSIT_CREDITED' AND `body_zh` = '您通过 {chain} 充值的 {amount} USDT 已到账。';
UPDATE `notify_template` SET `body_en` = 'Your deposit of {amount} USDT via {chain} has been credited to your available balance.' WHERE `code` = 'DEPOSIT_CREDITED' AND `body_en` = 'Your deposit of {amount} USDT via {chain} has been credited.';
-- #5 WITHDRAW_STATUS
UPDATE `notify_template` SET `req_no` = '#5', `title_tw` = '提現狀態更新', `body_tw` = '你的提現 {orderId}（{amount} USDT）{status}。\n原因：{reason}' WHERE `code` = 'WITHDRAW_STATUS';
UPDATE `notify_template` SET `body_zh` = '你的提现 {orderId}（{amount} USDT）{status}。\n原因：{reason}' WHERE `code` = 'WITHDRAW_STATUS' AND `body_zh` = '您的提现 {orderId}（{amount} USDT）{status}。\n原因：{reason}';
-- #6 KYC_APPROVED
UPDATE `notify_template` SET `req_no` = '#6', `title_tw` = '身分認證已通過', `body_tw` = '你的身分認證已通過，現在可以提現，下注也不再受累計充值的認證門檻限制。' WHERE `code` = 'KYC_APPROVED';
UPDATE `notify_template` SET `body_zh` = '你的身份认证已通过，现在可以提现，下注也不再受累计充值的认证门槛限制。' WHERE `code` = 'KYC_APPROVED' AND `body_zh` = '您的身份认证已通过，提现已开通。';
UPDATE `notify_template` SET `body_en` = 'Your identity has been verified. You can now withdraw, and the verification threshold on total deposits no longer limits your bets.' WHERE `code` = 'KYC_APPROVED' AND `body_en` = 'Your identity has been verified. Withdrawals are now available.';
-- #6 KYC_REJECTED
UPDATE `notify_template` SET `req_no` = '#6', `title_tw` = '身分認證未通過', `body_tw` = '你的身分認證未通過，請到「我的 → 身分認證」按原因修改後重新提交。\n駁回原因：{reason}' WHERE `code` = 'KYC_REJECTED';
UPDATE `notify_template` SET `body_zh` = '你的身份认证未通过，请到"我的 → 身份认证"按原因修改后重新提交。\n驳回原因：{reason}' WHERE `code` = 'KYC_REJECTED' AND `body_zh` = '您的身份认证未通过，请到"身份认证"页面按原因修改后重新提交。\n驳回原因：{reason}';
UPDATE `notify_template` SET `body_en` = 'Your identity verification was not approved. Please fix the issue under Me → Identity verification and submit again.\nReason: {reason}' WHERE `code` = 'KYC_REJECTED' AND `body_en` = 'Your identity verification was not approved. Please fix the issue on the Identity verification page and submit again.\nReason: {reason}';
-- #8 BET_PLACED
UPDATE `notify_template` SET `req_no` = '#8', `title_tw` = '下注成功', `body_tw` = '你已在「{market}」押「{option}」{amount} USDT（下注編號 {betNo}）。下注後不能撤銷；賠率隨獎池變化，最終賠率在截止時確定。' WHERE `code` = 'BET_PLACED';
-- #10 RESULT_PUBLICIZED
UPDATE `notify_template` SET `req_no` = '#10', `title_tw` = '「{market}」公示結果：{option}', `body_tw` = '你下注的市場「{market}」擬定結果是「{option}」，正在公示。\n如果你認為結果不對，可以在 {deadline} 之前到市場詳情提交爭議（每人一次，寫明理由）。公示期滿沒有爭議就按這個結果結算。' WHERE `code` = 'RESULT_PUBLICIZED';
-- #11 SETTLE_WON
UPDATE `notify_template` SET `req_no` = '#11', `title_tw` = '「{market}」已結算：你押中了', `body_tw` = '「{market}」結果是「{option}」。你的派彩 {payout} USDT 已到可用餘額（本金 {stake} + 盈利 {gross} − 抽成 {fee}，抽成為盈利的 {feeRate}%）。' WHERE `code` = 'SETTLE_WON';
UPDATE `notify_template` SET `title_zh` = '「{market}」已结算：你押中了' WHERE `code` = 'SETTLE_WON' AND `title_zh` = '「{market}」已结算：你赢了';
UPDATE `notify_template` SET `title_en` = '"{marketEn}" settled: your option won' WHERE `code` = 'SETTLE_WON' AND `title_en` = '"{marketEn}" settled: you won';
-- #12 SETTLE_LOST
UPDATE `notify_template` SET `req_no` = '#12', `title_tw` = '「{market}」已結算', `body_tw` = '「{market}」結果是「{option}」，你押的選項沒有押中，本金 {stake} USDT 進入了押中一方的獎池。' WHERE `code` = 'SETTLE_LOST';
UPDATE `notify_template` SET `body_zh` = '「{market}」结果是「{option}」，你押的选项没有押中，本金 {stake} USDT 进入了押中一方的奖池。' WHERE `code` = 'SETTLE_LOST' AND `body_zh` = '「{market}」结果是「{option}」，你押的选项没有赢，本金 {stake} USDT 进入了赢方的奖池。';
-- #13 MARKET_REFUNDED
UPDATE `notify_template` SET `req_no` = '#13', `title_tw` = '「{market}」{refundKind}，下注已全額退回', `body_tw` = '「{market}」{refundKind}，你在這個市場的下注 {amount} USDT 已全額退回到可用餘額，不收任何費用。\n原因：{refundReason}' WHERE `code` = 'MARKET_REFUNDED';
-- #14 LATE_BET_REFUNDED
UPDATE `notify_template` SET `req_no` = '#14', `title_tw` = '「{market}」有下注已退回', `body_tw` = '「{market}」的實際開賽（或截止）時間是 {at}，你在這之後下的 {amount} USDT 不參與結算，已全額退回到可用餘額。' WHERE `code` = 'LATE_BET_REFUNDED';
-- #15 DISPUTE_DECIDED
UPDATE `notify_template` SET `req_no` = '#15', `title_tw` = '你對「{market}」的爭議有結論了', `body_tw` = '你對「{market}」提交的爭議已處理：{decision}。\n最終結果：{option}\n結論和理由：{note}' WHERE `code` = 'DISPUTE_DECIDED';
-- #16 RESULT_OVERTURNED
UPDATE `notify_template` SET `req_no` = '#16', `title_tw` = '「{market}」爭議後改判為「{option}」', `body_tw` = '你下注的市場「{market}」公示的結果「{oldOption}」經爭議處理後改判為「{option}」，將按新結果結算，結算後另有通知。' WHERE `code` = 'RESULT_OVERTURNED';
-- #17 COMPENSATION_PAID
UPDATE `notify_template` SET `req_no` = '#17', `title_tw` = '「{market}」的補償 {amount} USDT 已到帳', `body_tw` = '我們發現「{market}」結算時的結果有誤，正確結果應為「{option}」。平台已按正確結果補償你 {amount} USDT，已到可用餘額。已經派出的錢不會追回。' WHERE `code` = 'COMPENSATION_PAID';
-- #22 FEE_CHANGED
UPDATE `notify_template` SET `req_no` = '#22', `title_tw` = '你下注的市場調整了抽成費率', `body_tw` = '你有下注的市場「{market}」在 {at} 把平台抽成費率從 {old} 調整為 {new}，結算時按新費率計算。修改記錄可以在市場詳情的規則區查看。' WHERE `code` = 'FEE_CHANGED';

-- 员工模板按角色发（SQ-14）；没有列在这里的（复用：热钱包余额、冲正挂账、入金对账、归集、提现卡住、监控告警、自动审批总开关）照旧发告警邮箱
UPDATE `notify_template` SET `staff_perms` = 'resolve:confirm' WHERE `code` = 'RESOLVE_PROPOSED';
UPDATE `notify_template` SET `staff_perms` = 'resolve:propose,resolve:confirm,dispute:handle' WHERE `code` = 'RESOLVE_OVERDUE';
UPDATE `notify_template` SET `staff_perms` = 'resolve:propose' WHERE `code` = 'RESOLVE_AUTO_FAILED';
UPDATE `notify_template` SET `staff_perms` = 'resolve:propose' WHERE `code` = 'SPORTS_AUTO_FAILED';
UPDATE `notify_template` SET `staff_perms` = 'dispute:handle,dispute:view' WHERE `code` = 'DISPUTE_NEW';
UPDATE `notify_template` SET `staff_perms` = 'pool_recon:view' WHERE `code` = 'SETTLE_ABNORMAL';
UPDATE `notify_template` SET `staff_perms` = 'pool_recon:view' WHERE `code` = 'POOL_RECON_DIFF';
UPDATE `notify_template` SET `staff_perms` = 'market:edit' WHERE `code` = 'SPORTS_KICKOFF_CHANGED';

-- 后台菜单（需求第 6.1 节；"内容中心""来源统计"不在 S9，风控告警在 S10）。admin_menu 只存顺序和是否显示，菜单结构在 Menus.java。
-- INSERT IGNORE：后台已经调过顺序的菜单不动；没有记录的按下面的默认顺序。
INSERT IGNORE INTO `admin_menu` (`menu_key`, `sort_order`, `visible`) VALUES
  ('dashboard', 10, 1),
  ('market', 20, 1),
  ('markets', 10, 1),
  ('market-review', 20, 1),
  ('autogen', 30, 1),
  ('sports', 40, 1),
  ('judge', 30, 1),
  ('resolve', 10, 1),
  ('disputes', 20, 1),
  ('settle', 40, 1),
  ('settle-runs', 10, 1),
  ('compensations', 20, 1),
  ('reports', 50, 1),
  ('params', 60, 1),
  ('customers', 70, 1),
  ('kyc', 80, 1),
  ('funds', 90, 1),
  ('deposits', 10, 1),
  ('withdrawals', 20, 1),
  ('reconciliation', 30, 1),
  ('pool-recon', 40, 1),
  ('tasks', 50, 1),
  ('notify', 100, 1),
  ('templates', 10, 1),
  ('send', 20, 1),
  ('records', 30, 1),
  ('system', 110, 1),
  ('admins', 10, 1),
  ('roles', 20, 1),
  ('menus', 30, 1),
  ('permissions', 40, 1),
  ('audit', 50, 1),
  ('config', 60, 1);
