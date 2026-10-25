/**
 * 定点小数工具（规则 NF-07：金额一律用定点小数，不用浮点）。
 *
 * 内部用 bigint 表示，固定放大 10^18。所有入参只接受十进制字符串或整数，
 * 避免 0.1 + 0.2 这类浮点误差进入金额计算。前端只用它做"展示用的预估"，
 * 真实结果以后端返回为准。
 */
export type Dec = bigint;

const SCALE_DIGITS = 18;
const SCALE = 10n ** BigInt(SCALE_DIGITS);

const DEC_RE = /^([+-])?(\d+)(?:\.(\d+))?$/;

/** 判断字符串是不是合法的十进制数（不接受科学计数法、空串、多个小数点） */
export function isDecimalString(s: string): boolean {
  return DEC_RE.test(s.trim());
}

/** 把十进制字符串或整数转换成定点值；非法输入抛错 */
export function dec(v: string | number | bigint): Dec {
  if (typeof v === 'bigint') return v * SCALE;
  if (typeof v === 'number') {
    if (!Number.isInteger(v)) throw new Error(`dec() 只接受整数 number，收到 ${v}`);
    return BigInt(v) * SCALE;
  }
  const m = DEC_RE.exec(v.trim());
  if (!m) throw new Error(`非法的十进制字符串：${v}`);
  const sign = m[1] === '-' ? -1n : 1n;
  const intPart = BigInt(m[2]);
  const frac = (m[3] ?? '').slice(0, SCALE_DIGITS).padEnd(SCALE_DIGITS, '0');
  return sign * (intPart * SCALE + BigInt(frac));
}

/** 宽松转换：非法时返回 null，用于表单输入 */
export function tryDec(v: string): Dec | null {
  return isDecimalString(v) ? dec(v) : null;
}

export const ZERO: Dec = 0n;
export const add = (a: Dec, b: Dec): Dec => a + b;
export const sub = (a: Dec, b: Dec): Dec => a - b;
export const neg = (a: Dec): Dec => -a;
export const abs = (a: Dec): Dec => (a < 0n ? -a : a);
export const cmp = (a: Dec, b: Dec): -1 | 0 | 1 => (a < b ? -1 : a > b ? 1 : 0);
export const sum = (xs: Dec[]): Dec => xs.reduce((s, x) => s + x, 0n);

/** 乘法，结果四舍五入到内部精度 */
export function mul(a: Dec, b: Dec): Dec {
  return divRound(a * b, SCALE);
}

/** 除法，结果四舍五入到内部精度；除数为 0 时抛错 */
export function div(a: Dec, b: Dec): Dec {
  if (b === 0n) throw new Error('除数不能为 0');
  return divRound(a * SCALE, b);
}

/** bigint 整除并"四舍五入，远离零" */
function divRound(n: bigint, d: bigint): bigint {
  const negative = n < 0n !== d < 0n;
  const an = n < 0n ? -n : n;
  const ad = d < 0n ? -d : d;
  const q = an / ad;
  const r = an % ad;
  const rounded = r * 2n >= ad ? q + 1n : q;
  return negative ? -rounded : rounded;
}

/** 四舍五入到 dp 位小数（远离零） */
export function round(a: Dec, dp: number): Dec {
  const unit = 10n ** BigInt(SCALE_DIGITS - dp);
  return divRound(a, unit) * unit;
}

/** 转成固定小数位的字符串（不带千分位），例如 toFixed(dec('1.005'), 2) === '1.01' */
export function toFixed(a: Dec, dp: number): string {
  const r = round(a, dp);
  const negative = r < 0n;
  const v = negative ? -r : r;
  const intPart = v / SCALE;
  const fracAll = (v % SCALE).toString().padStart(SCALE_DIGITS, '0');
  const frac = dp > 0 ? '.' + fracAll.slice(0, dp) : '';
  const out = `${intPart.toString()}${frac}`;
  return negative && /[1-9]/.test(out) ? `-${out}` : out;
}

/** 转成去掉尾随 0 的字符串，用于回填输入框 */
export function toPlain(a: Dec, maxDp = 8): string {
  const s = toFixed(a, maxDp);
  return s.includes('.') ? s.replace(/\.?0+$/, '') : s;
}

/** 仅用于图表等"纯展示"场景：转换成 number。不能再参与金额计算。 */
export function toNumber(a: Dec): number {
  return Number(toFixed(a, 10));
}
