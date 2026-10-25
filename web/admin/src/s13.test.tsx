/**
 * forecast S13 后台（MF-1、MF-3、MF-10）：上架时的同时开放上限（"仍要上架"）和"列入今日场次"、详情页加入 / 移出今日场次、列表"只看今日场次"、
 * 仪表盘的同时开放和今日场次、参数设置的"场次"一组、场次表的星期、来源统计页（正常、空、出错）和导出；真实接口的路径和参数。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { renderAs, setScenario } from './test/render';
import { getMarket, listMarkets } from './api';
import { resetFcMocks } from './mocks/fc';
import { params } from './mocks/data/db';
import { MarketEditPage } from './pages/markets/MarketEditPage';
import { MarketDetailPage } from './pages/markets/MarketDetailPage';
import { MarketListPage } from './pages/markets/MarketListPage';
import { DashboardPage } from './pages/DashboardPage';
import { ParamsPage } from './pages/params/ParamsPage';
import { GrowthPage } from './pages/growth/GrowthPage';
import { formatSlot, parseSlot } from './utils/market';
import * as real from './api/realFc';
import { fakeFetch } from './test/fakeFetch';

beforeEach(() => resetFcMocks());
afterEach(() => {
  setScenario('normal');
  vi.unstubAllGlobals();
  const p = params.find((x) => x.key === 'market.maxOpen');
  if (p) p.value = '2';
});

async function submittedDraft(): Promise<string> {
  const page = await listMarkets({ status: 'draft', submitted: true, page: 1, pageSize: 20 });
  return page.items[0].publicNo;
}

function renderEdit(no: string) {
  renderAs(
    <Routes>
      <Route path="/markets/:no/edit" element={<MarketEditPage />} />
      <Route path="/markets/:no" element={<div>详情页</div>} />
    </Routes>,
    { path: `/markets/${no}/edit` },
  );
}

describe('上架：同时开放上限和今日场次（MF-3，AC-81）', () => {
  it('超过上限时提示"当前已开放 X 个，上限 Y 个"，勾选"仍要上架"后才能上架；勾了今日场次的上架后带标记', async () => {
    const no = await submittedDraft();
    renderEdit(no);
    const box = await screen.findByTestId('open-limit');
    expect(within(box).getByText(/当前已开放 \d+ 个，上限 2 个/)).toBeInTheDocument();
    expect(screen.getByText(/再上架一个就超过同时开放上限（2 个）/)).toBeInTheDocument();
    // 人工检查的三项都勾上
    const manual = document.querySelectorAll('.mke-manual input[type="checkbox"]');
    manual.forEach((c) => {
      if (!(c as HTMLInputElement).checked) fireEvent.click(c);
    });
    fireEvent.click(screen.getByRole('checkbox', { name: '列入今日场次' }));
    const publish = screen.getByRole('button', { name: '检查后上架' });
    expect(publish).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox', { name: '仍要上架（超过上限）' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '检查后上架' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: '检查后上架' }));
    expect(await screen.findByText('详情页')).toBeInTheDocument();
    const m = await getMarket(no);
    expect(m.status).toBe('listed');
    expect(m.todaySlate).toBe(true);
  });

  it('没超过上限（上限 0 = 不限）时不出现"仍要上架"', async () => {
    params.find((x) => x.key === 'market.maxOpen')!.value = '0';
    const no = await submittedDraft();
    renderEdit(no);
    expect(await screen.findByText(/当前已开放 \d+ 个，不限/)).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: '仍要上架（超过上限）' })).toBeNull();
  });

  it('详情页：今日场次的标记，已上架的可以移出、再加入', async () => {
    const page = await listMarkets({ status: 'listed', todaySlate: true, page: 1, pageSize: 20 });
    const no = page.items[0].publicNo;
    renderAs(
      <Routes>
        <Route path="/markets/:no" element={<MarketDetailPage />} />
      </Routes>,
      { path: `/markets/${no}` },
    );
    expect(await screen.findByText('今日场次')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /移出今日场次/ }));
    await waitFor(() => expect(document.body.textContent).toContain('加入今日场次'), { timeout: 4000 });
    expect(screen.getByRole('button', { name: /加入今日场次/ })).toBeInTheDocument();
    expect((await getMarket(no)).todaySlate).toBe(false);
  });

  it('列表：题目旁标"今日场次"，可以只看今日场次', async () => {
    renderAs(<MarketListPage />, { path: '/markets' });
    await screen.findAllByText(/BTC 涨还是跌/);
    const before = screen.getAllByText('今日场次').length;
    expect(before).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('checkbox', { name: '只看今日场次' }));
    await waitFor(() => expect(screen.queryByText(/阿森纳 vs 热刺/)).toBeNull());
    expect(screen.getByText(/曼城 vs 利物浦/)).toBeInTheDocument();
  });
});

describe('仪表盘、参数、场次表', () => {
  it('仪表盘：同时开放 X / 上限 Y、今日场次 N（SQ-21）', async () => {
    renderAs(<DashboardPage />, { path: '/' });
    const open = await screen.findByTestId('dash-open');
    expect(within(open).getByText('同时开放 / 上限')).toBeInTheDocument();
    expect(within(open).getByText('/ 2', { exact: false })).toBeInTheDocument();
    // 原型数据已开放 4 个（> 上限 2），标红
    expect(open.className).toContain('stat-red');
    expect(within(screen.getByTestId('dash-slate')).getByText('今日场次（今天 UTC 开始）')).toBeInTheDocument();
  });

  it('参数设置有"场次"一组：同时开放的市场数上限，默认 2 个', async () => {
    renderAs(<ParamsPage />, { path: '/params' });
    expect(await screen.findByText('同时开放的市场数上限')).toBeInTheDocument();
    expect(screen.getByText('2 个')).toBeInTheDocument();
  });

  it('场次表的星期：解析和格式化（和后端 AutogenService.slot / format 同一格式）', () => {
    expect(parseSlot('13:00')).toEqual({ time: '13:00', days: [] });
    expect(parseSlot('13:00@6,1,7')).toEqual({ time: '13:00', days: [1, 6, 7] });
    expect(parseSlot('13:00@1,2,3,4,5,6,7')).toEqual({ time: '13:00', days: [] });
    expect(formatSlot({ time: '20:00', days: [7, 6] })).toBe('20:00@6,7');
    expect(formatSlot({ time: '20:00', days: [1, 2, 3, 4, 5, 6, 7] })).toBe('20:00');
  });
});

describe('来源统计（MF-1、MF-10）', () => {
  it('正常：合计、按注册周和来源（直接访问单独一行）、第 2 周没过完显示"还没到"、市场情况和原因、导出菜单', async () => {
    renderAs(<GrowthPage />, { path: '/growth' });
    expect(await screen.findByText('按注册周和来源')).toBeInTheDocument();
    expect((await screen.findAllByText('tg_ch')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('直接访问').length).toBeGreaterThan(0);
    expect(screen.getAllByText('推荐 kol_amy').length).toBeGreaterThan(0);
    expect(screen.getAllByText('还没到').length).toBeGreaterThan(0);
    expect(screen.getAllByText('延期超过 48 小时').length).toBeGreaterThan(0);
    expect(screen.getByText('有效市场率')).toBeInTheDocument();
    expect(screen.getByText('7.5')).toBeInTheDocument();
    fireEvent.mouseEnter(screen.getByTestId('growth-export'));
    expect(await screen.findByText('按客户（只有客户编号）')).toBeInTheDocument();
  });

  it('按注册来源看：重新取数', async () => {
    renderAs(<GrowthPage />, { path: '/growth' });
    await screen.findByText('按注册周和来源');
    fireEvent.click(screen.getByText('按注册来源'));
    await waitFor(() => expect(screen.getAllByText('tg_ch').length).toBeGreaterThan(0));
  });

  it('空：没有注册、没有结束的市场', async () => {
    setScenario('empty');
    renderAs(<GrowthPage />, { path: '/growth' });
    expect(await screen.findByText('这段时间没有注册')).toBeInTheDocument();
    expect(screen.getAllByText('这段时间没有结束的市场').length).toBeGreaterThan(0);
  });

  it('出错：显示原因和重试', async () => {
    setScenario('error');
    renderAs(<GrowthPage />, { path: '/growth' });
    expect(await screen.findByRole('button', { name: /重试/ })).toBeInTheDocument();
  });
});

describe('S13 真实接口', () => {
  it('上架带 checked / todaySlate / overLimit；同时开放上限；改今日场次；列表带 todaySlate；来源统计', async () => {
    const { calls } = fakeFetch({
      'POST /api/admin/markets/1/publish': { body: { publicNo: '1', status: 'listed', todaySlate: true } },
      'GET /api/admin/markets/open-limit': { body: { open: 3, limit: 2 } },
      'POST /api/admin/markets/1/today-slate': { body: { publicNo: '1', todaySlate: false } },
      'GET /api/admin/markets': { body: { items: [], total: 0, page: 1, pageSize: 20 } },
      'GET /api/admin/growth/report': { body: { cohorts: [] } },
    });
    await real.publishMarket('1', { checked: true, todaySlate: true, overLimit: true });
    expect(calls[0]).toMatchObject({ method: 'POST', url: '/api/admin/markets/1/publish', body: { checked: true, todaySlate: true, overLimit: true } });
    expect(await real.getOpenLimit()).toEqual({ open: 3, limit: 2 });
    await real.setTodaySlate('1', false);
    expect(calls[2]).toMatchObject({ method: 'POST', url: '/api/admin/markets/1/today-slate', body: { todaySlate: false } });
    await real.listMarkets({ todaySlate: true, page: 1, pageSize: 20 });
    expect(calls[3].url).toContain('todaySlate=true');
    await real.getGrowthReport({ from: '2026-09-01', to: '2026-10-25', basis: 'reg' });
    expect(calls[4].url).toBe('/api/admin/growth/report?from=2026-09-01&to=2026-10-25&basis=reg');
  });

  it('导出：带种类、语言、范围；出错时按普通错误抛出', async () => {
    const urls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        urls.push(url);
        if (url.includes('kind=bad')) return { ok: false, status: 400, json: async () => ({ code: 'VALIDATION_FAILED', fields: [{ field: 'kind', code: 'invalid' }] }) } as unknown as Response;
        return { ok: true, status: 200, headers: new Headers({ 'Content-Disposition': 'attachment; filename="growth-sources-recent-now.csv"' }), blob: async () => new Blob(['x']) } as unknown as Response;
      }),
    );
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const create = vi.fn(() => 'blob:x');
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: vi.fn() }));
    await real.exportGrowth('sources', { basis: 'first' }, 'zh');
    expect(urls[0]).toBe('/api/admin/growth/export?kind=sources&lang=zh&basis=first');
    expect(create).toHaveBeenCalled();
    await expect(real.exportGrowth('bad' as never, {}, 'en')).rejects.toMatchObject({ status: 400 });
  });
});
