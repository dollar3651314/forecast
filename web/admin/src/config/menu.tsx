/**
 * 后台菜单。每个页面都必须在这里有入口（由单元测试检查），和后端 admin/security/Menus.java 一致（后端测试 AdminSystemTest 核对）。
 * forecast S1：复制源的交易、风控、品种行情、交易参数四组删除；S9 按需求说明书第 6.1 节加上市场管理、裁决、结算与补偿、报表、参数设置（11 个一级菜单）；
 * S10 加风控告警（12 个）。
 */
import type { ReactNode } from 'react';
import {
  AppstoreOutlined,
  AuditOutlined,
  BarChartOutlined,
  BellOutlined,
  ControlOutlined,
  FundOutlined,
  IdcardOutlined,
  ReconciliationOutlined,
  SafetyOutlined,
  SettingOutlined,
  TeamOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import type { PermissionCode } from './permissions';

export interface MenuItemDef {
  key: string;
  labelKey: string;
  icon?: ReactNode;
  /** 叶子菜单的路径（不含 /admin 前缀） */
  path?: string;
  /** 查看该页面需要的权限点 */
  permission?: PermissionCode;
  /** 菜单角标：对应 MenuBadges 的字段 */
  badge?: 'kyc' | 'withdrawals' | 'review' | 'riskAlerts';
  children?: MenuItemDef[];
}

export const MENU: MenuItemDef[] = [
  { key: 'dashboard', labelKey: 'menu.dashboard', icon: <AppstoreOutlined />, path: '/dashboard', permission: 'dashboard:view' },
  // forecast S9：需求第 6.1 节（内容中心、来源统计还没有；风控告警 S10）。顺序和后端 Menus.java、迁移 V17 的 admin_menu 一致
  {
    key: 'market',
    labelKey: 'menu.market',
    icon: <FundOutlined />,
    children: [
      { key: 'markets', labelKey: 'menu.markets', path: '/markets', permission: 'market:view' },
      { key: 'market-review', labelKey: 'menu.marketReview', path: '/markets/review', permission: 'market:publish', badge: 'review' },
      { key: 'autogen', labelKey: 'menu.autogen', path: '/markets/autogen', permission: 'market:view' },
      { key: 'sports', labelKey: 'menu.sports', path: '/markets/sports', permission: 'market:view' },
    ],
  },
  {
    key: 'judge',
    labelKey: 'menu.judge',
    icon: <AuditOutlined />,
    children: [
      { key: 'resolve', labelKey: 'menu.resolve', path: '/resolve', permission: 'resolve:propose' },
      { key: 'disputes', labelKey: 'menu.disputes', path: '/resolve/disputes', permission: 'dispute:view' },
    ],
  },
  {
    key: 'settle',
    labelKey: 'menu.settle',
    icon: <ReconciliationOutlined />,
    children: [
      { key: 'settle-runs', labelKey: 'menu.settleRuns', path: '/settle/runs', permission: 'pool_recon:view' },
      { key: 'compensations', labelKey: 'menu.compensations', path: '/settle/compensations', permission: 'report:view' },
    ],
  },
  { key: 'reports', labelKey: 'menu.reports', icon: <BarChartOutlined />, path: '/reports', permission: 'report:view' },
  { key: 'params', labelKey: 'menu.params', icon: <ControlOutlined />, path: '/params', permission: 'fee:edit' },
  { key: 'customers', labelKey: 'menu.customers', icon: <TeamOutlined />, path: '/customers', permission: 'customer:view' },
  { key: 'kyc', labelKey: 'menu.kyc', icon: <IdcardOutlined />, path: '/kyc', permission: 'kyc:view', badge: 'kyc' },
  {
    key: 'funds',
    labelKey: 'menu.funds',
    icon: <WalletOutlined />,
    children: [
      { key: 'deposits', labelKey: 'menu.deposits', path: '/funds/deposits', permission: 'deposit:view' },
      { key: 'withdrawals', labelKey: 'menu.withdrawals', path: '/funds/withdrawals', permission: 'withdrawal:view', badge: 'withdrawals' },
      { key: 'reconciliation', labelKey: 'menu.reconciliation', path: '/funds/reconciliation', permission: 'recon:view' },
      { key: 'pool-recon', labelKey: 'menu.poolRecon', path: '/funds/pool-recon', permission: 'pool_recon:view' },
      { key: 'tasks', labelKey: 'menu.tasks', path: '/funds/tasks', permission: 'task:view' },
    ],
  },
  // forecast S10：风控告警（需求第 6.1 节；迁移 V19 写默认顺序）
  { key: 'risk-alerts', labelKey: 'menu.riskAlerts', icon: <SafetyOutlined />, path: '/risk/alerts', permission: 'risk_alert:view', badge: 'riskAlerts' },
  {
    key: 'notify',
    labelKey: 'menu.notify',
    icon: <BellOutlined />,
    children: [
      { key: 'templates', labelKey: 'menu.templates', path: '/notify/templates', permission: 'notify_template:view' },
      { key: 'send', labelKey: 'menu.send', path: '/notify/send', permission: 'notify:send' },
      { key: 'records', labelKey: 'menu.records', path: '/notify/records', permission: 'notify_record:view' },
    ],
  },
  {
    key: 'system',
    labelKey: 'menu.system',
    icon: <SettingOutlined />,
    children: [
      { key: 'admins', labelKey: 'menu.admins', path: '/system/admins', permission: 'admin:view' },
      { key: 'roles', labelKey: 'menu.roles', path: '/system/roles', permission: 'role:view' },
      { key: 'menus', labelKey: 'menu.menus', path: '/system/menus', permission: 'menu:view' },
      { key: 'permissions', labelKey: 'menu.permissions', path: '/system/permissions', permission: 'permission:view' },
      { key: 'audit', labelKey: 'menu.audit', path: '/system/audit', permission: 'audit:view' },
      { key: 'config', labelKey: 'menu.config', path: '/system/config', permission: 'config:view' },
    ],
  },
];

/** 本地菜单（S9 起原型模式和真实模式相同；M1 的"只在原型模式显示"的菜单已改成正式菜单） */
export const LOCAL_MENU: MenuItemDef[] = MENU;

/** 所有叶子菜单（带路径） */
export function flattenMenu(items: MenuItemDef[] = LOCAL_MENU, parent?: MenuItemDef): (MenuItemDef & { parent?: MenuItemDef })[] {
  return items.flatMap((item) => (item.children ? flattenMenu(item.children, item) : [{ ...item, parent }]));
}

/** 菜单节点（和 GET /api/admin/me 返回的 menus 字段一致） */
export interface ServerMenuNode {
  key: string;
  path: string | null;
  labelKey: string;
  permission: string | null;
  order: number;
  visible: boolean;
  children?: ServerMenuNode[];
}

function localDef(key: string, items: MenuItemDef[] = LOCAL_MENU): MenuItemDef | undefined {
  for (const m of items) {
    if (m.key === key) return m;
    const c = m.children ? localDef(key, m.children) : undefined;
    if (c) return c;
  }
  return undefined;
}

/**
 * 当前管理员能看到的菜单。
 * - 真实模式：用 /api/admin/me 返回的 menus（后端已按权限点、顺序、是否显示过滤，AC-32），图标和角标取本地配置；
 * - 原型模式（没有 menus）：本地菜单按权限点过滤。
 * 两种情况都再按权限点检查一遍叶子；分组下一个都看不到时整个分组不显示。
 */
export function visibleMenu(serverMenus: ServerMenuNode[] | undefined, can: (perm: string | undefined) => boolean): MenuItemDef[] {
  const filter = (items: MenuItemDef[]): MenuItemDef[] =>
    items.flatMap((m) => {
      if (m.children) {
        const children = filter(m.children);
        return children.length ? [{ ...m, children }] : [];
      }
      return m.path && can(m.permission) ? [m] : [];
    });
  if (!serverMenus) return filter(LOCAL_MENU);
  const toDef = (n: ServerMenuNode): MenuItemDef => {
    const local = localDef(n.key);
    return {
      key: n.key,
      labelKey: n.labelKey,
      icon: local?.icon,
      badge: local?.badge,
      path: n.path ?? undefined,
      permission: (n.permission ?? undefined) as PermissionCode | undefined,
      children: n.children?.length ? n.children.map(toDef) : undefined,
    };
  };
  return filter(serverMenus.filter((n) => n.visible).map(toDef));
}

/** 根据当前路径找到对应菜单（详情页按前缀归到列表菜单） */
export function findMenuByPath(pathname: string): (MenuItemDef & { parent?: MenuItemDef }) | undefined {
  const leaves = flattenMenu();
  return leaves
    .filter((m) => m.path && (pathname === m.path || pathname.startsWith(m.path + '/')))
    .sort((a, b) => (b.path?.length ?? 0) - (a.path?.length ?? 0))[0];
}
