/**
 * 数据页兼等候页（S16，MF-8、MF-17；后端 `growth/board/BoardController.java`、`growth/waitlist/WaitlistController.java`）：
 * - `GET /api/public/board`：BTC 行情、今天的场次（上线以后）、Telegram 链接
 * - `POST /api/public/waitlist`：留邮箱（只发确认邮件；回答不说明邮箱是否留过）
 * - `POST /api/public/waitlist/confirm`、`/unsubscribe`：邮件里的确认、退订链接（凭随机串，不用登录）
 * 都不需要登录。
 */
import { get, post } from '../http';
import type { Attribution, Board, Lang, WaitlistLinkResult } from '../types';

export const getBoard = (): Promise<Board> => get<Board>('/api/public/board', { quiet401: true });

export const joinWaitlist = (email: string, lang: Lang, website: string, attribution?: Attribution): Promise<{ status: 'check_email' }> => {
  const a = attribution?.last ?? attribution?.first;
  return post('/api/public/waitlist', {
    email: email.trim(),
    lang,
    website,
    attribution: a ? { src: a.src, cmp: a.cmp, ref: a.ref, start: a.start } : undefined,
  }, { quiet401: true });
};

export const confirmWaitlist = (token: string): Promise<WaitlistLinkResult> => post('/api/public/waitlist/confirm', { token }, { quiet401: true });

export const unsubscribeWaitlist = (token: string): Promise<WaitlistLinkResult> => post('/api/public/waitlist/unsubscribe', { token }, { quiet401: true });
