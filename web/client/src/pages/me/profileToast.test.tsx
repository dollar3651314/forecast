import { fireEvent, screen } from '@testing-library/react';
import { act } from 'react';
import { PushListener } from '../../components/PushListener';
import { emit } from '../../mocks/bus';
import { resetDb } from '../../mocks/db';
import { renderPage } from '../../test/renderApp';
import Profile from './Profile';

/** v1 功能对照核对"已实现没有测试"的两项（v2 E12）：A-25 个人资料页、N-32 新通知 Toast */
afterEach(() => resetDb());

describe('个人资料页（A-25）', () => {
  it('显示资料，编辑后保存成功并显示新值', async () => {
    renderPage(<Profile />, '/me/profile');
    expect(await screen.findByText('ouyang.siyuan.prediction.account@example-mail.com')).toBeInTheDocument();
    expect(screen.getByText('1990-06-18')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '编辑' }));
    const first = await screen.findByLabelText('名');
    fireEvent.change(first, { target: { value: '思齐' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    expect(await screen.findByText('资料已保存')).toBeInTheDocument();
    expect(await screen.findByText('思齐')).toBeInTheDocument();
  });
});

describe('新通知 Toast（N-32）', () => {
  it('收到新通知推送时在页面顶部显示标题和正文', async () => {
    renderPage(<PushListener />);
    act(() =>
      emit({
        type: 'notification',
        item: { id: 'N1', kind: 'deposit', title: '充值到账', body: '100 USDT 已到账（TRC20）', read: false, createdAt: new Date().toISOString() },
      }),
    );
    const box = await screen.findByRole('status');
    expect(box).toHaveTextContent('充值到账');
    expect(box).toHaveTextContent('100 USDT 已到账（TRC20）');
    expect(box.querySelector('.toast-success')).not.toBeNull();
  });

  it('提现被撤销时提示', async () => {
    renderPage(<PushListener />);
    act(() => emit({ type: 'withdraw', status: 'cancelled', amount: '100' }));
    expect(await screen.findByText('提现已撤销，资金已退回')).toBeInTheDocument();
  });
});
