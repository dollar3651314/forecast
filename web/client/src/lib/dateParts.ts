/**
 * "已知日期"（出生日期等）三框输入的纯函数：拆分、合成、粘贴解析、自动跳格判断、逐框找错。
 * 组件见 `components/DateFields.tsx`，说明见 `技术方案/v2-日期输入.md`。
 *
 * 对外的值始终是一个字符串：
 * - 三个框都空 → ''；
 * - 年是 4 位数字、月和日是 1–2 位数字 → 补零后的 `YYYY-MM-DD`（后端接口不变）；
 * - 其他（没填完） → 原样拼成 `年-月-日`（例如 `1988-5-`），校验时报"没填完整"。
 */

export type DatePart = 'y' | 'm' | 'd';
export interface DateParts {
  y: string;
  m: string;
  d: string;
}

export const PART_MAX: Record<DatePart, number> = { y: 4, m: 2, d: 2 };

/** 三个框的顺序：中文 年 月 日；英文 日 月 年（国际通行写法，和 GOV.UK 一致；每个框都有标签，不会和美式写法混淆） */
export function partOrder(lang: string): DatePart[] {
  return lang.startsWith('zh') ? ['y', 'm', 'd'] : ['d', 'm', 'y'];
}

/** 全角数字换成半角（中文输入法下可能打出全角） */
export function toAsciiDigits(s: string): string {
  return s.replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0));
}

export function splitDate(v: string | null | undefined): DateParts {
  const [y = '', m = '', d = ''] = (v ?? '').split('-');
  const clean = (s: string) => toAsciiDigits(s).replace(/\D/g, '');
  return { y: clean(y), m: clean(m), d: clean(d) };
}

export function joinDate(p: DateParts): string {
  if (!p.y && !p.m && !p.d) return '';
  if (/^\d{4}$/.test(p.y) && /^\d{1,2}$/.test(p.m) && /^\d{1,2}$/.test(p.d)) {
    return `${p.y}-${p.m.padStart(2, '0')}-${p.d.padStart(2, '0')}`;
  }
  return `${p.y}-${p.m}-${p.d}`;
}

/**
 * 这一格是否已经填完、可以自动跳到下一格：年满 4 位；月满 2 位或第一位是 2–9（不可能再接一位）；
 * 日满 2 位或第一位是 4–9。
 */
export function partComplete(k: DatePart, v: string): boolean {
  if (k === 'y') return v.length >= 4;
  if (v.length >= 2) return true;
  if (v.length !== 1) return false;
  return k === 'm' ? v >= '2' : v >= '4';
}

const fits = (p: DateParts) => /^\d{4}$/.test(p.y) && /^\d{1,2}$/.test(p.m) && /^\d{1,2}$/.test(p.d);

/** 年在最后的写法（`12/05/1988`、`12051988`）里日和月谁在前：哪一个大于 12 就是日；都不超过 12 时按界面语言（中文按 月/日，英文按 日/月） */
function dayMonth(a: string, b: string, order: DatePart[]): { d: string; m: string } {
  if (Number(a) > 12) return { d: a, m: b };
  if (Number(b) > 12) return { d: b, m: a };
  return order[0] === 'd' ? { d: a, m: b } : { d: b, m: a };
}

/**
 * 解析整串粘贴或自动填充的日期，例如 `1988-05-12`、`1988/5/12`、`1988年5月12日`、`19880512`、`12/05/1988`、`12.5.1988`。
 * 认不出时返回 null（调用方按普通输入处理）。
 */
export function parseDateText(text: string, order: DatePart[]): DateParts | null {
  const s = toAsciiDigits(text).trim();
  if (!s) return null;
  const groups = s.split(/\D+/).filter(Boolean);
  let p: DateParts | null = null;
  if (groups.length === 3) {
    const [a, b, c] = groups;
    if (a.length === 4) p = { y: a, m: b, d: c };
    else if (c.length === 4) p = { y: c, ...dayMonth(a, b, order) };
  } else if (groups.length === 1 && groups[0].length === 8) {
    const g = groups[0];
    const ymd = { y: g.slice(0, 4), m: g.slice(4, 6), d: g.slice(6, 8) };
    if (/^(19|20)/.test(g) && Number(ymd.m) >= 1 && Number(ymd.m) <= 12) p = ymd;
    else p = { y: g.slice(4), ...dayMonth(g.slice(0, 2), g.slice(2, 4), order) };
  }
  return p && fits(p) ? p : null;
}

export function daysInMonth(y: number, m: number): number {
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

/**
 * 哪几个框有问题（用来逐框标红）：空的框、年不是 4 位或早于 1900、月不在 1–12、日超出当月天数（例如 2 月 30 日标"日"）。
 * 每一格都没问题、但整体不合规（例如未满 18 岁）时返回空集合，由调用方决定标哪里。
 */
export function problemParts(v: string, minYear = 1900): Set<DatePart> {
  const p = splitDate(v);
  const out = new Set<DatePart>();
  if (!p.y && !p.m && !p.d) return new Set(['y', 'm', 'd']);
  const y = Number(p.y);
  const m = Number(p.m);
  const d = Number(p.d);
  if (!/^\d{4}$/.test(p.y) || y < minYear) out.add('y');
  if (!p.m || m < 1 || m > 12) out.add('m');
  if (!p.d || d < 1 || d > 31) out.add('d');
  else if (!out.has('y') && !out.has('m') && d > daysInMonth(y, m)) out.add('d');
  return out;
}
