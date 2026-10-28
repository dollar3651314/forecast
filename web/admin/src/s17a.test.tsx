/**
 * forecast S17A 后台：测试入金（只在测试环境）。客户详情的按钮只在后端给了 testCredit 时显示（生产为 null，不显示）；
 * 高危确认（金额、原因必填、勾选）、单笔上限、重复提交；权限点 balance:test_credit；报表单独一列、对账页"平台账户"单独一行；真实接口的路径和请求体。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { renderAs, setScenario } from './test/render';
import { customers, mockEnv, params } from './mocks/data/db';
import { CustomerDetailPage } from './pages/customers/CustomerDetailPage';
import { ReportsPage } from './pages/reports/ReportsPage';
import { ReconPage } from './pages/funds/ReconPage';
import { ParamsPage } from './pages/params/ParamsPage';
import { PERMISSIONS } from './config/permissions';
import * as realFc from './api/realFc';
import { fakeFetch } from './test/fakeFetch';
import { amountProblem, newRequestId } from './utils/adjust';

const page = (id: number, adminId = 1) =>
  renderAs(
    <Routes>
      <Route path="/customers/:id" element={<CustomerDetailPage />} />
    </Routes>,
    { path: `/customers/${id}`, adminId },
  );

const balance0 = customers[3].balance;

afterEach(() => {
  setScenario('normal');
  mockEnv.testCredit = true;
  customers[3].balance = balance0;
  params.find((p) => p.key === 'adjust.testCreditMax')!.value = '10000';
  vi.unstubAllGlobals();
});

/** 按钮要等登录身份（权限）加载完才可用 */
async function clickEnabled(name: RegExp) {
  // 页面数据刷新时按钮会重新挂载：每次都重新找
  await waitFor(() => expect(screen.getByRole('button', { name })).toBeEnabled(), { timeout: 8000 });
  fireEvent.click(screen.getByRole('button', { name }));
}

async function fillAndConfirm(dialog: HTMLElement, amount: string, reason: string) {
  fireEvent.change(within(dialog).getByLabelText('入金金额（USDT）'), { target: { value: amount } });
  fireEvent.change(within(dialog).getByLabelText('操作原因'), { target: { value: reason } });
  const check = within(dialog).getByText(/我已核对操作对象/).closest('label')!.querySelector('input')!;
  if (!check.checked) fireEvent.click(check);
  fireEvent.click(within(dialog).getByRole('button', { name: '确认入金' }));
}

describe('权限点和规则', () => {
  it('balance:test_credit 在客户组、高危', () => {
    expect(PERMISSIONS.find((p) => p.code === 'balance:test_credit')).toMatchObject({ module: 'customer', highRisk: true });
  });

  it('金额：必填、大于 0、最多 8 位小数、不超过上限；请求号符合后端规则', () => {
    expect(amountProblem('', '10000')).toBe('required');
    expect(amountProblem('0', '10000')).toBe('invalid');
    expect(amountProblem('-5', '10000')).toBe('invalid');
    expect(amountProblem('1.123456789', '10000')).toBe('invalid');
    expect(amountProblem('abc', '10000')).toBe('invalid');
    expect(amountProblem('10000.01', '10000')).toBe('overLimit');
    expect(amountProblem('10000', '10000')).toBeNull();
    expect(amountProblem('0.00000001', '10000')).toBeNull();
    const a = newRequestId();
    expect(a).toMatch(/^[A-Za-z0-9._-]{8,64}$/);
    expect(newRequestId()).not.toBe(a);
  });
});

