/**
 * 市场、下注的真实接口（forecast S3、S4 的后端）：
 * - `GET /api/markets`（category、status、sort=closeAt|newest、page、pageSize ≤ 50）→ `market/web/MarketController.java`
 * - `GET /api/markets/{publicNo}`（带本人持仓和限额）
 * - `POST /api/markets/{publicNo}/bets`（optionNo、amount、clientReqId）→ `pool/web/BetController.java`
 * - `GET /api/me/bets`（tab、page、pageSize）
 * - `POST /api/markets/{publicNo}/disputes`（claimedOptionNo、reason、link）→ `resolve/web/DisputeController.java`（S7）
 * 裁决与公示在详情的 `resolution`（S6 自动裁决、S7 平台裁决）。后端还没有的：按关键字搜索、按奖池排序（这一页内在前端做）。
 */
import { get, post } from '../http';
import type { BetRequest, BetResult, DisputeReceipt, DisputeRequest, MarketCard, MarketDetail, MarketQuery, MyBets, MyBetTab, Page } from '../types';

const PAGE_SIZE = 50;

function matches(m: MarketCard, keyword: string): boolean {
  const k = keyword.trim().toLowerCase();
  return !k || [m.title.en, m.title.zhCN, m.title.zhTW, m.publicNo].some((s) => s.toLowerCase().includes(k));
}

export async function listMarkets(q: MarketQuery): Promise<MarketCard[]> {
  const status = q.status === 'all' ? undefined : (q.status ?? 'active');
  const sort = q.sort === 'newest' ? 'newest' : 'closeAt';
  const page = await get<Page<MarketCard>>('/api/markets', { query: { category: q.category, status, sort, page: 1, pageSize: PAGE_SIZE } });
  const items = page.items.filter((m) => matches(m, q.keyword ?? ''));
  if (q.sort === 'pool') items.sort((a, b) => Number(b.pool.total) - Number(a.pool.total));
  return items;
}

export const getMarket = (publicNo: string): Promise<MarketDetail> => get<MarketDetail>(`/api/markets/${encodeURIComponent(publicNo)}`);

export const placeBet = (publicNo: string, req: BetRequest): Promise<BetResult> => post<BetResult>(`/api/markets/${encodeURIComponent(publicNo)}/bets`, req);

export const listMyBets = (tab: MyBetTab, page: number, pageSize: number): Promise<MyBets> => get<MyBets>('/api/me/bets', { query: { tab, page, pageSize } });

/** 提交争议（S7）：能不能提交看详情的 resolution.canDispute；拒绝时 DISPUTE_CLOSED / DISPUTE_NOT_BETTOR / DISPUTE_EXISTS */
export const submitDispute = (publicNo: string, req: DisputeRequest): Promise<DisputeReceipt> =>
  post<DisputeReceipt>(`/api/markets/${encodeURIComponent(publicNo)}/disputes`, req);
