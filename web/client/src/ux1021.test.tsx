/**
 * 沿用复制源的体验优化：语言下拉框、新手引导、电脑顶栏（去掉"我的"和跑马灯）。默认浅色在 pages/layouts.test.tsx。
 * forecast S1：复制源这里还测部分平仓（差价合约），删除；新手引导先是 3 步（S11 改成需求的 4 步）。
 */
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { LangSelect } from './components/LangSelect';
import { resetOnboarding } from './hooks/useOnboarding';
import { setLanguage } from './i18n';
import { langFromBrowser, pickLang } from './i18n/urlLang';
import { resetDb } from './mocks/db';
import { protoSetKyc } from './mocks/user';
import Me from './pages/me/Me';
import { renderPage } from './test/renderApp';

function setDesktop(on: boolean) {
  window.matchMedia = ((query: string) =>
    ({ matches: on && query.includes('min-width: 1024px'), media: query, onchange: null, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false }) as MediaQueryList) as typeof window.matchMedia;
}

const SESSION = { token: 't', userId: 'U10086', email: 'demo@example.com' };

function renderShell(path = '/home') {
  localStorage.setItem('fc.session', JSON.stringify(SESSION));
  return renderPage(
    <Routes>
      <Route element={<Layout />}>
        <Route path="/home" element={<div>首页内容</div>} />
        <Route path="/me" element={<Me />} />
        <Route path="/me/kyc" element={<div>身份认证页内容</div>} />
        <Route path="/assets" element={<div>资产页内容</div>} />
      </Route>
    </Routes>,
    path,
  );
}

afterEach(async () => {
  setDesktop(false);
  resetOnboarding();
  resetDb();
  localStorage.clear();
  await act(() => setLanguage('zh-CN'));
});

describe('第 1 条：语言下拉框', () => {
  it('按钮显示当前语言，展开后当前语言打勾，选另一项切换', async () => {
    render(<LangSelect />);
    const btn = screen.getByRole('button', { name: '语言：简体中文' });
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(btn);
    const list = screen.getByRole('listbox');
    const options = within(list).getAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual(['简体中文当前', 'English']);
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(within(options[1]).getByRole('button'));
    await waitFor(() => expect(screen.getByRole('button', { name: /English$/ })).toBeInTheDocument());
    expect(localStorage.getItem('fc.lang')).toBe('en');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('默认跟随浏览器语言：zh 开头的都用中文，其他或取不到用英文；?lang= 优先并记住；自己选过的优先于浏览器（UX-1）', () => {
    // 单元测试把浏览器语言设成了 zh-CN（test/browserLang.ts），页面是中文
    expect(document.documentElement.lang).toBe('zh-CN');
    for (const l of ['zh', 'zh-CN', 'zh-TW', 'zh-HK', 'zh-Hans-CN', 'ZH-cn']) expect(langFromBrowser([l, 'en'])).toBe('zh-CN');
    for (const l of ['en-US', 'en', 'ja-JP', 'fr', 'zu']) expect(langFromBrowser([l, 'zh-CN'])).toBe('en');
    expect(langFromBrowser(undefined, 'zh-TW'), 'languages 取不到时看 language').toBe('zh-CN');
    expect(langFromBrowser([], undefined), '都取不到用英文').toBe('en');
    expect(langFromBrowser([''], ''), '空字符串也算取不到').toBe('en');
    expect(pickLang({ search: '', stored: null, languages: ['en-GB'] })).toEqual({ lang: 'en', remember: false });
    expect(pickLang({ search: '', stored: 'zh-CN', languages: ['en-GB'] }), '自己选过中文，英文浏览器也用中文').toEqual({ lang: 'zh-CN', remember: false });
    expect(pickLang({ search: '', stored: 'en', languages: ['zh-CN'] }), '自己选过英文').toEqual({ lang: 'en', remember: false });
    expect(pickLang({ search: '?lang=en', stored: 'zh-CN', languages: ['zh-CN'] }), '官网带的 ?lang= 最优先，并记住').toEqual({ lang: 'en', remember: true });
    expect(pickLang({ search: '?lang=xx', stored: null, languages: ['zh-HK'] })).toEqual({ lang: 'zh-CN', remember: false });
  });
});

describe('第 4、10 条：电脑顶栏', () => {
  it('顶栏没有"我的"和跑马灯，右上角头像进"我的"', async () => {
    setDesktop(true);
    renderShell();
    const top = (await screen.findByText('首页内容')).closest('.app')!.querySelector('header.topnav') as HTMLElement;
    const menu = within(top).getByRole('navigation', { name: '主导航' });
    expect(within(menu).queryByRole('link', { name: '我的' })).not.toBeInTheDocument();
    expect(within(menu).getByRole('link', { name: '资产' })).toBeInTheDocument();
    expect(within(top).queryByRole('navigation', { name: '热门品种行情' })).not.toBeInTheDocument();
    expect(within(top).getByRole('link', { name: '我的' })).toHaveAttribute('href', '/me');
    // 手机底部导航照旧有"我的"（手机没有右上角头像）
    const bottom = document.querySelector('nav.bottomnav') as HTMLElement;
    expect(within(bottom).getByRole('link', { name: /我的/ })).toBeInTheDocument();
  });
});

describe('第 2 条：新手引导', () => {
  it('新客户（认证未提交）第一次进来自动打开，4 步，终点是去认证；看完不再自动打开', async () => {
    protoSetKyc('none');
    const view = renderShell();
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('第 1 / 3 步')).toBeInTheDocument();
    expect(within(dialog).getByText('欢迎来到 Forecast')).toBeInTheDocument();
    const titles = ['第一步：完成身份认证', '第二步：充值 USDT'];
    for (const title of titles) {
      fireEvent.click(within(dialog).getByRole('button', { name: '下一步' }));
      expect(within(dialog).getByText(title)).toBeInTheDocument();
    }
    expect(within(dialog).queryByRole('button', { name: '下一步' })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '去完成认证' }));
    expect(await screen.findByText('身份认证页内容')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(localStorage.getItem('fc.onboarding.v1:U10086')).toBe('done');
    view.unmount();
    renderShell();
    await screen.findByText('首页内容');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('可以跳过；已认证的老客户不自动打开，从"我的 → 新手引导"重看，终点是去充值', async () => {
    protoSetKyc('none');
    const view = renderShell();
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '跳过' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    view.unmount();

    protoSetKyc('approved');
    renderShell('/me');
    fireEvent.click(await screen.findByRole('button', { name: '新手引导' }));
    const again = await screen.findByRole('dialog');
    for (let i = 0; i < 2; i++) fireEvent.click(within(again).getByRole('button', { name: '下一步' }));
    fireEvent.click(within(again).getByRole('button', { name: '去充值' }));
    expect(await screen.findByText('资产页内容')).toBeInTheDocument();
  });
});
