/**
 * 市场奖池的实时推送 `WS /api/ws/markets`（后端 `pool/push/PoolPush.java`，需求 AC-17：变化后 2 秒内推到正在看的页面）。
 * - 连上后发 `{"op":"sub","markets":[…]}` 订阅（每个连接最多 20 个），订阅时后端立即推一次现在的奖池；断线自动重连，重连后重新订阅；
 * - 推送 `{"type":"pool","marketNo","status","ts","pool":{…}}`，pool 和详情的 pool 同一个结构，但不含本人的持仓和限额；
 * - 页面拿到后只换数字（C84：不闪色、不做动画）。没有页面订阅时断开连接。
 */
import type { MarketPool, MarketStatus } from '../types';
import { ReconnectingSocket } from '../ws';
import { isLoggedIn, onSessionChange } from './session';

export interface PoolPushEvent {
  marketNo: string;
  status: MarketStatus;
  ts: string;
  pool: MarketPool;
}

type Handler = (e: PoolPushEvent) => void;

/** 每个市场的订阅者 */
const subs = new Map<string, Set<Handler>>();
/** 每个连接最多订阅几个市场（和后端 forecast.pool.push.max-subscriptions 一致） */
export const MAX_MARKETS = 20;

function handle(msg: unknown): void {
  const e = msg as (PoolPushEvent & { type?: string }) | undefined;
  if (!e || e.type !== 'pool' || !e.marketNo || !e.pool) return;
  subs.get(e.marketNo)?.forEach((h) => h(e));
}

const socket = new ReconnectingSocket('/api/ws/markets', {
  onMessage: handle,
  onOpen: (send) => {
    const markets = [...subs.keys()].slice(0, MAX_MARKETS);
    if (markets.length) send({ op: 'sub', markets });
  },
});

function sync(): void {
  if (isLoggedIn() && subs.size > 0) socket.start();
  else socket.stop();
}
onSessionChange(sync);

/** 订阅一批市场的奖池推送（超过 20 个的只订前 20 个）；返回取消函数 */
export function subscribePools(marketNos: string[], h: Handler): () => void {
  const added: string[] = [];
  for (const no of marketNos.slice(0, MAX_MARKETS)) {
    let set = subs.get(no);
    if (!set) {
      set = new Set();
      subs.set(no, set);
      added.push(no);
    }
    set.add(h);
  }
  sync();
  if (added.length && socket.running) socket.send({ op: 'sub', markets: added });
  return () => {
    const removed: string[] = [];
    for (const no of marketNos.slice(0, MAX_MARKETS)) {
      const set = subs.get(no);
      if (!set) continue;
      set.delete(h);
      if (set.size === 0) {
        subs.delete(no);
        removed.push(no);
      }
    }
    if (removed.length && socket.running) socket.send({ op: 'unsub', markets: removed });
    sync();
  };
}