describe('客户详情：测试入金', () => {
  it('测试环境：显示按钮；金额必填、超过上限提示；入金后提示前后余额、刷新详情', { timeout: 40000 }, async () => {
    const c = customers[3];
    page(c.id);
    await clickEnabled(/测试入金/);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/只在测试环境可用/)).toBeInTheDocument();
    expect(within(dialog).getByText('单笔上限 10,000.00 USDT（参数设置 → 余额调整）')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '确认入金' }));
    expect(await within(dialog).findByText('请填写入金金额', undefined, { timeout: 5000 })).toBeInTheDocument();
    await fillAndConfirm(dialog, '10000.5', '测试上限');
    expect(await within(dialog).findByText('超过单笔上限 10,000.00 USDT', undefined, { timeout: 5000 })).toBeInTheDocument();
    await fillAndConfirm(dialog, '500', '测试下注流程');
    expect(await screen.findByText(/已入金 500\.00 USDT，可用余额/, undefined, { timeout: 5000 })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('后端拒绝（超过参数设置里改小的上限）：弹窗不关，显示后端的原因', { timeout: 40000 }, async () => {
    const c = customers[3];
    page(c.id);
    // 页面打开以后别人把上限改小了：前端按页面上的 10,000 放行，后端拒绝
    await clickEnabled(/测试入金/);
    params.find((p) => p.key === 'adjust.testCreditMax')!.value = '50';
    const dialog = await screen.findByRole('dialog', undefined, { timeout: 5000 });
    await fillAndConfirm(dialog, '60', '超过新上限');
    expect(await within(dialog).findByText(/超过测试入金的单笔上限 50 USDT/, undefined, { timeout: 5000 })).toBeInTheDocument();
  });

  it('生产（后端 testCredit 为空）：不显示按钮', async () => {
    mockEnv.testCredit = false;
    page(customers[3].id);
    expect(await screen.findByText('最近登录 IP')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /测试入金/ })).not.toBeInTheDocument();
    expect(screen.queryByTestId('test-credit')).not.toBeInTheDocument();
  });

  it('没有 balance:test_credit：按钮置灰', async () => {
    page(customers[3].id, 2);
    const btn = await screen.findByRole('button', { name: /测试入金/ }, { timeout: 5000 });
    expect(btn).toBeDisabled();
  });

  it('英文界面', async () => {
    renderAs(
      <Routes>
        <Route path="/customers/:id" element={<CustomerDetailPage />} />
      </Routes>,
      { path: `/customers/${customers[3].id}`, lang: 'en' },
    );
    await clickEnabled(/Test credit/);
    const dialog = await screen.findByRole('dialog', undefined, { timeout: 5000 });
    expect(within(dialog).getByText(/Test environment only/)).toBeInTheDocument();
  });
});

describe('报表、对账、参数设置', () => {
  it('报表：测试入金单独一张卡片、一列', async () => {
    renderAs(<ReportsPage />, { path: '/reports' });
    expect(await screen.findAllByText('测试入金', undefined, { timeout: 5000 })).not.toHaveLength(0);
    await waitFor(() => expect(screen.getAllByText('15,000.00').length).toBeGreaterThanOrEqual(2), { timeout: 5000 });
  });

  it('对账：平台账户三行，测试入金单独一行、余额为负、一致', async () => {
    renderAs(<ReconPage />, { path: '/funds/recon' });
    expect(await screen.findByText('平台账户', undefined, { timeout: 5000 })).toBeInTheDocument();
    const row = (await screen.findByText('测试入金', undefined, { timeout: 5000 })).closest('tr')!;
    expect(row.textContent).toMatch(/测试入金-15,\d{3}\.00-15,\d{3}\.00/);
    expect(row).toHaveTextContent('一致');
    expect(screen.getByText('平台收入（抽成 + 尾差）')).toBeInTheDocument();
    expect(screen.getByText('平台补偿支出')).toBeInTheDocument();
  });

  it('参数设置："余额调整"一组有测试入金单笔上限，默认 10,000 USDT', async () => {
    renderAs(<ParamsPage />, { path: '/params' });
    expect(await screen.findByText('测试入金单笔上限（只在测试环境生效）', undefined, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText('余额调整')).toBeInTheDocument();
    expect(screen.getByText(/生产上这一项不起作用/)).toBeInTheDocument();
  });
});

describe('真实接口', () => {
  it('路径和请求体', async () => {
    const { calls } = fakeFetch({
      'POST /api/admin/customers/7/test-credit': { body: { customer: {}, credit: { creditNo: '1', amount: '5', availableBefore: '0', availableAfter: '5', duplicate: false } } },
      'GET /api/admin/recon/platform-accounts': { body: [] },
    });
    const r = await realFc.testCredit(7, '5', '测试', 'adj-req-12345678');
    expect(r.credit.amount).toBe('5');
    expect(calls[0]).toMatchObject({ method: 'POST', url: '/api/admin/customers/7/test-credit', body: { amount: '5', reason: '测试', requestId: 'adj-req-12345678', checked: true } });
    await realFc.listPlatformAccounts();
    expect(calls[1]).toMatchObject({ method: 'GET', url: '/api/admin/recon/platform-accounts' });
  });
});
