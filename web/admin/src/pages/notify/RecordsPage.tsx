/**
 * 通知记录：每个渠道一条，显示发送结果；客户关闭了某渠道时记为"已跳过"。
 */
import { useState } from 'react';
import { Input, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { listNotifyRecords } from '../../api';
import type { NotifyRecord, NotifyRecordQuery } from '../../api/types';
import { usePagedList } from '../../hooks/usePagedList';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { notifyTone } from '../../utils/status';
import { shortTime } from '../../utils/display';

type Filters = Omit<NotifyRecordQuery, 'page' | 'pageSize'>;

export function RecordsPage() {
  const { t } = useTranslation();
  const list = usePagedList<NotifyRecord, Filters>(listNotifyRecords, {});
  const [draft, setDraft] = useState<Filters>({});
  return (
    <>
      <PageHeader title={t('records.title')} sub={t('records.sub')} />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft({}); list.setFilters({}); }}>
        <FilterItem label={t('records.channel')}>
          <Select aria-label={t('records.channel')} allowClear style={{ width: 110 }} placeholder={t('common.all')} value={draft.channel} onChange={(v) => setDraft({ ...draft, channel: v })} options={(['inbox', 'push', 'email'] as const).map((c) => ({ value: c, label: t(`channel.${c}`) }))} />
        </FilterItem>
        <FilterItem label={t('records.status')}>
          <Select aria-label={t('records.status')} allowClear style={{ width: 110 }} placeholder={t('common.all')} value={draft.status} onChange={(v) => setDraft({ ...draft, status: v })} options={(['pending', 'sent', 'failed', 'skipped'] as const).map((c) => ({ value: c, label: t(`notifyStatus.${c}`) }))} />
        </FilterItem>
        <FilterItem label={t('funds.user')} htmlFor="n-kw">
          <Input id="n-kw" allowClear style={{ width: 200 }} placeholder={t('records.keywordPh')} value={draft.keyword} onChange={(e) => setDraft({ ...draft, keyword: e.target.value })} />
        </FilterItem>
      </FilterBar>
      <DataTable<NotifyRecord>
        rowKey="id"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total ?? 0}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        columns={[
          { title: t('records.time'), dataIndex: 'time', render: (v: string) => <Num>{shortTime(v)}</Num> },
          { title: t('funds.user'), key: 'u', render: (_, r) => <div className="user-cell"><b title={r.customerName}>{r.customerName}</b>{r.customerId !== null ? <small>ID {r.customerId}</small> : null}</div> },
          { title: t('records.source'), key: 's', render: (_, r) => (r.source === 'manual' ? <Pill tone="admin">{t('records.manual', { name: r.operator })}</Pill> : <Num>{r.templateCode}</Num>) },
          { title: t('records.channel'), dataIndex: 'channel', render: (v: string) => t(`channel.${v}`) },
          { title: t('records.titleCol'), dataIndex: 'title', render: (v: string) => <span className="ellipsis" style={{ maxWidth: 220 }} title={v}>{v}</span> },
          { title: t('records.status'), dataIndex: 'status', render: (v: string) => <Pill tone={notifyTone[v]}>{t(`notifyStatus.${v}`)}</Pill> },
          { title: t('records.error'), dataIndex: 'error', render: (v: string | null) => (v ? <span className="ellipsis muted" style={{ maxWidth: 240 }} title={v}>{v}</span> : '—') },
        ]}
      />
    </>
  );
}
