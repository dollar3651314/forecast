/** 提现白名单：最多 10 个，新地址 24 小时后生效，可添加 / 删除。 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../../api/errors';
import { addWhitelist, listWhitelist, removeWhitelist } from '../../api/wallet';
import type { Network, WhitelistAddress } from '../../api/types';
import { useToast } from '../../app/contexts';
import { AsyncView, EmptyState } from '../../components/AsyncView';
import { FieldErrors, Segmented, TextField } from '../../components/Form';
import { Icon } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { ConfirmDialog, Modal } from '../../components/Modal';
import { useAsync } from '../../hooks/useAsync';
import { useNow } from '../../hooks/useCountdown';
import { formatCountdown, formatDateTime } from '../../lib/format';
import { hasErrors, validateWhitelist } from '../../lib/validators';

const MAX = 10;

function AddDialog({ existing, onClose, onAdded }: { existing: WhitelistAddress[]; onClose: () => void; onAdded: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [network, setNetwork] = useState<Network>('TRC20');
  const [address, setAddress] = useState('');
  const [label, setLabel] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const errs = validateWhitelist({ network, address, label }, existing, MAX);
  const shown = submitted ? errs : {};
  const submit = async () => {
    setSubmitted(true);
    setErr(null);
    if (hasErrors(errs)) return;
    setBusy(true);
    try {
      await addWhitelist({ network, address, label });
      toast.show({ tone: 'success', title: t('whitelist.added'), body: t('whitelist.addedBody') });
      onAdded();
      onClose();
    } catch (e) {
      setErr(t(errorKey(e)));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open
      title={t('whitelist.addTitle')}
      onClose={onClose}
      size="sm"
      footer={
        <button type="button" className="btn btn-primary btn-block" onClick={submit} disabled={busy}>
          {busy ? t('common.submitting') : t('whitelist.addConfirm')}
        </button>
      }
    >
      <div className="field">
        <div className="field-label">
          <span>{t('whitelist.network')}</span>
        </div>
        <Segmented label={t('whitelist.network')} value={network} onChange={setNetwork} options={(['ERC20', 'TRC20', 'BSC'] as Network[]).map((n) => ({ value: n, label: n }))} />
      </div>
      <TextField label={t('whitelist.address')} value={address} onChange={setAddress} mono errors={shown.address} placeholder={network === 'TRC20' ? 'T…' : '0x…'} autoComplete="off" spellCheck={false} />
      <TextField label={t('whitelist.label')} value={label} onChange={setLabel} errors={shown.label} maxLength={40} hint={t('whitelist.labelHint')} />
      <FieldErrors id="wl-form-err" errors={shown.form} />
      <div className="notice warn">{t('whitelist.rule')}</div>
      {err && (
        <div className="notice error" role="alert">
          {err}
        </div>
      )}
    </Modal>
  );
}

export default function Whitelist() {
  const { t } = useTranslation();
  const toast = useToast();
  const now = useNow();
  const s = useAsync(listWhitelist, 'whitelist');
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<WhitelistAddress | null>(null);
  const [busy, setBusy] = useState(false);
  const count = s.data?.length ?? 0;
  const doRemove = async () => {
    if (!removing) return;
    setBusy(true);
    try {
      await removeWhitelist(removing.id);
      toast.show({ tone: 'success', title: t('whitelist.removed') });
      setRemoving(null);
      s.reload();
    } catch (e) {
      toast.show({ tone: 'error', title: t(errorKey(e)) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="page page-whitelist">
      <PageHeader title={t('whitelist.title')} back="/assets/withdraw" />
      <section className="card">
        <div className="card-head">
          <h2>{t('whitelist.count', { n: count, max: MAX })}</h2>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setAdding(true)} disabled={!s.data || count >= MAX}>
            <Icon name="plus" size={14} strokeWidth={2.4} />
            {t('whitelist.add')}
          </button>
        </div>
        {count >= MAX && <div className="notice warn">{t('validation.whitelistFull')}</div>}
        <AsyncView state={s} isEmpty={(d) => d.length === 0} empty={<EmptyState title={t('whitelist.empty')} hint={t('whitelist.emptyHint')} />}>
          {(list) => (
            <ul className="wl-list">
              {list.map((w) => {
                const left = new Date(w.activeAt).getTime() - now;
                return (
                  <li key={w.id}>
                    <span className="net-tag">{w.network}</span>
                    <div className="wl-t">
                      <b>{w.label}</b>
                      <span className="num break">{w.address}</span>
                      <small>
                        {t('whitelist.addedAt', { time: formatDateTime(w.createdAt) })} ·{' '}
                        {left > 0 ? <span className="warn-text">{t('whitelist.activeIn', { time: formatCountdown(left) })}</span> : <span className="ok-text">{t('whitelist.active')}</span>}
                      </small>
                    </div>
                    <button type="button" className="icon-btn danger" onClick={() => setRemoving(w)} aria-label={t('whitelist.remove', { label: w.label })}>
                      <Icon name="trash" size={18} />
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </AsyncView>
      </section>
      {adding && s.data && <AddDialog existing={s.data} onClose={() => setAdding(false)} onAdded={s.reload} />}
      <ConfirmDialog open={!!removing} title={t('whitelist.removeTitle')} confirmText={t('common.delete')} tone="danger" busy={busy} onConfirm={doRemove} onClose={() => setRemoving(null)}>
        <p>{t('whitelist.removeBody', { label: removing?.label ?? '' })}</p>
        <p className="num small break">{removing?.address}</p>
      </ConfirmDialog>
    </div>
  );
}
