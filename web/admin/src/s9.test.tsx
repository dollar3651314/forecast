/**
 * forecast S9 后台页面（原型模式，模拟层的数据结构和后端一致）：每个页面看正常数据、空数据、超过一页、加载中、出错五种状态的关键部分，
 * 以及主操作：上架检查、暂停（高危确认）、提议 / 确认（结算预览守恒）、争议处理、结算异常重试、补偿预览、通知模板繁体。
 */
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { renderAs, setScenario } from './test/render';
import { resetFcMocks } from './mocks/fc';
import { setLatency } from './mocks/runtime';
import { DashboardPage } from './pages/DashboardPage';
import { MarketListPage } from './pages/markets/MarketListPage';
import { MarketDetailPage } from './pages/markets/MarketDetailPage';
import { MarketEditPage } from './pages/markets/MarketEditPage';
import { AutogenPage } from './pages/markets/AutogenPage';
import { SportsPage } from './pages/markets/SportsPage';
import { ResolveDeskPage } from './pages/resolve/ResolveDeskPage';
import { DisputesPage } from './pages/resolve/DisputesPage';
import { SettleRunsPage } from './pages/settle/SettleRunsPage';
import { CompensationsPage } from './pages/settle/CompensationsPage';
import { PoolReconPage } from './pages/funds/PoolReconPage';
import { ReportsPage } from './pages/reports/ReportsPage';
import { ParamsPage } from './pages/params/ParamsPage';
import { TemplatesPage } from './pages/notify/TemplatesPage';
import { toCsv } from './utils/csv';
import { bannedIn } from './utils/market';

const no = (i: number) => `1079${String(i).padStart(14, '0')}`;
// 先建了 15 个已结算的加密市场（mocks/fc.ts），之后的市场编号从 16 开始
const MANCITY = no(19);
const CHELSEA = no(24);
const BOE = no(28);
const CPI_SEPT = no(29);

beforeEach(() => resetFcMocks());
afterEach(() => setScenario('normal'));

describe('市场管理列表', () => {
  it('分组带数量、列有奖池和人数、来源；超过一页时有分页', async () => {
    renderAs(<MarketListPage />, { path: '/markets' });
    expect(await screen.findByText('曼城 vs 利物浦（90 分钟胜平负）')).toBeInTheDocument();
    expect(screen.getByText(/^全部 \d+$/)).toBeInTheDocument();
    expect(screen.getByText('待上架 2')).toBeInTheDocument();
    expect(screen.getAllByText('赛程生成').length).toBeGreaterThan(0);
    expect(screen.getAllByText('加密自动生成').length).toBeGreaterThan(0);
    // 32 个市场 > 20：有分页
    expect(screen.getByText(/共 32 条/)).toBeInTheDocument();
  });

  it('待上架页只列已提交的草稿，操作是"检查上架"', async () => {
    renderAs(<MarketListPage review />, { path: '/markets/review' });
    expect(await screen.findByText('勇士 vs 湖人（胜负，含加时）')).toBeInTheDocument();
    expect(screen.queryByText('曼城 vs 利物浦（90 分钟胜平负）')).toBeNull();
    expect(screen.getAllByRole('button', { name: /检查上架/ })).toHaveLength(2);
  });

  it('空数据', async () => {
    setScenario('empty');
    renderAs(<MarketListPage />, { path: '/markets' });
    expect(await screen.findByText('暂无市场，点"新建市场"或从赛程生成')).toBeInTheDocument();
  });

  it('出错时显示原因和重试，重试成功后显示数据', async () => {
    setScenario('error');
    renderAs(<MarketListPage />, { path: '/markets' });
    expect(await screen.findByText(/无法连接服务器/)).toBeInTheDocument();
    setScenario('normal');
    fireEvent.click(await screen.findByRole('button', { name: '重试' }));
    expect(await screen.findByText('曼城 vs 利物浦（90 分钟胜平负）')).toBeInTheDocument();
  });

  it('加载慢时先显示表格的加载状态', async () => {
    setLatency({ min: 0, max: 0, slow: 300 });
    setScenario('slow');
    try {
      const { container } = renderAs(<MarketListPage />, { path: '/markets' });
      expect(container.querySelector('.ant-spin')).not.toBeNull();
      expect(await screen.findByText('曼城 vs 利物浦（90 分钟胜平负）', {}, { timeout: 3000 })).toBeInTheDocument();
    } finally {
      setLatency({ min: 0, max: 0, slow: 0 });
    }
  });
});

