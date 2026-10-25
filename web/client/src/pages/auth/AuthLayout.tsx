import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { EnvBanner } from '../../components/EnvBanner';
import { Brand } from '../../components/Icon';
import { ProtoBanner } from '../../components/Layout';
import { LangSelect } from '../../components/LangSelect';
import { ProtoPanel } from '../../components/ProtoPanel';

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
      <main className="auth-main">
        <div className="auth-card">
          <h1>{title}</h1>
          {subtitle && <p className="muted">{subtitle}</p>}
          {children}
        </div>
        {footer && <div className="auth-foot">{footer}</div>}
        <p className="auth-risk">{t('auth.riskNotice')}</p>
      </main>
      <ProtoPanel />
    </div>
  );
}
