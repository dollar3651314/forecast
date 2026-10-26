/**
 * 周报列表（S13，MF-6；需求 P12"每周周报，历史周报可翻看"）：从本周往前，每周的场数、有效场数（已结算）、作废和取消的场数、
 * 奖池合计、派彩合计、抽成合计；本周标"截至现在"。一次 12 周，"更早的周"接着往前翻。点一周看这一周的市场。不登录可看。
 * 周按 UTC 的 ISO 周（周一 00:00 起），和后台、周报卡片同一个口径。
 */
import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { listWeeks } from '../../api/public';
import type { WeekList } from '../../api/types';
import { EmptyState, ErrorState, Loading } from '../../components/AsyncView';
import { GuestGate } from '../../components/GuestGate';
import { Icon } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { PublicLinks } from '../../components/PublicLinks';
import { useAsync } from '../../hooks/useAsync';
import { useTitle } from '../../hooks/useTitle';
import { WeekStats } from './parts';
import { weekRange } from './format';

export default function Weekly() {
  const { t } = useTranslation();
  useTitle(t('wk.title'));
  const s = useAsync(() => listWeeks(1), 'weeks');
  const [more, setMore] = useState<{ pages: WeekList[]; loading: boolean; error: unknown }>({ pages: [], loading: false, error: undefined });
  const last = more.pages[more.pages.length - 1] ?? s.data;
  const loadMore = useCallback(async () => {
    if (!last) return;
    setMore((m) => ({ ...m, loading: true, error: undefined }));
    try {
      const p = await listWeeks(last.page + 1);
      setMore((m) => ({ pages: [...m.pages, p], loading: false, error: undefined }));
    } catch (error) {
      setMore((m) => ({ ...m, loading: false, error }));
    }
  }, [last]);
  const weeks = [...(s.data?.items ?? []), ...more.pages.flatMap((p) => p.items)];

  return (
    <div className="page page-weekly">
      <PageHeader
        title={t('wk.title')}
        back="/results"
        right={
          <Link to="/results" className="btn btn-outline btn-sm">
            {t('pub.results')}
          </Link>
        }
      />
      <p className="res-intro muted">{t('wk.intro')}</p>
      <GuestGate error={s.error} />
      {s.error && !s.data ? (
        <section className="card">
          <ErrorState error={s.error} onRetry={s.reload} />
        </section>
      ) : !s.data ? (
        <section className="card">
          <Loading rows={4} />
        </section>
      ) : weeks.length === 0 ? (
        <section className="card">
          <EmptyState title={t('wk.empty')} />
        </section>
      ) : (
        <>
          <ul className="wk-list">
            {weeks.map((w) => (
              <li key={w.week}>
                <Link to={`/results/weekly/${w.week}`} className="wk-card" data-testid="week-item">
                  <div className="wk-head">
                    <b className="num">{w.week}</b>
                    <span className="num muted">{weekRange(w)} UTC</span>
                    {!w.complete && <span className="mk-status s-open">{t('wk.inProgress')}</span>}
                    <Icon name="chevronRight" size={16} className="chev" />
                  </div>
                  <WeekStats w={w} />
                </Link>
              </li>
            ))}
          </ul>
          <div className="load-more">
            {more.error ? (
              <ErrorState error={more.error} onRetry={() => void loadMore()} compact />
            ) : last?.hasMore ? (
              <button type="button" className="btn btn-outline" disabled={more.loading} onClick={() => void loadMore()}>
                {more.loading ? t('common.loading') : t('wk.more')}
              </button>
            ) : (
              <p className="muted small">{t('wk.allShown', { n: weeks.length })}</p>
            )}
          </div>
        </>
      )}
      <PublicLinks />
    </div>
  );
}