describe('市场详情', () => {
  const at = (n: string) =>
    renderAs(
      <Routes>
        <Route path="/markets/:no" element={<MarketDetailPage />} />
      </Routes>,
      { path: `/markets/${n}` },
    );

  // 这个页面的 DOM 比较大，按角色查（getByRole）在 jsdom 里很慢，这里按文字查
  it('可下注的市场：奖池、前 20 大下注，暂停要高危确认（原因必填）', async () => {
    at(MANCITY);
    expect(await screen.findByText('前 20 大下注')).toBeInTheDocument();
    expect(screen.getByText('总奖池')).toBeInTheDocument();
    fireEvent.click(screen.getByText('暂停下注'));
    fireEvent.click(await screen.findByText('确认执行'));
    expect(await screen.findByText('请填写操作原因', {}, { timeout: 5000 })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('操作原因'), { target: { value: '数据源中断' } });
    fireEvent.click(screen.getByLabelText(/我已核对/));
    fireEvent.click(screen.getByText('确认执行'));
    expect(await screen.findByText('恢复下注', {}, { timeout: 5000 })).toBeInTheDocument();
  }, 40000);

  it('已结算的市场：补偿预览列出每个客户应补多少', async () => {
    at(CPI_SEPT);
    // 权限（当前管理员）加载完以后按钮才可用；加载前后页面会重新渲染，每次重新查
    const enabled = () => screen.getByText('发起补偿').closest('button') as HTMLButtonElement;
    await waitFor(() => expect(enabled()).toBeEnabled(), { timeout: 10000 });
    const btn = enabled();
    fireEvent.click(btn);
    await waitFor(() => expect(document.querySelector('.ant-modal')).not.toBeNull(), { timeout: 5000 });
    const modal = document.querySelector('.ant-modal') as HTMLElement;
    fireEvent.click(within(modal).getByText('否'));
    expect(await screen.findByTestId('comp-plan', {}, { timeout: 5000 })).toHaveTextContent(/补偿合计 .* USDT/);
  }, 40000);
});

describe('新建市场草稿', () => {
  it('必填项没填时清单标红，不能提交上架', async () => {
    renderAs(
      <Routes>
        <Route path="/markets/new" element={<MarketEditPage />} />
      </Routes>,
      { path: '/markets/new' },
    );
    const list = await screen.findByTestId('checklist');
    expect(within(list).getByText('题目三种语言齐全').closest('li')).toHaveClass('bad');
    expect(screen.getByRole('button', { name: '提交上架' })).toBeDisabled();
  });
});

describe('裁决台', () => {
  it('待确认的提议：结算预览守恒（9,799.99 + 199.99 + 0.02 的例子），确认要高危确认', async () => {
    renderAs(<ResolveDeskPage />, { path: `/resolve?market=${CHELSEA}` });
    const pv = await screen.findByTestId('settle-preview');
    expect(pv).toHaveTextContent('守恒：等于奖池合计');
    expect(screen.getByTestId('proposal')).toHaveTextContent('官方比分 2:1');
    fireEvent.click(screen.getByRole('button', { name: '确认结果' }));
    expect(await screen.findByRole('dialog')).toHaveTextContent('确认结果（进入公示）');
  });

  it('待提议：提议表单一次列出全部错误', async () => {
    renderAs(<ResolveDeskPage />, { path: '/resolve?tab=awaiting_proposal' });
    const form = await screen.findByTestId('proposal-form');
    fireEvent.click(within(form).getByRole('button', { name: '提交提议' }));
    expect(await screen.findByText(/依据链接不能为空.*说明不能为空|说明不能为空.*依据链接不能为空/)).toBeInTheDocument();
  });

  it('分组数量；空数据', async () => {
    renderAs(<ResolveDeskPage />, { path: '/resolve' });
    expect(await screen.findByText('自动裁决失败', { selector: '.rs-stage span' })).toBeInTheDocument();
    act(() => setScenario('empty'));
    expect(await screen.findByText('没有待处理的市场')).toBeInTheDocument();
  });
});

describe('公示与争议', () => {
  it('争议中的市场：处理表单（结论必填），维持后进入已结算', async () => {
    renderAs(<DisputesPage />, { path: `/resolve/disputes?market=${BOE}` });
    const panel = await screen.findByTestId('dispute-decide');
    expect(screen.getByText('央行声明里写了上调 25 个基点，公示的结果不对。')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: '提交结论' })).toBeDisabled();
    fireEvent.change(within(panel).getByRole('textbox'), { target: { value: '以央行官网声明为准，维持原结果' } });
    fireEvent.click(within(panel).getByRole('button', { name: '提交结论' }));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByLabelText('操作原因'), { target: { value: '复核官网' } });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: '确认执行' }));
    await waitFor(() => expect(screen.queryByTestId('dispute-decide')).toBeNull(), { timeout: 8000 });
  });
});

