/** 资金流水：按类型和日期筛选，分页加载。 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { listLedger } from '../../api/wallet';
import type { LedgerQuery, LedgerType } from '../../api/types';
import { EmptyState, ErrorState, LoadMore, Loading } from '../../components/AsyncView';
import { Chips } from '../../components/Form';
import { PageHeader } from '../../components/Layout';
import { usePaged } from '../../hooks/usePaged';
import { LedgerList } from './LedgerList';

// S4 加下注；派彩、退款、补偿在 S5、S7 加（复制源的保证金、平仓盈亏、手续费、隔夜费、人工调整在 forecast S1 删除）
const TYPES: Array<LedgerType | 'all'> = ['all', 'deposit', 'withdraw', 'withdrawRefund', 'bet', 'payout', 'fee', 'refund', 'compensation'];
const RANGES: Array<LedgerQuery['range']> = ['7d', '30d', '90d', 'all'];

export default function Ledger() {
  const { t } = useTranslation();
  const [type, setType] = useState<LedgerType | 'all'>('all');
  const [range, setRange] = useState<LedgerQuery['range']>('30d');
  const s = usePaged((page, pageSize) => listLedger({ type, range, page, pageSize }), `ledger|${type}|${range}`);
  return (
    <div className="page page-ledger">
      <PageHeader title={t('assets.ledger')} back="/assets" />
      <section className="card">
        <div className="filter-block">
          <span className="filter-label">{t('ledger.filterType')}</span>
          <Chips label={t('ledger.filterType')} value={type} onChange={setType} options={TYPES.map((x) => ({ value: x, label: x === 'all' ? t('ledger.allTypes') : t(`ledger.type.${x}`) }))} />
        </div>
        <div className="filter-block">
          <span className="filter-label">{t('ledger.filterDate')}</span>
          <Chips label={t('ledger.filterDate')} value={range} onChange={setRange} options={RANGES.map((x) => ({ value: x, label: t(`ledger.range.${x}`) }))} />
        </div>
      </section>
      <section className="card">
        {s.error && !s.data ? (
          <ErrorState error={s.error} onRetry={s.reload} />
        ) : !s.data ? (
          <Loading rows={5} />
        ) : s.items.length === 0 ? (
          <EmptyState title={t('ledger.emptyFiltered')} hint={t('ledger.emptyFilteredHint')} />
        ) : (
          <>
            <div className="list-meta">{t('ledger.total', { total: s.total })}</div>
            <LedgerList items={s.items} />
            <LoadMore shown={s.items.length} total={s.total} hasMore={s.hasMore} loading={s.loadingMore} error={s.moreError} onMore={() => void s.loadMore()} />
          </>
        )}
      </section>
    </div>
  );
}
