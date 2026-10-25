import { useEffect } from 'react';
import { getUnreadCount } from '../api/notifications';
import { useAsync } from './useAsync';
import { useServerEvent } from './useServerEvent';

const listeners = new Set<() => void>();

/** 页面里标记已读后调用，让所有未读角标刷新 */
export function notifyUnreadChanged(): void {
  listeners.forEach((l) => l());
}

/** 未读站内信数量 */
export function useUnread(): number {
  const s = useAsync(getUnreadCount, 'unread');
  const { reload } = s;
  useServerEvent((e) => {
    // account：推送每次连上（含断线重连，api/real/events.ts）都会补发一次，断线期间收到的站内信靠它补刷新未读数
    // （体验优化 1021 UX-9）；成交、出入金后也会有，顺带刷新，请求很轻
    if (e.type === 'notification' || e.type === 'account') reload();
  });
  useEffect(() => {
    listeners.add(reload);
    return () => {
      listeners.delete(reload);
    };
  }, [reload]);
  return s.data ?? 0;
}
