/**
 * 结算记录（需求第 6.2 节"结算与补偿"；后端 SettleAdminApi，S5）：结算单和退款单（市场、种类、状态、奖池、派彩、抽成、尾差、平台收入、守恒、费率）；
 * 结算异常（守恒不等、结算前对账不平、对账发现差异）停在"结算异常"、不动钱，处理完彩池对账差异后"重试"（pool_recon:resolve ★）。
 * 点一行看明细（结算单是持仓，退款单是下注，分页）。
 */
import { useCallback, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert, Descriptions, Drawer, Segmented, Space, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { getSettleRun, listSettleRuns, retrySettleRun } from '../../api';
import type { SettleRun } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { Amount, Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { utc } from '../../utils/market';

const tone = (s: SettleRun['status']) => (s === 'abnormal' ? 'danger' : s === 'done' ? 'ok' : 'pend');

function RunDrawer({ runNo, onClose, onDone }: { runNo: string | null; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const fetcher = useCallback(() => (runNo ? getSettleRun(runNo, page, size) : Promise.resolve(null)), [runNo, page, size]);
  const { data, error, reload, loading } = useApi(fetcher);
  const hr = useHighRisk();
  const r = data?.run;
  return (
    <Drawer open={!!runNo} onClose={onClose} width={Math.min(820, typeof window !== 'undefined' ? window.innerWidth : 820)} title={t('sr.detail', { no: runNo ?? '' })} destroyOnClose>
      {error ? (
        <ErrorState error={error} onRetry={reload} compact />
      ) : !r ? null : (
        <Space direction="vertical" style={{ width: '100%' }}>
          {r.status === 'abnormal' ? (
            <Alert
              type="error"
              showIcon
              message={t('sr.abnormal')}
              description={r.abnormalReason}
              action={
                <PermButton perm="pool_recon:resolve" danger onClick={() => hr.ask({ title: t('sr.retry'), description: t('sr.retryNote'), onConfirm: (v) => retrySettleRun(r.runNo, v.reason), onDone: () => (reload(), onDone()) })}>
                  {t('sr.retry')}
                </PermButton>
              }
            />
          ) : null}
          <Descriptions size="small" bordered column={{ xs: 1, sm: 2 }}>
            <Descriptions.Item label={t('sr.market')}>
              <Link to={`/markets/${r.marketNo}`}>{r.marketNo}</Link>
            </Descriptions.Item>
            <Descriptions.Item label={t('sr.kind')}>{t(`sr.k.${r.kind}`)}</Descriptions.Item>
            <Descriptions.Item label={t('sr.status')}>
              <Pill tone={tone(r.status)}>{t(`sr.st.${r.status}`)}</Pill>
            </Descriptions.Item>
            <Descriptions.Item label={t('sr.feeRate')}>{r.feeRate}%</Descriptions.Item>
            <Descriptions.Item label={t('sr.pool')}>
              <Amount value={r.poolTotal} />
            </Descriptions.Item>
            <Descriptions.Item label={t('sr.payout')}>
              <Amount value={r.sumPayout} />
            </Descriptions.Item>
            <Descriptions.Item label={t('sr.fee')}>
              <Amount value={r.sumFee} />
            </Descriptions.Item>
            <Descriptions.Item label={t('sr.dust')}>
              <Amount value={r.dust} />
            </Descriptions.Item>
            <Descriptions.Item label={t('sr.income')}>
              <Amount value={r.platformIncome} strong />
            </Descriptions.Item>
            <Descriptions.Item label={t('sr.conserved')}>{r.conservationOk ? <Pill tone="ok">{t('sr.ok')}</Pill> : <Pill tone="danger">{t('sr.bad')}</Pill>}</Descriptions.Item>
            <Descriptions.Item label={t('sr.positions')}>
              {t('sr.posN', { w: r.winnerPositions, l: r.loserPositions })} · {t('sr.pending', { n: r.pendingItems })}
            </Descriptions.Item>
            <Descriptions.Item label={t('sr.time')}>
              <Num>{utc(r.calculatedAt, true)}</Num> → <Num>{utc(r.doneAt, true)}</Num>
            </Descriptions.Item>
            {r.reason ? <Descriptions.Item label={t('sr.reason')}>{t(`mkd.voidWhy.${r.reason}`, { defaultValue: r.reason })}</Descriptions.Item> : null}
          </Descriptions>
          {data.payoutItems ? (
            <Table
              size="small"
              rowKey={(x) => `${x.userId}-${x.optionNo}`}
              loading={loading}
              scroll={{ x: 'max-content' }}
              dataSource={data.payoutItems.items}
              pagination={{ current: page, pageSize: size, total: data.payoutItems.total, size: 'small', onChange: (p, s) => (setPage(p), setSize(s)) }}
              columns={[
                { title: t('mkd.customer'), dataIndex: 'userId', render: (v: number) => <Link to={`/customers/${v}`}>{v}</Link> },
                { title: t('mkd.option'), dataIndex: 'optionNo', render: (v: string, x) => (<Space size={4}><Num>{v}</Num>{x.win ? <Pill tone="ok">{t('sr.win')}</Pill> : null}</Space>) },
                { title: t('sr.stake'), dataIndex: 'stake', align: 'right', render: (v: string) => <Amount value={v} /> },
                { title: t('sr.gross'), dataIndex: 'gross', align: 'right', render: (v: string) => <Amount value={v} /> },
                { title: t('sr.fee'), dataIndex: 'fee', align: 'right', render: (v: string) => <Amount value={v} /> },
                { title: t('sr.payout'), dataIndex: 'payout', align: 'right', render: (v: string) => <Amount value={v} strong /> },
                { title: t('sr.status'), dataIndex: 'status' },
              ]}
            />
          ) : null}
          {data.refundItems ? (
            <Table
              size="small"
              rowKey="betNo"
              loading={loading}
              scroll={{ x: 'max-content' }}
              dataSource={data.refundItems.items}
              pagination={{ current: page, pageSize: size, total: data.refundItems.total, size: 'small', onChange: (p, s) => (setPage(p), setSize(s)) }}
              columns={[
                { title: t('mkd.customer'), dataIndex: 'userId', render: (v: number) => <Link to={`/customers/${v}`}>{v}</Link> },
                { title: t('mkd.betNo'), dataIndex: 'betNo', render: (v: string) => <Num>{v}</Num> },
                { title: t('mkd.amount'), dataIndex: 'amount', align: 'right', render: (v: string) => <Amount value={v} strong /> },
                { title: t('sr.reason'), dataIndex: 'reason' },
                { title: t('sr.status'), dataIndex: 'status' },
              ]}
            />
          ) : null}
        </Space>
      )}
      {hr.element}
    </Drawer>
  );
}

export function SettleRunsPage() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const status = sp.get('status') ?? undefined;
  const kind = sp.get('kind') ?? undefined;
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const fetcher = useCallback(() => listSettleRuns({ status, kind, page, pageSize: size }), [status, kind, page, size]);
  const { data, loading, error, reload } = useApi(fetcher);
  const set = (k: string, v: string | undefined) => {
    const n = new URLSearchParams(sp);
    if (v) n.set(k, v);
    else n.delete(k);
    setSp(n, { replace: true });
    setPage(1);
  };
  return (
    <>
      <PageHeader title={t('sr.title')} sub={t('sr.sub')} />
      <div className="fc-bar">
        <Segmented
          value={status ?? 'all'}
          onChange={(v) => set('status', v === 'all' ? undefined : String(v))}
          options={[
            { value: 'all', label: t('sr.all') },
            { value: 'abnormal', label: t('sr.st.abnormal') },
            { value: 'paying', label: t('sr.st.paying') },
            { value: 'done', label: t('sr.st.done') },
          ]}
        />
        <Segmented
          value={kind ?? 'all'}
          onChange={(v) => set('kind', v === 'all' ? undefined : String(v))}
          options={[
            { value: 'all', label: t('sr.allKinds') },
            { value: 'payout', label: t('sr.k.payout') },
            { value: 'refund', label: t('sr.k.refund') },
          ]}
        />
      </div>
      <DataTable<SettleRun>
        rowKey="runNo"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={size}
        onPageChange={(p, s) => (setPage(p), setSize(s))}
        emptyText={status || kind ? t('sr.emptyFiltered') : t('sr.empty')}
        onRow={(r) => ({ onClick: () => set('run', r.runNo) })}
        columns={[
          { title: t('sr.runNo'), dataIndex: 'runNo', render: (v: string) => <a>{v}</a> },
          { title: t('sr.market'), dataIndex: 'marketNo', render: (v: string) => <Link to={`/markets/${v}`} onClick={(e) => e.stopPropagation()}>{v}</Link> },
          { title: t('sr.kind'), dataIndex: 'kind', render: (v: SettleRun['kind']) => t(`sr.k.${v}`) },
          { title: t('sr.status'), dataIndex: 'status', render: (v: SettleRun['status']) => <Pill tone={tone(v)}>{t(`sr.st.${v}`)}</Pill> },
          { title: t('sr.pool'), dataIndex: 'poolTotal', align: 'right', render: (v: string) => <Amount value={v} /> },
          { title: t('sr.payout'), dataIndex: 'sumPayout', align: 'right', render: (v: string) => <Amount value={v} /> },
          { title: t('sr.income'), dataIndex: 'platformIncome', align: 'right', render: (v: string) => <Amount value={v} strong /> },
          { title: t('sr.conserved'), dataIndex: 'conservationOk', render: (v: boolean) => (v ? <Pill tone="ok">{t('sr.ok')}</Pill> : <Pill tone="danger">{t('sr.bad')}</Pill>) },
          { title: t('sr.time'), dataIndex: 'calculatedAt', render: (v: string) => <Num>{utc(v)}</Num> },
        ]}
      />
      <RunDrawer runNo={sp.get('run')} onClose={() => set('run', undefined)} onDone={reload} />
    </>
  );
}
