/**
 * 登录状态。真实环境不保存令牌：打开页面时问后端（GET /api/me/session）是否已登录，检查期间 checking 为 true；
 * 任意接口返回 401 时清空会话，路由守卫回到登录页。原型从 localStorage 恢复，不需要检查。
 */
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { getSession, logout as apiLogout, restoreSession, sessionChanged } from '../api/auth';
import { onUnauthorized } from '../api/http';
import { IS_MOCK } from '../api/mode';
import type { Session } from '../api/types';
import { AuthContext } from './contexts';

export function AuthProvider({ children }: { children: ReactNode }) {
  // undefined：还不知道（真实环境正在问后端）
  const [session, setSessionState] = useState<Session | null | undefined>(() => (IS_MOCK ? getSession() : undefined));
  const [loggedOut, setLoggedOut] = useState(false);
  const setSession = useCallback((s: Session | null | undefined | ((cur: Session | null | undefined) => Session | null | undefined)) => {
    if (typeof s !== 'function' && s) setLoggedOut(false);
    setSessionState(s);
  }, []);

  useEffect(() => {
    if (session !== undefined) return;
    let alive = true;
    restoreSession().then(
      (s) => alive && setSession((cur) => (cur === undefined ? s : cur)),
      () => alive && setSession((cur) => (cur === undefined ? null : cur)),
    );
    return () => {
      alive = false;
    };
    // 只在打开页面时检查一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => onUnauthorized(() => setSession(null)), [setSession]);

  const loggedIn = !!session;
  useEffect(() => {
    sessionChanged(loggedIn);
  }, [loggedIn]);

  const logout = useCallback(async () => {
    await apiLogout().catch(() => undefined);
    setLoggedOut(true);
    setSession(null);
  }, [setSession]);
  const value = useMemo(() => ({ session: session ?? null, checking: session === undefined, setSession, logout, loggedOut }), [session, setSession, logout, loggedOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
