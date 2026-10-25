/**
 * 全局上下文和对应的 hook。Provider 组件放在各自的 .tsx 文件里。
 */
import { createContext, useContext } from 'react';
import type { Account, Session } from '../api/types';
import type { Dec } from '../lib/decimal';

// ---------- 登录状态 ----------
export interface AuthValue {
  session: Session | null;
  /** 真实环境打开页面时正在向后端确认登录状态 */
  checking: boolean;
  setSession: (s: Session | null) => void;
  logout: () => Promise<void>;
}
export const AuthContext = createContext<AuthValue | null>(null);
export function useAuth(): AuthValue {
  const v = useContext(AuthContext);
  if (!v) throw new Error('useAuth 必须在 AuthProvider 内使用');
  return v;
}

// ---------- 提示 ----------
export type ToastTone = 'info' | 'success' | 'warn' | 'error';
export interface ToastItem {
  id: number;
  tone: ToastTone;
  title: string;
  body?: string;
}
export interface ToastValue {
  show: (t: Omit<ToastItem, 'id'>) => void;
}
export const ToastContext = createContext<ToastValue>({ show: () => undefined });
export const useToast = (): ToastValue => useContext(ToastContext);

// ---------- 账户 ----------
/** 账户的几项金额（十进制）。forecast S1：复制源的净值、保证金、浮动盈亏、挂单冻结删除；S2 加下注锁定（下注中） */
export interface AccountMetrics {
  balance: Dec;
  available: Dec;
  betLocked: Dec;
  withdrawFrozen: Dec;
}
export interface AccountValue {
  loading: boolean;
  error: unknown;
  account?: Account;
  metrics?: AccountMetrics;
  reload: () => void;
}
export const AccountContext = createContext<AccountValue>({ loading: true, error: undefined, reload: () => undefined });
export const useAccount = (): AccountValue => useContext(AccountContext);

// ---------- 主题 ----------
/** 实际显示的主题 */
export type Theme = 'light' | 'dark';
/** 客户的选择：跟随系统（默认，RQ-1）/ 浅色 / 深色 */
export type ThemePref = 'system' | Theme;
export interface ThemeValue {
  /** 实际显示的主题（跟随系统时按系统设置） */
  theme: Theme;
  pref: ThemePref;
  setPref: (p: ThemePref) => void;
}
export const ThemeContext = createContext<ThemeValue>({ theme: 'light', pref: 'system', setPref: () => undefined });
export const useTheme = (): ThemeValue => useContext(ThemeContext);
