/**
 * 市场页面共用的显示规则（列表、详情、下注、我的下注；手机和电脑两套界面都用这一份，C64）：
 * - 三种语言的文字按界面语言取，取不到用英文（RQ-18）；
 * - 时间同时显示 UTC 和客户本地时间（需求第 3.7 节："截止 14:55 UTC（你的时间 22:55）"）；
 * - 倒计时按分钟显示，最后 1 分钟按秒（需求 P1）；刷新只换数字，不闪色（C84）。
 */
import { useEffect, useState } from 'react';
import type { I18nText, Lang, MarketDetail, MarketPool, MarketResolution, MarketStatus } from '../api/types';
import { payout, rateFromPercent } from './pool';

export function pickText(t: I18nText | undefined, lang: Lang): string {
  if (!t) return '';
  const v = lang === 'zh-CN' ? t.zhCN : lang === 'zh-TW' ? t.zhTW : t.en;
  return v || t.en;
}

const pad = (n: number) => (n < 10 ? `0${n}` : String(n));

/** "11-01 14:55 UTC"（年份不是今年时带年份） */
export function utcText(iso: string, withYear = false): string {
  const d = new Date(iso);
  const y = d.getUTCFullYear();
  const md = `${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
  const hm = `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
  return `${withYear || y !== new Date().getUTCFullYear() ? `${y}-` : ''}${md} ${hm} UTC`;
}

/** 本地时间：同一天只显示时分，不同天带月日 */
export function localText(iso: string): string {
  const d = new Date(iso);
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const sameDay = d.getUTCDate() === d.getDate() && d.getUTCMonth() === d.getMonth();
  return sameDay ? hm : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`;
}

/** 本地时区和 UTC 是同一个时区（不用再写"你的时间"） */
export const localIsUtc = (): boolean => new Date().getTimezoneOffset() === 0;

export interface Remaining {
  unit: 'd' | 'h' | 'm' | 's';
  /** 主单位和次单位（例如 6 天 7 小时、2 小时 15 分）；只剩分或秒时只有一个 */
  parts: Array<{ unit: 'd' | 'h' | 'm' | 's'; n: number }>;
}

/** 剩余时间（毫秒）→ 显示用的两段：天+小时、小时+分、分（不足 1 小时）、秒（最后 1 分钟） */
export function remaining(ms: number): Remaining | null {
  if (ms <= 0) return null;
  const s = Math.ceil(ms / 1000);
  if (s < 60) return { unit: 's', parts: [{ unit: 's', n: s }] };
  const m = Math.floor(s / 60);
  if (m < 60) return { unit: 'm', parts: [{ unit: 'm', n: m }] };
  const h = Math.floor(m / 60);
  if (h < 24) return { unit: 'h', parts: [{ unit: 'h', n: h }, ...(m % 60 ? [{ unit: 'm' as const, n: m % 60 }] : [])] };
  const d = Math.floor(h / 24);
  return { unit: 'd', parts: [{ unit: 'd', n: d }, ...(h % 24 ? [{ unit: 'h' as const, n: h % 24 }] : [])] };
}

/**
 * 当前时间：平时每 15 秒刷新一次（显示精确到分钟），最近的截止时间在 1 分钟内时每秒刷新（最后 1 分钟按秒显示）。
 * 只换数字，不加动画（C84）。
 */
export function useClock(nearest?: number): number {
  const [now, setNow] = useState(() => Date.now());
  const fast = nearest !== undefined && nearest - now > 0 && nearest - now <= 61_000;
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), fast ? 1000 : 15_000);
    return () => clearInterval(id);
  }, [fast]);
  return now;
}

/** 能下注的状态 */
export const canBetStatus = (s: MarketStatus): boolean => s === 'listed';

/** 状态标签的颜色类别 */
export function statusTone(s: MarketStatus): 'open' | 'wait' | 'review' | 'done' | 'void' {
  if (s === 'listed') return 'open';
  if (s === 'paused' || s === 'closed' || s === 'pending_resolution') return 'wait';
  if (s === 'publicizing' || s === 'disputed') return 'review';
  if (s === 'settled') return 'done';
  return 'void';
}

/** 概率条的宽度（"58%"、"<1%"、">99%"、"—" → 0～100） */
export function barWidth(pct: string): number {
  if (pct === '<1%') return 1;
  if (pct === '>99%') return 99;
  const n = parseFloat(pct);
  return Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 0;
}

/** "1.63" → "1.63x"；"—" 原样 */
export const multText = (m: string): string => (m === '—' ? m : `${m}x`);

// ---------- 结果和裁决（后端 S5 winningOptionNo、S6 / S7 resolution 的两种结构） ----------

/** 现在要显示的结果选项：已结算用 winningOptionNo；公示中、争议中用平台裁决的公示结果（改判后是最终结果）；作废、还没有结果为空 */
export function outcomeOptionNo(m: MarketDetail): string | undefined {
  if (m.winningOptionNo) return m.winningOptionNo;
  const r = m.resolution;
  if (r?.mode === 'platform' && !r.voided) return r.resultOptionNo ?? r.publicizedOptionNo ?? undefined;
  return undefined;
}

/** 平台裁决的争议截止时间（自动裁决没有） */
export const disputeDeadline = (r: MarketResolution | null | undefined): string | undefined =>
  r?.mode === 'platform' ? (r.disputeDeadlineAt ?? undefined) : undefined;

/** 本人现在能不能提交争议 */
export const canDispute = (r: MarketResolution | null | undefined): boolean => r?.mode === 'platform' && r.canDispute;

/** 公开的裁决人：自动裁决 AUTO、系统 SYSTEM 翻译，其他是员工编号（不公开姓名） */
export function resolverText(r: MarketResolution, t: (k: string) => string): string {
  const by = r.resolvedBy ?? '';
  if (by === 'AUTO') return t('md.res.auto');
  if (by === 'SYSTEM') return t('md.res.system');
  return by;
}

/** 推来的奖池（不含本人的持仓、限额）合进详情的奖池：持仓的"赢了约回"按新奖池和费率重算（lib/pool.payout，和后端同一套规则） */
export function mergePool(prev: MarketPool, next: MarketPool): MarketPool {
  const rate = rateFromPercent(next.feeRate);
  const mine = prev.mine?.map((x) => {
    const o = next.options.find((p) => p.optionNo === x.optionNo);
    return o ? { ...x, ifWin: payout(x.amount, o.amount, next.total, rate).payout } : x;
  });
  return { ...next, mine, limits: prev.limits };
}
