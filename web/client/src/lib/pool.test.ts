import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { expectedReturn, multiplier, NONE, payout, percent, rateFromPercent } from './pool';

/**
 * 前后端共用的测试向量（forecast S4）：backend/src/test/resources/pool/pool-vectors.json，后端 PoolMathVectorsTest 读同一个文件。
 * 生成脚本 scripts/gen-pool-vectors.py 用精确分数独立算出；三边逐分一致。
 */
interface Vectors {
  cases: Array<{
    name: string;
    feeRate: string;
    pools: string[];
    total: string;
    pctList: string[];
    pctDetail: string[];
    mult: string[];
    bets: Array<{ option: number; amount: string; expectedReturn: string }>;
    positions: Array<{ option: number; amount: string; gross: string; fee: string; payout: string }>;
  }>;
}

// 测试在 web/client 下运行（npm test），向量在仓库的 backend 目录（CI 的 web (client) 检出整个仓库）
const vectors = JSON.parse(readFileSync(join(process.cwd(), '../../backend/src/test/resources/pool/pool-vectors.json'), 'utf8')) as Vectors;

describe('lib/pool 和后端共用的测试向量', () => {
  it('每一组都逐分一致', () => {
    expect(vectors.cases.length).toBeGreaterThan(500);
    const diffs: string[] = [];
    let checks = 0;
    for (const c of vectors.cases) {
      const eq = (what: string, expected: string, actual: string) => {
        checks += 1;
        if (expected !== actual) diffs.push(`${c.name} ${what}：期望 ${expected}，实际 ${actual}`);
      };
      c.pools.forEach((p, i) => {
        eq(`pctList[${i}]`, c.pctList[i], percent(p, c.total, 0));
        eq(`pctDetail[${i}]`, c.pctDetail[i], percent(p, c.total, 1));
        eq(`mult[${i}]`, c.mult[i], multiplier(p, c.total, c.feeRate));
      });
      for (const b of c.bets) eq(`bet ${b.option}/${b.amount}`, b.expectedReturn, expectedReturn(c.pools[b.option], c.total, b.amount, c.feeRate));
      for (const p of c.positions) {
        const x = payout(p.amount, c.pools[p.option], c.total, c.feeRate);
        eq(`gross ${p.option}/${p.amount}`, p.gross, x.gross);
        eq(`fee ${p.option}/${p.amount}`, p.fee, x.fee);
        eq(`payout ${p.option}/${p.amount}`, p.payout, x.payout);
      }
    }
    expect(diffs).toEqual([]);
    expect(checks).toBeGreaterThan(5000);
  });
});

describe('需求第 3.4、3.5 节的例子（写死，不依赖向量）', () => {
  it('AC-19：60% / 15% / 25%，1.63x / 6.38x / 3.85x，押主胜 100 预计回报 162.29', () => {
    expect(['6000', '1500', '2500'].map((p) => percent(p, '10000', 0))).toEqual(['60%', '15%', '25%']);
    expect(['6000', '1500', '2500'].map((p) => multiplier(p, '10000', '0.05'))).toEqual(['1.63', '6.38', '3.85']);
    expect(expectedReturn('6000', '10000', '100', '0.05')).toBe('162.29');
  });

  it('AC-19：奖池为 0 显示 —，T = 0 全部 —，1 / 1,001 显示 <1%', () => {
    expect(multiplier('0', '10000', '0.05')).toBe(NONE);
    expect(percent('0', '0', 0)).toBe(NONE);
    expect(percent('1', '1001', 0)).toBe('<1%');
    expect(percent('1000', '1001', 1)).toBe('>99%');
  });

  it('第 3.5 节：Alice 163.33、Bob 4,083.33、Carol 5,553.33；费率 8% 时 Alice 161.33', () => {
    expect(payout('100', '6000', '10000', '0.05').payout).toBe('163.33');
    expect(payout('2500', '6000', '10000', '0.05').payout).toBe('4083.33');
    expect(payout('3400', '6000', '10000', '0.05').payout).toBe('5553.33');
    expect(payout('100', '6000', '10000', '0.08')).toEqual({ gross: '66.66', fee: '5.33', payout: '161.33' });
  });

  it('接口里的费率是百分比字符串', () => {
    expect(rateFromPercent('5')).toBe('0.0500');
    expect(rateFromPercent('5.25')).toBe('0.0525');
    expect(rateFromPercent('0')).toBe('0.0000');
    expect(multiplier('6000', '10000', rateFromPercent('8'))).toBe('1.61');
  });

  it('接口给的 8 位小数的金额也能直接用', () => {
    expect(percent('6000.00000000', '10000.00000000', 1)).toBe('60.0%');
    expect(expectedReturn('6000.00', '10000.00', '100.00', '0.05')).toBe('162.29');
  });
});
