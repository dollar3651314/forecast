/**
 * 开奖记录的一条（S13，P12，MF-6）：结果、依据、奖池、派彩合计、抽成、尾差、裁决人编号、作废 / 取消原因、结束时间。
 * 手机是一张卡片，电脑是表格的一行（C64）；开奖记录页和周报页都用。数字全部来自结算单（后端 MarketPublicService），不含任何客户的个人数据。
 */
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import type { ResultItem, WeekReport } from '../../api/types';
import { Icon } from '../../components/Icon';
import { CategoryIcon, StatusTag } from '../../components/market/parts';
import { useLang } from '../../hooks/useLang';
import { formatAmount } from '../../lib/format';
import { pickText, resolverText, utcText } from '../../lib/market';
import { resultLine } from './format';

function hostOf(url: string): string {
  try {
    return new URL(url).host || url;
  } catch {
    return url;
  }
}

/** 依据：自动裁决写开始价 → 结束价（来源）；平台裁决给依据链接（只显示域名） */
export function Basis({ r }: { r: ResultItem }) {
  const { t } = useTranslation();
  const res = r.resolution;
  if (!res) return <span className="muted">—</span>;
  if (res.mode === 'auto') {
    if (res.startPrice || res.endPrice) {
      return (
        <span className="num">
          {res.startPrice ?? '—'} → {res.endPrice ?? '—'}
          {res.source ? <small className="muted"> · {res.source}</small> : null}
        </span>
      );
    }
    return <span>{res.source ?? t('md.res.auto')}</span>;
  }
  if (!res.evidenceUrls.length) return <span>{res.basis ? t(`md.res.bases.${res.basis}`, { defaultValue: res.basis }) : '—'}</span>;
  return (
    <span className="res-links">
      {res.evidenceUrls.map((u) => (
        <a key={u} href={u} target="_blank" rel="noreferrer noopener">
          {hostOf(u)}
          <Icon name="external" size={12} />
        </a>
      ))}
    </span>
  );
}

const money = (v: string | undefined) => (v === undefined ? '—' : formatAmount(v));

/** 手机：一张卡片 */
export function ResultCard({ r }: { r: ResultItem }) {
  const { t } = useTranslation();
  const lang = useLang();
  const st = r.settle;
  return (
    <li className="res-card" data-testid="result-item">
      <div className="res-meta">
        <CategoryIcon category={r.category} size={14} />
        <span>{t(`mk.cats.${r.category}`)}</span>
        <StatusTag status={r.status} />
        <time className="num muted">{utcText(r.endedAt)}</time>
      </div>
      <h3 className="res-title">
        <Link to={`/markets/${r.publicNo}`}>{pickText(r.title, lang)}</Link>
      </h3>
      <p className="res-result">
        {r.status === 'settled' && <Icon name="trophy" size={14} strokeWidth={2.2} />}
        <b>{resultLine(r, t, lang)}</b>
      </p>
      <dl className="res-nums">
        <div>
          <dt>{t('md.res.poolTotal')}</dt>
          <dd className="num">{money(st?.poolTotal)}</dd>
        </div>
        {st?.kind === 'payout' ? (
          <>
            <div>
              <dt>{t('md.res.payoutTotal')}</dt>
              <dd className="num">{money(st.payoutTotal)}</dd>
            </div>
            <div>
              <dt>{t('res.fee')}</dt>
              <dd className="num">{money(st.feeTotal)}</dd>
            </div>
            <div>
              <dt>{t('res.dust')}</dt>
              <dd className="num">{money(st.dust)}</dd>
            </div>
          </>
        ) : (
          <div>
            <dt>{t('res.refunded')}</dt>
            <dd className="num">{money(st?.payoutTotal)}</dd>
          </div>
        )}
        <div>
          <dt>{t('md.bettors')}</dt>
          <dd className="num">{r.bettors}</dd>
        </div>
      </dl>
      {r.resolution && (
        <p className="res-basis">
          <span className="muted">{t('md.res.basis')}：</span>
          <Basis r={r} />
          <span className="muted"> · {t('md.res.resolver')}：</span>
          <b className="num">{resolverText(r.resolution, t)}</b>
        </p>
      )}
    </li>
  );
}

/** 电脑：表格 */
export function ResultTable({ items }: { items: ResultItem[] }) {
  const { t } = useTranslation();
  const lang = useLang();
  return (
    <table className="res-table">
      <thead>
        <tr>
          <th>{t('res.colMarket')}</th>
          <th>{t('md.res.result')}</th>
          <th>{t('md.res.basis')}</th>
          <th className="r">{t('md.res.poolTotal')}</th>
          <th className="r">{t('md.res.payoutTotal')}</th>
          <th className="r">{t('res.fee')}</th>
          <th className="r">{t('res.dust')}</th>
          <th className="r">{t('md.bettors')}</th>
          <th>{t('md.res.resolver')}</th>
          <th>{t('res.endedAt')}</th>
        </tr>
      </thead>
      <tbody>
        {items.map((r) => {
          const st = r.settle;
          return (
            <tr key={r.publicNo} data-testid="result-item">
              <td className="c-market">
                <Link to={`/markets/${r.publicNo}`}>{pickText(r.title, lang)}</Link>
                <small className="muted">{t(`mk.cats.${r.category}`)}</small>
              </td>
              <td>
                <StatusTag status={r.status} /> <span className={r.status === 'settled' ? 'strong' : ''}>{r.status === 'settled' ? resultLine(r, t, lang) : ''}</span>
                {r.status !== 'settled' && <small className="muted res-why">{resultLine(r, t, lang)}</small>}
              </td>
              <td className="c-basis">
                <Basis r={r} />
              </td>
              <td className="r num">{money(st?.poolTotal)}</td>
              <td className="r num">{st?.kind === 'payout' ? money(st.payoutTotal) : t('res.refundedShort')}</td>
              <td className="r num">{st?.kind === 'payout' ? money(st.feeTotal) : '0.00'}</td>
              <td className="r num">{st?.kind === 'payout' ? money(st.dust) : '0.00'}</td>
              <td className="r num">{r.bettors}</td>
              <td className="num">{r.resolution ? resolverText(r.resolution, t) : '—'}</td>
              <td className="num">{utcText(r.endedAt)}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/** 一周的六个数：结束场数、有效场数、作废或取消、奖池、派彩、抽成合计 */
export function WeekStats({ w }: { w: WeekReport }) {
  const { t } = useTranslation();
  return (
    <dl className="wk-stats">
      <div>
        <dt>{t('wk.markets')}</dt>
        <dd className="num">{w.markets}</dd>
      </div>
      <div>
        <dt>{t('wk.settled')}</dt>
        <dd className="num">{w.settled}</dd>
      </div>
      <div>
        <dt>{t('wk.voided')}</dt>
        <dd className="num">{w.voided + w.cancelled}</dd>
      </div>
      <div>
        <dt>{t('wk.pool')}</dt>
        <dd className="num">{formatAmount(w.poolTotal)}</dd>
      </div>
      <div>
        <dt>{t('wk.payout')}</dt>
        <dd className="num">{formatAmount(w.payoutTotal)}</dd>
      </div>
      <div>
        <dt>{t('wk.fee')}</dt>
        <dd className="num">{formatAmount(w.feeTotal)}</dd>
      </div>
    </dl>
  );
}
