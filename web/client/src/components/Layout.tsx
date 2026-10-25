/**
 * 登录后的整体布局（需求第 5.1 节）：顶部原型横幅（只在 mock 模式）或测试环境标识条（只在 real 模式且后端返回了 env）。
 * - 手机（<1024）：顶部一条品牌 + 余额 + 通知铃铛，底部 4 个入口（市场、我的下注、资产、我的）；
 * - 电脑（≥1024）：顶部导航（市场、我的下注、资产），右上角余额、充值、通知、我的（头像）。
 * 两种宽度下各页面的顶栏、底部导航位置一致，切换页面不跳动（C90）：页面只在内容区里改宽度。
 * 新客户（身份认证未提交）第一次进来自动弹出新手引导（C83）。
 */
import type { ReactNode } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { IS_MOCK } from '../api/mode';
import { useAccount } from '../app/contexts';
import { useUnread } from '../hooks/useUnread';
import { formatAmount } from '../lib/format';
import { Onboarding } from './Onboarding';
import { EnvBanner } from './EnvBanner';
import { BRAND_NAME } from '../lib/brand';
import { Brand, Icon, type IconName } from './Icon';
import { ProtoPanel } from './ProtoPanel';
import { PushListener } from './PushListener';

// 电脑顶栏的"我的"和右上角头像重复，去掉"我的"、保留头像（手机底部导航没有头像，照旧保留"我的"）
const NAV: Array<{ to: string; key: string; icon: IconName; desktop: boolean }> = [
  { to: '/markets', key: 'nav.markets', icon: 'markets', desktop: true },
  { to: '/bets', key: 'nav.bets', icon: 'ticket', desktop: true },
  { to: '/assets', key: 'nav.assets', icon: 'assets', desktop: true },
  { to: '/me', key: 'nav.me', icon: 'me', desktop: false },
];

/** 原型横幅：只在 mock 模式显示 */
export function ProtoBanner() {
  const { t } = useTranslation();
  if (!IS_MOCK) return null;
  return (
    <div className="proto-banner" role="note">
      <Icon name="flask" size={12} strokeWidth={2.2} />
      {t('proto.banner')}
    </div>
  );
}

/** 电脑顶栏：可用、下注中都显示（PQ-3），点进资产 */
function TopAccount() {
  const { t } = useTranslation();
  const { metrics, error } = useAccount();
  const dash = error ? '—' : undefined;
  return (
    <NavLink to="/assets" className="top-acc" aria-label={t('nav.assets')}>
      <div>
        {t('account.available')}
        <span className="num">{dash ?? formatAmount(metrics?.available)}</span>
      </div>
      <div>
        {t('assets.betLocked')}
        <span className="num">{dash ?? formatAmount(metrics?.betLocked)}</span>
      </div>
    </NavLink>
  );
}

/** 手机顶栏：品牌、可用余额（点进资产）、通知 */
function MobileBar({ unread }: { unread: number }) {
  const { t } = useTranslation();
  const { metrics, error } = useAccount();
  return (
    <header className="mbar">
      <NavLink to="/markets" className="brand" aria-label={BRAND_NAME}>
        <Brand />
      </NavLink>
      <NavLink to="/assets" className="mbar-bal" aria-label={t('account.available')}>
        <small>{t('account.available')}</small>
        <span className="num">{error ? '—' : formatAmount(metrics?.available)}</span>
      </NavLink>
      <NavLink to="/me/notifications" className="icon-btn" aria-label={t('me.notifications')}>
        <Icon name="bell" />
        {unread > 0 && <span className="badge">{unread > 99 ? '99+' : unread}</span>}
      </NavLink>
    </header>
  );
}

export function Layout() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const unread = useUnread();
  return (
    <div className="app">
      <ProtoBanner />
      <EnvBanner />
      <MobileBar unread={unread} />
      <header className="topnav">
        <NavLink to="/markets" className="brand" aria-label={BRAND_NAME}>
          <Brand />
        </NavLink>
        <nav className="topnav-menu" aria-label={t('nav.main')}>
          {NAV.filter((n) => n.desktop).map((n) => (
            <NavLink key={n.to} to={n.to} className={({ isActive }) => (isActive ? 'on' : '')}>
              {t(n.key)}
            </NavLink>
          ))}
        </nav>
        {/* 不放跑马灯（需求 C84：不闪动） */}
        <div className="topnav-spacer" />
        <TopAccount />
        <button type="button" className="btn btn-primary btn-sm" onClick={() => navigate('/assets')}>
          {t('assets.deposit')}
        </button>
        <NavLink to="/me/notifications" className="icon-btn" aria-label={t('me.notifications')}>
          <Icon name="bell" />
          {unread > 0 && <span className="badge">{unread > 99 ? '99+' : unread}</span>}
        </NavLink>
        <NavLink to="/me" className="avatar" aria-label={t('nav.me')} title={t('nav.me')}>
          <Icon name="user" size={16} />
        </NavLink>
      </header>
      <main className="main" id="main">
        <Outlet />
      </main>
      <nav className="bottomnav" aria-label={t('nav.main')}>
        {NAV.map((n) => (
          <NavLink key={n.to} to={n.to} className={({ isActive }) => (isActive ? 'on' : '')}>
            <span className="bn-icon">
              <Icon name={n.icon} size={22} />
              {n.to === '/me' && unread > 0 && <span className="badge dot" aria-label={t('notifications.unreadCount', { count: unread })} />}
            </span>
            {t(n.key)}
          </NavLink>
        ))}
      </nav>
      <ProtoPanel />
      <PushListener />
      <Onboarding />
    </div>
  );
}

/** 页面标题栏（手机）；桌面上显示为页面内标题 */
export function PageHeader({ title, back, right }: { title: string; back?: string | boolean; right?: ReactNode }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return (
    <div className="page-head">
      {back && (
        <button type="button" className="icon-btn back" onClick={() => (typeof back === 'string' ? navigate(back) : navigate(-1))} aria-label={t('common.back')}>
          <Icon name="back" size={22} strokeWidth={2.2} />
        </button>
      )}
      <h1>{title}</h1>
      {right && <div className="page-head-right">{right}</div>}
    </div>
  );
}
