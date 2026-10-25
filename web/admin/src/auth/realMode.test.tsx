/**
 * real 模式下的登录流程和菜单（fetch 替身代替后端）：
 * 没登录回登录页、IP 不在白名单有明确提示、首次登录跳改密码页、菜单只显示 /api/admin/me 返回的、页面数据来自真实接口。
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import zh from '../i18n/zh-CN.json';
import i18n from '../i18n';
import { Providers } from '../Providers';
import { App } from '../App';
import { fakeFetch } from '../test/fakeFetch';

vi.mock('../api/mode', () => ({ API_MODE: 'real', IS_MOCK: false }));

const admin = {
  id: 7,
  username: 'lina',
  displayName: '李娜',
  email: 'lina@example.com',
  roleIds: ['2'],
  status: 'active',
  mustChangePassword: false,
  lastLoginAt: '2026-10-14T01:12:33Z',
  createdAt: '2026-10-01T00:00:00Z',
};

const me = (overrides: Partial<typeof admin> = {}) => ({
  admin: { ...admin, ...overrides },
  roles: [{ id: '2', code: 'operations', nameZh: '运营', nameEn: 'Operations', description: '', permissions: ['customer:view'], builtIn: true, memberCount: 1 }],
  permissions: ['customer:view'],
  isSuperAdmin: false,
  menus: [{ key: 'customers', path: '/customers', labelKey: 'menu.customers', permission: 'customer:view', order: 20, visible: true, children: [] }],
});

function renderApp(path: string) {
  void i18n.changeLanguage('zh-CN');
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Providers>
        <App />
      </Providers>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('真实接口模式', () => {
  it('没登录时回登录页；没有原型横幅和原型提示', async () => {
    fakeFetch({ 'GET /api/admin/me': { status: 401, body: { code: 'UNAUTHORIZED', message: '未登录' } } });
    renderApp('/customers');
    expect(await screen.findByRole('heading', { name: zh.auth.loginTitle })).toBeInTheDocument();
    expect(screen.queryByText(zh.proto.banner)).not.toBeInTheDocument();
    expect(screen.queryByText(zh.auth.protoHint)).not.toBeInTheDocument();
    expect(screen.queryByText(zh.errors.unauthorized)).not.toBeInTheDocument();
  });

  it('IP 不在白名单：登录页显示明确原因', async () => {
    fakeFetch({ 'GET /api/admin/me': { status: 403, body: { code: 'IP_NOT_ALLOWED', message: '当前网络不能访问后台' } } });
    renderApp('/');
    expect(await screen.findByText(zh.errors.ipNotAllowed)).toBeInTheDocument();
  });

  it('首次登录：登录后跳到改密码页，前端不保存令牌', async () => {
    let loggedIn = false;
    const { calls } = fakeFetch({
      'GET /api/admin/me': () => (loggedIn ? { body: me({ mustChangePassword: true }) } : { status: 401, body: { code: 'UNAUTHORIZED' } }),
      'POST /api/admin/auth/login': () => {
        loggedIn = true;
        return { body: { adminId: 7, username: 'lina', displayName: '李娜', mustChangePassword: true } };
      },
    });
    renderApp('/');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(zh.auth.username), 'lina');
    await user.type(screen.getByLabelText(zh.auth.password), 'Temp1234567');
    await user.click(screen.getByRole('button', { name: zh.auth.login }));
    expect(await screen.findByRole('heading', { name: zh.auth.firstLoginTitle })).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ username: 'lina', password: 'Temp1234567' });
    expect(localStorage.getItem('fx-admin-session')).toBeNull();
  });

  it('已登录：菜单只显示后端返回的，列表数据来自接口；没有"切换原型身份"', async () => {
    const { calls } = fakeFetch({
      'GET /api/admin/me': { body: me() },
      'GET /api/admin/menu-badges': { body: { kyc: 0, withdrawals: 0 } },
      'GET /api/admin/customers': {
        body: {
          items: [
            {
              id: 100482, name: '王小明', email: 'wang@example.com', nationality: 'CN', status: 'active', kycStatus: 'approved', balance: '1200.5',
              registeredAt: '2026-10-01T02:00:00Z', lastLoginAt: null,
            },
          ],
          total: 1,
          page: 1,
          pageSize: 10,
        },
      },
    });
    renderApp('/customers');
    expect(await screen.findByText('王小明')).toBeInTheDocument();
    expect(screen.getByText('10-01 10:00')).toBeInTheDocument();
    expect(screen.queryByText(zh.menu.dashboard)).not.toBeInTheDocument();
    expect(screen.queryByText(zh.proto.switchIdentity, { exact: false })).not.toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.url === '/api/admin/customers?page=1&pageSize=10')).toBe(true));
    expect(calls.every((c) => c.init.credentials === 'same-origin')).toBe(true);
  });

  it('重置管理员密码：带原因提交，临时密码在弹窗里显示一次', async () => {
    const target = { ...admin, id: 9, username: 'neo', displayName: 'Neo', email: 'neo@example.com' };
    const sysMe = {
      ...me(),
      permissions: ['admin:view', 'admin:edit'],
      menus: [{ key: 'system', path: null, labelKey: 'menu.system', permission: null, order: 100, visible: true, children: [{ key: 'admins', path: '/system/admins', labelKey: 'menu.admins', permission: 'admin:view', order: 10, visible: true, children: [] }] }],
    };
    const { calls } = fakeFetch({
      'GET /api/admin/me': { body: sysMe },
      'GET /api/admin/menu-badges': { body: { kyc: 0, withdrawals: 0 } },
      'GET /api/admin/admins': { body: { items: [target], total: 1, page: 1, pageSize: 10 } },
      'GET /api/admin/roles': { body: sysMe.roles },
      'POST /api/admin/admins/9/reset-password': { body: { id: 9, temporaryPassword: 'tmp-aaaa-1111' } },
    });
    renderApp('/system/admins');
    await screen.findByText('neo');
    fireEvent.click(screen.getByRole('button', { name: new RegExp(zh.admins.resetPwd) }));
    fireEvent.change(await screen.findByLabelText(zh.highRisk.reason), { target: { value: '本人忘记密码' } });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: zh.highRisk.confirm }));
    expect(await screen.findByText('tmp-aaaa-1111')).toBeInTheDocument();
    expect(screen.getByText(zh.admins.tempPwdOnce)).toBeInTheDocument();
    expect(calls.find((c) => c.url === '/api/admin/admins/9/reset-password')?.body).toEqual({ reason: '本人忘记密码' });
    fireEvent.click(screen.getByRole('button', { name: zh.admins.tempPwdSaved }));
    await waitFor(() => expect(screen.queryByText('tmp-aaaa-1111')).not.toBeInTheDocument());
  }, 60_000);

  it('没有权限的页面：提示联系超级管理员，不提原型身份', async () => {
    fakeFetch({ 'GET /api/admin/me': { body: me() }, 'GET /api/admin/menu-badges': { body: { kyc: 0, withdrawals: 0 } } });
    renderApp('/system/audit');
    expect(await screen.findByText(zh.errors.forbiddenPageReal)).toBeInTheDocument();
  });
});
