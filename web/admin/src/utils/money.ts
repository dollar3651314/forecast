/**
 * 金额工具：前端只做展示格式化和少量整数运算。
 * 金额一律用字符串（如 "18430.50"）在各层之间传递，内部换算成 BigInt 定点数，不用浮点累加。
 * Money helpers: amounts travel as decimal strings; arithmetic uses scaled BigInt, never floats.
 */

const DECIMAL_RE = /^([+-])?(\d+)(?:\.(\d+))?$/;

/** 把十进制字符串转成按 scale 位小数放大的 BigInt（四舍五入，half-up）。非法输入返回 null。 */
export function toScaled(value: string | number | bigint, scale = 2): bigint | null {
  if (typeof value === 'bigint') return value * 10n ** BigInt(scale);
  const text = typeof value === 'number' ? (Number.isInteger(value) ? String(value) : '') : value.trim().replace(/,/g, '');
  const m = DECIMAL_RE.exec(text);
  if (!m) return null;
  const negative = m[1] === '-';
  const intPart = m[2];
  const frac = m[3] ?? '';
  const kept = (frac + '0'.repeat(scale)).slice(0, scale);
  let scaled = BigInt(intPart + kept);
  // 被截掉的第一位 ≥5 时进位
  if (frac.length > scale && Number(frac[scale]) >= 5) scaled += 1n;
  return negative ? -scaled : scaled;
}

/** 把放大后的 BigInt 还原成字符串（不带千分位），例如 1843050n, 2 → "18430.50" */
export function fromScaled(scaled: bigint, scale = 2): string {
  const negative = scaled < 0n;
  const abs = negative ? -scaled : scaled;
  const s = abs.toString().padStart(scale + 1, '0');
  const intPart = scale > 0 ? s.slice(0, s.length - scale) : s;
  const frac = scale > 0 ? s.slice(s.length - scale) : '';
  return (negative ? '-' : '') + intPart + (scale > 0 ? '.' + frac : '');
}

function groupThousands(intPart: string): string {
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

export interface FormatAmountOptions {
  /** 小数位数，默认 2 */
  decimals?: number;
  /** 正数前加 "+" 号（盈亏、资金流水用） */
  showPlus?: boolean;
}

/**
 * 金额展示：千分位 + 固定小数位。非法值显示 "—"，绝不显示 NaN。
 * formatAmount("18430.5") → "18,430.50"；formatAmount("-40000", {decimals:0, showPlus:true}) → "-40,000"
 */
export function formatAmount(value: string | number | bigint | null | undefined, options: FormatAmountOptions = {}): string {
  const decimals = options.decimals ?? 2;
  if (value === null || value === undefined || value === '') return '—';
  const scaled = toScaled(value as string | number | bigint, decimals);
  if (scaled === null) return '—';
  const raw = fromScaled(scaled, decimals);
  const negative = raw.startsWith('-');
  const body = negative ? raw.slice(1) : raw;
  const [i, f] = body.split('.');
  const text = groupThousands(i) + (f !== undefined ? '.' + f : '');
  if (negative && scaled !== 0n) return '-' + text;
  if (options.showPlus && scaled > 0n) return '+' + text;
  return text;
}

/** 把多个金额字符串相加（定点运算），返回字符串 */
export function sumAmounts(values: readonly string[], scale = 2): string {
  let total = 0n;
  for (const v of values) {
    const s = toScaled(v, scale);
    if (s !== null) total += s;
  }
  return fromScaled(total, scale);
}

/** 比较两个金额：a<b 返回 -1，相等 0，a>b 返回 1；非法输入按 0 处理 */
export function compareAmounts(a: string, b: string, scale = 2): number {
  const x = toScaled(a, scale) ?? 0n;
  const y = toScaled(b, scale) ?? 0n;
  return x === y ? 0 : x < y ? -1 : 1;
}

/** 金额相减 a-b，返回字符串 */
export function subtractAmounts(a: string, b: string, scale = 2): string {
  return fromScaled((toScaled(a, scale) ?? 0n) - (toScaled(b, scale) ?? 0n), scale);
}

/**
 * 费率展示成百分比，例如 "0.0005" → "0.05%"；"-0.00012" → "-0.012%"
 * decimals 为百分数保留的小数位。
 */
export function formatRatePercent(rate: string | null | undefined, decimals = 3): string {
  if (rate === null || rate === undefined || rate === '') return '—';
  // 百分数 = rate × 100：放大 decimals+2 位后按 decimals 位还原
  const scaled = toScaled(rate, decimals + 2);
  if (scaled === null) return '—';
  const text = fromScaled(scaled, decimals);
  // 去掉多余的尾随 0，但至少保留 2 位
  const trimmed = text.replace(/(\.\d{2}\d*?)0+$/, '$1');
  return trimmed + '%';
}

/** 整数比例：|part| / whole × 100，四舍五入到整数（用于敞口使用率），whole 为 0 时返回 0 */
export function usagePercent(part: string | null | undefined, whole: string | null | undefined): number {
  // 阈值为空（不监控）时按 0 处理
  if (part === null || part === undefined || whole === null || whole === undefined) return 0;
  const p = toScaled(part, 0);
  const w = toScaled(whole, 0);
  if (p === null || w === null || w === 0n) return 0;
  const abs = p < 0n ? -p : p;
  return Number((abs * 200n + w) / (2n * w));
}

/** 校验用户输入的金额：正数、最多 decimals 位小数 */
export function isValidAmountInput(text: string, decimals = 2): boolean {
  const re = new RegExp(`^\\d+(\\.\\d{1,${decimals}})?$`);
  if (!re.test(text.trim())) return false;
  const scaled = toScaled(text, decimals);
  return scaled !== null && scaled > 0n;
}
