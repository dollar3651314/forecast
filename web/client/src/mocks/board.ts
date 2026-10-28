/**
 * 数据页兼等候页的模拟层（S16，只在原型模式）：BTC 行情是固定的演示数字；原型默认是"上线前"（等候页），launched 用 setMockLaunched 切换。
 * 字段和真实接口一致（api/types.ts 的 Board）。
 */
import { ApiError } from '../api/errors';
import type { Board, BoardItem, Lang, WaitlistLinkResult } from '../api/types';
import { mutate, respond } from './delay';

let launched = false;
const joined = new Set<string>();

/** 原型和测试：切换"已经正式上线" */
export function setMockLaunched(v: boolean): void {
  launched = v;
}

const tx = (en: string, zhCN: string, zhTW: string) => ({ en, zhCN, zhTW });

const SLATE: BoardItem[] = [
  {
    publicNo: '108000000000001001',
    category: 'CRYPTO_1H',
    title: tx('BTC Up or Down: 10-25 13:00–14:00 UTC', 'BTC 涨还是跌：10-25 13:00–14:00 UTC', 'BTC 漲還是跌：10-25 13:00–14:00 UTC'),
    status: 'listed',
    upcoming: false,
    openAt: '2026-10-25T12:00:00Z',
    closeAt: '2026-10-25T12:59:30Z',
    eventStartAt: '2026-10-25T13:00:00Z',
    poolTotal: '1820.00',
    bettors: 23,
    options: [
      { optionNo: '1', label: tx('Up', '涨', '漲'), bettors: 14, amount: '1120.00', pct: '61.5%' },
      { optionNo: '2', label: tx('Down', '跌', '跌'), bettors: 9, amount: '700.00', pct: '38.5%' },
    ],
  },
  {
    publicNo: '108000000000001002',
    category: 'FOOTBALL',
    title: tx('Arsenal vs Chelsea: over 2.5 goals?', '阿森纳 vs 切尔西：总进球大于 2.5？', '阿森納 vs 切爾西：總進球大於 2.5？'),
    status: 'listed',
    upcoming: true,
    openAt: '2026-10-25T15:00:00Z',
    closeAt: '2026-10-25T19:55:00Z',
    eventStartAt: '2026-10-25T20:00:00Z',
    poolTotal: '0.00',
    bettors: 0,
    options: [
      { optionNo: '3', label: tx('Over', '大', '大'), bettors: 0, amount: '0.00', pct: '—' },
      { optionNo: '4', label: tx('Under', '小', '小'), bettors: 0, amount: '0.00', pct: '—' },
    ],
  },
];

/** S19：Cloudflare 上的看板带数据来源（OKX / Binance）；原型和测试里默认不带（= 测试服务器的后端） */
let mockSource: string | null = null;
export function setMockBoardSource(s: string | null): void {
  mockSource = s;
}

export function getBoard(): Promise<Board> {
  const base = (): Board => ({
    updatedAt: '2026-10-25T08:00:00Z',
    btc: {
      available: true,
      symbol: 'BTCUSDT',
      price: '67234.50',
      hours: 24,
      up: 14,
      down: 10,
      upPct: '58.3',
      downPct: '41.7',
      from: '2026-10-24T08:00:00Z',
      to: '2026-10-25T08:00:00Z',
      updatedAt: '2026-10-25T08:00:12Z',
      ...(mockSource ? { source: mockSource } : {}),
    },
    launched,
    waitlistOpen: !launched,
    slate: launched ? { available: true, items: SLATE, next: null } : { available: false, items: [], next: null },
    polymarket: { enabled: false },
    telegram: { channelZh: 'https://t.me/quickcome_cn', channelEn: null, bot: 'https://t.me/quickcome_bot?start=s-web__c-data' },
  });
  return respond(base, () => ({
    ...base(),
    btc: { available: false, symbol: null, price: null, hours: 0, up: 0, down: 0, upPct: null, downPct: null, from: null, to: null, updatedAt: null },
    slate: { available: launched, items: [], next: null },
  }));
}

export function joinWaitlist(email: string, _lang: Lang, website: string): Promise<{ status: 'check_email' }> {
  return mutate(() => {
    if (launched) throw new ApiError('waitlistClosed', undefined, 409);
    if (!website) joined.add(email.trim().toLowerCase());
    return { status: 'check_email' as const };
  });
}

export function confirmWaitlist(token: string): Promise<WaitlistLinkResult> {
  return mutate(() => {
    if (!/^[0-9a-f]{32}$/.test(token)) throw new ApiError('notFound', undefined, 404);
    if (token.startsWith('0000')) throw new ApiError('waitlistLinkExpired', undefined, 410);
    return { status: 'confirmed' as const, lang: 'zh-CN' as Lang };
  });
}

export function unsubscribeWaitlist(token: string): Promise<WaitlistLinkResult> {
  return mutate(() => {
    if (!/^[0-9a-f]{32}$/.test(token)) throw new ApiError('notFound', undefined, 404);
    return { status: 'unsubscribed' as const, lang: 'zh-CN' as Lang };
  });
}
