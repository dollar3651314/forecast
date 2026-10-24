/** 通知偏好：分别开关推送和邮件（站内信始终保留）。 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../../api/errors';
import { getNotifyPrefs, updateNotifyPrefs } from '../../api/user';
import type { NotifyPrefs as Prefs } from '../../api/types';
import { syncPushSubscription } from '../../api/webPush';
import { useToast } from '../../app/contexts';
import { AsyncView } from '../../components/AsyncView';
import { Switch } from '../../components/Form';
import { PageHeader } from '../../components/Layout';
import { useAsync } from '../../hooks/useAsync';
import { notificationPermission, requestNotificationPermission } from '../../lib/notify';

export default function NotifyPrefs() {
  const { t } = useTranslation();
  const toast = useToast();
  const s = useAsync(getNotifyPrefs, 'prefs');
  const [busy, setBusy] = useState<keyof Prefs | null>(null);
  const [perm, setPerm] = useState(notificationPermission());
  const toggle = async (p: Prefs, k: keyof Prefs, v: boolean) => {
    setBusy(k);
    try {
      const np = await updateNotifyPrefs({ ...p, [k]: v });
      s.setData(() => np);
      if (k === 'push') void syncPushSubscription(np.push);
      toast.show({ tone: 'success', title: t('prefs.saved') });
    } catch (e) {
      toast.show({ tone: 'error', title: t(errorKey(e)) });
    } finally {
      setBusy(null);
    }
  };
  return (
    <div className="page page-narrow">
      <PageHeader title={t('me.notifyPrefs')} back="/me" />
      <AsyncView state={s}>
        {(p) => (
          <section className="card">
            <div className="pref-row">
              <div>
                <b id="pref-push">{t('prefs.push')}</b>
                <p className="muted small">{t('prefs.pushDesc')}</p>
              </div>
              <Switch checked={p.push} onChange={(v) => toggle(p, 'push', v)} label={t('prefs.push')} disabled={busy === 'push'} />
            </div>
            <div className="pref-perm">
              <span>
                {t('prefs.browserPerm')}: <b>{t(`prefs.perm.${perm}`)}</b>
              </span>
              {perm !== 'granted' && perm !== 'unsupported' && (
                <button type="button" className="btn btn-outline btn-sm" onClick={async () => {
                    const r = await requestNotificationPermission();
                    setPerm(r);
                    if (r === 'granted') void syncPushSubscription(p.push);
                  }}>
                  {t('install.allow')}
                </button>
              )}
            </div>
            <div className="pref-row">
              <div>
                <b>{t('prefs.email')}</b>
                <p className="muted small">{t('prefs.emailDesc')}</p>
              </div>
              <Switch checked={p.email} onChange={(v) => toggle(p, 'email', v)} label={t('prefs.email')} disabled={busy === 'email'} />
            </div>
            <div className="pref-row">
              <div>
                <b>{t('prefs.inbox')}</b>
                <p className="muted small">{t('prefs.inboxDesc')}</p>
              </div>
              <Switch checked onChange={() => undefined} label={t('prefs.inbox')} disabled />
            </div>
            <p className="muted small">{t('prefs.androidNote')}</p>
          </section>
        )}
      </AsyncView>
    </div>
  );
}
