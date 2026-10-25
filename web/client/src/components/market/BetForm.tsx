/**
 * P3 下注：电脑右栏（BetPanel）和手机底部抽屉（BetSheet）。两者的计算、校验、提交都在 hooks/useBetForm.ts，这里只管布局（C64）。
 * 主操作"确认押注"在两种布局里都固定在底部（右栏 sticky、抽屉的页脚），1366×768、1280×720、390×844、375×667 下不滚动就能看到（C79）。
 */
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { errorKey } from '../../api/errors';
import type { BetResult, MarketDetail } from '../../api/types';
import { useToast } from '../../app/contexts';
import { QUICK_AMOUNTS, useBetForm } from '../../hooks/useBetForm';
import { useLang } from '../../hooks/useLang';
import { formatAmount } from '../../lib/format';
import { multText, pickText, utcText } from '../../lib/market';
import { Icon } from '../Icon';
import { Modal } from '../Modal';

type Form = ReturnType<typeof useBetForm>;

/** 金额、快捷金额、余额和上限、预计回报、不能撤销的提示 */
function BetBody({ f }: { f: Form }) {
  const { t } = useTranslation();
  const lim = f.limits;
  const errText = f.fieldError ? t(`bet.err.${f.fieldError.key}`, { v: f.fieldError.v ? formatAmount(f.fieldError.v) : '' }) : null;
  return (
    <div className="bet-body">
      <div className={`bet-amount${errText ? ' invalid' : ''}`}>
        <label className="field-label" htmlFor="bet-amount">
          {t('bet.amount')}
        </label>
        <div className="bet-input">
          <input
            id="bet-amount"
            inputMode="decimal"
            autoComplete="off"
            className="num"
            value={f.amount}
            placeholder={lim ? t('bet.placeholder', { min: formatAmount(lim.min, { dp: 0 }), max: formatAmount(lim.max, { dp: 0 }) }) : ''}
            onChange={(e) => f.setAmount(e.target.value)}
            aria-invalid={!!errText}
            aria-describedby={errText ? 'bet-err' : undefined}
          />
          <span className="bet-unit">USDT</span>
        </div>
        <div className="bet-quick">
          {QUICK_AMOUNTS.map((v) => (
            <button key={v} type="button" className={f.amount === v ? 'on' : ''} onClick={() => f.setAmount(v)}>
              {v}
            </button>
          ))}
          <button type="button" className={f.amount !== '' && f.amount === f.maxAmount ? 'on' : ''} onClick={() => f.setAmount(f.maxAmount)}>
            {t('bet.max')}
          </button>
        </div>
        {errText && (
          <p className="bet-err" id="bet-err" role="alert">
            {errText}
          </p>
        )}
      </div>
      <dl className="bet-kv">
        <div>
          <dt>{t('bet.available')}</dt>
          <dd className="num">{formatAmount(f.available)} USDT</dd>
        </div>
        {lim && (
          <div>
            <dt>{t('bet.remaining')}</dt>
            <dd className="num">{formatAmount(lim.remaining)} USDT</dd>
          </div>
        )}
      </dl>
      <div className={`bet-est${f.estimate ? '' : ' empty'}`} aria-live="polite">
        <div className="bet-est-k">{t('bet.estimate')}</div>
        {f.estimate ? (
          <>
            <div className="bet-est-v">
              <span>{t('bet.estimateLead', { b: formatAmount(f.estimate.stake) })}</span>
              <b className="num">{formatAmount(f.estimate.ret)}</b>
              <span className="bet-profit num">{t('bet.profit', { v: `+${formatAmount(f.estimate.profit)}` })}</span>
            </div>
            <p>{t('bet.estimateSub', { fee: formatAmount(f.estimate.fee) })}</p>
          </>
        ) : (
          <p>{t('bet.estimateEmpty')}</p>
        )}
      </div>
      <p className="bet-warn">
        <Icon name="info" size={14} />
        {t('bet.irreversible')}
      </p>
      {f.error ? (
        <div className="notice error" role="alert">
          {t(errorKey(f.error))}
        </div>
      ) : null}
    </div>
  );
}

