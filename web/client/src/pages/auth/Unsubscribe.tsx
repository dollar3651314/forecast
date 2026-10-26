/**
 * 开赛提醒邮件里的"退订"链接（#23，需求第 7 节"每条都带退订"；S11）：/unsubscribe?t=随机串，不用登录。
 * 打开就退订这一条（只能退订这一条，不暴露邮箱等信息）；已经发过的说明"这一场已经提醒过，不会再发"。
 * 分清"链接无效"（404）和"暂时出错"（可以重试，C56）。
 */
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { isApiError } from '../../api/errors';
import { unsubscribeByToken } from '../../api/markets';
import { useAuth } from '../../app/contexts';
import { ErrorState, Loading } from '../../components/AsyncView';
import { useAsync } from '../../hooks/useAsync';
import { useLang } from '../../hooks/useLang';
import { pickText } from '../../lib/market';
import { AuthLayout } from './AuthLayout';

export default function Unsubscribe() {
  const { t } = useTranslation();
  const lang = useLang();
  const { session } = useAuth();
  const [params] = useSearchParams();
  const token = params.get('t') ?? '';
  const s = useAsync(() => unsubscribeByToken(token), `unsub|${token}`);
  const invalid = !token || (isApiError(s.error) && s.error.reason === 'notFound');
  return (
    <AuthLayout
      title={t('remind.unsubTitle')}
      footer={
        <>
          {session ? <Link to="/me/notify-prefs">{t('remind.manage')}</Link> : <Link to="/login">{t('auth.goLogin')}</Link>}
          {' · '}
          <Link to="/markets">{t('nav.markets')}</Link>
        </>
      }
    >
      {invalid ? (
        <div className="notice error" role="alert" data-testid="unsub-invalid">
          {t('remind.unsubInvalid')}
        </div>
      ) : s.error ? (
        <ErrorState error={s.error} onRetry={s.reload} compact />
      ) : !s.data ? (
        <Loading rows={2} />
      ) : (
        <div className="notice info" role="status" data-testid="unsub-done">
          {s.data.status === 'sent'
            ? t('remind.unsubSent', { title: pickText(s.data.title, lang) })
            : t('remind.unsubDone', { title: pickText(s.data.title, lang) })}
        </div>
      )}
    </AuthLayout>
  );
}
