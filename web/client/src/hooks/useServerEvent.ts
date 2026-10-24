import { useEffect, useRef } from 'react';
import { subscribeEvents } from '../api/events';
import type { ServerEvent } from '../api/types';

/** 订阅服务端推送事件；handler 每次渲染可以变化，不会导致重复订阅 */
export function useServerEvent(handler: (e: ServerEvent) => void): void {
  const ref = useRef(handler);
  ref.current = handler;
  useEffect(() => subscribeEvents((e) => ref.current(e)), []);
}
