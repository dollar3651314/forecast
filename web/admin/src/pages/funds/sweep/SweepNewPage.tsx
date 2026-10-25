/**
 * 资金归集 · 生成 / 执行归集（方案第 4.5、4.6 节和附录 B.3）：选链 → 预览（冷钱包、门槛、gas 价格、每个地址的余额和手续费、跳过原因、合计）。
 * 以太坊、BSC："执行归集"——服务器用发起账户签一笔批量交易；TRON："生成待签名清单"——没激活的地址热钱包转 1 TRX 激活，之后在批次详情页下载、离线签名、导入；
 * 广播前系统给每个地址租能量（预览里显示租赁平台、单价和每个地址的预计租金），确认到账再广播。
 * 两种都走统一的高危确认（原因必填，审计 sweep.run）。
 * 真实接口模式（第一阶段）：以太坊、BSC 接后端实时预览；TRON 显示"下一阶段"、不调用接口。
 */
import { useCallback } from 'react';
import { Alert, Segmented, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { previewSweep, runSweep } from '../../../api';
import type { SweepCandidate, SweepChain } from '../../../api/types';
import { useApi } from '../../../hooks/useApi';
import { useHighRisk } from '../../../hooks/useConfirmDialogs';
import { ErrorState } from '../../../components/ErrorState';
import { PermButton } from '../../../components/PermButton';
import { Amount, Num } from '../../../components/Num';
import { Pill } from '../../../components/Pill';
import { shortHash } from '../../../utils/display';
import { SWEEP_BASE, SWEEP_CHAINS, SWEEP_MODE, SWEEP_NEXT_PHASE, SWEEP_PERM, SWEEP_UI, sweepChainLive } from '../../../config/sweep';
import { SweepShell } from './SweepShell';

export function SweepNewPage() {
  return <SweepShell tab="new">{SWEEP_UI ? <Preview /> : null}</SweepShell>;
}

function Preview() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const raw = params.get('chain');
  const chain: SweepChain = SWEEP_CHAINS.includes(raw as SweepChain) ? (raw as SweepChain) : SWEEP_NEXT_PHASE ? 'ERC20' : 'TRC20';
  const live = sweepChainLive(chain);
  return (
    <>
      <div className="card card-pad" style={{ marginBottom: 12 }}>
        <div className="card-title">
          <Segmented
            aria-label={t('sweep.chain')}
            value={chain}
            onChange={(v) => setParams({ chain: String(v) })}
            options={SWEEP_CHAINS.map((c) => ({ value: c, label: `${c} · ${sweepChainLive(c) ? t(`sweepMode.${SWEEP_MODE[c]}`) : t('sweep.nextPhase')}` }))}
          />
        </div>
        {live ? (
          <Alert type={SWEEP_MODE[chain] === 'offline' ? 'warning' : 'info'} showIcon message={t(SWEEP_MODE[chain] === 'offline' ? 'sweep.offlineHint' : 'sweep.contractHint')} />
        ) : (
          <Alert type="info" showIcon message={t('sweep.nextPhaseTron')} />
        )}
      </div>
      {live ? <ChainPreview key={chain} chain={chain} /> : null}
    </>
  );
}

