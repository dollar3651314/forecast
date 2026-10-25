import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderPage } from '../../test/renderApp';

// ERC20、BEP20 只用合约地址：服务器还没配置归集合约时后端只返回 TRC20（开发记录/钱包-合约地址方案.md）
vi.mock('../../api/wallet', async (orig) => {
  const actual = await orig<typeof import('../../api/wallet')>();
  return {
    ...actual,
    getDepositAddresses: () =>
      Promise.resolve([{ network: 'TRC20', address: 'TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH', confirmations: 19, etaMinutes: 1, minDeposit: '1' }]),
  };
});

import { DepositCard } from './DepositCard';

describe('只开通了部分链', () => {
  it('ERC20、BSC 显示"充值暂未开通"，TRC20 照常显示地址', async () => {
    renderPage(<DepositCard />, '/assets');
    fireEvent.click(screen.getByRole('radio', { name: /ERC20/ }));
    expect(await screen.findByText('充值暂未开通')).toBeInTheDocument();
    expect(screen.queryByText('加载失败')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: /BSC/ }));
    expect(await screen.findByText('充值暂未开通')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: /TRC20/ }));
    expect(await screen.findByRole('button', { name: /复制/ })).toBeInTheDocument();
    expect(screen.queryByText('充值暂未开通')).not.toBeInTheDocument();
  });
});
