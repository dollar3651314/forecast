/**
 * 原型场景（规则 C32、C42）：正常数据 / 空数据 / 接口很慢 / 接口出错。
 * 只在原型阶段存在；正式开发时连同整个 mocks 目录一起替换为真实接口。
 */
import type { KycStatus } from '../api/types';

export type Scenario = 'normal' | 'empty' | 'slow' | 'error';

export interface ProtoState {
  scenario: Scenario;
}

const STORAGE_KEY = 'fc.proto.state';

function load(): ProtoState {
  const fallback: ProtoState = { scenario: 'normal' };
  try {
    const raw = typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null;
    return raw ? { ...fallback, ...(JSON.parse(raw) as Partial<ProtoState>) } : fallback;
  } catch {
    return fallback;
  }
}

// 标成纯调用：真实接口模式的构建没有用到 state 时整句去掉（读 localStorage 不算需要保留的副作用）
let state: ProtoState = /* @__PURE__ */ load();
let version = 0;
const listeners = new Set<() => void>();

export function getProtoState(): ProtoState {
  return state;
}

/** 每次场景变化 +1，页面据此重新加载 */
export function getScenarioVersion(): number {
  return version;
}

export function setProtoState(patch: Partial<ProtoState>): void {
  state = { ...state, ...patch };
  version += 1;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    /* 隐私模式下写不进去也不影响使用 */
  }
  listeners.forEach((l) => l());
}

export function subscribeProto(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

export const scenario = (): Scenario => state.scenario;

// KYC 状态单独由 user 模拟模块维护，这里只做类型导出，方便原型面板使用
export type { KycStatus };
