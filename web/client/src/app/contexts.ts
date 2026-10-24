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
/** 账户的几项金额（十进制）。forecast S1：复制源的净值、保证金、浮动盈亏、挂单冻结删除；S2 起加下注锁定 */
export interface AccountMetrics {
  balance: Dec;
  available: Dec;
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
export type Theme = 'light' | 'dark';
/** 涨跌色：green = 绿涨红跌（默认），red = 红涨绿跌。买卖按钮颜色跟随涨跌色 */
export type UpDown = 'green' | 'red';
export interface ThemeValue {
  theme: Theme;
  setTheme: (t: Theme) => void;
  upDown: UpDown;
  setUpDown: (u: UpDown) => void;
}
export const ThemeContext = createContext<ThemeValue>({ theme: 'dark', setTheme: () => undefined, upDown: 'green', setUpDown: () => undefined });
export const useTheme = (): ThemeValue => useContext(ThemeContext);
