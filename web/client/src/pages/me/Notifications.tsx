/**
 * 通知中心（需求 P6）：站内信列表、未读数、单条已读、全部已读；点一条有关联页面的（市场、提现、充值、认证，后端给 link）直接打开那个页面，
 * 没有的展开全文。分页“加载更多”（C52）。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { errorKey } from '../../api/errors';
import { listNotifications, markAllRead, markRead } from '../../api/notifications';
import type { NotificationItem } from '../../api/types';
import { useToast } from '../../app/contexts';
import { EmptyState, ErrorState, LoadMore, Loading } from '../../components/AsyncView';
import { Icon } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { currentLang } from '../../i18n';
import { usePaged } from '../../hooks/usePaged';
import { useServerEvent } from '../../hooks/useServerEvent';
import { notifyUnreadChanged, useUnread } from '../../hooks/useUnread';
import { formatDateTime } from '../../lib/format';

export default function Notifications() {
  const { t, i18n } = useTranslation();
  const toast = useToast();
  const navigate = useNavigate();
  const unread = useUnread();
  const s = usePaged((page, size) => listNotifications(currentLang(), page, size), `notes|${i18n.language}`, 20, (n) => n.id);
  const [expanded, setExpanded] = useState<string | null>(null);
  useServerEvent((e) => e.type === 'notification' && s.reload());

  const setRead = (ids: string[] | 'all') =>
    s.setData((d) => d && { ...d, items: d.items.map((n: NotificationItem) => (ids === 'all' || ids.includes(n.id) ? { ...n, read: true } : n)) });

  const open = async (n: NotificationItem) => {
    if (!n.link) setExpanded((x) => (x === n.id ? null : n.id));
    if (!n.read) {
      try {
        await markRead(n.id);
        setRead([n.id]);
        notifyUnreadChanged();
      } catch (e) {
        toast.show({ tone: 'error', title: t(errorKey(e)) });
      }
    }
    if (n.link) navigate(n.link);
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
                  <button type="button" onClick={() => open(n)} aria-expanded={n.link ? undefined : expanded === n.id} data-link={n.link ?? undefined}>
                    <span className={`note-kind k-${n.kind}`}>{t(`notifications.kind.${n.kind}`)}</span>
                    <span className="note-main">
                      <b>{n.title}</b>
                      <span className={`note-body${expanded === n.id ? ' full' : ''}`}>{n.body}</span>
                    </span>
                    <span className="note-meta">
                      <small className="num">{formatDateTime(n.createdAt, { short: true })}</small>
                      {!n.read && <span className="unread-dot" aria-label={t('notifications.unread')} />}
                      {n.link && (
                        <span className="note-go" title={t('notifications.open')}>
                          <Icon name="chevronRight" size={14} />
                        </span>
                      )}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            <LoadMore shown={s.items.length} total={s.total} hasMore={s.hasMore} loading={s.loadingMore} error={s.moreError} onMore={() => void s.loadMore()} />
          </>
        )}
      </section>
    </div>
  );
}
