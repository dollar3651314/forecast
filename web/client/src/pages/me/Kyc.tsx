/**
 * 身份认证（线框图 W5）：身份证 / 护照、证件号、正面 / 反面 / 手持自拍（每张 ≤2MB，JPG/PNG）。
 * 状态：未提交 / 审核中 / 已通过 / 被驳回（附原因）。只在提现前要求通过。
 */
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../../api/errors';
import { getKyc, submitKyc } from '../../api/user';
import type { KycDocType, KycInfo } from '../../api/types';
import { useToast } from '../../app/contexts';
import { AsyncView } from '../../components/AsyncView';
import { FieldErrors, Segmented, TextField } from '../../components/Form';
import { Icon } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { currentLang } from '../../i18n';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime } from '../../lib/format';
import { hasErrors, validateKyc, validateKycFile } from '../../lib/validators';

type Slot = 'front' | 'back' | 'selfie';

function Upload({ slot, file, onFile, errors }: { slot: Slot; file: File | null; onFile: (f: File | null) => void; errors?: string[] }) {
  const { t } = useTranslation();
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    // 只预览合法图片，避免预览超大文件
    if (!file || validateKycFile(file).length) {
      setUrl(null);
      return;
    }
    const u = URL.createObjectURL(file);
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [file]);
  const id = `kyc-${slot}`;
  return (
    <div className={`upload${errors?.length ? ' invalid' : ''}`}>
      <label htmlFor={id} className="upload-box">
        {url ? <img src={url} alt={t(`kyc.slot.${slot}`)} /> : <Icon name="upload" size={26} />}
        <span className="upload-name">{t(`kyc.slot.${slot}`)}</span>
        <small className="break">{file ? `${file.name} · ${(file.size / 1024 / 1024).toFixed(2)} MB` : t('kyc.uploadHint')}</small>
      </label>
      <input id={id} type="file" accept="image/jpeg,image/png" className="sr-only" onChange={(e) => onFile(e.target.files?.[0] ?? null)} aria-describedby={`${id}-err`} aria-invalid={!!errors?.length} />
      <FieldErrors id={`${id}-err`} errors={errors} />
    </div>
  );
}

function KycForm({ onSubmitted }: { onSubmitted: (k: KycInfo) => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [docType, setDocType] = useState<KycDocType>('idCard');
  const [docNumber, setDocNumber] = useState('');
  const [files, setFiles] = useState<Record<Slot, File | null>>({ front: null, back: null, selfie: null });
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const errs = validateKyc({ docType, docNumber, ...files });
  const shown = submitted ? errs : {};
  // 选择文件后立即显示文件本身的错误（大小、格式）
  const fileErr = (s: Slot) => (submitted ? errs[s] : files[s] ? validateKycFile(files[s]) : undefined);
  const submit = async () => {
    setSubmitted(true);
    setErr(null);
    if (hasErrors(errs)) return;
    setBusy(true);
    try {
      const k = await submitKyc({ docType, docNumber: docNumber.trim(), front: files.front!, back: files.back!, selfie: files.selfie! });
      toast.show({ tone: 'success', title: t('kyc.submitted') });
      onSubmitted(k);
    } catch (e) {
      setErr(t(errorKey(e)));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="card">
      <div className="field">
        <div className="field-label">
          <span>{t('kyc.docType')}</span>
        </div>
        <Segmented
          label={t('kyc.docType')}
          value={docType}
          onChange={setDocType}
          options={[
            { value: 'idCard', label: t('kyc.idCard') },
            { value: 'passport', label: t('kyc.passport') },
          ]}
        />
      </div>
      <TextField label={t('kyc.docNumber')} value={docNumber} onChange={setDocNumber} mono errors={shown.docNumber} autoComplete="off" hint={docType === 'idCard' ? t('kyc.idCardHint') : t('kyc.passportHint')} />
      <div className="upload-grid">
        {(['front', 'back', 'selfie'] as Slot[]).map((s) => (
          <Upload key={s} slot={s} file={files[s]} onFile={(f) => setFiles((x) => ({ ...x, [s]: f }))} errors={fileErr(s)} />
        ))}
      </div>
      <p className="muted small">{t('kyc.privacy')}</p>
      {submitted && hasErrors(errs) && (
        <div className="form-summary" role="alert">
          {t('validation.fixAll')}
        </div>
      )}
      {err && (
        <div className="notice error" role="alert">
          {err}
        </div>
      )}
      <button type="button" className="btn btn-primary btn-lg btn-block" onClick={submit} disabled={busy}>
        {busy ? t('common.submitting') : t('kyc.submit')}
      </button>
    </section>
  );
}

function StatusCard({ k }: { k: KycInfo }) {
  const { t } = useTranslation();
  const icon = k.status === 'approved' ? 'checkCircle' : k.status === 'rejected' ? 'warn' : 'shield';
  return (
    <section className={`card kyc-status k-${k.status}`} aria-live="polite">
      <Icon name={icon} size={28} />
      <div>
        <b>{t(`kyc.status.${k.status}`)}</b>
        <p>{t(`kyc.statusDesc.${k.status}`)}</p>
        {k.status === 'rejected' && k.rejectReason && (
          <p className="reject">
            {t('kyc.rejectReason')}
            {k.rejectReason}
          </p>
        )}
        {k.docType && (
          <dl className="kv-list compact">
            <div>
              <dt>{t('kyc.docType')}</dt>
              <dd>{t(`kyc.${k.docType}`)}</dd>
            </div>
            <div>
              <dt>{t('kyc.docNumber')}</dt>
              <dd className="num">{k.docNumber ? `${k.docNumber.slice(0, 3)}${'*'.repeat(Math.max(0, k.docNumber.length - 7))}${k.docNumber.slice(-4)}` : '—'}</dd>
            </div>
            <div>
              <dt>{t('kyc.submittedAt')}</dt>
              <dd className="num">{formatDateTime(k.submittedAt)}</dd>
            </div>
            {k.reviewedAt && (
              <div>
                <dt>{t('kyc.reviewedAt')}</dt>
                <dd className="num">{formatDateTime(k.reviewedAt)}</dd>
              </div>
            )}
          </dl>
        )}
      </div>
    </section>
  );
}

export default function Kyc() {
  const { t, i18n } = useTranslation();
  const s = useAsync(() => getKyc(currentLang()), `kyc|${i18n.language}`);
  return (
    <div className="page page-narrow">
      <PageHeader title={t('me.kyc')} back="/me" />
      <p className="muted small mx">{t('kyc.intro')}</p>
      <AsyncView state={s}>
        {(k) => (
          <>
            {k.status !== 'none' && <StatusCard k={k} />}
            {(k.status === 'none' || k.status === 'rejected') && <KycForm onSubmitted={(nk) => s.setData(() => nk)} />}
          </>
        )}
      </AsyncView>
    </div>
  );
}
