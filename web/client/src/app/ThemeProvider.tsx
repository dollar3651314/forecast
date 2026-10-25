import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ThemeContext, type Theme, type ThemePref } from './contexts';

/**
 * 主题（RQ-1，架构方案第 9.3 节）：默认**跟随系统**（prefers-color-scheme），系统切换时页面跟着变；
 * "我的 → 设置"可选跟随系统 / 浅色 / 深色，选过的记在 fc.theme（读写包 try/catch，隐私模式下读不到就当没选过）。
 * index.html 里有同样规则的内联脚本，页面渲染前先设好 data-theme，避免先闪一下另一种颜色。
 * 新项目没有老用户的本地存储，不涉及旧值兼容（C55）。
 */
const THEME_KEY = 'fc.theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';
const THEME_COLOR: Record<Theme, string> = { light: '#ffffff', dark: '#0d1117' };

function readPref(): ThemePref {
  try {
    const v = localStorage.getItem(THEME_KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system';
  }
}

function writePref(p: ThemePref): void {
  try {
    if (p === 'system') localStorage.removeItem(THEME_KEY);
    else localStorage.setItem(THEME_KEY, p);
  } catch {
    /* 忽略 */
  }
}

function systemDark(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [pref, setPrefState] = useState<ThemePref>(readPref);
  const [sysDark, setSysDark] = useState(systemDark);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia(DARK_QUERY);
    const on = () => setSysDark(mq.matches);
    mq.addEventListener?.('change', on);
    return () => mq.removeEventListener?.('change', on);
  }, []);
  const theme: Theme = pref === 'system' ? (sysDark ? 'dark' : 'light') : pref;
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[theme]);
  }, [theme]);
  const setPref = useCallback((p: ThemePref) => {
    setPrefState(p);
    writePref(p);
  }, []);
  const value = useMemo(() => ({ theme, pref, setPref }), [theme, pref, setPref]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
