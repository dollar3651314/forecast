/**
 * 通知偏好（需求 P7）：分别开关推送和邮件（站内信始终保留）；S11 加"开赛提醒"（#23，SQ-20）：订阅了的市场列表、退订。
 * 订阅在市场详情（还没开放的市场"开放时提醒我"），发到账户邮箱；邮件关着时提醒不会发出，这里写明。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { errorKey } from '../../api/errors';
import { listReminders, unsubscribeReminder } from '../../api/markets';
import { getNotifyPrefs, updateNotifyPrefs } from '../../api/user';
import type { NotifyPrefs as Prefs, ReminderItem } from '../../api/types';
import { syncPushSubscription } from '../../api/webPush';
import { useToast } from '../../app/contexts';
import { AsyncView, EmptyState } from '../../components/AsyncView';
import { TimePair } from '../../components/market/parts';
import { Switch } from '../../components/Form';
import { PageHeader } from '../../components/Layout';
import { useAsync } from '../../hooks/useAsync';
import { useLang } from '../../hooks/useLang';
import { pickText } from '../../lib/market';
import { notificationPermission, requestNotificationPermission } from '../../lib/notify';

/** 开赛提醒（#23）：订阅了的市场和最近发过的 */
function Reminders({ emailOn }: { emailOn: boolean | undefined }) {
  const { t } = useTranslation();
  const toast = useToast();
  const lang = useLang();
  const s = useAsync(listReminders, 'reminders');
  const [busy, setBusy] = useState<string | null>(null);
  const off = async (r: ReminderItem) => {
    setBusy(r.marketNo);
    try {
      const st = await unsubscribeReminder(r.marketNo);
      s.setData((d) => d && { ...d, items: d.items.map((x) => (x.marketNo === r.marketNo ? { ...x, status: st.status } : x)) });
      toast.show({ tone: 'success', title: t('remind.unsubscribed') });
    } catch (e) {
      toast.show({ tone: 'error', title: t(errorKey(e)) });
    } finally {
      setBusy(null);
    }
  };
  return (
    <section className="card" aria-labelledby="remind-h" data-testid="reminders">
      <div className="card-head">
        <h2 id="remind-h">{t('remind.title')}</h2>
      </div>
      <p className="muted small">{t('remind.desc')}</p>
      {emailOn === false && (
        <div className="notice warn" role="note">
          {t('remind.emailOff')}
        </div>
      )}
      <AsyncView
        state={s}
        rows={2}
        compact
        isEmpty={(d) => d.items.length === 0}
        empty={
          <>
            {s.data && !s.data.eligible && (
              <div className="notice" role="note">
                {t('remind.needLogin')}
              </div>
            )}
            <EmptyState compact title={t('remind.empty')} hint={t('remind.emptyHint')} />
          </>
        }
      >
        {(d) => (
          <ul className="remind-list">
            {d.items.map((r) => (
              <li key={r.marketNo} className={`rm-${r.status}`}>
                <div className="rm-main">
                  <Link to={`/markets/${r.marketNo}`}>{pickText(r.title, lang)}</Link>
                  <small>
                    {t('md.openAt')} <TimePair iso={r.openAt} />
                  </small>
                </div>
                <span className={`rm-status s-${r.status}`}>{t(`remind.status.${r.status}`)}</span>
                {r.status === 'active' && (
                  <button type="button" className="btn btn-ghost btn-sm" disabled={busy === r.marketNo} onClick={() => off(r)}>
                    {t('remind.unsubscribe')}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </AsyncView>
    </section>
  );
}

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
      <Reminders emailOn={s.data?.email} />
    </div>
  );
}
