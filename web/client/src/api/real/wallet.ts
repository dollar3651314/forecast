/**
 * 钱包的真实接口：`/api/wallet/*`（`wallet/web/WalletController.java`、`wallet/withdraw/WithdrawController.java`）。
 * 资金流水：`GET /api/ledger/entries`（后端 `ledger/service/LedgerController.java`）。
 */
import { del, get, post } from '../http';
import type { DepositAddress, DepositRecord, LedgerItem, LedgerQuery, Network, Page, WhitelistAddress, Withdrawal, WithdrawInfo, WithdrawRequest } from '../types';

export const getDepositAddresses = (): Promise<DepositAddress[]> => get<DepositAddress[]>('/api/wallet/addresses');

/** 最近 50 条充值记录 */
export const listDeposits = (): Promise<DepositRecord[]> => get<DepositRecord[]>('/api/wallet/deposits');

export const listLedger = (q: LedgerQuery): Promise<Page<LedgerItem>> =>
  get<Page<LedgerItem>>('/api/ledger/entries', { query: { type: q.type === 'all' ? undefined : q.type, range: q.range, page: q.page, pageSize: q.pageSize } });

export const getWithdrawInfo = (): Promise<WithdrawInfo> => get<WithdrawInfo>('/api/wallet/withdraw-info');

export const listWhitelist = (): Promise<WhitelistAddress[]> => get<WhitelistAddress[]>('/api/wallet/whitelist');

export const addWhitelist = (f: { network: Network; address: string; label: string }): Promise<WhitelistAddress> =>
  post<WhitelistAddress>('/api/wallet/whitelist', { network: f.network, address: f.address.trim(), label: f.label.trim() });

export async function removeWhitelist(id: string): Promise<void> {
  await del<void>(`/api/wallet/whitelist/${encodeURIComponent(id)}`);
}

export const listWithdrawals = (page: number, pageSize: number): Promise<Page<Withdrawal>> => get<Page<Withdrawal>>('/api/wallet/withdrawals', { query: { page, pageSize } });

/**
 * 提现表单的字段名：后端 `VALIDATION_FAILED` 的 fields（`WithdrawService.submit`：whitelistId、amount）→ 页面表单字段。
 * 目前两边同名，写在这里是为了把对应关系固定下来：后端改字段名时只改这里，页面照样把错误显示在对应的输入框下面。
 */
export const WITHDRAW_FIELDS: Record<string, 'whitelistId' | 'amount'> = { whitelistId: 'whitelistId', amount: 'amount' };

/** 提现的 INSUFFICIENT_BALANCE 是"超过可提金额" */
export const submitWithdraw = (req: WithdrawRequest): Promise<Withdrawal> =>
  post<Withdrawal>('/api/wallet/withdrawals', { whitelistId: req.whitelistId, amount: req.amount.trim() }, { codes: { INSUFFICIENT_BALANCE: 'withdrawInsufficient' }, fieldNames: WITHDRAW_FIELDS });

export const cancelWithdraw = (id: string): Promise<Withdrawal> => post<Withdrawal>(`/api/wallet/withdrawals/${encodeURIComponent(id)}/cancel`);
