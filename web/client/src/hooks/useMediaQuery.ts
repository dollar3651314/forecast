import { useSyncExternalStore } from 'react';

/** 订阅媒体查询，例如桌面布局 (min-width: 1024px) */
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

export const DESKTOP_QUERY = '(min-width: 1024px)';
export const useIsDesktop = (): boolean => useMediaQuery(DESKTOP_QUERY);
