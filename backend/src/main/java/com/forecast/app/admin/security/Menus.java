package com.forecast.app.admin.security;

import java.util.List;

/**
 * 后台菜单结构：固定在代码里，和后台 {@code web/admin/src/config/menu.tsx} 一致，每个页面都有菜单入口（测试核对）。
 * forecast S1 删掉了交易、风控、品种行情、交易参数四组；需求说明书第 6.1 节的 12 个一级菜单随 S3 以后各步补齐。
 * 后台只能改顺序和是否显示（表 {@code admin_menu}）；隐藏菜单不影响权限，权限只看权限点。
 */
public final class Menus {

  /** 一个菜单项：叶子有路径和权限点，分组有子菜单 */
  public record Item(String key, String labelKey, String path, Permission permission, List<Item> children) {}

  public static final List<Item> TREE = List.of(
      leaf("dashboard", "menu.dashboard", "/dashboard", Permission.DASHBOARD_VIEW),
      leaf("customers", "menu.customers", "/customers", Permission.CUSTOMER_VIEW),
      leaf("kyc", "menu.kyc", "/kyc", Permission.KYC_VIEW),
      group("funds", "menu.funds",
          leaf("deposits", "menu.deposits", "/funds/deposits", Permission.DEPOSIT_VIEW),
          leaf("withdrawals", "menu.withdrawals", "/funds/withdrawals", Permission.WITHDRAWAL_VIEW),
          leaf("reconciliation", "menu.reconciliation", "/funds/reconciliation", Permission.RECON_VIEW),
          leaf("tasks", "menu.tasks", "/funds/tasks", Permission.TASK_VIEW)),
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
