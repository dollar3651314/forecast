/**
 * 推给本人的事件 `WS /api/ws/user`（需要登录，只推本人，后端 `common/push/UserPush.java`）：
 * `account`、`withdraw`、`deposit`、`notification`，格式和 types.ts 的 ServerEvent 一致（S4 起加结算结果）。
 * 断线自动重连。**每次连上（包括第一次）**都补发一次 account，让页面重新拉取：
 * 第一次连上之前（页面加载后、推送连上前）和断线期间发生的变化都收不到推送（D-2，部署形态端到端测试发现）。
 * 资产页的充值记录收到 account 也会重新拉取。
 */
import type { ServerEvent } from '../types';
import { ReconnectingSocket } from '../ws';
import { isLoggedIn, onSessionChange } from './session';

type Handler = (e: ServerEvent) => void;
const handlers = new Set<Handler>();

const TYPES = new Set<ServerEvent['type']>(['account', 'withdraw', 'deposit', 'notification']);

function emit(e: ServerEvent): void {
  handlers.forEach((h) => h(e));
}

export function handleUserMessage(msg: unknown): void {
  const e = msg as ServerEvent | undefined;
  if (!e || !TYPES.has(e.type)) return;
  if (e.type === 'notification' && !e.item) return;
  emit(e);
}

const socket = new ReconnectingSocket('/api/ws/user', {
  onMessage: handleUserMessage,
  onOpen: () => {
    emit({ type: 'account' });
  },
});

function sync(): void {
  if (isLoggedIn() && handlers.size > 0) socket.start();
  else if (!isLoggedIn()) socket.stop();
}
onSessionChange(sync);

export function subscribeEvents(h: Handler): () => void {
  handlers.add(h);
  sync();
  return () => {
    handlers.delete(h);
  };
}
