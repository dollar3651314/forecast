/** 市场管理、裁决台共用的显示规则（forecast M1）：三语文字按后台语言取（后台只有中英文），UTC 时间，状态的颜色。 */
import type { AdminMarketStatus, I18nText } from '../api/types';
import type { PillTone } from '../components/Pill';

export const textOf = (t: I18nText | undefined, lang: string): string => (t ? (lang.startsWith('zh') ? t.zhCN : t.en) || t.en : '');

const pad = (n: number) => String(n).padStart(2, '0');
/** "11-01 14:55 UTC" */
export function utc(iso: string, withYear = false): string {
  const d = new Date(iso);
  return `${withYear ? `${d.getUTCFullYear()}-` : ''}${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())} UTC`;
}

export function statusTone(s: AdminMarketStatus, submitted = false): PillTone {
  if (s === 'draft') return submitted ? 'pend' : 'muted';
  if (s === 'listed') return 'ok';
  if (s === 'paused') return 'warn';
  if (s === 'closed' || s === 'pending_resolution') return 'delay';
  if (s === 'publicizing' || s === 'disputed') return 'info';
  if (s === 'settled') return 'muted';
  return 'danger';
}

/** 剩余分钟数 → "1 小时 12 分"（负数表示已超时） */
export function minutesLeft(iso: string, now = Date.now()): number {
  return Math.round((Date.parse(iso) - now) / 60_000);
}
