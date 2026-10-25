/**
 * 下注表单的全部逻辑（架构方案第 9.1 节）：手机的底部抽屉（BetSheet）和电脑的右栏（BetPanel）只负责布局，计算、校验、提交都在这里，只写一份（C64）。
 * - 金额：最多 2 位小数；快捷金额 10 / 50 / 100 / 500 / 最大；最大 = min(单笔最大, 本市场还可押, 可用余额)；
 * - 本注预计回报按需求第 3.4 节（把本注加进奖池后按第 3.5 节算，lib/pool.ts，和后端同一套测试向量）；
 * - 余额不足时按钮变成"去充值"；
 * - 请求号 clientReqId 一次提交生成一个，失败重试沿用同一个（重复提交后端返回第一次的结果，X-3），成功后换新的。
 */
import { useCallback, useMemo, useRef, useState } from 'react';
import { placeBet } from '../api/markets';
import type { BetResult, MarketDetail } from '../api/types';
import { useAccount } from '../app/contexts';
import { dec, isDecimalString, toFixed } from '../lib/decimal';
import { expectedReturn, rateFromPercent } from '../lib/pool';

export const QUICK_AMOUNTS = ['10', '50', '100', '500'] as const;

export type BetError = { key: 'below' | 'above' | 'cap' | 'funds' | 'digits'; v?: string } | null;

const newReqId = (): string => `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;

export function useBetForm(market: MarketDetail | undefined, optionNo: string | undefined, onPlaced?: (r: BetResult) => void) {
  const { account, reload } = useAccount();
  const [amount, setAmountRaw] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const reqId = useRef(newReqId());

  const limits = market?.pool.limits;
  const option = market?.pool.options.find((o) => o.optionNo === optionNo);
  const available = account?.available ?? '0';

  /** 只允许数字和一个小数点、最多 2 位小数（多输的直接不收） */
  const setAmount = useCallback((v: string) => {
    const s = v.replace(/[^\d.]/g, '');
    if (/^\d*(\.\d{0,2})?$/.test(s)) setAmountRaw(s.replace(/^0+(?=\d)/, ''));
    setError(null);
  }, []);

  const maxAmount = useMemo(() => {
    if (!limits) return '0.00';
    const xs = [dec(limits.max), dec(limits.remaining), dec(available)];
    const m = xs.reduce((a, b) => (b < a ? b : a));
    return toFixed(m < 0n ? 0n : m, 2);
  }, [limits, available]);

  const valid = amount !== '' && isDecimalString(amount) && dec(amount) > 0n;

  const fieldError: BetError = useMemo(() => {
    if (!valid || !limits) return null;
    const b = dec(amount);
    if (b < dec(limits.min)) return { key: 'below', v: limits.min };
    if (b > dec(limits.max)) return { key: 'above', v: limits.max };
    if (b > dec(limits.remaining)) return { key: 'cap', v: limits.remaining };
    if (b > dec(available)) return { key: 'funds' };
    return null;
  }, [valid, amount, limits, available]);

  /**
   * 预计回报（lib/pool.expectedReturn，需求 3.4）和其中的盈利、抽成（显示用）：
   * G = b × (T − P) ÷ (P + b) 向下取整到分，回报 = b + G − F，所以 F = b + G − 回报（和 expectedReturn 的舍入完全一致）。
   */
  const estimate = useMemo(() => {
    if (!valid || !option || !market) return null;
    const stake = toFixed(dec(amount), 2);
    const ret = expectedReturn(option.amount, market.pool.total, stake, rateFromPercent(market.pool.feeRate));
    const cents = (v: string) => dec(v) / 10n ** 16n; // 金额都是 2 位小数，放大 10^18 的定点值换成"分"
    const b = cents(stake);
    const p = cents(option.amount);
    const t = cents(market.pool.total);
    const g = (b * (t - p)) / (p + b);
    const f = b + g - cents(ret);
    const fromCents = (c: bigint) => toFixed(c * 10n ** 16n, 2);
    return { stake, ret, profit: fromCents(g - f), fee: fromCents(f) };
  }, [valid, option, market, amount]);

  const insufficient = fieldError?.key === 'funds';
  const canSubmit = !!market && market.status === 'listed' && !!option && valid && !fieldError && !busy;

  const submit = useCallback(async () => {
    if (!market || !option || !canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const r = await placeBet(market.publicNo, { optionNo: option.optionNo, amount: toFixed(dec(amount), 2), clientReqId: reqId.current });
      reqId.current = newReqId();
      setAmountRaw('');
      reload();
      onPlaced?.(r);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }, [market, option, canSubmit, amount, reload, onPlaced]);

  return { amount, setAmount, maxAmount, option, limits, available, fieldError, estimate, insufficient, canSubmit, busy, error, submit };
}
