/**
 * 接收服务端推送：每条新通知都在页面顶部显示提示。
 * - 真实环境：登录后按授权和偏好订阅 Web Push（api/webPush.ts），系统通知由 Service Worker 收到推送后显示；
 * - 原型：不接推送服务，用户允许了浏览器通知且开启了推送偏好时，在本地用系统通知展示。
 */
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { IS_MOCK } from '../api/mode';
import { syncPushSubscription } from '../api/webPush';
import { getNotifyPrefs } from '../api/user';
import type { NotificationKind } from '../api/types';
import { useToast, type ToastTone } from '../app/contexts';
import { useServerEvent } from '../hooks/useServerEvent';
import { showSystemNotification } from '../lib/notify';

const TONE: Record<NotificationKind, ToastTone> = {
  deposit: 'success',
  withdraw: 'info',
  kyc: 'info',
  system: 'info',
};

export function PushListener() {
  const toast = useToast();
  const { t } = useTranslation();
  useEffect(() => {
    void syncPushSubscription();
  }, []);
  useServerEvent((e) => {
    if (e.type === 'notification') {
      toast.show({ tone: TONE[e.item.kind] ?? 'info', title: e.item.title, body: e.item.body });
      if (!IS_MOCK) return;
      void getNotifyPrefs()
        .then((p) => (p.push ? showSystemNotification(e.item.title, e.item.body) : false))
        .catch(() => false);
    }
    if (e.type === 'withdraw' && e.status === 'cancelled') {
      toast.show({ tone: 'success', title: t('withdraw.cancelled') });
    }
  });
  return null;
}
