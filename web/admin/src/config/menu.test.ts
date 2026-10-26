/** AC-35：后台每个页面都能从菜单进入；forecast S9 按需求第 6.1 节有 11 个一级菜单，S10 加风控告警成 12 个，S13 加来源统计成 13 个，S16 加等候页订阅、Telegram 机器人成 15 个（和后端 Menus.java、迁移 V17、V19、V21、V41、V42 一致） */
import { MENU, flattenMenu, findMenuByPath, visibleMenu, type ServerMenuNode } from './menu';
import { APP_ROUTES } from './routes';
import { ALL_PERMISSION_CODES } from './permissions';

describe('菜单', () => {
  it('一级菜单共 15 个，顺序和后端 Menus.java 一致', () => {
    expect(MENU.map((m) => m.key)).toEqual(['dashboard', 'market', 'judge', 'settle', 'reports', 'growth', 'waitlist', 'content', 'params', 'customers', 'kyc', 'funds', 'risk-alerts', 'notify', 'system']);
  });

  it('整合 4：Telegram 机器人并进内容中心，是它的最后一项（发布渠道）', () => {
    expect(MENU.find((m) => m.key === 'content')?.children?.map((c) => c.key)).toEqual([
      'content-drafts',
      'content-events',
      'content-pages',
      'content-templates',
      'content-tools',
      'content-logs',
      'content-settings',
      'telegram',
    ]);
  });

  it('每个页面路由都对应一个菜单入口', () => {
    const keys = new Set(flattenMenu().map((m) => m.key));
    const orphan = APP_ROUTES.filter((r) => !keys.has(r.menuKey)).map((r) => r.path);
    expect(orphan).toEqual([]);
  });

  it('每个菜单都有对应的页面路由，且权限点已登记', () => {
    const paths = new Set(APP_ROUTES.map((r) => r.path));
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

  it('S9：M1 原型里"只在原型模式显示"的菜单改成了正式菜单（原型和真实模式同一套）', () => {
    expect(flattenMenu(MENU).map((m) => m.key)).toEqual(expect.arrayContaining(['markets', 'market-review', 'autogen', 'sports', 'resolve', 'disputes', 'settle-runs', 'compensations', 'reports', 'params', 'pool-recon']));
    expect(findMenuByPath('/markets/105901339852805101/edit')?.key).toBe('markets');
    expect(findMenuByPath('/markets/review')?.key).toBe('market-review');
    expect(findMenuByPath('/resolve/disputes')?.key).toBe('disputes');
  });

  it('详情页归到列表菜单', () => {
    expect(findMenuByPath('/customers/100482')?.key).toBe('customers');
    expect(findMenuByPath('/kyc/KYC-1')?.key).toBe('kyc');
  });
});
