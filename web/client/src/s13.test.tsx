/**
 * S13 推广功能第一批（开发记录/S13-推广功能1.md）：不登录可浏览（框架、市场详情"登录后押注"、登录后回到原市场和原选项）、
 * 今日场次（P11）、开奖记录和周报（P12）、说明页（P14）、来源参数（MF-1，lib/attribution.ts）。
 * 跑在原型模拟层上（数据结构和后端 S13 一致）；真实接口的契约在后端 S13PublicTest、api/real/real.test.ts。每个页面都看正常、空、出错三种状态（C32、C52）。
 */
import { act, fireEvent, screen, within } from '@testing-library/react';
import { Route, Routes, useLocation } from 'react-router-dom';
import { listMarkets } from './api/markets';
import { setProtoState } from './api/prototype';
import { Layout } from './components/Layout';
import { captureAttribution, clearAttribution, readAttribution, sourceFrom } from './lib/attribution';
import { localDay } from './lib/market';
import { resetOnboarding } from './hooks/useOnboarding';
import { resetDb } from './mocks/db';
import { protoSetKyc } from './mocks/user';
import { resetMarkets } from './mocks/markets';
import HowTo from './pages/HowTo';
import MarketDetail from './pages/markets/MarketDetail';
import Markets from './pages/markets/Markets';
import Results from './pages/results/Results';
import WeekPage from './pages/results/WeekPage';
import Weekly from './pages/results/Weekly';
import { renderPage } from './test/renderApp';

function setDesktop(on: boolean) {
  window.matchMedia = ((query: string) =>
    ({ matches: on && query.includes('min-width: 1024px'), media: query, onchange: null, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false }) as MediaQueryList) as typeof window.matchMedia;
}

const login = () => localStorage.setItem('fc.session', JSON.stringify({ token: 't', userId: 'U10086', email: 'demo@example.com' }));

/** 登录页：显示从哪里来的（state.from） */
function LoginProbe() {
  const loc = useLocation();
  return <div data-testid="login-page">{(loc.state as { from?: string } | null)?.from ?? ''}</div>;
}

async function marketNo(prefix: string): Promise<string> {
  const all = (await listMarkets({ status: 'all' }, 1, 100)).items;
  return all.find((m) => m.title.zhCN.startsWith(prefix))!.publicNo;
}

afterEach(() => {
  vi.useRealTimers();
  setDesktop(false);
  act(() => setProtoState({ scenario: 'normal' }));
  resetDb();
  resetMarkets();
  localStorage.clear();
});

describe('登录后回到市场：新手引导不盖住下注抽屉', () => {
  it('新客户在市场详情页不自动打开新手引导，回到列表才打开', async () => {
    resetOnboarding();
    protoSetKyc('none');
    login();
    const routes = (
      <Routes>
        <Route element={<Layout />}>
          <Route path="/markets" element={<div>市场内容</div>} />
          <Route path="/markets/:no" element={<div>详情内容</div>} />
        </Route>
      </Routes>
    );
    const view = renderPage(routes, '/markets/123?opt=1232');
    expect(await screen.findByText('详情内容')).toBeInTheDocument();
    await act(async () => new Promise((r) => setTimeout(r, 50)));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    view.unmount();
    renderPage(routes, '/markets');
    expect(await screen.findByRole('dialog')).toHaveTextContent('新手引导');
    resetOnboarding();
  });
});

