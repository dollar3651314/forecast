/**
 * 市场、下注、开赛提醒的真实接口（forecast S3、S4、S7、S11 的后端）：
 * - `GET /api/markets`（category、status、sort=closeAt|newest|pool、q、page、pageSize ≤ 50）→ `market/web/MarketController.java`
 * - `GET /api/markets/{publicNo}`（带本人持仓和限额、结算信息 settle、开赛提醒 reminder）
 * - `POST /api/markets/{publicNo}/bets`（optionNo、amount、clientReqId）→ `pool/web/BetController.java`
 * - `GET /api/me/bets`（tab、page、pageSize）
 * - `POST /api/markets/{publicNo}/disputes`（claimedOptionNo、reason、link）→ `resolve/web/DisputeController.java`（S7）
 * - 开赛提醒（S11，`market/remind/ReminderController.java`）：`GET /api/me/reminders`、`POST|DELETE /api/markets/{publicNo}/reminder`、
 *   邮件里的退订链接 `POST /api/reminders/unsubscribe`（不用登录）
 * 搜索、按奖池排序、分页都在后端（S11）。
 */
import { del, get, post } from '../http';
import type {
  BetRequest,
  BetResult,
  DisputeReceipt,
  DisputeRequest,
  MarketCard,
  MarketDetail,
  MarketQuery,
  MyBets,
  MyBetTab,
  Page,
  ReminderListing,
  ReminderState,
  ReminderUnsubscribed,
} from '../types';

export function listMarkets(q: MarketQuery, page: number, pageSize: number): Promise<Page<MarketCard>> {
  const status = q.status === 'all' ? undefined : (q.status ?? 'active');
  const keyword = (q.keyword ?? '').trim();
  return get<Page<MarketCard>>('/api/markets', { query: { category: q.category, status, sort: q.sort ?? 'closeAt', q: keyword || undefined, page, pageSize } });
}

export const getMarket = (publicNo: string): Promise<MarketDetail> => get<MarketDetail>(`/api/markets/${encodeURIComponent(publicNo)}`);

export const placeBet = (publicNo: string, req: BetRequest): Promise<BetResult> => post<BetResult>(`/api/markets/${encodeURIComponent(publicNo)}/bets`, req);

export const listMyBets = (tab: MyBetTab, page: number, pageSize: number): Promise<MyBets> => get<MyBets>('/api/me/bets', { query: { tab, page, pageSize } });

/** 提交争议（S7）：能不能提交看详情的 resolution.canDispute；拒绝时 DISPUTE_CLOSED / DISPUTE_NOT_BETTOR / DISPUTE_EXISTS */
export const submitDispute = (publicNo: string, req: DisputeRequest): Promise<DisputeReceipt> =>
  post<DisputeReceipt>(`/api/markets/${encodeURIComponent(publicNo)}/disputes`, req);

/** 开赛提醒：订阅（REMINDER_NOT_AVAILABLE / REMINDER_LOGIN_REQUIRED / REMINDER_TOO_MANY） */
export const subscribeReminder = (publicNo: string): Promise<ReminderState> => post<ReminderState>(`/api/markets/${encodeURIComponent(publicNo)}/reminder`);

export const unsubscribeReminder = (publicNo: string): Promise<ReminderState> => del<ReminderState>(`/api/markets/${encodeURIComponent(publicNo)}/reminder`);

export const listReminders = (): Promise<ReminderListing> => get<ReminderListing>('/api/me/reminders');

/** 邮件里的退订链接（不用登录；随机串不对是 404） */
export const unsubscribeByToken = (token: string): Promise<ReminderUnsubscribed> =>
  post<ReminderUnsubscribed>('/api/reminders/unsubscribe', { token }, { quiet401: true });
