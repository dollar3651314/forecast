/** 通知中心：站内信列表、未读数、单条已读、全部已读。 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../../api/errors';
import { listNotifications, markAllRead, markRead } from '../../api/notifications';
import type { NotificationItem } from '../../api/types';
import { useToast } from '../../app/contexts';
import { EmptyState, ErrorState, Loading } from '../../components/AsyncView';
import { PageHeader } from '../../components/Layout';
import { currentLang } from '../../i18n';
import { usePaged } from '../../hooks/usePaged';
import { useServerEvent } from '../../hooks/useServerEvent';
import { notifyUnreadChanged, useUnread } from '../../hooks/useUnread';
import { formatDateTime } from '../../lib/format';

export default function Notifications() {
  const { t, i18n } = useTranslation();
  const toast = useToast();
  const unread = useUnread();
  const s = usePaged((page, size) => listNotifications(currentLang(), page, size), `notes|${i18n.language}`);
  const [expanded, setExpanded] = useState<string | null>(null);
  useServerEvent((e) => e.type === 'notification' && s.reload());

  const setRead = (ids: string[] | 'all') =>
    s.setData((d) => d && { ...d, items: d.items.map((n: NotificationItem) => (ids === 'all' || ids.includes(n.id) ? { ...n, read: true } : n)) });

  const open = async (n: NotificationItem) => {
    setExpanded((x) => (x === n.id ? null : n.id));
    if (n.read) return;
    try {
      await markRead(n.id);
      setRead([n.id]);
      notifyUnreadChanged();
    } catch (e) {
      toast.show({ tone: 'error', title: t(errorKey(e)) });
    }
  };

  const readAll = async () => {
    try {
      await markAllRead();
      setRead('all');
      notifyUnreadChanged();
      toast.show({ tone: 'success', title: t('notifications.allReadDone') });
    } catch (e) {
      toast.show({ tone: 'error', title: t(errorKey(e)) });
    }
  };

  return (
    <div className="page page-narrow">
      <PageHeader
        title={t('me.notifications')}
        back="/me"
        right={
          <button type="button" className="btn btn-outline btn-sm" onClick={readAll} disabled={unread === 0}>
            {t('notifications.markAll')}
          </button>
        }
      />
      <section className="card flush">
        <div className="list-meta pad">{t('notifications.unreadCount', { count: unread })}</div>
        {s.error && !s.data ? (
          <ErrorState error={s.error} onRetry={s.reload} />
        ) : !s.data ? (
          <Loading rows={5} />
        ) : s.items.length === 0 ? (
          <EmptyState title={t('notifications.empty')} hint={t('notifications.emptyHint')} />
        ) : (
          <>
            <ul className="note-full">
              {s.items.map((n) => (
                <li key={n.id} className={n.read ? '' : 'unread'}>
                  <button type="button" onClick={() => open(n)} aria-expanded={expanded === n.id}>
                    <span className={`note-kind k-${n.kind}`}>{t(`notifications.kind.${n.kind}`)}</span>
                    <span className="note-main">
                      <b>{n.title}</b>
                      <span className={`note-body${expanded === n.id ? ' full' : ''}`}>{n.body}</span>
                    </span>
                    <span className="note-meta">
                      <small className="num">{formatDateTime(n.createdAt, { short: true })}</small>
                      {!n.read && <span className="unread-dot" aria-label={t('notifications.unread')} />}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            {s.hasMore ? (
              <button type="button" className="btn btn-ghost btn-block" onClick={s.loadMore} disabled={s.loading}>
                {s.loading ? t('common.loading') : t('common.loadMore', { shown: s.items.length, total: s.total })}
              </button>
            ) : (
              <p className="list-end">{t('common.noMore', { total: s.total })}</p>
            )}
          </>
        )}
      </section>
    </div>
  );
}
