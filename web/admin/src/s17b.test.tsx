/**
 * forecast S17B 后台：人工调账（需求说明书第 21 节）。客户详情的"人工加款""人工扣款"（balance:adjust，高危）：金额、类别、原因必填，参考号选填；
 * 类别按方向；扣款不能超过可用余额；超过单笔上限要再勾一次；"人工调账记录"页的筛选、导出；菜单、权限点；报表、对账、风控告警的显示；真实接口。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { renderAs, setScenario } from './test/render';
import { customers, params } from './mocks/data/db';
import { CustomerDetailPage } from './pages/customers/CustomerDetailPage';
import { AdjustmentsPage } from './pages/funds/AdjustmentsPage';
import { ReportsPage } from './pages/reports/ReportsPage';
import { ReconPage } from './pages/funds/ReconPage';
import { riskSummary } from './pages/risk/RiskAlertsPage';
import { PERMISSIONS } from './config/permissions';
import { MENU } from './config/menu';
import { ADJUST_CATEGORIES } from './api/fcTypes';
import * as realFc from './api/realFc';
import { fakeFetch } from './test/fakeFetch';
import i18n from './i18n';

const page = (id: number, adminId = 1) =>
  renderAs(
    <Routes>
      <Route path="/customers/:id" element={<CustomerDetailPage />} />
    </Routes>,
    { path: `/customers/${id}`, adminId },
  );

const c = customers[4];
const balance0 = c.balance;

afterEach(() => {
  setScenario('normal');
  c.balance = balance0;
  params.find((p) => p.key === 'adjust.manualMax')!.value = '10000';
  vi.unstubAllGlobals();
});

async function clickEnabled(name: RegExp) {
  await waitFor(() => expect(screen.getByRole('button', { name })).toBeEnabled(), { timeout: 8000 });
  fireEvent.click(screen.getByRole('button', { name }));
}

async function fill(dialog: HTMLElement, amount: string, reason: string) {
  fireEvent.change(within(dialog).getByLabelText('金额（USDT）'), { target: { value: amount } });
  fireEvent.change(within(dialog).getByLabelText('操作原因'), { target: { value: reason } });
  const check = within(dialog).getByText(/我已核对操作对象/).closest('label')!.querySelector('input')!;
  if (!check.checked) fireEvent.click(check);
}

describe('权限点、菜单、类别', () => {
  it('balance:adjust 高危；菜单"资金 → 人工调账记录"要 recon:view', () => {
    expect(PERMISSIONS.find((p) => p.code === 'balance:adjust')).toMatchObject({ module: 'customer', highRisk: true });
    const funds = MENU.find((m) => m.key === 'funds')!;
    const keys = funds.children!.map((m) => m.key);
    expect(keys.indexOf('adjustments')).toBe(keys.indexOf('reconciliation') + 1);
    expect(funds.children!.find((m) => m.key === 'adjustments')).toMatchObject({ path: '/funds/adjustments', permission: 'recon:view' });
  });

  it('类别按方向（和后端 ManualAdjustApi.Category 一致）', () => {
    expect(ADJUST_CATEGORIES).toEqual({ credit: ['deposit_missing', 'other'], debit: ['wrong_credit', 'other'] });
  });

  it('风控告警的说明：24 小时人工加款累计', () => {
    const t = i18n.getFixedT('zh-CN');
    expect(
      riskSummary(t as never, { kind: 'manual_credit_large', detail: { total: '12000.00', limit: '10000.00', adjustNo: '108' } } as never),
    ).toBe('24 小时内人工加款累计 12,000.00，超过阈值 10,000.00（调账编号 108）');
  });
});

describe('客户详情：人工加款、扣款', () => {
  it('加款：类别只有"充值未到账补入""其他"；金额必填；参考号选填；成功提示前后余额', { timeout: 40000 }, async () => {
    page(c.id);
    await clickEnabled(/人工加款/);
    const dialog = await screen.findByRole('dialog', undefined, { timeout: 5000 });
    expect(within(dialog).getByText(/对手方是平台"人工调账"账户/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText('充值未到账补入')).toBeChecked();
    expect(within(dialog).getByLabelText('其他')).toBeInTheDocument();
    expect(within(dialog).queryByLabelText('误入账扣回')).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '确认加款' }));
    expect(await within(dialog).findByText('请填写金额', undefined, { timeout: 5000 })).toBeInTheDocument();
    await fill(dialog, '300', 'TRC20 充值没识别');
    fireEvent.change(within(dialog).getByLabelText('参考号（选填）'), { target: { value: '0xabc' } });
    expect(within(dialog).queryByTestId('adjust-over-limit')).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '确认加款' }));
    expect(await screen.findByText(/已加款 300\.00 USDT，可用余额/, undefined, { timeout: 5000 })).toBeInTheDocument();
  });

  it('超过单笔上限：出现"我确认超过单笔上限"，不勾不能提交，勾了才调', { timeout: 40000 }, async () => {
    params.find((p) => p.key === 'adjust.manualMax')!.value = '100';
    page(c.id);
    await clickEnabled(/人工加款/);
    const dialog = await screen.findByRole('dialog', undefined, { timeout: 5000 });
    expect(within(dialog).getByText('单笔上限 100.00 USDT（参数设置 → 余额调整）')).toBeInTheDocument();
    await fill(dialog, '150', '大额补入');
    const over = await within(dialog).findByTestId('adjust-over-limit', undefined, { timeout: 5000 });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认加款' }));
    expect(await within(dialog).findByText('金额超过单笔上限，要勾选这一项', undefined, { timeout: 5000 })).toBeInTheDocument();
    fireEvent.click(over.closest('label')!.querySelector('input')!);
    fireEvent.click(within(dialog).getByRole('button', { name: '确认加款' }));
    expect(await screen.findByText(/已加款 150\.00 USDT/, undefined, { timeout: 5000 })).toBeInTheDocument();
  });

  it('扣款：类别"误入账扣回""其他"；不能超过可用余额', { timeout: 40000 }, async () => {
    page(c.id);
    await clickEnabled(/人工扣款/);
    const dialog = await screen.findByRole('dialog', undefined, { timeout: 5000 });
    expect(within(dialog).getByLabelText('误入账扣回')).toBeChecked();
    expect(within(dialog).queryByLabelText('充值未到账补入')).not.toBeInTheDocument();
    expect(within(dialog).getByText(/下注中、提现冻结的钱不动/)).toBeInTheDocument();
    await fill(dialog, '999999999', '扣太多');
    fireEvent.click(within(dialog).getByRole('button', { name: '确认扣款' }));
    expect(await within(dialog).findByText(/不能超过可用余额/, undefined, { timeout: 5000 })).toBeInTheDocument();
    await fill(dialog, '1', '误入账扣回');
    fireEvent.click(within(dialog).getByRole('button', { name: '确认扣款' }));
    expect(await screen.findByText(/已扣款 1\.00 USDT/, undefined, { timeout: 5000 })).toBeInTheDocument();
  });

  it('没有 balance:adjust：两个按钮都置灰', async () => {
    page(c.id, 2);
    await screen.findByText('最近登录 IP');
    await waitFor(() => expect(screen.getByRole('button', { name: /人工加款/ })).toBeDisabled());
    expect(screen.getByRole('button', { name: /人工扣款/ })).toBeDisabled();
  });
});

describe('人工调账记录', () => {
  it('列表：方向、类别、前后余额、参考号；按方向筛选', { timeout: 30000 }, async () => {
    renderAs(<AdjustmentsPage />, { path: '/funds/adjustments' });
    expect(await screen.findByText('BEP20 充值到了旧地址，链上已核对', undefined, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getAllByText('充值未到账补入').length).toBeGreaterThan(0);
    expect(screen.getByText('同一笔充值入账两次，扣回多入的一笔')).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '方向' }));
    fireEvent.click(await screen.findByTitle('扣款'));
    fireEvent.click(screen.getByRole('button', { name: /查\s?询/ }));
    await waitFor(() => expect(screen.queryByText('BEP20 充值到了旧地址，链上已核对')).not.toBeInTheDocument(), { timeout: 5000 });
    expect(screen.getByText('同一笔充值入账两次，扣回多入的一笔')).toBeInTheDocument();
  });

  it('空、出错', async () => {
    setScenario('empty');
    const { unmount } = renderAs(<AdjustmentsPage />, { path: '/funds/adjustments' });
    expect(await screen.findByText('没有人工调账记录', undefined, { timeout: 5000 })).toBeInTheDocument();
    unmount();
    setScenario('error');
    renderAs(<AdjustmentsPage />, { path: '/funds/adjustments' });
    await waitFor(() => expect(screen.getByRole('button', { name: /重试/ })).toBeInTheDocument(), { timeout: 5000 });
  });

  it('英文界面', async () => {
    renderAs(<AdjustmentsPage />, { path: '/funds/adjustments', lang: 'en' });
    expect(await screen.findByText('Manual adjustments', undefined, { timeout: 5000 })).toBeInTheDocument();
    expect((await screen.findAllByText('Missing deposit credited', undefined, { timeout: 5000 })).length).toBeGreaterThan(0);
  });
});

describe('报表、对账', () => {
  it('报表：人工加款、人工扣款单独卡片', async () => {
    renderAs(<ReportsPage />, { path: '/reports' });
    expect((await screen.findAllByText('人工加款', undefined, { timeout: 5000 })).length).toBeGreaterThan(0);
    expect(screen.getAllByText('人工扣款').length).toBeGreaterThan(0);
  });

  it('对账：平台账户里人工调账一行', async () => {
    renderAs(<ReconPage />, { path: '/funds/reconciliation' });
    const row = (await screen.findByText('人工调账', undefined, { timeout: 5000 })).closest('tr')!;
    expect(row).toHaveTextContent('一致');
  });
});

describe('真实接口', () => {
  it('路径和请求体', async () => {
    const { calls } = fakeFetch({
      'POST /api/admin/customers/7/adjust': { body: { customer: {}, adjustment: { adjustNo: '1' }, duplicate: false } },
      'GET /api/admin/balance-adjustments': { body: { items: [], total: 0, page: 1, pageSize: 20 } },
    });
    await realFc.adjustBalance(7, { direction: 'debit', category: 'wrong_credit', amount: '5', refNo: ' ', reason: '测试', requestId: 'adj-12345678', overLimitConfirmed: false });
    expect(calls[0]).toMatchObject({
      method: 'POST',
      url: '/api/admin/customers/7/adjust',
      body: { direction: 'debit', category: 'wrong_credit', amount: '5', refNo: null, reason: '测试', requestId: 'adj-12345678', overLimitConfirmed: false, checked: true },
    });
    await realFc.listAdjustments({ customerId: '7', direction: 'credit', page: 1, pageSize: 20 });
    expect(calls[1].url).toContain('/api/admin/balance-adjustments?');
    expect(calls[1].url).toContain('customerId=7');
    expect(calls[1].url).toContain('direction=credit');
  });
});