/** 确认按钮：余额不足时变成"去充值" */
function BetSubmit({ f, market }: { f: Form; market: MarketDetail }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  if (market.status !== 'listed') {
    return (
      <button type="button" className="btn btn-lg btn-block btn-ghost" disabled>
        {market.status === 'paused' ? t('bet.paused') : t('bet.closed')}
      </button>
    );
  }
  if (Date.parse(market.openAt) > Date.now()) {
    return (
      <button type="button" className="btn btn-lg btn-block btn-ghost" disabled>
        {t('bet.notOpen', { t: utcText(market.openAt) })}
      </button>
    );
  }
  if (f.insufficient) {
    return (
      <button type="button" className="btn btn-lg btn-block btn-outline" onClick={() => navigate('/assets')}>
        {t('bet.toDeposit')}
      </button>
    );
  }
  return (
    <button type="button" className="btn btn-lg btn-block btn-primary bet-confirm" disabled={!f.canSubmit} onClick={() => void f.submit()} data-testid="bet-confirm">
      {f.busy ? t('bet.placing') : f.estimate ? t('bet.confirmAmount', { v: formatAmount(f.estimate.stake) }) : t('bet.confirm')}
    </button>
  );
}

function useSuccessToast() {
  const { t } = useTranslation();
  const toast = useToast();
  const lang = useLang();
  return (market: MarketDetail, r: BetResult) => {
    const o = market.options.find((x) => x.optionNo === r.optionNo);
    toast.show({ tone: 'success', title: t('bet.success'), body: t('bet.successBody', { opt: pickText(o?.label, lang), amount: formatAmount(r.amount), no: r.betNo }) });
  };
}

/** 选项按钮（电脑右栏顶部）：概率和预计倍数 */
function OptionPicker({ market, value, onChange }: { market: MarketDetail; value?: string; onChange: (no: string) => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const updown = market.category === 'CRYPTO_15M' || market.category === 'CRYPTO_1H';
  return (
    <div className={`bet-pick n${market.options.length}`} role="radiogroup" aria-label={t('bet.pick')}>
      {market.options.map((o, i) => {
        const p = market.pool.options.find((x) => x.optionNo === o.optionNo)!;
        const on = value === o.optionNo;
        return (
          <button
            key={o.optionNo}
            type="button"
            role="radio"
            aria-checked={on}
            className={`pick-btn${on ? ' on' : ''}${updown ? (i === 0 ? ' up' : ' down') : ''}`}
            onClick={() => onChange(o.optionNo)}
          >
            <span className="pick-label">{pickText(o.label, lang)}</span>
            <span className="pick-nums">
              <b className="num">{p.pct}</b>
              <small className="num">{multText(p.mult)}</small>
            </span>
          </button>
        );
      })}
    </div>
  );
}

export function BetPanel({ market, optionNo, onOption, onPlaced }: { market: MarketDetail; optionNo?: string; onOption: (no: string) => void; onPlaced: (r: BetResult) => void }) {
  const { t } = useTranslation();
  const success = useSuccessToast();
  const f = useBetForm(market, optionNo, (r) => {
    success(market, r);
    onPlaced(r);
  });
  return (
    <aside className="bet-panel" aria-label={t('bet.title')} data-testid="bet-panel">
      <div className="bet-panel-scroll">
        <h2 className="bet-h">{t('bet.title')}</h2>
        <OptionPicker market={market} value={optionNo} onChange={onOption} />
        {f.option ? (
          <p className="bet-now">
            {t('bet.current', { pct: f.option.pct, mult: multText(f.option.mult) })}
            <span className="muted"> · {t('md.oddsNote')}</span>
          </p>
        ) : (
          <p className="bet-now muted">{t('bet.choose')}</p>
        )}
        <BetBody f={f} />
      </div>
      <div className="bet-panel-foot">
        <BetSubmit f={f} market={market} />
      </div>
    </aside>
  );
}

export function BetSheet({ market, optionNo, onClose, onPlaced }: { market: MarketDetail; optionNo: string; onClose: () => void; onPlaced: (r: BetResult) => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const success = useSuccessToast();
  const f = useBetForm(market, optionNo, (r) => {
    success(market, r);
    onPlaced(r);
    onClose();
  });
  const o = market.options.find((x) => x.optionNo === optionNo);
  return (
    <Modal
      open
      variant="sheet"
      title={
        <>
          {t('bet.title')}：<span className="sheet-opt">{pickText(o?.label, lang)}</span>
        </>
      }
      onClose={onClose}
      footer={<BetSubmit f={f} market={market} />}
    >
      {f.option && (
        <p className="bet-now">
          {t('bet.current', { pct: f.option.pct, mult: multText(f.option.mult) })}
          <span className="muted"> · {t('md.oddsNote')}</span>
        </p>
      )}
      <BetBody f={f} />
    </Modal>
  );
}
