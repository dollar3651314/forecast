/**
 * 市场、下注、开赛提醒、奖池推送。real：/api/markets、/api/me/bets、/api/me/reminders、WS /api/ws/markets（real/markets.ts、real/marketPush.ts）；
 * mock：原型模拟层（mocks/markets.ts，见 mode.ts）。
 */
import * as mock from '../mocks/markets';
import { IS_MOCK } from './mode';
import * as push from './real/marketPush';
import * as real from './real/markets';
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
} from './types';

export type { PoolPushEvent } from './real/marketPush';

export const listMarkets = (q: MarketQuery, page = 1, pageSize = 24): Promise<Page<MarketCard>> =>
  IS_MOCK ? mock.listMarkets(q, page, pageSize) : real.listMarkets(q, page, pageSize);
export const getMarket = (publicNo: string): Promise<MarketDetail> => (IS_MOCK ? mock.getMarket(publicNo) : real.getMarket(publicNo));
export const placeBet = (publicNo: string, req: BetRequest): Promise<BetResult> => (IS_MOCK ? mock.placeBet(publicNo, req) : real.placeBet(publicNo, req));
export const listMyBets = (tab: MyBetTab, page = 1, pageSize = 20): Promise<MyBets> => (IS_MOCK ? mock.listMyBets(tab, page, pageSize) : real.listMyBets(tab, page, pageSize));
export const submitDispute = (publicNo: string, req: DisputeRequest): Promise<DisputeReceipt> => (IS_MOCK ? mock.submitDispute(publicNo, req) : real.submitDispute(publicNo, req));
export const subscribeReminder = (publicNo: string): Promise<ReminderState> => (IS_MOCK ? mock.subscribeReminder(publicNo) : real.subscribeReminder(publicNo));
export const unsubscribeReminder = (publicNo: string): Promise<ReminderState> => (IS_MOCK ? mock.unsubscribeReminder(publicNo) : real.unsubscribeReminder(publicNo));
export const listReminders = (): Promise<ReminderListing> => (IS_MOCK ? mock.listReminders() : real.listReminders());
export const unsubscribeByToken = (token: string): Promise<ReminderUnsubscribed> => (IS_MOCK ? mock.unsubscribeByToken(token) : real.unsubscribeByToken(token));
/** 奖池推送：原型模式下不推送（模拟层的奖池只在下注后变化，页面下注成功后自己刷新） */
export const subscribePools = (marketNos: string[], h: (e: push.PoolPushEvent) => void): (() => void) =>
  IS_MOCK ? () => undefined : push.subscribePools(marketNos, h);
