-- forecast 基线迁移（S1，技术方案/v1-架构方案.md 第 3.4 节，AQ-9 按建议重建基线）。
-- 来源：在空的 MySQL 8.4 上跑 复制源（staging 2583328，见 技术方案/v1-架构方案.md 第 1.1 节）的 V1～V32，用 mysqldump --no-data 导出保留下来的表，
-- 再按下面写的修改整理；内置数据同样从那次导出里整理。差价合约的表（行情、订单、持仓、隔夜费、追保、风控、调余额）全部不建。
-- 已合并的迁移脚本不再修改；改表、改内置数据用新的迁移脚本。
-- 本文件：通知模板、偏好、站内信、发送记录、推送订阅，以及沿用的内置模板。
-- 修改：删掉差价合约的模板（开仓、平仓、部分平仓、成交、止盈止损、追保、强平、风控禁开）和旧的 KYC_RESULT；
-- 品牌名用占位名 Forecast（品牌待 Amos 确认，AQ-5）；去掉文案里"交易"的说法。需求说明书第 7 节的 22 类模板（含繁体中文一列）在 S9。

CREATE TABLE `notify_template` (
  `code` varchar(40) NOT NULL COMMENT '模板编号，业务代码按编号发送',
  `audience` varchar(8) NOT NULL COMMENT 'USER 发给客户 / STAFF 发给风控人员 / ANY 按邮箱发（验证码）',
  `kind` varchar(16) DEFAULT NULL COMMENT '站内信类型，和客户端 NotificationKind 一致；不发站内信的为空',
  `name_zh` varchar(60) NOT NULL,
  `name_en` varchar(60) NOT NULL,
  `title_zh` varchar(200) NOT NULL,
  `title_en` varchar(200) NOT NULL,
  `body_zh` varchar(1000) NOT NULL,
  `body_en` varchar(1000) NOT NULL,
  `variables` varchar(300) NOT NULL DEFAULT '' COMMENT '可用变量，逗号分隔',
  `channels` varchar(40) NOT NULL COMMENT 'INBOX、PUSH、EMAIL，逗号分隔',
  `built_in` tinyint(1) NOT NULL DEFAULT '1' COMMENT '内置模板只能改文案，不能禁用',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知模板';

CREATE TABLE `notify_pref` (
  `user_id` bigint NOT NULL,
  `push_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `email_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `lang` varchar(8) NOT NULL DEFAULT 'zh-CN' COMMENT '推送和邮件用的语言：zh-CN / en',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知偏好；没有记录时推送和邮件都开、中文';

CREATE TABLE `notify_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `kind` varchar(16) NOT NULL COMMENT '和客户端 NotificationKind 一致',
  `template_code` varchar(40) DEFAULT NULL COMMENT '按模板发的：打开时按当前语言生成标题和正文',
  `params` varchar(2000) DEFAULT NULL COMMENT '模板变量（JSON）',
  `title` varchar(200) DEFAULT NULL COMMENT '手动发送的标题和正文原样保存',
  `body` varchar(2000) DEFAULT NULL,
  `read_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notify_message_user` (`user_id`,`id`),
  KEY `idx_notify_message_unread` (`user_id`,`read_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内信';

CREATE TABLE `notify_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '发给客户的；发给风控人员或按邮箱发的为空',
  `recipient` varchar(254) DEFAULT NULL COMMENT '按邮箱发的收件人',
  `source` varchar(10) NOT NULL COMMENT 'TEMPLATE 模板 / MANUAL 后台手动发送',
  `template_code` varchar(40) DEFAULT NULL,
  `channel` varchar(8) NOT NULL COMMENT 'INBOX / PUSH / EMAIL',
  `title` varchar(200) NOT NULL COMMENT '中文标题，后台列表显示用',
  `status` varchar(8) NOT NULL COMMENT 'PENDING 发送中 / SENT 已发送 / FAILED 失败 / SKIPPED 已跳过',
  `error` varchar(500) DEFAULT NULL COMMENT '失败或跳过的原因',
  `operator_id` bigint DEFAULT NULL COMMENT '手动发送的操作人',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_notify_record_user` (`user_id`,`id`),
  KEY `idx_notify_record_status` (`status`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知记录，每个渠道一条';

CREATE TABLE `notify_push_sub` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `endpoint` varchar(1000) NOT NULL COMMENT '浏览器推送服务给的地址',
  `endpoint_hash` char(64) NOT NULL COMMENT '地址的 SHA-256（地址太长，不能直接建唯一索引）',
  `p256dh` varchar(120) NOT NULL COMMENT '浏览器的公钥（base64url）',
  `auth` varchar(40) NOT NULL COMMENT '浏览器的认证密钥（base64url）',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notify_push_sub_endpoint` (`endpoint_hash`),
  KEY `idx_notify_push_sub_user` (`user_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Web Push 订阅';

-- 内置数据
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('DEPOSIT_CREDITED','USER','deposit','入金到账','Deposit credited','充值已到账','Deposit credited','您通过 {chain} 充值的 {amount} USDT 已到账。','Your deposit of {amount} USDT via {chain} has been credited.','amount,chain','INBOX,PUSH,EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('DEPOSIT_SUSPENSE','STAFF',NULL,'冲正挂账','Reversal shortfall','充值被链上回滚，已挂账','Reversed deposit recorded as shortfall','客户 {userId} 在 {chain} 的充值 {txHash}（{amount} USDT）被链上回滚，余额不足，挂账 {shortfall} USDT，需要人工处理。','Deposit {txHash} ({amount} USDT) of customer {userId} on {chain} was reversed; {shortfall} USDT recorded as shortfall. Manual handling required.','chain,txHash,userId,amount,shortfall','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('EMAIL_CODE','ANY',NULL,'邮箱验证码','Email code','Forecast 验证码','Forecast verification code','您的验证码是 {code}，10 分钟内有效。请勿告诉任何人。','Your code is {code}. It expires in 10 minutes. Do not share it with anyone.','code,purpose','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('HOT_WALLET_LOW','STAFF',NULL,'热钱包余额不足','Hot wallet low','{chain} 热钱包余额不足','{chain} hot wallet balance low','{chain} 热钱包 {address} 的 {what} 余额不足，提现暂停上链，请补充。','The {what} balance of the {chain} hot wallet {address} is too low. Withdrawals are on hold until it is topped up.','chain,address,what','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('KYC_APPROVED','USER','kyc','身份认证通过','Identity verified','身份认证已通过','Identity verified','您的身份认证已通过，提现已开通。','Your identity has been verified. Withdrawals are now available.','','INBOX,PUSH,EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('KYC_REJECTED','USER','kyc','身份认证驳回','Identity verification rejected','身份认证未通过','Identity verification not approved','您的身份认证未通过，请到\"身份认证\"页面按原因修改后重新提交。\n驳回原因：{reason}','Your identity verification was not approved. Please fix the issue on the Identity verification page and submit again.\nReason: {reason}','reason','INBOX,PUSH,EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('OPS_ALERT','STAFF',NULL,'监控告警','Monitoring alert','告警：{itemZh}','Alert: {itemEn}','{itemZh}。\n说明：{detail}\n开始时间（UTC）：{since}\n恢复后会再发一封邮件；同一项 30 分钟内不重复发送。当前状态见健康检查 /api/health 的 alerts。','{itemEn}.\nDetail: {detail}\nSince (UTC): {since}\nA recovery email will follow. The same alert is not repeated within 30 minutes. Current state: alerts in /api/health.','itemZh,itemEn,detail,since','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('OPS_AUTO_APPROVE_SWITCH','STAFF',NULL,'提现自动审批总开关变更','Withdrawal auto-approval switch changed','提现自动审批总开关已{stateZh}','Withdrawal auto-approval switched {stateEn}','提现自动审批总开关已{stateZh}（规则配置第 {version} 版）。\n操作人：{admin}\n原因：{reason}\n时间（UTC）：{at}\n打开时，冷静期结束的提现满足全部已启用的规则就由系统自动通过；关闭时全部转人工审核。如果不是你们安排的变更，请立即在后台\"出金审核 → 自动审批规则\"关闭并查看审计日志。','Withdrawal auto-approval was switched {stateEn} (rule config version {version}).\nBy: {admin}\nReason: {reason}\nAt (UTC): {at}\nWhen on, withdrawals that pass every enabled rule after the cooling period are approved by the system; when off, all go to manual review. If this change was not planned, switch it off in the admin console (Withdrawals → Auto-approval rules) and check the audit log.','stateZh,stateEn,version,admin,reason,at','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('OPS_RECOVERED','STAFF',NULL,'监控告警恢复','Monitoring alert recovered','已恢复：{itemZh}','Recovered: {itemEn}','{itemZh}已恢复正常。\n开始时间（UTC）：{since}\n恢复时间（UTC）：{at}','{itemEn} has recovered.\nSince (UTC): {since}\nRecovered at (UTC): {at}','itemZh,itemEn,since,at','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('RECON_ISSUES','STAFF',NULL,'对账差异','Reconciliation issues','对账发现 {count} 个新问题','Reconciliation found {count} new issues','对账发现 {count} 个新问题，请在后台对账页面处理。','Reconciliation found {count} new issues. Please check the reconciliation page in the admin console.','count','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('SWEEP_FAILED','STAFF',NULL,'资金归集失败','Sweep failed','{chain} 归集批次 {batchNo} {statusZh}','{chain} sweep batch {batchNo} {statusEn}','{chain} 归集批次 {batchNo}：{detail}\n钱没有离开充值地址的部分下次会重新列入；请在后台\"资金归集\"查看。','{chain} sweep batch {batchNo}: {detail}\nAddresses that were not swept will be picked up again next time. See \"Fund sweep\" in the admin console.','chain,batchNo,statusZh,statusEn,detail','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('SWEEP_STUCK','STAFF',NULL,'归集交易长时间未确认','Sweep transaction stuck','{chain} 归集批次 {batchNo} 长时间未确认','{chain} sweep batch {batchNo} is stuck','{chain} 归集批次 {batchNo}（发起账户 nonce {nonce}）加价重发 {attempts} 次后仍未上链，需要人工处理（查发起账户余额和节点）。','Sweep batch {batchNo} on {chain} (operator nonce {nonce}) is still not mined after {attempts} fee bumps. Manual handling required (check the operator balance and the node).','chain,batchNo,nonce,attempts','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('SWEEP_UNKNOWN_OUTFLOW','STAFF',NULL,'充值地址异常转出','Unexpected outflow from a deposit address','{chain} 充值地址出现不是归集的转出','{chain}: unexpected outflow from a deposit address','{chain} 充值地址 {from}（客户 {userId}）转出 {amount} USDT 到 {to}，交易 {txHash} 不是系统发起的归集。{hint}\n请立即核对：Safe 有没有人手动归集；没有的话按资金归集的应急处理流程处理，并在后台对账页处理这条记录。','{amount} USDT moved from deposit address {from} (customer {userId}) to {to} on {chain} in transaction {txHash}, which is not a sweep sent by the system. {hint}\nCheck immediately whether anyone flushed it manually from the Safe; if not, follow the sweep incident procedure and resolve the item on the reconciliation page.','chain,from,to,amount,txHash,userId,hint','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('WELCOME','USER','system','注册欢迎','Welcome','欢迎来到 Forecast','Welcome to Forecast','开始使用前：\n1. 充值 USDT：在\"资产 → 充值\"选择网络，复制你的专属地址，从交易所或钱包转入 USDT。\n2. 完成身份认证：在\"我的 → 身份认证\"上传证件照片，一般 1 个工作日内审核完，结果会发到站内信和邮箱。提现前必须完成身份认证。\n随时可以在\"我的 → 新手引导\"重新查看。','Before you start:\n1. Deposit USDT: go to Assets → Deposit, choose a network, copy your own address and send USDT from an exchange or wallet.\n2. Verify your identity: upload photos of your ID under Me → Identity verification. Reviews usually finish within one business day; the result is sent to your inbox and email. Withdrawals require identity verification.\nYou can open the guide again from Me → Getting started.','','INBOX',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('WITHDRAW_STATUS','USER','withdraw','出金状态变化','Withdrawal status','提现状态更新','Withdrawal update','您的提现 {orderId}（{amount} USDT）{status}。\n原因：{reason}','Your withdrawal {orderId} ({amount} USDT) {status}.\nReason: {reason}','orderId,amount,status,reason','INBOX,PUSH,EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('WITHDRAW_STUCK','STAFF',NULL,'提现长时间未确认','Withdrawal stuck','{chain} 提现 {withdrawalId} 长时间未确认','{chain} withdrawal {withdrawalId} is stuck','{chain} 提现 {withdrawalId}（nonce {nonce}）提高手续费重发后仍未确认，需要人工处理。不会自动退款。','Withdrawal {withdrawalId} on {chain} (nonce {nonce}) is still unconfirmed after fee bumps. Manual handling required; it will not be refunded automatically.','chain,withdrawalId,nonce','EMAIL',1,1);
