/**
 * 余额调整（forecast S17）的前端规则：请求号、金额校验。和后端一致：
 * - 请求号 `[A-Za-z0-9._-]{8,64}`（后端 TestCreditService.REQUEST_ID），每次打开确认框生成一个，重复点击只入一次；
 * - 金额大于 0、最多 8 位小数、不超过单笔上限（后端再校验一次，超过时 ADJUST_OVER_LIMIT）。
 */
import { compareAmounts } from './money';

/** 新的请求号：adj- + 随机串（浏览器有 crypto.randomUUID 时用它） */
export function newRequestId(): string {
  const c = typeof globalThis !== 'undefined' ? (globalThis.crypto as Crypto | undefined) : undefined;
  if (c && typeof c.randomUUID === 'function') return `adj-${c.randomUUID()}`;
  return `adj-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

const AMOUNT_RE = /^\d+(\.\d{1,8})?$/;

/** 金额有什么问题（翻译键的最后一段）：required / invalid / overLimit；没问题返回 null */
export function amountProblem(value: string | undefined | null, max?: string | null): 'required' | 'invalid' | 'overLimit' | null {
  const v = (value ?? '').trim();
  if (!v) return 'required';
  if (!AMOUNT_RE.test(v) || Number(v) <= 0) return 'invalid';
  if (max && compareAmounts(v, max, 8) > 0) return 'overLimit';
  return null;
}
