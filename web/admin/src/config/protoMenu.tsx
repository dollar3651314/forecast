/**
 * forecast M1 原型新增的后台菜单：市场管理、裁决台、参数设置（需求第 6.1 节）。
 * 只在原型（mock）模式下加到菜单里：后端的菜单表（admin/security/Menus.java + 迁移）在正式开发时加，那时把这三项移进 menu.tsx 的 MENU，
 * 并删掉本文件（后端 AdminSystemTest 核对 menu.tsx 和 Menus.java 一致，所以原型阶段不能直接写进 MENU）。
 */
import { AuditOutlined, ControlOutlined, FundOutlined } from '@ant-design/icons';
import type { MenuItemDef } from './menu';

export const PROTO_MENU: MenuItemDef[] = [
  { key: 'markets', labelKey: 'menu.markets', icon: <FundOutlined />, path: '/markets', permission: 'market:view' },
  { key: 'resolve', labelKey: 'menu.resolve', icon: <AuditOutlined />, path: '/resolve', permission: 'resolve:propose' },
  { key: 'params', labelKey: 'menu.params', icon: <ControlOutlined />, path: '/params', permission: 'fee:edit' },
];
