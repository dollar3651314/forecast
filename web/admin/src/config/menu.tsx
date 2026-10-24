/**
 * 后台菜单。每个页面都必须在这里有入口（由单元测试检查），和后端 admin/security/Menus.java 一致（后端测试 AdminSystemTest 核对）。
 * forecast S1：复制源的交易、风控、品种行情、交易参数四组删除；需求说明书第 6.1 节的 12 个一级菜单随 S3 以后各步补齐。
 */
import type { ReactNode } from 'react';
import {
  AppstoreOutlined,
  TeamOutlined,
  IdcardOutlined,
  WalletOutlined,
  BellOutlined,
  SettingOutlined,
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
  badge?: 'kyc' | 'withdrawals';
  children?: MenuItemDef[];
}

export const MENU: MenuItemDef[] = [
  { key: 'dashboard', labelKey: 'menu.dashboard', icon: <AppstoreOutlined />, path: '/dashboard', permission: 'dashboard:view' },
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
      { key: 'tasks', labelKey: 'menu.tasks', path: '/funds/tasks', permission: 'task:view' },
    ],
  },
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

/** 所有叶子菜单（带路径） */
export function flattenMenu(items: MenuItemDef[] = MENU, parent?: MenuItemDef): (MenuItemDef & { parent?: MenuItemDef })[] {
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

function localDef(key: string, items: MenuItemDef[] = MENU): MenuItemDef | undefined {
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
  if (!serverMenus) return filter(MENU);
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
