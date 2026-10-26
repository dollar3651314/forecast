/**
 * 测试环境标识条（S20）：real 模式启动时调一次 /api/health，env 非空才显示；失败不显示、不报错；mock 模式不调用。
 * S12（原 S11 的 SQ-28）：真实模式不给顶部横幅留 24px（生产没有空白），显示标识条时才恢复。
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { act, render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import i18n from '../i18n';
import { fetchEnvLabel, loadEnvLabel, resetEnvLabel } from '../api/envLabel';
import { ENV_ON_CLASS, initBannerSpace, NO_PROTO_CLASS } from '../lib/bannerSpace';
import { EnvBanner } from './EnvBanner';

const mode = vi.hoisted(() => ({ mock: true }));
vi.mock('../api/mode', () => ({
  get IS_MOCK() {
    return mode.mock;
  },
  get API_MODE() {
    return mode.mock ? 'mock' : 'real';
  },
}));

let urls: string[] = [];

/** fetch 替身：返回给定的状态码和响应体，或抛出网络错误 */
function stubHealth(r: { status: number; body: string } | Error) {
  urls = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      urls.push(url);
      if (r instanceof Error) throw r;
      return { ok: r.status >= 200 && r.status < 300, status: r.status, text: async () => r.body } as Response;
    }),
  );
}

async function renderBanner() {
  const view = render(<EnvBanner />);
  await act(async () => {
    await loadEnvLabel();
  });
  return view;
}

beforeEach(() => {
  resetEnvLabel();
  mode.mock = false;
});

afterEach(async () => {
  document.documentElement.classList.remove(NO_PROTO_CLASS, ENV_ON_CLASS);
  mode.mock = true;
  vi.unstubAllGlobals();
  await i18n.changeLanguage('zh-CN');
});

describe('测试环境标识条', () => {
  it('real 模式：健康检查返回 env 时显示中文标识条，只请求一次', async () => {
    stubHealth({ status: 200, body: '{"status":"UP","version":"a1b2c3d","env":"test","db":"UP","redis":"UP"}' });
    await renderBanner();
    expect(screen.getByTestId('env-banner')).toHaveTextContent('测试环境');
    expect(screen.getByRole('note')).toBeInTheDocument();
    render(<EnvBanner />);
    await act(async () => {
      await loadEnvLabel();
    });
    expect(screen.getAllByTestId('env-banner')).toHaveLength(2);
    expect(urls).toEqual(['/api/health']);
    // 显示标识条时恢复顶部预留的 24px（根元素 env-on），标识条不遮挡内容
    expect(document.documentElement).toHaveClass(ENV_ON_CLASS);
  });

  it('英文界面显示 Test environment', async () => {
    await i18n.changeLanguage('en');
    stubHealth({ status: 200, body: '{"status":"UP","env":"test"}' });
    await renderBanner();
    expect(screen.getByTestId('env-banner')).toHaveTextContent('Test environment');
  });

  it('生产（不返回 env 或为空）不显示', async () => {
    stubHealth({ status: 200, body: '{"status":"UP","version":"a1b2c3d","db":"UP","redis":"UP"}' });
    await renderBanner();
    expect(screen.queryByTestId('env-banner')).toBeNull();
    // 生产：不恢复预留高度
    expect(document.documentElement).not.toHaveClass(ENV_ON_CLASS);
    resetEnvLabel();
    stubHealth({ status: 200, body: '{"status":"UP","env":"  "}' });
    expect(await fetchEnvLabel()).toBe('');
  });

  it('健康检查 503、断网、返回的不是 JSON 时不显示，也不报错', async () => {
    const errors = vi.spyOn(console, 'error');
    stubHealth({ status: 503, body: '{"status":"DOWN","env":"test","db":"DOWN","redis":"UP"}' });
    await renderBanner();
    expect(screen.queryByTestId('env-banner')).toBeNull();
    stubHealth(new TypeError('Failed to fetch'));
    expect(await fetchEnvLabel()).toBe('');
    stubHealth({ status: 200, body: '<html>bad gateway</html>' });
    expect(await fetchEnvLabel()).toBe('');
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });

  it('mock 模式（原型）不调用健康检查、不显示', async () => {
    mode.mock = true;
    stubHealth({ status: 200, body: '{"env":"test"}' });
    await renderBanner();
    expect(screen.queryByTestId('env-banner')).toBeNull();
    expect(urls).toEqual([]);
  });
});

/** 读 CSS 文件（vitest 里样式不参与布局计算，这里核对规则本身；真实浏览器里的高度在 e2e/tests/client.spec.ts 里量） */
const css = (name: string) => readFileSync(join(process.cwd(), 'src/styles', name), 'utf8');

describe('顶部横幅的预留高度（S12）', () => {
  it('真实模式启动时给根元素加 no-proto；原型模式不加', () => {
    const real = document.createElement('div');
    initBannerSpace(real, false);
    expect(real).toHaveClass(NO_PROTO_CLASS);
    const mock = document.createElement('div');
    initBannerSpace(mock, true);
    expect(mock).not.toHaveClass(NO_PROTO_CLASS);
  });

  it('样式：默认 24px（原型横幅）；no-proto 时 0（生产不留空白）；no-proto + env-on 时恢复 24px（测试环境标识条）', () => {
    expect(css('tokens.css')).toMatch(/--banner-h:\s*24px;/);
    const layout = css('layout.css').replace(/\/\*[\s\S]*?\*\//g, '');
    expect(layout).toMatch(/:root\.no-proto\s*\{\s*--banner-h:\s*0px;\s*\}/);
    expect(layout).toMatch(/:root\.no-proto\.env-on\s*\{\s*--banner-h:\s*24px;\s*\}/);
    // 页面、顶栏、标识条都按 --banner-h 让位，不写死 24px
    expect(layout).toMatch(/\.app, \.auth \{ padding-top: var\(--banner-h\); \}/);
    expect(layout).toMatch(/\.env-banner \{[^}]*height: var\(--banner-h\)/);
  });
});
