/**
 * forecast S10 后台页面（原型模式，模拟层的数据结构和后端一致）：风控告警列表的五种状态（正常、空、超过一页、加载中、出错）、筛选、处理（已处理 / 误报要写说明）；
 * 客户详情的员工登记（高危确认，登记后提示退回的下注）和关联账户；参数设置的"风控"一组；自动审批 R14、R15 的规则定义和判定说明。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { renderAs, setScenario } from './test/render';
import { getRiskAlertCounts, listRiskAlerts } from './api';
import { resetFcMocks } from './mocks/fc';
import { setLatency } from './mocks/runtime';
import { customers } from './mocks/data/db';
import { RiskAlertsPage, riskSummary } from './pages/risk/RiskAlertsPage';
import { CustomerDetailPage } from './pages/customers/CustomerDetailPage';
import { ParamsPage } from './pages/params/ParamsPage';
import { AUTO_RULES } from './config/autoApprove';
import { ruleLine } from './pages/funds/AutoReview';
import { MENU } from './config/menu';
import i18n from './i18n';

const t = i18n.t.bind(i18n);

beforeEach(() => resetFcMocks());
afterEach(() => setScenario('normal'));

describe('风控告警', () => {
  it('默认看新的告警：类型、客户、市场、金额、判定依据；分组带待处理数量', async () => {
    renderAs(<RiskAlertsPage />, { path: '/risk/alerts' });
    expect(await screen.findAllByText('尾盘大额')).not.toHaveLength(0);
    const counts = await getRiskAlertCounts();
    expect(await screen.findByText(`全部类型 · ${counts.open}`)).toBeInTheDocument();
    expect(screen.getByText(`关联账户对押 · ${counts.openByKind.linked_opposite}`)).toBeInTheDocument();
    expect(screen.getAllByText(/截止前 \d+ 秒下注，占下注后奖池 [\d.]+%/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/近 30 天充值 2,000.00，之后有效下注/).length).toBeGreaterThan(0);
    // 新的告警都能处理
    expect(screen.getAllByRole('button', { name: '标记已查看' }).length).toBeGreaterThan(0);
  });

  it('全部状态超过一页时有分页；按类型、客户筛选', async () => {
    renderAs(<RiskAlertsPage />, { path: '/risk/alerts' });
    await screen.findAllByText('尾盘大额');
    fireEvent.click(screen.getByText('全部状态'));
    expect(await screen.findByText(/共 26 条/)).toBeInTheDocument();
    fireEvent.click(screen.getByText(/^新账户押中冷门 · \d+$/));
    await waitFor(() => expect(screen.queryByText(/共 26 条/)).toBeNull());
    const page = await listRiskAlerts({ kind: 'new_account_longshot', page: 1, pageSize: 20 });
    expect(page.total).toBeGreaterThan(0);
    expect((await screen.findAllByText(/注册第 2 天押中 6.82 倍的结果/)).length).toBe(page.items.length);
    fireEvent.change(screen.getByLabelText('客户'), { target: { value: 'no-such-customer' } });
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(await screen.findByText('没有符合筛选条件的告警')).toBeInTheDocument();
  });

  it('已处理、误报要写处理说明；处理后从"新"的列表里消失', async () => {
    renderAs(<RiskAlertsPage />, { path: '/risk/alerts' });
    await screen.findAllByText('尾盘大额');
    const before = (await listRiskAlerts({ status: 'new', page: 1, pageSize: 100 })).total;
    fireEvent.click(screen.getAllByRole('button', { name: '误报' })[0]);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/处理说明/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: '同一家庭的两个账户' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /确\s*定|确认|提交/ }));
    await waitFor(async () => expect((await listRiskAlerts({ status: 'new', page: 1, pageSize: 100 })).total).toBe(before - 1));
    const fp = await listRiskAlerts({ status: 'false_positive', page: 1, pageSize: 100 });
    expect(fp.items.some((a) => a.note === '同一家庭的两个账户')).toBe(true);
  });

  it('空数据', async () => {
    setScenario('empty');
    renderAs(<RiskAlertsPage />, { path: '/risk/alerts' });
    expect(await screen.findByText('没有新的告警')).toBeInTheDocument();
    expect(screen.getByText('全部类型 · 0')).toBeInTheDocument();
  });

  it('出错时显示原因和重试', async () => {
    setScenario('error');
    renderAs(<RiskAlertsPage />, { path: '/risk/alerts' });
    expect(await screen.findByText(/无法连接服务器/)).toBeInTheDocument();
    setScenario('normal');
    fireEvent.click((await screen.findAllByRole('button', { name: '重试' }))[0]);
    expect(await screen.findAllByText('尾盘大额')).not.toHaveLength(0);
  });

  it('加载慢时先显示表格的加载状态', async () => {
    setLatency({ min: 0, max: 0, slow: 300 });
    setScenario('slow');
    try {
      const { container } = renderAs(<RiskAlertsPage />, { path: '/risk/alerts' });
      expect(container.querySelector('.ant-spin')).not.toBeNull();
      expect((await screen.findAllByText('尾盘大额', {}, { timeout: 3000 })).length).toBeGreaterThan(0);
    } finally {
      setLatency({ min: 0, max: 0, slow: 0 });
    }
  });

  it('判定依据的说明（四种）', () => {
    const base = { id: '1', userId: '1', customer: null, marketNo: null, market: null, amount: null, status: 'new' as const, note: null, handledBy: null, handledAt: null, createdAt: '2026-10-25T08:00:00Z' };
    expect(riskSummary(t, { ...base, kind: 'late_large_bet', detail: { pct: '35.9', poolAfter: '4463.27', secondsBeforeClose: '300', limitPct: '30', limitMinutes: '5', limitAmount: '100.00' } })).toBe(
      // S16（SQ-24）：阈值加单笔最低金额
      '截止前 300 秒下注，占下注后奖池 35.9%（奖池 4,463.27；阈值：截止前 5 分钟内、≥ 30%、单笔 ≥ 100.00）',
    );
    expect(riskSummary(t, { ...base, kind: 'linked_opposite', detail: { linkedUsers: '7,9' } })).toBe('关联账户 7、9 在同一市场押了不同的选项');
    expect(riskSummary(t, { ...base, kind: 'new_account_longshot', detail: { odds: '5.75', accountDays: '6', limitDays: '7' } })).toBe('注册第 6 天押中 5.75 倍的结果（阈值：注册 7 天内）');
    expect(riskSummary(t, { ...base, kind: 'deposit_no_bet', detail: { deposit: '500', stake: '499.99', multiple: '1', withdrawalNo: '88' } })).toBe(
      '近 30 天充值 500.00，之后有效下注 499.99（要求 1 倍）就申请提现 88',
    );
  });

  it('菜单：风控告警是一级菜单，带待处理角标，权限 risk_alert:view', () => {
    const m = MENU.find((x) => x.key === 'risk-alerts')!;
    expect(m.path).toBe('/risk/alerts');
    expect(m.permission).toBe('risk_alert:view');
    expect(m.badge).toBe('riskAlerts');
  });
});

describe('客户详情：员工账户和关联账户', () => {
  const page = (id: number) =>
    renderAs(
      <Routes>
        <Route path="/customers/:id" element={<CustomerDetailPage />} />
      </Routes>,
      { path: `/customers/${id}` },
    );

  it('登记为员工：高危确认（原因必填），提示退回的下注；登记后显示员工标记', { timeout: 40000 }, async () => {
    const c = customers[0];
    c.staff = false;
    page(c.id);
    expect(await screen.findByText('最近登录 IP')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /登记为员工/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/登记后这个账户不能下注/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '确认登记' }));
    expect(await within(dialog).findByText('请填写操作原因', undefined, { timeout: 5000 })).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('操作原因'), { target: { value: '运营同事的账户' } });
    const check = within(dialog).queryByLabelText(/我已核对/);
    if (check) fireEvent.click(check);
    fireEvent.click(within(dialog).getByRole('button', { name: '确认登记' }));
    expect(await screen.findByText('已退回 2 个市场的 3 笔下注，合计 450.00 USDT')).toBeInTheDocument();
    expect(await screen.findByText('这是登记的员工账户：不能下注')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /取消员工登记/ })).toBeInTheDocument();
    c.staff = false;
  });

  it('关联账户：原因、共同的值；没有时显示空', async () => {
    page(customers[0].id);
    expect(await screen.findByText('关联账户')).toBeInTheDocument();
    expect(await screen.findByText('同设备')).toBeInTheDocument();
    expect(screen.getByText('同 IP（注册 / 登录）')).toBeInTheDocument();
    expect(screen.getByText('45.77.12.9')).toBeInTheDocument();
  });

  it('没有关联账户', async () => {
    const odd = customers.find((c) => c.id % 2 === 1)!;
    page(odd.id);
    expect(await screen.findByText('没有发现关联账户')).toBeInTheDocument();
  });
});

describe('参数设置：风控一组', () => {
  it('尾盘大额、押中冷门、关联上限的默认值', async () => {
    renderAs(<ParamsPage />, { path: '/params' });
    expect(await screen.findByText('尾盘大额：截止前多少分钟内')).toBeInTheDocument();
    expect(screen.getByText('风控告警')).toBeInTheDocument();
    expect(screen.getByText('押中冷门：赔率不低于')).toBeInTheDocument();
    expect(screen.getByText('5 倍')).toBeInTheDocument();
    expect(screen.getByText('7 天')).toBeInTheDocument();
    expect(screen.getByText('10 个')).toBeInTheDocument();
  });
});

describe('提现自动审批 R14、R15（需求第 8.2 节）', () => {
  it('规则定义：R14 近 30 天、盈利门槛 100；R15 近 7 天', () => {
    expect(AUTO_RULES.map((r) => r.code)).toEqual(['R1', 'R2', 'R3', 'R4', 'R5', 'R6', 'R7', 'R8', 'R9', 'R10', 'R11', 'R12', 'R13', 'R14', 'R15']);
    expect(AUTO_RULES.find((r) => r.code === 'R14')!.params.map((p) => `${p.key}=${p.defaultValue}`)).toEqual(['days=30', 'minProfit=100']);
    expect(AUTO_RULES.find((r) => r.code === 'R15')!.params.map((p) => `${p.key}=${p.defaultValue}`)).toEqual(['days=7']);
  });

  it('判定说明', () => {
    expect(ruleLine(t, { code: 'R14', status: 'failed', values: { profit: '199.5', markets: '1', linked: '1', days: '30', limit: '100' } })).toBe(
      '近 30 天关联账户对押的市场 1 个（关联账户 1 个），盈利 199.50 > 100',
    );
    expect(ruleLine(t, { code: 'R15', status: 'failed', values: { count: '2', days: '7' } })).toBe('近 7 天有 2 条没处理的可疑告警（到"风控告警"处理）');
    expect(ruleLine(t, { code: 'R8', status: 'failed', values: { volume: '600', deposit: '1000', ratio: '0.6', limit: '1' } })).toBe(
      '充值后有效下注 600 / 充值 1,000 = 0.6 倍 < 1 倍',
    );
  });
});
