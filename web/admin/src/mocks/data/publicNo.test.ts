import { describe, expect, it } from 'vitest';
import { deposits, mockNo, withdrawals } from './db';

/** 原型数据的对外编号和后端雪花 ID 同一格式（v2 第 11.4 节）：字符串、超过 JavaScript 安全整数、不重复 */
describe('原型数据的对外编号', () => {
  it('出金和入金编号是雪花 ID 字符串，不能转成 number', () => {
    for (const id of [...withdrawals.map((w) => w.id), ...deposits.map((d) => d.id)]) {
      expect(id).toMatch(/^\d{17,19}$/);
      expect(BigInt(id) > BigInt(Number.MAX_SAFE_INTEGER)).toBe(true);
    }
    expect(new Set(withdrawals.map((w) => w.id)).size).toBe(withdrawals.length);
  });

  it('时间和节点号的位置和后端一致', () => {
    const id = BigInt(mockNo(0, 5));
    expect(id >> 22n).toBe(BigInt(Date.UTC(2026, 9, 14, 8, 0, 0) - Date.UTC(2026, 0, 1)));
    expect((id >> 12n) & 1023n).toBe(1n);
    expect(id & 4095n).toBe(5n);
  });
});
