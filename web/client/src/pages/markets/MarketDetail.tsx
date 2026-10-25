/**
 * P2 市场详情（需求第 5.2 节，线框图 W2、W3）。
 * - 手机：详情一列；每个选项一行带"押注"按钮，点了从底部弹出下注抽屉（BetSheet）；
 * - 电脑：左边详情（选项表），右边固定的下注栏（BetPanel），确认按钮贴在右栏底部（C79）。
 * 标签页：规则（全文 + 补充说明 + 费率修改记录）、裁决与公示（结果、依据、裁决人编号、争议截止、提交争议）、彩池怎么算（通俗说明 + 本市场的数字）。
 * 地址栏 ?opt= 表示从列表点进来时选中的选项（手机直接打开抽屉）。赔率更新只换数字（C84）。
 * S11：奖池实时推送（WS /api/ws/markets，AC-17），推来的奖池不含本人的持仓，本人的"赢了约回"按新奖池在前端重算（lib/pool，和后端同一套规则）；
 * 状态变了（截止、公示、结算）重新取详情。结算信息 settle（后端 S11）、"结算中"、还没开放的市场可以订阅开赛提醒（#23，SQ-20）。
 * S13（MF-4，AC-84）：不登录也能看（没有"我的下注"、本人的结果）；点"押注"先去登录 / 注册，登录后回到这个市场并选中原来的选项
 * （地址带 ?opt=，手机直接打开下注抽屉）；电脑右栏是"登录后押注"。
 * S14（MF-5，AC-86、AC-87）：标题旁"分享"（市场卡；已结束的市场是开奖卡）；下注成功后弹出挑战卡（"我押了 X，谁敢押 Y？"，可以关掉，W5）。
 */
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { errorKey } from '../../api/errors';
import { getMarket, subscribePools, subscribeReminder, unsubscribeReminder } from '../../api/markets';
import { createShare, getShare } from '../../api/share';
import type { BetResult, MarketDetail as Detail, OptionPool, ReminderState } from '../../api/types';
import { useAuth, useToast } from '../../app/contexts';
import { GuestGate } from '../../components/GuestGate';
import { useTitle } from '../../hooks/useTitle';
import { ErrorState, Loading } from '../../components/AsyncView';
import { Icon } from '../../components/Icon';
import { BetPanel, BetSheet } from '../../components/market/BetForm';
import { DisputeDialog } from '../../components/market/DisputeDialog';
import { ShareButton } from '../../components/share/ShareButton';
import { ShareDialog } from '../../components/share/ShareDialog';
import { CategoryIcon, ProbFill, StatusTag, TimePair } from '../../components/market/parts';
import { useAsync } from '../../hooks/useAsync';
import { useLang, useRemainText } from '../../hooks/useLang';
import { useIsDesktop } from '../../hooks/useMediaQuery';
import { formatAmount } from '../../lib/format';
import { canDispute, disputeDeadline, mergePool, multText, outcomeOptionNo, pickText, resolverText, useClock, utcText } from '../../lib/market';
import { expectedReturn, rateFromPercent } from '../../lib/pool';

type Tab = 'rules' | 'resolution' | 'how';
const isUpDown = (m: Detail) => m.category === 'CRYPTO_15M' || m.category === 'CRYPTO_1H';
/** 现在能下注：已上架、已到开放时间（S11：还没开放的不显示押注按钮，显示开放时间和开赛提醒） */
const bettable = (m: Detail) => m.status === 'listed' && Date.parse(m.openAt) <= Date.now();
const upcomingOf = (m: Detail) => (m.status === 'listed' || m.status === 'paused') && Date.parse(m.openAt) > Date.now();

function Stat({ k, v, sub }: { k: string; v: ReactNode; sub?: ReactNode }) {
  return (
    <div className="md-stat">
      <span>{k}</span>
      <b>{v}</b>
      {sub && <small>{sub}</small>}
    </div>
  );
}

/**
 * 开赛提醒（#23，SQ-20）：还没开放下注的市场，订阅后在开放时发一封邮件到账户邮箱；"通知偏好"里能看到和退订。
 * 只有用邮箱和密码登录过的账户能订（注册时的自动登录不算），不能订时说明原因。
 */
