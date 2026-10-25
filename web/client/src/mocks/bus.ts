/**
 * 模拟的服务端推送通道（将来是 WebSocket + Web Push）。
 */
import type { ServerEvent } from '../api/types';

type Handler = (e: ServerEvent) => void;
const handlers = new Set<Handler>();

export function emit(e: ServerEvent): void {
  handlers.forEach((h) => h(e));
}

export function onServerEvent(h: Handler): () => void {
  handlers.add(h);
  return () => handlers.delete(h);
}
