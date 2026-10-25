/**
 * 站内信、界面语言、Web Push 订阅的真实接口（`notification/service/NotificationController.java`，通知方案第 8 节）。
 */
import { get, post, put } from '../http';
import type { Lang, NotificationItem, Page } from '../types';
import { isLoggedIn, onSessionChange } from './session';

/** 站内信按 lang（当前界面语言）生成标题和正文 */
export const listNotifications = (lang: Lang, page: number, pageSize: number): Promise<Page<NotificationItem>> =>
  get<Page<NotificationItem>>('/api/notifications', { query: { lang, page, pageSize } });

export const getUnreadCount = async (): Promise<number> => (await get<{ count: number }>('/api/notifications/unread-count')).count ?? 0;

export async function markRead(id: string): Promise<void> {
  await post<void>(`/api/notifications/${encodeURIComponent(id)}/read`);
}

export async function markAllRead(): Promise<void> {
  await post<void>('/api/notifications/read-all');
}

// ---------- 推送和邮件用的语言 ----------
let lang: Lang | null = null;

function sendLang(): void {
  if (!lang || !isLoggedIn()) return;
  // 失败不影响使用：下次切换语言或重新登录时还会再告诉后端
  // 繁体的推送和邮件模板在 S9 加（架构方案第 8 节）；在那之前繁体界面的推送、邮件用简体
  put<void>('/api/me/notify-lang', { lang: lang === 'zh-TW' ? 'zh-CN' : lang }, { quiet401: true }).catch(() => undefined);
}

/** 切换界面语言时调用（PUT /api/me/notify-lang）；还没登录时先记下，登录后再告诉后端 */
export function setPushLanguage(l: Lang): void {
  const changed = lang !== l;
  lang = l;
  if (changed) sendLang();
}
onSessionChange((v) => v && sendLang());

// ---------- Web Push ----------
/** 推送公钥（applicationServerKey）；服务器没配置推送时为 null */
export const getPushKey = async (): Promise<string | null> => (await get<{ publicKey?: string }>('/api/notifications/push/key')).publicKey ?? null;

/** 保存这台设备的订阅：`PushSubscription.toJSON()` 原样提交 */
export async function savePushSubscription(sub: PushSubscriptionJSON): Promise<void> {
  await post<void>('/api/notifications/push/subscriptions', sub);
}

/** 取消这台设备的订阅（退出登录、关闭推送时） */
export async function removePushSubscription(endpoint: string): Promise<void> {
  await post<void>('/api/notifications/push/unsubscribe', { endpoint }, { quiet401: true });
}
