/**
 * 资金归集 · 归集设置（方案第 4.7、4.9 节和附录 B.7）。
 * - 以太坊、BSC：冷钱包（parent，Safe 多签）、合约地址、发起账户——从链上读，**只读**（写在合约里，后台改不了）；
 * - TRON：冷钱包地址可以改，高危、原因必填，**24 小时后生效**，待生效期间可以取消；
 * - 参数：门槛、手续费占比上限（Amos 10-20：后台可改，默认按方案建议，页面上显示默认值）、以太坊 / BSC 上线初期的金额上限、自动归集、以太坊 gas 上限、
 *   发起账户余额告警下限、TRON 能量（租能量 / 烧 TRX、最高单价、每天租金上限、租不到时回退、平台余额告警）、TRON 补 TRX 上限和清单有效期、提醒下限。
 *   保存走高危确认（原因必填、审计），带版本号防覆盖。
 * 真实接口模式（第一阶段）：只显示以太坊、BSC 的合约和冷钱包（含配置问题、发起账户核对结果）、门槛、手续费占比上限、上线初期上限、发起账户余额告警下限；
 * TRON 冷钱包、能量、自动归集、gas 上限、提醒下限是下一阶段，不显示。
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Form, Input, InputNumber, Segmented, Switch } from 'antd';
import { useTranslation } from 'react-i18next';
import { cancelTronSweepTarget, getSweepSettings, requestTronSweepTarget, updateSweepSettings } from '../../../api';
import type { SweepChain, SweepSettings, SweepSettingsInput } from '../../../api/types';
import { useApi } from '../../../hooks/useApi';
import { useHighRisk } from '../../../hooks/useConfirmDialogs';
import { ErrorState } from '../../../components/ErrorState';
import { PermButton, useCan } from '../../../components/PermButton';
import { Num } from '../../../components/Num';
import { ChainTag, Pill } from '../../../components/Pill';
import { fullTime } from '../../../utils/display';
import { SWEEP_CHAINS, SWEEP_DEFAULTS, SWEEP_NEXT_PHASE, SWEEP_PERM, SWEEP_UI, isSweepNumber, isTronAddress } from '../../../config/sweep';
import { SweepShell } from './SweepShell';

export function SweepSettingsPage() {
  return <SweepShell tab="settings">{SWEEP_UI ? <Settings /> : null}</SweepShell>;
}

type Draft = Omit<SweepSettingsInput, 'reason' | 'expectedVersion'>;

const toDraft = (s: SweepSettings): Draft => ({
  thresholds: { ...s.thresholds },
  maxFeeRatioPct: s.maxFeeRatioPct,
  autoEnabled: s.autoEnabled,
  autoHourUtc: s.autoHourUtc,
  ethMaxBaseFeeGwei: s.ethMaxBaseFeeGwei,
  operatorMin: { ...s.operatorMin },
  remind: { ...s.remind },
  tronTopupPerItem: s.tronTopupPerItem,
  tronTopupPerDay: s.tronTopupPerDay,
  tronExpireHours: s.tronExpireHours,
  evmCaps: { ...s.evmCaps },
  tronEnergy: {
    mode: s.tronEnergy.mode,
    maxPriceSun: s.tronEnergy.maxPriceSun,
    perDayTrx: s.tronEnergy.perDayTrx,
    fallbackBurn: s.tronEnergy.fallbackBurn,
    durationMin: s.tronEnergy.durationMin,
    providerBalanceMin: s.tronEnergy.providerBalanceMin,
  },
});

function Settings() {
  const { t } = useTranslation();
  const can = useCan();
  const hr = useHighRisk();
  const fetcher = useCallback(() => getSweepSettings(), []);
  const { data: s, error, reload } = useApi(fetcher);
  const [draft, setDraft] = useState<Draft | null>(null);
  useEffect(() => {
    if (s) setDraft(toDraft(s));
  }, [s]);

  const changed = useMemo(() => (s && draft ? JSON.stringify(toDraft(s)) !== JSON.stringify(draft) : false), [s, draft]);
  const invalid = useMemo(() => {
    if (!draft) return true;
    const nums = [...SWEEP_CHAINS.map((c) => draft.thresholds[c]), ...SWEEP_CHAINS.map((c) => draft.remind[c]), draft.operatorMin.ERC20, draft.operatorMin.BSC, draft.maxFeeRatioPct, draft.ethMaxBaseFeeGwei, draft.tronTopupPerItem, draft.tronTopupPerDay, draft.evmCaps.perAddress, draft.evmCaps.perBatch, draft.tronEnergy.maxPriceSun, draft.tronEnergy.perDayTrx, draft.tronEnergy.providerBalanceMin];
    return nums.some((v) => !isSweepNumber(v)) || Number(draft.maxFeeRatioPct) > 100 || Number(draft.tronEnergy.maxPriceSun) > 100;
  }, [draft]);

  if (error) {
    return (
      <div className="card">
        <ErrorState error={error} onRetry={reload} />
      </div>
    );
  }
  if (!s || !draft) return <div className="card card-pad">{t('common.loading')}</div>;
  const editable = can(SWEEP_PERM.config);
  const num = (value: string, onChange: (v: string) => void, label: string, suffix?: string) => (
    <Input aria-label={label} value={value} disabled={!editable} status={isSweepNumber(value) ? undefined : 'error'} suffix={suffix} onChange={(e) => onChange(e.target.value)} style={{ width: 170 }} />
  );
  const setThreshold = (c: SweepChain, v: string) => setDraft({ ...draft, thresholds: { ...draft.thresholds, [c]: v } });
  const setRemind = (c: SweepChain, v: string) => setDraft({ ...draft, remind: { ...draft.remind, [c]: v } });
  const pending = s.tronTarget.pending;
  const te = draft.tronEnergy;
  const setTe = (patch: Partial<Draft['tronEnergy']>) => setDraft({ ...draft, tronEnergy: { ...te, ...patch } });
  const dflt = (v: string) => <small className="muted sweep-default">{t('sweep.defaultIs', { v })}</small>;

  return (
    <>
      <section className="card card-pad" style={{ marginBottom: 12 }} aria-label={t('sweep.evmTitle')}>
        <div className="card-title">
          <span>
            <ChainTag chain="ERC20" /> <ChainTag chain="BSC" /> {t('sweep.evmTitle')}
          </span>
          <Pill tone="muted">{t('sweep.readOnly')}</Pill>
        </div>
        <Alert type="info" showIcon style={{ marginBottom: 10 }} message={t('sweep.evmParentHint')} />
        {s.evm.configured === false ? (
          <Alert type="warning" showIcon style={{ marginBottom: 10 }} message={s.evm.problem ? t('sweep.contractsProblem', { problem: s.evm.problem }) : t('sweep.contractsMissing')} />
        ) : null}
        {s.evm.operatorReady === false && s.evm.operatorProblem ? (
          <Alert type="warning" showIcon style={{ marginBottom: 10 }} message={t('sweep.operatorProblem', { problem: s.evm.operatorProblem })} />
        ) : null}
        <div className="kv-grid sweep-kv4">
          <div>
            {t('sweep.parent')}
            <Num strong>{s.evm.parent}</Num>
          </div>
          {s.evm.safeThreshold ? (
            <div>
              {t('sweep.safeThreshold')}
              <Num>{s.evm.safeThreshold}</Num>
            </div>
          ) : null}
          <div>
            {t('sweep.factory')}
            <Num>{s.evm.factory}</Num>
          </div>
          <div>
            {t('sweep.implementation')}
            <Num>{s.evm.implementation}</Num>
          </div>
          <div>
            {t('sweep.batcher')}
            <Num>{s.evm.batcher}</Num>
          </div>
          <div>
            {t('sweep.operator')}
            <Num>{s.evm.operator}</Num>
          </div>
          <div>
            {t('sweep.operatorBalance')}
            <Num>{`${s.evm.operatorBalances.ERC20} ETH · ${s.evm.operatorBalances.BSC} BNB`}</Num>
          </div>
          <div>
            {t('sweep.readAt')}
            <Num>{fullTime(s.evm.readAt)}</Num>
          </div>
        </div>
      </section>

      {SWEEP_NEXT_PHASE ? null : (
        <>
      <section className="card card-pad" style={{ marginBottom: 12 }} aria-label={t('sweep.tronTitle')}>
        <div className="card-title">
          <span>
            <ChainTag chain="TRC20" /> {t('sweep.tronTitle')}
          </span>
          <PermButton
            perm={SWEEP_PERM.config}
            danger
            disabled={!!pending}
            disabledReason={t('sweep.targetPendingHint')}
            onClick={() =>
              hr.ask({
                title: t('sweep.targetChangeTitle'),
                description: t('sweep.targetChangeDesc', { hours: s.tronTargetDelayHours }),
                extra: (
                  <Form.Item
                    name="address"
                    label={t('sweep.newAddress')}
                    rules={[
                      { required: true, message: t('sweep.addressRequired') },
                      { validator: (_r, v: string) => (!v || isTronAddress(v) ? Promise.resolve() : Promise.reject(new Error(t('sweep.addressFormat')))) },
                    ]}
                  >
                    <Input placeholder="T…" autoComplete="off" />
                  </Form.Item>
                ),
                confirmText: t('sweep.submitChange'),
                onConfirm: (v) => requestTronSweepTarget(String(v.address ?? ''), v.reason),
                onDone: reload,
              })
            }
          >
            {t('sweep.changeTarget')}
          </PermButton>
        </div>
        <div className="kv-grid">
          <div>
            {t('sweep.currentTarget')}
            <Num strong>{s.tronTarget.address ?? t('sweep.notSet')}</Num>
          </div>
          <div>
            {t('sweep.targetDelay')}
            <Num>{t('sweep.hours', { count: s.tronTargetDelayHours })}</Num>
          </div>
        </div>
        {pending ? (
          <Alert
            type="warning"
            showIcon
            style={{ marginTop: 10 }}
            message={t('sweep.pendingTarget', { address: pending.address, time: fullTime(pending.effectiveAt) })}
            description={t('sweep.pendingBy', { who: pending.requestedBy, at: fullTime(pending.requestedAt), reason: pending.reason })}
            action={
              <PermButton
                perm={SWEEP_PERM.config}
                size="small"
                onClick={() =>
                  hr.ask({ title: t('sweep.cancelChangeTitle'), confirmText: t('sweep.cancelChange'), onConfirm: (v) => cancelTronSweepTarget(v.reason), onDone: reload })
                }
              >
                {t('sweep.cancelChange')}
              </PermButton>
            }
          />
        ) : null}
        <p className="muted" style={{ margin: '10px 0 0', fontSize: 12 }}>{t('sweep.tronTargetHint')}</p>
      </section>

        </>
      )}

      <section className="card card-pad" aria-label={t('sweep.paramsTitle')}>
        <div className="card-title">
          <span>{t('sweep.paramsTitle')}</span>
          <span className="muted" style={{ fontSize: 12, fontWeight: 400 }}>
            {t('sweep.version', { v: s.version, who: s.updatedBy ?? '—', at: fullTime(s.updatedAt) })}
          </span>
        </div>
        <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('sweep.paramsHint')} />
        {SWEEP_NEXT_PHASE ? <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('sweep.nextPhaseSettings')} /> : null}
        <div className="sweep-form">
          <div className="sweep-form-row">
            <label>{t('sweep.threshold')}</label>
            <div className="sweep-form-fields">
              {SWEEP_CHAINS.map((c) => (
                <span key={c} className="sweep-field">
                  <span>
                    <ChainTag chain={c} /> {num(draft.thresholds[c], (v) => setThreshold(c, v), `${t('sweep.threshold')} ${c}`, 'USDT')}
                  </span>
                  {dflt(`${SWEEP_DEFAULTS.thresholds[c]} USDT`)}
                </span>
              ))}
            </div>
          </div>
          <div className="sweep-form-row">
            <label>{t('sweep.maxFeeRatio')}</label>
            <div className="sweep-form-fields">
              <span className="sweep-field">
                {num(draft.maxFeeRatioPct, (v) => setDraft({ ...draft, maxFeeRatioPct: v }), t('sweep.maxFeeRatio'), '%')}
                {dflt(`${SWEEP_DEFAULTS.maxFeeRatioPct}%`)}
              </span>
              <small className="muted">{t('sweep.maxFeeRatioHint')}</small>
            </div>
          </div>
          <div className="sweep-form-row">
            <label>{t('sweep.evmCaps')}</label>
            <div className="sweep-form-fields">
              {num(draft.evmCaps.perAddress, (v) => setDraft({ ...draft, evmCaps: { ...draft.evmCaps, perAddress: v } }), t('sweep.capPerAddress'), t('sweep.usdtPerAddress'))}
              {num(draft.evmCaps.perBatch, (v) => setDraft({ ...draft, evmCaps: { ...draft.evmCaps, perBatch: v } }), t('sweep.capPerBatch'), t('sweep.usdtPerBatch'))}
              <small className="muted">{t('sweep.evmCapsHint')}</small>
            </div>
          </div>
          {SWEEP_NEXT_PHASE ? null : (
            <>
          <div className="sweep-form-row">
            <label>{t('sweep.autoTitle')}</label>
            <div className="sweep-form-fields">
              <Switch aria-label={t('sweep.autoTitle')} checked={draft.autoEnabled} disabled={!editable} onChange={(v) => setDraft({ ...draft, autoEnabled: v })} />
              <span>
                {t('sweep.autoHour')}{' '}
                <InputNumber aria-label={t('sweep.autoHour')} min={0} max={23} value={draft.autoHourUtc} disabled={!editable} onChange={(v) => setDraft({ ...draft, autoHourUtc: Number(v ?? 0) })} />
              </span>
              <small className="muted">{t('sweep.autoHint')}</small>
            </div>
          </div>
          <div className="sweep-form-row">
            <label>{t('sweep.ethMaxBaseFee')}</label>
            <div className="sweep-form-fields">{num(draft.ethMaxBaseFeeGwei, (v) => setDraft({ ...draft, ethMaxBaseFeeGwei: v }), t('sweep.ethMaxBaseFee'), 'gwei')}</div>
          </div>
            </>
          )}
          <div className="sweep-form-row">
            <label>{t('sweep.operatorMin')}</label>
            <div className="sweep-form-fields">
              {num(draft.operatorMin.ERC20, (v) => setDraft({ ...draft, operatorMin: { ...draft.operatorMin, ERC20: v } }), `${t('sweep.operatorMin')} ETH`, 'ETH')}
              {num(draft.operatorMin.BSC, (v) => setDraft({ ...draft, operatorMin: { ...draft.operatorMin, BSC: v } }), `${t('sweep.operatorMin')} BNB`, 'BNB')}
              <small className="muted">{t('sweep.zeroOff')}</small>
            </div>
          </div>
          {SWEEP_NEXT_PHASE ? null : (
            <>
          <div className="sweep-form-row">
            <label>{t('sweep.tronEnergy')}</label>
            <div className="sweep-form-fields">
              <Segmented
                aria-label={t('sweep.tronEnergy')}
                disabled={!editable}
                value={te.mode}
                onChange={(v) => setTe({ mode: v as Draft['tronEnergy']['mode'] })}
                options={[
                  { value: 'rent', label: t('sweep.energyRent') },
                  { value: 'burn', label: t('sweep.energyBurn') },
                ]}
              />
              <small className="muted">{t('sweep.energyHint', { provider: s.tronEnergy.provider })}</small>
            </div>
          </div>
          <div className="sweep-form-row">
            <label>{t('sweep.rentLimits')}</label>
            <div className="sweep-form-fields">
              {num(te.maxPriceSun, (v) => setTe({ maxPriceSun: v }), t('sweep.rentMaxPrice'), t('sweep.sunPerEnergy'))}
              {num(te.perDayTrx, (v) => setTe({ perDayTrx: v }), t('sweep.rentPerDay'), t('sweep.trxPerDay'))}
              <span>
                {t('sweep.rentDuration')}{' '}
                <InputNumber aria-label={t('sweep.rentDuration')} min={10} max={1440} value={te.durationMin} disabled={!editable} onChange={(v) => setTe({ durationMin: Number(v ?? 60) })} />
              </span>
              <span>
                <Switch aria-label={t('sweep.fallbackBurn')} checked={te.fallbackBurn} disabled={!editable} onChange={(v) => setTe({ fallbackBurn: v })} /> {t('sweep.fallbackBurn')}
              </span>
            </div>
          </div>
          <div className="sweep-form-row">
            <label>{t('sweep.rentAccount')}</label>
            <div className="sweep-form-fields">
              <span>
                {t('sweep.rentBalance')} <Num strong>{`${s.tronEnergy.providerBalance} TRX`}</Num>
              </span>
              <span>
                {t('sweep.rentSpentToday')} <Num>{`${s.tronEnergy.spentTodayTrx} / ${s.tronEnergy.perDayTrx} TRX`}</Num>
              </span>
              {num(te.providerBalanceMin, (v) => setTe({ providerBalanceMin: v }), t('sweep.rentBalanceMin'), 'TRX')}
              <small className="muted">{t('sweep.rentBalanceMinHint')}</small>
            </div>
          </div>
          <div className="sweep-form-row">
            <label>{t('sweep.tronTopup')}</label>
            <div className="sweep-form-fields">
              {num(draft.tronTopupPerItem, (v) => setDraft({ ...draft, tronTopupPerItem: v }), t('sweep.perItem'), t('sweep.trxPerItem'))}
              {num(draft.tronTopupPerDay, (v) => setDraft({ ...draft, tronTopupPerDay: v }), t('sweep.perDay'), t('sweep.trxPerDay'))}
              <small className="muted">{t('sweep.tronTopupHint')}</small>
            </div>
          </div>
          <div className="sweep-form-row">
            <label>{t('sweep.tronExpire')}</label>
            <div className="sweep-form-fields">
              <InputNumber aria-label={t('sweep.tronExpire')} min={1} max={23} value={draft.tronExpireHours} disabled={!editable} onChange={(v) => setDraft({ ...draft, tronExpireHours: Number(v ?? 20) })} />
              <small className="muted">{t('sweep.tronExpireHint')}</small>
            </div>
          </div>
          <div className="sweep-form-row">
            <label>{t('sweep.remind')}</label>
            <div className="sweep-form-fields">
              {SWEEP_CHAINS.map((c) => (
                <span key={c}>
                  <ChainTag chain={c} /> {num(draft.remind[c], (v) => setRemind(c, v), `${t('sweep.remind')} ${c}`, 'USDT')}
                </span>
              ))}
              <small className="muted">{t('sweep.zeroOff')}</small>
            </div>
          </div>
            </>
          )}
        </div>
        <div className="sweep-actions" style={{ marginTop: 12 }}>
          <PermButton
            perm={SWEEP_PERM.config}
            type="primary"
            danger
            disabled={!changed || invalid}
            disabledReason={invalid ? t('sweep.invalid') : t('sweep.noChange')}
            onClick={() =>
              hr.ask({
                title: t('sweep.saveTitle'),
                description: t('sweep.saveDesc', { v: s.version + 1 }),
                confirmText: t('common.save'),
                onConfirm: (v) => updateSweepSettings({ ...draft, reason: v.reason, expectedVersion: s.version }),
                onDone: reload,
              })
            }
          >
            {t('common.save')}
          </PermButton>
        </div>
      </section>
      {hr.element}
    </>
  );
}