describe('结算、对账、补偿、报表', () => {
  it('结算异常可以筛出来；点开看明细和重试', async () => {
    renderAs(<SettleRunsPage />, { path: '/settle/runs?status=abnormal' });
    const cell = await screen.findByText('结算异常', { selector: '.pill' });
    fireEvent.click(cell);
    expect(await screen.findByText('结算异常：已停止派彩')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /处理后重试/ })).toBeInTheDocument();
  });

  it('彩池对账：待处理的差异可以标记已处理（说明必填）', async () => {
    renderAs(<PoolReconPage />, { path: '/funds/pool-recon' });
    expect(await screen.findByText('全部市场的奖池合计和账本"下注锁定"合计差 0.01')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '标记已处理' })).toBeInTheDocument();
  });

  it('补偿单列表和报表（收入和补偿支出分开）', async () => {
    renderAs(<CompensationsPage />, { path: '/settle/compensations' });
    expect(await screen.findByText('统计局修正了 9 月 CPI，正确结果应为"否"')).toBeInTheDocument();
  });

  it('报表：合计和按天；出错时显示原因', async () => {
    renderAs(<ReportsPage />, { path: '/reports' });
    expect(await screen.findAllByText('平台收入')).not.toHaveLength(0);
    expect(screen.getByText('补偿支出', { selector: '.k' })).toBeInTheDocument();
    act(() => setScenario('error'));
    expect(await screen.findAllByText(/无法连接服务器/)).not.toHaveLength(0);
  });

  it('CSV：逗号、引号、换行转义', () => {
    expect(toCsv([['a,b', 'say "hi"'], ['x\ny', 1]])).toBe('"a,b","say ""hi"""\n"x\ny",1');
  });
});

describe('仪表盘、参数设置、自动生成、体育', () => {
  it('仪表盘：待处理事项和今日 / 近 7 天；空数据显示 0', async () => {
    renderAs(<DashboardPage />, { path: '/dashboard' });
    expect(await screen.findByTestId('todo-review')).toHaveTextContent('待上架2');
    expect(screen.getByText('近 7 天')).toBeInTheDocument();
    act(() => setScenario('empty'));
    await waitFor(() => expect(screen.getByTestId('todo-review')).toHaveTextContent('待上架0'));
  });

  it('参数设置：裁决时限、体育两组；单笔最小提现在提现组', async () => {
    renderAs(<ParamsPage />, { path: '/params' });
    expect(await screen.findByText('裁决时限（平台裁决）')).toBeInTheDocument();
    expect(screen.getByText('体育赛程')).toBeInTheDocument();
    expect(await screen.findByText('单笔最小提现')).toBeInTheDocument();
  });

  it('自动生成：场次同时显示北京时间；启用中的不能改', async () => {
    renderAs(<AutogenPage />, { path: '/markets/autogen' });
    // S13：场次带星期（按 UTC 日期），没选星期 = 每天；UTC 16:00 以后在北京是次日
    expect(await screen.findByText('20:00 UTC · 北京 04:00（次日） · 周六、周日')).toBeInTheDocument();
    expect(await screen.findByText('13:00 UTC · 北京 21:00 · 每天')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /编辑/ })[0]).toBeDisabled();
  });

  it('体育：赛程、对应状态、处理抽屉里的候选', async () => {
    renderAs(<SportsPage />, { path: '/markets/sports' });
    expect(await screen.findByText('Liverpool FC vs Brighton & Hove Albion FC')).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: '处理' })[1]);
    expect(await screen.findAllByText('确认是同一场')).toHaveLength(2);
  });
});

describe('通知模板（S9：编号、受众、繁体、按角色发）', () => {
  it('列出需求编号和收件人；员工模板按角色', async () => {
    renderAs(<TemplatesPage />, { path: '/notify/templates' });
    expect(await screen.findByText('#18')).toBeInTheDocument();
    expect(screen.getByText('有 resolve:confirm 的管理员')).toBeInTheDocument();
    expect(screen.getAllByText('简体 · 英文 · 繁体').length).toBeGreaterThan(0);
  });

  it('不能用的说法（F-12、F-15）', () => {
    expect(bannedIn('押中稳赚，别错过')).toEqual(['稳赚', '别错过']);
    expect(bannedIn('Your payout of 10 USDT is in your balance.')).toEqual([]);
  });
});
