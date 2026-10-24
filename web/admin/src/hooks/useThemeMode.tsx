/**
 * 后台浅色 / 深色主题（v2 E2）：默认跟随系统（prefers-color-scheme），右上角可以选"跟随系统 / 浅色 / 深色"，选择记在本机浏览器。
 * - 选择存 localStorage（键 fx-admin-theme）；隐私模式、禁用存储时读写都可能抛错，一律按"跟随系统"处理，页面照常显示。
 * - 当前主题写在根元素的 data-theme 上（global.css 按它切换 CSS 变量），同时设 color-scheme（滚动条、原生控件跟着变）。
 * - index.html 里有一小段同样逻辑的脚本，在页面脚本加载前先设好 data-theme，避免深色用户先看到一闪白屏。
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { ThemeName } from '../theme';

export type ThemePref = 'system' | 'light' | 'dark';

export const THEME_KEY = 'fx-admin-theme';
const QUERY = '(prefers-color-scheme: dark)';

export function readThemePref(): ThemePref {
  try {
    const v = globalThis.localStorage?.getItem(THEME_KEY);
    if (v === 'light' || v === 'dark') return v;
  } catch {
    /* 读不到存储：跟随系统 */
  }
  return 'system';
}

export function writeThemePref(pref: ThemePref): void {
  try {
    if (pref === 'system') globalThis.localStorage?.removeItem(THEME_KEY);
    else globalThis.localStorage?.setItem(THEME_KEY, pref);
  } catch {
    /* 存不了：只在这次打开的页面里生效 */
  }
}

function mediaQuery(): MediaQueryList | null {
  try {
    return typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(QUERY) : null;
  } catch {
    return null;
  }
}

export function systemPrefersDark(): boolean {
  return mediaQuery()?.matches ?? false;
}

export function resolveTheme(pref: ThemePref, systemDark: boolean): ThemeName {
  return pref === 'system' ? (systemDark ? 'dark' : 'light') : pref;
}

export function applyTheme(name: ThemeName): void {
  const root = document.documentElement;
  root.dataset.theme = name;
  root.style.colorScheme = name;
}

interface ThemeModeValue {
  /** 用户的选择 */
  pref: ThemePref;
  /** 实际生效的主题 */
  theme: ThemeName;
  setPref: (p: ThemePref) => void;
}

const Ctx = createContext<ThemeModeValue>({ pref: 'system', theme: 'light', setPref: () => {} });

export function ThemeModeProvider({ children }: { children: ReactNode }) {
  const [pref, setPrefState] = useState<ThemePref>(readThemePref);
  const [systemDark, setSystemDark] = useState<boolean>(systemPrefersDark);

  // 跟随系统时，系统切换深浅色立即生效
  useEffect(() => {
    const mq = mediaQuery();
    if (!mq) return;
    const onChange = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    if (typeof mq.addEventListener === 'function') {
      mq.addEventListener('change', onChange);
      return () => mq.removeEventListener('change', onChange);
    }
    mq.addListener?.(onChange);
    return () => mq.removeListener?.(onChange);
  }, []);

  const theme = resolveTheme(pref, systemDark);
  useEffect(() => applyTheme(theme), [theme]);

  const setPref = useCallback((p: ThemePref) => {
    writeThemePref(p);
    setPrefState(p);
  }, []);

  const value = useMemo(() => ({ pref, theme, setPref }), [pref, theme, setPref]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useThemeMode(): ThemeModeValue {
  return useContext(Ctx);
}
