/**
 * S18 台湾预热（市场/v1-台湾第一期推广.md P1、P2、P3）：
 * - P1：站点形态只看页面里的 <meta name="fc-site-mode">（Nginx 在对外预热域名上填 prelaunch），不看域名；预热形态的根路径是等候页，
 *   没有注册、登录和进入产品的链接，别的路径回到根路径（带上来源参数）；
 * - P2：上线前、繁中界面，等候页多一块"先准备 USDT"（台湾入金教程，内容来自说明页）；简中、英文不显示；
 * - P3：繁体文案用台湾用语（电子信箱、身分、通过、审核）。
 * 跑在原型模拟层上。
 */
import { act, screen } from '@testing-library/react';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { setProtoState } from './api/prototype';
import { PrelaunchRoutes } from './app/PrelaunchApp';
import { AuthProvider } from './app/AuthProvider';
import { ThemeProvider } from './app/ThemeProvider';
import { ToastProvider } from './app/ToastProvider';
import i18n from './i18n';
import zhTW from './i18n/zh-TW.json';
import { resolveSiteMode, resetSiteMode } from './lib/siteMode';
import { setMockLaunched } from './mocks/board';
import { resetDb } from './mocks/db';
import DataPage from './pages/data/DataPage';
import { renderPage } from './test/renderApp';

function setMode(v: string | null) {
  document.querySelector('meta[name="fc-site-mode"]')?.remove();
  if (v !== null) {
    const m = document.createElement('meta');
    m.setAttribute('name', 'fc-site-mode');
    m.setAttribute('content', v);
    document.head.appendChild(m);
  }
  resetSiteMode();
}

function renderPrelaunch(path: string) {
  return render(
    <ThemeProvider>
      <AuthProvider>
        <ToastProvider>
          <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
            <PrelaunchRoutes />
          </MemoryRouter>
        </ToastProvider>
      </AuthProvider>
    </ThemeProvider>,
  );
}

afterEach(async () => {
  setMode(null);
  act(() => setProtoState({ scenario: 'normal' }));
  setMockLaunched(false);
  resetDb();
  localStorage.clear();
  await act(() => i18n.changeLanguage('zh-CN'));
});

describe('P1 站点形态', () => {
  it('只认服务器填的 prelaunch；占位符、空、别的值都是平常的客户端', () => {
    expect(resolveSiteMode('prelaunch')).toBe('prelaunch');
    expect(resolveSiteMode(' prelaunch ')).toBe('prelaunch');
    for (const v of ['__FC_SITE_MODE__', '', null, undefined, 'app', 'PRELAUNCH', 'www.quickcome.online']) expect(resolveSiteMode(v)).toBe('app');
  });

  it('预热形态的根路径：标题"即将上线"、留邮箱；没有注册登录、市场、开奖记录等进入产品的链接；页脚是风险提示和官网的三份文件', async () => {
    setMode('prelaunch');
    renderPrelaunch('/');
    expect(await screen.findByTestId('prelaunch')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Quick Come 即将上线' })).toBeInTheDocument();
    expect(await screen.findByTestId('wl-submit')).toBeInTheDocument();
    expect(screen.queryByTestId('guest-login')).toBeNull();
    for (const name of [/登录/, /注册/, /开奖记录/, /周报/, /^市场$/]) expect(screen.queryByRole('link', { name })).toBeNull();
    for (const a of screen.getAllByRole('link')) expect(a.getAttribute('href') || '').not.toMatch(/^\/(markets|login|register|results|assets|me|bets)/);
    expect(screen.getByText(/押错选项的本金不会退回/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '服务条款' })).toHaveAttribute('href', expect.stringMatching(/\/terms\?lang=zh-CN$/));
    expect(screen.getByRole('link', { name: '风险披露' })).toHaveAttribute('href', expect.stringMatching(/\/risk\?lang=zh-CN$/));
  });

  it('预热形态：别的路径回到根路径（来源参数保留）；确认链接照常', async () => {
    setMode('prelaunch');
    const a = renderPrelaunch('/login?src=line&cmp=seed');
    expect(await screen.findByTestId('prelaunch')).toBeInTheDocument();
    a.unmount();
    const b = renderPrelaunch('/markets/108000000000001001');
    expect(await screen.findByTestId('prelaunch')).toBeInTheDocument();
    b.unmount();
    renderPrelaunch('/data/confirm?t=abc');
    expect(await screen.findByTestId('wl-confirm')).toBeInTheDocument();
  });

  it('上线后在预热形态：场次不链到市场、没有注册按钮', async () => {
    setMode('prelaunch');
    setMockLaunched(true);
    renderPrelaunch('/');
    const list = await screen.findByTestId('slate-list');
    expect(list).toHaveTextContent('BTC 涨还是跌');
    expect(screen.queryByRole('link', { name: /BTC 涨还是跌/ })).toBeNull();
    expect(screen.queryByRole('link', { name: /注册/ })).toBeNull();
  });

  it('平常的客户端（不是预热形态）：数据页照旧有页脚入口', async () => {
    renderPage(<DataPage />, '/data');
    expect(await screen.findByRole('heading', { name: '即将上线' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '开奖记录' })).toBeInTheDocument();
    expect(screen.queryByTestId('prelaunch')).toBeNull();
  });
});

describe('P2 台湾入金教程', () => {
  it('繁中、上线前：等候页有"先準備 USDT"，内容是说明页的台湾入金教程', async () => {
    await act(() => i18n.changeLanguage('zh-TW'));
    renderPage(<DataPage />, '/data');
    const box = await screen.findByTestId('tw-deposit');
    expect(box).toHaveTextContent('先準備 USDT');
    expect(box).toHaveTextContent('MAX');
    expect(box).toHaveTextContent('BitoPro');
    expect(box).toHaveTextContent('正式上線後才會有你自己的充值地址');
  });

  it('简中、英文不显示；上线以后也不显示', async () => {
    const a = renderPage(<DataPage />, '/data');
    await screen.findByTestId('wl-submit');
    expect(screen.queryByTestId('tw-deposit')).toBeNull();
    a.unmount();
    await act(() => i18n.changeLanguage('en'));
    const b = renderPage(<DataPage />, '/data');
    await screen.findByTestId('wl-submit');
    expect(screen.queryByTestId('tw-deposit')).toBeNull();
    b.unmount();
    await act(() => i18n.changeLanguage('zh-TW'));
    setMockLaunched(true);
    renderPage(<DataPage />, '/data');
    await screen.findByTestId('slate-list');
    expect(screen.queryByTestId('tw-deposit')).toBeNull();
  });
});

describe('P3 繁体用台湾用语', () => {
  it('客户端繁体文案：电子信箱、身分、认证通过、审核（不再是郵箱、身份、透過、稽覈、後臺）', () => {
    const all = JSON.stringify(zhTW);
    for (const w of ['郵箱', '身份', '稽覈', '後臺', '二維碼', '認證透過', '已透過']) expect(all, w).not.toContain(w);
    expect(zhTW.auth.email).toBe('電子信箱');
    expect(zhTW.me.kyc).toBe('身分認證');
    expect(zhTW.withdraw.kycApproved).toBe('身分認證已通過');
  });
});
