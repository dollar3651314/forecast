/** 账户接口。real：GET /api/ledger/account；mock：原型模拟层（见 mode.ts）。 */
import * as mock from '../mocks/account';
import { IS_MOCK } from './mode';
import * as real from './real/account';
import type { Account } from './types';

export const getAccount = (): Promise<Account> => (IS_MOCK ? mock.getAccount() : real.getAccount());
