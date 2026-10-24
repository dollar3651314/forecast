/** 个人资料：查看和编辑（邮箱不可在此修改）。 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorKey } from '../../api/errors';
import { getProfile, updateProfile } from '../../api/user';
import type { Profile as ProfileT } from '../../api/types';
import { useToast } from '../../app/contexts';
import { AsyncView } from '../../components/AsyncView';
import { DateFields } from '../../components/DateFields';
import { Select, TextField } from '../../components/Form';
import { PageHeader } from '../../components/Layout';
import { useAsync } from '../../hooks/useAsync';
import { COUNTRIES } from '../../lib/countries';
import { formatDateTime } from '../../lib/format';
import { displayName } from '../../lib/profile';
import { hasErrors, validateProfile, type ProfileForm } from '../../lib/validators';

function Editor({ p, onSaved, onCancel }: { p: ProfileT; onSaved: (p: ProfileT) => void; onCancel: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [f, setF] = useState<ProfileForm>({ firstName: p.firstName, middleName: p.middleName, lastName: p.lastName, birthDate: p.birthDate, nationality: p.nationality });
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const errs = validateProfile(f);
  const shown = submitted ? errs : {};
  const set = (k: keyof ProfileForm, v: string) => setF((x) => ({ ...x, [k]: v }));
  const save = async () => {
    setSubmitted(true);
    setErr(null);
    if (hasErrors(errs)) return;
    setBusy(true);
    try {
      const np = await updateProfile({ ...f, firstName: f.firstName.trim(), middleName: f.middleName.trim(), lastName: f.lastName.trim(), birthDate: f.birthDate.trim() });
      toast.show({ tone: 'success', title: t('profile.saved') });
      onSaved(np);
    } catch (e) {
      setErr(t(errorKey(e)));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="card">
      <div className="row3">
        <TextField label={t('profile.firstName')} value={f.firstName} onChange={(v) => set('firstName', v)} errors={shown.firstName} />
        <TextField label={t('profile.middleName')} labelExtra={<em>{t('common.optional')}</em>} value={f.middleName} onChange={(v) => set('middleName', v)} errors={shown.middleName} />
        <TextField label={t('profile.lastName')} value={f.lastName} onChange={(v) => set('lastName', v)} errors={shown.lastName} />
      </div>
      <div className="row2 row2-date">
        <DateFields label={t('profile.birthDate')} hint={t('dateFields.birthHint')} autoCompletePrefix="bday" value={f.birthDate} onChange={(v) => set('birthDate', v)} errors={shown.birthDate} />
        <Select label={t('profile.nationality')} value={f.nationality} onChange={(v) => set('nationality', v)} options={COUNTRIES.map((c) => ({ value: c, label: t(`countries.${c}`) }))} searchable errors={shown.nationality} />
      </div>
      <p className="muted small">{t('profile.kycNote')}</p>
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
      <div className="btn-row">
        <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={busy}>
          {t('common.cancel')}
        </button>
        <button type="button" className="btn btn-primary" onClick={save} disabled={busy}>
          {busy ? t('common.submitting') : t('common.save')}
        </button>
      </div>
    </section>
  );
}

export default function Profile() {
  const { t, i18n } = useTranslation();
  const s = useAsync(getProfile, 'profile');
  const [editing, setEditing] = useState(false);
  return (
    <div className="page page-narrow">
      <PageHeader
        title={t('me.profile')}
        back="/me"
        right={
          !editing && s.data ? (
            <button type="button" className="btn btn-outline btn-sm" onClick={() => setEditing(true)}>
              {t('common.edit')}
            </button>
          ) : undefined
        }
      />
      <AsyncView state={s}>
        {(p) =>
          editing ? (
            <Editor
              p={p}
              onCancel={() => setEditing(false)}
              onSaved={(np) => {
                s.setData(() => np);
                setEditing(false);
              }}
            />
          ) : (
            <section className="card">
              <dl className="kv-list big">
                <div>
                  <dt>{t('profile.name')}</dt>
                  <dd>{displayName(p, i18n.language)}</dd>
                </div>
                <div>
                  <dt>{t('profile.firstName')}</dt>
                  <dd>{p.firstName}</dd>
                </div>
                <div>
                  <dt>{t('profile.middleName')}</dt>
                  <dd>{p.middleName || '—'}</dd>
                </div>
                <div>
                  <dt>{t('profile.lastName')}</dt>
                  <dd>{p.lastName}</dd>
                </div>
                <div>
                  <dt>{t('auth.email')}</dt>
                  <dd className="break">{p.email}</dd>
                </div>
                <div>
                  <dt>{t('profile.birthDate')}</dt>
                  <dd className="num">{p.birthDate}</dd>
                </div>
                <div>
                  <dt>{t('profile.nationality')}</dt>
                  <dd>{t(`countries.${p.nationality}`)}</dd>
                </div>
                <div>
                  <dt>{t('profile.registeredAt')}</dt>
                  <dd className="num">{formatDateTime(p.registeredAt)}</dd>
                </div>
              </dl>
            </section>
          )
        }
      </AsyncView>
    </div>
  );
}
