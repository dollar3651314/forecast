import { describe, expect, it } from 'vitest';
import { snowflakeAt, snowflakeId } from './ids';

/** 模拟层的对外编号和后端雪花 ID（common/id/Snowflake）同一格式：超过 JavaScript 安全整数，只能当字符串用（v2 第 11.4 节） */
describe('模拟对外编号', () => {
  it('是纯数字字符串，超过 Number.MAX_SAFE_INTEGER，转成 number 会丢精度', () => {
    const id = snowflakeId();
    expect(id).toMatch(/^\d{17,19}$/);
    expect(BigInt(id) > BigInt(Number.MAX_SAFE_INTEGER)).toBe(true);
    // 转成 number 丢精度：用固定的序号 1（最低位是 1）。超过 2^53 的 double 只能表示偶数，所以一定丢精度；
    // 不用随机生成的 id：低位恰好是 0 的 id（例如序号 0）能被 double 精确表示，测试会偶发失败（#48 合并后 staging CI 实际发生过）
    const odd = snowflakeAt(Date.UTC(2026, 9, 14, 10, 0, 0), 1);
    expect(String(Number(odd))).not.toBe(odd);
  });

  it('时间、节点号 1、序号的位置和后端一致，同一时间不同序号不重复', () => {
    const ms = Date.UTC(2026, 9, 14, 10, 0, 0);
    const a = BigInt(snowflakeAt(ms, 0));
    expect(a >> 22n).toBe(BigInt(ms - Date.UTC(2026, 0, 1)));
    expect((a >> 12n) & 1023n).toBe(1n);
    expect(snowflakeAt(ms, 1)).not.toBe(snowflakeAt(ms, 0));
    expect(snowflakeId()).not.toBe(snowflakeId());
  });
});
