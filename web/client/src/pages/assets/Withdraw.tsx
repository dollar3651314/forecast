/**
 * 提现（效果图 M4）：KYC 检查、白名单地址、金额限制（一次显示全部错误）、
 * 2 小时冷静期可撤销、≥3,000 审核后延迟 6 小时、提现记录的全部状态。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { errorKey, isApiError } from '../../api/errors';
import { cancelWithdraw, getWithdrawInfo, listWhitelist, listWithdrawals, submitWithdraw } from '../../api/wallet';
import type { WhitelistAddress, Withdrawal, WithdrawInfo } from '../../api/types';
import { useToast } from '../../app/contexts';
import { EmptyState, ErrorState, LoadMore, Loading } from '../../components/AsyncView';
import { FieldErrors, Select, TextField } from '../../components/Form';
import { Icon } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { ConfirmDialog } from '../../components/Modal';
import { useAsync } from '../../hooks/useAsync';
import { useNow } from '../../hooks/useCountdown';
import { usePaged } from '../../hooks/usePaged';
import { useServerEvent } from '../../hooks/useServerEvent';
import { cmp, dec, div, isDecimalString, mul, sub, toFixed, ZERO } from '../../lib/decimal';
import { formatAmount, formatCountdown, formatDateTime, shortAddress } from '../../lib/format';
import { dailyRemaining, hasErrors, validateWithdraw, type FieldErrors as Errs } from '../../lib/validators';

function KycBanner({ info }: { info: WithdrawInfo }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  if (info.kycStatus === 'approved') {
    return (
      <div className="kyc-ok">
        <Icon name="checkCircle" size={18} strokeWidth={2.2} />
        <b>{t('withdraw.kycApproved')}</b>
        <span>{t('withdraw.canWithdraw')}</span>
      </div>
    );
  }
  return (
    <section className="card kyc-need" role="alert">
      <Icon name="shield" size={28} />
      <div>
        <b>{t('withdraw.kycNeedTitle')}</b>
        <p>{t(`withdraw.kycNeed.${info.kycStatus}`)}</p>
      </div>
      <button type="button" className="btn btn-primary" onClick={() => navigate('/me/kyc')}>
        {info.kycStatus === 'pending' ? t('withdraw.viewKyc') : t('withdraw.goKyc')}
      </button>
    </section>
  );
}

function WithdrawForm({ info, whitelist, onDone }: { info: WithdrawInfo; whitelist: WhitelistAddress[]; onDone: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const now = useNow(30_000);
  const [wid, setWid] = useState(() => whitelist.find((w) => new Date(w.activeAt).getTime() <= Date.now())?.id ?? '');
  const [amount, setAmount] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [serverErr, setServerErr] = useState<string | null>(null);
  // 后端返回的字段错误（VALIDATION_FAILED 的 fields），显示在对应输入框下面；改了这个字段就清掉
  const [serverFields, setServerFields] = useState<Errs<'whitelistId' | 'amount'>>({});
  const errs: Errs<'whitelistId' | 'amount' | 'kyc'> = validateWithdraw({ whitelistId: wid, amount }, info, whitelist, new Date(now));
  const local: Errs<'whitelistId' | 'amount' | 'kyc'> = submitted ? errs : {};
  const merged = (k: 'whitelistId' | 'amount') => [...(local[k] ?? []), ...(serverFields[k] ?? [])].filter((x, i, a) => a.indexOf(x) === i);
  const shown: Errs<'whitelistId' | 'amount'> = { whitelistId: merged('whitelistId'), amount: merged('amount') };
  const changeWid = (v: string) => {
    setWid(v);
    setServerFields((s) => ({ ...s, whitelistId: [] }));
  };
  const changeAmount = (v: string) => {
    setAmount(v);
    setServerFields((s) => ({ ...s, amount: [] }));
  };
  const addr = whitelist.find((w) => w.id === wid);
  const fee = addr ? info.fees[addr.network] : null;
  const amt = isDecimalString(amount) ? dec(amount) : null;
  const receive = amt && fee ? sub(amt, dec(fee)) : null;
  const used = dec(info.usedToday);
  const pct = cmp(dec(info.dailyLimit), ZERO) > 0 ? Number(toFixed(mul(div(used, dec(info.dailyLimit)), dec(100)), 1)) : 0;
  const large = amt !== null && cmp(amt, dec(info.largeAmount)) >= 0;

  const fillAll = () => {
    let m = dec(info.available);
    for (const cap of [dec(info.maxPerTx), sub(dailyRemaining(info), dec('0.01'))]) if (cmp(cap, m) < 0) m = cap;
    if (cmp(m, ZERO) < 0) m = ZERO;
    // 向下取整到 0.01
    changeAmount(toFixed(sub(m, dec('0.004999999')), 2));
  };

  const submit = () => {
    setSubmitted(true);
    setServerErr(null);
    setServerFields({});
    if (hasErrors(errs)) return;
    setConfirm(true);
  };

  const doSubmit = async () => {
    setBusy(true);
    try {
      await submitWithdraw({ whitelistId: wid, amount: amount.trim() });
      setConfirm(false);
      setAmount('');
      setSubmitted(false);
      toast.show({ tone: 'success', title: t('withdraw.submitted'), body: t('withdraw.submittedBody', { h: info.coolingHours }) });
      onDone();
    } catch (e) {
      setConfirm(false);
      const f = isApiError(e) ? e.fieldErrors : undefined;
      if (f?.whitelistId?.length || f?.amount?.length) {
        setServerFields({ whitelistId: f.whitelistId, amount: f.amount });
        setServerErr(t('validation.fixAll'));
      } else setServerErr(t(errorKey(e))); // 表单上没有对应输入框的字段错误：直接显示第一条原因
    } finally {
      setBusy(false);
    }
  };

  const options = whitelist.map((w) => {
    const pending = new Date(w.activeAt).getTime() > now;
    return {
      value: w.id,
      label: `${w.network} · ${w.label} · ${shortAddress(w.address)}`,
      hint: pending ? t('whitelist.activeIn', { time: formatCountdown(new Date(w.activeAt).getTime() - now) }) : undefined,
    };
  });

  return (
    <>
      <section className="card">
        <div className="wd-wl-link">
          <Link to="/assets/whitelist" className="small">
            {t('withdraw.manageWhitelist', { n: whitelist.length, max: info.maxWhitelist })}
          </Link>
        </div>
        {whitelist.length === 0 ? (
          <div className="notice info">
            {t('withdraw.noWhitelist')} <Link to="/assets/whitelist">{t('whitelist.add')}</Link>
          </div>
        ) : (
          <Select label={t('withdraw.to')} value={wid} onChange={changeWid} options={options} errors={shown.whitelistId} />
        )}
        {whitelist.length === 0 && <FieldErrors id="wl-err" errors={shown.whitelistId} />}
        <p className="muted small wd-rule">{t('withdraw.newAddressRule')}</p>

        <TextField
          label={t('withdraw.amount')}
          className="amount-field"
          value={amount}
          onChange={(v) => changeAmount(v.replace(/[^\d.]/g, ''))}
          inputMode="decimal"
          mono
          placeholder={t('withdraw.amountPlaceholder', { min: info.minAmount })}
          errors={shown.amount}
          suffix="USDT"
          addon={
            <button type="button" className="btn btn-ghost addon-btn" onClick={fillAll}>
              {t('withdraw.all')}
            </button>
          }
        />
        <dl className="kv-rows">
          <div>
            <dt>{t('withdraw.available')}</dt>
            <dd className="num">{formatAmount(info.available)}</dd>
          </div>
          <div>
            <dt>{t('withdraw.networkFee')}</dt>
            <dd className="num">{fee ? formatAmount(fee) : '—'}</dd>
          </div>
          <div>
            <dt>{t('withdraw.receive')}</dt>
            <dd className="num strong">{receive && cmp(receive, ZERO) > 0 ? `${formatAmount(receive)} USDT` : '—'}</dd>
          </div>
          <div>
            <dt>{t('withdraw.limits', { min: formatAmount(info.minAmount, { dp: 0 }), max: formatAmount(info.maxPerTx, { dp: 0 }) })}</dt>
            <dd>
              <span className="num">{formatAmount(info.usedToday)}</span> / <span className="num">{formatAmount(info.dailyLimit, { dp: 0 })}</span>
            </dd>
          </div>
        </dl>
        <div className="bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label={t('withdraw.dailyUsed')}>
          <i style={{ width: `${Math.min(100, pct)}%` }} />
        </div>
        {large && <div className="notice warn">{t('withdraw.largeNotice', { amount: formatAmount(info.largeAmount, { dp: 0 }), h: info.largeDelayHours })}</div>}
      </section>

      <section className="card">
        <h2 className="card-title">{t('withdraw.process')}</h2>
        <ol className="steps">
          <li>
            <b>1</b>
            {t('withdraw.step1')}
          </li>
          <li>
            <b>2</b>
            {t('withdraw.step2', { h: info.coolingHours })}
          </li>
          <li>
            <b>3</b>
            {t('withdraw.step3')}
          </li>
          <li>
            <b>4</b>
            {t('withdraw.step4')}
          </li>
        </ol>
        <p className="pnote">{t('withdraw.processNote', { amount: formatAmount(info.largeAmount, { dp: 0 }), h: info.largeDelayHours })}</p>
      </section>

      {submitted && hasErrors(errs) && (
        <div className="form-summary mx" role="alert">
          {t('validation.fixAll')}
        </div>
      )}
      {serverErr && (
        <div className="notice error mx" role="alert">
          {serverErr}
        </div>
      )}
      <button type="button" className="btn btn-primary btn-lg btn-block submit-wd" onClick={submit}>
        {t('withdraw.submit')}
      </button>

      <ConfirmDialog open={confirm} title={t('withdraw.confirmTitle')} confirmText={t('withdraw.confirm')} busy={busy} onConfirm={doSubmit} onClose={() => setConfirm(false)}>
        <dl className="kv-list">
          <div>
            <dt>{t('withdraw.address')}</dt>
            <dd>
              {addr?.network} · {addr?.label}
              <br />
              <span className="num small break">{addr?.address}</span>
            </dd>
          </div>
          <div>
            <dt>{t('withdraw.amount')}</dt>
            <dd className="num">{formatAmount(amount)} USDT</dd>
          </div>
          <div>
            <dt>{t('withdraw.networkFee')}</dt>
            <dd className="num">{fee ? formatAmount(fee) : '—'} USDT</dd>
          </div>
          <div>
            <dt>{t('withdraw.receive')}</dt>
            <dd className="num">{receive ? formatAmount(receive) : '—'} USDT</dd>
          </div>
        </dl>
        <p className="muted small">{t('withdraw.confirmNote', { h: info.coolingHours })}</p>
      </ConfirmDialog>
    </>
  );
}

const STATUS_TONE: Record<Withdrawal['status'], string> = {
  cooling: 'cool',
  reviewing: 'wait',
  approved: 'ok',
  delayed: 'ok',
  broadcasting: 'ok',
  completed: 'done',
  failedRefunded: 'bad',
  rejected: 'bad',
  cancelled: 'mute',
};

function Records() {
  const { t } = useTranslation();
  const now = useNow();
  const s = usePaged(listWithdrawals, 'withdrawals', 10);
  const [cancel, setCancel] = useState<Withdrawal | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useServerEvent((e) => (e.type === 'account' || e.type === 'withdraw') && s.reload());

  const doCancel = async () => {
    if (!cancel) return;
    setBusy(true);
    setErr(null);
    try {
      await cancelWithdraw(cancel.id);
      setCancel(null);
    } catch (e) {
      setErr(t(errorKey(e)));
    } finally {
      setBusy(false);
    }
  };

  const detail = (w: Withdrawal) => {
    const left = new Date(w.coolingEndsAt).getTime() - now;
    switch (w.status) {
      case 'cooling':
        return left > 0 ? t('withdraw.rec.coolingLeft', { time: formatCountdown(left) }) : t('withdraw.rec.coolingDone');
      case 'reviewing':
        return t('withdraw.rec.reviewing');
      case 'approved':
        return t('withdraw.rec.approved');
      case 'delayed':
        return t('withdraw.rec.delayed', { time: formatDateTime(w.delayEndsAt, { short: true }) });
      case 'broadcasting':
      case 'completed':
        return w.txHash ? t('withdraw.rec.tx', { hash: shortAddress(w.txHash) }) : '';
      case 'failedRefunded':
        return t('withdraw.rec.refunded');
      case 'rejected':
        return t('withdraw.rec.rejected', { reason: t(`withdraw.rejectReason.${w.rejectReason ?? 'other'}`) });
      case 'cancelled':
        return t('withdraw.rec.cancelled');
    }
  };

  return (
    <section className="card">
      <div className="card-head">
        <h2>{t('withdraw.records')}</h2>
        {s.data && <span className="muted small">{t('withdraw.recordsTotal', { total: s.total })}</span>}
      </div>
      {s.error && !s.data ? (
        <ErrorState error={s.error} onRetry={s.reload} compact />
      ) : !s.data ? (
        <Loading rows={3} />
      ) : s.items.length === 0 ? (
        <EmptyState compact title={t('withdraw.recordsEmpty')} />
      ) : (
        <>
          <ul className="wd-recs">
            {s.items.map((w) => {
              const canCancel = w.status === 'cooling' && new Date(w.coolingEndsAt).getTime() > now;
              return (
                <li key={w.id}>
                  <div className="a">
                    <b className="num">{formatAmount(w.amount)}</b>
                    <span className={`st st-${STATUS_TONE[w.status]}`}>{t(`withdraw.status.${w.status}`)}</span>
                    {canCancel && (
                      <button type="button" className="btn btn-danger-outline btn-xs op" onClick={() => setCancel(w)}>
                        {t('withdraw.cancel')}
                      </button>
                    )}
                  </div>
                  <div className="d">
                    <span className="num">
                      {formatDateTime(w.createdAt, { short: true })} · {w.network}
                    </span>
                    <span className="num">{detail(w)}</span>
                  </div>
                </li>
              );
            })}
          </ul>
          <LoadMore shown={s.items.length} total={s.total} hasMore={s.hasMore} loading={s.loadingMore} error={s.moreError} onMore={() => void s.loadMore()} />
        </>
      )}
      <ConfirmDialog open={!!cancel} title={t('withdraw.cancelTitle')} confirmText={t('withdraw.cancelConfirm')} tone="danger" busy={busy} onConfirm={doCancel} onClose={() => setCancel(null)}>
        <p>{t('withdraw.cancelBody', { amount: cancel ? formatAmount(cancel.amount) : '' })}</p>
        {err && (
          <div className="notice error" role="alert">
            {err}
          </div>
        )}
      </ConfirmDialog>
    </section>
  );
}

export default function Withdraw() {
  const { t } = useTranslation();
  const info = useAsync(getWithdrawInfo, 'wd-info');
  const wl = useAsync(listWhitelist, 'whitelist');
  useServerEvent((e) => e.type === 'account' && info.reload());
  const err = info.error ?? wl.error;
  return (
    <div className="page page-withdraw">
      <PageHeader title={t('withdraw.title')} back="/assets" />
      <div className="wd-grid">
        <div>
          {err && (!info.data || !wl.data) ? (
            <section className="card">
              <ErrorState
                error={err}
                onRetry={() => {
                  info.reload();
                  wl.reload();
                }}
              />
            </section>
          ) : !info.data || !wl.data ? (
            <section className="card">
              <Loading rows={5} />
            </section>
          ) : (
            <>
              <KycBanner info={info.data} />
              {info.data.kycStatus === 'approved' && <WithdrawForm info={info.data} whitelist={wl.data} onDone={info.reload} />}
            </>
          )}
        </div>
        <Records />
      </div>
    </div>
  );
}
