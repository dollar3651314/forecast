import { fireEvent, screen } from '@testing-library/react';
import { useState } from 'react';
import { ApiError } from '../../api/errors';
import { renderPage } from '../../test/renderApp';
import { EmailCodeFields } from './EmailCodeField';

const sendEmailCode = vi.fn();
vi.mock('../../api/auth', async (orig) => ({ ...(await orig<typeof import('../../api/auth')>()), sendEmailCode: (...a: unknown[]) => sendEmailCode(...a) }));

function Wrapper() {
  const [email, setEmail] = useState('a@example.com');
  const [code, setCode] = useState('');
  return <EmailCodeFields purpose="register" email={email} code={code} onEmail={setEmail} onCode={setCode} />;
}

afterEach(() => sendEmailCode.mockReset());

describe('发送验证码的倒计时', () => {
  it('按接口返回的冷却秒数倒计时，不写死 60 秒', async () => {
    sendEmailCode.mockResolvedValue({ cooldownSeconds: 90 });
    renderPage(<Wrapper />, '/register');
    fireEvent.click(screen.getByRole('button', { name: '发送验证码' }));
    expect(await screen.findByRole('button', { name: '90 秒后重发' })).toBeDisabled();
  });

  it('发送太频繁（429）时显示原因，并按剩余秒数倒计时', async () => {
    sendEmailCode.mockRejectedValue(new ApiError('codeTooFrequent', undefined, 429, 25));
    renderPage(<Wrapper />, '/register');
    fireEvent.click(screen.getByRole('button', { name: '发送验证码' }));
    expect(await screen.findByRole('button', { name: '25 秒后重发' })).toBeDisabled();
    expect(screen.getByText('发送太频繁，请等倒计时结束后再试')).toBeInTheDocument();
  });

  it('429 没有带秒数（旧版后端）时只显示原因，按钮可以再点', async () => {
    sendEmailCode.mockRejectedValue(new ApiError('codeTooFrequent', undefined, 429));
    renderPage(<Wrapper />, '/register');
    fireEvent.click(screen.getByRole('button', { name: '发送验证码' }));
    expect(await screen.findByText('发送太频繁，请等倒计时结束后再试')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '发送验证码' })).toBeEnabled();
  });
});
