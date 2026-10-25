/**
 * 原型专用面板（右下角）："原型场景"切换、KYC 状态、模拟推送。只在 mock 模式显示（VITE_API_MODE=mock）。
 * forecast S1 删除了复制源的下单拒绝模拟和止盈止损、强平触发；下注、结算的模拟在原型阶段（M1）加。
 */
import { useState, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import {
  getProtoState,
  protoKycStatus,
  protoSetKyc,
  resetPrototypeData,
  setProtoState,
  simulatePush,
  subscribeProto,
  type PushKind,
  type Scenario,
} from '../api/prototype';
import { IS_MOCK } from '../api/mode';
import type { KycStatus } from '../api/types';
import { useToast } from '../app/contexts';
import { Icon } from './Icon';

const SCENARIOS: Scenario[] = ['normal', 'empty', 'slow', 'error'];
const KYC: KycStatus[] = ['none', 'pending', 'approved', 'rejected'];

export function ProtoPanel() {
  return IS_MOCK ? <ProtoPanelBody /> : null;
}

function ProtoPanelBody() {
  const { t } = useTranslation();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const state = useSyncExternalStore(subscribeProto, getProtoState, getProtoState);
  const kyc = protoKycStatus();

  return (
    <div className={`proto-panel${open ? ' open' : ''}`}>
      {open && (
        <section className="proto-box" aria-label={t('proto.title')}>
          <header>
            <b>{t('proto.title')}</b>
            <button type="button" className="icon-btn" onClick={() => setOpen(false)} aria-label={t('common.close')}>
              <Icon name="close" size={14} strokeWidth={2.4} />
            </button>
          </header>
          <fieldset>
            <legend>{t('proto.scenario')}</legend>
            <div className="proto-grid">
              {SCENARIOS.map((s) => (
                <label key={s} className={`proto-opt${state.scenario === s ? ' on' : ''}`}>
                  <input type="radio" name="proto-scenario" checked={state.scenario === s} onChange={() => setProtoState({ scenario: s })} />
                  {t(`proto.scenarios.${s}`)}
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset>
            <legend>{t('proto.kyc')}</legend>
            <div className="proto-grid">
              {KYC.map((k) => (
                <label key={k} className={`proto-opt${kyc === k ? ' on' : ''}`}>
                  <input
                    type="radio"
                    name="proto-kyc"
                    checked={kyc === k}
                    onChange={() => {
                      protoSetKyc(k);
                      setProtoState({});
                    }}
                  />
                  {t(`kyc.status.${k}`)}
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset>
            <legend>{t('proto.push')}</legend>
            <div className="proto-grid">
              {(['deposit', 'withdraw'] as PushKind[]).map((k) => (
                <button key={k} type="button" className="btn btn-outline btn-xs" onClick={() => simulatePush(k)}>
                  {t(`proto.pushKinds.${k}`)}
                </button>
              ))}
            </div>
            <p className="proto-note">{t('proto.pushNote')}</p>
          </fieldset>
          <div className="proto-grid">
            <button type="button" className="btn btn-ghost btn-xs" onClick={() => window.dispatchEvent(new Event('fc:show-install'))}>
              {t('proto.showInstall')}
            </button>
            <button
              type="button"
              className="btn btn-ghost btn-xs"
              onClick={() => {
                resetPrototypeData();
                setProtoState({ scenario: 'normal' });
                toast.show({ tone: 'success', title: t('proto.resetDone') });
              }}
            >
              {t('proto.reset')}
            </button>
          </div>
        </section>
      )}
      <button type="button" className="proto-fab" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        <Icon name="flask" size={14} strokeWidth={2.2} />
        <span className="proto-fab-t">{t('proto.title')}</span>
        {state.scenario !== 'normal' && <span className="proto-dot">{t(`proto.scenarios.${state.scenario}`)}</span>}
      </button>
    </div>
  );
}
