/**
 * KYC 审核列表：默认看待审核，按状态和关键字筛选。
 */
import { useState } from 'react';
import { Input, Select } from 'antd';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { listKyc } from '../../api';
import type { KycApplication, KycQuery } from '../../api/types';
import { usePagedList } from '../../hooks/usePagedList';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { kycTone } from '../../utils/status';
import { shortTime } from '../../utils/display';

type Filters = Omit<KycQuery, 'page' | 'pageSize'>;
const INITIAL: Filters = { status: 'pending' };

export function KycListPage() {
  const { t } = useTranslation();
  const list = usePagedList<KycApplication, Filters>(listKyc, INITIAL);
  const [draft, setDraft] = useState<Filters>(INITIAL);

  return (
    <>
      <PageHeader title={t('kyc.title')} sub={t('kyc.sub')} />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft(INITIAL); list.setFilters(INITIAL); }}>
        <FilterItem label={t('kyc.status')}>
          <Select
            aria-label={t('kyc.status')}
            allowClear
            style={{ width: 130 }}
            placeholder={t('common.all')}
            value={draft.status}
            onChange={(v) => setDraft({ ...draft, status: v })}
            options={(['pending', 'approved', 'rejected'] as const).map((s) => ({ value: s, label: t(`kycStatus.${s}`) }))}
          />
        </FilterItem>
        <FilterItem label={t('customers.keyword')} htmlFor="k-kw">
          <Input id="k-kw" allowClear style={{ width: 220 }} placeholder={t('customers.keywordPh')} value={draft.keyword} onChange={(e) => setDraft({ ...draft, keyword: e.target.value })} />
        </FilterItem>
      </FilterBar>
      <DataTable<KycApplication>
        rowKey="id"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total ?? 0}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        emptyText={list.filters.status === 'pending' ? t('kyc.emptyPending') : undefined}
        columns={[
          { title: t('kyc.id'), dataIndex: 'id', render: (v: string) => <Num>{v}</Num> },
          {
            title: t('customers.customer'),
            key: 'c',
            render: (_, r) => (
              <div className="user-cell">
                <b title={r.name}>{r.name}</b>
                <small>{r.email} · ID {r.customerId}</small>
              </div>
            ),
          },
          { title: t('customers.nationality'), dataIndex: 'nationality' },
          { title: t('kyc.docType'), dataIndex: 'docType', render: (v: string) => t(`kyc.doc.${v}`) },
          { title: t('kyc.submittedAt'), dataIndex: 'submittedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          { title: t('kyc.status'), dataIndex: 'status', render: (v: string) => <Pill tone={kycTone[v]}>{t(`kycStatus.${v}`)}</Pill> },
          { title: t('kyc.reviewer'), dataIndex: 'reviewer', render: (v: string | null) => v ?? '—' },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) => <Link to={`/kyc/${r.id}`}>{r.status === 'pending' ? t('kyc.review') : t('common.detail')}</Link>,
          },
        ]}
      />
    </>
  );
}
