/**
 * 账户的模拟层（原型模式）。forecast S1：复制源的持仓、挂单、撮合都删除了；S2：三项余额——可用、下注中、提现冻结，余额 = 可用 + 下注中。
 */
import type { Account } from '../api/types';
import { add, dec, toFixed, type Dec } from '../lib/decimal';
import { db } from './db';
import { respond } from './delay';

/** 可用：可以提现（S4 起可以下注）的金额 */
export function availableNow(): Dec {
  return dec(db.account.available);
}

export function getAccount(): Promise<Account> {
  return respond(
    () => ({
      currency: 'USDT',
      balance: toFixed(add(dec(db.account.available), dec(db.account.betLocked)), 2),
      available: db.account.available,
      betLocked: db.account.betLocked,
      withdrawFrozen: db.account.withdrawFrozen,
    }),
    () => ({ currency: 'USDT', balance: '0.00', available: '0.00', betLocked: '0.00', withdrawFrozen: '0.00' }),
  );
}
