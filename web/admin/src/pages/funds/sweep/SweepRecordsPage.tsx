/**
 * 资金归集 · 归集记录（方案第 6 节）：每条链一张卡片（方式、待归集金额、达到门槛的地址数、冷钱包、发起账户余额、上次归集），下面是批次列表。
 * 真实接口模式：TRON 卡片显示"下一阶段"；以太坊、BSC 不能执行时（没配合约地址、发起账户等）卡片上写原因。
 */
import { useCallback, useState } from 'react';
import { Alert, Button, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { getSweepOverview, listSweepBatches } from '../../../api';
import type { SweepBatch, SweepBatchQuery, SweepChain, SweepOverview } from '../../../api/types';
import { useApi } from '../../../hooks/useApi';
import { usePagedList } from '../../../hooks/usePagedList';
import { DataTable } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { FilterBar, FilterItem } from '../../../components/FilterBar';
import { Amount, Num } from '../../../components/Num';
import { ChainTag, Pill } from '../../../components/Pill';
import { shortHash, shortTime } from '../../../utils/display';
import { SWEEP_BASE, SWEEP_CHAINS, SWEEP_UI, sweepChainLive } from '../../../config/sweep';
import { BatchStatus, SweepShell } from './SweepShell';

type Filters = Omit<SweepBatchQuery, 'page' | 'pageSize'>;

export function SweepRecordsPage() {
  return <SweepShell tab="records">{SWEEP_UI ? <Records /> : null}</SweepShell>;
}

function ChainCard({ o }: { o: SweepOverview }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  if (!sweepChainLive(o.chain)) {
    return (
      <section className="card card-pad sweep-chain-card" aria-label={o.chain}>
        <div className="card-title">
          <span>
            <ChainTag chain={o.chain} /> <Pill tone="admin">{t(`sweepMode.${o.mode}`)}</Pill>
          </span>
          <Pill tone="muted">{t('sweep.nextPhase')}</Pill>
        </div>
        <p className="muted" style={{ margin: 0 }}>
          {t('sweep.nextPhaseTron')}
        </p>
      </section>
    );
  }
  return (
    <section className="card card-pad sweep-chain-card" aria-label={o.chain}>
      <div className="card-title">
        <span>
          <ChainTag chain={o.chain} /> <Pill tone={o.mode === 'contract' ? 'ok' : 'admin'}>{t(`sweepMode.${o.mode}`)}</Pill>
        </span>
        {o.activeBatch ? (
          <Button size="small" type="link" onClick={() => navigate(`${SWEEP_BASE}/batches/${o.activeBatch}`)}>
            {t('sweep.activeBatch')}
          </Button>
        ) : null}
      </div>
      {o.available === false && o.unavailable ? <Alert type="warning" showIcon style={{ marginBottom: 8 }} message={o.unavailable} /> : null}
      <div className="sweep-big">
        <small className="muted">{t('sweep.pendingUsdt')}</small>
        <Amount value={o.pendingUsdt} strong />
        <span className="muted"> USDT</span>
      </div>
      <div className="kv-grid">
        <div>
          {t('sweep.eligible')}
          <Num>{t('common.countItems', { count: o.eligibleCount })}</Num>
        </div>
        <div>
          {t('sweep.below')}
          <Num>{t('common.countItems', { count: o.belowCount })}</Num>
        </div>
        <div>
          {t('sweep.destination')}
          <Num>
            <span title={o.destination ?? ''}>{shortHash(o.destination, 8, 6)}</span>
          </Num>
        </div>
        <div>
          {o.mode === 'contract' ? t('sweep.operatorBalance') : t('sweep.gasPayer')}
          <Num>{o.operatorBalance !== null ? `${o.operatorBalance} ${o.gasAsset}` : o.mode === 'contract' ? '—' : t('sweep.hotWalletTopup')}</Num>
        </div>
        <div>
          {t('sweep.lastSweep')}
          <Num>{shortTime(o.lastSweepAt)}</Num>
        </div>
        {o.checkedAt !== undefined ? (
          <div>
            {t('sweep.checkedAt')}
            <Num>{shortTime(o.checkedAt ?? null)}</Num>
          </div>
        ) : null}
      </div>
      <Button block style={{ marginTop: 10 }} onClick={() => navigate(`${SWEEP_BASE}/new?chain=${o.chain}`)}>
        {o.mode === 'contract' ? t('sweep.goRun') : t('sweep.goPrepare')}
      </Button>
    </section>
  );
}

function Records() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const fetcher = useCallback(() => getSweepOverview(), []);
  const overview = useApi(fetcher);
  const list = usePagedList<SweepBatch, Filters>(listSweepBatches, {});
  const [draft, setDraft] = useState<Filters>({});

  return (
    <>
      {overview.error ? (
        <div className="card">
          <ErrorState error={overview.error} onRetry={overview.reload} />
        </div>
      ) : (
        <div className="sweep-cards">{(overview.data ?? []).map((o) => <ChainCard key={o.chain} o={o} />)}</div>
      )}
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft({}); list.setFilters({}); }}>
        <FilterItem label={t('sweep.chain')}>
          <Select
            aria-label={t('sweep.chain')}
            allowClear
            style={{ width: 120 }}
            placeholder={t('common.all')}
            value={draft.chain}
            onChange={(v: SweepChain | undefined) => setDraft({ ...draft, chain: v })}
            options={SWEEP_CHAINS.map((c) => ({ value: c, label: c }))}
          />
        </FilterItem>
      </FilterBar>
      <DataTable<SweepBatch>
        rowKey="no"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total ?? 0}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        emptyText={t('sweep.emptyBatches')}
        columns={[
          { title: t('sweep.batchNo'), dataIndex: 'no', render: (v: string) => <Num>{v}</Num> },
          { title: t('sweep.chain'), dataIndex: 'chain', render: (v: string) => <ChainTag chain={v} /> },
          { title: t('sweep.mode'), dataIndex: 'mode', render: (v: SweepBatch['mode'], r) => <span>{t(`sweepMode.${v}`)}<small className="muted" style={{ display: 'block' }}>{t(`sweepTrigger.${r.trigger}`)}</small></span> },
          { title: t('sweep.addresses'), dataIndex: 'addressCount', align: 'right', render: (v: number) => <Num>{v}</Num> },
          { title: t('sweep.expectedUsdt'), dataIndex: 'expectedUsdt', align: 'right', render: (v: string) => <Amount value={v} /> },
          { title: t('sweep.sweptUsdt'), dataIndex: 'sweptUsdt', align: 'right', render: (v: string) => <Amount value={v} strong /> },
          { title: t('sweep.fee'), dataIndex: 'fee', align: 'right', render: (v: string, r) => <Num>{`${v} ${r.gasAsset}`}</Num> },
          { title: t('sweep.status'), dataIndex: 'status', render: (v: SweepBatch['status']) => <BatchStatus status={v} /> },
          { title: t('sweep.createdBy'), dataIndex: 'createdBy', render: (v: string) => (v === 'system' ? t('sweep.system') : v) },
          { title: t('sweep.createdAt'), dataIndex: 'createdAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) => (
              <Button size="small" onClick={() => navigate(`${SWEEP_BASE}/batches/${r.no}`)}>
                {t('common.detail')}
              </Button>
            ),
          },
        ]}
      />
    </>
  );
}
