/**
 * M1 原型的市场页面：列表（二选一两个按钮、多选项概率条、空状态）、详情（概率、预计赔率、手机和电脑两套结构、下注抽屉 / 右栏、
 * "押 100 → 赢了约回 162.29"）、我的下注（4 个标签、派彩）、公示中提交争议。数据来自 mocks/markets.ts，赔率用 lib/pool.ts。
 */
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { listMarkets } from '../api/markets';
import { setProtoState } from '../api/prototype';
import { resetDb } from '../mocks/db';
import { resetMarkets } from '../mocks/markets';
import { renderPage } from '../test/renderApp';
import MyBets from './bets/MyBets';
import MarketDetail from './markets/MarketDetail';
import Markets from './markets/Markets';

function setDesktop(on: boolean) {
  window.matchMedia = ((query: string) =>
    ({ matches: on && query.includes('min-width: 1024px'), media: query, onchange: null, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false }) as MediaQueryList) as typeof window.matchMedia;
}

async function marketNo(prefix: string, status?: string): Promise<string> {
  const all = await listMarkets({ status: 'all' });
  return all.find((m) => m.title.zhCN.startsWith(prefix) && (!status || m.status === status))!.publicNo;
}

function renderDetail(no: string) {
  return renderPage(
    <Routes>
      <Route path="/markets/:publicNo" element={<MarketDetail />} />
      <Route path="/assets" element={<div>资产页内容</div>} />
    </Routes>,
    `/markets/${no}`,
  );
}

beforeEach(() => {
  localStorage.setItem('fc.session', JSON.stringify({ token: 't', userId: 'U10086', email: 'demo@example.com' }));
});

afterEach(() => {
  setDesktop(false);
  act(() => setProtoState({ scenario: 'normal' }));
  resetDb();
  resetMarkets();
  localStorage.clear();
});

describe('P1 市场列表', () => {
  it('默认只列可下注的：二选一直接两个按钮，多选项显示概率条；已结束的要筛选才显示', async () => {
    renderPage(<Markets />, '/markets');
    const cards = await screen.findAllByTestId('market-card');
    expect(cards.length).toBe(6);
    const btc = cards.find((c) => c.textContent?.includes('BTC 涨还是跌'))!;
    expect(within(btc).getAllByRole('button')).toHaveLength(2);
    expect(within(btc).getByRole('button', { name: /涨：概率 58%/ })).toBeInTheDocument();
    const epl = cards.find((c) => c.textContent?.includes('曼城 vs 利物浦'))!;
    expect(within(epl).getByRole('button', { name: '主胜 曼城：概率 60%，预计赔率 1.63x' })).toBeInTheDocument();
    expect(screen.queryByText(/美国 9 月 CPI/)).not.toBeInTheDocument();
    const eth = cards.find((c) => c.textContent?.includes('ETH 涨还是跌'))!;
    expect(within(eth).getAllByText('—').length).toBeGreaterThan(0);
  });

  it('空数据：显示"暂无市场"', async () => {
    act(() => setProtoState({ scenario: 'empty' }));
    renderPage(<Markets />, '/markets');
    expect(await screen.findByText('暂无市场')).toBeInTheDocument();
  });
});

