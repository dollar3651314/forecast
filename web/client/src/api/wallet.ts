/** 资金接口。real：/api/wallet/*（资金流水见 real/wallet.ts 的说明）；mock：原型模拟层（见 mode.ts）。 */
import * as mock from '../mocks/wallet';
import { IS_MOCK } from './mode';
import * as real from './real/wallet';
import type { DepositAddress, DepositRecord, LedgerItem, LedgerQuery, Network, Page, WhitelistAddress, Withdrawal, WithdrawInfo, WithdrawRequest } from './types';

export const getDepositAddresses = (): Promise<DepositAddress[]> => (IS_MOCK ? mock.getDepositAddresses() : real.getDepositAddresses());
export const listDeposits = (): Promise<DepositRecord[]> => (IS_MOCK ? mock.listDeposits() : real.listDeposits());
export const listLedger = (q: LedgerQuery): Promise<Page<LedgerItem>> => (IS_MOCK ? mock.listLedger(q) : real.listLedger(q));
export const getWithdrawInfo = (): Promise<WithdrawInfo> => (IS_MOCK ? mock.getWithdrawInfo() : real.getWithdrawInfo());
export const listWhitelist = (): Promise<WhitelistAddress[]> => (IS_MOCK ? mock.listWhitelist() : real.listWhitelist());
export const addWhitelist = (f: { network: Network; address: string; label: string }): Promise<WhitelistAddress> => (IS_MOCK ? mock.addWhitelist(f) : real.addWhitelist(f));
export const removeWhitelist = (id: string): Promise<void> => (IS_MOCK ? mock.removeWhitelist(id) : real.removeWhitelist(id));
export const listWithdrawals = (page: number, pageSize = 20): Promise<Page<Withdrawal>> => (IS_MOCK ? mock.listWithdrawals(page, pageSize) : real.listWithdrawals(page, pageSize));
export const submitWithdraw = (req: WithdrawRequest): Promise<Withdrawal> => (IS_MOCK ? mock.submitWithdraw(req) : real.submitWithdraw(req));
export const cancelWithdraw = (id: string): Promise<Withdrawal> => (IS_MOCK ? mock.cancelWithdraw(id) : real.cancelWithdraw(id));
