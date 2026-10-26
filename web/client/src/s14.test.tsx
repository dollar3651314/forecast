/**
 * S14 分享卡片（MF-5，开发记录/S14-分享卡片.md）：市场详情的"分享"、下注成功后的挑战卡、我的下注里押中的开奖卡（金额默认不显示）、周报的分享；
 * 手机调系统分享（navigator.share），电脑复制链接、下载图片、发到 Telegram / X 的分享页。跑在原型模拟层上（卡片图片是占位 SVG）；
 * 真实接口的地址和请求体在 api/real/real.test.ts，卡片内容和图片在后端 S14CardsTest。正常、出错两种状态都看（C52）。
 */
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { ApiError } from './api/errors';
import { listMarkets } from './api/markets';
import { setProtoState } from './api/prototype';
import { resetDb } from './mocks/db';
import { resetMarkets } from './mocks/markets';
import { ShareDialog } from './components/share/ShareDialog';
import MyBets from './pages/bets/MyBets';
import MarketDetail from './pages/markets/MarketDetail';
import WeekPage from './pages/results/WeekPage';
import { renderPage } from './test/renderApp';

function setDesktop(on: boolean) {
  window.matchMedia = ((query: string) =>
    ({ matches: on && query.includes('min-width: 1024px'), media: query, onchange: null, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false }) as MediaQueryList) as typeof window.matchMedia;
}

async function marketNo(prefix: string): Promise<string> {
  const all = (await listMarkets({ status: 'all' }, 1, 100)).items;
  return all.find((m) => m.title.zhCN.startsWith(prefix))!.publicNo;
}

function renderDetail(no: string) {
  return renderPage(
    <Routes>
      <Route path="/markets/:publicNo" element={<MarketDetail />} />
    </Routes>,
    `/markets/${no}`,
  );
}

const login = () => localStorage.setItem('fc.session', JSON.stringify({ token: 't', userId: 'U10086', email: 'demo@example.com' }));

let writeText: ReturnType<typeof vi.fn>;

beforeEach(() => {
  writeText = vi.fn(async () => undefined);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
});

afterEach(() => {
  setDesktop(false);
  act(() => setProtoState({ scenario: 'normal' }));
  resetDb();
  resetMarkets();
  localStorage.clear();
  // jsdom 没有 navigator.share；测试里加过的删掉
  delete (navigator as { share?: unknown }).share;
  vi.unstubAllGlobals();
});

describe('电脑：复制链接、下载图片、Telegram / X', () => {
  it('市场详情"分享"：卡片图片、链接带 src=share 和 cmp=card-market；复制、下载、Telegram、X', async () => {
    setDesktop(true);
    const no = await marketNo('曼城 vs 利物浦');
    renderDetail(no);
    fireEvent.click(await screen.findByTestId('share-open'));
    const dlg = await screen.findByTestId('share-dialog');
    const img = await within(dlg).findByTestId('share-img');
    expect(img).toHaveAttribute('src', '/favicon.svg');
    fireEvent.load(img);
    const url = (within(dlg).getByTestId('share-url') as HTMLInputElement).value;
    expect(url).toMatch(new RegExp(`/m/${no}\\?src=share&cmp=card-market&cl=zh-CN$`));
    // 主按钮是"复制链接"
    fireEvent.click(screen.getByTestId('share-copy'));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(url));
    expect(await screen.findByText('链接已复制')).toBeInTheDocument();
    expect(screen.queryByTestId('share-native')).not.toBeInTheDocument();
    // 发到 Telegram / X：打开对方的分享页（客户自己发），带链接
    expect(screen.getByTestId('share-tg')).toHaveAttribute('href', expect.stringContaining(`https://t.me/share/url?url=${encodeURIComponent(url)}`));
    expect(screen.getByTestId('share-x')).toHaveAttribute('href', expect.stringContaining(`&url=${encodeURIComponent(url)}`));
    // 下载图片：取图片 → 本地文件
    const fetchMock = vi.fn(async () => ({ ok: true, blob: async () => new Blob(['png'], { type: 'image/png' }) }) as unknown as Response);
    vi.stubGlobal('fetch', fetchMock);
    const createObjectURL = vi.fn(() => 'blob:card');
    URL.createObjectURL = createObjectURL;
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    fireEvent.click(screen.getByTestId('share-download'));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith(img.getAttribute('src'));
    click.mockRestore();
  });

  it('下注成功后弹出挑战卡（可以关掉）', async () => {
    setDesktop(true);
    login();
    const no = await marketNo('曼城 vs 利物浦');
    renderDetail(no);
    const panel = await screen.findByTestId('bet-panel');
    fireEvent.change(within(panel).getByLabelText('金额（USDT）'), { target: { value: '10' } });
    fireEvent.click(within(panel).getByTestId('bet-confirm'));
    const dlg = await screen.findByRole('dialog', { name: '下注成功 · 挑战卡' });
    const url = (await within(dlg).findByTestId('share-url')) as HTMLInputElement;
    expect(url.value).toContain('cmp=card-challenge');
    fireEvent.click(within(dlg).getByRole('button', { name: '关闭' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '下注成功 · 挑战卡' })).not.toBeInTheDocument());
  });

  it('取卡片出错：弹窗里说明原因（不是"不存在"）、可以重试，按钮不可用', async () => {
    setDesktop(true);
    let n = 0;
    const load = vi.fn(async () => {
      n += 1;
      if (n === 1) throw new ApiError('cardNotAvailable');
      return { kind: 'market' as const, url: 'https://x.test/m/1?src=share&cmp=card-market&cl=zh-CN', imageUrl: 'data:image/png;base64,', title: 't', text: 't' };
    });
    renderPage(<ShareDialog title="分享卡片" cacheKey="err" load={load} onClose={() => undefined} />, '/');
    const dlg = await screen.findByTestId('share-dialog');
    expect(await within(dlg).findByRole('alert')).toHaveTextContent('挑战卡只能为自己押过、还在下注期的选项生成');
    expect(screen.getByTestId('share-copy')).toBeDisabled();
    fireEvent.click(within(dlg).getByRole('button', { name: '重试' }));
    expect(await within(dlg).findByTestId('share-url')).toHaveValue('https://x.test/m/1?src=share&cmp=card-market&cl=zh-CN');
    // 图片取不到：说明原因，链接照样能分享
    fireEvent.error(within(dlg).getByTestId('share-img'));
    expect(within(dlg).getByText('卡片图片暂时显示不出来，链接仍然可以分享')).toBeInTheDocument();
    expect(screen.getByTestId('share-copy')).toBeEnabled();
  });
});

