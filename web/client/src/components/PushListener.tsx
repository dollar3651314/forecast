/**
 * 接收服务端推送：每条新通知都在页面顶部显示提示；市场的通知、而客户正在看这个市场时不再弹（S11：例如下注成功的站内信，
 * 下注时页面已经提示过；只更新未读数）。充值、提现的通知照常弹（资产页上也要提示到账）。
 * - 真实环境：登录后按授权和偏好订阅 Web Push（api/webPush.ts），系统通知由 Service Worker 收到推送后显示；
 * - 原型：不接推送服务，用户允许了浏览器通知且开启了推送偏好时，在本地用系统通知展示。
 */
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';
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
  const { pathname } = useLocation();
  const here = useRef(pathname);
  here.current = pathname;
  useEffect(() => {
    void syncPushSubscription();
  }, []);
  useServerEvent((e) => {
    if (e.type === 'notification') {
      if (e.item.link?.startsWith('/markets/') && e.item.link.split('?')[0] === here.current) return;
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
