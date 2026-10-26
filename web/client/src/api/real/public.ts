/**
 * 不登录也能看的接口（S13，推广功能 MF-3、MF-4、MF-6；后端 `market/web/MarketController.java`、`market/pub/PublicController.java`）：
 * - `GET /api/markets/today?from&to`：今日场次（客户所在时区的"今天"换成 UTC 传过去）
 * - `GET /api/public/results`（status、category、from、page、pageSize ≤ 50）：开奖记录
 * - `GET /api/public/weekly?page`、`GET /api/public/weekly/{week}`：周报
 * - `GET /api/public/pages/how-to-play?lang`：说明页（文字由后端按语言给）
 * - `POST /api/attribution/visit`：带来源参数的访问（MF-1，只用于来源统计；失败不影响页面）
 * 都不需要登录（`quiet401`：服务器关掉公开浏览时返回 401，页面自己去登录页，不当成"登录失效"）。
 */
import { get, post } from '../http';
import type { HowToPage, InsightList, InsightView, Lang, Page, ResultItem, ResultQuery, TodaySlate, WeekDetail, WeekList } from '../types';

export const todaySlate = (from: string, to: string): Promise<TodaySlate> => get<TodaySlate>('/api/markets/today', { query: { from, to }, quiet401: true });

export const listResults = (q: ResultQuery, page: number, pageSize: number): Promise<Page<ResultItem>> =>
  get<Page<ResultItem>>('/api/public/results', { query: { category: q.category, status: q.status, from: q.from, page, pageSize }, quiet401: true });

export const listWeeks = (page: number): Promise<WeekList> => get<WeekList>('/api/public/weekly', { query: { page }, quiet401: true });

export const getWeek = (week: string): Promise<WeekDetail> => get<WeekDetail>(`/api/public/weekly/${encodeURIComponent(week)}`, { quiet401: true });

export const getHowTo = (lang: Lang): Promise<HowToPage> => get<HowToPage>('/api/public/pages/how-to-play', { query: { lang }, quiet401: true });

export const recordVisit = (v: { src?: string; cmp?: string; ref?: string; start?: string }): Promise<void> =>
  post<void>('/api/attribution/visit', v, { device: true, quiet401: true });

/** S15：SEO 页面（MF-11）：`GET /api/public/insights?lang`、`GET /api/public/insights/{slug}?lang` */
export const listInsights = (lang: Lang): Promise<InsightList> => get<InsightList>('/api/public/insights', { query: { lang }, quiet401: true });
export const getInsight = (slug: string, lang: Lang): Promise<InsightView> =>
  get<InsightView>(`/api/public/insights/${encodeURIComponent(slug)}`, { query: { lang }, quiet401: true });
