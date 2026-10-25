/** 市场、下注接口。real：/api/markets、/api/me/bets（real/markets.ts）；mock：原型模拟层（mocks/markets.ts，见 mode.ts）。 */
import * as mock from '../mocks/markets';
import { IS_MOCK } from './mode';
import * as real from './real/markets';
import type { BetRequest, BetResult, DisputeReceipt, DisputeRequest, MarketCard, MarketDetail, MarketQuery, MyBets, MyBetTab } from './types';

export const listMarkets = (q: MarketQuery): Promise<MarketCard[]> => (IS_MOCK ? mock.listMarkets(q) : real.listMarkets(q));
export const getMarket = (publicNo: string): Promise<MarketDetail> => (IS_MOCK ? mock.getMarket(publicNo) : real.getMarket(publicNo));
export const placeBet = (publicNo: string, req: BetRequest): Promise<BetResult> => (IS_MOCK ? mock.placeBet(publicNo, req) : real.placeBet(publicNo, req));
export const listMyBets = (tab: MyBetTab, page = 1, pageSize = 50): Promise<MyBets> => (IS_MOCK ? mock.listMyBets(tab, page, pageSize) : real.listMyBets(tab, page, pageSize));
export const submitDispute = (publicNo: string, req: DisputeRequest): Promise<DisputeReceipt> => (IS_MOCK ? mock.submitDispute(publicNo, req) : real.submitDispute(publicNo, req));
