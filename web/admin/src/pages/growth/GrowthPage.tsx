/**
 * 来源统计（forecast S13，MF-1、MF-10；需求第 6.2 节、AC-94；后端 GET /api/admin/growth/report、/api/admin/growth/export，权限点 growth:view）。
 * 上面是合计（访问、注册、首充、激活、第 2 周仍下注）；按注册周 × 来源（src / cmp / ref）的表；按周的市场情况（有效市场率、每场下注人数中位数，
 * 加场门槛用，需求第 4.2 节）；作废 / 取消原因；范围内结束的市场。
 * 口径：周 = UTC 的 ISO 周（周一起）；来源可以按"首次来源"（默认）或"注册来源"看；第 2 周 = 注册后第 8～14 天，第 2 周还没过完的客户不算进分母。
 * 导出三种 CSV（按来源、按市场、按客户），服务器生成并写审计；文件里只有客户编号，没有邮箱、地址、KYC 信息。
 * 导出按钮在页头（C79 首屏可见）。
 */
import { useCallback, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, App, Button, Card, Col, DatePicker, Dropdown, Empty, Row, Segmented, Space, Table, Tag } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import utcPlugin from 'dayjs/plugin/utc';
import { useTranslation } from 'react-i18next';
import { exportGrowth, getGrowthReport } from '../../api';
import type { GrowthCohort, GrowthExportKind, GrowthMarketRow, GrowthMarketWeek, GrowthQuery } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { Amount, Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { errorText } from '../../utils/errors';
import { utc } from '../../utils/market';

dayjs.extend(utcPlugin);

const KINDS: GrowthExportKind[] = ['sources', 'markets', 'customers'];

/** a / b 的百分比（1 位小数）；分母 0 时 "—" */
function pct(a: number, b: number): string {
  return b > 0 ? `${((a * 100) / b).toFixed(1)}%` : '—';
}

function Source({ c }: { c: Pick<GrowthCohort, 'src' | 'cmp' | 'ref'> }) {
  const { t } = useTranslation();
  if (!c.src && !c.cmp && !c.ref) return <span className="muted">{t('gr.direct')}</span>;
  return (
    <Space size={4} wrap>
      {c.src ? <Tag color="blue">{c.src}</Tag> : <span className="muted">—</span>}
      {c.cmp ? <Tag>{c.cmp}</Tag> : null}
      {c.ref ? <Tag color="purple">{t('gr.refBy', { ref: c.ref })}</Tag> : null}
    </Space>
  );
}

function Kpi({ k, v, sub, tone = '' }: { k: string; v: string | number; sub?: string; tone?: string }) {
  return (
    <div className={`card kpi ${tone}`}>
      <div className="k">{k}</div>
      <div className="v">
        <b>{v}</b>
        {sub ? <span>{sub}</span> : null}
      </div>
    </div>
  );
}

export function GrowthPage() {
  const { t, i18n } = useTranslation();
  const { message } = App.useApp();
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [basis, setBasis] = useState<'first' | 'reg'>('first');
  const [busy, setBusy] = useState<GrowthExportKind | null>(null);
  const q: GrowthQuery = useMemo(() => ({ from: range?.[0].format('YYYY-MM-DD'), to: range?.[1].format('YYYY-MM-DD'), basis }), [range, basis]);
  const { data, loading, error, reload } = useApi(useCallback(() => getGrowthReport(q), [q]));
  const [mPage, setMPage] = useState(1);
  const [mSize, setMSize] = useState(10);

  const doExport = async (kind: GrowthExportKind) => {
    setBusy(kind);
    try {
      await exportGrowth(kind, q, i18n.language.startsWith('en') ? 'en' : 'zh');
      void message.success(t('gr.exported'));
    } catch (e) {
      void message.error(errorText(t, e));
    } finally {
      setBusy(null);
    }
  };

  const tot = data?.totals;
  const markets = data?.markets ?? [];

  return (
    <>
      <PageHeader
        title={t('gr.title')}
        sub={data ? t('gr.range', { from: data.from, to: data.to }) : t('gr.sub')}
        extra={
          <Space wrap>
            <DatePicker.RangePicker
              value={range}
              placeholder={[t('gr.recent8'), t('gr.recent8')]}
              onChange={(v) => setRange(v && v[0] && v[1] ? [v[0], v[1]] : null)}
              disabledDate={(d) => d.isAfter(dayjs.utc(), 'day')}
            />
            <Segmented<'first' | 'reg'>
              value={basis}
              onChange={setBasis}
              options={[
                { value: 'first', label: t('gr.basis.first') },
                { value: 'reg', label: t('gr.basis.reg') },
              ]}
            />
            <Dropdown
              menu={{
                items: KINDS.map((k) => ({ key: k, label: t(`gr.export.${k}`) })),
                onClick: ({ key }) => void doExport(key as GrowthExportKind),
              }}
            >
              <Button icon={<DownloadOutlined />} loading={busy !== null} data-testid="growth-export">
                {t('gr.exportCsv')}
              </Button>
            </Dropdown>
          </Space>
        }
      />
      <Alert type="info" showIcon className="gr-note" message={t('gr.note')} />
      {error ? (
        <ErrorState error={error} onRetry={reload} />
      ) : (
        <>
          {data?.truncated ? <Alert type="warning" showIcon className="gr-note" message={t('gr.truncated')} /> : null}
          <section className="kpis gr-kpis" aria-label={t('gr.totals')}>
            <Kpi k={t('gr.visits')} v={tot ? tot.visits : '—'} />
            <Kpi k={t('gr.registrations')} v={tot ? tot.registrations : '—'} tone="green" />
            <Kpi k={t('gr.firstDeposits')} v={tot ? tot.firstDeposits : '—'} sub={tot ? pct(tot.firstDeposits, tot.registrations) : undefined} />
            <Kpi k={t('gr.activated')} v={tot ? tot.activated : '—'} sub={tot ? pct(tot.activated, tot.registrations) : undefined} />
            <Kpi k={t('gr.week2')} v={tot ? tot.week2Active : '—'} sub={tot ? t('gr.ofEligible', { n: tot.week2Eligible, pct: pct(tot.week2Active, tot.week2Eligible) }) : undefined} tone="orange" />
          </section>

          <Card size="small" title={t('gr.bySource')} className="gr-card">
            <Table<GrowthCohort>
              size="middle"
              rowKey={(c) => `${c.week}|${c.src}|${c.cmp}|${c.ref}`}
              loading={loading && !data}
              dataSource={data?.cohorts}
              scroll={{ x: 'max-content' }}
              pagination={{ pageSize: 20, size: 'small', showSizeChanger: false, hideOnSinglePage: true }}
              locale={{ emptyText: loading && !data ? <div style={{ height: 80 }} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('gr.emptyCohorts')} /> }}
              columns={[
                { title: t('gr.col.week'), dataIndex: 'week', render: (v: string) => <Num>{v}</Num> },
                { title: t('gr.col.source'), key: 'src', render: (_, c) => <Source c={c} /> },
                { title: t('gr.visits'), dataIndex: 'visits', align: 'right', render: (v: number) => <Num>{v}</Num> },
                { title: t('gr.registrations'), dataIndex: 'registrations', align: 'right', render: (v: number) => <Num strong>{v}</Num> },
                {
                  title: t('gr.firstDeposits'),
                  key: 'fd',
                  align: 'right',
                  render: (_, c) => (
                    <span>
                      <Num>{c.firstDeposits}</Num> <small className="muted">{pct(c.firstDeposits, c.registrations)}</small>
                    </span>
                  ),
                },
                {
                  title: t('gr.activated'),
                  key: 'act',
                  align: 'right',
                  render: (_, c) => (
                    <span>
                      <Num>{c.activated}</Num> <small className="muted">{pct(c.activated, c.registrations)}</small>
                    </span>
                  ),
                },
                {
                  title: t('gr.week2'),
                  key: 'w2',
                  align: 'right',
                  render: (_, c) =>
                    c.week2Eligible > 0 ? (
                      <span>
                        <Num>{c.week2Active}</Num> / <Num>{c.week2Eligible}</Num> <small className="muted">{pct(c.week2Active, c.week2Eligible)}</small>
                      </span>
                    ) : (
                      <span className="muted">{t('gr.notYet')}</span>
                    ),
                },
              ]}
            />
          </Card>

          <Row gutter={[12, 12]} className="gr-grid">
            <Col xs={24} xl={14}>
              <Card size="small" title={t('gr.marketWeeks')} extra={<span className="muted">{t('gr.marketWeeksHint')}</span>}>
                <Table<GrowthMarketWeek>
                  size="small"
                  rowKey="week"
                  loading={loading && !data}
                  dataSource={data?.marketWeeks}
                  scroll={{ x: 'max-content' }}
                  pagination={false}
                  locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('gr.emptyMarkets')} /> }}
                  columns={[
                    { title: t('gr.col.week'), dataIndex: 'week', render: (v: string) => <Num>{v}</Num> },
                    { title: t('gr.col.ended'), dataIndex: 'ended', align: 'right', render: (v: number) => <Num>{v}</Num> },
                    { title: t('mk.status.settled'), dataIndex: 'settled', align: 'right', render: (v: number) => <Num>{v}</Num> },
                    { title: t('mk.status.voided'), dataIndex: 'voided', align: 'right', render: (v: number) => <Num>{v}</Num> },
                    { title: t('mk.status.cancelled'), dataIndex: 'cancelled', align: 'right', render: (v: number) => <Num>{v}</Num> },
                    { title: t('gr.col.validRate'), dataIndex: 'validRate', align: 'right', render: (v: string | null) => (v === null ? '—' : <Num strong>{v}%</Num>) },
                    { title: t('gr.col.median'), dataIndex: 'medianBettors', align: 'right', render: (v: string | null) => (v === null ? '—' : <Num>{v}</Num>) },
                  ]}
                />
              </Card>
            </Col>
            <Col xs={24} xl={10}>
              <Card size="small" title={t('gr.reasons')}>
                {!data ? (
                  <div style={{ height: 80 }} />
                ) : data.reasons.length === 0 ? (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('gr.noReasons')} />
                ) : (
                  <ul className="gr-reasons">
                    {data.reasons.map((r) => (
                      <li key={`${r.status}|${r.reason ?? ''}`}>
                        <Pill tone={r.status === 'voided' ? 'warn' : 'muted'}>{t(`mk.status.${r.status}`)}</Pill>
                        <span>{r.reason ? t(`mkd.voidWhy.${r.reason}`, { defaultValue: r.reason }) : <span className="muted">{t('gr.noReason')}</span>}</span>
                        <Num strong>× {r.count}</Num>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </Col>
          </Row>

          <Card size="small" title={t('gr.endedMarkets', { n: markets.length })} className="gr-card">
            <DataTable<GrowthMarketRow>
              bare
              rowKey="marketNo"
              loading={loading && !data}
              dataSource={data ? markets.slice((mPage - 1) * mSize, mPage * mSize) : undefined}
              total={markets.length}
              page={mPage}
              pageSize={mSize}
              onPageChange={(p, s) => (setMPage(p), setMSize(s))}
              emptyText={t('gr.emptyMarkets')}
              columns={[
                { title: t('mk.col.no'), dataIndex: 'marketNo', render: (v: string) => <Link to={`/markets/${v}`}>{v}</Link> },
                { title: t('mk.col.category'), dataIndex: 'category', render: (v: string) => <Tag>{t(`mk.cats.${v}`)}</Tag> },
                { title: t('mk.col.title'), key: 'title', render: (_, r) => (i18n.language.startsWith('en') ? r.titleEn : r.titleZhCN) },
                { title: t('mk.col.status'), dataIndex: 'status', render: (v: string) => <Pill tone={v === 'settled' ? 'ok' : v === 'voided' ? 'warn' : 'muted'}>{t(`mk.status.${v}`)}</Pill> },
                { title: t('gr.col.endedAt'), dataIndex: 'endedAt', render: (v: string) => <Num>{utc(v)}</Num> },
                { title: t('mk.col.bettors'), dataIndex: 'bettors', align: 'right', render: (v: number) => <Num>{v}</Num> },
                { title: t('mk.col.pool'), dataIndex: 'pool', align: 'right', render: (v: string) => <Amount value={v} /> },
                { title: t('gr.col.reason'), dataIndex: 'reason', render: (v: string | null) => (v ? t(`mkd.voidWhy.${v}`, { defaultValue: v }) : <span className="muted">—</span>) },
              ]}
            />
          </Card>
        </>
      )}
    </>
  );
}
