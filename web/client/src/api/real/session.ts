/**
 * real 模式下"当前是否已登录"（S17）。只记一个布尔值，不保存任何令牌（令牌在 HttpOnly Cookie 里）。
 * AuthProvider 在登录、退出、401 时更新它；实时推送（WebSocket）和"告诉后端界面语言"据此开始或停止。
 */

type Listener = (loggedIn: boolean) => void;

let loggedIn = false;
const listeners = new Set<Listener>();

export function isLoggedIn(): boolean {
  return loggedIn;
}

export function setLoggedIn(v: boolean): void {
  if (loggedIn === v) return;
  loggedIn = v;
  listeners.forEach((l) => l(v));
}

export function onSessionChange(l: Listener): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}
