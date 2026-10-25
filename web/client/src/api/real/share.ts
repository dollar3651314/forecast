/**
 * 分享卡片（S14，MF-5；后端 `market/card/CardController`）：
 * - `GET /api/public/share?kind=market|countdown|weekly&no=|week=&lang=`：市场卡（市场已结束时后端换成开奖卡）、周报卡的链接、图片和文字；不登录也能取（登录了链接带分享人 ref）
 * - `POST /api/me/share`：生成挑战卡、押中的开奖卡（要登录）
 */
import { get, post } from '../http';
import type { Lang, ShareCard, ShareCreate } from '../types';

export const getShare = (q: { kind: 'market' | 'countdown' | 'weekly'; no?: string; week?: string }, lang: Lang): Promise<ShareCard> =>
  get<ShareCard>('/api/public/share', { query: { kind: q.kind, no: q.no, week: q.week, lang }, quiet401: true });

export const createShare = (req: ShareCreate, lang: Lang): Promise<ShareCard> => post<ShareCard>('/api/me/share', { ...req, lang });
