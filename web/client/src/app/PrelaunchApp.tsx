/**
 * 对外预热域名的客户端（forecast S18，P1；lib/siteMode.ts）：只有等候页兼公开概率看板（/data 那一页）放在根路径，
 * 加上等候页邮件里的确认、退订链接。没有注册、登录、下注、资产，也不跳到测试环境的客户端域名（SQ-61）；别的路径一律回到根路径
 * （Nginx 已经 302，这里是 Service Worker 接管页面以后的兜底）。
 * - 不问后端登录状态（这个域名上 /api/me/session 是 404）：登录状态固定为"没登录"；
 * - 顶部只有品牌和语言；底部是风险提示和官网的服务条款、隐私、风险披露；
 * - 测试环境标识：后端在这个域名上不返回 env（common/web/PrelaunchSite），EnvBanner 照常调用、不会显示，顶部也不留空白。
 */
import { lazy, Suspense, useEffect, useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Loading } from '../components/AsyncView';
import { EnvBanner } from '../components/EnvBanner';
import { Brand } from '../components/Icon';
import { LangSelect } from '../components/LangSelect';
import { currentLang } from '../i18n';
import { captureAttribution } from '../lib/attribution';
import { siteLink } from '../lib/siteUrl';
import { AuthContext, type AuthValue } from './contexts';
import { ThemeProvider } from './ThemeProvider';
import { ToastProvider } from './ToastProvider';

const DataPage = lazy(() => import('../pages/data/DataPage'));
const WaitlistConfirm = lazy(() => import('../pages/data/Confirm'));
const WaitlistUnsubscribe = lazy(() => import('../pages/data/Unsubscribe'));

/** 来源参数（LINE 内测群的 ?src=line&cmp=seed，P4）照样记 */
function AttributionCapture() {
  const { search } = useLocation();
  useEffect(() => {
    if (search) captureAttribution(search);
  }, [search]);
  return null;
}

/** 根路径以外的地址：保留查询参数（来源统计）回到根路径 */
function ToRoot() {
  const { search } = useLocation();
  return <Navigate to={`/${search}`} replace />;
}

function PrelaunchFrame() {
  const { t } = useTranslation();
  const lang = currentLang();
  return (
    <div className="app prelaunch" data-testid="prelaunch">
      <EnvBanner />
      <header className="pre-top">
        <div className="brand">
          <Brand />
        </div>
        <LangSelect />
      </header>
      <main className="main pre-main" id="main">
        <DataPage />
      </main>
      <footer className="pre-foot">
        <p className="auth-risk">{t('auth.riskNotice')}</p>
        <nav className="pub-links" aria-label={t('me.help')}>
          <a href={siteLink('terms', lang)} target="_blank" rel="noreferrer noopener">
            {t('me.terms')}
          </a>
          <span aria-hidden="true">·</span>
          <a href={siteLink('privacy', lang)} target="_blank" rel="noreferrer noopener">
            {t('me.privacy')}
          </a>
          <span aria-hidden="true">·</span>
          <a href={siteLink('risk', lang)} target="_blank" rel="noreferrer noopener">
            {t('me.risk')}
          </a>
        </nav>
      </footer>
    </div>
  );
}

export function PrelaunchRoutes() {
  return (
    <Suspense fallback={<Loading rows={4} />}>
      <Routes>
        <Route path="/" element={<PrelaunchFrame />} />
        <Route path="/data/confirm" element={<WaitlistConfirm />} />
        <Route path="/data/unsubscribe" element={<WaitlistUnsubscribe />} />
        <Route path="*" element={<ToRoot />} />
      </Routes>
    </Suspense>
  );
}

/** 固定"没登录"：不调用 /api/me/session */
function GuestAuth({ children }: { children: ReactNode }) {
  const value = useMemo<AuthValue>(
    () => ({ session: null, checking: false, setSession: () => undefined, logout: () => Promise.resolve(), loggedOut: false }),
    [],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export default function PrelaunchApp() {
  return (
    <ThemeProvider>
      <GuestAuth>
        <ToastProvider>
          <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
            <AttributionCapture />
            <PrelaunchRoutes />
          </BrowserRouter>
        </ToastProvider>
      </GuestAuth>
    </ThemeProvider>
  );
}
