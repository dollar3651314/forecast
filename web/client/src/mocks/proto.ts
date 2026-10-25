/**
 * 原型面板用到的模拟操作：模拟推送、设置 KYC 状态、重置数据。forecast S1 删除了复制源的成交、止盈止损、强平模拟；
 * 下注、结算的模拟在原型阶段（M1）加。
 */
import type { KycStatus } from '../api/types';
import { emit } from './bus';
import { resetDb } from './db';
import { protoKycStatus, protoSetKyc, pushNotification } from './user';

export type PushKind = 'deposit' | 'withdraw';

export function simulatePush(kind: PushKind): void {
  if (kind === 'deposit') {
    pushNotification('deposit', ['充值已到账', 'Deposit credited'], ['您通过 TRC20 充值的 500.00 USDT 已到账。（原型模拟推送）', 'Your deposit of 500.00 USDT via TRC20 has been credited. (Prototype push)']);
    emit({ type: 'account' });
  } else {
    pushNotification('withdraw', ['提现已完成', 'Withdrawal completed'], ['200.00 USDT（BSC）已上链完成，交易号 0x7f3a…e21b。（原型模拟推送）', '200.00 USDT (BSC) has been sent on-chain, tx 0x7f3a…e21b. (Prototype push)']);
    emit({ type: 'withdraw', status: 'completed', amount: '200.00' });
  }
}

export { protoSetKyc, protoKycStatus };
export type { KycStatus };

export function resetPrototypeData(): void {
  resetDb();
  emit({ type: 'account' });
}
