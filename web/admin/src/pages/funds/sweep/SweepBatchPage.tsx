/**
 * 资金归集 · 批次详情（方案第 4.6 节和附录 B.9）。
 * - 以太坊、BSC（合约）：批量交易哈希、每个地址的结果、失败的"重试"；
 * - TRON（离线签名）：步骤（激活地址 → 等待签名 → 已导入 → 租能量并广播 → 完成）、有效期、下载待签名文件、导入签名文件（逐笔核对结果）、
 *   广播（先给每个地址租能量、确认到账再广播；没到账的回退到热钱包补 TRX）、每个地址的租赁状态和租金、作废。
 *   原型里有"使用演示签名文件"按钮（最后一笔故意改过，演示"签名内容和清单不一致"）。
 */
import { useCallback, useRef, useState } from 'react';
import { Alert, Button, Steps, Table } from 'antd';
import { DownloadOutlined, UploadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';
import {
  broadcastSweep,
  cancelSweepBatch,
  demoSweepSignedFile,
  downloadSweepUnsigned,
  getSweepBatch,
  importSweepSigned,
  IS_MOCK,
  retrySweepBatch,
} from '../../../api';
import type { SweepBatchDetail, SweepImportResult, SweepItem, SweepRent, SweepRentStatus } from '../../../api/types';
import { useApi } from '../../../hooks/useApi';
import { useHighRisk } from '../../../hooks/useConfirmDialogs';
import { ErrorState } from '../../../components/ErrorState';
import { PermButton } from '../../../components/PermButton';
import { Amount, Num } from '../../../components/Num';
import { ChainTag, Pill, type PillTone } from '../../../components/Pill';
import { fullTime, shortHash, shortTime } from '../../../utils/display';
import { errorText } from '../../../utils/errors';
import { SWEEP_BASE, SWEEP_PERM, SWEEP_UI } from '../../../config/sweep';
import { BatchStatus, ITEM_TONE, SweepShell } from './SweepShell';

export function SweepBatchPage() {
  const { t } = useTranslation();
  const { no = '' } = useParams();
  return (
    <SweepShell tab="records" title={t('sweep.batchTitle')} sub={no}>
      {SWEEP_UI ? <Batch no={no} /> : null}
    </SweepShell>
  );
}

const OFFLINE_STEPS = ['topup', 'awaiting', 'imported', 'broadcast', 'done'] as const;

const RENT_TONE: Record<SweepRentStatus, PillTone> = {
  none: 'muted',
  ordered: 'pend',
  delivered: 'ok',
  fallbackBurn: 'warn',
  notDelivered: 'danger',
  expired: 'muted',
};

function offlineStep(d: SweepBatchDetail): number {
  switch (d.status) {
    case 'topping_up':
      return 0;
    case 'awaiting_signature':
      return 1;
    case 'signed':
      return 2;
    case 'broadcasting':
      return 3;
    default:
      return 4;
  }
}

function saveFile(name: string, content: string) {
  try {
    const url = URL.createObjectURL(new Blob([content], { type: 'application/json' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
    URL.revokeObjectURL(url);
  } catch {
    /* 测试环境（jsdom）没有 createObjectURL，忽略 */
  }
}

/** 原型给的是翻译键的后缀；真实接口给的是后端写好的中文说明，原样显示 */
function useLabel() {
  const { t, i18n } = useTranslation();
  return (prefix: string, v: string) => (/^[A-Za-z_]+$/.test(v) && i18n.exists(`${prefix}.${v}`) ? t(`${prefix}.${v}`) : v);
}

function Batch({ no }: { no: string }) {
  const { t } = useTranslation();
  const label = useLabel();
  const navigate = useNavigate();
  const fetcher = useCallback(() => getSweepBatch(no), [no]);
  const { data: d, loading, error, reload } = useApi(fetcher);
  const hr = useHighRisk();
  const fileRef = useRef<HTMLInputElement>(null);
  const [importResult, setImportResult] = useState<SweepImportResult | null>(null);
  const [importError, setImportError] = useState<unknown>(null);

  if (error) {
    return (
      <div className="card">
        <ErrorState error={error} onRetry={reload} />
      </div>
    );
  }
  if (!d) return <div className="card card-pad">{loading ? t('common.loading') : null}</div>;

  const offline = d.mode === 'offline';
  const canSign = offline && (d.status === 'awaiting_signature' || d.status === 'signed');

  const doImport = async (text: string) => {
    setImportError(null);
    setImportResult(null);
    try {
      setImportResult(await importSweepSigned(d.no, text));
      reload();
    } catch (e) {
      setImportError(e);
    }
  };

  const onFile = async (f: File | undefined) => {
    if (f) await doImport(await f.text());
    if (fileRef.current) fileRef.current.value = '';
  };

  const signedCount = d.items.filter((i) => i.status === 'signed').length;
  const rentTotal = d.items.reduce((s, i) => s + (i.rent && i.rent.status === 'delivered' ? Number(i.rent.trx) : 0), 0);
  const burnTotal = d.items.reduce((s, i) => s + (i.rent && i.rent.status === 'fallbackBurn' ? Number(i.rent.trx) : 0), 0);

  return (
    <>
      <div className="card card-pad" style={{ marginBottom: 12 }}>
        <div className="card-title">
          <span>
            <ChainTag chain={d.chain} /> <Pill tone={offline ? 'admin' : 'ok'}>{t(`sweepMode.${d.mode}`)}</Pill> <BatchStatus status={d.status} />
          </span>
          <span className="sweep-actions">
            {offline ? (
              <>
                <PermButton
                  perm={SWEEP_PERM.operate}
                  icon={<DownloadOutlined />}
                  onClick={async () => {
                    const f = await downloadSweepUnsigned(d.no);
                    saveFile(f.fileName, f.content);
                  }}
                >
                  {t('sweep.downloadUnsigned')}
                </PermButton>
                <PermButton perm={SWEEP_PERM.operate} icon={<UploadOutlined />} disabled={!canSign} disabledReason={t('sweep.notSignable')} onClick={() => fileRef.current?.click()}>
                  {t('sweep.importSigned')}
                </PermButton>
                <input ref={fileRef} type="file" accept=".json,application/json" hidden aria-label={t('sweep.importSigned')} onChange={(e) => void onFile(e.target.files?.[0])} />
                {IS_MOCK && canSign ? (
                  <Button onClick={async () => doImport(await demoSweepSignedFile(d.no))}>{t('sweep.useDemoFile')}</Button>
                ) : null}
                <PermButton
                  perm={SWEEP_PERM.operate}
                  type="primary"
                  danger
                  disabled={d.status !== 'signed' || !signedCount}
                  disabledReason={t('sweep.importFirst')}
                  onClick={() =>
                    hr.ask({
                      title: t('sweep.broadcastTitle'),
                      description: t('sweep.broadcastDesc', { count: signedCount }),
                      confirmText: t('sweep.broadcast'),
                      onConfirm: (v) => broadcastSweep(d.no, v.reason),
                      onDone: reload,
                    })
                  }
                >
                  {t('sweep.broadcast')}
                </PermButton>
                <PermButton
                  perm={SWEEP_PERM.operate}
                  danger
                  disabled={!canSign}
                  disabledReason={t('sweep.notSignable')}
                  onClick={() =>
                    hr.ask({
                      title: t('sweep.cancelTitle'),
                      description: t('sweep.cancelDesc'),
                      confirmText: t('sweep.cancelBatch'),
                      onConfirm: (v) => cancelSweepBatch(d.no, v.reason),
                      onDone: reload,
                    })
                  }
                >
                  {t('sweep.cancelBatch')}
                </PermButton>
              </>
            ) : (
              <PermButton
                perm={SWEEP_PERM.operate}
                disabled={!['partial', 'failed', 'deferred'].includes(d.status)}
                disabledReason={t('sweep.noRetry')}
                onClick={() =>
                  hr.ask({
                    title: t('sweep.retryTitle'),
                    description: t('sweep.retryDesc'),
                    confirmText: t('sweep.retry'),
                    onConfirm: async (v) => {
                      const r = await retrySweepBatch(d.no, v.reason);
                      navigate(`${SWEEP_BASE}/batches/${r.no}`);
                    },
                  })
                }
              >
                {t('sweep.retry')}
              </PermButton>
            )}
          </span>
        </div>
        {offline ? (
          <Steps
            size="small"
            current={offlineStep(d)}
            status={d.status === 'cancelled' || d.status === 'expired' || d.status === 'failed' ? 'error' : d.status === 'partial' ? 'error' : undefined}
            items={OFFLINE_STEPS.map((s) => ({ title: t(`sweep.step.${s}`) }))}
            style={{ margin: '4px 0 12px' }}
          />
        ) : null}
        {d.failReason ? <Alert type={d.status === 'partial' ? 'warning' : 'error'} showIcon style={{ marginBottom: 12 }} message={`${t('sweep.batchFailReason')}：${d.failReason}`} /> : null}
        {offline && canSign && d.expiresAt ? <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('sweep.expiresHint', { time: fullTime(d.expiresAt) })} /> : null}
        {offline && d.status === 'signed' ? <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('sweep.rentBeforeBroadcast')} /> : null}
        {importError ? <Alert type="error" showIcon style={{ marginBottom: 12 }} message={t('sweep.importFailed')} description={errorText(t, importError)} /> : null}
        {importResult ? (
          <Alert
            type={importResult.rejected.length ? 'warning' : 'success'}
            showIcon
            style={{ marginBottom: 12 }}
            message={t('sweep.importResult', { accepted: importResult.accepted, rejected: importResult.rejected.length })}
            description={importResult.rejected.length ? importResult.rejected.map((r) => `#${r.itemNo} ${t(`sweepFail.${r.reason}`)}`).join('；') : undefined}
          />
        ) : null}
        <div className="kv-grid sweep-kv4">
          <div>
            {t('sweep.destination')}
            <Num>{d.destination}</Num>
          </div>
          <div>
            {t('sweep.expectedUsdt')} / {t('sweep.sweptUsdt')}
            <Num strong>{`${d.expectedUsdt} / ${d.sweptUsdt} USDT`}</Num>
          </div>
          <div>
            {t('sweep.fee')}
            <Num>{`${d.fee} ${d.gasAsset}`}</Num>
          </div>
          <div>
            {t('sweep.createdBy')}
            <Num>{`${d.createdBy === 'system' ? t('sweep.system') : d.createdBy} · ${fullTime(d.createdAt)}`}</Num>
          </div>
          {offline ? (
            <>
              <div>
                {t('sweep.expiresAt')}
                <Num>{fullTime(d.expiresAt)}</Num>
              </div>
              <div>
                {t('sweep.unsignedSha')}
                <Num>{shortHash(d.unsignedSha256, 10, 8)}</Num>
              </div>
              <div>
                {t('sweep.signedSha')}
                <Num>{shortHash(d.signedSha256, 10, 8)}</Num>
              </div>
              <div>
                {t('sweep.rentSummary')}
                <Num>{t('sweep.rentSummaryValue', { rent: rentTotal.toFixed(2), burn: burnTotal.toFixed(2) })}</Num>
              </div>
            </>
          ) : (
            <div>
              {t('sweep.txHash')}
              <Num>
                <span title={d.txHash ?? ''}>{shortHash(d.txHash, 10, 8)}</span>
              </Num>
            </div>
          )}
          <div>
            {t('sweep.reason')}
            <Num>{d.reason || t('sweep.autoRun')}</Num>
          </div>
        </div>
      </div>
      <div className="card" style={{ marginBottom: 12 }}>
        <Table<SweepItem>
          size="middle"
          rowKey="itemNo"
          dataSource={d.items}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            { title: '#', dataIndex: 'itemNo', render: (v: number) => <Num>{v}</Num> },
            { title: t('funds.user'), dataIndex: 'customerName', render: (v: string, r) => <div className="user-cell"><b className="ellipsis" style={{ maxWidth: 180 }} title={v}>{v}</b><small>ID {r.userId}</small></div> },
            { title: t('sweep.address'), dataIndex: 'address', render: (v: string) => <Num className="muted"><span title={v}>{shortHash(v, 8, 6)}</span></Num> },
            { title: t('sweep.usdt'), dataIndex: 'amount', align: 'right', render: (v: string) => <Amount value={v} strong /> },
            ...(offline
              ? [
                  { title: t('sweep.activation'), dataIndex: 'topup', render: (v: string) => (Number(v) > 0 ? <Pill tone="delay">{t('sweep.activatedWith', { trx: v })}</Pill> : <span className="muted">{t('sweep.activated')}</span>) },
                  {
                    title: t('sweep.rentCol'),
                    dataIndex: 'rent',
                    render: (v: SweepRent | null) =>
                      v ? (
                        <span>
                          <Pill tone={RENT_TONE[v.status]}>{t(`sweepRent.${v.status}`)}</Pill>
                          {v.status === 'delivered' || v.status === 'fallbackBurn' ? (
                            <small className="muted" style={{ display: 'block' }}>
                              {v.status === 'delivered' ? t('sweep.rentPaid', { trx: v.trx, energy: v.energy.toLocaleString('en-US') }) : t('sweep.burnPaid', { trx: v.trx })}
                            </small>
                          ) : null}
                        </span>
                      ) : null,
                  },
                ]
              : [{ title: t('sweep.deploy'), dataIndex: 'deploy', render: (v: boolean) => (v ? <Pill tone="delay">{t('sweep.firstDeploy')}</Pill> : <span className="muted">{t('sweep.deployed')}</span>) }]),
            {
              title: t('sweep.status'),
              dataIndex: 'status',
              render: (v: SweepItem['status'], r) => (
                <span>
                  <Pill tone={ITEM_TONE[v]}>{t(`sweepItem.${v}`)}</Pill>
                  {r.failReason ? <small style={{ display: 'block', color: 'var(--danger)' }}>{label('sweepFail', r.failReason)}</small> : null}
                </span>
              ),
            },
            { title: t('sweep.txHash'), dataIndex: 'txHash', render: (v: string | null) => <Num className="muted"><span title={v ?? ''}>{shortHash(v, 8, 6)}</span></Num> },
          ]}
        />
      </div>
      <div className="card card-pad">
        <div className="card-title">{t('sweep.events')}</div>
        <ul className="sweep-events">
          {d.events.map((e, i) => (
            <li key={i}>
              <Num className="muted">{shortTime(e.time)}</Num> <b>{e.actor === 'system' ? t('sweep.system') : e.actor}</b> {label('sweepEvent', e.text)}
            </li>
          ))}
        </ul>
      </div>
      {hr.element}
    </>
  );
}
