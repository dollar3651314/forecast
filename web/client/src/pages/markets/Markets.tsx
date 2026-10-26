/**
 * P1 市场列表（需求第 5.2 节，线框图 W1）。
 * - 手机：品类标签一行（放不下时这一行内左右滑，页面本身不横向滚动）、搜索 + 显示 + 排序一行、卡片单列；
 * - 电脑：标题和筛选一行、品类标签、卡片 3 列网格（≥1440 宽 4 列）。
 * 筛选条件放在地址栏（?cat=&show=&sort=&q=），刷新、返回时保持。已结束的市场默认不显示（"显示"选"已结束"才列出）。
 * S11：搜索、排序、分页都在后端（一页 24 个，"加载更多"，C52）；搜索框停止输入 300 毫秒后才查；可下注的市场订阅奖池推送（前 20 个），
 * 概率和赔率只换数字（C84）。S13：顶部"今日场次"（P11，MF-3）；不登录也能看（MF-4）；页脚是开奖记录、周报、说明页的入口。
 */
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { listMarkets, subscribePools } from '../../api/markets';
import type { MarketCard as Card, MarketCategory, MarketQuery } from '../../api/types';
import { EmptyState, ErrorState, LoadMore, Loading } from '../../components/AsyncView';
import { Icon } from '../../components/Icon';
import { GuestGate } from '../../components/GuestGate';
import { MarketCard } from '../../components/market/MarketCard';
import { TodaySlate } from '../../components/market/TodaySlate';
import { PublicLinks } from '../../components/PublicLinks';
import { useTitle } from '../../hooks/useTitle';
import { useIsDesktop } from '../../hooks/useMediaQuery';
import { usePaged } from '../../hooks/usePaged';
import { useClock } from '../../lib/market';

const CATS: Array<MarketCategory | 'all'> = ['all', 'FOOTBALL', 'NBA', 'CRYPTO_15M', 'CRYPTO_1H', 'MACRO'];
const SHOWS = ['active', 'closed', 'ended'] as const;
const SORTS = ['closeAt', 'pool', 'newest'] as const;

/** 一页几个：电脑 3 列 / 4 列都能排满 */
const PAGE_SIZE = 24;

type Show = (typeof SHOWS)[number];
type Sort = (typeof SORTS)[number];

function pick<T extends string>(v: string | null, all: readonly T[], fallback: T): T {
  return v && (all as readonly string[]).includes(v) ? (v as T) : fallback;
}

export default function Markets() {
  const { t } = useTranslation();
  const desktop = useIsDesktop();
  const [params, setParams] = useSearchParams();
  useTitle(t('mk.title'));
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
  // 搜索框：边输入边显示，停 300 毫秒才改地址栏（才去查）
  const [typing, setTyping] = useState(q);
  useEffect(() => setTyping(q), [q]);
  useEffect(() => {
    if (typing === q) return;
    const id = setTimeout(() => set('q', typing, ''), 300);
    return () => clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typing]);

  const query: MarketQuery = { category: cat === 'all' ? undefined : cat, status: show, sort, keyword: q };
  const s = usePaged((page, size) => listMarkets(query, page, size), JSON.stringify(query), PAGE_SIZE, (m) => m.publicNo);
  const nearest = useMemo(() => {
    const xs = s.items.filter((m) => m.status === 'listed').map((m) => Date.parse(m.closeAt)).filter((x) => x > Date.now());
    return xs.length ? Math.min(...xs) : undefined;
  }, [s.items]);
  const now = useClock(nearest);

  // 奖池推送：可下注的前 20 个市场（后端每个连接最多 20 个）
  const live = useMemo(() => s.items.filter((m) => m.status === 'listed').map((m) => m.publicNo).slice(0, 20), [s.items]);
  const { setData } = s;
  useEffect(
    () =>
      subscribePools(live, (e) =>
        setData((d) => d && { ...d, items: d.items.map((m: Card) => (m.publicNo === e.marketNo ? { ...m, status: e.status, pool: e.pool } : m)) }),
      ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [live.join(','), setData],
  );

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
      <input type="search" value={typing} maxLength={100} placeholder={t('mk.search')} aria-label={t('mk.search')} onChange={(e) => setTyping(e.target.value)} />
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
            {s.data && <span className="mk-count">{t('mk.count', { n: s.total })}</span>}
          </div>
          <div className="mk-tools">
            {search}
            {selects}
          </div>
        </div>
      ) : null}
      <TodaySlate />
      {cats}
      {!desktop && (
        <div className="mk-tools">
          {search}
          {selects}
        </div>
      )}
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
            title={q ? t('mk.emptySearch', { q }) : t('mk.empty')}
            hint={q || cat !== 'all' || show !== 'active' ? t('mk.emptyFilteredHint') : t('mk.emptyHint')}
            action={
              q || cat !== 'all' || show !== 'active' ? (
                <button type="button" className="btn btn-outline btn-sm" onClick={() => setParams(new URLSearchParams(), { replace: true })}>
                  {t('mk.clearFilters')}
                </button>
              ) : undefined
            }
          />
        </section>
      ) : (
        <>
          {!desktop && <p className="mk-count-m">{t('mk.count', { n: s.total })}</p>}
          <div className="mk-grid">
            {s.items.map((m) => (
              <MarketCard key={m.publicNo} m={m} now={now} />
            ))}
          </div>
          <LoadMore shown={s.items.length} total={s.total} hasMore={s.hasMore} loading={s.loadingMore} error={s.moreError} onMore={() => void s.loadMore()} />
        </>
      )}
      <p className="mk-note">
        <Icon name="info" size={14} />
        {t('mk.oddsNote')}
      </p>
      <PublicLinks />
    </div>
  );
}
