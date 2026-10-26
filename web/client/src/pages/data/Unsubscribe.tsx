/**
 * 等候页邮件里的"退订"链接（S16，MF-8）：/data/unsubscribe?t=随机串，不用登录，打开就退订（只能退订这一个邮箱，不显示邮箱）。
 * 分清链接无效（404）和暂时出错（可以重试，C56）。
 */
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { unsubscribeWaitlist } from '../../api/board';
import { isApiError } from '../../api/errors';
import { ErrorState, Loading } from '../../components/AsyncView';
import { useAsync } from '../../hooks/useAsync';
import { AuthLayout } from '../auth/AuthLayout';

export default function WaitlistUnsubscribe() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const token = params.get('t') ?? '';
  const s = useAsync(() => unsubscribeWaitlist(token), `wl-unsub|${token}`);
  const invalid = !token || (isApiError(s.error) && s.error.reason === 'notFound');
  return (
    <AuthLayout title={t('data.unsubTitle')} footer={<Link to="/data">{t('data.backToData')}</Link>}>
      {invalid ? (
        <div className="notice error" role="alert" data-testid="wl-unsub-invalid">
          {t('data.linkInvalid')}
        </div>
      ) : s.error ? (
        <ErrorState error={s.error} onRetry={s.reload} compact />
      ) : !s.data ? (
        <Loading rows={2} />
      ) : (
        <div className="notice info" role="status" data-testid="wl-unsub-done">
          {t('data.unsubDone')}
        </div>
      )}
    </AuthLayout>
  );
}
