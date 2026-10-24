import { useSyncExternalStore } from 'react';

/** 订阅媒体查询（和 web/client/src/hooks/useMediaQuery.ts 相同） */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (cb) => {
      if (typeof window === 'undefined' || !window.matchMedia) return () => undefined;
      const m = window.matchMedia(query);
      m.addEventListener('change', cb);
      return () => m.removeEventListener('change', cb);
    },
    () => (typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(query).matches : false),
    () => false,
  );
}

/** 电脑版结构从 1024 宽开始（和客户端一致） */
export const DESKTOP_QUERY = '(min-width: 1024px)';
export const useIsDesktop = (): boolean => useMediaQuery(DESKTOP_QUERY);
