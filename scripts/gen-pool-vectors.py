#!/usr/bin/env python3
"""
生成前后端共用的赔率测试向量 backend/src/test/resources/pool/pool-vectors.json（forecast S4，技术方案/v1-架构方案.md 第 4.1 节）。

为什么用 Python：后端 PoolMath / Odds（Java BigDecimal）和客户端 lib/pool.ts（bigint）是两份实现，向量用第三份独立实现
（这里用 fractions.Fraction 精确分数）算出，三边结果逐分一致才算对。手写的几组（需求第 3.5 节两个例子、费率 8%、AC-19 的边界）
的期望值另外在 PoolMathTest 里按需求原文写死核对，不依赖本脚本。

规则（需求说明书 v1.2 第 3.4、3.5 节）：
- 概率 % = Pᵢ ÷ T × 100：列表 0 位、详情 1 位小数，四舍五入；Pᵢ > 0 且 < 1% 显示 "<1%"；> 99% 且 < 100% 显示 ">99%"；T = 0 显示 "—"
- 预计赔率 x = (Pᵢ + (T − Pᵢ)(1 − r)) ÷ Pᵢ，2 位小数向下取整；Pᵢ = 0 显示 "—"
- 本注预计回报：G = b × (T − Pᵢ) ÷ (Pᵢ + b) 向下取整到 0.01；F = G × r 四舍五入到 0.01；回报 = b + G − F
- 持仓（已经在奖池里）按现在的奖池结算：G = b × (T − Pᵢ) ÷ Pᵢ 向下取整；F 同上；派彩 = b + G − F

用法：python3 scripts/gen-pool-vectors.py > backend/src/test/resources/pool/pool-vectors.json
随机部分用固定种子，重复生成结果不变。
"""
import json
import math
import random
from fractions import Fraction as F

DASH = "—"


def d(s):
    return F(s)


def fmt(x, dp):
    """非负分数 → 固定 dp 位小数的字符串（调用方已经舍入好，这里只是格式化）"""
    scaled = x * (10 ** dp)
    assert scaled.denominator == 1, x
    n = scaled.numerator
    if dp == 0:
        return str(n)
    s = str(n).rjust(dp + 1, "0")
    return s[:-dp] + "." + s[-dp:]


def floor_to(x, dp):
    u = F(1, 10 ** dp)
    return F(math.floor(x / u)) * u


def half_up_to(x, dp):
    # 只用于非负数
    u = F(1, 10 ** dp)
    return F(math.floor(x / u + F(1, 2))) * u


def percent(pi, t, dp):
    if t == 0:
        return DASH
    p = pi / t * 100
    if pi > 0 and p < 1:
        return "<1%"
    if p > 99 and p < 100:
        return ">99%"
    return fmt(half_up_to(p, dp), dp) + "%"


def mult(pi, t, r):
    if pi == 0:
        return DASH
    return fmt(floor_to((pi + (t - pi) * (1 - r)) / pi, 2), 2)


def expected_return(pi, t, b, r):
    g = floor_to(b * (t - pi) / (pi + b), 2)
    f = half_up_to(g * r, 2)
    return fmt(b + g - f, 2)


def position(pi, t, b, r):
    g = floor_to(b * (t - pi) / pi, 2)
    f = half_up_to(g * r, 2)
    return {"gross": fmt(g, 2), "fee": fmt(f, 2), "payout": fmt(b + g - f, 2)}


def case(name, rate, pools, bets=(), positions=()):
    r = d(rate)
    ps = [d(p) for p in pools]
    t = sum(ps)
    out = {
        "name": name,
        "feeRate": rate,
        "pools": [fmt(p, 2) for p in ps],
        "total": fmt(t, 2),
        "pctList": [percent(p, t, 0) for p in ps],
        "pctDetail": [percent(p, t, 1) for p in ps],
        "mult": [mult(p, t, r) for p in ps],
        "bets": [],
        "positions": [],
    }
    for opt, amount in bets:
        out["bets"].append({"option": opt, "amount": amount, "expectedReturn": expected_return(ps[opt], t, d(amount), r)})
    for opt, amount in positions:
        row = {"option": opt, "amount": amount}
        row.update(position(ps[opt], t, d(amount), r))
        out["positions"].append(row)
    return out


