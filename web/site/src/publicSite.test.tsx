/**
 * forecast S19（P11）：官网的公开版（Cloudflare 上 www 的 /about/，VITE_PUBLIC_SITE=true）。交易还没开放：
 * 没有"登录"，"注册"换成"加入等候名单"，链到同一个站点首页的等候页（带当前语言），不指向任何客户端地址。平常的构建不变（App.test.tsx）。
 */
import { fireEvent, render, screen } from '@testing-library/react';

function setDesktop(on: boolean) {
  window.matchMedia = ((query: string) =>
    ({ matches: on && query.includes('min-width: 1024px'), media: query, onchange: null, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false }) as MediaQueryList) as typeof window.matchMedia;
}

async function loadPublicApp() {
  vi.resetModules();
  vi.stubEnv('VITE_PUBLIC_SITE', 'true');
  return (await import('./App')).default;
}

beforeEach(() => {
  localStorage.clear();
  window.history.replaceState(null, '', '/');
});
afterEach(() => {
  vi.unstubAllEnvs();
});

describe('官网公开版（P11）', () => {
  it('手机：首屏和底部固定栏只有"加入等候名单"，去首页的等候页；没有登录；菜单里也是等候名单', async () => {
    setDesktop(false);
    const App = await loadPublicApp();
    render(<App initialLang="zh-TW" />);
    expect(screen.getByTestId('hero-register')).toHaveTextContent('加入等候名單');
    expect(screen.getByTestId('hero-register').getAttribute('href')).toBe('/?lang=zh-TW');
    expect(screen.getByTestId('sticky-register').getAttribute('href')).toBe('/?lang=zh-TW');
    expect(screen.queryByTestId('hero-login')).toBeNull();
    expect(screen.queryByTestId('sticky-login')).toBeNull();
    fireEvent.click(screen.getByTestId('menu-btn'));
    expect(screen.getByTestId('menu-waitlist').getAttribute('href')).toBe('/?lang=zh-TW');
    expect(screen.queryByText('登入')).toBeNull();
    expect(screen.queryByText('免費註冊')).toBeNull();
    expect(screen.queryByText('現在註冊')).toBeNull();
    expect(screen.getByText('正式上線時通知你')).toBeInTheDocument();
  });

  it('电脑：顶部只有"加入等候名单"；英文', async () => {
    setDesktop(true);
    const App = await loadPublicApp();
    render(<App initialLang="en" />);
    expect(screen.getByTestId('nav-register')).toHaveTextContent('Join the waitlist');
    expect(screen.getByTestId('nav-register').getAttribute('href')).toBe('/?lang=en');
    expect(screen.queryByTestId('nav-login')).toBeNull();
    for (const a of document.querySelectorAll('a[href]')) {
      const href = a.getAttribute('href') ?? '';
      expect(href, href).not.toMatch(/\/(login|register)\b/);
    }
  });
});
