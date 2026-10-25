/** 修改密码：需要原密码；成功后其他设备的登录全部失效。 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { changePassword } from '../../api/auth';
import { errorKey, isApiError } from '../../api/errors';
import { IS_MOCK } from '../../api/mode';
import { useToast } from '../../app/contexts';
import { PasswordField } from '../../components/Form';
import { PageHeader } from '../../components/Layout';
import { hasErrors, validateChangePassword } from '../../lib/validators';

export default function ChangePassword() {
  const { t } = useTranslation();
  const toast = useToast();
  const navigate = useNavigate();
  const [f, setF] = useState({ current: '', password: '', confirm: '' });
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [serverErr, setServerErr] = useState<Record<string, string[]>>({});
  const [err, setErr] = useState<string | null>(null);
  const errs = validateChangePassword(f);
  const shown = (k: keyof typeof f) => [...((submitted && errs[k]) || []), ...(serverErr[k] ?? [])];
  const set = (k: keyof typeof f, v: string) => {
    setF((x) => ({ ...x, [k]: v }));
    setServerErr((s) => ({ ...s, [k]: [] }));
  };
  const submit = async () => {
    setSubmitted(true);
    setErr(null);
    if (hasErrors(errs)) return;
    setBusy(true);
    try {
      await changePassword(f.current, f.password);
      toast.show({ tone: 'success', title: t('password.changed'), body: t('password.changedBody') });
      navigate('/me');
    } catch (e) {
      if (isApiError(e) && e.fieldErrors) setServerErr(e.fieldErrors);
      else setErr(t(errorKey(e)));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="page page-narrow">
      <PageHeader title={t('me.changePassword')} back="/me" />
      <section className="card">
        {IS_MOCK && <div className="proto-hint">{t('password.protoHint')}</div>}
        <PasswordField label={t('password.current')} autoComplete="current-password" value={f.current} onChange={(v) => set('current', v)} errors={shown('current')} />
        <PasswordField label={t('auth.newPassword')} autoComplete="new-password" value={f.password} onChange={(v) => set('password', v)} errors={shown('password')} hint={t('auth.passwordRule')} />
        <PasswordField label={t('auth.confirmPassword')} autoComplete="new-password" value={f.confirm} onChange={(v) => set('confirm', v)} errors={shown('confirm')} />
        <p className="muted small">{t('password.note')}</p>
        {err && (
          <div className="notice error" role="alert">
            {err}
          </div>
        )}
        <button type="button" className="btn btn-primary btn-lg btn-block" onClick={submit} disabled={busy}>
          {busy ? t('common.submitting') : t('password.submit')}
        </button>
      </section>
    </div>
  );
}
