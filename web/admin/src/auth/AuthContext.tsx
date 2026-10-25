/**
 * 登录状态和权限：页面通过 useAuth().can('xxx') 判断菜单和按钮是否可用。
 * - real 模式：登录态只在 HttpOnly Cookie 里，前端不保存令牌；打开页面时调 GET /api/admin/me 判断是否已登录。
 *   任何接口返回 401 → 清空登录状态（回登录页）；返回 403 PASSWORD_CHANGE_REQUIRED → 标记必须先改密码（跳改密码页）。
 * - mock 模式（原型）：演示身份存在 localStorage，保持原型行为不变。
 * Auth state & permission checks.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  IS_MOCK,
  changePassword as apiChangePassword,
  getCurrentAdmin,
  login as apiLogin,
  logout as apiLogout,
  onAuthEvent,
  switchIdentity as apiSwitch,
  type CurrentAdmin,
  type ProtoIdentity,
} from '../api';
import { ApiError, type AdminSession } from '../api/types';

const SESSION_KEY = 'fx-admin-session';

function loadSession(): AdminSession | null {
  if (!IS_MOCK) return null;
  try {
    const raw = globalThis.localStorage?.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as AdminSession) : null;
  } catch {
    return null;
  }
}

/** 只有原型模式把演示身份存进 localStorage；真实模式不在浏览器里存任何登录信息 */
function saveSession(s: AdminSession | null): void {
  if (!IS_MOCK) return;
  try {
    if (s) globalThis.localStorage?.setItem(SESSION_KEY, JSON.stringify(s));
    else globalThis.localStorage?.removeItem(SESSION_KEY);
  } catch {
    /* 忽略 */
  }
}

function sessionOf(me: CurrentAdmin): AdminSession {
  return { adminId: me.admin.id, username: me.admin.username, displayName: me.admin.displayName, mustChangePassword: me.admin.mustChangePassword };
}

export interface AuthState {
  session: AdminSession | null;
  me: CurrentAdmin | null;
  loading: boolean;
  /** 打开页面时检查登录失败的原因（例如 IP 不在白名单），登录页显示 */
  authError: unknown;
  identity: ProtoIdentity;
  can: (perm: string | undefined) => boolean;
  login: (username: string, password: string) => Promise<AdminSession>;
  logout: () => void;
  changePassword: (oldPassword: string, newPassword: string) => Promise<void>;
  switchIdentity: (identity: ProtoIdentity) => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

function identityOf(me: CurrentAdmin | null): ProtoIdentity {
  if (!me) return 'super';
  if (me.isSuperAdmin) return 'super';
  if (me.roles.some((r) => r.code === 'risk')) return 'risk';
  return 'ops';
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AdminSession | null>(loadSession);
  const [me, setMe] = useState<CurrentAdmin | null>(null);
  // 真实模式一打开就要问后端是否已登录
  const [loading, setLoading] = useState<boolean>(IS_MOCK ? !!session : true);
  const [authError, setAuthError] = useState<unknown>(null);

  // ---------- mock 模式：按演示身份加载 ----------
  const loadMockMe = useCallback(async (s: AdminSession | null) => {
    if (!s) {
      setMe(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      setMe(await getCurrentAdmin(s.adminId));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (IS_MOCK) void loadMockMe(session);
  }, [session, loadMockMe]);

  // ---------- real 模式：GET /api/admin/me 决定是否已登录 ----------
  const loadRealMe = useCallback(async () => {
    setLoading(true);
    try {
      const m = await getCurrentAdmin();
      setMe(m);
      setSession(sessionOf(m));
      setAuthError(null);
    } catch (e) {
      setMe(null);
      setSession(null);
      // 401 只是没登录，不算错误
      setAuthError(e instanceof ApiError && e.code === 'UNAUTHORIZED' ? null : e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!IS_MOCK) void loadRealMe();
  }, [loadRealMe]);

  // 任何接口返回 401 / 必须先改密码时更新登录状态，路由守卫负责跳转
  useEffect(() => {
    if (IS_MOCK) return undefined;
    return onAuthEvent((e) => {
      if (e === 'unauthorized') {
        setSession(null);
        setMe(null);
      } else {
        setSession((s) => (s ? { ...s, mustChangePassword: true } : s));
      }
    });
  }, []);

  const login = useCallback(
    async (username: string, password: string) => {
      const s = await apiLogin(username, password);
      saveSession(s);
      setAuthError(null);
      if (IS_MOCK) {
        setSession(s);
      } else {
        // 登录成功后取权限点和菜单；必须先改密码时 /me 也能调用
        setSession(s);
        await loadRealMe();
      }
      return s;
    },
    [loadRealMe],
  );

  const logout = useCallback(() => {
    if (!IS_MOCK) void apiLogout().catch(() => undefined);
    saveSession(null);
    setSession(null);
    setMe(null);
  }, []);

  const changePassword = useCallback(
    async (oldPassword: string, newPassword: string) => {
      await apiChangePassword(oldPassword, newPassword);
      if (session) {
        const next = { ...session, mustChangePassword: false };
        saveSession(next);
        setSession(next);
      }
      if (!IS_MOCK) await loadRealMe();
    },
    [session, loadRealMe],
  );

  const switchIdentity = useCallback(async (identity: ProtoIdentity) => {
    const s = await apiSwitch(identity);
    saveSession(s);
    setSession(s);
  }, []);

  const refresh = useCallback(() => (IS_MOCK ? loadMockMe(session) : loadRealMe()), [loadMockMe, loadRealMe, session]);

  const value = useMemo<AuthState>(() => {
    const perms = new Set(me?.permissions ?? []);
    return {
      session,
      me,
      loading,
      authError,
      identity: identityOf(me),
      can: (perm) => !perm || perms.has(perm),
      login,
      logout,
      changePassword,
      switchIdentity,
      refresh,
    };
  }, [session, me, loading, authError, login, logout, changePassword, switchIdentity, refresh]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
