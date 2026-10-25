import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { EnvBanner } from '../../components/EnvBanner';
import { Brand, Icon } from '../../components/Icon';
import { ProtoBanner } from '../../components/Layout';
import { LangSelect } from '../../components/LangSelect';
import { ProtoPanel } from '../../components/ProtoPanel';

/** 电脑登录注册页的左栏：一句话说清玩法 + 一张静态的示例市场（不滚动、不闪动，C84）。手机不显示 */
function AuthSide() {
  const { t } = useTranslation();
  const rows: Array<[string, string, string]> = [
    [t('authSide.exHome'), '60%', '1.63x'],
    [t('authSide.exDraw'), '15%', '6.38x'],
    [t('authSide.exAway'), '25%', '3.85x'],
  ];
  return (
    <aside className="auth-side" aria-hidden="true">
      <h2>{t('authSide.title')}</h2>
      <p>{t('authSide.sub')}</p>
      <div className="auth-ex">
        <div className="auth-ex-meta">
          <span>{t('authSide.exLeague')}</span>
          <span>{t('authSide.exPool')}</span>
        </div>
        <b>{t('authSide.exTitle')}</b>
        {rows.map(([n, p, m]) => (
          <div key={n} className="auth-ex-row">
            <span className="auth-ex-bar" style={{ width: p }} />
            <span>{n}</span>
            <b className="num">{p}</b>
            <small className="num">{m}</small>
          </div>
        ))}
        <p className="auth-ex-note num">{t('onboarding.odds.example')}</p>
      </div>
      <ul>
        {(['p1', 'p2', 'p3'] as const).map((k) => (
          <li key={k}>
            <Icon name="check" size={16} strokeWidth={2.4} />
            {t(`authSide.${k}`)}
          </li>
        ))}
      </ul>
    </aside>
  );
}

export function AuthLayout({ title, subtitle, children, footer }: { title: string; subtitle?: string; children: ReactNode; footer?: ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="auth">
      <ProtoBanner />
      <EnvBanner />
      <div className="auth-top">
        <div className="brand">
          <Brand />
        </div>
        <LangSelect />
      </div>
      <div className="auth-split">
        <AuthSide />
        <main className="auth-main">
          <div className="auth-card">
            <h1>{title}</h1>
            {subtitle && <p className="muted">{subtitle}</p>}
            {children}
          </div>
          {footer && <div className="auth-foot">{footer}</div>}
          <p className="auth-risk">{t('auth.riskNotice')}</p>
        </main>
      </div>
      <ProtoPanel />
    </div>
  );
}
