/**
 * P12 开奖记录（S13，MF-6；需求第 5.2 节 P12、AC-88）：已结算、已作废、已取消的市场，结果、依据、奖池、派彩合计、抽成、尾差、裁决人编号、
 * 作废 / 取消原因；按品类、状态、时间筛选；一页 20 条"加载更多"（C52）。不登录可看，可以被收录（服务器给搜索引擎的版本见后端 SeoPages）。
 * 手机卡片列表、电脑表格（C64）。筛选条件放在地址栏（?cat=&st=&range=），刷新、返回时保持。
 */
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { listResults } from '../../api/public';
import type { MarketCategory, ResultQuery } from '../../api/types';
import { EmptyState, ErrorState, LoadMore, Loading } from '../../components/AsyncView';
import { GuestGate } from '../../components/GuestGate';
import { Icon } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { PublicLinks } from '../../components/PublicLinks';
import { useIsDesktop } from '../../hooks/useMediaQuery';
import { usePaged } from '../../hooks/usePaged';
import { useTitle } from '../../hooks/useTitle';
import { ResultCard, ResultTable } from './parts';

const CATS: Array<MarketCategory | 'all'> = ['all', 'FOOTBALL', 'NBA', 'CRYPTO_15M', 'CRYPTO_1H', 'MACRO'];
const STATUSES = ['all', 'settled', 'voided', 'cancelled'] as const;
const RANGES = ['all', '7d', '30d'] as const;
const PAGE_SIZE = 20;

function pick<T extends string>(v: string | null, all: readonly T[], fallback: T): T {
  return v && (all as readonly string[]).includes(v) ? (v as T) : fallback;
}

export default function Results() {
  const { t } = useTranslation();
  const desktop = useIsDesktop();
  useTitle(t('res.title'));
  const [params, setParams] = useSearchParams();
  const cat = pick(params.get('cat'), CATS, 'all');
  const st = pick(params.get('st'), STATUSES, 'all');
  const range = pick(params.get('range'), RANGES, 'all');
  const set = (k: string, v: string, def: string) => {
    const next = new URLSearchParams(params);
    if (v === def) next.delete(k);
    else next.set(k, v);
    setParams(next, { replace: true });
  };
  const days = range === '7d' ? 7 : range === '30d' ? 30 : 0;
  // 时间筛选按天取整，免得每次渲染都是新的查询
  const from = days ? new Date(Math.floor(Date.now() / 86_400_000) * 86_400_000 - days * 86_400_000).toISOString() : undefined;
  const q: ResultQuery = { category: cat === 'all' ? undefined : cat, status: st === 'all' ? undefined : st, from };
  const s = usePaged((page, size) => listResults(q, page, size), JSON.stringify(q), PAGE_SIZE, (r) => r.publicNo);
  const filtered = cat !== 'all' || st !== 'all' || range !== 'all';

  return (
    <div className={`page page-results${desktop ? ' is-d' : ' is-m'}`}>
      <PageHeader
        title={t('res.title')}
        right={
          <Link to="/results/weekly" className="btn btn-outline btn-sm">
            <Icon name="list" size={14} />
            {t('pub.weekly')}
          </Link>
        }
      />
      <p className="res-intro muted">{t('res.intro')}</p>
      <div className="cat-row" role="tablist" aria-label={t('res.title')}>
        {CATS.map((c) => (
          <button key={c} type="button" role="tab" aria-selected={cat === c} className={`cat-chip${cat === c ? ' on' : ''}`} onClick={() => set('cat', c, 'all')}>
            {t(`mk.cats.${c}`)}
          </button>
        ))}
      </div>
      <div className="mk-tools res-tools">
        <label className="mk-select">
          <span className="sr-only">{t('res.statusLabel')}</span>
          <select value={st} onChange={(e) => set('st', e.target.value, 'all')}>
            {STATUSES.map((x) => (
              <option key={x} value={x}>
                {x === 'all' ? t('res.allStatus') : t(`mk.status.${x}`)}
              </option>
            ))}
          </select>
          <Icon name="chevronDown" size={12} strokeWidth={2.4} />
        </label>
        <label className="mk-select">
          <span className="sr-only">{t('res.rangeLabel')}</span>
          <select value={range} onChange={(e) => set('range', e.target.value, 'all')}>
            {RANGES.map((x) => (
              <option key={x} value={x}>
                {t(`res.range.${x}`)}
              </option>
            ))}
          </select>
          <Icon name="chevronDown" size={12} strokeWidth={2.4} />
        </label>
        {s.data && <span className="mk-count">{t('res.count', { n: s.total })}</span>}
      </div>
      <GuestGate error={s.error} />
      {s.error && !s.data ? (
        <section className="card">
          <ErrorState error={s.error} onRetry={s.reload} />
        </section>
      ) : !s.data ? (
        <section className="card">
          <Loading rows={5} />
        </section>
      ) : s.items.length === 0 ? (
        <section className="card">
          <EmptyState
            title={filtered ? t('res.emptyFiltered') : t('res.empty')}
            hint={filtered ? undefined : t('res.emptyHint')}
            action={
              filtered ? (
                <button type="button" className="btn btn-outline btn-sm" onClick={() => setParams(new URLSearchParams(), { replace: true })}>
                  {t('mk.clearFilters')}
                </button>
              ) : undefined
            }
          />
        </section>
      ) : (
        <section className={desktop ? 'card flush res-wrap' : 'res-wrap'}>
          {desktop ? (
            <ResultTable items={s.items} />
          ) : (
            <ul className="res-list">
              {s.items.map((r) => (
                <ResultCard key={r.publicNo} r={r} />
              ))}
            </ul>
          )}
          <LoadMore shown={s.items.length} total={s.total} hasMore={s.hasMore} loading={s.loadingMore} error={s.moreError} onMore={() => void s.loadMore()} />
        </section>
      )}
      <p className="mk-note">
        <Icon name="info" size={14} />
        {t('md.res.conservation')}
      </p>
      <PublicLinks />
    </div>
  );
}
