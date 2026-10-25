/** AC-35：后台每个页面都能从菜单进入；forecast S1 删除差价合约的交易、风控、品种、参数菜单后剩 6 个一级菜单 */
import { MENU, flattenMenu, findMenuByPath, visibleMenu, type ServerMenuNode } from './menu';
import { APP_ROUTES } from './routes';
import { PROTO_ROUTES } from './protoRoutes';
import { PROTO_MENU } from './protoMenu';
import { ALL_PERMISSION_CODES } from './permissions';

describe('菜单', () => {
  it('一级菜单共 6 个', () => {
    expect(MENU).toHaveLength(6);
  });

  it('每个页面路由都对应一个菜单入口', () => {
    const keys = new Set(flattenMenu().map((m) => m.key));
    const orphan = APP_ROUTES.filter((r) => !keys.has(r.menuKey)).map((r) => r.path);
    expect(orphan).toEqual([]);
  });

  it('每个菜单都有对应的页面路由，且权限点已登记（含 M1 原型页面）', () => {
    const paths = new Set([...APP_ROUTES, ...PROTO_ROUTES].map((r) => r.path));
    for (const m of flattenMenu()) {
      expect(paths.has(m.path!)).toBe(true);
      expect(ALL_PERMISSION_CODES).toContain(m.permission);
    }
  });

  it('原型模式（没有后端菜单）：按权限点过滤，空分组不显示', () => {
    const can = (p?: string) => p === 'customer:view' || p === 'deposit:view';
    const menu = visibleMenu(undefined, can);
    expect(menu.map((m) => m.key)).toEqual(['customers', 'funds']);
    expect(menu[1].children?.map((m) => m.key)).toEqual(['deposits']);
  });

  it('真实模式：按 /api/admin/me 的菜单顺序和是否显示，图标、角标取本地配置', () => {
    const node = (key: string, path: string | null, permission: string | null, order: number, visible = true, children?: ServerMenuNode[]): ServerMenuNode => ({
      key, path, labelKey: `menu.${key}`, permission, order, visible, children,
    });
    const server = [
      node('kyc', '/kyc', 'kyc:view', 10),
      node('customers', '/customers', 'customer:view', 20),
      node('funds', null, null, 30, true, [node('withdrawals', '/funds/withdrawals', 'withdrawal:view', 10)]),
      node('system', null, null, 40, false, [node('audit', '/system/audit', 'audit:view', 10)]),
    ];
    const menu = visibleMenu(server, () => true);
    expect(menu.map((m) => m.key)).toEqual(['kyc', 'customers', 'funds']);
    expect(menu[0].badge).toBe('kyc');
    expect(menu[0].icon).toBeTruthy();
    expect(menu[2].children?.[0].badge).toBe('withdrawals');
    // 没有权限点的叶子即使后端返回也不显示
    expect(visibleMenu(server, (p) => p !== 'kyc:view').map((m) => m.key)).toEqual(['customers', 'funds']);
  });

  it('M1 原型页面（市场管理、裁决台、参数设置）只在原型模式的本地菜单里，排在仪表盘后面；不进 MENU（后端 Menus.java 核对的是 MENU）', () => {
    expect(PROTO_MENU.map((m) => m.key)).toEqual(['markets', 'resolve', 'params']);
    expect(flattenMenu().map((m) => m.key).slice(0, 4)).toEqual(['dashboard', 'markets', 'resolve', 'params']);
    expect(flattenMenu(MENU).some((m) => m.key === 'markets')).toBe(false);
    expect(findMenuByPath('/markets/105901339852805101/edit')?.key).toBe('markets');
  });

  it('详情页归到列表菜单', () => {
    expect(findMenuByPath('/customers/100482')?.key).toBe('customers');
    expect(findMenuByPath('/kyc/KYC-1')?.key).toBe('kyc');
  });
});