describe('不登录可浏览（MF-4，AC-84）', () => {
  it('框架：没登录时右上角是登录、注册，导航位置不变；登录后是余额和通知', async () => {
    renderPage(
      <Routes>
        <Route element={<Layout />}>
          <Route path="/markets" element={<div>市场内容</div>} />
        </Route>
      </Routes>,
      '/markets',
    );
    expect(await screen.findByText('市场内容')).toBeInTheDocument();
    expect(screen.getAllByTestId('guest-login')[0]).toHaveAttribute('href', '/login');
    expect(screen.getAllByTestId('guest-register')[0]).toHaveAttribute('href', '/register');
    expect(screen.queryByRole('link', { name: '通知' })).not.toBeInTheDocument();
    // 底部导航还是 4 个入口
    expect(within(screen.getAllByRole('navigation', { name: /主导航|导航/ }).at(-1)!).getAllByRole('link')).toHaveLength(4);
  });

  it('手机：点"押注"去登录，带着原市场和原选项', async () => {
    const no = await marketNo('曼城 vs 利物浦');
    renderPage(
      <Routes>
        <Route path="/markets/:publicNo" element={<MarketDetail />} />
        <Route path="/login" element={<LoginProbe />} />
      </Routes>,
      `/markets/${no}`,
    );
    const bets = await screen.findAllByRole('button', { name: '押注' });
    fireEvent.click(bets[1]);
    const probe = await screen.findByTestId('login-page');
    expect(probe.textContent).toMatch(new RegExp(`^/markets/${no}\\?opt=\\d+$`));
    // 没登录看不到"我的下注"
    expect(screen.queryByText('我在本市场的下注')).not.toBeInTheDocument();
  });

  it('电脑：右栏是"登录后押注"，链接带着选中的选项；登录了是下注表单', async () => {
    setDesktop(true);
    const no = await marketNo('曼城 vs 利物浦');
    const { unmount } = renderPage(
      <Routes>
        <Route path="/markets/:publicNo" element={<MarketDetail />} />
      </Routes>,
      `/markets/${no}`,
    );
    const go = await screen.findByTestId('guest-bet');
    expect(go).toHaveAttribute('href', '/login');
    expect(screen.queryByRole('button', { name: /确认押注/ })).not.toBeInTheDocument();
    unmount();
    login();
    renderPage(
      <Routes>
        <Route path="/markets/:publicNo" element={<MarketDetail />} />
      </Routes>,
      `/markets/${no}`,
    );
    expect(await screen.findByRole('button', { name: /确认押注/ })).toBeInTheDocument();
    expect(screen.queryByTestId('guest-bet')).not.toBeInTheDocument();
  });

  it('没开放的市场：没登录时提示登录后可以订阅开放提醒', async () => {
    const all = (await listMarkets({ status: 'all' }, 1, 100)).items;
    const no = all.find((m) => m.status === 'listed' && Date.parse(m.openAt) > Date.now())!.publicNo;
    renderPage(
      <Routes>
        <Route path="/markets/:publicNo" element={<MarketDetail />} />
      </Routes>,
      `/markets/${no}`,
    );
    expect(await screen.findByText(/登录后可以订阅/)).toBeInTheDocument();
    expect(screen.queryByTestId('remind-on')).not.toBeInTheDocument();
  });
});

describe('今日场次（P11，MF-3，AC-82）', () => {
  it('列表顶部按本地的今天列出带标记的市场，时间带 UTC；点进详情', async () => {
    // 原型的场次按"现在"生成（BTC 是下一个整点）。本地 23 点以后跑，下一场就到了明天，今日场次是空的；
    // 所以把时钟固定在本地中午、重新生成原型数据（afterEach 会恢复真实时钟并再重置一次）
    vi.useFakeTimers({ toFake: ['Date'], now: new Date(2026, 9, 27, 12, 0) });
    resetMarkets();
    renderPage(<Markets />, '/markets');
    const slate = await screen.findByTestId('today-slate');
    expect(within(slate).getByText('今日场次')).toBeInTheDocument();
    const items = await within(slate).findAllByTestId('slate-item');
    expect(items.length).toBeGreaterThan(0);
    for (const i of items) expect(i.getAttribute('href')).toMatch(/^\/markets\/\d+$/);
  });

  it('今天没有场次：显示下一场；出错：原因和重试', async () => {
    act(() => setProtoState({ scenario: 'empty' }));
    const { unmount } = renderPage(<Markets />, '/markets');
    const slate = await screen.findByTestId('today-slate');
    expect(await within(slate).findByText(/今天没有场次/)).toBeInTheDocument();
    unmount();
    act(() => setProtoState({ scenario: 'error' }));
    renderPage(<Markets />, '/markets');
    const s2 = await screen.findByTestId('today-slate');
    expect(await within(s2).findByRole('button', { name: '重试' })).toBeInTheDocument();
  });

  it('本地的今天：从本地 0 点到第二天 0 点', () => {
    const d = localDay(new Date(2026, 9, 14, 21, 30));
    expect(Date.parse(d.to) - Date.parse(d.from)).toBe(24 * 3600_000);
    expect(new Date(d.from).getHours()).toBe(0);
    expect(d.label).toBe('10-14');
  });
});

