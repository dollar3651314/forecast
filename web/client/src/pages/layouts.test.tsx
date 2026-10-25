/** 主题（RQ-1、AC-57）：没选过时跟随系统；"我的"里选过的记住、优先。市场列表、详情的手机和电脑两套结构在 pages/markets.test.tsx。 */
import { act, render, screen } from '@testing-library/react';
import { useTheme } from '../app/contexts';
import { ThemeProvider } from '../app/ThemeProvider';

function mockSystemDark(dark: boolean) {
  window.matchMedia = ((query: string) =>
    ({ matches: dark && query.includes('prefers-color-scheme: dark'), media: query, onchange: null, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false }) as MediaQueryList) as typeof window.matchMedia;
}

function Probe() {
  const { theme, pref, setPref } = useTheme();
  return (
    <>
      <span data-testid="theme">
        {pref}-{theme}
      </span>
      <button type="button" onClick={() => setPref('dark')}>
        dark
      </button>
      <button type="button" onClick={() => setPref('system')}>
        system
      </button>
    </>
  );
}

afterEach(() => {
  mockSystemDark(false);
  localStorage.clear();
});

describe('主题跟随系统', () => {
  it('没选过：系统浅色显示浅色、系统深色显示深色，不写本地存储', () => {
    localStorage.clear();
    mockSystemDark(false);
    const a = render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('theme')).toHaveTextContent('system-light');
    expect(document.documentElement.dataset.theme).toBe('light');
    a.unmount();
    mockSystemDark(true);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('theme')).toHaveTextContent('system-dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem('fc.theme')).toBeNull();
  });

  it('选过深色：记住并优先于系统；改回跟随系统时删掉记录', () => {
    localStorage.clear();
    mockSystemDark(false);
    const first = render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    act(() => screen.getByRole('button', { name: 'dark' }).click());
    expect(localStorage.getItem('fc.theme')).toBe('dark');
    first.unmount();
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('theme')).toHaveTextContent('dark-dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    act(() => screen.getByRole('button', { name: 'system' }).click());
    expect(localStorage.getItem('fc.theme')).toBeNull();
    expect(document.documentElement.dataset.theme).toBe('light');
  });
});