describe('P2、P3 市场详情和下注', () => {
  it('电脑：选项表 + 右栏；押 100 显示"赢了约回 162.29"，确认后余额和奖池更新（需求 3.4 例子 2）', async () => {
    setDesktop(true);
    renderDetail(await marketNo('曼城'));
    const panel = await screen.findByTestId('bet-panel');
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getAllByText('60.0%').length).toBeGreaterThan(0);
    expect(screen.getAllByText('1.63x').length).toBeGreaterThan(0);
    fireEvent.change(within(panel).getByLabelText('金额（USDT）'), { target: { value: '100' } });
    expect(within(panel).getByText('162.29')).toBeInTheDocument();
    expect(within(panel).getByText(/已扣抽成 3\.28/)).toBeInTheDocument();
    fireEvent.click(within(panel).getByTestId('bet-confirm'));
    expect(await screen.findByText('下注成功')).toBeInTheDocument();
    // 奖池 6,000 + 100
    await waitFor(() => expect(screen.getAllByText('6,100.00').length).toBeGreaterThan(0));
  });

  it('手机：每个选项一个"押注"按钮，点了弹出抽屉；超过可用余额时按钮变成"去充值"', async () => {
    renderDetail(await marketNo('曼城'));
    const btns = await screen.findAllByRole('button', { name: '押注' });
    expect(btns).toHaveLength(3);
    expect(screen.queryByTestId('bet-panel')).not.toBeInTheDocument();
    fireEvent.click(btns[1]);
    const sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByText('平局')).toBeInTheDocument();
    fireEvent.change(within(sheet).getByLabelText('金额（USDT）'), { target: { value: '2000' } });
    expect(within(sheet).getByText('可用余额不足')).toBeInTheDocument();
    fireEvent.click(within(sheet).getByRole('button', { name: '余额不足，去充值' }));
    expect(await screen.findByText('资产页内容')).toBeInTheDocument();
  });

  it('已结算：显示结果、依据、裁决人编号和我的派彩', async () => {
    renderDetail(await marketNo('美国 9 月 CPI'));
    expect(await screen.findByText('结果：否')).toBeInTheDocument();
    // 裁决人是员工编号（数字，后端 S7 PlatformViews 的 resolvedBy），不显示姓名
    expect(screen.getAllByText('1027').length).toBeGreaterThan(0);
    expect(screen.getByText(/9 月 CPI 同比 2\.9%/)).toBeInTheDocument();
    expect(screen.getAllByText(/323\.40/).length).toBeGreaterThan(0);
  });

  it('公示中：有下注的客户可以提交争议，提交后变成"争议处理中"', async () => {
    renderDetail(await marketNo('布伦特福德'));
    fireEvent.click(await screen.findByTestId('dispute-open'));
    const dlg = await screen.findByRole('dialog');
    fireEvent.click(within(dlg).getByRole('button', { name: '提交争议' }));
    expect(within(dlg).getByText('请选择你认为的结果')).toBeInTheDocument();
    fireEvent.click(within(dlg).getByLabelText('主胜 布伦特福德'));
    fireEvent.change(within(dlg).getByLabelText(/理由/), { target: { value: '第 88 分钟的进球应该有效' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '提交争议' }));
    expect(await screen.findByText('争议已提交')).toBeInTheDocument();
    expect((await screen.findAllByText('争议处理中')).length).toBeGreaterThan(0);
  });

  it('公示中：争议主张可以选"应作废"，不能选公示的结果（请求体和后端 S7 一样）', async () => {
    renderDetail(await marketNo('布伦特福德'));
    fireEvent.click(await screen.findByTestId('dispute-open'));
    const dlg = await screen.findByRole('dialog');
    expect(within(dlg).getByLabelText(/^平局/)).toBeDisabled();
    fireEvent.click(within(dlg).getByLabelText('无法判定，应作废'));
    fireEvent.change(within(dlg).getByLabelText(/理由/), { target: { value: '比赛报告和录像对不上' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '提交争议' }));
    expect(await screen.findByText('争议已提交')).toBeInTheDocument();
  });

  it('自动裁决已结算：显示开始价、结束价和参与判定的来源（后端 S6 ResolutionViews）', async () => {
    const no = await marketNo('BTC 涨还是跌', 'settled');
    renderDetail(no);
    expect(await screen.findByText(/开始价 67412\.50000000，结束价 67630\.10000000（Binance BTC\/USDT）/)).toBeInTheDocument();
    expect(screen.getByText('OKX BTC-USDT')).toBeInTheDocument();
    expect(screen.getAllByText('自动裁决').length).toBeGreaterThan(0);
  });
});

describe('P4 我的下注', () => {
  it('4 个标签带笔数；已结算显示押中和派彩、未押中派彩 0；已退回显示原因', async () => {
    renderPage(<MyBets />, '/bets');
    const tabs = await screen.findByRole('tablist');
    await waitFor(() => expect(within(tabs).getAllByRole('tab').map((t) => t.textContent)).toEqual(['进行中4', '待结果2', '已结算2', '已退回1']));
    fireEvent.click(within(tabs).getByRole('tab', { name: /已结算/ }));
    expect(await screen.findByText('押中')).toBeInTheDocument();
    expect(screen.getByText('派彩 323.40')).toBeInTheDocument();
    expect(screen.getByText('未押中')).toBeInTheDocument();
    fireEvent.click(within(tabs).getByRole('tab', { name: /已退回/ }));
    expect(await screen.findByText('市场作废')).toBeInTheDocument();
    expect(screen.getByText('退回 50.00')).toBeInTheDocument();
  });
});
