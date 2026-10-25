/** 账户的真实接口：`GET /api/ledger/account`（后端 `ledger/service/LedgerController.java`）。变化经推送 `account` 事件通知（events.ts）。 */
import { get } from '../http';
import type { Account } from '../types';

export const getAccount = (): Promise<Account> => get<Account>('/api/ledger/account');
