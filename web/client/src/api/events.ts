/** 服务端推送事件。real：WebSocket /api/ws/user（断线自动重连）；mock：模拟层的事件总线。 */
import { onServerEvent } from '../mocks/bus';
import { IS_MOCK } from './mode';
import { subscribeEvents as realSubscribe } from './real/events';
import type { ServerEvent } from './types';

export const subscribeEvents = (h: (e: ServerEvent) => void): (() => void) => (IS_MOCK ? onServerEvent(h) : realSubscribe(h));
