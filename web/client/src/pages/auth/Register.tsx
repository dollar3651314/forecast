/**
 * 注册（需求 P8）：邮箱 + 验证码 + 密码 + 名 / 中间名 / 姓 / 出生日期 / 国籍 + 勾选"已年满 18 周岁并同意服务条款"
 * （S11：后端 adultConfirmed 必须为 true，出生日期也要满 18 周岁；条款链到官网，带当前语言）。
 */
import { useState, type FormEvent } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { register } from '../../api/auth';
import { errorKey, isApiError } from '../../api/errors';
import { useAuth } from '../../app/contexts';
import { DateFields } from '../../components/DateFields';
import { FieldErrors, PasswordField, Select, TextField } from '../../components/Form';
import { COUNTRIES } from '../../lib/countries';
import { siteLink } from '../../lib/siteUrl';
import { currentLang } from '../../i18n';
import { hasErrors, validateRegister, type RegisterForm } from '../../lib/validators';
import { AuthLayout } from './AuthLayout';
import { EmailCodeFields } from './EmailCodeField';

export default function Register() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { setSession } = useAuth();
  const [f, setF] = useState<RegisterForm>({ email: '', code: '', password: '', confirm: '', firstName: '', middleName: '', lastName: '', birthDate: '', nationality: '', agree: false });
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [serverErr, setServerErr] = useState<Record<string, string[]>>({});
  const [error, setError] = useState<string | null>(null);
  const set = <K extends keyof RegisterForm>(k: K, v: RegisterForm[K]) => {
    setF((x) => ({ ...x, [k]: v }));
    setServerErr((s) => ({ ...s, [k]: [] }));
  };
  const errs = validateRegister(f);
  const shown = (k: keyof RegisterForm) => [...((submitted && errs[k]) || []), ...(serverErr[k] ?? [])];

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    setError(null);
    if (hasErrors(errs)) return;
    setBusy(true);
    try {
      setSession(
        await register({ email: f.email.trim(), code: f.code, password: f.password, firstName: f.firstName.trim(), middleName: f.middleName.trim(), lastName: f.lastName.trim(), birthDate: f.birthDate.trim(), nationality: f.nationality, adultConfirmed: f.agree }),
      );
      navigate('/', { replace: true });
    } catch (err) {
      if (isApiError(err) && err.fieldErrors) setServerErr(err.fieldErrors);
      else setError(t(errorKey(err)));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout
      title={t('auth.registerTitle')}
      subtitle={t('auth.registerSubtitle')}
      footer={
        <>
          {t('auth.haveAccount')} <Link to="/login">{t('auth.goLogin')}</Link> · <Link to="/forgot-password">{t('auth.forgot')}</Link>
        </>
      }
    >
      <form onSubmit={onSubmit} noValidate>
        <EmailCodeFields purpose="register" email={f.email} code={f.code} onEmail={(v) => set('email', v)} onCode={(v) => set('code', v)} emailErrors={shown('email')} codeErrors={shown('code')} />
        <PasswordField label={t('auth.password')} autoComplete="new-password" value={f.password} onChange={(v) => set('password', v)} errors={shown('password')} hint={t('auth.passwordRule')} />
        <PasswordField label={t('auth.confirmPassword')} autoComplete="new-password" value={f.confirm} onChange={(v) => set('confirm', v)} errors={shown('confirm')} />
        <div className="form-section">{t('profile.section')}</div>
        <div className="row3">
          <TextField label={t('profile.firstName')} autoComplete="given-name" value={f.firstName} onChange={(v) => set('firstName', v)} errors={shown('firstName')} />
          <TextField label={t('profile.middleName')} labelExtra={<em>{t('common.optional')}</em>} autoComplete="additional-name" value={f.middleName} onChange={(v) => set('middleName', v)} errors={shown('middleName')} />
          <TextField label={t('profile.lastName')} autoComplete="family-name" value={f.lastName} onChange={(v) => set('lastName', v)} errors={shown('lastName')} />
        </div>
        <div className="row2 row2-date">
          <DateFields label={t('profile.birthDate')} hint={t('dateFields.birthHint')} autoCompletePrefix="bday" value={f.birthDate} onChange={(v) => set('birthDate', v)} errors={shown('birthDate')} />
          <Select
            label={t('profile.nationality')}
            value={f.nationality}
            onChange={(v) => set('nationality', v)}
            options={COUNTRIES.map((c) => ({ value: c, label: t(`countries.${c}`) }))}
            searchable
            errors={shown('nationality')}
          />
        </div>
        <label className="check">
          <input type="checkbox" checked={f.agree} onChange={(e) => set('agree', e.target.checked)} aria-describedby="agree-err" />
          <span>
            <Trans
              i18nKey="auth.adultAgree"
              components={{
                terms: <a href={siteLink('terms', currentLang())} target="_blank" rel="noreferrer noopener" />,
                privacy: <a href={siteLink('privacy', currentLang())} target="_blank" rel="noreferrer noopener" />,
                risk: <a href={siteLink('risk', currentLang())} target="_blank" rel="noreferrer noopener" />,
              }}
            />
          </span>
        </label>
        <FieldErrors id="agree-err" errors={shown('agree')} />
        {submitted && hasErrors(errs) && (
          <div className="form-summary" role="alert">
            {t('validation.fixAll')}
          </div>
        )}
        {error && (
          <div className="notice error" role="alert">
            {error}
          </div>
        )}
        <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={busy}>
          {busy ? t('common.submitting') : t('auth.register')}
        </button>
      </form>
    </AuthLayout>
  );
}
