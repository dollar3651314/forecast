/** 站内信、推送语言。real：/api/notifications/*、/api/me/notify-lang；mock：原型模拟层（见 mode.ts）。 */
import * as mock from '../mocks/user';
import { IS_MOCK } from './mode';
import * as real from './real/notifications';
import type { Lang, NotificationItem, Page } from './types';

export const listNotifications = (lang: Lang, page: number, pageSize = 20): Promise<Page<NotificationItem>> =>
  IS_MOCK ? mock.listNotifications(lang, page, pageSize) : real.listNotifications(lang, page, pageSize);
export const getUnreadCount = (): Promise<number> => (IS_MOCK ? mock.unreadCount() : real.getUnreadCount());
export const markRead = (id: string): Promise<void> => (IS_MOCK ? mock.markRead(id) : real.markRead(id));
export const markAllRead = (): Promise<void> => (IS_MOCK ? mock.markAllRead() : real.markAllRead());
/** 告诉服务端当前界面语言，推送和邮件按该语言生成（real：PUT /api/me/notify-lang，登录后才发） */
export const setPushLanguage = (lang: Lang): void => (IS_MOCK ? mock.setMockLang(lang) : real.setPushLanguage(lang));
