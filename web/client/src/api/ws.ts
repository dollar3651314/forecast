/**
 * 断线自动重连的 WebSocket（S17，只在 real 模式使用）。
 * 同源 `ws(s)://<当前域名>/api/ws/...`，握手时浏览器自动带上登录 Cookie（Cookie 的 path 是 /api）。
 * 断开后按 1、2、4 … 最多 30 秒退避重连；页面从后台切回来、网络恢复时立即重连。
 */

export interface SocketHandlers {
  onMessage: (data: unknown) => void;
  /** 每次连上都会调用；reconnect 为 true 表示断线后重连（期间可能漏了推送） */
  onOpen?: (send: (msg: unknown) => void, reconnect: boolean) => void;
}

const MIN_DELAY = 1000;
const MAX_DELAY = 30_000;

export function wsUrl(path: string): string {
  const loc = typeof window !== 'undefined' ? window.location : undefined;
  const proto = loc?.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${loc?.host ?? 'localhost'}${path}`;
}

export class ReconnectingSocket {
  private ws: WebSocket | null = null;
  private wanted = false;
  private delay = MIN_DELAY;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private opened = false;
  private readonly path: string;
  private readonly handlers: SocketHandlers;

  constructor(path: string, handlers: SocketHandlers) {
    this.path = path;
    this.handlers = handlers;
  }

  /** 开始连接（已经在连就什么都不做） */
  start(): void {
    if (this.wanted) return;
    this.wanted = true;
    if (typeof window !== 'undefined') {
      window.addEventListener('online', this.wake);
      document.addEventListener('visibilitychange', this.wake);
    }
    this.connect();
  }

  /** 断开且不再重连（退出登录） */
  stop(): void {
    this.wanted = false;
    this.opened = false;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    if (typeof window !== 'undefined') {
      window.removeEventListener('online', this.wake);
      document.removeEventListener('visibilitychange', this.wake);
    }
    const ws = this.ws;
    this.ws = null;
    if (ws) {
      ws.onclose = null;
      ws.onmessage = null;
      ws.onerror = null;
      ws.onopen = null;
      ws.close();
    }
  }

  get running(): boolean {
    return this.wanted;
  }

  send(msg: unknown): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  /** 页面回到前台或网络恢复：如果正在等待重连，马上连 */
  private wake = (): void => {
    if (!this.wanted || this.ws) return;
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.delay = MIN_DELAY;
    this.connect();
  };

  private connect(): void {
    if (!this.wanted || typeof WebSocket === 'undefined') return;
    let ws: WebSocket;
    try {
      ws = new WebSocket(wsUrl(this.path));
    } catch {
      this.schedule();
      return;
    }
    this.ws = ws;
    ws.onopen = () => {
      this.delay = MIN_DELAY;
      const reconnect = this.opened;
      this.opened = true;
      this.handlers.onOpen?.((m) => this.send(m), reconnect);
    };
    ws.onmessage = (ev: MessageEvent) => {
      let data: unknown;
      try {
        data = JSON.parse(String(ev.data), (_k, v: unknown) => (v === null ? undefined : v));
      } catch {
        return;
      }
      this.handlers.onMessage(data);
    };
    ws.onclose = () => {
      if (this.ws === ws) this.ws = null;
      this.schedule();
    };
    ws.onerror = () => {
      // 之后一定会触发 onclose，在那里重连
    };
  }

  private schedule(): void {
    if (!this.wanted || this.timer) return;
    const d = this.delay;
    this.delay = Math.min(this.delay * 2, MAX_DELAY);
    this.timer = setTimeout(() => {
      this.timer = null;
      this.connect();
    }, d);
  }
}
