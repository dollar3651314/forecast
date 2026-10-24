/**
 * 模拟层运行时：场景切换（正常 / 空数据 / 接口很慢 / 接口出错）和随机延迟。
 * 只在原型阶段使用；正式开发时 src/api 改为调用真实接口，本目录整体删除。
 * Mock runtime: scenario switching and simulated latency (prototype only).
 */
import { ApiError } from '../api/types';

export type Scenario = 'normal' | 'empty' | 'slow' | 'error';

const SCENARIO_KEY = 'fx-admin-proto-scenario';

function readStoredScenario(): Scenario {
  try {
    const v = globalThis.localStorage?.getItem(SCENARIO_KEY);
    if (v === 'normal' || v === 'empty' || v === 'slow' || v === 'error') return v;
  } catch {
    /* 隐私模式等情况下读不到，按正常处理 */
  }
  return 'normal';
}

let scenario: Scenario = readStoredScenario();
let version = 0;
const listeners = new Set<() => void>();

/** 测试时可把延迟关掉 */
let latency = { min: 200, max: 600, slow: 3500 };

export function setLatency(next: { min: number; max: number; slow: number }): void {
  latency = next;
}

export function getScenario(): Scenario {
  return scenario;
}

export function getScenarioVersion(): number {
  return version;
}

export function setScenario(next: Scenario): void {
  scenario = next;
  version += 1;
  try {
    globalThis.localStorage?.setItem(SCENARIO_KEY, next);
  } catch {
    /* 忽略 */
  }
  listeners.forEach((l) => l());
}

export function subscribeScenario(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function randomDelay(): number {
  if (scenario === 'slow') return latency.slow;
  return latency.min + Math.floor(Math.random() * (latency.max - latency.min + 1));
}

interface RespondOptions<T> {
  /** 空数据场景下返回的值；不传时照常返回数据 */
  empty?: () => T;
  /** 不受场景影响（登录、身份等原型基础功能） */
  bypass?: boolean;
}

/** 查询类接口：按当前场景返回数据、空数据或错误 */
export async function respond<T>(produce: () => T, options: RespondOptions<T> = {}): Promise<T> {
  if (options.bypass) {
    await wait(Math.min(latency.min, 150));
    return structuredClone(produce());
  }
  await wait(randomDelay());
  if (scenario === 'error') throw new ApiError('errors.serverUnavailable');
  if (scenario === 'empty' && options.empty) return structuredClone(options.empty());
  return structuredClone(produce());
}

/** 操作类接口：出错场景下失败；业务校验失败时 produce 内部抛 ApiError */
export async function mutate<T>(produce: () => T): Promise<T> {
  await wait(randomDelay());
  if (scenario === 'error') throw new ApiError('errors.serverUnavailable');
  return structuredClone(produce());
}

/** 通用分页 */
export function paginate<T>(items: T[], page: number, pageSize: number) {
  const safeSize = Math.max(1, pageSize);
  const maxPage = Math.max(1, Math.ceil(items.length / safeSize));
  const safePage = Math.min(Math.max(1, page), maxPage);
  return {
    items: items.slice((safePage - 1) * safeSize, safePage * safeSize),
    total: items.length,
    page: safePage,
    pageSize: safeSize,
  };
}

export function emptyPage<T>(page: number, pageSize: number) {
  return { items: [] as T[], total: 0, page, pageSize };
}

/** 关键字匹配（忽略大小写） */
export function matches(keyword: string | undefined, ...fields: (string | number | null | undefined)[]): boolean {
  if (!keyword) return true;
  const k = keyword.trim().toLowerCase();
  if (!k) return true;
  return fields.some((f) => f !== null && f !== undefined && String(f).toLowerCase().includes(k));
}