function ReminderBar({ m, onChange }: { m: Detail; onChange: (r: ReminderState) => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const { session } = useAuth();
  const [busy, setBusy] = useState(false);
  const r = m.reminder;
  // S13：没登录时还没开放的市场提示"登录后可以订阅开放提醒"
  if (!session && upcomingOf(m)) {
    return (
      <div className="md-remind r-none" role="status">
        <Icon name="bell" size={16} />
        <span>{t('remind.guest')}</span>
        <Link to="/login" state={{ from: `/markets/${m.publicNo}` }} className="btn btn-outline btn-sm">
          {t('guest.login')}
        </Link>
      </div>
    );
  }
  if (!r) return null;
  const run = async (fn: (no: string) => Promise<ReminderState>, ok: string) => {
    setBusy(true);
    try {
      onChange(await fn(m.publicNo));
      toast.show({ tone: 'success', title: t(ok) });
    } catch (e) {
      toast.show({ tone: 'error', title: t(errorKey(e)) });
    } finally {
      setBusy(false);
    }
  };
  let body: ReactNode;
  if (r.status === 'sent') body = <span>{t('remind.sent')}</span>;
  else if (r.status === 'active')
    body = (
      <>
        <span>{t('remind.active')}</span>
        <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={() => run(unsubscribeReminder, 'remind.unsubscribed')} data-testid="remind-off">
          {t('remind.unsubscribe')}
        </button>
      </>
    );
  else if (!r.available) body = <span>{t(r.status === 'missed' ? 'remind.missed' : 'remind.closed')}</span>;
  else if (!r.eligible) body = <span>{t('remind.needLogin')}</span>;
  else
    body = (
      <>
        <span>{t('remind.pitch')}</span>
        <button type="button" className="btn btn-outline btn-sm" disabled={busy} onClick={() => run(subscribeReminder, 'remind.subscribed')} data-testid="remind-on">
          <Icon name="bell" size={14} />
          {t('remind.subscribe')}
        </button>
      </>
    );
  return (
    <div className={`md-remind r-${r.status}`} role="status">
      <Icon name="bell" size={16} />
      {body}
    </div>
  );
}

function Header({ m, now, onReminder }: { m: Detail; now: number; onReminder: (r: ReminderState) => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const left = useRemainText(m.closeAt, now);
  const open = m.status === 'listed' || m.status === 'paused';
  const upcoming = open && Date.parse(m.openAt) > now;
  return (
    <header className="md-head">
      <div className="md-meta">
        <CategoryIcon category={m.category} size={15} />
        <span>{pickText(m.league, lang) || t(`mk.cats.${m.category}`)}</span>
        {upcomingOf(m) ? <span className="mk-status s-wait">{t('mk.status.upcoming')}</span> : <StatusTag status={m.status} />}
        <ShareButton className="md-share" title={t('share.title')} cacheKey={`market|${m.publicNo}|${m.status}|${lang}`} load={() => getShare({ kind: 'market', no: m.publicNo }, lang)} />
      </div>
      <h1 className="md-title">{pickText(m.title, lang)}</h1>
      {upcoming && (
        <p className="md-time">
          <span className="muted">{t('md.openAt')}</span> <TimePair iso={m.openAt} />
        </p>
      )}
      <p className="md-time">
        <span className="muted">{t('md.closeAt')}</span> <TimePair iso={m.closeAt} />
        {open && !upcoming && left && <b className="md-left">{left}</b>}
      </p>
      <ReminderBar m={m} onChange={onReminder} />
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
  const deadline = disputeDeadline(r);
  const until = useRemainText(deadline ?? new Date(0).toISOString(), now);
  if (m.status === 'voided' || m.status === 'cancelled') {
    return (
      <div className="md-banner b-void" role="status">
        <Icon name="info" size={18} />
        <div>
          <b>{t(m.status === 'voided' ? 'md.res.voided' : 'md.res.cancelled')}</b>
          {m.voidReason && (
            <span>
              {t('md.res.reason')}：{t(`md.res.reasons.${m.voidReason}`, { defaultValue: m.voidReason })}
            </span>
          )}
        </div>
      </div>
    );
  }
  if (m.settling) {
    return (
      <div className="md-banner b-review" role="status">
        <Icon name="clock" size={18} />
        <div>
          <b>{t('md.res.settling')}</b>
          <span>{t('md.res.settlingNote')}</span>
        </div>
      </div>
    );
  }
  if (!r) return null;
  const opt = pickText(m.options.find((o) => o.optionNo === outcomeOptionNo(m))?.label, lang);
  if (m.status === 'publicizing' || m.status === 'disputed') {
    return (
      <div className="md-banner b-review" role="status">
        <Icon name="scale" size={18} />
        <div>
          <b>{t('dispute.proposed', { opt })}</b>
          <span>
            {m.status === 'disputed' ? t('md.res.disputed') : t('md.res.publicizing')}
            {deadline && m.status === 'publicizing' && until && (
              <>
                {' '}
                {t('md.res.disputeUntil')} <b className="num">{until}</b>
              </>
            )}
          </span>
        </div>
        {canDispute(r) && (
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
          {t('md.res.resolver')}：<b className="num">{resolverText(r, t)}</b>
          {r.resolvedAt && <> · {utcText(r.resolvedAt)}</>}
        </span>
      </div>
      {m.settle?.mine && m.settle.kind === 'payout' && (
        <div className="md-banner-r">
          <small>{t('md.res.myPayout')}</small>
          <b className={`num${m.settle.mine.payout !== '0.00' ? ' up' : ''}`}>{formatAmount(m.settle.mine.payout)}</b>
        </div>
      )}
    </div>
  );
}

/** 电脑：选项表 */
function OptionTable({ m, selected, onSelect }: { m: Detail; selected?: string; onSelect: (no: string) => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const open = bettable(m);
  const win = outcomeOptionNo(m);
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
  const open = bettable(m);
  const win = outcomeOptionNo(m);
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
                  {formatAmount(p.amount, { dp: 0 })} USDT · {t('mk.people', { n: p.bettors, count: p.bettors })}
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
            <span className="num muted">{t('md.mineLine', { amount: formatAmount(x.amount), n: x.betCount, count: x.betCount })}</span>
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

/** 结算合计（公开：ΣPay + ΣF + D = T）和本人的结果（后端 S11 的 settle）；取消、作废时是全额退款 */
function SettleBlock({ m }: { m: Detail }) {
  const { t } = useTranslation();
  const st = m.settle;
  if (!st) return null;
  const mine = st.mine;
  return (
    <>
      <h3>{t(st.kind === 'refund' ? 'md.res.refundTitle' : 'md.res.settleTitle')}</h3>
      <dl className="md-dl two">
        {st.kind === 'payout' ? (
          <>
            <div>
              <dt>{t('md.res.poolTotal')}</dt>
              <dd className="num">{formatAmount(st.poolTotal)}</dd>
            </div>
            <div>
              <dt>{t('md.res.settleFee')}</dt>
              <dd className="num">{st.feeRate}%</dd>
            </div>
            <div>
              <dt>{t('md.res.payoutTotal')}</dt>
              <dd className="num">{formatAmount(st.payoutTotal)}</dd>
            </div>
            <div>
              <dt>{t('md.res.feeTotal')}</dt>
              <dd className="num">{formatAmount(st.feeTotal)}</dd>
            </div>
            <div>
              <dt>{t('md.res.dust')}</dt>
              <dd className="num">{formatAmount(st.dust)}</dd>
            </div>
            <div>
              <dt>{t('md.res.positions')}</dt>
              <dd className="num">{t('md.res.positionsV', { w: st.winnerPositions, l: st.loserPositions })}</dd>
            </div>
          </>
        ) : (
          <div>
            <dt>{t('md.res.refundTotal')}</dt>
            <dd className="num">{formatAmount(st.payoutTotal)}</dd>
          </div>
        )}
        {mine && st.kind === 'payout' && (
          <>
            <div>
              <dt>{t('md.res.myStake')}</dt>
              <dd className="num">{formatAmount(mine.stake)}</dd>
            </div>
            <div>
              <dt>{t('md.res.myPayout')}</dt>
              <dd className={`num strong${mine.payout !== '0.00' ? ' up' : ''}`}>{formatAmount(mine.payout)}</dd>
            </div>
            <div>
              <dt>{t('md.res.myFee')}</dt>
              <dd className="num">{formatAmount(mine.fee)}</dd>
            </div>
          </>
        )}
        {mine && mine.refund !== '0.00' && (
          <div>
            <dt>{t('md.res.myRefund')}</dt>
            <dd className="num strong">{formatAmount(mine.refund)}</dd>
          </div>
        )}
        {mine && mine.compensation !== '0.00' && (
          <div>
            <dt>{t('md.res.myCompensation')}</dt>
            <dd className="num strong up">{formatAmount(mine.compensation)}</dd>
          </div>
        )}
      </dl>
      {st.kind === 'payout' && <p className="muted small">{t('md.res.conservation')}</p>}
    </>
  );
}

/** 依据链接只显示域名 */
function hostOf(url: string): string {
  try {
    return new URL(url).host || url;
  } catch {
    return url;
  }
}

function ResolutionTab({ m, onDispute }: { m: Detail; onDispute: () => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const r = m.resolution;
  const voidedOrCancelled = m.status === 'voided' || m.status === 'cancelled';
  if (voidedOrCancelled && r?.mode !== 'platform') {
    return (
      <div className="md-res">
        <p>{t(m.status === 'voided' ? 'md.res.voided' : 'md.res.cancelled')}</p>
        {m.voidReason && (
          <dl className="md-dl">
            <div>
              <dt>{t('md.res.reason')}</dt>
              <dd>{t(`md.res.reasons.${m.voidReason}`, { defaultValue: m.voidReason })}</dd>
            </div>
          </dl>
        )}
        <SettleBlock m={m} />
      </div>
    );
  }
  if (!r) return <p className="md-res-empty">{t('md.res.pending')}</p>;
  const win = outcomeOptionNo(m);
  const reviewing = m.status === 'publicizing' || m.status === 'disputed';
  const deadline = disputeDeadline(r);
  return (
    <div className="md-res">
      <dl className="md-dl">
        <div>
          <dt>{t('md.res.result')}</dt>
          <dd className="strong">{r.mode === 'platform' && r.voided ? t('md.res.voided') : pickText(m.options.find((o) => o.optionNo === win)?.label, lang)}</dd>
        </div>
        <div>
          <dt>{t('md.res.basis')}</dt>
          <dd>
            {r.mode === 'auto'
              ? t('md.res.autoBasis', { start: r.startPrice ?? '—', end: r.endPrice ?? '—', source: r.source ?? '' })
              : [r.basis ? t(`md.res.bases.${r.basis}`, { defaultValue: r.basis }) : '', r.explanation ?? ''].filter(Boolean).join('：')}
          </dd>
        </div>
        <div>
          <dt>{t('md.res.sources')}</dt>
          <dd>
            <ul className="md-src">
              {r.mode === 'auto'
                ? r.sources.map((s) => (
                    <li key={s.source}>
                      <span>{s.label}</span>
                      <span className="num">
                        {s.startPrice} → {s.endPrice}
                      </span>
                    </li>
                  ))
                : r.evidenceUrls.map((u) => (
                    <li key={u}>
                      <a href={u} target="_blank" rel="noreferrer noopener">
                        {hostOf(u)}
                        <Icon name="external" size={12} />
                      </a>
                    </li>
                  ))}
            </ul>
          </dd>
        </div>
        <div>
          <dt>{t('md.res.resolver')}</dt>
          <dd className="num strong">{resolverText(r, t)}</dd>
        </div>
        {r.resolvedAt && (
          <div>
            <dt>{t('md.res.resolvedAt')}</dt>
            <dd className="num">{utcText(r.resolvedAt, true)}</dd>
          </div>
        )}
        {deadline && reviewing && (
          <div>
            <dt>{t('md.res.disputeUntil')}</dt>
            <dd className="num">{utcText(deadline, true)}</dd>
          </div>
        )}
        {r.mode === 'platform' && r.dispute && (
          <div>
            <dt>{t('md.res.disputeResult')}</dt>
            <dd>
              {t(`md.res.decision.${r.dispute.decision}`)}
              {r.dispute.conclusion ? `：${r.dispute.conclusion}` : ''}
            </dd>
          </div>
        )}
        {r.mode === 'platform' && r.myDispute && (
          <div>
            <dt>{t('md.res.myDispute')}</dt>
            <dd>{t(`md.res.disputeStatus.${r.myDispute.status}`, { defaultValue: r.myDispute.status })}</dd>
          </div>
        )}
      </dl>
      {reviewing && (
        <div className="md-dispute">
          <p>{m.status === 'disputed' ? t('md.res.disputed') : t('md.res.disputeNote')}</p>
          {canDispute(r) && (
            <button type="button" className="btn btn-outline" onClick={onDispute}>
              <Icon name="scale" size={16} />
              {t('md.res.disputeBtn')}
            </button>
          )}
        </div>
      )}
      <SettleBlock m={m} />
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
          {upcomingOf(m) ? <span className="mk-status s-wait">{t('mk.status.upcoming')}</span> : <StatusTag status={m.status} />}
          <p>{upcomingOf(m) ? t('bet.notOpen', { t: utcText(m.openAt) }) : m.status === 'paused' ? t('bet.paused') : t('bet.closed')}</p>
          {r && outcomeOptionNo(m) && (
            <p className="closed-res">
              {t(m.status === 'settled' ? 'mk.result' : 'dispute.proposed', { opt: pickText(m.options.find((o) => o.optionNo === outcomeOptionNo(m))?.label, lang) })}
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
        {canDispute(r) ? (
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

/** 电脑右栏（没登录，S13）：选中的选项和概率，"登录后押注"（登录后回到这里、选中同一个选项） */
function GuestPanel({ m, optionNo }: { m: Detail; optionNo?: string }) {
  const { t } = useTranslation();
  const lang = useLang();
  const from = `/markets/${m.publicNo}${optionNo ? `?opt=${optionNo}` : ''}`;
  const o = m.options.find((x) => x.optionNo === optionNo);
  const p = m.pool.options.find((x) => x.optionNo === optionNo);
  return (
    <aside className="bet-panel guest" aria-label={t('bet.title')}>
      <div className="bet-panel-scroll">
        <h2 className="bet-h">{t('bet.title')}</h2>
        {o && p && (
          <dl className="bet-kv">
            <div>
              <dt>{pickText(o.label, lang)}</dt>
              <dd className="num">
                {p.pct} · {multText(p.mult)}
              </dd>
            </div>
          </dl>
        )}
        <p className="muted">{t('guest.betHint')}</p>
      </div>
      <div className="bet-panel-foot">
        <Link to="/login" state={{ from }} className="btn btn-lg btn-block btn-primary" data-testid="guest-bet">
          {t('guest.loginToBet')}
        </Link>
        <Link to="/register" state={{ from }} className="btn btn-block btn-ghost">
          {t('guest.registerToBet')}
        </Link>
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
  const { session } = useAuth();
  const guest = !session;
  const [params, setParams] = useSearchParams();
  const s = useAsync(() => getMarket(publicNo), `market|${publicNo}|${guest ? 'g' : 'u'}`);
  const m = s.data;
  const lang = useLang();
  useTitle(m ? pickText(m.title, lang) : undefined);
  const status = m?.status;
  const { setData, reload } = s;
  // 奖池推送（AC-17）：没结束的市场订阅；推来的奖池不含本人的持仓和限额，沿用详情里的，本人的"赢了约回"按新奖池重算；状态变了重新取详情
  const watching = !!status && !['settled', 'cancelled', 'voided'].includes(status);
  useEffect(() => {
    if (!watching) return undefined;
    return subscribePools([publicNo], (e) => {
      setData((d) => (d ? { ...d, pool: mergePool(d.pool, e.pool) } : d));
      if (e.status !== status) reload();
    });
  }, [publicNo, watching, status, setData, reload]);
  const optParam = params.get('opt') ?? undefined;
  const [sheet, setSheet] = useState<string | null>(null);
  const [dispute, setDispute] = useState(false);
  const now = useClock(m && m.status === 'listed' ? Date.parse(m.closeAt) : undefined);

  // 手机：从列表点选项进来时直接打开下注抽屉（只打开一次）；没登录时不打开（点"押注"才去登录）
  useEffect(() => {
    if (!guest && !desktop && m && m.status === 'listed' && optParam && m.options.some((o) => o.optionNo === optParam)) {
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
  // S14：下注成功后弹出挑战卡（可以关掉）
  const [challenge, setChallenge] = useState<{ optionNo: string; n: number } | null>(null);
  const placed = (r: BetResult) => {
    s.setData((prev) => (prev ? { ...prev, pool: r.pool } : prev));
    s.reload();
    setChallenge((c) => ({ optionNo: r.optionNo, n: (c?.n ?? 0) + 1 }));
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
        <GuestGate error={s.error} />
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
      <Header m={m} now={now} onReminder={(r) => s.setData((d) => (d ? { ...d, reminder: r } : d))} />
      <ResultBanner m={m} onDispute={() => setDispute(true)} />
      <section className="md-options" aria-label={t('md.options')}>
        {desktop ? (
          <OptionTable m={m} selected={selected} onSelect={select} />
        ) : (
          <OptionList m={m} onBet={(no) => (guest ? navigate('/login', { state: { from: `/markets/${m.publicNo}?opt=${no}` } }) : setSheet(no))} />
        )}
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
          {bettable(m) ? (
            guest ? (
              <GuestPanel m={m} optionNo={selected} />
            ) : (
              <BetPanel market={m} optionNo={selected} onOption={select} onPlaced={placed} />
            )
          ) : (
            <ClosedPanel m={m} onDispute={() => setDispute(true)} />
          )}
        </div>
      ) : (
        main
      )}
      {!desktop && sheet && !guest && <BetSheet market={m} optionNo={sheet} onClose={() => setSheet(null)} onPlaced={placed} />}
      {challenge && (
        <ShareDialog
          title={t('share.challengeTitle')}
          cacheKey={`challenge|${m.publicNo}|${challenge.optionNo}|${challenge.n}|${lang}`}
          load={() => createShare({ kind: 'challenge', marketNo: m.publicNo, optionNo: challenge.optionNo }, lang)}
          onClose={() => setChallenge(null)}
        />
      )}
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
