/** 忘记密码：邮件验证码重置。成功后其他设备的登录全部失效。 */
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { resetPassword } from '../../api/auth';
import { errorKey, isApiError } from '../../api/errors';
import { useToast } from '../../app/contexts';
import { PasswordField } from '../../components/Form';
import { hasErrors, validateReset } from '../../lib/validators';
import { AuthLayout } from './AuthLayout';
import { EmailCodeFields } from './EmailCodeField';

export default function ForgotPassword() {
  const { t } = useTranslation();
  const toast = useToast();
  const navigate = useNavigate();
  const [f, setF] = useState({ email: '', code: '', password: '', confirm: '' });
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [serverErr, setServerErr] = useState<Record<string, string[]>>({});
  const [error, setError] = useState<string | null>(null);
  const set = (k: keyof typeof f, v: string) => {
    setF((x) => ({ ...x, [k]: v }));
    setServerErr((s) => ({ ...s, [k]: [] }));
  };
  const errs = validateReset(f);
  const shown = (k: keyof typeof f) => [...((submitted && errs[k]) || []), ...(serverErr[k] ?? [])];

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    setError(null);
    if (hasErrors(errs)) return;
    setBusy(true);
    try {
      await resetPassword(f.email, f.code, f.password);
      toast.show({ tone: 'success', title: t('auth.resetDone'), body: t('auth.resetDoneBody') });
      navigate('/login', { replace: true });
    } catch (err) {
      if (isApiError(err) && err.fieldErrors) setServerErr(err.fieldErrors);
      else setError(t(errorKey(err)));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title={t('auth.forgotTitle')} subtitle={t('auth.forgotSubtitle')} footer={<Link to="/login">{t('auth.backToLogin')}</Link>}>
      <form onSubmit={onSubmit} noValidate>
        <EmailCodeFields purpose="resetPassword" email={f.email} code={f.code} onEmail={(v) => set('email', v)} onCode={(v) => set('code', v)} emailErrors={shown('email')} codeErrors={shown('code')} />
        <PasswordField label={t('auth.newPassword')} autoComplete="new-password" value={f.password} onChange={(v) => set('password', v)} errors={shown('password')} hint={t('auth.passwordRule')} />
        <PasswordField label={t('auth.confirmPassword')} autoComplete="new-password" value={f.confirm} onChange={(v) => set('confirm', v)} errors={shown('confirm')} />
        {error && (
          <div className="notice error" role="alert">
            {error}
          </div>
        )}
        <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={busy}>
          {busy ? t('common.submitting') : t('auth.resetSubmit')}
        </button>
      </form>
    </AuthLayout>
  );
}
