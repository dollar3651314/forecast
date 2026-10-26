/**
 * 不登录也能看的数据（S13）：今日场次、开奖记录、周报、说明页、来源访问。real：api/real/public.ts；mock：原型模拟层（mocks/markets.ts）。
 */
import * as mock from '../mocks/markets';
import { IS_MOCK } from './mode';
import * as real from './real/public';
import * as insightMock from '../mocks/insights';
import type { HowToPage, InsightList, InsightView, Lang, Page, ResultItem, ResultQuery, TodaySlate, WeekDetail, WeekList } from './types';

export const todaySlate = (from: string, to: string): Promise<TodaySlate> => (IS_MOCK ? mock.todaySlate(from, to) : real.todaySlate(from, to));
export const listResults = (q: ResultQuery, page = 1, pageSize = 20): Promise<Page<ResultItem>> =>
  IS_MOCK ? mock.listResults(q, page, pageSize) : real.listResults(q, page, pageSize);
export const listWeeks = (page = 1): Promise<WeekList> => (IS_MOCK ? mock.listWeeks(page) : real.listWeeks(page));
export const getWeek = (week: string): Promise<WeekDetail> => (IS_MOCK ? mock.getWeek(week) : real.getWeek(week));
export const getHowTo = (lang: Lang): Promise<HowToPage> => (IS_MOCK ? mock.getHowTo(lang) : real.getHowTo(lang));
/** 原型模式不记访问 */
export const recordVisit = (v: { src?: string; cmp?: string; ref?: string; start?: string }): Promise<void> => (IS_MOCK ? Promise.resolve() : real.recordVisit(v));
/** S15：SEO 页面（焦点战、BTC 每小时涨跌统计、文章；MF-11） */
export const listInsights = (lang: Lang): Promise<InsightList> => (IS_MOCK ? insightMock.listInsights(lang) : real.listInsights(lang));
export const getInsight = (slug: string, lang: Lang): Promise<InsightView> => (IS_MOCK ? insightMock.getInsight(slug, lang) : real.getInsight(slug, lang));
