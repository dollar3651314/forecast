/**
 * 我的（需求 P7）：资料、身份认证、通知、安全、新手引导、语言（三种，C82）、主题（跟随系统 / 浅色 / 深色，RQ-1）、
 * 规则说明 · 常见问题 · 服务条款 · 隐私 · 风险披露（S11：链到官网，带当前语言）、退出登录。
 * S13（需求第 5.1 节）：规则说明改成站内的说明页 /how-to-play，加开奖记录、周报的入口（不加底部导航）。
 */
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { BRAND_NAME } from '../../lib/brand';
import { Link, useNavigate } from 'react-router-dom';
import { getKyc, getProfile } from '../../api/user';
import { useAuth, useTheme } from '../../app/contexts';
import { ErrorState, Loading } from '../../components/AsyncView';
import { Segmented } from '../../components/Form';
import { Icon, type IconName } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { ConfirmDialog } from '../../components/Modal';
import { LangSelect } from '../../components/LangSelect';
import { currentLang } from '../../i18n';
import { useAsync } from '../../hooks/useAsync';
import { openOnboarding } from '../../hooks/useOnboarding';
import { useUnread } from '../../hooks/useUnread';
import { displayName } from '../../lib/profile';
import { siteLink } from '../../lib/siteUrl';

/** 链到官网的一项（新窗口打开） */
function ExtItem({ href, icon, label }: { href: string; icon: IconName; label: string }) {
  return (
    <li>
      <a href={href} className="menu-item" target="_blank" rel="noreferrer noopener">
        <Icon name={icon} size={20} />
        <span className="menu-label">{label}</span>
        <Icon name="external" size={14} className="chev" />
      </a>
    </li>
  );
}

function MenuItem({ to, icon, label, extra }: { to: string; icon: IconName; label: string; extra?: ReactNode }) {
  return (
    <li>
      <Link to={to} className="menu-item">
        <Icon name={icon} size={20} />
        <span className="menu-label">{label}</span>
        {extra}
        <Icon name="chevronRight" size={16} className="chev" />
      </Link>
    </li>
  );
}

export default function Me() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { logout } = useAuth();
  const { pref, setPref } = useTheme();
  const unread = useUnread();
  const profile = useAsync(getProfile, 'profile');
  const kyc = useAsync(() => getKyc(currentLang()), `kyc|${i18n.language}`);
  const [confirmOut, setConfirmOut] = useState(false);

  return (
    <div className="page page-me">
      <PageHeader title={t('nav.me')} />
      <section className="card me-card">
        {profile.error && !profile.data ? (
          <ErrorState error={profile.error} onRetry={profile.reload} compact />
        ) : !profile.data ? (
          <Loading rows={2} />
        ) : (
          <>
            <div className="me-avatar" aria-hidden="true">
              {(profile.data.lastName || profile.data.firstName).slice(0, 1).toUpperCase()}
            </div>
            <div className="me-info">
              <b>{displayName(profile.data, i18n.language)}</b>
              <span className="break">{profile.data.email}</span>
              <small>
                UID <span className="num">{profile.data.userId}</span>
              </small>
            </div>
            {kyc.data && <span className={`kyc-tag k-${kyc.data.status}`}>{t(`kyc.status.${kyc.data.status}`)}</span>}
          </>
        )}
      </section>

      <section className="card flush">
        <h2 className="menu-title">{t('me.account')}</h2>
        <ul className="menu">
          <MenuItem to="/me/profile" icon="user" label={t('me.profile')} />
          <MenuItem to="/me/kyc" icon="shield" label={t('me.kyc')} extra={kyc.data && <span className={`kyc-tag k-${kyc.data.status}`}>{t(`kyc.status.${kyc.data.status}`)}</span>} />
          <MenuItem to="/me/notifications" icon="bell" label={t('me.notifications')} extra={unread > 0 ? <span className="count-badge">{unread}</span> : undefined} />
          <MenuItem to="/me/notify-prefs" icon="bell" label={t('me.notifyPrefs')} />
          <MenuItem to="/me/password" icon="lock" label={t('me.changePassword')} />
          <li>
            <button type="button" className="menu-item" onClick={openOnboarding}>
              <Icon name="checkCircle" size={20} />
              <span className="menu-label">{t('me.onboarding')}</span>
              <Icon name="chevronRight" size={16} className="chev" />
            </button>
          </li>
        </ul>
      </section>

      <section className="card">
        <h2 className="menu-title">{t('me.settings')}</h2>
        <div className="setting-row">
          <span>
            <Icon name="globe" size={20} />
            {t('me.language')}
          </span>
          <LangSelect />
        </div>
        <div className="setting-row">
          <span>
            <Icon name="moon" size={20} />
            {t('me.theme')}
          </span>
          <Segmented
            label={t('me.theme')}
            value={pref}
            onChange={setPref}
            options={[
              { value: 'system', label: t('me.themeSystem') },
              { value: 'light', label: t('me.themeLight') },
              { value: 'dark', label: t('me.themeDark') },
            ]}
          />
        </div>
      </section>

      <section className="card flush">
        <h2 className="menu-title">{t('me.help')}</h2>
        <ul className="menu">
          <MenuItem to="/how-to-play" icon="info" label={t('me.rules')} />
          <MenuItem to="/results" icon="trophy" label={t('pub.results')} />
          <MenuItem to="/results/weekly" icon="list" label={t('pub.weekly')} />
          <ExtItem href={`${siteLink('home', currentLang())}#faq`} icon="info" label={t('me.faq')} />
          <ExtItem href={siteLink('terms', currentLang())} icon="list" label={t('me.terms')} />
          <ExtItem href={siteLink('privacy', currentLang())} icon="lock" label={t('me.privacy')} />
          <ExtItem href={siteLink('risk', currentLang())} icon="warn" label={t('me.risk')} />
        </ul>
      </section>

      <button type="button" className="btn btn-danger-outline btn-lg btn-block logout" onClick={() => setConfirmOut(true)}>
        <Icon name="logout" size={18} />
        {t('me.logout')}
      </button>
      <p className="footnote">{t('me.version', { brand: BRAND_NAME })}</p>
      <ConfirmDialog
        open={confirmOut}
        title={t('me.logoutTitle')}
        confirmText={t('me.logout')}
        tone="danger"
        onClose={() => setConfirmOut(false)}
        onConfirm={async () => {
          await logout();
          navigate('/login', { replace: true });
        }}
      >
        <p>{t('me.logoutBody')}</p>
      </ConfirmDialog>
    </div>
  );
}
