/**
 * 市场管理、裁决台、结算等 forecast 页面共用的显示规则：三语文字按后台语言取（后台只有中英文，中文界面显示简体）、UTC 时间、状态颜色、
 * 时限（还剩 / 已超时）、通知模板里不能出现的说法（市场策略 F-12、F-15）。
 */
import type { I18nText, MarketStatus } from '../api/fcTypes';
import type { PillTone } from '../components/Pill';

export const textOf = (t: I18nText | undefined | null, lang: string): string => (t ? (lang.startsWith('zh') ? t.zhCN : t.en) || t.en || t.zhCN : '');

const pad = (n: number) => String(n).padStart(2, '0');

/** "11-01 14:55 UTC"；withYear 时 "2026-11-01 14:55 UTC" */
export function utc(iso: string | null | undefined, withYear = false): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return `${withYear ? `${d.getUTCFullYear()}-` : ''}${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())} UTC`;
}

/** UTC 的 "HH:mm" 换成北京时间（需求第 6.2 节：场次表页面同时显示北京时间） */
export function beijing(hhmm: string): string {
  const [h, m] = hhmm.split(':').map(Number);
  if (Number.isNaN(h) || Number.isNaN(m)) return '—';
  return `${pad((h + 8) % 24)}:${pad(m)}`;
}

/** ISO → 后台日期时间输入框里的 "YYYY-MM-DDTHH:mm"（UTC） */
export function toInput(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

/** 输入框的 "YYYY-MM-DDTHH:mm"（按 UTC 理解）→ ISO；空或不合法返回空串（后端报字段错误） */
export function fromInput(v: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(v)) return '';
  return `${v}:00Z`;
}

export function statusTone(s: MarketStatus, submitted = false): PillTone {
  if (s === 'draft') return submitted ? 'pend' : 'muted';
  if (s === 'listed') return 'ok';
  if (s === 'paused') return 'warn';
  if (s === 'closed' || s === 'pending_resolution') return 'delay';
  if (s === 'publicizing' || s === 'disputed') return 'info';
  if (s === 'settled') return 'muted';
  return 'danger';
}

/** 剩余分钟数（负数表示已超时） */
export function minutesLeft(iso: string, now = Date.now()): number {
  return Math.round((Date.parse(iso) - now) / 60_000);
}

/** 市场管理的分组 → 后端的状态筛选（逗号分隔） */
export const TAB_STATUSES: Record<string, string | undefined> = {
  all: undefined,
  draft: 'draft',
  active: 'listed,paused',
  waiting: 'closed,pending_resolution,publicizing,disputed',
  ended: 'settled,cancelled,voided',
};

/** 市场策略 F-12、F-15：通知、卡片里不能出现的诱导收益、和事实不符的说法（后端 S9NotifyFlowTest 对初始模板做同样的检查） */
export const BANNED_WORDS = ['稳赚', '必中', '保本', '翻倍', '胜者瓜分', '最后入场', '别错过', '秒出', '全在链上', '链上公开', '私房钱', '恭喜', '穩賺', '勝者瓜分', '最後入場', '別錯過', 'guaranteed', 'risk-free', "don't miss", 'last chance', 'double your'];

export const bannedIn = (text: string): string[] => BANNED_WORDS.filter((w) => text.toLowerCase().includes(w.toLowerCase()));
