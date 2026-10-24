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
  const [session, setSession] = useState<Session | null | undefined>(() => (IS_MOCK ? getSession() : undefined));

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

  useEffect(() => onUnauthorized(() => setSession(null)), []);

  const loggedIn = !!session;
  useEffect(() => {
    sessionChanged(loggedIn);
  }, [loggedIn]);

  const logout = useCallback(async () => {
    await apiLogout().catch(() => undefined);
    setSession(null);
  }, []);
  const value = useMemo(() => ({ session: session ?? null, checking: session === undefined, setSession, logout }), [session, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
