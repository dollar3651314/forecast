package com.forecast.app.admin.security;

import java.util.List;

/**
 * 后台菜单结构：固定在代码里，和后台 {@code web/admin/src/config/menu.tsx} 一致，每个页面都有菜单入口（测试核对）。
 * forecast S1 删掉了交易、风控、品种行情、交易参数四组；S9 按需求说明书第 6.1 节加上市场管理、裁决、结算与补偿、报表、参数设置（迁移 V17 写默认顺序）。
 * 后台只能改顺序和是否显示（表 {@code admin_menu}）；隐藏菜单不影响权限，权限只看权限点。
 */
public final class Menus {

  /** 一个菜单项：叶子有路径和权限点，分组有子菜单 */
  public record Item(String key, String labelKey, String path, Permission permission, List<Item> children) {}

  public static final List<Item> TREE = List.of(
      leaf("dashboard", "menu.dashboard", "/dashboard", Permission.DASHBOARD_VIEW),
      // forecast S9：需求第 6.1 节（风控告警 S10；来源统计 S13；内容中心 S15）。默认顺序同迁移 V17 的 admin_menu
      group("market", "menu.market",
          leaf("markets", "menu.markets", "/markets", Permission.MARKET_VIEW),
          leaf("market-review", "menu.marketReview", "/markets/review", Permission.MARKET_PUBLISH),
          leaf("autogen", "menu.autogen", "/markets/autogen", Permission.MARKET_VIEW),
          leaf("sports", "menu.sports", "/markets/sports", Permission.MARKET_VIEW)),
      group("judge", "menu.judge",
          leaf("resolve", "menu.resolve", "/resolve", Permission.RESOLVE_PROPOSE),
          leaf("disputes", "menu.disputes", "/resolve/disputes", Permission.DISPUTE_VIEW)),
      group("settle", "menu.settle",
          leaf("settle-runs", "menu.settleRuns", "/settle/runs", Permission.POOL_RECON_VIEW),
          leaf("compensations", "menu.compensations", "/settle/compensations", Permission.REPORT_VIEW)),
      leaf("reports", "menu.reports", "/reports", Permission.REPORT_VIEW),
      // forecast S13：来源统计（需求第 6.1 节，MF-1、MF-10；迁移 V21 写默认顺序）
      leaf("growth", "menu.growth", "/growth", Permission.GROWTH_VIEW),
      // forecast S16：等候页订阅（需求第 6.2 节"来源统计"里的等候页订阅列表，MF-8；发送上线通知要 content:publish）。迁移 V26 写默认顺序
      leaf("waitlist", "menu.waitlist", "/waitlist", Permission.GROWTH_VIEW),
      // forecast S15：内容中心（需求第 6.1 节、自动化需求第 7 节；迁移 V24 写默认顺序）。
      // 整合 4：S16 的官方 Telegram 机器人（MF-7：渠道开关、群播报、模板审定、紧急停止、发布日志）作为内容中心的"发布渠道"并进来（S16 开发记录第 7 节做法 ①；迁移 V27）
      group("content", "menu.content",
          leaf("content-drafts", "menu.contentDrafts", "/content/drafts", Permission.CONTENT_VIEW),
          leaf("content-events", "menu.contentEvents", "/content/events", Permission.CONTENT_VIEW),
          leaf("content-pages", "menu.contentPages", "/content/pages", Permission.CONTENT_VIEW),
          leaf("content-templates", "menu.contentTemplates", "/content/templates", Permission.CONTENT_VIEW),
          leaf("content-tools", "menu.contentTools", "/content/tools", Permission.CONTENT_EDIT),
          leaf("content-logs", "menu.contentLogs", "/content/logs", Permission.CONTENT_VIEW),
          leaf("content-settings", "menu.contentSettings", "/content/settings", Permission.CONTENT_VIEW),
          leaf("telegram", "menu.telegram", "/content/telegram", Permission.CONTENT_VIEW)),
      leaf("params", "menu.params", "/params", Permission.FEE_EDIT),
      leaf("customers", "menu.customers", "/customers", Permission.CUSTOMER_VIEW),
      leaf("kyc", "menu.kyc", "/kyc", Permission.KYC_VIEW),
      group("funds", "menu.funds",
          leaf("deposits", "menu.deposits", "/funds/deposits", Permission.DEPOSIT_VIEW),
          leaf("withdrawals", "menu.withdrawals", "/funds/withdrawals", Permission.WITHDRAWAL_VIEW),
          leaf("reconciliation", "menu.reconciliation", "/funds/reconciliation", Permission.RECON_VIEW),
          leaf("pool-recon", "menu.poolRecon", "/funds/pool-recon", Permission.POOL_RECON_VIEW),
          leaf("tasks", "menu.tasks", "/funds/tasks", Permission.TASK_VIEW)),
      // forecast S10：风控告警（需求第 6.1 节；迁移 V19 写默认顺序）
      leaf("risk-alerts", "menu.riskAlerts", "/risk/alerts", Permission.RISK_ALERT_VIEW),
      group("notify", "menu.notify",
          leaf("templates", "menu.templates", "/notify/templates", Permission.NOTIFY_TEMPLATE_VIEW),
          leaf("send", "menu.send", "/notify/send", Permission.NOTIFY_SEND),
          leaf("records", "menu.records", "/notify/records", Permission.NOTIFY_RECORD_VIEW)),
      group("system", "menu.system",
          leaf("admins", "menu.admins", "/system/admins", Permission.ADMIN_VIEW),
          leaf("roles", "menu.roles", "/system/roles", Permission.ROLE_VIEW),
          leaf("menus", "menu.menus", "/system/menus", Permission.MENU_VIEW),
          leaf("permissions", "menu.permissions", "/system/permissions", Permission.PERMISSION_VIEW),
          leaf("audit", "menu.audit", "/system/audit", Permission.AUDIT_VIEW),
          leaf("config", "menu.config", "/system/config", Permission.CONFIG_VIEW)));

  private Menus() {}

  private static Item leaf(String key, String labelKey, String path, Permission permission) {
    return new Item(key, labelKey, path, permission, List.of());
  }

  private static Item group(String key, String labelKey, Item... children) {
    return new Item(key, labelKey, null, null, List.of(children));
  }
}
