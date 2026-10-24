/** 模拟 ID 生成（站内信、流水等内部编号） */
let seq = 1000;
export const nextId = (prefix: string): string => `${prefix}${Date.now().toString(36)}${(seq++).toString(36)}`;

/**
 * 模拟对外编号（和后端 common/id/Snowflake 同样的格式：41 位毫秒时间（从 2026-01-01 起）+ 10 位节点号 1 + 12 位序号）。
 * 订单、持仓、提现、充值的编号用它。雪花 ID 超过 JavaScript 能精确表示的整数范围，一律当字符串，只用 BigInt 生成，不转成 number。
 */
const SNOWFLAKE_EPOCH = Date.UTC(2026, 0, 1);
let snowSeq = 0;
export function snowflakeAt(ms: number, sequence: number): string {
  return ((BigInt(Math.max(0, Math.floor(ms - SNOWFLAKE_EPOCH))) << 22n) | (1n << 12n) | BigInt(sequence & 0xfff)).toString();
}
export const snowflakeId = (): string => snowflakeAt(Date.now(), snowSeq++);

/** 确定性伪随机数，保证每次打开原型的模拟数据一致 */
export function seeded(seed: number): () => number {
  let s = seed | 0;
  return () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const minutesAgo = (m: number, now = Date.now()): string => new Date(now - m * 60_000).toISOString();
export const hoursFromNow = (h: number, now = Date.now()): string => new Date(now + h * 3_600_000).toISOString();
