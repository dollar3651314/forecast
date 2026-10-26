/**
 * 等候页确认链接（S16，MF-8，双重确认）：/data/confirm?t=随机串，不用登录。要在页面上按"确认订阅"才确认（邮箱扫描器预取链接不会误确认）。
 * 分清链接无效（404）、过期（410，回数据页重新留）、已经上线（409）、暂时出错（可以重试，C56）。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { confirmWaitlist } from '../../api/board';
import { errorKey, isApiError } from '../../api/errors';
import type { WaitlistLinkResult } from '../../api/types';
import { AuthLayout } from '../auth/AuthLayout';

export default function WaitlistConfirm() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const token = params.get('t') ?? '';
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<WaitlistLinkResult | null>(null);
  const [error, setError] = useState<unknown>(null);
  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      setResult(await confirmWaitlist(token));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const invalid = !token || (isApiError(error) && error.reason === 'notFound');
  return (
    <AuthLayout title={t('data.confirmTitle')} footer={<Link to="/data">{t('data.backToData')}</Link>}>
      {invalid ? (
        <div className="notice error" role="alert" data-testid="wl-invalid">
          {t('data.linkInvalid')}
        </div>
      ) : result ? (
        <div className="notice info" role="status" data-testid="wl-confirmed">
          {result.status === 'unsubscribed' ? t('data.confirmUnsubscribed') : t('data.confirmed')}
        </div>
      ) : (
        <>
          <p>{t('data.confirmText')}</p>
          {error ? (
            <div className="notice error" role="alert" data-testid="wl-confirm-error">
              {t(errorKey(error))}
            </div>
          ) : null}
          <button type="button" className="btn btn-primary btn-block" disabled={busy} onClick={() => void confirm()} data-testid="wl-confirm">
            {busy ? t('common.submitting') : t('data.confirmBtn')}
          </button>
        </>
      )}
    </AuthLayout>
  );
}
