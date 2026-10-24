/**
 * 入金记录：链、交易号、确认数、入账状态。只认配置好的 USDT 合约，其他代币标为"未入账（非配置合约）"。
 */
import { useState } from 'react';
import { Input, Select, Tooltip, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { listDeposits } from '../../api';
import type { Deposit, DepositQuery } from '../../api/types';
import { CHAINS } from '../../config/constants';
import { usePagedList } from '../../hooks/usePagedList';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { ChainTag, Pill } from '../../components/Pill';
import { Amount, Num } from '../../components/Num';
import { depositTone } from '../../utils/status';
import { shortHash, shortTime } from '../../utils/display';

type Filters = Omit<DepositQuery, 'page' | 'pageSize'>;

export function DepositsPage() {
  const { t } = useTranslation();
  const list = usePagedList<Deposit, Filters>(listDeposits, {});
  const [draft, setDraft] = useState<Filters>({});

  return (
    <>
      <PageHeader title={t('deposits.title')} sub={t('deposits.sub')} />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft({}); list.setFilters({}); }}>
        <FilterItem label={t('funds.chain')}>
          <Select aria-label={t('funds.chain')} allowClear style={{ width: 110 }} placeholder={t('common.all')} value={draft.chain} onChange={(v) => setDraft({ ...draft, chain: v })} options={CHAINS.map((c) => ({ value: c, label: c }))} />
        </FilterItem>
        <FilterItem label={t('deposits.status')}>
          <Select
            aria-label={t('deposits.status')}
            allowClear
            style={{ width: 170 }}
            placeholder={t('common.all')}
            value={draft.status}
            onChange={(v) => setDraft({ ...draft, status: v })}
            options={(['confirming', 'credited', 'ignored', 'reversed'] as const).map((s) => ({ value: s, label: t(`depositStatus.${s}`) }))}
          />
        </FilterItem>
        <FilterItem label={t('funds.user')} htmlFor="d-kw">
          <Input id="d-kw" allowClear style={{ width: 240 }} placeholder={t('deposits.keywordPh')} value={draft.keyword} onChange={(e) => setDraft({ ...draft, keyword: e.target.value })} />
        </FilterItem>
      </FilterBar>
      <DataTable<Deposit>
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
          { title: t('deposits.id'), dataIndex: 'id', render: (v: string) => <Num>{v}</Num> },
          {
            title: t('funds.user'),
            key: 'u',
            render: (_, r) => (
              <div className="user-cell">
                <b title={r.customerName}>{r.customerName}</b>
                <small>{r.email}</small>
              </div>
            ),
          },
          { title: t('funds.chain'), dataIndex: 'chain', render: (v: string) => <ChainTag chain={v} /> },
          { title: t('deposits.amount'), dataIndex: 'amount', align: 'right', render: (v: string) => <Amount value={v} decimals={v.includes('.') && v.split('.')[1].length > 2 ? 6 : 2} strong /> },
          {
            title: t('deposits.txHash'),
            dataIndex: 'txHash',
            render: (v: string) => (
              <Tooltip title={v}>
                <Typography.Text copyable={{ text: v, tooltips: [t('common.copy'), t('common.copied')] }} className="num">
                  {shortHash(v, 8, 6)}
                </Typography.Text>
              </Tooltip>
            ),
          },
          {
            title: t('deposits.confirmations'),
            key: 'conf',
            align: 'right',
            render: (_, r) => (
              <Num className={r.confirmations < r.requiredConfirmations ? 'text-warn' : ''}>
                {r.confirmations}/{r.requiredConfirmations}
              </Num>
            ),
          },
          { title: t('deposits.status'), dataIndex: 'status', render: (v: string) => <Pill tone={depositTone[v]}>{t(`depositStatus.${v}`)}</Pill> },
          { title: t('deposits.detectedAt'), dataIndex: 'detectedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          { title: t('deposits.creditedAt'), dataIndex: 'creditedAt', render: (v: string | null) => (v ? <Num>{shortTime(v)}</Num> : '—') },
        ]}
      />
    </>
  );
}
