/**
 * Web Push 订阅（S17，通知方案第 8 节）。只在 real 模式工作，mock 模式什么都不做。
 * - 浏览器已授权通知、通知偏好里推送开着、后端配置了公钥：用公钥订阅（pushManager.subscribe），把 toJSON() 交给后端；
 * - 推送关了：取消这台设备的订阅；公钥为 null：不订阅；
 * - 退出登录：先告诉后端取消这台设备，再取消浏览器里的订阅。
 * 收到推送后由 Service Worker（`public/push-sw.js`）显示系统通知。所有失败都不影响页面使用。
 */
import { IS_MOCK } from './mode';
import { getPushKey, removePushSubscription, savePushSubscription } from './real/notifications';
import { getNotifyPrefs } from './real/user';

export function webPushSupported(): boolean {
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

/** base64url → 字节（applicationServerKey） */
export function base64UrlToBytes(s: string): Uint8Array<ArrayBuffer> {
  const pad = '='.repeat((4 - (s.length % 4)) % 4);
  const raw = atob((s + pad).replace(/-/g, '+').replace(/_/g, '/'));
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

function sameKey(a: ArrayBuffer | null | undefined, b: Uint8Array): boolean {
  if (!a) return false;
  const x = new Uint8Array(a);
  return x.length === b.length && x.every((v, i) => v === b[i]);
}

async function registration(): Promise<ServiceWorkerRegistration | undefined> {
  try {
    return await navigator.serviceWorker.getRegistration();
  } catch {
    return undefined;
  }
}

/**
 * 按当前授权和偏好同步这台设备的订阅。登录后、改推送偏好后、授权通知后调用。
 * @param push 已知的推送偏好；不传时向后端查询
 */
export async function syncPushSubscription(push?: boolean): Promise<void> {
  if (IS_MOCK || !webPushSupported()) return;
  try {
    if (Notification.permission !== 'granted') return;
    const reg = await registration();
    if (!reg) return;
    const enabled = push ?? (await getNotifyPrefs()).push;
    const existing = await reg.pushManager.getSubscription();
    if (!enabled) {
      if (existing) {
        await removePushSubscription(existing.endpoint).catch(() => undefined);
        await existing.unsubscribe().catch(() => false);
      }
      return;
    }
    const key = await getPushKey();
    if (!key) return;
    const appKey = base64UrlToBytes(key);
    let sub = existing;
    // 服务器换了公钥：旧订阅收不到推送，重新订阅
    if (sub && !sameKey(sub.options.applicationServerKey, appKey)) {
      await sub.unsubscribe().catch(() => false);
      sub = null;
    }
    sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: appKey });
    await savePushSubscription(sub.toJSON());
  } catch {
    // 推送是附加功能：浏览器不支持、用户拒绝、网络失败都静默跳过，站内信照常
  }
}

/** 退出登录前调用（此时还有登录态） */
export async function unsubscribeThisDevice(): Promise<void> {
  if (IS_MOCK || !webPushSupported()) return;
  try {
    const reg = await registration();
    const sub = await reg?.pushManager.getSubscription();
    if (!sub) return;
    await removePushSubscription(sub.endpoint).catch(() => undefined);
    await sub.unsubscribe().catch(() => false);
  } catch {
    /* 忽略 */
  }
}
