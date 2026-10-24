/**
 * 通用的异步加载：返回 加载中 / 出错 / 数据，并在原型场景切换后自动重新加载。
 */
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { IS_MOCK } from '../api/mode';
import { getScenarioVersion, subscribeProto } from '../api/prototype';

export interface AsyncState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
  /** 本地更新数据（例如操作成功后不必整页刷新） */
  setData: (updater: (prev: T | undefined) => T | undefined) => void;
}

const noSubscribe = () => () => {};
const zero = () => 0;

/** 原型场景的版本号；真实接口模式下没有原型场景，恒为 0（按编译期常量选，real 构建不打包模拟层） */
export function useScenarioVersion(): number {
  return useSyncExternalStore(IS_MOCK ? subscribeProto : noSubscribe, IS_MOCK ? getScenarioVersion : zero, IS_MOCK ? getScenarioVersion : zero);
}

/**
 * @param fn  加载函数
 * @param key 依赖变化时的标识（例如品种代码、筛选条件拼成的字符串）
 */
export function useAsync<T>(fn: () => Promise<T>, key: string): AsyncState<T> {
  const version = useScenarioVersion();
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const [state, setState] = useState<{ data: T | undefined; error: unknown; loading: boolean }>({ data: undefined, error: undefined, loading: true });
  const [nonce, setNonce] = useState(0);

  const lastKey = useRef<string | null>(null);

  useEffect(() => {
    let alive = true;
    // 场景或参数变化时清空旧数据（显示加载中）；手动刷新或推送触发的刷新保留旧数据，避免闪烁
    const k = `${key}|${version}`;
    const hard = lastKey.current !== k;
    lastKey.current = k;
    setState((s) => (hard ? { data: undefined, error: undefined, loading: true } : { ...s, loading: true }));
    fnRef.current().then(
      (data) => alive && setState({ data, error: undefined, loading: false }),
      (error: unknown) => alive && setState({ data: undefined, error, loading: false }),
    );
    return () => {
      alive = false;
    };
  }, [key, version, nonce]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const setData = useCallback((updater: (prev: T | undefined) => T | undefined) => setState((s) => ({ ...s, data: updater(s.data) })), []);
  return { ...state, reload, setData };
}
