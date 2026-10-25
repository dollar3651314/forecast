/**
 * P4 我的下注（需求第 5.2 节）：4 个标签（进行中、待结果、已结算、已退回）带笔数；顶部汇总（总下注、总派彩、净盈亏）。
 * 每条：市场、选项、金额、下注时间、状态、赔率（下注时 / 截止后最终）、派彩 / 抽成 / 退款；点进市场详情。
 * 手机是卡片列表，电脑是表格（C64）。
 */
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { listMyBets } from '../../api/markets';
import type { MyBet, MyBetTab, MyBets as Data } from '../../api/types';
import { AsyncView, EmptyState } from '../../components/AsyncView';
import { Icon } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { CategoryIcon, StatusTag } from '../../components/market/parts';
import { useAsync } from '../../hooks/useAsync';
import { useLang } from '../../hooks/useLang';
import { useIsDesktop } from '../../hooks/useMediaQuery';
import { formatAmount, formatDateTime } from '../../lib/format';
import { multText, pickText } from '../../lib/market';

const TABS: MyBetTab[] = ['active', 'pending', 'settled', 'refunded'];

function Summary({ d }: { d: Data }) {
  const { t } = useTranslation();
  const net = d.totals?.net ?? '0.00';
  const neg = net.startsWith('-');
  return (
    <section className="mb-sum">
      <div>
        <span>{t('myBets.staked')}</span>
        <b className="num">{formatAmount(d.totals?.staked)}</b>
      </div>
      <div>
        <span>{t('myBets.payout')}</span>
        <b className="num">{formatAmount(d.totals?.payout)}</b>
      </div>
      <div>
        <span>{t('myBets.net')}</span>
        <b className={`num ${neg ? 'down' : net === '0.00' ? '' : 'up'}`}>{formatAmount(net, { sign: true })}</b>
      </div>
      <p className="mb-sum-note">{t('myBets.sumNote')} · USDT</p>
    </section>
  );
}

function Odds({ b }: { b: MyBet }) {
  const { t } = useTranslation();
  return b.finalMult ? <span className="num">{t('myBets.final', { m: multText(b.finalMult) })}</span> : <span className="num">{t('myBets.atBet', { m: multText(b.multAtBet) })}</span>;
}

/** 状态：已结算显示押中 / 未押中，已退回显示原因，其余显示市场状态 */
function BetState({ b }: { b: MyBet }) {
  const { t } = useTranslation();
  if (b.tab === 'settled') return <span className={`mb-res ${b.won ? 'won' : 'lost'}`}>{b.won ? t('myBets.won') : t('myBets.lost')}</span>;
  if (b.tab === 'refunded') return <span className="mb-res void">{t(`myBets.refundReasons.${b.refundReason ?? 'VOIDED'}`)}</span>;
  return <StatusTag status={b.marketStatus} />;
}

function Result({ b }: { b: MyBet }) {
  const { t } = useTranslation();
  if (b.tab === 'settled')
    return b.won ? (
      <span className="mb-pay">
        <b className="num up">{t('myBets.payoutV', { v: formatAmount(b.payout) })}</b>
        <small className="num">{t('myBets.feeV', { v: formatAmount(b.fee) })}</small>
      </span>
    ) : (
      <span className="num muted">{t('myBets.payoutV', { v: '0.00' })}</span>
    );
  if (b.tab === 'refunded') return <b className="num">{t('myBets.refundV', { v: formatAmount(b.amount) })}</b>;
  return <span className="muted">—</span>;
}

function BetCard({ b }: { b: MyBet }) {
  const lang = useLang();
  return (
    <li className="mb-card">
      <Link to={`/markets/${b.marketNo}`} className="mb-card-link">
        <div className="mb-r1">
          {b.category && <CategoryIcon category={b.category} size={13} />}
          <span className="mb-market">{pickText(b.market, lang)}</span>
          <Icon name="chevronRight" size={14} />
        </div>
        <div className="mb-r2">
          <span className="mb-opt">{pickText(b.option, lang)}</span>
          <b className="num">{formatAmount(b.amount)} USDT</b>
        </div>
        <div className="mb-r3">
          <span className="num">{formatDateTime(b.receivedAt, { short: true })}</span>
          <Odds b={b} />
          <BetState b={b} />
        </div>
        {(b.tab === 'settled' || b.tab === 'refunded') && (
          <div className="mb-r4">
            <Result b={b} />
          </div>
        )}
      </Link>
    </li>
  );
}

function BetTable({ items }: { items: MyBet[] }) {
  const { t } = useTranslation();
  const lang = useLang();
  return (
    <table className="mb-table">
      <thead>
        <tr>
          <th>{t('myBets.col.market')}</th>
          <th>{t('myBets.col.option')}</th>
          <th className="r">{t('myBets.col.amount')}</th>
          <th>{t('myBets.col.time')}</th>
          <th>{t('myBets.col.odds')}</th>
          <th>{t('myBets.col.status')}</th>
          <th className="r">{t('myBets.col.result')}</th>
        </tr>
      </thead>
      <tbody>
        {items.map((b) => (
          <tr key={b.betNo}>
            <td className="c-market">
              <Link to={`/markets/${b.marketNo}`}>
                {b.category && <CategoryIcon category={b.category} size={13} />}
                <span>{pickText(b.market, lang)}</span>
              </Link>
            </td>
            <td>{pickText(b.option, lang)}</td>
            <td className="r num strong">{formatAmount(b.amount)}</td>
            <td className="num">{formatDateTime(b.receivedAt)}</td>
            <td>
              <Odds b={b} />
            </td>
            <td>
              <BetState b={b} />
            </td>
            <td className="r">
              <Result b={b} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default function MyBets() {
  const { t } = useTranslation();
  const desktop = useIsDesktop();
  const [params, setParams] = useSearchParams();
  const tab = (TABS as string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as MyBetTab) : 'active';
  const s = useAsync(() => listMyBets(tab), `bets|${tab}`);
  return (
    <div className={`page page-bets${desktop ? ' is-d' : ' is-m'}`}>
      <PageHeader title={t('myBets.title')} />
      {s.data && <Summary d={s.data} />}
      <div className="mb-wrap">
        <div className="ptabs mb-tabs" role="tablist">
          {TABS.map((k) => (
            <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setParams(k === 'active' ? {} : { tab: k }, { replace: true })}>
              {t(`myBets.tabs.${k}`)}
              {s.data && <span className="tab-n num">{s.data.summary[k].count}</span>}
            </button>
          ))}
        </div>
        <AsyncView
          state={s}
          rows={4}
          isEmpty={(d) => d.items.length === 0}
          empty={
            <EmptyState
              title={t(`myBets.empty.${tab}`)}
              hint={t('myBets.emptyHint')}
              action={
                <Link to="/markets" className="btn btn-primary btn-sm">
                  {t('myBets.goMarkets')}
                </Link>
              }
            />
          }
        >
          {(d) =>
            desktop ? (
              <BetTable items={d.items} />
            ) : (
              <ul className="mb-list">
                {d.items.map((b) => (
                  <BetCard key={b.betNo} b={b} />
                ))}
              </ul>
            )
          }
        </AsyncView>
      </div>
    </div>
  );
}
