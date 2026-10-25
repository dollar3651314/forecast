/**
 * 测试环境标识条（S20）：真实模式启动时调一次 /api/health，env 非空才显示并恢复横幅高度；失败不显示、不报错；原型模式不调用。
 */
import { act, render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import i18n from '../i18n';
import { fetchEnvLabel, loadEnvLabel, resetEnvLabel } from '../api/envLabel';
import { ENV_ON_CLASS, EnvBanner } from './EnvBanner';

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
  render(<EnvBanner />);
  await act(async () => {
    await loadEnvLabel();
  });
}

const rootOn = () => document.documentElement.classList.contains(ENV_ON_CLASS);

beforeEach(() => {
  resetEnvLabel();
  document.documentElement.classList.remove(ENV_ON_CLASS);
  mode.mock = false;
});

afterEach(() => {
  mode.mock = true;
  vi.unstubAllGlobals();
  i18n.changeLanguage('zh-CN');
  document.documentElement.classList.remove(ENV_ON_CLASS);
});

describe('测试环境标识条（后台）', () => {
  it('真实模式：返回 env 时显示中文标识条、恢复横幅高度，只请求一次', async () => {
    stubHealth({ status: 200, body: '{"status":"UP","version":"a1b2c3d","env":"test"}' });
    await renderBanner();
    expect(screen.getByTestId('env-banner')).toHaveTextContent('测试环境');
    expect(rootOn()).toBe(true);
    await renderBanner();
    expect(urls).toEqual(['/api/health']);
  });

  it('英文界面显示 Test environment', async () => {
    i18n.changeLanguage('en');
    stubHealth({ status: 200, body: '{"env":"test"}' });
    await renderBanner();
    expect(screen.getByTestId('env-banner')).toHaveTextContent('Test environment');
  });

  it('生产（没有 env）不显示，横幅高度保持 0', async () => {
    stubHealth({ status: 200, body: '{"status":"UP","version":"a1b2c3d"}' });
    await renderBanner();
    expect(screen.queryByTestId('env-banner')).toBeNull();
    expect(rootOn()).toBe(false);
  });

  it('健康检查 503、断网时不显示，也不报错', async () => {
    const errors = vi.spyOn(console, 'error');
    stubHealth({ status: 503, body: '{"status":"DOWN","env":"test"}' });
    await renderBanner();
    expect(screen.queryByTestId('env-banner')).toBeNull();
    expect(rootOn()).toBe(false);
    stubHealth(new TypeError('Failed to fetch'));
    expect(await fetchEnvLabel()).toBe('');
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });

  it('原型模式不调用健康检查、不显示', async () => {
    mode.mock = true;
    stubHealth({ status: 200, body: '{"env":"test"}' });
    await renderBanner();
    expect(screen.queryByTestId('env-banner')).toBeNull();
    expect(urls).toEqual([]);
  });
});
