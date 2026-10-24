/**
 * v2 资金归集（技术方案/v2-半自动归集.md 第 6 节）四个页面共用的外壳：标题、页内导航（归集记录 / 生成·执行归集 / 归集设置）、返回对账页。
 * SWEEP_UI = false（总开关）时只显示"未上线"，不调用任何接口；原型说明只在原型模式显示。
 */
import type { ReactNode } from 'react';
import { Button, Segmented } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../../../components/PageHeader';
import { Pill, type PillTone } from '../../../components/Pill';
import { SWEEP_BASE, SWEEP_UI } from '../../../config/sweep';
import { IS_MOCK } from '../../../api/mode';
import type { SweepBatchStatus, SweepItemStatus } from '../../../api/types';

export type SweepTab = 'records' | 'new' | 'settings';

export const BATCH_TONE: Record<SweepBatchStatus, PillTone> = {
  pending: 'pend',
  sent: 'pend',
  confirmed: 'ok',
  partial: 'warn',
  failed: 'danger',
  deferred: 'delay',
  topping_up: 'pend',
  awaiting_signature: 'pend',
  signed: 'info',
  broadcasting: 'info',
  cancelled: 'muted',
  expired: 'muted',
};

export const ITEM_TONE: Record<SweepItemStatus, PillTone> = {
  pending: 'pend',
  swept: 'ok',
  failed: 'danger',
  topup_sent: 'pend',
  ready: 'pend',
  signed: 'info',
  sign_rejected: 'danger',
  broadcast: 'info',
  expired: 'muted',
  cancelled: 'muted',
};

export function BatchStatus({ status }: { status: SweepBatchStatus }) {
  const { t } = useTranslation();
  return <Pill tone={BATCH_TONE[status]}>{t(`sweepStatus.${status}`)}</Pill>;
}

export function SweepShell({ tab, title, sub, extra, children }: { tab: SweepTab; title?: ReactNode; sub?: ReactNode; extra?: ReactNode; children: ReactNode }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const back = (
    <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/funds/reconciliation')}>
      {t('sweep.backRecon')}
    </Button>
  );
  if (!SWEEP_UI) {
    return (
      <>
        <PageHeader title={t('sweep.title')} extra={back} />
        <div className="card card-pad sweep-notlive">
          <Pill tone="muted">{t('sweep.notLive')}</Pill>
          <p className="muted">{t('sweep.notLiveDesc')}</p>
        </div>
      </>
    );
  }
  const paths: Record<SweepTab, string> = { records: SWEEP_BASE, new: `${SWEEP_BASE}/new`, settings: `${SWEEP_BASE}/settings` };
  return (
    <>
      <PageHeader
        title={title ?? t('sweep.title')}
        sub={sub ?? t('sweep.sub')}
        extra={
          <>
            {extra}
            {back}
          </>
        }
      />
      <div className="sweep-nav">
        <Segmented
          aria-label={t('sweep.title')}
          value={tab}
          onChange={(v) => navigate(paths[v as SweepTab])}
          options={(['records', 'new', 'settings'] as const).map((k) => ({ value: k, label: t(`sweep.tab.${k}`) }))}
        />
        {IS_MOCK ? <span className="muted sweep-proto-note">{t('sweep.protoNote')}</span> : null}
      </div>
      {children}
    </>
  );
}
