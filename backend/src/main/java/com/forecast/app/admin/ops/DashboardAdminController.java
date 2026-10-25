package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.market.api.MarketAdminApi;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * forecast S9 后台页面要的几个只读汇总：仪表盘的彩池部分、市场管理和裁决台的分组数量。字段见 {@link MarketDashboardOps}。
 */
@RestController
class DashboardAdminController {

  private final MarketDashboardOps dashboard;
  private final MarketAdminApi markets;

  DashboardAdminController(MarketDashboardOps dashboard, MarketAdminApi markets) {
    this.dashboard = dashboard;
    this.markets = markets;
  }

  /** 仪表盘的彩池部分：今日 / 近 7 天（UTC）、待处理事项 */
  @RequiresPermission(Permission.DASHBOARD_VIEW)
  @GetMapping("/api/admin/dashboard/markets")
  MarketDashboardOps.View markets() {
    return dashboard.view();
  }

  /** 市场管理各分组的数量：byStatus（小写状态 → 数量）、review（待上架） */
  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/markets/counts")
  MarketAdminApi.Counts marketCounts() {
    return markets.counts();
  }

  /** 裁决台各分组的数量（all、awaiting_proposal、awaiting_confirm、auto_failed、on_hold、overdue、publicizing、disputed） */
  @RequiresPermission(Permission.RESOLVE_PROPOSE)
  @GetMapping("/api/admin/resolve/counts")
  Map<String, Long> resolveCounts() {
    return dashboard.resolveCounts();
  }
}
