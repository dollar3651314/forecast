/**
 * P2 市场详情（需求第 5.2 节，线框图 W2、W3）。
 * - 手机：详情一列；每个选项一行带"押注"按钮，点了从底部弹出下注抽屉（BetSheet）；
 * - 电脑：左边详情（选项表），右边固定的下注栏（BetPanel），确认按钮贴在右栏底部（C79）。
 * 标签页：规则（全文 + 补充说明 + 费率修改记录）、裁决与公示（结果、依据、裁决人编号、争议截止、提交争议）、彩池怎么算（通俗说明 + 本市场的数字）。
 * 地址栏 ?opt= 表示从列表点进来时选中的选项（手机直接打开抽屉）。赔率更新只换数字（C84）。
 */
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { getMarket } from '../../api/markets';
import type { BetResult, MarketDetail as Detail, OptionPool } from '../../api/types';
import { ErrorState, Loading } from '../../components/AsyncView';
import { Icon } from '../../components/Icon';
import { BetPanel, BetSheet } from '../../components/market/BetForm';
import { DisputeDialog } from '../../components/market/DisputeDialog';
import { CategoryIcon, ProbFill, StatusTag, TimePair } from '../../components/market/parts';
import { useAsync } from '../../hooks/useAsync';
import { useLang, useRemainText } from '../../hooks/useLang';
import { useIsDesktop } from '../../hooks/useMediaQuery';
import { formatAmount } from '../../lib/format';
import { multText, pickText, useClock, utcText } from '../../lib/market';
import { expectedReturn, rateFromPercent } from '../../lib/pool';

type Tab = 'rules' | 'resolution' | 'how';
const isUpDown = (m: Detail) => m.category === 'CRYPTO_15M' || m.category === 'CRYPTO_1H';

function Stat({ k, v, sub }: { k: string; v: ReactNode; sub?: ReactNode }) {
  return (
    <div className="md-stat">
      <span>{k}</span>
      <b>{v}</b>
      {sub && <small>{sub}</small>}
    </div>
  );
}

function Header({ m, now }: { m: Detail; now: number }) {
  const { t } = useTranslation();
  const lang = useLang();
  const left = useRemainText(m.closeAt, now);
  const open = m.status === 'listed' || m.status === 'paused';
  return (
    <header className="md-head">
      <div className="md-meta">
        <CategoryIcon category={m.category} size={15} />
        <span>{pickText(m.league, lang) || t(`mk.cats.${m.category}`)}</span>
        <StatusTag status={m.status} />
      </div>
      <h1 className="md-title">{pickText(m.title, lang)}</h1>
      <p className="md-time">
        <span className="muted">{t('md.closeAt')}</span> <TimePair iso={m.closeAt} />
        {open && left && <b className="md-left">{left}</b>}
      </p>
      <div className="md-stats">
        <Stat k={t('md.totalPool')} v={<span className="num">{formatAmount(m.pool.total)}</span>} sub="USDT" />
        <Stat k={t('md.bettors')} v={<span className="num">{m.pool.bettors}</span>} />
        <Stat k={t('md.fee')} v={<span className="num">{m.pool.feeRate}%</span>} sub={t('md.feeOnProfit')} />
      </div>
    </header>
  );
}

