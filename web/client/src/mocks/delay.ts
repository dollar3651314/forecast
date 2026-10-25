/**
 * 模拟网络延迟和场景：每个模拟接口都通过 respond() 返回，
 * 这样"接口很慢 / 接口出错 / 空数据"对所有页面一致生效。
 */
import { ApiError } from '../api/errors';
import { scenario } from './scenario';

let range: [number, number] = [200, 600];
let slowRange: [number, number] = [2500, 3500];

/** 测试里把延迟设为 0 */
export function setMockDelay(min: number, max: number, slowMin = min, slowMax = max): void {
  range = [min, max];
  slowRange = [slowMin, slowMax];
}

function wait(): Promise<void> {
  const [a, b] = scenario() === 'slow' ? slowRange : range;
  const ms = a + Math.random() * (b - a);
  return ms <= 0 ? Promise.resolve() : new Promise((r) => setTimeout(r, ms));
}

/**
 * 读接口：empty 场景返回 empty()，error 场景抛错。
 * 不传 empty 时，空数据场景照常返回正常数据（例如品种配置这类不会为空的数据）。
 */
export async function respond<T>(normal: () => T, empty?: () => T): Promise<T> {
  await wait();
  const s = scenario();
  if (s === 'error') throw new ApiError('server');
  if (s === 'empty' && empty) return empty();
  return normal();
}

/** 写接口：error 场景抛错，其余照常执行 */
export async function mutate<T>(fn: () => T): Promise<T> {
  await wait();
  if (scenario() === 'error') throw new ApiError('server');
  return fn();
}
