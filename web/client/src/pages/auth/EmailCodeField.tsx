/** 邮箱 + "发送验证码" 按钮（按接口返回的冷却秒数倒计时，发送太频繁时按剩余秒数）+ 验证码输入 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PROTOTYPE_EMAIL_CODE, sendEmailCode } from '../../api/auth';
import { errorKey, isApiError } from '../../api/errors';
import { IS_MOCK } from '../../api/mode';
import type { EmailCodePurpose } from '../../api/types';
import { TextField } from '../../components/Form';
import { useCountdown } from '../../hooks/useCountdown';
import { validateEmail } from '../../lib/validators';

export function EmailCodeFields(props: {
  purpose: EmailCodePurpose;
  email: string;
  code: string;
  onEmail: (v: string) => void;
  onCode: (v: string) => void;
  emailErrors?: string[];
  codeErrors?: string[];
}) {
  const { t } = useTranslation();
  const [left, setLeft] = useCountdown();
  const [sending, setSending] = useState(false);
  const [sendErr, setSendErr] = useState<string[] | undefined>();
  const [sent, setSent] = useState(false);

  const send = async () => {
    const e = validateEmail(props.email);
    if (e.length) {
      setSendErr(e);
      return;
    }
    setSendErr(undefined);
    setSending(true);
    try {
      const r = await sendEmailCode(props.email, props.purpose);
      setLeft(r.cooldownSeconds);
      setSent(true);
    } catch (err) {
      setSendErr([errorKey(err)]);
      // 太频繁（429）：按后端返回的剩余秒数倒计时
      if (isApiError(err) && err.cooldownSeconds) setLeft(err.cooldownSeconds);
    } finally {
      setSending(false);
    }
  };

  return (
    <>
      <TextField
        label={t('auth.email')}
        type="email"
        autoComplete="email"
        value={props.email}
        onChange={(v) => {
          props.onEmail(v);
          setSendErr(undefined);
        }}
        errors={[...(props.emailErrors ?? []), ...(sendErr ?? [])].filter((x, i, a) => a.indexOf(x) === i)}
        addon={
          <button type="button" className="btn btn-outline addon-btn" onClick={send} disabled={sending || left > 0}>
            {sending ? t('auth.sending') : left > 0 ? t('auth.resendIn', { s: left }) : sent ? t('auth.resend') : t('auth.sendCode')}
          </button>
        }
      />
      <TextField
        label={t('auth.code')}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={6}
        value={props.code}
        onChange={(v) => props.onCode(v.replace(/\D/g, ''))}
        errors={props.codeErrors}
        mono
        hint={
          !IS_MOCK ? (
            sent ? t('auth.codeSentReal', { email: props.email }) : undefined
          ) : sent ? (
            <span className="proto-inline">{t('auth.codeSent', { email: props.email, code: PROTOTYPE_EMAIL_CODE })}</span>
          ) : (
            <span className="proto-inline">{t('auth.codeProtoHint', { code: PROTOTYPE_EMAIL_CODE })}</span>
          )
        }
      />
    </>
  );
}
