/**
 * 登录页：账号 + 密码。
 * 真实模式调 POST /api/admin/auth/login（令牌只在 HttpOnly Cookie 里）；首次登录、被重置密码后必须先改密码。
 * 原型（mock 模式）里任意密码可登录；账号 newbie 演示"首次登录强制改密"。
 */
import { useState } from 'react';
import { Alert, Button, Form, Input, Typography } from 'antd';
import { LockOutlined, UserOutlined, TranslationOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { EnvBanner } from '../components/EnvBanner';
import { ProtoBanner } from '../components/ProtoBanner';
import { Logo } from '../components/Logo';
import { errorText } from '../utils/errors';
import { setLanguage } from '../i18n';
import { IS_MOCK } from '../api/mode';

export function LoginPage() {
  const { t, i18n } = useTranslation();
  const { login, authError } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  // 打开页面时检查登录就失败了（例如 IP 不在白名单），先显示这个原因
  const shownError = err ?? authError;

  const onFinish = async (v: { username: string; password: string }) => {
    setBusy(true);
    setErr(null);
    try {
      const s = await login(v.username, v.password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(s.mustChangePassword ? '/change-password' : from && from !== '/login' ? from : '/', { replace: true });
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
        <div className="card login-card">
          <Logo />
          <h1>{t('auth.loginTitle')}</h1>
          {IS_MOCK ? <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('auth.protoHint')} description={t('auth.protoHintFirst')} /> : null}
          <Form layout="vertical" onFinish={onFinish} requiredMark={false} name="login" initialValues={IS_MOCK ? { username: 'amos' } : undefined}>
            <Form.Item name="username" label={t('auth.username')} rules={[{ required: true, whitespace: true, message: t('auth.usernameRequired') }]}>
              <Input prefix={<UserOutlined />} autoComplete="username" />
            </Form.Item>
            <Form.Item name="password" label={t('auth.password')} rules={[{ required: true, message: t('auth.passwordRequired') }]}>
              <Input.Password prefix={<LockOutlined />} autoComplete="current-password" />
            </Form.Item>
            {shownError ? <Alert type="error" showIcon style={{ marginBottom: 12 }} message={errorText(t, shownError)} /> : null}
            <Button type="primary" htmlType="submit" block loading={busy} size="large">
              {t('auth.login')}
            </Button>
          </Form>
          <div style={{ textAlign: 'center', marginTop: 16 }}>
            <Button type="link" icon={<TranslationOutlined />} onClick={() => setLanguage(i18n.language.startsWith('zh') ? 'en' : 'zh-CN')}>
              {i18n.language.startsWith('zh') ? 'English' : '中文'}
            </Button>
          </div>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, textAlign: 'center', marginBottom: 0 }}>
            {t('auth.securityNote')}
          </Typography.Paragraph>
        </div>
      </main>
    </div>
  );
}
