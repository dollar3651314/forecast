/**
 * 路由。客户端部署在根路径 `/`，使用浏览器路由。需求说明书第 5.1 节的站点地图（M1 原型）：
 * 市场（首页）/markets、市场详情 /markets/:publicNo（下注在详情里：手机抽屉、电脑右栏）、我的下注 /bets、资产、我的。
 * 不登录的页面：登录、注册、忘记密码、开赛提醒的退订链接 /unsubscribe（S11）。
 * S13（MF-4，需求第 5.1 节虚线框）：市场列表（顶部今日场次）、市场详情、开奖记录 /results、周报 /results/weekly、说明页 /how-to-play
 * 不登录也能看，和登录后同一个框架（Layout 没登录时右上角是"登录 / 注册"，C90 位置不变）；下注、我的下注、资产、我的仍要登录
 * （点"押注"先去登录，登录后回到原市场和原选项）。分享链接 /m/:publicNo（架构方案第 18 节预留）跳到市场详情，查询参数（src、ref）保留。
 * 地址里的来源参数（src、cmp、ref、start）由 AttributionCapture 记下（MF-1，lib/attribution.ts）。
 * S15（MF-11）：数据和文章 /insights、/insights/:slug（焦点战、BTC 每小时涨跌统计、文章），不登录可看。
 */
import { lazy, Suspense, useEffect, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { AccountProvider } from './app/AccountProvider';
import { AuthProvider } from './app/AuthProvider';
import { useAuth } from './app/contexts';
import { ThemeProvider } from './app/ThemeProvider';
import { ToastProvider } from './app/ToastProvider';
import { Loading } from './components/AsyncView';
import { Layout } from './components/Layout';
import { captureAttribution } from './lib/attribution';

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
const Results = lazy(() => import('./pages/results/Results'));
const Weekly = lazy(() => import('./pages/results/Weekly'));
const WeekPage = lazy(() => import('./pages/results/WeekPage'));
const HowTo = lazy(() => import('./pages/HowTo'));
const Insights = lazy(() => import('./pages/insights/Insights'));
const InsightPage = lazy(() => import('./pages/insights/InsightPage'));

function RequireAuth({ children }: { children: ReactNode }) {
  const { session, checking, loggedOut } = useAuth();
  const location = useLocation();
  if (checking) return <Loading rows={4} />;
  // 自己点了退出：去登录页不带"回到原处"（登录后到首页）；会话过期等其他情况登录后回到这里
  if (!session) return <Navigate to="/login" replace state={loggedOut ? undefined : { from: location.pathname + location.search }} />;
  return <>{children}</>;
}

/** 登录了：账户数据 + 完整的框架；没登录：同一个框架（右上角"登录 / 注册"），只放不登录也能看的页面（S13） */
function Shell() {
  const { session, checking } = useAuth();
  if (checking) return <Loading rows={4} />;
  return session ? (
    <AccountProvider>
      <Layout />
    </AccountProvider>
  ) : (
    <Layout />
  );
}

/** 地址里带来源参数时记下（MF-1）；每次地址变化都看一次 */
function AttributionCapture() {
  const { search } = useLocation();
  useEffect(() => {
    if (search) captureAttribution(search);
  }, [search]);
  return null;
}

/** 分享链接 /m/:publicNo → 市场详情（带上查询参数） */
function ShareLink() {
  const { publicNo = '' } = useParams();
  const { search } = useLocation();
  return <Navigate to={`/markets/${encodeURIComponent(publicNo)}${search}`} replace />;
}

/**
 * 登录、注册页：已经登录就离开。S13：回到进来之前的地址（state.from，例如游客点"押注"时的市场和选项），
 * 登录成功的那一刻这里和登录页自己的跳转同时发生，两边要去同一个地方（否则会先被带回首页）。
 */
function GuestOnly({ children }: { children: ReactNode }) {
  const { session, checking } = useAuth();
  const location = useLocation();
  if (checking) return <Loading rows={4} />;
  const from = (location.state as { from?: string } | null)?.from;
  const to = from && from.startsWith('/') && !from.startsWith('//') && from !== '/login' && from !== '/register' ? from : '/';
  return session ? <Navigate to={to} replace /> : <>{children}</>;
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
        <Route path="/m/:publicNo" element={<ShareLink />} />
        <Route element={<Shell />}>
          <Route index element={<Navigate to="/markets" replace />} />
          <Route path="home" element={<Navigate to="/markets" replace />} />
          {/* 不登录也能看（S13，MF-4） */}
          <Route path="markets" element={<Markets />} />
          <Route path="markets/:publicNo" element={<MarketDetail />} />
          <Route path="results" element={<Results />} />
          <Route path="results/weekly" element={<Weekly />} />
          <Route path="results/weekly/:week" element={<WeekPage />} />
          <Route path="how-to-play" element={<HowTo />} />
          <Route path="insights" element={<Insights />} />
          <Route path="insights/:slug" element={<InsightPage />} />
          {/* 要登录 */}
          <Route
            element={
              <RequireAuth>
                <Outlet />
              </RequireAuth>
            }
          >
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
          </Route>
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
            <AttributionCapture />
            <AppRoutes />
          </BrowserRouter>
        </ToastProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}
