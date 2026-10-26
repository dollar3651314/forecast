/**
 * 加载中 / 出错 / 空数据 三种状态的统一展示（规则 C32、C42）。
 * 出错时只显示原因和"重试"，不显示错误代码。
 */
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../api/errors';
import { Icon } from './Icon';

export function Loading({ rows = 3, label }: { rows?: number; label?: string }) {
  const { t } = useTranslation();
  return (
    <div className="loading" role="status" aria-live="polite">
      <span className="sr-only">{label ?? t('common.loading')}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton" style={{ width: `${92 - i * 14}%` }} />
      ))}
      <div className="loading-text">{label ?? t('common.loading')}</div>
    </div>
  );
}

export function ErrorState({ error, onRetry, compact }: { error: unknown; onRetry?: () => void; compact?: boolean }) {
  const { t } = useTranslation();
  return (
    <div className={`state state-error${compact ? ' compact' : ''}`} role="alert">
      <Icon name="warn" size={compact ? 18 : 28} />
      <div>
        <b>{t('common.loadFailed')}</b>
        <p>{t(errorKey(error))}</p>
      </div>
      {onRetry && (
        <button type="button" className="btn btn-outline btn-sm" onClick={onRetry}>
          {t('common.retry')}
        </button>
      )}
    </div>
  );
}

export function EmptyState({ title, hint, action, compact }: { title: string; hint?: string; action?: ReactNode; compact?: boolean }) {
  return (
    <div className={`state state-empty${compact ? ' compact' : ''}`}>
      <svg width={compact ? 36 : 56} height={compact ? 36 : 56} viewBox="0 0 56 56" aria-hidden="true">
        <rect x="8" y="14" width="40" height="30" rx="6" fill="none" stroke="currentColor" strokeWidth="2" opacity=".5" />
        <path d="M8 26h12l3 5h10l3-5h12" fill="none" stroke="currentColor" strokeWidth="2" opacity=".5" />
      </svg>
      <b>{title}</b>
      {hint && <p>{hint}</p>}
      {action}
    </div>
  );
}

/** 根据 useAsync 的状态渲染：数据为空时显示 empty */
export function AsyncView<T>(props: {
  state: { data: T | undefined; error: unknown; loading: boolean; reload: () => void };
  isEmpty?: (d: T) => boolean;
  empty?: ReactNode;
  rows?: number;
  compact?: boolean;
  children: (d: T) => ReactNode;
}) {
  const { state } = props;
  if (state.error && !state.data) return <ErrorState error={state.error} onRetry={state.reload} compact={props.compact} />;
  if (state.data === undefined) return <Loading rows={props.rows} />;
  if (props.isEmpty?.(state.data)) return <>{props.empty}</>;
  return <>{props.children(state.data)}</>;
}

/**
 * 列表底部（C52：能看到全部记录）：还有没显示的显示"加载更多（已显示 n / 共 m）"，取下一页失败时就地显示原因和重试；全部显示完写"共 m 条"。
 * 和 hooks/usePaged.ts 一起用。
 */
export function LoadMore({ shown, total, hasMore, loading, error, onMore }: { shown: number; total: number; hasMore: boolean; loading: boolean; error?: unknown; onMore: () => void }) {
  const { t } = useTranslation();
  if (!hasMore) return <p className="list-end">{t('common.noMore', { total })}</p>;
  return (
    <div className="load-more">
      {error ? (
        <p className="load-more-err" role="alert">
          <Icon name="warn" size={14} />
          {t('common.loadMoreFailed', { reason: t(errorKey(error)) })}
        </p>
      ) : null}
      <button type="button" className="btn btn-ghost btn-block" onClick={onMore} disabled={loading} data-testid="load-more">
        {loading ? t('common.loading') : error ? t('common.retry') : t('common.loadMore', { shown, total })}
      </button>
    </div>
  );
}
