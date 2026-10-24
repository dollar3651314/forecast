/**
 * 新手引导（沿用复制源的组件）：可跳过；终点是"完成身份认证 / 充值"。新客户（认证未提交）第一次登录自动打开一次；之后在"我的 → 新手引导"重看。
 * forecast S1：复制源第 4 步"怎么下单"删除，先是 3 步 ① 欢迎 ② 身份认证 ③ 充值 USDT；
 * 需求的 4 步（这里押什么 → 赔率怎么算、只对盈利抽成 → 认证 → 充值）在 S11 做（技术方案/v1-架构方案.md 第 9.4 节）。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { getKyc } from '../api/user';
import { useAuth } from '../app/contexts';
import { useAsync } from '../hooks/useAsync';
import { onboardingDone, useOnboarding } from '../hooks/useOnboarding';
import { currentLang } from '../i18n';
import { BRAND_NAME } from '../lib/brand';
import { Icon, type IconName } from './Icon';
import { Modal } from './Modal';

const STEPS: Array<{ key: string; icon: IconName }> = [
  { key: 'welcome', icon: 'checkCircle' },
  { key: 'kyc', icon: 'shield' },
  { key: 'deposit', icon: 'deposit' },
];

export function Onboarding() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { session } = useAuth();
  const userId = session?.userId;
  const { forcedOpen, finish } = useOnboarding(userId);
  const kyc = useAsync(() => getKyc(currentLang()), `kyc|${i18n.language}`);
  const [closed, setClosed] = useState(false);
  const [step, setStep] = useState(0);

  const auto = !!userId && kyc.data?.status === 'none' && !onboardingDone(userId) && !closed;
  const open = forcedOpen || auto;
  if (!open) return null;

  const kycDone = kyc.data?.status === 'approved' || kyc.data?.status === 'pending';
  const done = (to?: string) => {
    finish();
    setClosed(true);
    setStep(0);
    if (to) navigate(to);
  };
  const s = STEPS[step];
  const last = step === STEPS.length - 1;
  const items = t(`onboarding.${s.key}.items`, { returnObjects: true, brand: BRAND_NAME }) as unknown;
  const list = Array.isArray(items) ? (items as string[]) : [];

  return (
    <Modal
      open
      variant="dialog"
      size="sm"
      title={<span className="ob-title">{t('onboarding.title')}</span>}
      subtitle={t('onboarding.progress', { n: step + 1, total: STEPS.length })}
      onClose={() => done()}
      footer={
        <div className="ob-foot">
          {last ? (
            <button type="button" className="btn btn-ghost" onClick={() => done()}>
              {t('onboarding.later')}
            </button>
          ) : (
            <button type="button" className="btn btn-ghost" onClick={() => done()}>
              {t('onboarding.skip')}
            </button>
          )}
          <div className="ob-nav">
            {step > 0 && (
              <button type="button" className="btn btn-ghost" onClick={() => setStep(step - 1)}>
                {t('onboarding.prev')}
              </button>
            )}
            {last ? (
              <button type="button" className="btn btn-primary" data-autofocus onClick={() => done(kycDone ? '/assets' : '/me/kyc')}>
                {kycDone ? t('onboarding.goDeposit') : t('onboarding.goKyc')}
              </button>
            ) : (
              <button type="button" className="btn btn-primary" data-autofocus onClick={() => setStep(step + 1)}>
                {t('onboarding.next')}
              </button>
            )}
          </div>
        </div>
      }
    >
      <div className="ob" data-step={s.key}>
        <ol className="ob-dots" aria-hidden="true">
          {STEPS.map((x, i) => (
            <li key={x.key} className={i === step ? 'on' : i < step ? 'done' : ''} />
          ))}
        </ol>
        <div className="ob-icon" aria-hidden="true">
          <Icon name={s.icon} size={30} strokeWidth={1.8} />
        </div>
        <h3>{t(`onboarding.${s.key}.title`, { brand: BRAND_NAME })}</h3>
        <p>{t(`onboarding.${s.key}.body`)}</p>
        {list.length > 0 && (
          <ul className="ob-list">
            {list.map((x, i) => (
              <li key={i}>
                <span className="ob-n num">{i + 1}</span>
                {x}
              </li>
            ))}
          </ul>
        )}
        {s.key === 'kyc' && (
          <button type="button" className="btn btn-sm btn-ghost ob-inline" onClick={() => done('/me/kyc')}>
            {t('onboarding.kycNow')}
            <Icon name="chevronRight" size={14} />
          </button>
        )}
        {s.key === 'deposit' && (
          <button type="button" className="btn btn-sm btn-ghost ob-inline" onClick={() => done('/assets')}>
            {t('onboarding.depositNow')}
            <Icon name="chevronRight" size={14} />
          </button>
        )}
        <p className="ob-note">{t(`onboarding.${s.key}.note`)}</p>
      </div>
    </Modal>
  );
}
