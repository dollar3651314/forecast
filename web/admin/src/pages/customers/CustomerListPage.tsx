/**
 * 客户列表：按关键字、状态、KYC 状态筛选，分页。点击进入详情。
 */
import { useState } from 'react';
import { Input, Select } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { listCustomers } from '../../api';
import type { CustomerQuery, CustomerSummary } from '../../api/types';
import { usePagedList } from '../../hooks/usePagedList';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { Pill } from '../../components/Pill';
import { Amount, Num } from '../../components/Num';
import { customerTone, kycTone } from '../../utils/status';
import { shortTime } from '../../utils/display';

type Filters = Omit<CustomerQuery, 'page' | 'pageSize'>;

export function CustomerListPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const list = usePagedList<CustomerSummary, Filters>(listCustomers, {});
  const [draft, setDraft] = useState<Filters>({});

  return (
    <>
      <PageHeader title={t('customers.title')} sub={t('customers.sub')} />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft({}); list.setFilters({}); }}>
        <FilterItem label={t('customers.keyword')} htmlFor="c-kw">
          <Input id="c-kw" allowClear style={{ width: 220 }} placeholder={t('customers.keywordPh')} value={draft.keyword} onChange={(e) => setDraft({ ...draft, keyword: e.target.value })} />
        </FilterItem>
        <FilterItem label={t('customers.status')}>
          <Select
            aria-label={t('customers.status')}
            allowClear
            style={{ width: 120 }}
            placeholder={t('common.all')}
            value={draft.status}
            onChange={(v) => setDraft({ ...draft, status: v })}
            options={(['active', 'frozen'] as const).map((s) => ({ value: s, label: t(`customerStatus.${s}`) }))}
          />
        </FilterItem>
        <FilterItem label={t('customers.kyc')}>
          <Select
            aria-label={t('customers.kyc')}
            allowClear
            style={{ width: 130 }}
            placeholder={t('common.all')}
            value={draft.kycStatus}
            onChange={(v) => setDraft({ ...draft, kycStatus: v })}
            options={(['none', 'pending', 'approved', 'rejected'] as const).map((s) => ({ value: s, label: t(`kycStatus.${s}`) }))}
          />
        </FilterItem>
      </FilterBar>
      <DataTable<CustomerSummary>
        rowKey="id"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total ?? 0}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        onRow={(r) => ({ onDoubleClick: () => navigate(`/customers/${r.id}`) })}
        columns={[
          { title: t('customers.id'), dataIndex: 'id', render: (v: number) => <Num>{v}</Num> },
          {
            title: t('customers.customer'),
            key: 'name',
            render: (_, r) => (
              <div className="user-cell">
                <b title={r.name}>{r.name}</b>
                <small>{r.email}</small>
              </div>
            ),
          },
          { title: t('customers.nationality'), dataIndex: 'nationality' },
          { title: t('customers.status'), dataIndex: 'status', render: (v: string) => <Pill tone={customerTone[v]}>{t(`customerStatus.${v}`)}</Pill> },
          { title: t('customers.kyc'), dataIndex: 'kycStatus', render: (v: string) => <Pill tone={kycTone[v]}>{t(`kycStatus.${v}`)}</Pill> },
          { title: t('customers.balance'), dataIndex: 'balance', align: 'right', render: (v: string) => <Amount value={v} /> },
          { title: t('customers.registeredAt'), dataIndex: 'registeredAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          { title: t('customers.lastLogin'), dataIndex: 'lastLoginAt', render: (v: string | null) => (v ? <Num>{shortTime(v)}</Num> : <span className="muted">{t('customers.neverLogin')}</span>) },
          { title: t('common.actions'), key: 'op', fixed: 'right', render: (_, r) => <Link to={`/customers/${r.id}`}>{t('common.detail')}</Link> },
        ]}
      />
    </>
  );
}
