package com.forecast.app.admin.ops;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.common.task.TaskService;
import com.forecast.app.common.task.TaskStatus;
import com.forecast.app.wallet.api.WalletAdminApi;
import com.forecast.app.wallet.api.WalletReconApi;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台仪表盘（从复制源复制）。"今天"按 UTC；在线客户 = 最近 5 分钟有请求的客户。
 * <p>forecast S1：删掉了差价合约的持仓数、平台盈亏、净敞口和"交易参数"（追保线）。市场、彩池、平台收入在 S3 以后加，
 * 报表在 S9（技术方案/v1-架构方案.md 第 1.2 节 admin）。
 */
@Service
class DashboardOps {

  /** 字段和 web/admin/src/api/types.ts 的 DashboardStats 一致 */
  record Stats(long todayRegistrations, long onlineCustomers, String todayDeposit, long todayDepositCount, String todayWithdraw, long todayWithdrawCount,
      long pendingKyc, long pendingWithdrawals, long failedTasks, long openReconciliations, String updatedAt) {}

  private final CustomerAdminApi customers;
  private final WalletAdminApi wallet;
  private final WalletReconApi recon;
  private final TaskService tasks;
  private final Clock clock;

  DashboardOps(CustomerAdminApi customers, WalletAdminApi wallet, WalletReconApi recon, TaskService tasks, Clock clock) {
    this.customers = customers;
    this.wallet = wallet;
    this.recon = recon;
    this.tasks = tasks;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  Stats stats() {
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime today = now.toLocalDate().atStartOfDay();
    WalletAdminApi.Bucket dep = wallet.creditedSince(today);
    WalletAdminApi.Bucket wd = wallet.summary(today).completedToday();
    return new Stats(customers.registeredSince(today), customers.activeSince(now.minus(Duration.ofMinutes(5))), Ops.money(dep.amount()), dep.count(),
        Ops.money(wd.amount()), wd.count(), customers.pendingKyc(), wallet.pendingReview(), tasks.count(null, TaskStatus.FAILED), recon.countIssues("OPEN"),
        clock.instant().toString());
  }
}
