/**
 * 账户的全局状态：余额、可用、下注中、提现冻结。数据来自 GET /api/ledger/account，收到推送 `account` 时重新拉取。
 * forecast S1：复制源按实时报价计算净值、浮动盈亏、保证金水平的部分随差价合约删除。
 */
import { useMemo, type ReactNode } from 'react';
import { getAccount } from '../api/account';
import { useAsync } from '../hooks/useAsync';
import { useServerEvent } from '../hooks/useServerEvent';
import { dec } from '../lib/decimal';
import { AccountContext, type AccountMetrics } from './contexts';

export function AccountProvider({ children }: { children: ReactNode }) {
  const acc = useAsync(getAccount, 'account');

  useServerEvent((e) => {
    if (e.type === 'account') acc.reload();
  });

  const metrics = useMemo<AccountMetrics | undefined>(
    () =>
      acc.data
        ? { balance: dec(acc.data.balance), available: dec(acc.data.available), betLocked: dec(acc.data.betLocked), withdrawFrozen: dec(acc.data.withdrawFrozen) }
        : undefined,
    [acc.data],
  );

  const value = useMemo(
    () => ({
      // 只有首次加载（还没有数据）才算加载中，推送触发的刷新不闪烁
      loading: acc.loading && !acc.data,
      error: acc.error,
      account: acc.data,
      metrics,
      reload: () => acc.reload(),
    }),
    [acc, metrics],
  );
  return <AccountContext.Provider value={value}>{children}</AccountContext.Provider>;
}
