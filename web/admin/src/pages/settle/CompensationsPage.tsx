/**
 * 补偿（需求第 3.6 节 E13、第 6.2 节"结算与补偿"；后端 SettleAdminApi，S7）：已结算后发现裁错，由平台出钱按正确结果补给少拿的客户，只补不扣，
 * 每个市场一次。这里是补偿单列表（市场、原结果、正确结果、合计、人数、原因、时间）和明细；发起补偿在市场详情页（settle:compensate ★：
 * 选正确结果 → 预览每个客户应补多少 → 确认），这里提供按市场编号跳过去的入口。
 */
import { useCallback, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Drawer, Input, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { getCompensation, listCompensations } from '../../api';
import type { Compensation } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { Amount, Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { useAuth } from '../../auth/AuthContext';
import { utc } from '../../utils/market';

function CompDrawer({ no, onClose }: { no: string | null; onClose: () => void }) {
  const { t } = useTranslation();
  const { data, error, reload } = useApi(useCallback(() => (no ? getCompensation(no) : Promise.resolve(null)), [no]));
  return (
    <Drawer open={!!no} onClose={onClose} width={Math.min(640, typeof window !== 'undefined' ? window.innerWidth : 640)} title={t('cp.detail', { no: no ?? '' })} destroyOnClose>
      {error ? (
        <ErrorState error={error} onRetry={reload} compact />
      ) : data ? (
        <>
          <p>
            {t('cp.summary', { total: data.total, n: data.userCount, rate: data.feeRate })} · {data.reason}
          </p>
          <Table
            size="small"
            rowKey="userId"
            dataSource={data.lines}
            pagination={data.lines.length > 20 ? { pageSize: 20, size: 'small' } : false}
            columns={[
              { title: t('mkd.customer'), dataIndex: 'userId', render: (v: number) => <Link to={`/customers/${v}`}>{v}</Link> },
              { title: t('mkd.should'), dataIndex: 'shouldPayout', align: 'right', render: (v: string) => <Amount value={v} /> },
              { title: t('mkd.actual'), dataIndex: 'actualPayout', align: 'right', render: (v: string) => <Amount value={v} /> },
              { title: t('mkd.topUp'), dataIndex: 'amount', align: 'right', render: (v: string) => <Amount value={v} strong /> },
            ]}
          />
        </>
      ) : null}
    </Drawer>
  );
}

export function CompensationsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { can } = useAuth();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [open, setOpen] = useState<string | null>(null);
  const { data, loading, error, reload } = useApi(useCallback(() => listCompensations(page, size), [page, size]));
  return (
    <>
      <PageHeader
        title={t('cp.title')}
        sub={t('cp.sub')}
        extra={can('settle:compensate') ? <Input.Search style={{ width: 260 }} placeholder={t('cp.goMarket')} enterButton={t('cp.go')} onSearch={(v) => v.trim() && navigate(`/markets/${v.trim()}`)} /> : undefined}
      />
      <DataTable<Compensation>
        rowKey="compensationNo"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={size}
        onPageChange={(p, s) => (setPage(p), setSize(s))}
        emptyText={t('cp.empty')}
        onRow={(r) => ({ onClick: () => setOpen(r.compensationNo) })}
        columns={[
          { title: t('cp.no'), dataIndex: 'compensationNo', render: (v: string) => <a>{v}</a> },
          { title: t('sr.market'), dataIndex: 'marketNo', render: (v: string) => <Link to={`/markets/${v}`} onClick={(e) => e.stopPropagation()}>{v}</Link> },
          { title: t('cp.original'), dataIndex: 'originalOptionNo', render: (v: string) => <Num>{v}</Num> },
          { title: t('cp.correct'), dataIndex: 'correctOptionNo', render: (v: string) => (v === 'void' ? t('mkd.shouldVoid') : <Num>{v}</Num>) },
          { title: t('cp.total'), dataIndex: 'total', align: 'right', render: (v: string) => <Amount value={v} strong /> },
          { title: t('cp.users'), dataIndex: 'userCount', align: 'right', render: (v: number) => <Num>{v}</Num> },
          { title: t('cp.reason'), dataIndex: 'reason', render: (v: string) => <span className="ellipsis" style={{ maxWidth: 260 }}>{v}</span> },
          { title: t('cp.time'), dataIndex: 'createdAt', render: (v: string) => <Num>{utc(v, true)}</Num> },
        ]}
      />
      <CompDrawer no={open} onClose={() => setOpen(null)} />
    </>
  );
}
