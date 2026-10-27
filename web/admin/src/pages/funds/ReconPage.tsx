/**
 * 对账：列出钱包记录、账本、链上三方不一致的问题，标记已处理（必填说明）。修复 fxsystem F5。
 * 页面上方是热钱包余额（HotWalletCard，2026-10-17 热钱包余额告警）。
 * v2 资金归集：右上角"资金归集"入口（有 sweep:view 权限时显示；config/sweep.ts 的 SWEEP_UI 是总开关）。
 * forecast S17A：热钱包下面是"平台账户"（平台收入、补偿支出、测试入金，余额和账本合计应该相等）；测试入金单独一行。S17B 加"人工调账"一行。
 * 问题类型和字段按后端（后台方案第 8 节）：7 种类型，字段 type、ref、expected、actual、diff、detail；后端只支持按状态筛选。
 */
import { useState } from 'react';
import { Alert, Button, Select } from 'antd';
import { useNavigate } from 'react-router-dom';
import { SWEEP_BASE, SWEEP_PERM, SWEEP_UI } from '../../config/sweep';
import { useTranslation } from 'react-i18next';
import { listPlatformAccounts, listRecon, resolveRecon } from '../../api';
import type { PlatformAccount } from '../../api/fcTypes';
import { useApi } from '../../hooks/useApi';
import type { ReconItem, ReconQuery } from '../../api/types';
import { usePagedList } from '../../hooks/usePagedList';
import { useNoteDialog } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { PermButton, useCan } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Amount, Num } from '../../components/Num';
import { reconTone } from '../../utils/status';
import { HotWalletCard } from './HotWalletCard';
import { shortTime } from '../../utils/display';

type Filters = Omit<ReconQuery, 'page' | 'pageSize'>;
const INITIAL: Filters = { status: 'open' };

export function ReconPage() {
  const { t, i18n } = useTranslation();
  const list = usePagedList<ReconItem, Filters>(listRecon, INITIAL);
  const [draft, setDraft] = useState<Filters>(INITIAL);
  const note = useNoteDialog();
  const typeLabel = (v: string) => (i18n.exists(`reconType.${v}`) ? t(`reconType.${v}`) : v);
  const navigate = useNavigate();
  const can = useCan();

  return (
    <>
      <PageHeader
        title={t('recon.title')}
        sub={t('recon.sub')}
        extra={SWEEP_UI && can(SWEEP_PERM.view) ? <Button onClick={() => navigate(SWEEP_BASE)}>{t('sweep.entry')}</Button> : undefined}
      />
      <HotWalletCard />
      <PlatformAccounts />
      <Alert type="info" showIcon message={t('recon.hint')} />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft(INITIAL); list.setFilters(INITIAL); }}>
        <FilterItem label={t('recon.status')}>
          <Select
            aria-label={t('recon.status')}
            allowClear
            style={{ width: 120 }}
            placeholder={t('common.all')}
            value={draft.status}
            onChange={(v) => setDraft({ ...draft, status: v })}
            options={(['open', 'resolved'] as const).map((s) => ({ value: s, label: t(`reconStatus.${s}`) }))}
          />
        </FilterItem>
      </FilterBar>
      <DataTable<ReconItem>
        rowKey="id"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total ?? 0}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        emptyText={t('recon.empty')}
        columns={[
          { title: t('recon.id'), dataIndex: 'id', render: (v: string) => <Num>{v}</Num> },
          {
            title: t('recon.type'),
            dataIndex: 'type',
            render: (v: string, r) => (
              <div className="user-cell">
                <b>{typeLabel(v)}</b>
                {r.detail ? <small className="ellipsis" style={{ maxWidth: 260 }} title={r.detail}>{r.detail}</small> : null}
              </div>
            ),
          },
          { title: t('recon.ref'), dataIndex: 'ref', render: (v: string) => <Num>{v}</Num> },
          { title: t('funds.user'), key: 'u', render: (_, r) => (r.customerName ? <span className="ellipsis" style={{ maxWidth: 180 }} title={r.customerName}>{r.customerName}</span> : <span className="muted">{t('recon.unknownCustomer')}</span>) },
          { title: t('recon.expected'), dataIndex: 'expected', align: 'right', render: (v: string | null) => <Amount value={v} decimals={6} /> },
          { title: t('recon.actual'), dataIndex: 'actual', align: 'right', render: (v: string | null) => <Amount value={v} decimals={6} /> },
          { title: t('recon.diff'), dataIndex: 'diff', align: 'right', render: (v: string | null) => <Amount value={v} decimals={6} signed strong /> },
          { title: t('recon.detectedAt'), dataIndex: 'detectedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          { title: t('recon.status'), dataIndex: 'status', render: (v: string) => <Pill tone={reconTone[v]}>{t(`reconStatus.${v}`)}</Pill> },
          { title: t('recon.note'), dataIndex: 'note', render: (v: string | null, r) => (v ? <span title={v}>{r.handler} · <span className="ellipsis" style={{ maxWidth: 200 }}>{v}</span></span> : '—') },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) =>
              r.status === 'open' ? (
                <PermButton
                  perm="recon:resolve"
                  size="small"
                  onClick={() =>
                    note.ask({
                      title: t('recon.resolve'),
                      label: t('recon.note'),
                      placeholder: t('recon.notePh'),
                      okText: t('recon.resolve'),
                      onConfirm: (n) => resolveRecon(r.id, n),
                      onDone: list.reload,
                    })
                  }
                >
                  {t('recon.resolve')}
                </PermButton>
              ) : (
                <span className="muted">{t('reconStatus.resolved')}</span>
              ),
          },
        ]}
      />
      {note.element}
    </>
  );
}

/** 平台账户（S17A）：每个平台账户的余额和账本合计 */
function PlatformAccounts() {
  const { t } = useTranslation();
  const { data, loading, error, reload } = useApi(listPlatformAccounts);
  return (
    <div className="card card-pad">
      <h2 className="card-title">{t('recon.platformTitle')}</h2>
      <p className="muted">{t('recon.platformHint')}</p>
      <DataTable<PlatformAccount>
        bare
        rowKey="kind"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data ?? undefined}
        emptyText={t('recon.empty')}
        columns={[
          { title: t('recon.platformKind'), dataIndex: 'kind', render: (v: string) => t(`recon.platformKinds.${v}`, { defaultValue: v }) },
          { title: t('recon.platformBalance'), dataIndex: 'balance', align: 'right', render: (v: string) => <Amount value={v} /> },
          { title: t('recon.platformLedger'), dataIndex: 'ledgerTotal', align: 'right', render: (v: string) => <Amount value={v} /> },
          { title: t('recon.platformEntries'), dataIndex: 'entries', align: 'right', render: (v: number) => <Num>{v}</Num> },
          {
            title: t('recon.platformMatch'),
            dataIndex: 'matches',
            render: (v: boolean) => <Pill tone={v ? 'ok' : 'danger'}>{v ? t('recon.platformOk') : t('recon.platformBad')}</Pill>,
          },
        ]}
      />
    </div>
  );
}
