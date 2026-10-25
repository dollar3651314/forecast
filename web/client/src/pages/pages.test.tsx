import { screen, waitFor } from '@testing-library/react';
import { act } from 'react';
import { setProtoState } from '../api/prototype';
import { resetDb } from '../mocks/db';
import { protoSetKyc } from '../mocks/user';
import { renderPage } from '../test/renderApp';
import Assets from './assets/Assets';
import Notifications from './me/Notifications';
import Withdraw from './assets/Withdraw';

afterEach(() => {
  act(() => setProtoState({ scenario: 'normal' }));
  resetDb();
});

describe('关键页面渲染', () => {
  it('资产：余额、可用、下注中、提现冻结；空数据显示 0', async () => {
    renderPage(<Assets />, '/assets');
    // S2：余额 = 可用 + 下注中（M1 原型数据：可用 1,234.56，下注中 550.00）
    expect(await screen.findByText('1,784.56')).toBeInTheDocument();
    expect(screen.getAllByText('1,234.56').length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: '下注中' })).toHaveAttribute('href', '/bets');
    expect(screen.getAllByText('550.00').length).toBeGreaterThan(0);
    expect(screen.getAllByText('1,000.00').length).toBeGreaterThan(0);
    expect(screen.queryByText('净值')).not.toBeInTheDocument();
    act(() => setProtoState({ scenario: 'empty' }));
    expect(await screen.findByText('暂无资金流水')).toBeInTheDocument();
  });

  it('资产：接口出错时显示原因和重试，不显示错误代码', async () => {
    act(() => setProtoState({ scenario: 'error' }));
    renderPage(<Assets />, '/assets');
    expect((await screen.findAllByRole('button', { name: '重试' })).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/服务暂时不可用/).length).toBeGreaterThan(0);
  });

  it('通知中心：未读数和空状态', async () => {
    renderPage(<Notifications />, '/me/notifications');
    expect(await screen.findByText('3 条未读')).toBeInTheDocument();
    act(() => setProtoState({ scenario: 'empty' }));
    expect(await screen.findByText('暂无通知')).toBeInTheDocument();
  });

  it('提现：KYC 未通过时提示去认证', async () => {
    protoSetKyc('none');
    renderPage(<Withdraw />, '/assets/withdraw');
    expect(await screen.findByRole('button', { name: '去认证' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '提交提现' })).not.toBeInTheDocument();
  });

  it('提现：已认证时显示表单和各状态的记录', async () => {
    renderPage(<Withdraw />, '/assets/withdraw');
    expect(await screen.findByRole('button', { name: '提交提现' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('冷静期中')).toBeInTheDocument());
    expect(screen.getByText('失败已退款')).toBeInTheDocument();
  });
});

import { fireEvent } from '@testing-library/react';
import { DepositCard } from './assets/DepositCard';
import { emit } from '../mocks/bus';
import { db } from '../mocks/db';

describe('充值', () => {
  it('选网络后显示最小充值金额；下方显示充值记录的各种状态（AC-41）', async () => {
    renderPage(<DepositCard />, '/assets');
    expect(await screen.findByText('低于最小充值金额')).toBeInTheDocument();
    expect(screen.getByText(/待确认 7\/20/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: /TRC20/ }));
    expect(await screen.findByText('单笔最少 1 USDT，低于该金额不会入账')).toBeInTheDocument();
  });

  it('收到充值推送（发现待确认）时重新拉取充值记录，不用刷新页面（E2E-2）', async () => {
    renderPage(<DepositCard />, '/assets');
    expect(await screen.findByText('低于最小充值金额')).toBeInTheDocument();
    expect(screen.queryByText('4,321.00')).not.toBeInTheDocument();
    db.ledger.unshift({ id: 'Lnew', type: 'deposit', amount: '4321', ref: 'ERC20', time: new Date().toISOString() });
    act(() => emit({ type: 'deposit', status: 'pending', network: 'ERC20', amount: '4321', confirmations: 1 }));
    expect(await screen.findByText('4,321.00')).toBeInTheDocument();
  });
});
