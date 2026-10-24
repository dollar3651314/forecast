/**
 * 展示格式化。只做"把后端给的字符串变成好看的样子"，不做任何金额计算。
 */
import { dec, isDecimalString, toFixed, type Dec } from './decimal';

const PLACEHOLDER = '—';

function groupThousands(intStr: string): string {
  return intStr.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** 把定点值或十进制字符串格式化为带千分位的字符串 */
export function formatDecimal(
  value: string | Dec | null | undefined,
  dp: number,
  opts: { sign?: boolean } = {},
): string {
  if (value === null || value === undefined) return PLACEHOLDER;
  let d: Dec;
  if (typeof value === 'bigint') d = value;
  else if (isDecimalString(value)) d = dec(value);
  else return PLACEHOLDER;
  const fixed = toFixed(d, dp);
  const negative = fixed.startsWith('-');
  const body = negative ? fixed.slice(1) : fixed;
  const [i, f] = body.split('.');
  const grouped = groupThousands(i) + (f !== undefined ? `.${f}` : '');
  if (negative) return `-${grouped}`;
  if (opts.sign && /[1-9]/.test(body)) return `+${grouped}`;
  return grouped;
}

/** 金额，默认 2 位小数（USDT） */
export const formatAmount = (v: string | Dec | null | undefined, opts: { sign?: boolean; dp?: number } = {}) =>
  formatDecimal(v, opts.dp ?? 2, opts);

/** 价格，按品种小数位 */
export const formatPrice = (v: string | Dec | null | undefined, digits: number) => formatDecimal(v, digits);

/** 百分比：入参已经是百分数（如 "0.42" 表示 0.42%） */
export const formatPercent = (v: string | Dec | null | undefined, opts: { sign?: boolean; dp?: number } = {}) => {
  const s = formatDecimal(v, opts.dp ?? 2, opts);
  return s === PLACEHOLDER ? s : `${s}%`;
};

/** 手数：固定 2 位 */
export const formatLots = (v: string | Dec | null | undefined) => formatDecimal(v, 2);

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/** 日期时间：YYYY-MM-DD HH:mm（本地时区）；非法日期显示占位符 */
export function formatDateTime(iso: string | null | undefined, opts: { short?: boolean; seconds?: boolean } = {}): string {
  if (!iso) return PLACEHOLDER;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return PLACEHOLDER;
  const date = opts.short ? `${pad(d.getMonth() + 1)}-${pad(d.getDate())}` : `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}${opts.seconds ? `:${pad(d.getSeconds())}` : ''}`;
  return `${date} ${time}`;
}

/** 剩余时长：h:mm:ss 或 mm:ss；负数按 0 处理 */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

/** 地址缩写：前 6 后 5 */
export function shortAddress(addr: string): string {
  return addr.length > 14 ? `${addr.slice(0, 6)}…${addr.slice(-5)}` : addr;
}

/** 地址分组显示（每 4 位一组），便于肉眼核对 */
export function groupAddress(addr: string): string {
  return addr.replace(/(.{4})(?=.)/g, '$1 ');
}
