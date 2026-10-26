/**
 * 风控告警（需求第 6.2 节、第 8.1 节；后端 admin/ops/RiskAdminController，forecast S10）：尾盘大额、关联账户对押、新账户押中冷门、充了不玩就提四类告警，
 * 列出类型、客户、市场、金额、判定时的数字、时间和处理状态；按类型、状态、客户筛选。处理（risk_alert:handle）：标记已查看；已处理、误报要写处理说明（写审计）。
 * 告警由后端每 30 秒扫描生成，新告警同时发邮件给有 risk_alert:view 的管理员（第一期是超管，SQ-19）。
 */
import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, Input, Segmented, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { getRiskAlertCounts, listRiskAlerts, setRiskAlertStatus } from '../../api';
import { RISK_ALERT_KINDS, type RiskAlert, type RiskAlertKind, type RiskAlertStatus } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill, type PillTone } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useAction, useNoteDialog } from '../../hooks/useConfirmDialogs';
import { formatAmount } from '../../utils/money';
import { utc } from '../../utils/market';

const STATUS_TONE: Record<RiskAlertStatus, PillTone> = { new: 'danger', seen: 'warn', handled: 'ok', false_positive: 'muted' };
type StatusFilter = RiskAlertStatus | 'open' | 'all';

/** 判定时的数字 → 一句说明（翻译键 ra.detail.*） */
export function riskSummary(t: (k: string, o?: Record<string, unknown>) => string, a: RiskAlert): string {
  const d = a.detail;
  switch (a.kind) {
    case 'late_large_bet':
      return t('ra.detail.late_large_bet', { pct: d.pct, pool: formatAmount(d.poolAfter ?? '0'), sec: d.secondsBeforeClose, limitPct: d.limitPct, limitMinutes: d.limitMinutes, limitAmount: formatAmount(d.limitAmount ?? '0') });
    case 'linked_opposite':
      return t('ra.detail.linked_opposite', { users: (d.linkedUsers ?? '').split(',').join('、') });
    case 'new_account_longshot':
      return t('ra.detail.new_account_longshot', { odds: d.odds, days: d.accountDays, limitDays: d.limitDays });
    case 'deposit_no_bet':
      return t('ra.detail.deposit_no_bet', { deposit: formatAmount(d.deposit ?? '0'), stake: formatAmount(d.stake ?? '0'), multiple: d.multiple, no: d.withdrawalNo });
    default:
      return '';
  }
}

