/**
 * P1 市场列表（需求第 5.2 节，线框图 W1）。
 * - 手机：品类标签一行（放不下时这一行内左右滑，页面本身不横向滚动）、搜索 + 显示 + 排序一行、卡片单列；
 * - 电脑：标题和筛选一行、品类标签、卡片 3 列网格（≥1440 宽 4 列）。
 * 筛选条件放在地址栏（?cat=&show=&sort=&q=），刷新、返回时保持。已结束的市场默认不显示（"显示"选"已结束"才列出）。
 */
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { listMarkets } from '../../api/markets';
import type { MarketCategory, MarketQuery } from '../../api/types';
import { AsyncView, EmptyState } from '../../components/AsyncView';
import { Icon } from '../../components/Icon';
import { MarketCard } from '../../components/market/MarketCard';
import { useAsync } from '../../hooks/useAsync';
import { useIsDesktop } from '../../hooks/useMediaQuery';
import { useClock } from '../../lib/market';

const CATS: Array<MarketCategory | 'all'> = ['all', 'FOOTBALL', 'NBA', 'CRYPTO_15M', 'CRYPTO_1H', 'MACRO'];
const SHOWS = ['active', 'closed', 'ended'] as const;
const SORTS = ['closeAt', 'pool', 'newest'] as const;

type Show = (typeof SHOWS)[number];
type Sort = (typeof SORTS)[number];

function pick<T extends string>(v: string | null, all: readonly T[], fallback: T): T {
  return v && (all as readonly string[]).includes(v) ? (v as T) : fallback;
}

export default function Markets() {
  const { t } = useTranslation();
  const desktop = useIsDesktop();
  const [params, setParams] = useSearchParams();
  const cat = pick(params.get('cat'), CATS, 'all');
  const show = pick<Show>(params.get('show'), SHOWS, 'active');
  const sort = pick<Sort>(params.get('sort'), SORTS, 'closeAt');
  const q = params.get('q') ?? '';
  const set = (k: string, v: string, def: string) => {
    const next = new URLSearchParams(params);
    if (v === def) next.delete(k);
    else next.set(k, v);
    setParams(next, { replace: true });
  };

  const query: MarketQuery = { category: cat === 'all' ? undefined : cat, status: show, sort, keyword: q };
  const s = useAsync(() => listMarkets(query), JSON.stringify(query));
  const nearest = useMemo(() => {
    const xs = (s.data ?? []).filter((m) => m.status === 'listed').map((m) => Date.parse(m.closeAt)).filter((x) => x > Date.now());
    return xs.length ? Math.min(...xs) : undefined;
  }, [s.data]);
  const now = useClock(nearest);

  const cats = (
    <div className="cat-row" role="tablist" aria-label={t('mk.title')}>
      {CATS.map((c) => (
        <button key={c} type="button" role="tab" aria-selected={cat === c} className={`cat-chip${cat === c ? ' on' : ''}`} onClick={() => set('cat', c, 'all')}>
          {t(`mk.cats.${c}`)}
        </button>
      ))}
    </div>
  );
  const search = (
    <label className="mk-search">
      <Icon name="search" size={16} />
      <input type="search" value={q} placeholder={t('mk.search')} aria-label={t('mk.search')} onChange={(e) => set('q', e.target.value, '')} />
    </label>
  );
  const selects = (
    <>
      <label className="mk-select">
        <span className="sr-only">{t('mk.show.label')}</span>
        <select value={show} onChange={(e) => set('show', e.target.value, 'active')}>
          {SHOWS.map((x) => (
            <option key={x} value={x}>
              {t(`mk.show.${x}`)}
            </option>
          ))}
        </select>
        <Icon name="chevronDown" size={12} strokeWidth={2.4} />
      </label>
      <label className="mk-select">
        <span className="sr-only">{t('mk.sort.label')}</span>
        <Icon name="sort" size={14} />
        <select value={sort} onChange={(e) => set('sort', e.target.value, 'closeAt')}>
          {SORTS.map((x) => (
            <option key={x} value={x}>
              {t(`mk.sort.${x}`)}
            </option>
          ))}
        </select>
        <Icon name="chevronDown" size={12} strokeWidth={2.4} />
      </label>
    </>
  );

  return (
    <div className={`page page-markets${desktop ? ' is-d' : ' is-m'}`}>
      {desktop ? (
        <div className="mk-head">
          <div className="mk-head-l">
            <h1>{t('mk.title')}</h1>
            {s.data && <span className="mk-count">{t('mk.count', { n: s.data.length })}</span>}
          </div>
          <div className="mk-tools">
            {search}
            {selects}
          </div>
        </div>
      ) : null}
      {cats}
      {!desktop && (
        <div className="mk-tools">
          {search}
          {selects}
        </div>
      )}
      <AsyncView
        state={s}
        rows={5}
        isEmpty={(d) => d.length === 0}
        empty={
          <section className="card">
            <EmptyState title={t('mk.empty')} hint={t('mk.emptyHint')} />
          </section>
        }
      >
        {(d) => (
          <div className="mk-grid">
            {d.map((m) => (
              <MarketCard key={m.publicNo} m={m} now={now} />
            ))}
          </div>
        )}
      </AsyncView>
      <p className="mk-note">
        <Icon name="info" size={14} />
        {t('mk.oddsNote')}
      </p>
    </div>
  );
}
