/**
 * 人工调账记录（forecast S17B，需求说明书第 21 节；菜单"资金 → 人工调账记录"，recon:view）：后台在客户详情里做的人工加款、扣款。
 * 按客户编号、方向、类别、日期（UTC）筛选；导出 CSV 由后端生成并写审计（adjust.export）。调整本身在客户详情（balance:adjust）。
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { App, Button, DatePicker, Input, Select, Tooltip } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { exportAdjustments, listAdjustments } from '../../api';
import type { AdjustCategory, AdjustDirection, AdjustQuery, Adjustment } from '../../api/fcTypes';
import { usePagedList } from '../../hooks/usePagedList';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { Pill } from '../../components/Pill';
import { Amount, Num } from '../../components/Num';
import { formatAmount } from '../../utils/money';
import { errorText } from '../../utils/errors';
import { utc } from '../../utils/market';

type Filters = Omit<AdjustQuery, 'page' | 'pageSize'>;
const INITIAL: Filters = {};
const CATS: AdjustCategory[] = ['deposit_missing', 'wrong_credit', 'other'];

export function AdjustmentsPage() {
  const { t, i18n } = useTranslation();
  const { message } = App.useApp();
  const list = usePagedList<Adjustment, Filters>(listAdjustments, INITIAL);
  const [draft, setDraft] = useState<Filters>(INITIAL);
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [exporting, setExporting] = useState(false);

  const apply = () => list.setFilters({ ...draft, from: range?.[0].format('YYYY-MM-DD'), to: range?.[1].format('YYYY-MM-DD') });

  const doExport = async () => {
    setExporting(true);
    try {
      await exportAdjustments(list.filters, i18n.language.startsWith('en') ? 'en' : 'zh');
    } catch (err) {
      void message.error(errorText(t, err));
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <PageHeader
        title={t('adjust.title')}
        sub={t('adjust.sub')}
        extra={
          <Tooltip title={t('adjust.csvHint')}>
            <Button icon={<DownloadOutlined />} loading={exporting} disabled={!list.data?.total} onClick={() => void doExport()}>
              {t('adjust.csv')}
            </Button>
          </Tooltip>
        }
      />
      <FilterBar
        onSearch={apply}
        onReset={() => {
          setDraft(INITIAL);
          setRange(null);
          list.setFilters(INITIAL);
        }}
      >
        <FilterItem label={t('adjust.customerId')}>
          <Input
            id="adj-customer"
            aria-label={t('adjust.customerId')}
            style={{ width: 140 }}
            allowClear
            inputMode="numeric"
            value={draft.customerId ?? ''}
            onChange={(e) => setDraft({ ...draft, customerId: e.target.value.trim() || undefined })}
            onPressEnter={apply}
          />
        </FilterItem>
        <FilterItem label={t('adjust.direction')}>
          <Select
            aria-label={t('adjust.direction')}
            allowClear
            style={{ width: 110 }}
            placeholder={t('common.all')}
            value={draft.direction}
            onChange={(v: AdjustDirection | undefined) => setDraft({ ...draft, direction: v })}
            options={(['credit', 'debit'] as const).map((d) => ({ value: d, label: t(`adjust.dir.${d}`) }))}
          />
        </FilterItem>
        <FilterItem label={t('adjust.category')}>
          <Select
            aria-label={t('adjust.category')}
            allowClear
            style={{ width: 160 }}
            placeholder={t('common.all')}
            value={draft.category}
            onChange={(v: AdjustCategory | undefined) => setDraft({ ...draft, category: v })}
            options={CATS.map((c) => ({ value: c, label: t(`adjust.cat.${c}`) }))}
          />
        </FilterItem>
        <FilterItem label={t('adjust.dates')}>
          <DatePicker.RangePicker aria-label={t('adjust.dates')} value={range} onChange={(v) => setRange(v && v[0] && v[1] ? [v[0], v[1]] : null)} />
        </FilterItem>
      </FilterBar>
      <DataTable<Adjustment>
        rowKey="adjustNo"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total ?? 0}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        emptyText={t('adjust.empty')}
        columns={[
          { title: t('adjust.time'), dataIndex: 'createdAt', render: (v: string) => <Num>{utc(v)}</Num> },
          {
            title: t('adjust.customer'),
            dataIndex: 'customerId',
            render: (v: number, r) => (
              <div className="user-cell">
                <b>
                  <Link to={`/customers/${v}`}>
                    {r.customerName ?? v} <span className="muted">#{v}</span>
                  </Link>
                </b>
                {r.customerEmail ? <small className="ellipsis">{r.customerEmail}</small> : null}
              </div>
            ),
          },
          {
            title: t('adjust.direction'),
            dataIndex: 'direction',
            render: (v: AdjustDirection) => <Pill tone={v === 'credit' ? 'ok' : 'warn'}>{t(`adjust.dir.${v}`)}</Pill>,
          },
          {
            title: t('adjust.amount'),
            dataIndex: 'amount',
            align: 'right',
            render: (v: string, r) => <Amount value={r.direction === 'debit' ? `-${v}` : v} signed strong />,
          },
          { title: t('adjust.category'), dataIndex: 'category', render: (v: AdjustCategory) => t(`adjust.cat.${v}`) },
          {
            title: t('adjust.balance'),
            dataIndex: 'availableAfter',
            render: (v: string, r) => (
              <Num>
                {formatAmount(r.availableBefore)} → {formatAmount(v)}
              </Num>
            ),
          },
          {
            title: t('adjust.reason'),
            dataIndex: 'reason',
            render: (v: string, r) => (
              <div className="user-cell">
                <b className="ellipsis" style={{ maxWidth: 260, fontWeight: 400 }} title={v}>
                  {v}
                </b>
                {r.refNo ? (
                  <small className="ellipsis" style={{ display: 'block', maxWidth: 260 }} title={r.refNo}>
                    {t('adjust.refNo')}：<Num>{r.refNo}</Num>
                  </small>
                ) : null}
                {r.overLimit ? <Pill tone="danger">{t('adjust.overLimit')}</Pill> : null}
              </div>
            ),
          },
          { title: t('adjust.operator'), dataIndex: 'operator' },
          { title: t('adjust.no'), dataIndex: 'adjustNo', render: (v: string) => <Num>{v}</Num> },
        ]}
      />
    </>
  );
}
