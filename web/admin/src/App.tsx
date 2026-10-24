import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from './auth/AuthContext';
import { AppLayout } from './components/AppLayout';
import { RequirePerm } from './components/RequirePerm';
import { APP_ROUTES } from './config/routes';
import { flattenMenu, visibleMenu } from './config/menu';
import { LoginPage } from './pages/LoginPage';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { NotFoundPage } from './pages/NotFoundPage';

const LEAF = new Map(flattenMenu().map((m) => [m.key, m]));

/** 需要登录；首次登录必须先改密码 */
function RequireAuth({ children }: { children: JSX.Element }) {
  const { session, me, loading } = useAuth();
  const location = useLocation();
  const { t } = useTranslation();
  // 真实模式打开页面时先问后端是否已登录，问完之前不跳登录页
  if (!session && !loading) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (session?.mustChangePassword && location.pathname !== '/change-password') return <Navigate to="/change-password" replace />;
  if (loading || !me) {
    return (
      <div className="full-center" role="status">
        <Spin tip={t('common.loading')}>
          <div style={{ width: 120, height: 60 }} />
        </Spin>
      </div>
    );
  }
  return children;
}

/** 首页：跳到第一个能看到的菜单（真实模式按 /api/admin/me 返回的菜单顺序） */
function HomeRedirect() {
  const { can, me } = useAuth();
  const first = flattenMenu(visibleMenu(me?.menus, can)).find((m) => m.path);
  return <Navigate to={first?.path ?? '/dashboard'} replace />;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/change-password"
        element={
          <RequireAuth>
            <ChangePasswordPage />
          </RequireAuth>
        }
      />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<HomeRedirect />} />
        {APP_ROUTES.map((r) => (
          <Route key={r.path} path={r.path} element={<RequirePerm perm={LEAF.get(r.menuKey)?.permission}>{r.element}</RequirePerm>} />
        ))}
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
