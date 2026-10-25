import { useSyncExternalStore } from 'react';
import { proto } from '../api';

/** 原型场景切换时返回新的版本号，用于让当前页面立刻重新加载 */
export function useScenarioVersion(): number {
  return useSyncExternalStore(proto.subscribeScenario, proto.getScenarioVersion, proto.getScenarioVersion);
}
