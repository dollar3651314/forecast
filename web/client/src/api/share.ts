/**
 * 分享卡片（S14，MF-5）。real：api/real/share.ts；mock：原型模拟层（mocks/share.ts）。
 */
import * as mock from '../mocks/share';
import { IS_MOCK } from './mode';
import * as real from './real/share';
import type { Lang, ShareCard, ShareCreate } from './types';

export const getShare = (q: { kind: 'market' | 'countdown' | 'weekly'; no?: string; week?: string }, lang: Lang): Promise<ShareCard> =>
  IS_MOCK ? mock.getShare(q, lang) : real.getShare(q, lang);
export const createShare = (req: ShareCreate, lang: Lang): Promise<ShareCard> => (IS_MOCK ? mock.createShare(req, lang) : real.createShare(req, lang));
