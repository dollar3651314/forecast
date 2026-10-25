import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { ApiError, isWalletNotReady } from '../../api/errors';
import { renderPage } from '../../test/renderApp';

// 服务器还没有配置区块链（测试环境没接测试网）时，后端返回 WALLET_NOT_READY
vi.mock('../../api/wallet', async (orig) => {
  const actual = await orig<typeof import('../../api/wallet')>();
  return { ...actual, getDepositAddresses: () => Promise.reject(new ApiError('walletNotReady', undefined, 400)) };
});

import { DepositCard } from './DepositCard';

describe('充值暂未开通', () => {
  it('选网络后显示"充值暂未开通"，不显示"加载失败"和"重试"', async () => {
    renderPage(<DepositCard />, '/assets');
    fireEvent.click(screen.getByRole('radio', { name: /ERC20/ }));
    expect(await screen.findByText('充值暂未开通')).toBeInTheDocument();
    expect(screen.queryByText('加载失败')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
  });

  it('只有 WALLET_NOT_READY 算"暂未开通"，其他错误照常显示加载失败', () => {
    expect(isWalletNotReady(new ApiError('walletNotReady'))).toBe(true);
    expect(isWalletNotReady(new ApiError('network'))).toBe(false);
    expect(isWalletNotReady(new Error('x'))).toBe(false);
  });
});
