/**
 * 新手引导（体验优化 1021 第 2 条，方案 UX-2）的状态：
 * - 新客户（身份认证还是"未提交"）登录后第一次进来自动打开一次；看完或点"跳过"就记下，不再自动打开；
 * - "我的 → 新手引导"可以随时重看（`openOnboarding`）。
 * 原型阶段记在这个浏览器（localStorage，按用户分开）；正式上线时改成后端字段，换设备不再重复弹（方案第 2 节）。
 */
import { useCallback, useSyncExternalStore } from 'react';

const KEY = 'fc.onboarding.v1';
const listeners = new Set<() => void>();
let forced = false;

function notify(): void {
  listeners.forEach((l) => l());
}
function subscribe(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}
const getForced = (): boolean => forced;

function storageKey(userId: string): string {
  return `${KEY}:${userId}`;
}

export function onboardingDone(userId: string): boolean {
  try {
    return localStorage.getItem(storageKey(userId)) === 'done';
  } catch {
    // 读不到（隐私模式等）时当成看过，避免每次打开都弹
    return true;
  }
}

/** "我的 → 新手引导"：手动打开 */
export function openOnboarding(): void {
  forced = true;
  notify();
}

export function useOnboarding(userId: string | undefined) {
  const forcedOpen = useSyncExternalStore(subscribe, getForced, getForced);
  const finish = useCallback(() => {
    forced = false;
    if (userId) {
      try {
        localStorage.setItem(storageKey(userId), 'done');
      } catch {
        /* 忽略 */
      }
    }
    notify();
  }, [userId]);
  return { forcedOpen, finish };
}

/** 测试用：清掉手动打开的状态 */
export function resetOnboarding(): void {
  forced = false;
  notify();
}
