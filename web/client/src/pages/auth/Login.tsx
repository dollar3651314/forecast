import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { login } from '../../api/auth';
import { errorKey } from '../../api/errors';
import { IS_MOCK } from '../../api/mode';
import { useAuth } from '../../app/contexts';
import { PasswordField, TextField } from '../../components/Form';
import { hasErrors, validateLogin } from '../../lib/validators';
import { BRAND_NAME } from '../../lib/brand';
import { AuthLayout } from './AuthLayout';

export default function Login() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const { setSession } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const errs = validateLogin(email, password);
  const shown = submitted ? errs : {};

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    setError(null);
    if (hasErrors(errs)) return;
    setBusy(true);
    try {
      setSession(await login(email, password));
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : '/', { replace: true });
    } catch (err) {
      setError(t(errorKey(err)));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout
      title={t('auth.loginTitle', { brand: BRAND_NAME })}
      subtitle={t('auth.loginSubtitle')}
      footer={
        <>
          {t('auth.noAccount')}{' '}
          <Link to="/register" state={location.state}>
            {t('auth.goRegister')}
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} noValidate>
        {IS_MOCK && <div className="proto-hint">{t('auth.loginProtoHint')}</div>}
        <TextField label={t('auth.email')} type="email" autoComplete="email" value={email} onChange={setEmail} errors={shown.email} />
        <PasswordField label={t('auth.password')} autoComplete="current-password" value={password} onChange={setPassword} errors={shown.password} labelExtra={<Link to="/forgot-password" className="small">{t('auth.forgot')}</Link>} />
        {error && (
          <div className="notice error" role="alert">
            {error}
          </div>
        )}
        <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={busy}>
          {busy ? t('auth.loggingIn') : t('auth.login')}
        </button>
      </form>
    </AuthLayout>
  );
}