/** 已有结果、作废时在选项上面先说结果 */
function ResultBanner({ m, onDispute }: { m: Detail; onDispute: () => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const now = useClock();
  const r = m.resolution;
  const until = useRemainText(r?.disputeUntil ?? new Date(0).toISOString(), now);
  if (m.status === 'voided' || m.status === 'cancelled') {
    return (
      <div className="md-banner b-void" role="status">
        <Icon name="info" size={18} />
        <div>
          <b>{t(m.status === 'voided' ? 'md.res.voided' : 'md.res.cancelled')}</b>
          {m.voidReason && (
            <span>
              {t('md.res.reason')}：{t(`md.res.reasons.${m.voidReason}`)}
            </span>
          )}
        </div>
      </div>
    );
  }
  if (!r) return null;
  const opt = pickText(m.options.find((o) => o.optionNo === r.winningOptionNo)?.label, lang);
  if (m.status === 'publicizing' || m.status === 'disputed') {
    return (
      <div className="md-banner b-review" role="status">
        <Icon name="scale" size={18} />
        <div>
          <b>{t('dispute.proposed', { opt })}</b>
          <span>
            {m.status === 'disputed' ? t('md.res.disputed') : t('md.res.publicizing')}
            {r.disputeUntil && m.status === 'publicizing' && until && (
              <>
                {' '}
                {t('md.res.disputeUntil')} <b className="num">{until}</b>
              </>
            )}
          </span>
        </div>
        {r.canDispute && (
          <button type="button" className="btn btn-outline btn-sm" onClick={onDispute} data-testid="dispute-open">
            {t('md.res.disputeBtn')}
          </button>
        )}
      </div>
    );
  }
  return (
    <div className="md-banner b-done" role="status">
      <Icon name="trophy" size={18} />
      <div>
        <b>{t('mk.result', { opt })}</b>
        <span>
          {t('md.res.resolver')}：<b className="num">{r.resolver === 'auto' ? t('md.res.auto') : r.resolver}</b> · {utcText(r.resolvedAt)}
        </span>
      </div>
      {r.minePayout && (
        <div className="md-banner-r">
          <small>{t('md.res.myPayout')}</small>
          <b className="num up">+{formatAmount(r.minePayout.payout)}</b>
        </div>
      )}
    </div>
  );
}

/** 电脑：选项表 */
function OptionTable({ m, selected, onSelect }: { m: Detail; selected?: string; onSelect: (no: string) => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const open = m.status === 'listed';
  const win = m.resolution && m.status === 'settled' ? m.resolution.winningOptionNo : m.resolution?.winningOptionNo;
  return (
    <table className="md-table">
      <thead>
        <tr>
          <th>{t('md.colOption')}</th>
          <th className="c-prob">{t('md.colProb')}</th>
          <th className="r">{m.pool.frozen ? t('md.colMultFinal') : t('md.colMult')}</th>
          <th className="r">{t('md.colPool')}</th>
          <th className="r">{t('md.colPeople')}</th>
          {open && <th className="r" aria-label={t('md.bet')} />}
        </tr>
      </thead>
      <tbody>
        {m.options.map((o, i) => {
          const p = m.pool.options.find((x) => x.optionNo === o.optionNo)!;
          const on = open && selected === o.optionNo;
          const won = win === o.optionNo;
          const tone = won ? 'win' : on ? 'sel' : isUpDown(m) ? (i === 0 ? 'up' : 'down') : 'base';
          return (
            <tr key={o.optionNo} className={`${on ? 'on' : ''}${won ? ' won' : ''}`} onClick={open ? () => onSelect(o.optionNo) : undefined}>
              <td className="c-name">
                <span>
                  {won && <Icon name="trophy" size={14} strokeWidth={2.2} />}
                  {pickText(o.label, lang)}
                </span>
              </td>
              <td className="c-prob">
                <span className="tbar">
                  <ProbFill pct={p.pct} tone={tone} />
                </span>
                <b className="num">{p.pct}</b>
              </td>
              <td className="r num strong">{multText(p.mult)}</td>
              <td className="r num">{formatAmount(p.amount)}</td>
              <td className="r num">{p.bettors}</td>
              {open && (
                <td className="r">
                  <button type="button" className={`btn btn-sm ${on ? 'btn-primary' : 'btn-outline'}`} onClick={() => onSelect(o.optionNo)} aria-pressed={on}>
                    {on ? t('md.chosen') : t('md.bet')}
                  </button>
                </td>
              )}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/** 手机：选项一行一个，带"押注"按钮 */
function OptionList({ m, onBet }: { m: Detail; onBet: (no: string) => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const open = m.status === 'listed';
  const win = m.resolution?.winningOptionNo;
  return (
    <ul className="md-opts">
      {m.options.map((o, i) => {
        const p = m.pool.options.find((x) => x.optionNo === o.optionNo)!;
        const won = win === o.optionNo;
        const tone = won ? 'win' : isUpDown(m) ? (i === 0 ? 'up' : 'down') : 'base';
        return (
          <li key={o.optionNo} className={`md-opt${won ? ' won' : ''}`}>
            <div className="md-opt-main">
              <div className="md-opt-top">
                <span className="md-opt-name">
                  {won && <Icon name="trophy" size={14} strokeWidth={2.2} />}
                  {pickText(o.label, lang)}
                </span>
                <b className="num md-opt-pct">{p.pct}</b>
              </div>
              <span className="tbar">
                <ProbFill pct={p.pct} tone={tone} />
              </span>
              <div className="md-opt-sub">
                <span className="num">
                  {m.pool.frozen ? t('md.multFinalShort', { m: multText(p.mult) }) : t('md.multShort', { m: multText(p.mult) })}
                </span>
                <span className="num">
                  {formatAmount(p.amount, { dp: 0 })} USDT · {t('mk.people', { n: p.bettors })}
                </span>
              </div>
            </div>
            {open && (
              <button type="button" className={`btn md-opt-btn ${isUpDown(m) ? (i === 0 ? 'btn-up' : 'btn-down') : 'btn-primary'}`} onClick={() => onBet(o.optionNo)}>
                {t('md.bet')}
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function Mine({ m }: { m: Detail }) {
  const { t } = useTranslation();
  const lang = useLang();
  const mine = m.pool.mine ?? [];
  if (!mine.length) return null;
  return (
    <section className="md-mine" aria-label={t('md.mine')}>
      <h2>{t('md.mine')}</h2>
      <ul>
        {mine.map((x) => (
          <li key={x.optionNo}>
            <span className="md-mine-opt">{pickText(m.options.find((o) => o.optionNo === x.optionNo)?.label, lang)}</span>
            <span className="num muted">{t('md.mineLine', { amount: formatAmount(x.amount), n: x.betCount })}</span>
            {(m.status === 'listed' || m.status === 'paused' || m.status === 'closed') && <b className="num md-mine-win">{t('md.ifWin', { v: formatAmount(x.ifWin) })}</b>}
          </li>
        ))}
      </ul>
    </section>
  );
}

function RulesTab({ m }: { m: Detail }) {
  const { t } = useTranslation();
  const lang = useLang();
  return (
    <div className="md-rules">
      <h3>{t('md.rules.winCond')}</h3>
      <dl className="md-dl">
        {m.options.map((o) => (
          <div key={o.optionNo}>
            <dt>{pickText(o.label, lang)}</dt>
            <dd>{pickText(o.winCondition, lang)}</dd>
          </div>
        ))}
      </dl>
      <h3>{t('md.rules.fullText')}</h3>
      <p className="md-pre">{pickText(m.rules, lang)}</p>
      <h3>{t('md.rules.mode')}</h3>
      <p>{t(`md.rules.${m.resolutionMode}`, { h: Math.round((m.disputeWindowMinutes ?? 0) / 60) })}</p>
      <dl className="md-dl two">
        <div>
          <dt>{t('md.rules.primary')}</dt>
          <dd>{m.sourcePrimary}</dd>
        </div>
        <div>
          <dt>{t('md.rules.backup')}</dt>
          <dd>{m.sourceBackup}</dd>
        </div>
      </dl>
      <h3>{t('md.rules.times')}</h3>
      <dl className="md-dl two">
        {(['open', 'close', 'start', 'end'] as const).map((k) => (
          <div key={k}>
            <dt>{t(`md.rules.${k}`)}</dt>
            <dd className="num">{utcText({ open: m.openAt, close: m.closeAt, start: m.eventStartAt, end: m.eventEndAt }[k], true)}</dd>
          </div>
        ))}
      </dl>
      {m.clarifications.length > 0 && (
        <>
          <h3>{t('md.rules.clarifications')}</h3>
          <ul className="md-log">
            {m.clarifications.map((c, i) => (
              <li key={i}>
                <time className="num">{utcText(c.time, true)}</time>
                <span>{pickText(c.text, lang)}</span>
              </li>
            ))}
          </ul>
        </>
      )}
      {m.feeChanges.length > 0 && (
        <>
          <h3>{t('md.rules.feeChanges')}</h3>
          <ul className="md-log">
            {m.feeChanges.map((c, i) => (
              <li key={i}>
                <time className="num">{utcText(c.time, true)}</time>
                <span className="num">{t('md.rules.feeChange', { old: c.oldRate, new: c.newRate })}</span>
              </li>
            ))}
          </ul>
        </>
      )}
      <dl className="md-dl two md-ids">
        <div>
          <dt>{t('md.rules.userCap')}</dt>
          <dd className="num">{formatAmount(m.userCap)} USDT</dd>
        </div>
        <div>
          <dt>{t('md.rules.marketNo')}</dt>
          <dd className="num">{m.publicNo}</dd>
        </div>
      </dl>
    </div>
  );
}

function ResolutionTab({ m, onDispute }: { m: Detail; onDispute: () => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const r = m.resolution;
  if (m.status === 'voided' || m.status === 'cancelled') {
    return (
      <div className="md-res">
        <p>{t(m.status === 'voided' ? 'md.res.voided' : 'md.res.cancelled')}</p>
        {m.voidReason && (
          <dl className="md-dl">
            <div>
              <dt>{t('md.res.reason')}</dt>
              <dd>{t(`md.res.reasons.${m.voidReason}`)}</dd>
            </div>
          </dl>
        )}
      </div>
    );
  }
  if (!r) return <p className="md-res-empty">{t('md.res.pending')}</p>;
  return (
    <div className="md-res">
      <dl className="md-dl">
        <div>
          <dt>{t('md.res.result')}</dt>
          <dd className="strong">{pickText(m.options.find((o) => o.optionNo === r.winningOptionNo)?.label, lang)}</dd>
        </div>
        <div>
          <dt>{t('md.res.basis')}</dt>
          <dd>{pickText(r.basis, lang)}</dd>
        </div>
        <div>
          <dt>{t('md.res.sources')}</dt>
          <dd>
            <ul className="md-src">
              {r.sources.map((s) => (
                <li key={s.name}>
                  <a href={s.url} target="_blank" rel="noreferrer noopener">
                    {s.name}
                    <Icon name="external" size={12} />
                  </a>
                  {s.value && <span className="num">{s.value}</span>}
                </li>
              ))}
            </ul>
          </dd>
        </div>
        <div>
          <dt>{t('md.res.resolver')}</dt>
          <dd className="num strong">{r.resolver === 'auto' ? t('md.res.auto') : r.resolver}</dd>
        </div>
        <div>
          <dt>{t('md.res.resolvedAt')}</dt>
          <dd className="num">{utcText(r.resolvedAt, true)}</dd>
        </div>
        {r.disputeUntil && (m.status === 'publicizing' || m.status === 'disputed') && (
          <div>
            <dt>{t('md.res.disputeUntil')}</dt>
            <dd className="num">{utcText(r.disputeUntil, true)}</dd>
          </div>
        )}
      </dl>
      {(m.status === 'publicizing' || m.status === 'disputed') && (
        <div className="md-dispute">
          <p>{m.status === 'disputed' ? t('md.res.disputed') : t('md.res.disputeNote')}</p>
          {r.canDispute && (
            <button type="button" className="btn btn-outline" onClick={onDispute}>
              <Icon name="scale" size={16} />
              {t('md.res.disputeBtn')}
            </button>
          )}
        </div>
      )}
      {r.settle && (
        <>
          <h3>{t('md.res.settleTitle')}</h3>
          <dl className="md-dl two">
            <div>
              <dt>{t('md.res.settleFee')}</dt>
              <dd className="num">{r.settle.feeRate}%</dd>
            </div>
            <div>
              <dt>{t('md.res.payoutTotal')}</dt>
              <dd className="num">{formatAmount(r.settle.payoutTotal)}</dd>
            </div>
            <div>
              <dt>{t('md.res.feeTotal')}</dt>
              <dd className="num">{formatAmount(r.settle.feeTotal)}</dd>
            </div>
            <div>
              <dt>{t('md.res.dust')}</dt>
              <dd className="num">{formatAmount(r.settle.dust)}</dd>
            </div>
            {r.minePayout && (
              <>
                <div>
                  <dt>{t('md.res.myPayout')}</dt>
                  <dd className="num up strong">{formatAmount(r.minePayout.payout)}</dd>
                </div>
                <div>
                  <dt>{t('md.res.myFee')}</dt>
                  <dd className="num">{formatAmount(r.minePayout.fee)}</dd>
                </div>
              </>
            )}
          </dl>
        </>
      )}
    </div>
  );
}

function HowTab({ m, optionNo }: { m: Detail; optionNo?: string }) {
  const { t } = useTranslation();
  const lang = useLang();
  const p: OptionPool | undefined = m.pool.options.find((o) => o.optionNo === optionNo) ?? m.pool.options[0];
  const label = pickText(m.options.find((o) => o.optionNo === p?.optionNo)?.label, lang);
  const ret = p && m.pool.total !== '0.00' ? expectedReturn(p.amount, m.pool.total, '100', rateFromPercent(m.pool.feeRate)) : null;
  return (
    <div className="md-how">
      <ol className="how-steps">
        {(['s1', 's2', 's3'] as const).map((k, i) => (
          <li key={k}>
            <span className="how-n">{i + 1}</span>
            <p>{t(`md.how.${k}`, { rate: m.pool.feeRate })}</p>
          </li>
        ))}
      </ol>
      <p className="how-formula num">{t('md.how.formula')}</p>
      <div className="how-ex">
        <small>{t('md.how.example')}</small>
        <p>{ret ? t('md.how.exampleLine', { opt: label, ret: formatAmount(ret) }) : t('md.how.exampleEmpty')}</p>
      </div>
      <p className="muted">{t('md.how.final')}</p>
    </div>
  );
}

/** 电脑右栏：不能下注时说明现在的状态，公示中可以直接提交争议 */
function ClosedPanel({ m, onDispute }: { m: Detail; onDispute: () => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const r = m.resolution;
  const mine = m.pool.mine ?? [];
  return (
    <aside className="bet-panel closed" aria-label={t('bet.title')}>
      <div className="bet-panel-scroll">
        <h2 className="bet-h">{t('bet.title')}</h2>
        <div className="closed-state">
          <StatusTag status={m.status} />
          <p>{m.status === 'paused' ? t('bet.paused') : t('bet.closed')}</p>
          {r && (
            <p className="closed-res">
              {m.status === 'settled' ? t('mk.result', { opt: pickText(m.options.find((o) => o.optionNo === r.winningOptionNo)?.label, lang) }) : t('dispute.proposed', { opt: pickText(m.options.find((o) => o.optionNo === r.winningOptionNo)?.label, lang) })}
            </p>
          )}
        </div>
        {mine.length > 0 && (
          <dl className="bet-kv">
            {mine.map((x) => (
              <div key={x.optionNo}>
                <dt>{pickText(m.options.find((o) => o.optionNo === x.optionNo)?.label, lang)}</dt>
                <dd className="num">{formatAmount(x.amount)} USDT</dd>
              </div>
            ))}
          </dl>
        )}
      </div>
      <div className="bet-panel-foot">
        {r?.canDispute ? (
          <button type="button" className="btn btn-lg btn-block btn-outline" onClick={onDispute}>
            <Icon name="scale" size={16} />
            {t('md.res.disputeBtn')}
          </button>
        ) : (
          <Link to="/bets" className="btn btn-lg btn-block btn-ghost">
            {t('nav.bets')}
          </Link>
        )}
      </div>
    </aside>
  );
}

function Tabs({ m, optionNo, onDispute }: { m: Detail; optionNo?: string; onDispute: () => void }) {
  const { t } = useTranslation();
  const resolved = !!m.resolution || m.status === 'voided' || m.status === 'cancelled';
  const [tab, setTab] = useState<Tab>(resolved ? 'resolution' : 'rules');
  return (
    <section className="md-tabs">
      <div className="ptabs" role="tablist">
        {(['rules', 'resolution', 'how'] as Tab[]).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>
            {t(`md.tabs.${k}`)}
          </button>
        ))}
      </div>
      <div className="md-tab-body" role="tabpanel">
        {tab === 'rules' && <RulesTab m={m} />}
        {tab === 'resolution' && <ResolutionTab m={m} onDispute={onDispute} />}
        {tab === 'how' && <HowTab m={m} optionNo={optionNo} />}
      </div>
    </section>
  );
}

export default function MarketDetail() {
  const { t } = useTranslation();
  const { publicNo = '' } = useParams();
  const navigate = useNavigate();
  const desktop = useIsDesktop();
  const [params, setParams] = useSearchParams();
  const s = useAsync(() => getMarket(publicNo), `market|${publicNo}`);
  const m = s.data;
  const optParam = params.get('opt') ?? undefined;
  const [sheet, setSheet] = useState<string | null>(null);
  const [dispute, setDispute] = useState(false);
  const now = useClock(m && m.status === 'listed' ? Date.parse(m.closeAt) : undefined);

  // 手机：从列表点选项进来时直接打开下注抽屉（只打开一次）
  useEffect(() => {
    if (!desktop && m && m.status === 'listed' && optParam && m.options.some((o) => o.optionNo === optParam)) {
      setSheet(optParam);
      const next = new URLSearchParams(params);
      next.delete('opt');
      setParams(next, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [desktop, m?.publicNo]);

  const selected = useMemo(() => {
    if (!m) return undefined;
    return optParam && m.options.some((o) => o.optionNo === optParam) ? optParam : m.options[0]?.optionNo;
  }, [m, optParam]);
  const select = (no: string) => {
    const next = new URLSearchParams(params);
    next.set('opt', no);
    setParams(next, { replace: true });
  };
  const placed = (r: BetResult) => {
    s.setData((prev) => (prev ? { ...prev, pool: r.pool } : prev));
    s.reload();
  };

  const back = (
    <div className="md-back">
      <button type="button" className="icon-btn back" onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/markets'))} aria-label={t('common.back')}>
        <Icon name="back" size={22} strokeWidth={2.2} />
      </button>
      <Link to="/markets">{t('nav.markets')}</Link>
      {m && (
        <>
          <span>/</span>
          <span>{t(`mk.cats.${m.category}`)}</span>
        </>
      )}
    </div>
  );

  if (s.error && !m)
    return (
      <div className="page page-md">
        {back}
        <section className="card">
          <ErrorState error={s.error} onRetry={s.reload} />
        </section>
      </div>
    );
  if (!m)
    return (
      <div className="page page-md">
        {back}
        <section className="card">
          <Loading rows={6} />
        </section>
      </div>
    );

  const main = (
    <div className="md-main">
      <Header m={m} now={now} />
      <ResultBanner m={m} onDispute={() => setDispute(true)} />
      <section className="md-options" aria-label={t('md.options')}>
        {desktop ? <OptionTable m={m} selected={selected} onSelect={select} /> : <OptionList m={m} onBet={(no) => setSheet(no)} />}
        <p className="md-note">
          <Icon name="info" size={14} />
          {m.pool.frozen ? t('md.frozenNote') : m.pool.total === '0.00' ? t('mk.noBetsYet') : t('md.oddsNote')}
        </p>
      </section>
      <Mine m={m} />
      <Tabs key={m.publicNo} m={m} optionNo={selected} onDispute={() => setDispute(true)} />
    </div>
  );

  return (
    <div className={`page page-md${desktop ? ' is-d' : ' is-m'}`}>
      {back}
      {desktop ? (
        <div className="md-layout">
          {main}
          {m.status === 'listed' ? <BetPanel market={m} optionNo={selected} onOption={select} onPlaced={placed} /> : <ClosedPanel m={m} onDispute={() => setDispute(true)} />}
        </div>
      ) : (
        main
      )}
      {!desktop && sheet && <BetSheet market={m} optionNo={sheet} onClose={() => setSheet(null)} onPlaced={placed} />}
      {dispute && (
        <DisputeDialog
          market={m}
          onClose={() => setDispute(false)}
          onDone={() => {
            setDispute(false);
            s.reload();
          }}
        />
      )}
    </div>
  );
}
