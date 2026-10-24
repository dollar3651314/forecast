/**
 * 审计日志：按操作、操作人、是否高危、关键字筛选；详情显示变更前后。任何密码字段都不显示（修复 fxsystem S5）。
 */
import { useCallback, useState } from 'react';
import { Checkbox, Descriptions, Drawer, Input, Select, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { listAuditActions, listAuditLogs } from '../../api';
import type { AuditLog, AuditQuery } from '../../api/types';
import { usePagedList } from '../../hooks/usePagedList';
import { useApi } from '../../hooks/useApi';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { auditDiff, type DiffRow } from '../../utils/audit';
import { fullTime } from '../../utils/display';

type Filters = Omit<AuditQuery, 'page' | 'pageSize'>;

export function AuditPage() {
  const { t, i18n } = useTranslation();
  const list = usePagedList<AuditLog, Filters>(listAuditLogs, {});
  const actionsFetcher = useCallback(() => listAuditActions(), []);
  const actions = useApi(actionsFetcher);
  const [draft, setDraft] = useState<Filters>({});
  const [detail, setDetail] = useState<AuditLog | null>(null);
  const actionLabel = (a: string) => (i18n.exists(`auditAction.${a}`) ? t(`auditAction.${a}`) : a);

  return (
    <>
      <PageHeader title={t('audit.title')} sub={t('audit.sub')} />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft({}); list.setFilters({}); }}>
        <FilterItem label={t('audit.action')}>
          <Select aria-label={t('audit.action')} allowClear showSearch optionFilterProp="label" style={{ width: 200 }} placeholder={t('common.all')} value={draft.action} onChange={(v) => setDraft({ ...draft, action: v })} options={(actions.data ?? []).map((a) => ({ value: a, label: actionLabel(a) }))} />
        </FilterItem>
        <FilterItem label={t('audit.keyword')} htmlFor="au-kw">
          <Input id="au-kw" allowClear style={{ width: 200 }} placeholder={t('audit.keywordPh')} value={draft.keyword} onChange={(e) => setDraft({ ...draft, keyword: e.target.value })} />
        </FilterItem>
        <FilterItem label={t('audit.level')}>
          <Checkbox checked={!!draft.highRiskOnly} onChange={(e) => setDraft({ ...draft, highRiskOnly: e.target.checked || undefined })}>
            {t('audit.highRiskOnly')}
          </Checkbox>
        </FilterItem>
      </FilterBar>
      <DataTable<AuditLog>
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
          { title: t('audit.time'), dataIndex: 'time', render: (v: string) => <Num>{fullTime(v)}</Num> },
          { title: t('audit.admin'), dataIndex: 'adminName' },
          { title: t('audit.action'), dataIndex: 'action', render: (v: string, r) => <span className="nowrap">{actionLabel(v)} {r.highRisk ? <Pill tone="danger">{t('highRisk.tag')}</Pill> : null}</span> },
          { title: t('audit.target'), dataIndex: 'target', render: (v: string) => <Num>{v}</Num> },
          { title: t('audit.result'), dataIndex: 'result', render: (v: string) => <Pill tone={v === 'success' ? 'ok' : 'danger'}>{t(`audit.res.${v}`)}</Pill> },
          { title: t('audit.reason'), dataIndex: 'reason', render: (v: string | null) => (v ? <span className="ellipsis" style={{ maxWidth: 220 }} title={v}>{v}</span> : '—') },
          { title: t('audit.ip'), dataIndex: 'ip', render: (v: string) => <Num>{v}</Num> },
          { title: t('common.actions'), key: 'op', fixed: 'right', render: (_, r) => <a role="button" tabIndex={0} onClick={() => setDetail(r)} onKeyDown={(e) => e.key === 'Enter' && setDetail(r)}>{t('common.detail')}</a> },
        ]}
      />
      <Drawer open={!!detail} onClose={() => setDetail(null)} title={t('audit.detail')} width={Math.min(560, typeof window !== 'undefined' ? window.innerWidth : 560)}>
        {detail ? (
          <>
            <Descriptions size="small" column={1} bordered>
              <Descriptions.Item label={t('audit.time')}><Num>{fullTime(detail.time)}</Num></Descriptions.Item>
              <Descriptions.Item label={t('audit.admin')}>{detail.adminName}</Descriptions.Item>
              <Descriptions.Item label={t('audit.ip')}><Num>{detail.ip}</Num></Descriptions.Item>
              <Descriptions.Item label={t('audit.action')}>{actionLabel(detail.action)}</Descriptions.Item>
              <Descriptions.Item label={t('audit.target')}><Num>{detail.target}</Num></Descriptions.Item>
              <Descriptions.Item label={t('audit.result')}>{t(`audit.res.${detail.result}`)}</Descriptions.Item>
              <Descriptions.Item label={t('audit.reason')}>{detail.reason ?? '—'}</Descriptions.Item>
            </Descriptions>
            <h3 style={{ fontSize: 14, margin: '16px 0 8px' }}>{t('audit.changes')}</h3>
            <Table<DiffRow>
              size="small"
              rowKey="field"
              pagination={false}
              scroll={{ x: 'max-content' }}
              dataSource={auditDiff(detail.before, detail.after)}
              locale={{ emptyText: t('audit.noChanges') }}
              rowClassName={(r) => (r.changed ? 'row-selected' : '')}
              columns={[
                { title: t('audit.field'), dataIndex: 'field', render: (v: string) => <Num>{v}</Num> },
                { title: t('audit.before'), dataIndex: 'before', render: (v: string) => <Num>{v}</Num> },
                { title: t('audit.after'), dataIndex: 'after', render: (v: string, r) => <Num className={r.changed ? 'strong' : ''}>{v}</Num> },
              ]}
            />
            <p className="muted" style={{ fontSize: 12, marginTop: 8 }}>{t('audit.pwdHidden')}</p>
          </>
        ) : null}
      </Drawer>
    </>
  );
}
