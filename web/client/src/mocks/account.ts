/**
 * 账户的模拟层（原型模式）。forecast S1：复制源的持仓、挂单、撮合都删除了，账户只有余额和提现冻结；可用 = 余额（S2 起减去下注锁定）。
 */
import type { Account } from '../api/types';
import { dec, type Dec } from '../lib/decimal';
import { db } from './db';
import { respond } from './delay';

/** 可用：可以提现（S4 起可以下注）的金额 */
export function availableNow(): Dec {
  return dec(db.account.balance);
}

export function getAccount(): Promise<Account> {
  return respond(
    () => ({ currency: 'USDT', balance: db.account.balance, withdrawFrozen: db.account.withdrawFrozen, available: db.account.balance }),
    () => ({ currency: 'USDT', balance: '0.00', withdrawFrozen: '0.00', available: '0.00' }),
  );
}
