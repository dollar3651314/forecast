/**
 * 公开版（forecast S19，P11）用的 api/notifications 空实现。公开站永远是"没登录"：
 * - setPushLanguage：原实现只在登录后发 PUT /api/me/notify-lang，这里什么都不做；
 * - 其他几个是站内信（登录后才有），公开站的页面不会调用，调用就报错。
 * vite.public.config.ts 的 PUBLIC_STUBS 在打包时把原模块换成这里；导出的名字和签名要和 src/api/notifications.ts 一致（s19.test.ts 检查）。
 */
import type { Lang, NotificationItem, Page } from '../../api/types';

const notAvailable = (): Promise<never> => Promise.reject(new Error('公开版没有站内信'));

export const listNotifications = (lang: Lang, page: number, pageSize = 20): Promise<Page<NotificationItem>> => (void lang, void page, void pageSize, notAvailable());
export const getUnreadCount = (): Promise<number> => notAvailable();
export const markRead = (id: string): Promise<void> => (void id, notAvailable());
export const markAllRead = (): Promise<void> => notAvailable();
export const setPushLanguage = (lang: Lang): void => void lang;
