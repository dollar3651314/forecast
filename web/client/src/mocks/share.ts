/**
 * 分享卡片的模拟层（S14，只在原型模式）：卡片图片用站点图标占位（真实图片由服务器生成，market/card/CardPainter），
 * 链接和真实接口同一个格式（/m/{编号}?src=share&cmp=card-<种类>&cl=<语言>）。
 */
import { ApiError } from '../api/errors';
import type { Lang, ShareCard, ShareCreate, ShareKind } from '../api/types';
import { respond } from './delay';
import { getMarket } from './markets';

/** 原型里卡片图片用站点图标占位（真实图片由服务器生成，market/card/CardPainter） */
const PLACEHOLDER = '/favicon.svg';

function card(kind: ShareKind, marketNo: string, title: string, lang: Lang, extra = ''): ShareCard {
  return {
    kind,
    url: `${window.location.origin}/m/${marketNo}?src=share&cmp=card-${kind}&cl=${lang}${extra}`,
    imageUrl: PLACEHOLDER,
    title,
    text: `${title}\n(prototype)`,
  };
}

export async function getShare(q: { kind: 'market' | 'countdown' | 'weekly'; no?: string; week?: string }, lang: Lang): Promise<ShareCard> {
  if (q.kind === 'weekly') {
    return respond(() => ({ ...card('weekly', '', `${q.week} weekly`, lang), url: `${window.location.origin}/results/weekly/${q.week}?src=share&cmp=card-weekly&cl=${lang}` }));
  }
  const m = await getMarket(q.no ?? '');
  const ended = m.status === 'settled' || m.status === 'voided' || m.status === 'cancelled';
  return respond(() => card(ended ? 'result' : q.kind === 'countdown' ? 'countdown' : 'market', m.publicNo, m.title.en, lang));
}

export async function createShare(req: ShareCreate, lang: Lang): Promise<ShareCard> {
  const m = await getMarket(req.marketNo);
  if (req.kind === 'challenge' && m.status !== 'listed') throw new ApiError('cardNotAvailable');
  return respond(() => card(req.kind, m.publicNo, m.title.en, lang, `&card=proto${req.kind}${req.showAmount ? '1' : '0'}`));
}
