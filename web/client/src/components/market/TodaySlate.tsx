/**
 * P11 今日场次（S13，MF-3；需求第 4.2 节、第 5.2 节 P11、AC-82）：市场列表顶部，按客户所在时区的"今天"列出带"列入今日场次"标记的市场：
 * 场次时间（本地时间 + UTC）、题目、各选项的人数和概率、状态；点进市场详情。今天没有场次时显示下一场的时间。不登录也能看。
 * - 手机：一行横向卡片（这一行内左右滑，页面本身不横向滚动）；电脑：列表上方一行（最多 4 列，多的换行）。
 * - 数字随奖池推送更新，只换数字，不闪动、不滚动（C84）。
 */
import { useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { subscribePools } from '../../api/markets';
import { todaySlate } from '../../api/public';
import type { MarketCard } from '../../api/types';
import { useAsync } from '../../hooks/useAsync';
import { useLang } from '../../hooks/useLang';
import { localDay, localIsUtc, pickText, utcText } from '../../lib/market';
import { ErrorState } from '../AsyncView';
import { Icon } from '../Icon';
import { StatusTag } from './parts';

const pad = (n: number) => (n < 10 ? `0${n}` : String(n));

/** UTC 时分（"13:00 UTC"） */
function utcHm(iso: string): string {
  const d = new Date(iso);
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())} UTC`;
}

/** 本地时分（"21:00"）；不是今天的带月日 */
function localHm(iso: string, withDate: boolean): string {
  const d = new Date(iso);
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return withDate ? `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}` : hm;
}

function SlateItem({ m }: { m: MarketCard }) {
  const { t } = useTranslation();
  const lang = useLang();
  const upcoming = (m.status === 'listed' || m.status === 'paused') && Date.parse(m.openAt) > Date.now();
  const pools = new Map(m.pool.options.map((o) => [o.optionNo, o]));
  return (
    <li className="slate-item">
      <Link to={`/markets/${m.publicNo}`} className="slate-link" data-testid="slate-item">
        <div className="slate-time">
          <b className="num">{localHm(m.eventStartAt, false)}</b>
          {!localIsUtc() && <small className="num">{utcHm(m.eventStartAt)}</small>}
          {upcoming ? <span className="mk-status s-wait">{t('mk.status.upcoming')}</span> : <StatusTag status={m.status} />}
        </div>
        <p className="slate-title">{pickText(m.title, lang)}</p>
        <ul className="slate-opts">
          {m.options.slice(0, 3).map((o) => {
            const p = pools.get(o.optionNo);
            return (
              <li key={o.optionNo}>
                <span className="slate-opt">{pickText(o.label, lang)}</span>
                <b className="num">{p?.pctList ?? '—'}</b>
                <small className="num">{t('mk.people', { n: p?.bettors ?? 0, count: p?.bettors ?? 0 })}</small>
              </li>
            );
          })}
        </ul>
      </Link>
    </li>
  );
}

export function TodaySlate() {
  const { t } = useTranslation();
  const lang = useLang();
  const day = useMemo(() => localDay(), []);
  const s = useAsync(() => todaySlate(day.from, day.to), `slate|${day.from}`);
  const { setData } = s;
  const live = useMemo(() => (s.data?.items ?? []).filter((m) => m.status === 'listed' || m.status === 'paused').map((m) => m.publicNo), [s.data]);
  useEffect(
    () =>
      subscribePools(live, (e) => setData((d) => d && { ...d, items: d.items.map((m) => (m.publicNo === e.marketNo ? { ...m, status: e.status, pool: e.pool } : m)) })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [live.join(','), setData],
  );

  let body;
  if (s.error && !s.data) body = <ErrorState error={s.error} onRetry={s.reload} compact />;
  else if (!s.data) body = <div className="slate-loading" role="status" aria-label={t('common.loading')} />;
  else if (s.data.items.length === 0) {
    const n = s.data.next;
    body = (
      <p className="slate-empty">
        {t('slate.none')}
        {n ? (
          <>
            {' '}
            <Link to={`/markets/${n.publicNo}`}>
              {t('slate.next', { t: localHm(n.eventStartAt, true), utc: utcText(n.eventStartAt), title: pickText(n.title, lang) })}
            </Link>
          </>
        ) : (
          <> {t('slate.noneNext')}</>
        )}
      </p>
    );
  } else
    body = (
      <ul className="slate-row">
        {s.data.items.map((m) => (
          <SlateItem key={m.publicNo} m={m} />
        ))}
      </ul>
    );

  return (
    <section className="slate" aria-label={t('slate.title')} data-testid="today-slate">
      <h2 className="slate-h">
        <Icon name="clock" size={15} strokeWidth={2.2} />
        {t('slate.title')}
        <small className="num">{day.label}</small>
      </h2>
      {body}
    </section>
  );
}
