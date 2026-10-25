/** 关键页面渲染：正常数据、空数据、出错和权限演示 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { renderAs, setScenario } from '../test/render';
import { WithdrawalsPage } from './funds/WithdrawalsPage';
import { CustomerListPage } from './customers/CustomerListPage';
import { AdminsPage } from './system/AdminsPage';
import { App } from '../App';
import { TemplatesPage } from './notify/TemplatesPage';
import * as db from '../mocks/data/db';

afterEach(() => setScenario('normal'));

describe('出金审核页（M6）', () => {
  it('显示待办卡片、列表和第一条的详情', async () => {
    renderAs(<WithdrawalsPage />);
    expect(await screen.findByText('103763725516804414')).toBeInTheDocument();
    expect(screen.getAllByText('待审核').length).toBeGreaterThan(1);
    expect(screen.getAllByText('张三').length).toBeGreaterThan(0);
    expect(screen.getByText('紧急取消（仅延迟期内可用 · 本单不适用）')).toBeInTheDocument();
  });

  it('拒绝时必须填写原因', async () => {
    renderAs(<WithdrawalsPage />);
    await screen.findByText('103763725516804414');
    fireEvent.click(screen.getByRole('button', { name: '拒绝' }));
    expect(await screen.findByText('拒绝时必须填写原因')).toBeInTheDocument();
  });

  it('空数据：卡片为 0 笔，列表显示空状态', async () => {
    setScenario('empty');
    renderAs(<WithdrawalsPage />);
    expect(await screen.findByText('没有符合条件的出金')).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText('0 笔')).toHaveLength(4));
    expect(screen.getByText('在左侧列表选择一笔出金查看详情')).toBeInTheDocument();
  });
});

describe('客户列表', () => {
  it('出错时显示原因和重试', async () => {
    setScenario('error');
    renderAs(<CustomerListPage />);
    expect(await screen.findByText(/无法连接服务器/)).toBeInTheDocument();
    setScenario('normal');
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText(/共 \d+ 条/)).toBeInTheDocument();
    expect(screen.queryByText(/无法连接服务器/)).toBeNull();
  });
});

describe('权限演示', () => {
  it('运营身份看不到系统中的角色菜单', async () => {
    renderAs(
      <Routes>
        <Route path="/*" element={<App />} />
      </Routes>,
      { adminId: 2, path: '/dashboard' },
    );
    const nav = await screen.findByRole('menu');
    expect(within(nav).queryByText('角色')).toBeNull();
    expect(within(nav).getByText('KYC 审核')).toBeInTheDocument();
  });

  it('非超级管理员不能分配超级管理员角色', async () => {
    renderAs(<AdminsPage />, { adminId: 2 });
    await screen.findByText('lina');
    fireEvent.click(screen.getByRole('button', { name: /新增管理员/ }));
    expect(await screen.findByText('你不是超级管理员，不能分配"超级管理员"角色（该选项已禁用）。')).toBeInTheDocument();
  });
});

describe('通知模板：品牌改名提醒（v2，《v2-需求说明书》第 8 节）', () => {
  it('没有模板含旧品牌名时不提醒', async () => {
    renderAs(<TemplatesPage />);
    expect(await screen.findByText('email_code')).toBeInTheDocument();
    expect(screen.queryByTestId('old-brand-hint')).toBeNull();
  });

  it('运营改过、仍含旧品牌名的模板：提醒运营检查', async () => {
    const tpl = db.templates.find((t) => t.code === 'email_code')!;
    const before = tpl.titleZh;
    tpl.titleZh = 'Falcon' + 'X 验证码（运营改过）';
    try {
      renderAs(<TemplatesPage />);
      const hint = await screen.findByTestId('old-brand-hint');
      expect(hint).toHaveTextContent('旧品牌名');
      expect(hint).toHaveTextContent('email_code');
    } finally {
      tpl.titleZh = before;
    }
  });
});
