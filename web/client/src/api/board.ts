/**
 * 数据页兼等候页（S16，MF-8、MF-17）。real：api/real/board.ts；mock：原型模拟层（mocks/board.ts）。
 */
import * as mock from '../mocks/board';
import { IS_MOCK } from './mode';
import * as real from './real/board';
import type { Attribution, Board, Lang, WaitlistLinkResult } from './types';

export const getBoard = (): Promise<Board> => (IS_MOCK ? mock.getBoard() : real.getBoard());
export const joinWaitlist = (email: string, lang: Lang, website: string, attribution?: Attribution): Promise<{ status: 'check_email' }> =>
  IS_MOCK ? mock.joinWaitlist(email, lang, website) : real.joinWaitlist(email, lang, website, attribution);
export const confirmWaitlist = (token: string): Promise<WaitlistLinkResult> => (IS_MOCK ? mock.confirmWaitlist(token) : real.confirmWaitlist(token));
export const unsubscribeWaitlist = (token: string): Promise<WaitlistLinkResult> => (IS_MOCK ? mock.unsubscribeWaitlist(token) : real.unsubscribeWaitlist(token));
