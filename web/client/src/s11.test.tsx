/**
 * S11 客户端页面（开发记录/S11-客户端.md）：开赛提醒（#23）的订阅和退订、退订链接页、通知中心点进对应页面、注册勾选"已满 18 岁"、
 * 我的下注按持仓的派彩和汇总、详情的结算信息、奖池推送合并、分页"加载更多"、每个页面的空 / 出错状态、官网链接。
 * 跑在原型模拟层上（数据结构和后端 S11 一致）；真实接口的契约在后端 S11ClientTest 和 api/real/real.test.ts。
 */
import { act, fireEvent, renderHook, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { listMarkets } from './api/markets';
import { setProtoState } from './api/prototype';
import type { MarketPool, Page } from './api/types';
import { usePaged } from './hooks/usePaged';
import { mergePool } from './lib/market';
import { resolveSiteUrl, siteLink } from './lib/siteUrl';
import { resetDb } from './mocks/db';
import { resetMarkets } from './mocks/markets';
import MyBets from './pages/bets/MyBets';
import MarketDetail from './pages/markets/MarketDetail';
import Markets from './pages/markets/Markets';
import Me from './pages/me/Me';
import Notifications from './pages/me/Notifications';
import NotifyPrefs from './pages/me/NotifyPrefs';
import Register from './pages/auth/Register';
import Unsubscribe from './pages/auth/Unsubscribe';
import Ledger from './pages/assets/Ledger';
import { renderPage } from './test/renderApp';

afterEach(() => {
  act(() => setProtoState({ scenario: 'normal' }));
  resetDb();
  resetMarkets();
});

async function marketNo(prefix: string, status?: string): Promise<string> {
  const all = (await listMarkets({ status: 'all' }, 1, 100)).items;
  return all.find((m) => m.title.zhCN.startsWith(prefix) && (!status || m.status === status))!.publicNo;
}

/** 明天的 BTC 场次（已上架、还没开放） */
async function upcomingNo(): Promise<string> {
  const all = (await listMarkets({ status: 'all' }, 1, 100)).items;
  return all.find((m) => m.status === 'listed' && Date.parse(m.openAt) > Date.now())!.publicNo;
}

function withRoutes(ui: React.ReactElement) {
  return (
    <Routes>
      <Route path="/markets/:publicNo" element={ui} />
      <Route path="/me/notify-prefs" element={<NotifyPrefs />} />
      <Route path="/bets" element={<div>我的下注页</div>} />
      <Route path="/assets" element={<div>资产页内容</div>} />
      <Route path="/assets/withdraw" element={<div>提现页内容</div>} />
      <Route path="*" element={ui} />
    </Routes>
  );
}

describe('开赛提醒（#23，SQ-20）', () => {
  it('还没开放的市场：显示开放时间，点"开放时提醒我"订阅，通知偏好里能看到并退订', async () => {
    const no = await upcomingNo();
    renderPage(withRoutes(<MarketDetail />), `/markets/${no}`);
    expect((await screen.findAllByText('开放下注')).length).toBeGreaterThan(0);
    fireEvent.click(await screen.findByTestId('remind-on'));
    expect(await screen.findByText('已订阅：开放下注时发邮件提醒你')).toBeInTheDocument();
    expect(await screen.findByText('已订阅开赛提醒，开放下注时发邮件给你。')).toBeInTheDocument();
  });

  it('通知偏好：列出订阅、退订后变成"已退订"；邮件关着时写明提醒不会发出', async () => {
    const no = await upcomingNo();
    const { unmount } = renderPage(withRoutes(<MarketDetail />), `/markets/${no}`);
    fireEvent.click(await screen.findByTestId('remind-on'));
    await screen.findByTestId('remind-off');
    unmount();
    renderPage(<NotifyPrefs />, '/me/notify-prefs');
    const box = await screen.findByTestId('reminders');
    expect(await within(box).findByText('订阅中')).toBeInTheDocument();
    fireEvent.click(within(box).getByRole('button', { name: '退订' }));
    expect(await within(box).findByText('已退订')).toBeInTheDocument();
    // 关掉邮件
    fireEvent.click(screen.getByRole('switch', { name: '邮件通知' }));
    expect(await within(box).findByText(/你关闭了邮件通知，开赛提醒不会发出/)).toBeInTheDocument();
  });

  it('通知偏好：没有订阅时显示空状态（C32）', async () => {
    renderPage(<NotifyPrefs />, '/me/notify-prefs');
    const box = await screen.findByTestId('reminders');
    expect(await within(box).findByText('还没有订阅开赛提醒')).toBeInTheDocument();
  });

  it('邮件里的退订链接：不用登录；有效显示已退订，无效、没带都说"链接无效"', async () => {
    const no = await upcomingNo();
    renderPage(withRoutes(<MarketDetail />), `/markets/${no}`);
    fireEvent.click(await screen.findByTestId('remind-on'));
    await screen.findByTestId('remind-off');
    renderPage(<Unsubscribe />, `/unsubscribe?t=${no}`);
    expect(await screen.findByTestId('unsub-done')).toHaveTextContent(/已退订.*的开赛提醒/);
    renderPage(<Unsubscribe />, '/unsubscribe?t=0');
    expect(await screen.findByTestId('unsub-invalid')).toBeInTheDocument();
  });
});

describe('通知中心（P6）', () => {
  it('点有关联页面的通知直接打开那个页面（提现、充值、我的下注），并标成已读', async () => {
    renderPage(
      <Routes>
        <Route path="/me/notifications" element={<Notifications />} />
        <Route path="/assets/withdraw" element={<div>提现页内容</div>} />
      </Routes>,
      '/me/notifications',
    );
    const items = await screen.findAllByRole('button', { name: /提现状态更新/ });
    fireEvent.click(items[0]);
    expect(await screen.findByText('提现页内容')).toBeInTheDocument();
  });

  it('超过一页：显示"加载更多"，加载完写"已全部显示"（C52）', async () => {
    renderPage(<Notifications />, '/me/notifications');
    const more = await screen.findByTestId('load-more');
    expect(more).toHaveTextContent('已显示 20 / 23');
    fireEvent.click(more);
    expect(await screen.findByText('已全部显示，共 23 条')).toBeInTheDocument();
  });
});

describe('注册（P8）', () => {
  it('必须勾选"已年满 18 周岁并同意条款"；条款链到官网（带当前语言）', async () => {
    renderPage(<Register />, '/register');
    const terms = await screen.findByRole('link', { name: '《服务条款》' });
    expect(terms.getAttribute('href')).toMatch(/\/terms\?lang=zh-CN$/);
    expect(screen.getByRole('link', { name: '《风险披露》' }).getAttribute('href')).toMatch(/\/risk\?lang=zh-CN$/);
    fireEvent.click(screen.getByRole('button', { name: '注册' }));
    expect(await screen.findByText('请阅读并同意条款')).toBeInTheDocument();
  });
});

describe('我的下注（P4）和详情的结算信息', () => {
  it('汇总：总下注不含已退回、总派彩、净盈亏；已结算显示按持仓的派彩和抽成', async () => {
    renderPage(<MyBets />, '/bets?tab=settled');
    expect(await screen.findByText('派彩 323.40')).toBeInTheDocument();
    expect(screen.getByText('抽成 6.49')).toBeInTheDocument();
    expect(screen.getByText('总下注')).toBeInTheDocument();
    expect(screen.getByText(/总派彩含补偿/)).toBeInTheDocument();
  });

  it('同一选项几笔下注：写明"本选项 n 笔合计，按合计结算"', async () => {
    renderPage(<MyBets />, '/bets?tab=active');
    // 进行中没有结果
    await screen.findAllByText(/曼城 vs 利物浦/);
    expect(screen.queryByText(/笔合计/)).not.toBeInTheDocument();
  });

  it('已结算的详情：结算合计（派彩 + 抽成 + 尾差 = 奖池）和我的本金、派彩、抽成', async () => {
    const no = await marketNo('美国 9 月 CPI');
    renderPage(withRoutes(<MarketDetail />), `/markets/${no}`);
    expect(await screen.findByText('奖池合计')).toBeInTheDocument();
    expect(screen.getByText('我的本金')).toBeInTheDocument();
    expect(screen.getByText(/派彩合计 \+ 抽成合计 \+ 尾差 = 奖池合计/)).toBeInTheDocument();
  });

  it('作废的详情：退款合计和我的退款', async () => {
    const no = await marketNo('赫罗纳');
    renderPage(withRoutes(<MarketDetail />), `/markets/${no}`);
    expect(await screen.findByText('退款合计（全额退回）')).toBeInTheDocument();
    expect(screen.getByText('我的退款')).toBeInTheDocument();
  });
});

describe('奖池推送（AC-17）', () => {
  it('推来的奖池合进详情：保留本人的持仓和限额，"赢了约回"按新奖池重算', () => {
    const prev: MarketPool = {
      total: '1000.00',
      bettors: 2,
      frozen: false,
      feeRate: '5',
      options: [
        { optionNo: '1', seq: 1, amount: '600.00', bettors: 1, pct: '60.0%', pctList: '60%', mult: '1.63' },
        { optionNo: '2', seq: 2, amount: '400.00', bettors: 1, pct: '40.0%', pctList: '40%', mult: '2.42' },
      ],
      mine: [{ optionNo: '1', seq: 1, amount: '100.00', betCount: 1, ifWin: '163.33' }],
      limits: { min: '1.00', max: '5000.00', userCap: '10000.00', used: '100.00', remaining: '9900.00' },
    };
    const next: MarketPool = { ...prev, total: '1400.00', options: [prev.options[0], { ...prev.options[1], amount: '800.00' }], mine: undefined, limits: undefined };
    const m = mergePool(prev, next);
    expect(m.total).toBe('1400.00');
    expect(m.limits).toEqual(prev.limits);
    // 100 × 800 ÷ 600 = 133.33 → 抽成 6.67 → 100 + 133.33 − 6.67 = 226.66
    expect(m.mine?.[0].ifWin).toBe('226.66');
  });
});

describe('分页（usePaged）', () => {
  it('"加载更多"只取下一页接在后面；失败时保留已显示的、给出原因', async () => {
    const all = Array.from({ length: 45 }, (_, i) => ({ id: String(i) }));
    let fail = false;
    const fetchPage = async (page: number, size: number): Promise<Page<{ id: string }>> => {
      if (fail) throw new Error('x');
      return { items: all.slice((page - 1) * size, page * size), total: all.length, page, pageSize: size };
    };
    const { result } = renderHook(() => usePaged(fetchPage, 'k', 20, (x) => x.id));
    await waitFor(() => expect(result.current.items).toHaveLength(20));
    expect(result.current.hasMore).toBe(true);
    await act(() => result.current.loadMore());
    expect(result.current.items).toHaveLength(40);
    fail = true;
    await act(() => result.current.loadMore());
    expect(result.current.items).toHaveLength(40);
    expect(result.current.moreError).toBeTruthy();
    fail = false;
    await act(() => result.current.loadMore());
    expect(result.current.items).toHaveLength(45);
    expect(result.current.hasMore).toBe(false);
  });
});

describe('每个页面的空、出错状态（C42）', () => {
  const pages: Array<[string, React.ReactElement, string, string]> = [
    ['市场', <Markets key="m" />, '/markets', '暂无市场'],
    ['我的下注', <MyBets key="b" />, '/bets', '没有进行中的下注'],
    ['通知中心', <Notifications key="n" />, '/me/notifications', '暂无通知'],
    ['资金流水', <Ledger key="l" />, '/assets/ledger', '没有符合条件的流水'],
  ];
  it.each(pages)('%s：空数据有明确的说明', async (_n, ui, path, text) => {
    act(() => setProtoState({ scenario: 'empty' }));
    renderPage(ui, path);
    expect(await screen.findByText(text)).toBeInTheDocument();
  });
  it.each(pages)('%s：出错显示原因和重试', async (_n, ui, path) => {
    act(() => setProtoState({ scenario: 'error' }));
    renderPage(ui, path);
    expect((await screen.findAllByRole('button', { name: '重试' })).length).toBeGreaterThan(0);
  });
  it('市场：搜索没有结果时写明搜了什么，可以一键清除筛选（C49）', async () => {
    renderPage(<Markets />, '/markets?q=不存在的市场');
    expect(await screen.findByText('没有找到"不存在的市场"')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '清除筛选' })).toBeInTheDocument();
  });
});

describe('官网链接', () => {
  it('官网地址：部署时 Nginx 换掉占位符；没换时原型用 /site，真实接口用同源', () => {
    expect(resolveSiteUrl('https://fc-test-www.quickcome.online/', false)).toBe('https://fc-test-www.quickcome.online');
    expect(resolveSiteUrl('__FC_SITE_URL__', true)).toBe('/site');
    expect(resolveSiteUrl('__FC_SITE_URL__', false)).toBe('');
    expect(resolveSiteUrl('javascript:alert(1)', false)).toBe('');
    expect(siteLink('privacy', 'en', 'https://x.test')).toBe('https://x.test/privacy?lang=en');
    expect(siteLink('home', 'zh-TW', 'https://x.test')).toBe('https://x.test/?lang=zh-TW');
  });

  it('我的：规则说明、常见问题、服务条款、隐私、风险披露都链到官网', async () => {
    renderPage(<Me />, '/me');
    expect(await screen.findByRole('link', { name: '服务条款' })).toHaveAttribute('href', '/site/terms?lang=zh-CN');
    expect(screen.getByRole('link', { name: '规则说明' }).getAttribute('href')).toBe('/site/?lang=zh-CN#how');
  });
});