function ChainPreview({ chain }: { chain: SweepChain }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const fetcher = useCallback(() => previewSweep(chain), [chain]);
  const { data, loading, error, reload } = useApi(fetcher);
  const hr = useHighRisk();
  const offline = SWEEP_MODE[chain] === 'offline';
  const rentMode = data?.tronRent?.mode === 'rent';

  const run = () =>
    data &&
    hr.ask({
      title: offline ? t('sweep.prepareTitle') : t('sweep.runTitle'),
      description: (
        <div>
          <div>{t(offline ? 'sweep.prepareDesc' : 'sweep.runDesc', { chain, count: data.totals.count, usdt: data.totals.usdt })}</div>
          <div className="muted" style={{ marginTop: 6 }}>
            {t('sweep.destination')}：<Num>{data.destination ?? '—'}</Num>
          </div>
        </div>
      ),
      confirmText: offline ? t('sweep.prepare') : t('sweep.run'),
      onConfirm: async (v) => {
        const r = await runSweep(chain, v.reason);
        navigate(`${SWEEP_BASE}/batches/${r.no}`);
      },
    });

  return (
    <>
      {error ? (
        <div className="card">
          <ErrorState error={error} onRetry={reload} />
        </div>
      ) : (
        <>
          <div className="card card-pad" style={{ marginBottom: 12 }}>
            <div className="kv-grid sweep-kv4">
              <div>
                {t('sweep.destination')}
                <Num>{data?.destination ?? '—'}</Num>
              </div>
              <div>
                {t('sweep.threshold')}
                <Num>{data ? `${data.threshold} USDT` : '—'}</Num>
              </div>
              <div>
                {t('sweep.gasPrice')}
                <Num>{data?.gasPrice ?? '—'}</Num>
              </div>
              <div>
                {t('sweep.maxFeeRatio')}
                <Num>{data ? `${data.maxFeeRatioPct}%` : '—'}</Num>
              </div>
            </div>
            {data?.warnings?.includes('priceMissing') ? <Alert type="warning" showIcon style={{ marginTop: 10 }} message={t('sweep.priceMissing')} /> : null}
            {data?.tronRent ? (
              <Alert
                type={rentMode ? 'success' : 'warning'}
                showIcon
                style={{ marginTop: 10 }}
                message={
                  rentMode
                    ? t('sweep.rentQuote', { provider: data.tronRent.provider, price: data.tronRent.priceSun, energy: data.tronRent.energyPerItem.toLocaleString('en-US'), min: data.tronRent.durationMin, burn: data.tronRent.burnSun })
                    : t('sweep.burnModeHint')
                }
                description={rentMode ? t('sweep.rentFlowHint') : undefined}
              />
            ) : null}
          </div>
          <div className="card" style={{ marginBottom: 12 }}>
            <Table<SweepCandidate>
              size="middle"
              rowKey="address"
              loading={loading}
              dataSource={data?.candidates ?? []}
              pagination={false}
              scroll={{ x: 'max-content' }}
              locale={{ emptyText: t('sweep.emptyCandidates') }}
              rowClassName={(r) => (r.skip ? 'sweep-row-skip' : '')}
              columns={[
                { title: t('funds.user'), dataIndex: 'customerName', render: (v: string, r) => <div className="user-cell"><b className="ellipsis" style={{ maxWidth: 180 }} title={v}>{v}</b><small>ID {r.userId}</small></div> },
                { title: t('sweep.address'), dataIndex: 'address', render: (v: string) => <Num className="muted"><span title={v}>{shortHash(v, 8, 6)}</span></Num> },
                { title: t('sweep.usdt'), dataIndex: 'usdt', align: 'right', render: (v: string) => <Amount value={v} strong /> },
                ...(offline
                  ? [
                      { title: t('sweep.activation'), dataIndex: 'activate', render: (v: boolean, r: SweepCandidate) => (v ? <Pill tone="delay">{t('sweep.needActivate', { trx: r.topup })}</Pill> : <span className="muted">{t('sweep.activated')}</span>) },
                      rentMode
                        ? { title: t('sweep.estRent'), dataIndex: 'rentTrx', align: 'right' as const, render: (v: string) => <Num>{`${v} TRX`}</Num> }
                        : { title: t('sweep.topup'), dataIndex: 'topup', align: 'right' as const, render: (v: string) => <Num>{v === '0.00' ? t('sweep.noTopup') : `${v} TRX`}</Num> },
                    ]
                  : [{ title: t('sweep.deploy'), dataIndex: 'deploy', render: (v: boolean) => (v ? <Pill tone="delay">{t('sweep.firstDeploy')}</Pill> : <span className="muted">{t('sweep.deployed')}</span>) }]),
                { title: t('sweep.estFee'), dataIndex: 'estFee', align: 'right', render: (v: string, r) => <span><Num>{`${v} ${data?.gasAsset ?? ''}`}</Num><small className="muted" style={{ display: 'block' }}>{r.estFeeUsd === '—' ? '—' : `≈ $${r.estFeeUsd}`}</small></span> },
                { title: t('sweep.result'), dataIndex: 'skip', render: (v: SweepCandidate['skip']) => (v ? <Pill tone="muted">{t(`sweepSkip.${v}`)}</Pill> : <Pill tone="ok">{t('sweep.willSweep')}</Pill>) },
              ]}
            />
          </div>
          {data ? (
            <section className="card card-pad sweep-total" aria-label={t('sweep.totals')}>
              <div className="card-title">{t('sweep.totals')}</div>
              <div className="kv-grid sweep-kv4">
                <div>
                  {t('sweep.addresses')}
                  <Num strong>{data.totals.count}</Num>
                </div>
                <div>
                  {t('sweep.usdtTotal')}
                  <Num strong>{`${data.totals.usdt} USDT`}</Num>
                </div>
                <div>
                  {t('sweep.feeTotal')}
                  <Num>{data.totals.feeUsd === '—' ? `${data.totals.fee} ${data.gasAsset}` : `${data.totals.fee} ${data.gasAsset} ≈ $${data.totals.feeUsd}（${data.totals.feePct}%）`}</Num>
                </div>
                {offline ? (
                  <div>
                    {rentMode ? t('sweep.rentTotal') : t('sweep.topupTotal')}
                    <Num>{rentMode ? `${data.totals.rent} TRX · ${t('sweep.activateTotal', { trx: data.totals.topup })}` : `${data.totals.topup} TRX`}</Num>
                  </div>
                ) : (
                  <div>
                    {t('sweep.skipped')}
                    <Num>{t('common.countItems', { count: data.totals.skipped })}</Num>
                  </div>
                )}
              </div>
              <div className="sweep-actions">
                <PermButton perm={SWEEP_PERM.operate} type="primary" danger disabled={!data.totals.count} disabledReason={t('sweep.nothing')} onClick={run}>
                  {offline ? t('sweep.prepare') : t('sweep.run')}
                </PermButton>
              </div>
            </section>
          ) : null}
        </>
      )}
      {hr.element}
    </>
  );
}
