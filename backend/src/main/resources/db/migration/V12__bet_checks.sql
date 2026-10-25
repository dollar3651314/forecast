-- forecast S4：下注前的检查要用到的列和索引（需求说明书第 3.3 节的拒单原因：员工账户、KYC 阈值；架构方案第 3.3 节 account_user 加 is_staff）。
-- 新项目没有已有数据（需求第 14 节）；新列有默认值，已有客户一律"不是员工"。
-- 员工的登记（后台客户详情标记"员工"）和 E15（漏登记后补登，退回员工的下注）在 S10 做；S4 只在下注时检查这一列。

ALTER TABLE `account_user`
  ADD COLUMN `is_staff` tinyint(1) NOT NULL DEFAULT '0' COMMENT '员工账户不能下注（需求第 3.3、8.1 节；后台登记在 S10）' AFTER `frozen_reason`;

-- 下注时判断"累计充值（扣冲正）是否超过 KYC 阈值"（只对没通过 KYC 的客户算）、以后提现自动审批 R8～R10（S10）按类型合计一个客户的流水：
-- 原来只有 (account_id, id)，客户的下注流水多了以后要扫全部流水；加上 entry_type 只扫这一类
ALTER TABLE `ledger_entry`
  ADD KEY `idx_ledger_entry_account_type` (`account_id`, `entry_type`, `id`);
