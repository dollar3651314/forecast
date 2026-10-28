/**
 * forecast S19（P11、P12、P13）：Cloudflare 上的公开版用到的客户端改动——
 * 看板照实写数据来源（OKX / Binance）；邮箱规则和 Cloudflare、后端同一组例子（C108）；官网地址可以是同站路径（/about）；
 * 公开版构建里换掉的模块（src/public/stubs/）导出的名字和原模块一致；平常的构建不是公开版。
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { act, screen } from '@testing-library/react';
import { setProtoState } from './api/prototype';
import * as notifications from './api/notifications';
import { setMockBoardSource } from './mocks/board';
import { resetDb } from './mocks/db';
import DataPage from './pages/data/DataPage';
import * as stub from './public/stubs/notifications';
import { EMAIL_MAX, validateEmail } from './lib/emailRule';
import { validateEmail as fromValidators } from './lib/validators';
import { resolveSiteUrl, siteLink } from './lib/siteUrl';
import { PUBLIC_BUILD } from './lib/siteMode';
import { renderPage } from './test/renderApp';

afterEach(() => {
  act(() => setProtoState({ scenario: 'normal' }));
  setMockBoardSource(null);
  resetDb();
  localStorage.clear();
});

describe('看板的数据来源（P13）', () => {
  it('接口带 source 时照实写（Cloudflare 上可能是 OKX）', async () => {
    setMockBoardSource('OKX');
    renderPage(<DataPage />, '/data');
    expect(await screen.findByTestId('btc-source')).toHaveTextContent('数据来自 OKX 公开行情');
  });

  it('不带 source（测试服务器的后端）仍写币安', async () => {
    renderPage(<DataPage />, '/data');
    expect(await screen.findByTestId('btc-source')).toHaveTextContent('数据来自币安公开行情');
  });
});

describe('邮箱规则（C108）', () => {
  it('和 Cloudflare、后端同一组例子（backend/src/test/resources/email/vectors.json）', () => {
    const file = path.resolve(__dirname, '../../../backend/src/test/resources/email/vectors.json');
    const cases = (JSON.parse(readFileSync(file, 'utf8')) as { cases: { input: string; ok: boolean }[] }).cases;
    expect(cases.length).toBeGreaterThan(10);
    for (const c of cases) expect(validateEmail(c.input).length === 0, c.input).toBe(c.ok);
    expect(EMAIL_MAX).toBe(254);
  });

  it('表单的 validateEmail 就是这一份', () => {
    expect(fromValidators).toBe(validateEmail);
    expect(validateEmail('')).toEqual(['validation.required']);
    expect(validateEmail('a@b')).toEqual(['validation.emailFormat']);
  });
});

describe('公开版', () => {
  it('官网地址可以是同站路径（公开版放在 /about/）；不合法的不认', () => {
    expect(resolveSiteUrl('/about', false)).toBe('/about');
    expect(resolveSiteUrl('/about/', false)).toBe('/about');
    expect(resolveSiteUrl('javascript:alert(1)', false)).toBe('');
    expect(resolveSiteUrl('//evil.example', false)).toBe('');
    expect(resolveSiteUrl('//evil', false)).toBe('');
    expect(siteLink('terms', 'zh-TW', '/about')).toBe('/about/terms?lang=zh-TW');
  });

  it('换掉的通知模块：导出的名字和原模块一致，公开版不发请求', async () => {
    expect(Object.keys(stub).sort()).toEqual(Object.keys(notifications).sort());
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    stub.setPushLanguage('zh-TW');
    await expect(stub.getUnreadCount()).rejects.toThrow();
    expect(fetchSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it('平常的构建和测试里不是公开版（PUBLIC_BUILD 只在 vite.public.config.ts 里打开）', () => {
    expect(PUBLIC_BUILD).toBe(false);
  });
});