describe('开奖记录和周报（P12，MF-6）', () => {
  it('开奖记录：已结算、已作废都在，结果、依据、奖池、派彩；按状态筛选；电脑是表格', async () => {
    const { unmount } = renderPage(<Results />, '/results');
    const items = await screen.findAllByTestId('result-item');
    expect(items.length).toBeGreaterThanOrEqual(3);
    expect(screen.getAllByText(/已作废/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('派彩合计').length).toBeGreaterThan(0);
    fireEvent.change(screen.getByRole('combobox', { name: '状态' }), { target: { value: 'voided' } });
    const voided = await screen.findAllByTestId('result-item');
    expect(voided.length).toBe(1);
    unmount();
    setDesktop(true);
    renderPage(<Results />, '/results');
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '裁决人编号' })).toBeInTheDocument();
  });

  it('开奖记录：空、出错', async () => {
    act(() => setProtoState({ scenario: 'empty' }));
    const { unmount } = renderPage(<Results />, '/results');
    expect(await screen.findByText('还没有已结束的市场')).toBeInTheDocument();
    unmount();
    act(() => setProtoState({ scenario: 'error' }));
    renderPage(<Results />, '/results');
    expect(await screen.findByRole('button', { name: '重试' })).toBeInTheDocument();
  });

  it('周报列表：每周的合计，本周标"截至现在"；点进一周看这一周的市场；不合法的周说"没有"', async () => {
    renderPage(
      <Routes>
        <Route path="/results/weekly" element={<Weekly />} />
        <Route path="/results/weekly/:week" element={<WeekPage />} />
      </Routes>,
      '/results/weekly',
    );
    const weeks = await screen.findAllByTestId('week-item');
    expect(weeks.length).toBeGreaterThan(0);
    expect(within(weeks[0]).getByText('本周截至现在')).toBeInTheDocument();
    const withMarkets = weeks.find((w) => !within(w).getAllByText(/^\d+$/).every((x) => x.textContent === '0'))!;
    fireEvent.click(withMarkets);
    expect(await screen.findByText('这一周结束的市场')).toBeInTheDocument();
    expect((await screen.findAllByTestId('result-item')).length).toBeGreaterThan(0);
  });

  it('一周：格式不对的周显示"没有这一周的周报"', async () => {
    renderPage(
      <Routes>
        <Route path="/results/weekly/:week" element={<WeekPage />} />
      </Routes>,
      '/results/weekly/2026-W99',
    );
    expect(await screen.findByText('没有这一周的周报')).toBeInTheDocument();
  });
});

describe('说明页（P14）', () => {
  it('怎么玩、怎么充、怎么提三段，文字按语言从后端来；出错有重试', async () => {
    const { unmount } = renderPage(<HowTo />, '/how-to-play');
    expect(await screen.findByRole('heading', { name: '怎么玩' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '怎么充' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '怎么提' })).toBeInTheDocument();
    expect(screen.getByText(/单笔最少提现 100 USDT/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '注册' })).toHaveAttribute('href', '/register');
    unmount();
    act(() => setProtoState({ scenario: 'error' }));
    renderPage(<HowTo />, '/how-to-play');
    expect(await screen.findByRole('button', { name: '重试' })).toBeInTheDocument();
  });
});

describe('来源参数（MF-1，AC-83）', () => {
  it('取参数：不合法的不记；只有 start 也算', () => {
    expect(sourceFrom('?src=tg&cmp=w1&ref=10023')).toEqual({ src: 'tg', cmp: 'w1', ref: '10023' });
    expect(sourceFrom('?src=%3Cx%3E&q=abc')).toBeNull();
    expect(sourceFrom('?start=s-tg__c-w1')).toEqual({ start: 's-tg__c-w1' });
    expect(sourceFrom('')).toBeNull();
  });

  it('先带 src=x、再带 src=tg：首次来源 x、最近一次 tg；30 天后首次来源换成新的；注册后清掉', () => {
    const t0 = Date.parse('2026-10-01T00:00:00Z');
    captureAttribution('?src=x&cmp=w1', t0);
    captureAttribution('?src=tg', t0 + 3 * 86_400_000);
    const a = readAttribution(t0 + 3 * 86_400_000)!;
    expect(a.first).toMatchObject({ src: 'x', cmp: 'w1' });
    expect(a.last).toMatchObject({ src: 'tg' });
    // 没带参数的访问不改
    expect(captureAttribution('?q=btc', t0 + 4 * 86_400_000)).toBeNull();
    expect(readAttribution(t0 + 4 * 86_400_000)!.first).toMatchObject({ src: 'x' });
    // 31 天以后：都过期了
    expect(readAttribution(t0 + 35 * 86_400_000)).toBeUndefined();
    captureAttribution('?src=y', t0 + 35 * 86_400_000);
    expect(readAttribution(t0 + 35 * 86_400_000)!.first).toMatchObject({ src: 'y' });
    clearAttribution();
    expect(readAttribution()).toBeUndefined();
  });
});
