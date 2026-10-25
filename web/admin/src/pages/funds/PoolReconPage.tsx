/**
 * 彩池对账（需求第 3.9、6.2 节"资金 → 彩池对账"；后端 SettleAdminApi.issues，S5）：每天 UTC 00:30 核对奖池、下注、账本"下注锁定"，
 * 差异列出来（核对日期、种类、来源、应为、实际、差额、说明）；有差异的市场暂停派彩。核对后"标记已处理"（pool_recon:resolve，处理说明必填），
 * 再到"结算记录"重试停下的结算单。
 */
import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { Segmented } from 'antd';
import { useTranslation } from 'react-i18next';
import { listPoolIssues, resolvePoolIssue } from '../../api';
import type { PoolIssue } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useNoteDialog } from '../../hooks/useConfirmDialogs';
import { utc } from '../../utils/market';

export function PoolReconPage() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<'open' | 'resolved' | undefined>('open');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const fetcher = useCallback(() => listPoolIssues(status, page, size), [status, page, size]);
  const { data, loading, error, reload } = useApi(fetcher);
  const note = useNoteDialog();
  return (
    <>
      <PageHeader title={t('pr.title')} sub={t('pr.sub')} />
      <div className="fc-bar">
        <Segmented
          value={status ?? 'all'}
          onChange={(v) => {
            setStatus(v === 'all' ? undefined : (v as 'open' | 'resolved'));
            setPage(1);
          }}
          options={[
            { value: 'open', label: t('pr.open') },
            { value: 'resolved', label: t('pr.resolved') },
            { value: 'all', label: t('pr.all') },
          ]}
        />
        <Link to="/settle/runs?status=abnormal">{t('pr.toAbnormal')}</Link>
      </div>
      <DataTable<PoolIssue>
        rowKey="id"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={size}
        onPageChange={(p, s) => (setPage(p), setSize(s))}
        emptyText={status === 'open' ? t('pr.emptyOpen') : t('pr.empty')}
        columns={[
          { title: t('pr.date'), dataIndex: 'checkDate', render: (v: string) => <Num>{v}</Num> },
          { title: t('pr.kind'), dataIndex: 'kind', render: (v: string) => t(`pr.kinds.${v}`, { defaultValue: v }) },
          { title: t('pr.market'), dataIndex: 'marketNo', render: (v: string | null) => (v ? <Link to={`/markets/${v}`}>{v}</Link> : <span className="muted">{t('pr.allMarkets')}</span>) },
          { title: t('pr.expected'), dataIndex: 'expected', align: 'right', render: (v: string) => <Num>{v}</Num> },
          { title: t('pr.actual'), dataIndex: 'actual', align: 'right', render: (v: string) => <Num>{v}</Num> },
          { title: t('pr.diff'), dataIndex: 'diff', align: 'right', render: (v: string) => <Num strong className={v.startsWith('-') ? 'down' : v === '0.00' ? '' : 'up'}>{v}</Num> },
          { title: t('pr.detail'), dataIndex: 'detail', render: (v: string | null, r) => <span className="ellipsis" style={{ maxWidth: 300 }} title={r.note ?? v ?? ''}>{r.note ? `${v ?? ''} · ${t('pr.noteIs', { note: r.note })}` : v}</span> },
          { title: t('pr.status'), dataIndex: 'status', render: (v: string, r) => (v === 'open' ? <Pill tone="danger">{t('pr.open')}</Pill> : <Pill tone="muted">{t('pr.resolvedAt', { t: utc(r.resolvedAt) })}</Pill>) },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) =>
              r.status === 'open' ? (
                <PermButton perm="pool_recon:resolve" size="small" onClick={() => note.ask({ title: t('pr.resolve'), label: t('pr.resolveNote'), onConfirm: (n) => resolvePoolIssue(r.id, n), onDone: reload })}>
                  {t('pr.resolve')}
                </PermButton>
              ) : null,
          },
        ]}
      />
      {note.element}
    </>
  );
}
