/**
 * 修改密码。首次登录时强制进入本页，改完才能使用后台。
 */
import { useState } from 'react';
import { Alert, App, Button, Form, Input } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { EnvBanner } from '../components/EnvBanner';
import { ProtoBanner } from '../components/ProtoBanner';
import { Logo } from '../components/Logo';
import { errorText } from '../utils/errors';
import { PASSWORD_MIN, passwordProblems } from '../utils/validators';

interface Values {
  oldPassword: string;
  newPassword: string;
  confirm: string;
}

export function ChangePasswordPage() {
  const { t } = useTranslation();
  const { session, changePassword, logout } = useAuth();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [form] = Form.useForm<Values>();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const forced = !!session?.mustChangePassword;

  const onFinish = async (v: Values) => {
    setBusy(true);
    setErr(null);
    try {
      await changePassword(v.oldPassword, v.newPassword);
      void message.success(t('auth.pwdChanged'));
      navigate('/', { replace: true });
    } catch (e) {
      setErr(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <ProtoBanner />
      <EnvBanner />
      <main className="login-wrap">
        <div className="card login-card" style={{ maxWidth: 420 }}>
          <Logo />
          <h1>{forced ? t('auth.firstLoginTitle') : t('auth.changePassword')}</h1>
          {forced ? <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={t('auth.firstLoginHint')} /> : null}
          <Form form={form} layout="vertical" onFinish={onFinish} name="change-password">
            <Form.Item name="oldPassword" label={forced ? t('auth.tempPassword') : t('auth.oldPassword')} rules={[{ required: true, message: t('auth.passwordRequired') }]}>
              <Input.Password autoComplete="current-password" />
            </Form.Item>
            <Form.Item
              name="newPassword"
              label={t('auth.newPassword')}
              required
              extra={t('auth.pwdRule', { min: PASSWORD_MIN })}
              dependencies={['oldPassword']}
              rules={[
                ({ getFieldValue }) => ({
                  validator: (_r, value: string | undefined) => {
                    const problems = passwordProblems(value ?? '', getFieldValue('oldPassword'));
                    return problems.length ? Promise.reject(new Error(problems.map((p) => t(p, { min: PASSWORD_MIN })).join('；'))) : Promise.resolve();
                  },
                }),
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            <Form.Item
              name="confirm"
              label={t('auth.confirmPassword')}
              dependencies={['newPassword']}
              rules={[
                { required: true, message: t('auth.confirmRequired') },
                ({ getFieldValue }) => ({
                  validator: (_r, value) => (!value || value === getFieldValue('newPassword') ? Promise.resolve() : Promise.reject(new Error(t('validation.pwdMismatch')))),
                }),
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            {err ? <Alert type="error" showIcon style={{ marginBottom: 12 }} message={errorText(t, err)} /> : null}
            <Button type="primary" htmlType="submit" block loading={busy} size="large">
              {t('auth.submitPassword')}
            </Button>
          </Form>
          <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 12 }}>
            {!forced ? <Button onClick={() => navigate(-1)}>{t('common.back')}</Button> : null}
            <Button
              type="link"
              onClick={() => {
                logout();
                navigate('/login');
              }}
            >
              {t('auth.logout')}
            </Button>
          </div>
        </div>
      </main>
    </div>
  );
}
