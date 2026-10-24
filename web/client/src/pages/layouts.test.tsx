/** 主题和涨跌色（沿用复制源）。复制源这里还测行情页、交易页的手机和电脑两套结构，随差价合约在 forecast S1 删除；市场列表、详情的两套结构在原型（M1）和 S11 加。 */
import { act, render, screen } from '@testing-library/react';
import { useTheme } from '../app/contexts';
import { ThemeProvider } from '../app/ThemeProvider';

describe('主题和涨跌色', () => {
  function Probe() {
    const { theme, upDown, setUpDown } = useTheme();
    return (
      <button type="button" onClick={() => setUpDown(upDown === 'green' ? 'red' : 'green')}>
        {theme}-{upDown}
      </button>
    );
  }

  it('默认浅色、绿涨红跌；切换后写到页面根元素', () => {
    localStorage.clear();
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByRole('button')).toHaveTextContent('light-green');
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(localStorage.getItem('fc.theme')).toBeNull();
    act(() => screen.getByRole('button').click());
    expect(document.documentElement.dataset.updown).toBe('red');
    expect(localStorage.getItem('fc.updown')).toBe('red');
  });

  it('只在客户自己切换时写入；选过深色的保留', () => {
    function ThemeProbe() {
      const { theme, setTheme } = useTheme();
      return (
        <button type="button" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>
          {theme}
        </button>
      );
    }
    localStorage.clear();
    const first = render(
      <ThemeProvider>
        <ThemeProbe />
      </ThemeProvider>,
    );
    expect(screen.getByRole('button')).toHaveTextContent('light');
    act(() => screen.getByRole('button').click());
    expect(localStorage.getItem('fc.theme')).toBe('dark');
    first.unmount();
    render(
      <ThemeProvider>
        <ThemeProbe />
      </ThemeProvider>,
    );
    expect(screen.getByRole('button')).toHaveTextContent('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
  });
});