describe('手机：系统分享', () => {
  it('有 navigator.share：主按钮"分享"调系统分享面板（标题、卡片文字、链接）；客户取消不报错', async () => {
    const share = vi.fn(async () => undefined);
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });
    const no = await marketNo('曼城 vs 利物浦');
    renderDetail(no);
    fireEvent.click(await screen.findByTestId('share-open'));
    await screen.findByTestId('share-url');
    fireEvent.click(screen.getByTestId('share-native'));
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1));
    const arg = (share.mock.calls[0] as unknown as [ShareData])[0];
    expect(arg.url).toMatch(/src=share&cmp=card-market/);
    expect(arg.title).toBeTruthy();
    expect(arg.text).toBeTruthy();
    share.mockRejectedValueOnce(new DOMException('cancel', 'AbortError'));
    fireEvent.click(screen.getByTestId('share-native'));
    await waitFor(() => expect(share).toHaveBeenCalledTimes(2));
    expect(writeText).not.toHaveBeenCalled();
    // 手机上没有 Telegram / X 按钮（系统分享面板里有）
    expect(screen.queryByTestId('share-tg')).not.toBeInTheDocument();
  });

  it('没有 navigator.share：退回复制链接和下载图片', async () => {
    const no = await marketNo('曼城 vs 利物浦');
    renderDetail(no);
    fireEvent.click(await screen.findByTestId('share-open'));
    await screen.findByTestId('share-url');
    expect(screen.queryByTestId('share-native')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('share-copy'));
    await waitFor(() => expect(writeText).toHaveBeenCalled());
    expect(screen.getByTestId('share-download')).toBeInTheDocument();
  });
});

describe('押中的开奖卡、周报卡', () => {
  it('我的下注：押中的已结算下注可以分享开奖卡，金额默认不显示，勾选后换一张带金额的', async () => {
    login();
    renderPage(<MyBets />, '/bets?tab=settled');
    const btn = await screen.findAllByTestId('share-win');
    fireEvent.click(btn[0]);
    const dlg = await screen.findByTestId('share-dialog');
    const amount = within(dlg).getByTestId('share-amount') as HTMLInputElement;
    expect(amount.checked).toBe(false);
    const url0 = ((await within(dlg).findByTestId('share-url')) as HTMLInputElement).value;
    expect(url0).toContain('cmp=card-win');
    expect(url0).toContain('card=protowin0');
    fireEvent.click(amount);
    await waitFor(() => expect((within(dlg).getByTestId('share-url') as HTMLInputElement).value).toContain('card=protowin1'));
  });

  it('周报页头"分享"：周报卡', async () => {
    setDesktop(true);
    renderPage(
      <Routes>
        <Route path="/results/weekly/:week" element={<WeekPage />} />
      </Routes>,
      '/results/weekly/2026-W43',
    );
    fireEvent.click(await screen.findByTestId('share-open'));
    const url = (await screen.findByTestId('share-url')) as HTMLInputElement;
    expect(url.value).toMatch(/\/results\/weekly\/2026-W43\?src=share&cmp=card-weekly/);
  });
});
