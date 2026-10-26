/**
 * S15 客户端（MF-11 SEO 页面；分享码）：数据和文章列表、焦点战页（实时奖池 + 审核过的赛前短文）、BTC 每小时涨跌统计页（来源、时间、口径）、
 * 文章页、找不到的页面、出错重试、页脚入口；分享弹窗说明 ref 是随机分享码、不是客户编号。跑在原型模拟层上（结构和后端 Insights 一致）。
 */
import { act, screen, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { setProtoState } from './api/prototype';
import i18n from './i18n';
import { resetMarkets } from './mocks/markets';
import Insights from './pages/insights/Insights';
import InsightPage from './pages/insights/InsightPage';
import { renderPage } from './test/renderApp';

afterEach(() => {
  act(() => setProtoState({ scenario: 'normal' }));
  resetMarkets();
});

function renderInsight(slug: string) {
  return renderPage(
    <Routes>
      <Route path="/insights/:slug" element={<InsightPage />} />
    </Routes>,
    `/insights/${slug}`,
  );
}

describe('数据和文章（MF-11）', () => {
  it('列表：BTC 统计、焦点战、文章三种，点进去是 /insights/{slug}；页脚有入口', async () => {
    renderPage(<Insights />, '/insights');
    const items = await screen.findAllByTestId('insight-item');
    expect(items.length).toBe(3);
    expect(items[0]).toHaveAttribute('href', '/insights/btc-hourly');
    expect(within(items[0]).getByText('BTC 统计')).toBeInTheDocument();
    expect(items[1].getAttribute('href')).toMatch(/^\/insights\/focus-\d{18}$/);
    expect(screen.getByRole('link', { name: '数据和文章' })).toHaveAttribute('href', '/insights');
  });

  it('列表：空和出错', async () => {
    act(() => setProtoState({ scenario: 'empty' }));
    const { unmount } = renderPage(<Insights />, '/insights');
    expect(await screen.findByText('还没有页面')).toBeInTheDocument();
    unmount();
    act(() => setProtoState({ scenario: 'error' }));
    renderPage(<Insights />, '/insights');
    expect(await screen.findByRole('button', { name: '重试' })).toBeInTheDocument();
  });

  it('焦点战页：奖池、下注人数、每个选项的金额和比例、去市场的链接、审核过的赛前短文；只陈述数据', async () => {
    renderPage(<Insights />, '/insights');
    const slug = (await screen.findAllByTestId('insight-item'))[1].getAttribute('href')!.split('/')[2];
    renderInsight(slug);
    const m = await screen.findByTestId('insight-market');
    expect(within(m).getByText('奖池合计')).toBeInTheDocument();
    expect(within(m).getAllByText(/USDT/).length).toBeGreaterThan(1);
    expect(within(m).getByRole('link', { name: '去看这个市场' })).toHaveAttribute('href', `/markets/${slug.slice('focus-'.length)}`);
    expect(screen.getByRole('heading', { name: '赛前数据' })).toBeInTheDocument();
    expect(screen.getByText('本页只陈述数据，不预测结果，也不构成任何建议。')).toBeInTheDocument();
  });

  it('BTC 统计页：24 小时 / 7 天收涨、按 UTC 小时 24 行、口径和数据来源', async () => {
    renderInsight('btc-hourly');
    const b = await screen.findByTestId('insight-btc');
    expect(within(b).getByText('13 / 24（54.2%）')).toBeInTheDocument();
    expect(within(b).getByText('87 / 168（51.8%）')).toBeInTheDocument();
    expect(b.querySelectorAll('tbody tr').length).toBe(24);
    expect(within(b).getByText(/收盘价 ≥ 开盘价算涨/)).toBeInTheDocument();
    expect(within(b).getByText(/数据来源：Binance/)).toBeInTheDocument();
  });

  it('文章页：分段显示；英文界面显示英文', async () => {
    const { unmount } = renderInsight('article-pool-basics');
    expect(await screen.findByText('所有人的下注放进同一个奖池。')).toBeInTheDocument();
    unmount();
    await act(async () => {
      await i18n.changeLanguage('en');
    });
    try {
      renderInsight('article-pool-basics');
      expect(await screen.findByText('Every stake goes into one pool.')).toBeInTheDocument();
    } finally {
      await act(async () => {
        await i18n.changeLanguage('zh-CN');
      });
    }
  });

  it('不存在的页面：显示原因，不白屏', async () => {
    renderInsight('focus-1');
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});

describe('分享码（S15）', () => {
  it('分享弹窗的说明：链接里是随机分享码，不是客户编号', () => {
    expect(i18n.t('share.refNote')).toContain('随机分享码');
    expect(i18n.t('share.refNote')).toContain('不是你的客户编号');
  });
});
