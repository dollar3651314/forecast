/*
 * Web Push（S17，通知方案第 8 节）。由 Workbox 生成的 Service Worker 通过 importScripts 加载（vite.config.ts）。
 * 推送内容是后端 NotifyWorker.pushPayload 生成的 JSON：{title, body, kind, id}。
 * - 页面正开着并在前台时不再弹系统通知（页面会收到 WebSocket 推送并显示提示），避免重复；
 * - 点击通知打开（或切到）站内信页面。
 */
self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = { body: event.data ? event.data.text() : '' };
  }
  const title = data.title || 'Quick Come'; // 品牌名（上线前沿用 Quick Come），和 src/lib/brand.ts 一致（Service Worker 里读不到它）
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      const visible = list.some((c) => c.visibilityState === 'visible' && c.focused);
      if (visible) return undefined;
      return self.registration.showNotification(title, {
        body: data.body || '',
        icon: '/icon-192.png',
        badge: '/icon-192.png',
        tag: data.id ? `fx-${data.id}` : undefined,
        data: { url: '/me/notifications' },
      });
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || '/me/notifications';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      for (const c of list) {
        if ('focus' in c) {
          if ('navigate' in c) c.navigate(url).catch(() => undefined);
          return c.focus();
        }
      }
      return self.clients.openWindow ? self.clients.openWindow(url) : undefined;
    }),
  );
});
