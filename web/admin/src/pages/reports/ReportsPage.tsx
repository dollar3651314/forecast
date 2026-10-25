/**
 * 报表（需求第 6.2 节；后端 GET /api/admin/reports/settle，S7）：平台收入 = 抽成 + 结算尾差，补偿支出单列、不冲减收入；按天（UTC 日期）。
 * 选日期范围（最多 366 天），导出 CSV（在浏览器里生成，只含这张表的数字，不含客户信息）。
 * 下面按市场列出已完成的结算单（每个市场的平台收入），同样可以导出。
 */
import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Card, DatePicker, Space, Table } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import utcPlugin from 'dayjs/plugin/utc';
import { useTranslation } from 'react-i18next';
import { getReport, listSettleRuns } from '../../api';
import type { ReportDay, SettleRun } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { Amount, Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { utc } from '../../utils/market';
import { downloadCsv } from '../../utils/csv';

dayjs.extend(utcPlugin);

function ByMarket() {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const { data, loading, error, reload } = useApi(useCallback(() => listSettleRuns({ kind: 'payout', status: 'done', page, pageSize: size }), [page, size]));
  return (
    <Card
      size="small"
      title={t('rp.byMarket')}
      extra={
        <Button size="small" icon={<DownloadOutlined />} disabled={!data?.items.length} onClick={() => data && downloadCsv(`markets-${page}.csv`, [[t('sr.market'), t('sr.pool'), t('sr.payout'), t('sr.fee'), t('sr.dust'), t('sr.income'), t('sr.feeRate'), t('sr.time')], ...data.items.map((r) => [r.marketNo, r.poolTotal, r.sumPayout, r.sumFee, r.dust, r.platformIncome, r.feeRate, r.doneAt ?? ''])])}>
          {t('rp.csvPage')}
        </Button>
      }
    >
      <DataTable<SettleRun>
        bare
        rowKey="runNo"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={size}
        onPageChange={(p, s) => (setPage(p), setSize(s))}
        emptyText={t('rp.noRuns')}
        columns={[
          { title: t('sr.market'), dataIndex: 'marketNo', render: (v: string) => <Link to={`/markets/${v}`}>{v}</Link> },
          { title: t('sr.pool'), dataIndex: 'poolTotal', align: 'right', render: (v: string) => <Amount value={v} /> },
          { title: t('sr.fee'), dataIndex: 'sumFee', align: 'right', render: (v: string) => <Amount value={v} /> },
          { title: t('sr.dust'), dataIndex: 'dust', align: 'right', render: (v: string) => <Amount value={v} /> },
          { title: t('sr.income'), dataIndex: 'platformIncome', align: 'right', render: (v: string) => <Amount value={v} strong /> },
          { title: t('sr.time'), dataIndex: 'doneAt', render: (v: string | null) => <Num>{utc(v)}</Num> },
        ]}
      />
    </Card>
  );
}

export function ReportsPage() {
  const { t } = useTranslation();
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs.utc().subtract(29, 'day'), dayjs.utc()]);
  const from = range[0].format('YYYY-MM-DD');
  const to = range[1].format('YYYY-MM-DD');
  const { data, loading, error, reload } = useApi(useCallback(() => getReport(from, to), [from, to]));
  return (
    <>
      <PageHeader
        title={t('rp.title')}
        sub={t('rp.sub')}
        extra={
          <Space wrap>
            <DatePicker.RangePicker allowClear={false} value={range} onChange={(v) => v && v[0] && v[1] && setRange([v[0], v[1]])} disabledDate={(d) => d.isAfter(dayjs.utc(), 'day')} />
            <Button
              icon={<DownloadOutlined />}
              disabled={!data?.days.length}
              onClick={() => data && downloadCsv(`report-${from}_${to}.csv`, [[t('rp.date'), t('rp.fee'), t('rp.dust'), t('rp.revenue'), t('rp.comp'), t('rp.compN')], ...data.days.map((d) => [d.date, d.feeIncome, d.dust, d.revenue, d.compensation, d.compensations])])}
            >
              {t('rp.csv')}
            </Button>
          </Space>
        }
      />
      {error ? (
        <ErrorState error={error} onRetry={reload} />
      ) : (
        <>
          <section className="kpis" aria-label={t('rp.totals')}>
            <div className="card kpi green">
              <div className="k">{t('rp.revenue')}</div>
              <div className="v">
                <b>{data ? <Amount value={data.revenue} /> : '—'}</b>
                <span>USDT</span>
              </div>
            </div>
            <div className="card kpi">
              <div className="k">{t('rp.fee')}</div>
              <div className="v">
                <b>{data ? <Amount value={data.feeIncome} /> : '—'}</b>
              </div>
            </div>
            <div className="card kpi">
              <div className="k">{t('rp.dust')}</div>
              <div className="v">
                <b>{data ? <Amount value={data.dust} /> : '—'}</b>
              </div>
            </div>
            <div className="card kpi orange">
              <div className="k">{t('rp.comp')}</div>
              <div className="v">
                <b>{data ? <Amount value={data.compensationExpense} /> : '—'}</b>
                <span>{data ? t('rp.compCount', { n: data.compensations }) : ''}</span>
              </div>
            </div>
          </section>
          <p className="muted rp-note">{t('rp.note')}</p>
          <Table<ReportDay>
            className="card table-card"
            size="middle"
            rowKey="date"
            loading={loading}
            scroll={{ x: 'max-content' }}
            dataSource={data?.days}
            pagination={{ pageSize: 15, size: 'small', showSizeChanger: false }}
            locale={{ emptyText: t('rp.empty') }}
            columns={[
              { title: t('rp.date'), dataIndex: 'date', render: (v: string) => <Num>{v}</Num> },
              { title: t('rp.fee'), dataIndex: 'feeIncome', align: 'right', render: (v: string) => <Amount value={v} /> },
              { title: t('rp.dust'), dataIndex: 'dust', align: 'right', render: (v: string) => <Amount value={v} /> },
              { title: t('rp.revenue'), dataIndex: 'revenue', align: 'right', render: (v: string) => <Amount value={v} strong /> },
              { title: t('rp.comp'), dataIndex: 'compensation', align: 'right', render: (v: string) => <Amount value={v} /> },
            ]}
          />
        </>
      )}
      <div style={{ marginTop: 12 }}>
        <ByMarket />
      </div>
    </>
  );
}
