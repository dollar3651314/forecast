/**
 * 手机行情页顶部的提示卡片（页面内普通卡片，不浮动遮挡）："添加到主屏幕，接收提醒" + "允许通知"。
 * 点"以后再说""添加到主屏幕""允许通知"任一按钮后本地记住，不再出现。
 * 原型里只调用浏览器的通知权限请求，不接后端推送服务。
 */
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useToast } from '../app/contexts';
import { syncPushSubscription } from '../api/webPush';
import { notificationPermission, requestNotificationPermission } from '../lib/notify';
import { Icon } from './Icon';

const KEY = 'fc.installPrompt.dismissed';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

function isStandalone(): boolean {
  return typeof window !== 'undefined' && (window.matchMedia?.('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true);
}

function readInstallDismissed(): boolean {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
}

export function InstallPrompt() {
  const { t } = useTranslation();
  const toast = useToast();
  const [visible, setVisible] = useState(() => !readInstallDismissed() && !isStandalone());
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null);
  const [, setPerm] = useState(notificationPermission());

  useEffect(() => {
    const onBip = (e: Event) => {
      e.preventDefault();
      setDeferred(e as BeforeInstallPromptEvent);
    };
    const onReset = () => setVisible(true);
    window.addEventListener('beforeinstallprompt', onBip);
    window.addEventListener('fc:show-install', onReset);
    return () => {
      window.removeEventListener('beforeinstallprompt', onBip);
      window.removeEventListener('fc:show-install', onReset);
    };
  }, []);

  const dismiss = () => {
    setVisible(false);
    try {
      localStorage.setItem(KEY, '1');
    } catch {
      /* 忽略 */
    }
  };

  const allow = async () => {
    const r = await requestNotificationPermission();
    dismiss();
    setPerm(r);
    if (r === 'granted') {
      toast.show({ tone: 'success', title: t('install.permGranted') });
      void syncPushSubscription();
    }
    else if (r === 'denied') toast.show({ tone: 'warn', title: t('install.permDenied') });
    else if (r === 'unsupported') toast.show({ tone: 'warn', title: t('install.permUnsupported') });
  };

  const install = async () => {
    if (!deferred) return;
    await deferred.prompt();
    setDeferred(null);
    dismiss();
  };

  if (!visible) return null;
  return (
    <section className="card install-card" aria-labelledby="install-title">
      <div className="install-icon">
        <Icon name="phone" size={22} />
      </div>
      <div className="install-text">
        <b id="install-title">{t('install.title')}</b>
        <p>{deferred ? t('install.bodyInstallable') : t('install.bodyIos')}</p>
        <div className="btn-row">
          {deferred && (
            <button type="button" className="btn btn-primary btn-sm" onClick={install}>
              {t('install.add')}
            </button>
          )}
          <button type="button" className="btn btn-primary btn-sm" onClick={allow}>
            {t('install.allow')}
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={dismiss}>
            {t('install.later')}
          </button>
        </div>
      </div>
    </section>
  );
}
