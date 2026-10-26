/**
 * S16 数据页兼上线前等候页（MF-8、MF-17；需求第 5.2 节 P13）：上线前（BTC 行情、即将上线、留邮箱、隐藏的防刷字段）、上线后（今天的场次、两边的概率和人数），
 * 确认链接（要按按钮才确认；无效、过期）、退订链接；正常、空、加载中、出错；页脚的"数据"入口。跑在原型模拟层上，真实接口的契约在 api/real/board.test.ts。
 */
import { act, fireEvent, screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { setProtoState } from './api/prototype';
import { PublicLinks } from './components/PublicLinks';
import { setMockLaunched } from './mocks/board';
import { resetDb } from './mocks/db';
import DataPage from './pages/data/DataPage';
import WaitlistConfirm from './pages/data/Confirm';
import WaitlistUnsubscribe from './pages/data/Unsubscribe';
import { renderPage } from './test/renderApp';

afterEach(() => {
  act(() => setProtoState({ scenario: 'normal' }));
  setMockLaunched(false);
  resetDb();
  localStorage.clear();
});

describe('数据页（P13）', () => {
  it('上线前：BTC 现价、过去 24 小时涨跌的比例和来源；即将上线；没有 Polymarket 这一块', async () => {
    renderPage(<DataPage />, '/data');
    expect(await screen.findByTestId('btc-price')).toHaveTextContent('67,234.50');
    expect(screen.getByTestId('btc-hours')).toHaveTextContent('过去 24 小时');
    expect(screen.getByTestId('btc-hours')).toHaveTextContent('涨 14 小时（58.3%）');
    expect(screen.getByTestId('btc-hours')).toHaveTextContent('跌 10 小时（41.7%）');
    expect(screen.getByText(/数据来自币安公开行情/)).toBeInTheDocument();
    expect(screen.getByText(/收盘价大于或等于开盘价算涨/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '即将上线' })).toBeInTheDocument();
    expect(screen.queryByText(/Polymarket/)).toBeNull();
    // 照实写玩法（F-1、F-2）
    expect(screen.getByText(/用 USDT 押注一件事的结果/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '中文频道' })).toHaveAttribute('href', 'https://t.me/quickcome_cn');
  });

  it('留邮箱：格式不对提示；提交后说明要去邮箱确认；隐藏的防刷字段不显示给人', async () => {
    renderPage(<DataPage />, '/data');
    const submit = await screen.findByTestId('wl-submit');
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: 'not-an-email' } });
    fireEvent.click(submit);
    expect(await screen.findByText('邮箱格式不正确')).toBeInTheDocument();
    const hp = document.getElementById('wl-website')!;
    expect(hp.closest('.hp-field')).toHaveAttribute('aria-hidden', 'true');
    expect(hp).toHaveAttribute('tabindex', '-1');
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: 'amos@example.com' } });
    fireEvent.click(submit);
    expect(await screen.findByTestId('wl-done')).toHaveTextContent('如果 amos@example.com 还没订阅过');
  });

  it('上线后：今天的场次、两边的概率和人数、链接；没有留邮箱的表单', async () => {
    setMockLaunched(true);
    renderPage(<DataPage />, '/data');
    const list = await screen.findByTestId('slate-list');
    expect(list).toHaveTextContent('BTC 涨还是跌');
    expect(list).toHaveTextContent('61.5%');
    expect(list).toHaveTextContent('14 人');
    expect(list).toHaveTextContent('还没开放下注');
    expect(screen.getAllByRole('link', { name: /BTC 涨还是跌/ })[0]).toHaveAttribute('href', '/markets/108000000000001001');
    expect(screen.queryByTestId('wl-submit')).toBeNull();
  });

  it('空（行情取不到、今天没有场次）、出错', async () => {
    setMockLaunched(true);
    act(() => setProtoState({ scenario: 'empty' }));
    const { unmount } = renderPage(<DataPage />, '/data');
    expect(await screen.findByTestId('btc-none')).toHaveTextContent('BTC 行情暂时取不到');
    expect(screen.getByTestId('slate-empty')).toHaveTextContent('今天没有场次');
    unmount();
    act(() => setProtoState({ scenario: 'error' }));
    renderPage(<DataPage />, '/data');
    expect(await screen.findByRole('button', { name: '重试' })).toBeInTheDocument();
  });

  it('加载中：先显示占位', () => {
    act(() => setProtoState({ scenario: 'slow' }));
    renderPage(<DataPage />, '/data');
    expect(document.querySelector('.skeleton, [aria-busy="true"], .loading')).not.toBeNull();
  });

  it('页脚有"数据"入口', () => {
    renderPage(<PublicLinks />);
    expect(screen.getByRole('link', { name: '数据' })).toHaveAttribute('href', '/data');
  });
});

describe('确认、退订链接（不用登录）', () => {
  const routes = (
    <Routes>
      <Route path="/data/confirm" element={<WaitlistConfirm />} />
      <Route path="/data/unsubscribe" element={<WaitlistUnsubscribe />} />
    </Routes>
  );

  it('确认：打开不自动确认，按"确认订阅"才确认', async () => {
    renderPage(routes, '/data/confirm?t=0123456789abcdef0123456789abcdef');
    expect(screen.queryByTestId('wl-confirmed')).toBeNull();
    fireEvent.click(await screen.findByTestId('wl-confirm'));
    expect(await screen.findByTestId('wl-confirmed')).toHaveTextContent('已确认');
  });

  it('确认：链接无效、过期', async () => {
    const { unmount } = renderPage(routes, '/data/confirm?t=bad');
    fireEvent.click(await screen.findByTestId('wl-confirm'));
    expect(await screen.findByTestId('wl-invalid')).toBeInTheDocument();
    unmount();
    renderPage(routes, '/data/confirm?t=00000000000000000000000000000000');
    fireEvent.click(await screen.findByTestId('wl-confirm'));
    expect(await screen.findByTestId('wl-confirm-error')).toHaveTextContent('确认链接已过期');
  });

  it('退订：打开就退订；链接无效', async () => {
    const { unmount } = renderPage(routes, '/data/unsubscribe?t=0123456789abcdef0123456789abcdef');
    expect(await screen.findByTestId('wl-unsub-done')).toHaveTextContent('已退订');
    unmount();
    renderPage(routes, '/data/unsubscribe?t=x');
    expect(await screen.findByTestId('wl-unsub-invalid')).toBeInTheDocument();
  });
});
