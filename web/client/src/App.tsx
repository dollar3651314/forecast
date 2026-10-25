/**
 * 路由。客户端部署在根路径 `/`，使用浏览器路由。需求说明书第 5.1 节的站点地图（M1 原型）：
 * 市场（首页）/markets、市场详情 /markets/:publicNo（下注在详情里：手机抽屉、电脑右栏）、我的下注 /bets、资产、我的。
 * 不登录的页面：登录、注册、忘记密码、开赛提醒的退订链接 /unsubscribe（S11）。以后的推广功能（MF：不登录也能看市场、今日场次、分享页）
 * 要把市场列表和详情移到 RequireAuth 外面，并让下注表单、余额等在未登录时改成"登录后押注"（S11 没做，页面结构已经按"列表 → 详情 → 下注"拆开）。
 */
import { lazy, Suspense, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AccountProvider } from './app/AccountProvider';
import { AuthProvider } from './app/AuthProvider';
import { useAuth } from './app/contexts';
import { ThemeProvider } from './app/ThemeProvider';
import { ToastProvider } from './app/ToastProvider';
import { Loading } from './components/AsyncView';
import { Layout } from './components/Layout';

const Login = lazy(() => import('./pages/auth/Login'));
const Register = lazy(() => import('./pages/auth/Register'));
const ForgotPassword = lazy(() => import('./pages/auth/ForgotPassword'));
const Unsubscribe = lazy(() => import('./pages/auth/Unsubscribe'));
const Markets = lazy(() => import('./pages/markets/Markets'));
const MarketDetail = lazy(() => import('./pages/markets/MarketDetail'));
const MyBets = lazy(() => import('./pages/bets/MyBets'));
const Assets = lazy(() => import('./pages/assets/Assets'));
const Ledger = lazy(() => import('./pages/assets/Ledger'));
const Withdraw = lazy(() => import('./pages/assets/Withdraw'));
const Whitelist = lazy(() => import('./pages/assets/Whitelist'));
const Me = lazy(() => import('./pages/me/Me'));
const Profile = lazy(() => import('./pages/me/Profile'));
const Kyc = lazy(() => import('./pages/me/Kyc'));
const Notifications = lazy(() => import('./pages/me/Notifications'));
const NotifyPrefs = lazy(() => import('./pages/me/NotifyPrefs'));
const ChangePassword = lazy(() => import('./pages/me/ChangePassword'));
const NotFound = lazy(() => import('./pages/NotFound'));

function RequireAuth({ children }: { children: ReactNode }) {
  const { session, checking } = useAuth();
  const location = useLocation();
  if (checking) return <Loading rows={4} />;
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

function GuestOnly({ children }: { children: ReactNode }) {
  const { session, checking } = useAuth();
  if (checking) return <Loading rows={4} />;
  return session ? <Navigate to="/" replace /> : <>{children}</>;
}

export function AppRoutes() {
  return (
    <Suspense fallback={<Loading rows={4} />}>
      <Routes>
        <Route path="/login" element={<GuestOnly><Login /></GuestOnly>} />
        <Route path="/register" element={<GuestOnly><Register /></GuestOnly>} />
        <Route path="/forgot-password" element={<ForgotPassword />} />
        {/* 开赛提醒邮件里的退订链接（不用登录，S11） */}
        <Route path="/unsubscribe" element={<Unsubscribe />} />
        <Route
          element={
            <RequireAuth>
              <AccountProvider>
                <Layout />
              </AccountProvider>
            </RequireAuth>
          }
        >
          <Route index element={<Navigate to="/markets" replace />} />
          <Route path="home" element={<Navigate to="/markets" replace />} />
          <Route path="markets" element={<Markets />} />
          <Route path="markets/:publicNo" element={<MarketDetail />} />
          <Route path="bets" element={<MyBets />} />
          <Route path="assets" element={<Assets />} />
          <Route path="assets/ledger" element={<Ledger />} />
          <Route path="assets/withdraw" element={<Withdraw />} />
          <Route path="assets/whitelist" element={<Whitelist />} />
          <Route path="me" element={<Me />} />
          <Route path="me/profile" element={<Profile />} />
          <Route path="me/kyc" element={<Kyc />} />
          <Route path="me/notifications" element={<Notifications />} />
          <Route path="me/notify-prefs" element={<NotifyPrefs />} />
          <Route path="me/password" element={<ChangePassword />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </Suspense>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <ToastProvider>
          <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
            <AppRoutes />
          </BrowserRouter>
        </ToastProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}
