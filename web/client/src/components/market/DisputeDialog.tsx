/** P10 提交争议（需求第 4.4 节）：公示中、在本市场有下注的客户可以提交一次；选认为的结果、写理由（必填，≤ 1,000 字）、可附链接。 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../../api/errors';
import { submitDispute } from '../../api/markets';
import type { MarketDetail } from '../../api/types';
import { useToast } from '../../app/contexts';
import { useLang } from '../../hooks/useLang';
import { pickText } from '../../lib/market';
import { Modal } from '../Modal';

export function DisputeDialog({ market, onClose, onDone }: { market: MarketDetail; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const lang = useLang();
  const toast = useToast();
  const proposed = market.resolution?.winningOptionNo;
  const [claim, setClaim] = useState('');
  const [reason, setReason] = useState('');
  const [link, setLink] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const errs = { claim: !claim, reason: !reason.trim() };

  const submit = async () => {
    setSubmitted(true);
    if (errs.claim || errs.reason) return;
    setBusy(true);
    setError(null);
    try {
      await submitDispute(market.publicNo, { optionNo: claim, reason: reason.trim(), link: link.trim() || undefined });
      toast.show({ tone: 'success', title: t('dispute.done'), body: t('dispute.doneBody') });
      onDone();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open
      variant="sheet"
      size="md"
      title={t('dispute.title')}
      subtitle={t('dispute.proposed', { opt: pickText(market.options.find((o) => o.optionNo === proposed)?.label, lang) })}
      onClose={onClose}
      footer={
        <div className="btn-row">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button type="button" className="btn btn-primary" onClick={() => void submit()} disabled={busy}>
            {busy ? t('common.submitting') : t('dispute.submit')}
          </button>
        </div>
      }
    >
      <fieldset className="dsp-claim">
        <legend className="field-label">{t('dispute.claim')}</legend>
        <div className="dsp-opts">
          {market.options.map((o) => (
            <label key={o.optionNo} className={`dsp-opt${claim === o.optionNo ? ' on' : ''}${o.optionNo === proposed ? ' same' : ''}`}>
              <input type="radio" name="dsp-claim" value={o.optionNo} checked={claim === o.optionNo} onChange={() => setClaim(o.optionNo)} disabled={o.optionNo === proposed} />
              <span>{pickText(o.label, lang)}</span>
              {o.optionNo === proposed && <small>{t('dispute.sameAsProposed')}</small>}
            </label>
          ))}
        </div>
        {submitted && errs.claim && <p className="bet-err">{t('dispute.needClaim')}</p>}
      </fieldset>
      <div className="field">
        <label className="field-label" htmlFor="dsp-reason">
          {t('dispute.reason')}
          <em>{reason.length} / 1000</em>
        </label>
        <textarea id="dsp-reason" className="dsp-text" rows={4} maxLength={1000} value={reason} placeholder={t('dispute.reasonPh')} onChange={(e) => setReason(e.target.value)} />
        {submitted && errs.reason && <p className="bet-err">{t('dispute.needReason')}</p>}
      </div>
      <div className="field">
        <label className="field-label" htmlFor="dsp-link">
          {t('dispute.link')}
        </label>
        <div className="input-wrap">
          <input id="dsp-link" type="url" value={link} placeholder="https://" onChange={(e) => setLink(e.target.value)} />
        </div>
      </div>
      <p className="bet-warn">{t('dispute.rule')}</p>
      {error ? (
        <div className="notice error" role="alert">
          {t(errorKey(error))}
        </div>
      ) : null}
    </Modal>
  );
}