def money(rng, lo_cents, hi_cents):
    return fmt(F(rng.randint(lo_cents, hi_cents), 100), 2)


def main():
    cases = []
    # 需求第 3.5 节例子 1（曼城 vs 利物浦，费率 5%）：概率、最终赔率、三个赢方持仓的派彩（Alice 两笔 60 + 40 合成一个持仓）
    ex_positions = [(0, "100.00"), (0, "2500.00"), (0, "3400.00"), (1, "1500.00"), (2, "2000.00"), (2, "500.00")]
    # 例子 2：Grace 押主胜 100 → 预计回报 162.29
    cases.append(case("需求3.5例子1和例子2_费率5%", "0.05", ["6000", "1500", "2500"], bets=[(0, "100"), (1, "100"), (2, "100")], positions=ex_positions))
    # 费率改成 8%（AC-37）：Alice 161.33
    cases.append(case("需求3.5例子_费率8%", "0.08", ["6000", "1500", "2500"], bets=[(0, "100")], positions=ex_positions))
    # AC-19：某选项奖池 1、总奖池 1,001 → "<1%"；另一边 ">99%"
    cases.append(case("AC19_小于1%和大于99%", "0.05", ["1", "1000"], bets=[(0, "1"), (1, "10")], positions=[(0, "1"), (1, "1000")]))
    # 某选项奖池为 0：概率 0%、赔率 "—"；先押的人赢了拿走输方全部（扣费后）
    cases.append(case("某选项奖池为0", "0.05", ["0", "500", "1500"], bets=[(0, "100"), (1, "100")], positions=[(1, "500")]))
    # T = 0：全部 "—"
    cases.append(case("总奖池为0", "0.05", ["0", "0"], bets=[(0, "100")]))
    # 只有一个选项有人押：赔率 1.00、概率 100%
    cases.append(case("只有一个选项有人押", "0.2", ["300", "0"], bets=[(0, "50"), (1, "50")], positions=[(0, "300")]))
    # 费率 0%、20%、5.25%（最多 2 位百分比小数）
    cases.append(case("费率0%", "0", ["1234.56", "789.01", "0.01"], bets=[(2, "0.01"), (0, "5000")], positions=[(0, "1234.56"), (2, "0.01")]))
    cases.append(case("费率20%", "0.2", ["3333.33", "6666.67"], bets=[(0, "0.01"), (1, "4999.99")], positions=[(0, "1111.11"), (1, "6666.67")]))
    cases.append(case("费率5.25%", "0.0525", ["100.01", "200.02", "300.03"], bets=[(1, "33.33")], positions=[(1, "100.01")]))
    # 四舍五入的边界：50.05% → 50.1%（详情）、50%（列表）；99.5% 这类 > 99 的显示 ">99%"
    cases.append(case("四舍五入边界", "0.05", ["1001", "999"], bets=[(0, "1")], positions=[(0, "1001")]))
    cases.append(case("刚好1%和99%", "0.05", ["1", "99"], bets=[(0, "1")], positions=[(0, "1"), (1, "99")]))

    rng = random.Random(20261025)
    for i in range(500):
        n = rng.randint(2, 10)
        pools = []
        for _ in range(n):
            pools.append("0" if rng.random() < 0.15 else money(rng, 1, 5_000_000))
        rate = fmt(F(rng.randint(0, 2000), 10000), 4)
        bets = [(rng.randrange(n), money(rng, 1, 500_000)) for _ in range(rng.randint(1, 3))]
        positions = []
        for _ in range(rng.randint(1, 4)):
            opt = rng.randrange(n)
            pi = F(pools[opt])
            if pi == 0:
                continue
            positions.append((opt, fmt(F(rng.randint(1, int(pi * 100)), 100), 2)))
        cases.append(case("随机%03d" % i, rate, pools, bets=bets, positions=positions))

    doc = {
        "description": "前后端共用的赔率测试向量（forecast S4）。生成：python3 scripts/gen-pool-vectors.py；后端 pool/PoolMathVectorsTest、客户端 src/lib/pool.test.ts 都读这一份，结果必须逐分一致。",
        "rules": "需求说明书 v1.2 第 3.4、3.5 节；feeRate 是小数（0.05 = 5%），金额是 USDT",
        "cases": cases,
    }
    print(json.dumps(doc, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
