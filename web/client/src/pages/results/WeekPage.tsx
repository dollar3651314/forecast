/**
 * 一周的周报（S13，MF-6，AC-88）：场数、有效场数、作废和取消的场数和原因、奖池合计、派彩合计、抽成合计、尾差、退款合计；
 * 下面是这一周结束的市场（和开奖记录同一个样子）。不登录可看。周报卡片图片放到 MF-5。
 */
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { isApiError } from '../../api/errors';
import { getWeek } from '../../api/public';
import { EmptyState, ErrorState, Loading } from '../../components/AsyncView';
import { GuestGate } from '../../components/GuestGate';
import { PageHeader } from '../../components/Layout';
import { PublicLinks } from '../../components/PublicLinks';
import { useAsync } from '../../hooks/useAsync';
import { useIsDesktop } from '../../hooks/useMediaQuery';
import { useTitle } from '../../hooks/useTitle';
import { formatAmount } from '../../lib/format';
import { weekRange } from './format';
import { ResultCard, ResultTable, WeekStats } from './parts';

export default function WeekPage() {
  const { t } = useTranslation();
  const desktop = useIsDesktop();
  const { week = '' } = useParams();
  useTitle(t('wk.oneTitle', { week }));
  const s = useAsync(() => getWeek(week), `week|${week}`);
  const w = s.data?.week;
  // 周的格式不对：后端 400（validation），按"不存在"显示
  const invalid = isApiError(s.error) && (s.error.reason === 'validationFailed' || s.error.reason === 'badRequest' || s.error.reason === 'notFound');

  return (
    <div className="page page-week">
      <PageHeader title={t('wk.oneTitle', { week })} back="/results/weekly" />
      <GuestGate error={s.error} />
      {invalid ? (
        <section className="card">
          <EmptyState title={t('wk.notFound')} />
        </section>
      ) : s.error && !s.data ? (
        <section className="card">
          <ErrorState error={s.error} onRetry={s.reload} />
        </section>
      ) : !s.data || !w ? (
        <section className="card">
          <Loading rows={5} />
        </section>
      ) : (
        <>
          <section className="card wk-summary">
            <p className="num muted">
              {weekRange(w)} UTC{!w.complete && ` · ${t('wk.inProgress')}`}
            </p>
            <WeekStats w={w} />
            <dl className="wk-more">
              <div>
                <dt>{t('wk.dust')}</dt>
                <dd className="num">{formatAmount(w.dust)}</dd>
              </div>
              <div>
                <dt>{t('wk.refund')}</dt>
                <dd className="num">{formatAmount(w.refundTotal)}</dd>
              </div>
            </dl>
            {w.reasons.length > 0 && (
              <>
                <h2 className="wk-h">{t('wk.reasons')}</h2>
                <ul className="wk-reasons">
                  {w.reasons.map((r) => (
                    <li key={`${r.status}|${r.reason ?? ''}`}>
                      <span>{t(`mk.status.${r.status}`)}</span>
                      <span>{r.reason ? t(`md.res.reasons.${r.reason}`, { defaultValue: r.reason }) : '—'}</span>
                      <b className="num">× {r.count}</b>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>
          <h2 className="wk-h">{t('wk.marketsTitle')}</h2>
          {s.data.markets.length === 0 ? (
            <section className="card">
              <EmptyState title={t('wk.noMarkets')} compact />
            </section>
          ) : desktop ? (
            <section className="card flush res-wrap">
              <ResultTable items={s.data.markets} />
            </section>
          ) : (
            <ul className="res-list">
              {s.data.markets.map((r) => (
                <ResultCard key={r.publicNo} r={r} />
              ))}
            </ul>
          )}
        </>
      )}
      <PublicLinks />
    </div>
  );
}
