/**
 * SEO 页面的模拟层（forecast S15，MF-11；原型预览站、单元测试）：结构和后端 content/page/Insights 一致。
 * 焦点战页用原型里第一个足球市场（数据实时取自模拟的市场，和市场详情一致）；BTC 统计是固定的 7 天数据；文章只有中英文，繁体取简体转换后的（这里直接写好）。
 */
import { ApiError } from '../api/errors';
import type { BtcStats, InsightItem, InsightList, InsightMarket, InsightView, Lang, MarketCard } from '../api/types';
import { pickText } from '../lib/market';
import { respond } from './delay';
import { listMarkets } from './markets';

const AT = '2026-10-25T08:00:00Z';

const BTC: BtcStats = {
  symbol: 'BTCUSDT',
  source: 'Binance 1h K 线（api.binance.com /api/v3/klines）',
  fetchedAt: AT,
  sha256: '3f1c0a9e5b7d2c4e8a6f1b3d5c7e9a0b2d4f6a8c0e2b4d6f8a0c2e4b6d8f0a2c',
  from: '2026-10-18T08:00:00Z',
  to: '2026-10-25T08:00:00Z',
  bars: 168,
  up24: 13,
  down24: 11,
  up24Pct: '54.2',
  up7d: 87,
  down7d: 81,
  up7dPct: '51.8',
  lastClose: '67250.40',
  change24Pct: '+1.35',
  byHour: Array.from({ length: 24 }, (_, h) => {
    const up = [4, 3, 5, 4, 2, 3, 4, 5, 3, 4, 2, 4, 5, 3, 4, 4, 3, 5, 2, 4, 3, 4, 3, 4][h];
    return { hour: h, up, total: 7, pct: ((up * 100) / 7).toFixed(1) };
  }),
};

const ARTICLE = {
  slug: 'article-pool-basics',
  title: { 'zh-CN': '彩池玩法怎么算', 'zh-TW': '彩池玩法怎麼算', en: 'How pari-mutuel pools work' } as Record<Lang, string>,
  body: {
    'zh-CN': '所有人的下注放进同一个奖池。\n\n截止时确定赔率：押中的人按各自下注额分奖池（扣抽成 5%）。\n\n只有一边有人押时全额退款。',
    'zh-TW': '所有人的下注放進同一個獎池。\n\n截止時確定賠率：押中的人按各自下注額分獎池（扣抽成 5%）。\n\n只有一邊有人押時全額退款。',
    en: 'Every stake goes into one pool.\n\nOdds are fixed at close: winners share the pool in proportion to their stakes (after the 5% fee).\n\nIf only one side has bets, everyone is refunded in full.',
  } as Record<Lang, string>,
};

async function focusMarket(): Promise<MarketCard | undefined> {
  const page = await listMarkets({ status: 'all', category: 'FOOTBALL' }, 1, 50);
  return page.items.find((m) => m.status === 'listed') ?? page.items[0];
}

function snapshot(m: MarketCard): InsightMarket {
  return {
    publicNo: m.publicNo,
    category: m.category,
    title: m.title,
    status: m.status,
    openAt: m.openAt,
    closeAt: m.closeAt,
    eventStartAt: m.eventStartAt,
    eventEndAt: m.eventEndAt,
    todaySlate: !!m.todaySlate,
    feeRate: m.feeRate,
    total: m.pool.total,
    bettors: m.pool.bettors,
    options: m.options.map((o) => {
      const p = m.pool.options.find((x) => x.optionNo === o.optionNo);
      return { optionNo: o.optionNo, seq: o.seq, label: o.label, amount: p?.amount ?? '0.00', bettors: p?.bettors ?? 0, pct: p?.pct ?? '—', mult: p && p.mult !== '—' ? p.mult : null };
    }),
    winningOptionNo: m.winningOptionNo ?? null,
    sourcePrimary: null,
    at: new Date().toISOString(),
  };
}

const BTC_TITLE: Record<Lang, string> = { 'zh-CN': 'BTC 每小时涨跌统计', 'zh-TW': 'BTC 每小時漲跌統計', en: 'BTC hourly up/down statistics' };
const BTC_INTRO: Record<Lang, string> = {
  'zh-CN': '过去 7 天共 168 根 1 小时 K 线，87 小时收涨（51.8%）；过去 24 小时 13 小时收涨（54.2%）。',
  'zh-TW': '過去 7 天共 168 根 1 小時 K 線，87 小時收漲（51.8%）；過去 24 小時 13 小時收漲（54.2%）。',
  en: 'Over the past 7 days, 87 of 168 one-hour candles closed up (51.8%); 13 of the last 24 hours closed up (54.2%).',
};

export async function listInsights(lang: Lang): Promise<InsightList> {
  const m = await focusMarket();
  return respond(
    () => {
      const items: InsightItem[] = [{ slug: 'btc-hourly', kind: 'BTC_STATS', title: BTC_TITLE[lang], summary: BTC_INTRO[lang], marketNo: null, updatedAt: AT }];
      if (m) items.push({ slug: `focus-${m.publicNo}`, kind: 'FOCUS', title: pickText(m.title, lang), summary: null, marketNo: m.publicNo, updatedAt: AT });
      items.push({ slug: ARTICLE.slug, kind: 'ARTICLE', title: ARTICLE.title[lang], summary: ARTICLE.body[lang].split('\n\n')[0], marketNo: null, updatedAt: '2026-10-23T09:00:00Z' });
      return { items };
    },
    () => ({ items: [] }),
  );
}

export async function getInsight(slug: string, lang: Lang): Promise<InsightView> {
  const m = await focusMarket();
  return respond(() => {
    const base = { slug, lang, intro: null, body: null, marketNo: null, market: null, btc: null, preview: null, updatedAt: AT, fallbackLang: null };
    if (slug === 'btc-hourly') return { ...base, kind: 'BTC_STATS', title: BTC_TITLE[lang], intro: BTC_INTRO[lang], btc: BTC };
    if (slug === ARTICLE.slug) return { ...base, kind: 'ARTICLE', title: ARTICLE.title[lang], body: ARTICLE.body[lang], updatedAt: '2026-10-23T09:00:00Z' };
    if (m && slug === `focus-${m.publicNo}`) {
      const s = snapshot(m);
      return {
        ...base,
        kind: 'FOCUS',
        title: pickText(m.title, lang),
        marketNo: m.publicNo,
        market: s,
        preview:
          lang === 'en'
            ? { title: 'Pre-match data', body: `The pool totals ${s.total} USDT from ${s.bettors} bettors. Numbers change with the pool.`, publishedAt: AT }
            : lang === 'zh-TW'
              ? { title: '賽前數據', body: `本場獎池合計 ${s.total} USDT，${s.bettors} 人下注。數字隨獎池變化，最終以截止時為準。`, publishedAt: AT }
              : { title: '赛前数据', body: `本场奖池合计 ${s.total} USDT，${s.bettors} 人下注。数字随奖池变化，最终以截止时为准。`, publishedAt: AT },
      };
    }
    throw new ApiError('notFound');
  });
}
