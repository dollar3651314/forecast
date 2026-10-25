import { act, fireEvent, render, screen, within } from '@testing-library/react';
import App from './App';
import { LANG_KEY } from './i18n/lang';
import { BRAND_NAME } from './lib/brand';

function setDesktop(on: boolean) {
  window.matchMedia = ((query: string) =>
    ({ matches: on && query.includes('min-width: 1024px'), media: query, onchange: null, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false }) as MediaQueryList) as typeof window.matchMedia;
}

beforeEach(() => {
  localStorage.clear();
  window.history.replaceState(null, '', '/');
  setDesktop(false);
});

describe('官网首页', () => {
  it('手机：区块都在，底部固定登录和注册（W8），顶部风险提示条（W9）；没有公开行情（forecast S1 删除）', () => {
    render(<App initialLang="zh-CN" />);
    expect(screen.getByTestId('risk-bar')).toHaveTextContent('风险');
    for (const id of ['hero', 'hero-note', 'sec-platform', 'sec-steps', 'sec-fee', 'sec-safety', 'sec-faq', 'footer', 'sticky-cta']) {
      expect(screen.getByTestId(id)).toBeInTheDocument();
    }
    expect(screen.queryByTestId('quote-board')).toBeNull();
    expect(screen.queryByTestId('sec-products')).toBeNull();
    expect(screen.getByTestId('menu-btn')).toBeInTheDocument();
    expect(screen.queryByTestId('nav-register')).toBeNull();
    expect(screen.getAllByTestId('faq-item')).toHaveLength(4);
    // 联系邮箱跟域名走，还没定：显示"待提供"（src/lib/brand.ts）
    expect(screen.getByTestId('contact')).toHaveTextContent('待提供');
    expect(screen.getByTestId('footer')).toHaveTextContent(`© ${BRAND_NAME}`);
    expect(screen.getAllByTestId('logo')[0]).toHaveTextContent(BRAND_NAME);
    // 首屏和底部按钮都去客户端注册页，带当前语言
    expect(screen.getByTestId('hero-register').getAttribute('href')).toBe('/register?lang=zh-CN');
    expect(screen.getByTestId('sticky-register').getAttribute('href')).toBe('/register?lang=zh-CN');
    expect(screen.getByTestId('sticky-login').getAttribute('href')).toBe('/login?lang=zh-CN');
    expect(document.title).toBe(`${BRAND_NAME}｜看好哪个结果，就押哪个`);
    // 静态示例市场（需求第 3.5 节的例子）
    expect(screen.getByTestId('hero-note')).toHaveTextContent('162.29');
  });

  it('切换英文：文字和链接里的语言都变，并记住选择', () => {
    render(<App initialLang="zh-CN" />);
    // 语言下拉框（体验优化 1021 UX-1）：按钮显示当前语言，展开后当前语言打勾、标"当前"
    const sw = screen.getByTestId('lang-switch');
    const btn = within(sw).getByRole('button', { name: '语言: 简体中文' });
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(btn);
    const options = within(sw).getAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual(['简体中文当前', '繁體中文', 'English']);
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(within(options[2]).getByRole('button'));
    expect(within(sw).queryByRole('listbox')).toBeNull();
    expect(within(sw).getByRole('button', { name: 'Language: English' })).toBeInTheDocument();
    expect(screen.getByTestId('hero')).toHaveTextContent('Back the outcome');
    expect(screen.getByTestId('hero-register').getAttribute('href')).toBe('/register?lang=en');
    expect(localStorage.getItem(LANG_KEY)).toBe('en');
    expect(document.documentElement.lang).toBe('en');
    // 英文页面上没有中文（下拉框收起时只显示当前语言 EN）
    const text = document.body.textContent || '';
    expect(text).not.toMatch(/[\u4e00-\u9fff]/);
  });

  it('电脑：顶部导航有登录和注册，首屏右栏是静态示例市场', () => {
    setDesktop(true);
    render(<App initialLang="en" />);
    expect(screen.getByTestId('nav-register').getAttribute('href')).toBe('/register?lang=en');
    expect(screen.getByTestId('nav-login').getAttribute('href')).toBe('/login?lang=en');
    expect(screen.queryByTestId('sticky-cta')).toBeNull();
    expect(screen.getByTestId('hero-note')).toHaveTextContent('Example market');
    expect(screen.getByTestId('hero-note')).toHaveTextContent('162.29');
  });
});

describe('法律页（AC2-08）', () => {
  it.each([
    ['/terms', '服务条款'],
    ['/privacy', '隐私政策'],
    ['/risk', '风险披露'],
  ])('%s 能打开，顶部标"需法务审核"，风险提示条和页脚照常显示', (path, title) => {
    window.history.replaceState(null, '', path);
    render(<App initialLang="zh-CN" />);
    expect(screen.getByTestId('legal-review')).toHaveTextContent('需法务审核');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(title);
    expect(screen.getByTestId('risk-bar')).toBeInTheDocument();
    expect(screen.getByTestId('footer-risk')).toHaveTextContent('风险提示');
  });

  it('页脚链接在页面内切换，不重新加载', () => {
    render(<App initialLang="en" />);
    act(() => {
      fireEvent.click(within(screen.getByTestId('footer')).getByText('Privacy Policy'));
    });
    expect(window.location.pathname).toBe('/privacy');
    expect(screen.getByTestId('legal-review')).toHaveTextContent('Legal review required');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Privacy Policy');
  });
});