export function RiskAlertsPage() {
  const { t } = useTranslation();
  const [kind, setKind] = useState<RiskAlertKind | 'all'>('all');
  const [status, setStatus] = useState<StatusFilter>('new');
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const fetcher = useCallback(
    () =>
      listRiskAlerts({
        kind: kind === 'all' ? undefined : kind,
        status: status === 'all' || status === 'open' ? undefined : status,
        keyword: keyword || undefined,
        page,
        pageSize: size,
      }),
    [kind, status, keyword, page, size],
  );
  const { data, loading, error, reload } = useApi(fetcher);
  const counts = useApi(useCallback(() => getRiskAlertCounts(), []));
  const note = useNoteDialog();
  const act = useAction();
  const refresh = () => {
    reload();
    counts.reload();
  };
  const reset = (f: () => void) => {
    f();
    setPage(1);
  };
  const closeWith = (a: RiskAlert, to: 'handled' | 'false_positive') =>
    note.ask({
      title: t(`ra.to.${to}`),
      label: t('ra.noteLabel'),
      placeholder: t(`ra.notePh.${to}`),
      onConfirm: (n) => setRiskAlertStatus(a.id, to, n),
      onDone: refresh,
    });

  const c = counts.data;
  return (
    <>
      <PageHeader title={t('ra.title')} sub={t('ra.sub')} />
      <div className="fc-bar">
        <Segmented
          value={kind}
          onChange={(v) => reset(() => setKind(v as RiskAlertKind | 'all'))}
          options={[
            { value: 'all', label: c ? t('ra.kindAllN', { n: c.open }) : t('ra.kindAll') },
            ...RISK_ALERT_KINDS.map((k) => ({ value: k, label: c ? `${t(`ra.kind.${k}`)} · ${c.openByKind[k] ?? 0}` : t(`ra.kind.${k}`) })),
          ]}
        />
      </div>
      <div className="fc-bar">
        <Segmented
          value={status}
          onChange={(v) => reset(() => setStatus(v as StatusFilter))}
          options={[
            { value: 'new', label: t('ra.status.new') },
            { value: 'seen', label: t('ra.status.seen') },
            { value: 'handled', label: t('ra.status.handled') },
            { value: 'false_positive', label: t('ra.status.false_positive') },
            { value: 'all', label: t('ra.statusAll') },
          ]}
        />
        <Space.Compact>
          <Input
            id="ra-kw"
            aria-label={t('ra.keyword')}
            allowClear
            style={{ width: 220 }}
            placeholder={t('ra.keywordPh')}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onPressEnter={() => reset(() => setKeyword(draft.trim()))}
          />
          <Button onClick={() => reset(() => setKeyword(draft.trim()))}>{t('common.search')}</Button>
        </Space.Compact>
      </div>
      <DataTable<RiskAlert>
        rowKey="id"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={size}
        onPageChange={(p, s) => (setPage(p), setSize(s))}
        emptyText={keyword || kind !== 'all' ? t('ra.emptyFiltered') : status === 'new' ? t('ra.emptyNew') : t('ra.empty')}
        columns={[
          { title: t('ra.time'), dataIndex: 'createdAt', render: (v: string) => <Num>{utc(v)}</Num> },
          { title: t('ra.kindCol'), dataIndex: 'kind', render: (v: RiskAlertKind) => t(`ra.kind.${v}`) },
          {
            title: t('ra.customer'),
            dataIndex: 'userId',
            render: (v: string, r) => (
              <Link to={`/customers/${v}`}>
                {r.customer ?? v} <span className="muted">#{v}</span>
              </Link>
            ),
          },
          { title: t('ra.market'), dataIndex: 'marketNo', render: (v: string | null, r) => (v ? <Link to={`/markets/${v}`} title={r.market ?? ''}>{v}</Link> : <span className="muted">—</span>) },
          { title: t('ra.amount'), dataIndex: 'amount', align: 'right', render: (v: string | null) => (v ? <Num>{formatAmount(v)}</Num> : <span className="muted">—</span>) },
          { title: t('ra.detailCol'), key: 'detail', render: (_, r) => <span className="ellipsis" style={{ maxWidth: 320 }} title={riskSummary(t, r)}>{riskSummary(t, r)}</span> },
          {
            title: t('ra.statusCol'),
            dataIndex: 'status',
            render: (v: RiskAlertStatus, r) => (
              <span title={r.note ?? ''}>
                <Pill tone={STATUS_TONE[v]}>{t(`ra.status.${v}`)}</Pill>
                {r.handledBy ? <span className="muted"> {r.handledBy}</span> : null}
              </span>
            ),
          },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) =>
              r.status === 'new' || r.status === 'seen' ? (
                <Space size={4}>
                  {r.status === 'new' ? (
                    <PermButton perm="risk_alert:handle" size="small" onClick={() => void act(() => setRiskAlertStatus(r.id, 'seen', null), undefined, refresh)}>
                      {t('ra.to.seen')}
                    </PermButton>
                  ) : null}
                  <PermButton perm="risk_alert:handle" size="small" onClick={() => closeWith(r, 'handled')}>
                    {t('ra.to.handled')}
                  </PermButton>
                  <PermButton perm="risk_alert:handle" size="small" onClick={() => closeWith(r, 'false_positive')}>
                    {t('ra.to.false_positive')}
                  </PermButton>
                </Space>
              ) : (
                <span className="muted ellipsis" style={{ maxWidth: 200 }} title={r.note ?? ''}>{r.note}</span>
              ),
          },
        ]}
      />
      {note.element}
    </>
  );
}
