/**
 * 测试环境标识条（S20）：real 模式启动时调一次 /api/health，env 非空才显示；失败不显示、不报错；mock 模式不调用。
 */
import { act, render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import i18n from '../i18n';
import { fetchEnvLabel, loadEnvLabel, resetEnvLabel } from '../api/envLabel';
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
