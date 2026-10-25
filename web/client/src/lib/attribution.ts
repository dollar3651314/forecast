/**
 * 来源统计（S13，MF-1；需求第 5.2 节"来源统计"、AC-83；架构方案第 18 节的扩展点 captureAttribution）。
 * 链接带 `src`（渠道）、`cmp`（活动）、`ref`（分享人或渠道编号）、`start`（Telegram 机器人深链参数）时：
 * - 记在浏览器的 `fc.attr`（30 天）：first = 第一次带参数访问时的值（30 天内不覆盖），last = 最近一次的值；
 * - 告诉后端一次"带来源的访问"（来源统计的"访问"一列，后端按设备号去重）；
 * - 注册时把 first、last 一起交给后端（首次来源、注册来源），之后就不再用。
 * <b>只用于统计，不给任何人奖励</b>（MQ-5）。客户自己看不到这些字段。值的清洗和 start 的解码在后端（common/attribution/Attribution.java），
 * 这里只做长度和字符的粗检查，不合法的不记。读写本地存储失败（隐私模式）时什么都不记，不影响页面。
 */
import { recordVisit } from '../api/public';
import type { Attribution, AttributionTouch } from '../api/types';

const KEY = 'fc.attr';
const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;
const VALUE = /^[A-Za-z0-9._~-]{1,64}$/;
const START = /^[A-Za-z0-9_-]{1,64}$/;

type Params = Pick<AttributionTouch, 'src' | 'cmp' | 'ref' | 'start'>;

/** 从地址的查询串里取来源参数；一个都没有（或都不合法）返回 null */
export function sourceFrom(search: string): Params | null {
  const q = new URLSearchParams(search);
  const out: Params = {};
  for (const k of ['src', 'cmp', 'ref'] as const) {
    const v = q.get(k)?.trim();
    if (v && VALUE.test(v)) out[k] = v;
  }
  const st = q.get('start')?.trim();
  if (st && START.test(st)) out.start = st;
  return Object.keys(out).length ? out : null;
}

function load(): Attribution {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Attribution) : {};
  } catch {
    return {};
  }
}

function fresh(t: AttributionTouch | undefined, now: number): AttributionTouch | undefined {
  if (!t || typeof t.at !== 'string') return undefined;
  const at = Date.parse(t.at);
  return Number.isFinite(at) && now - at <= MAX_AGE_MS ? t : undefined;
}

/** 浏览器里现在记着的来源（过期的去掉）；注册时用 */
export function readAttribution(now = Date.now()): Attribution | undefined {
  const a = load();
  const first = fresh(a.first, now);
  const last = fresh(a.last, now);
  return first || last ? { first, last } : undefined;
}

/** 同一个页面加载里同一组参数只上报一次（路由切换会多次调用） */
let reported = '';

/**
 * 路由层每次地址变化时调用（App.tsx 的 AttributionCapture）。有来源参数时：first 没有或已过期就记成这一次，last 总是更新；并上报一次访问。
 * @returns 记下的来源（测试用）
 */
export function captureAttribution(search: string, now = Date.now()): Attribution | null {
  const p = sourceFrom(search);
  if (!p) return null;
  const touch: AttributionTouch = { ...p, at: new Date(now).toISOString() };
  const cur = load();
  const next: Attribution = { first: fresh(cur.first, now) ?? touch, last: touch };
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    return null;
  }
  const sig = JSON.stringify(p);
  if (sig !== reported) {
    reported = sig;
    void recordVisit(p).catch(() => undefined);
  }
  return next;
}

/** 注册成功后不再需要（以后换个账户注册不会带上一个人的来源） */
export function clearAttribution(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* 忽略 */
  }
}
