/**
 * 彩池的赔率和预计回报（forecast S4，需求说明书第 3.4、3.5 节；技术方案/v1-架构方案.md 第 4.1 节）。
 *
 * 和后端 `pool/api/Odds.java`、`PoolMath.java` 是同一套规则，共用测试向量
 * `backend/src/test/resources/pool/pool-vectors.json`（`pool.test.ts`），结果必须逐分一致：
 * - 下注抽屉里客户输入金额时，本地算"押 100 → 赢了约回 162.29"，不用每输一个字就请求接口；
 * - 推送只带奖池时，本地重算概率和赔率也和接口给的一样。
 *
 * 金额、费率都是字符串（接口给的就是字符串），内部用 bigint 精确计算（不用 number，也不用 decimal.ts 的 18 位四舍五入），
 * 只在需求写明的地方舍入：盈利 G 向下取整到 0.01、抽成 F 四舍五入到 0.01、预计赔率 2 位小数向下取整、概率四舍五入。
 */

/** 没有数据时显示的符号（T = 0 的概率、Pᵢ = 0 的赔率） */
export const NONE = '—';

/** 金额、奖池最多 8 位小数（和数据库 DECIMAL(24,8) 一样）；费率最多 4 位小数（0.0525 = 5.25%） */
const AMOUNT_DP = 8;
const RATE_DP = 4;

const NUM_RE = /^(\d+)(?:\.(\d+))?$/;

/** 非负十进制字符串 → 放大 10^dp 的整数；小数位多于 dp 时抛错（调用方给的都是合法金额） */
function scaled(v: string, dp: number): bigint {
  const m = NUM_RE.exec(v.trim());
  if (!m) throw new Error(`不是非负的十进制数：${v}`);
  const frac = m[2] ?? '';
  if (frac.length > dp && /[1-9]/.test(frac.slice(dp))) throw new Error(`小数位太多：${v}`);
  return BigInt(m[1]) * 10n ** BigInt(dp) + BigInt(frac.slice(0, dp).padEnd(dp, '0'));
}

/** 非负整数 n 表示 n / 10^dp → 固定 dp 位小数的字符串 */
function fixed(n: bigint, dp: number): string {
  if (dp === 0) return n.toString();
  const s = n.toString().padStart(dp + 1, '0');
  return `${s.slice(0, -dp)}.${s.slice(-dp)}`;
}

/** 非负数的四舍五入整除 */
function divHalfUp(num: bigint, den: bigint): bigint {
  return (num * 2n + den) / (den * 2n);
}

/** 接口里的费率是百分比字符串（"5.25" = 5.25%）→ 计算用的小数字符串（"0.0525"） */
export function rateFromPercent(percent: string): string {
  const p = scaled(percent, 2); // 百分比最多 2 位小数
  return fixed(p, RATE_DP);
}

/**
 * 概率：Pᵢ ÷ T × 100。decimals：列表 0、详情 1。
 * Pᵢ > 0 且 < 1% 显示 "<1%"，> 99% 且 < 100% 显示 ">99%"（按精确值判断），T = 0 显示 "—"。
 */
export function percent(optionPool: string, total: string, decimals: 0 | 1): string {
  const p = scaled(optionPool, AMOUNT_DP);
  const t = scaled(total, AMOUNT_DP);
  if (t === 0n) return NONE;
  if (p > 0n && p * 100n < t) return '<1%';
  if (p * 100n > t * 99n && p < t) return '>99%';
  const unit = 10n ** BigInt(decimals);
  return `${fixed(divHalfUp(p * 100n * unit, t), decimals)}%`;
}

/** 预计赔率（扣费后，不带 x）：(Pᵢ + (T − Pᵢ)(1 − r)) ÷ Pᵢ，2 位小数向下取整；Pᵢ = 0 显示 "—" */
export function multiplier(optionPool: string, total: string, rate: string): string {
  const p = scaled(optionPool, AMOUNT_DP);
  if (p === 0n) return NONE;
  const t = scaled(total, AMOUNT_DP);
  const r = scaled(rate, RATE_DP);
  const one = 10n ** BigInt(RATE_DP);
  // 分子、分母都放大 10^RATE_DP，再 × 100 取 2 位小数，整除即向下取整
  const back = p * one + (t - p) * (one - r);
  return fixed((back * 100n) / (p * one), 2);
}

/** 盈利 G = b × L ÷ P_W，向下取整到 0.01；返回"分" */
function grossCents(stake: bigint, loser: bigint, winner: bigint): bigint {
  // 三个量都放大了 10^AMOUNT_DP：b × L ÷ P_W 也是放大 10^AMOUNT_DP 的值，换成分要再除以 10^(AMOUNT_DP − 2)
  return (stake * loser * 100n) / (winner * 10n ** BigInt(AMOUNT_DP));
}

/** 抽成 F = G × r，四舍五入到 0.01；返回"分" */
function feeCents(gross: bigint, rate: bigint): bigint {
  return divHalfUp(gross * rate, 10n ** BigInt(RATE_DP));
}

/** 金额（放大 10^AMOUNT_DP）→ 分（金额都是 2 位小数，正好整除） */
function cents(v: bigint): bigint {
  return v / 10n ** BigInt(AMOUNT_DP - 2);
}

/**
 * 本注预计回报（下注抽屉："押 100 → 赢了约回 162.29（已扣抽成）"）：把本注加进奖池后按第 3.5 节算，
 * G = b × (T − Pᵢ) ÷ (Pᵢ + b) 向下取整，F = G × r 四舍五入，回报 = b + G − F。T、Pᵢ 是下注之前的奖池。
 */
export function expectedReturn(optionPool: string, total: string, stake: string, rate: string): string {
  const p = scaled(optionPool, AMOUNT_DP);
  const t = scaled(total, AMOUNT_DP);
  const b = scaled(stake, AMOUNT_DP);
  const g = grossCents(b, t - p, p + b);
  const f = feeCents(g, scaled(rate, RATE_DP));
  return fixed(cents(b) + g - f, 2);
}

/** 一个已经在奖池里的持仓，如果它押的选项赢了、按现在（截止后是定格）的奖池结算：盈利、抽成、派彩 */
export interface Payout {
  gross: string;
  fee: string;
  payout: string;
}

export function payout(stake: string, optionPool: string, total: string, rate: string): Payout {
  const p = scaled(optionPool, AMOUNT_DP);
  const t = scaled(total, AMOUNT_DP);
  const b = scaled(stake, AMOUNT_DP);
  if (p === 0n) return { gross: '0.00', fee: '0.00', payout: fixed(cents(b), 2) };
  const g = grossCents(b, t - p, p);
  const f = feeCents(g, scaled(rate, RATE_DP));
  return { gross: fixed(g, 2), fee: fixed(f, 2), payout: fixed(cents(b) + g - f, 2) };
}
