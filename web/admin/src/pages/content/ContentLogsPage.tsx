/**
 * 内容中心 → 日志（forecast S15；自动化需求第 7 节"日志"、AC-118）：每次生成、发布、驳回、复制、编辑、过滤、拦截（NF-16）、告警、设置；
 * 按日期、动作筛选；导出 CSV（服务器生成，写审计）。
 */
import { useCallback, useState } from 'react';
import { App, Button, DatePicker, Select, Space } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { exportContentLogs, listContentLogs } from '../../api';
import type { ContentLogRow } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { PageHeader } from '../../components/PageHeader';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { errorText } from '../../utils/errors';
import { utc } from '../../utils/market';

const ACTIONS = ['GENERATE', 'PUBLISH', 'REJECT', 'COPY', 'EDIT', 'RETRY', 'EXPIRE', 'FILTER', 'ECON', 'ECON_DRAFT', 'PAGE', 'TOOL', 'TEMPLATE', 'BLOCKED', 'ALERT', 'SETTING', 'WORD', 'STOP', 'RESUME', 'DIGEST', 'JOB'];

export function ContentLogsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [action, setAction] = useState<string | undefined>();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [busy, setBusy] = useState(false);
  const from = range?.[0].format('YYYY-MM-DD');
  const to = range?.[1].format('YYYY-MM-DD');
  const { data, loading, error, reload } = useApi(useCallback(() => listContentLogs({ from, to, action, page, pageSize: size }), [from, to, action, page, size]));
  return (
    <>
      <PageHeader
        title={t('ct.logs.title')}
        sub={t('ct.logs.sub')}
        extra={
          <Button
            icon={<DownloadOutlined />}
            loading={busy}
            onClick={async () => {
              setBusy(true);
              try {
                await exportContentLogs({ from, to, action });
              } catch (e) {
                void message.error(errorText(t, e));
              } finally {
                setBusy(false);
              }
            }}
          >
            {t('ct.logs.export')}
          </Button>
        }
      />
      <Space wrap className="filter-bar">
        <DatePicker.RangePicker value={range} onChange={(v) => { setRange(v && v[0] && v[1] ? [v[0], v[1]] : null); setPage(1); }} />
        <Select allowClear placeholder={t('ct.logs.action')} style={{ minWidth: 160 }} value={action} onChange={(v) => { setAction(v); setPage(1); }}
          options={ACTIONS.map((a) => ({ value: a, label: t(`ct.action.${a}`) }))} />
      </Space>
      <DataTable<ContentLogRow>
        rowKey="id"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={size}
        onPageChange={(p, s) => { setPage(p); setSize(s); }}
        emptyText={t('ct.logs.empty')}
        columns={[
          { title: t('ct.logs.time'), dataIndex: 'createdAt', render: (v: string) => utc(v) },
          { title: t('ct.logs.action'), dataIndex: 'action', render: (v: string) => t(`ct.action.${v}`, { defaultValue: v }) },
          { title: t('ct.logs.kind'), dataIndex: 'kind', render: (v: string | null) => (v ? t(`ct.kind.${v}`, { defaultValue: v }) : '—') },
          { title: t('ct.logs.ref'), dataIndex: 'ref', render: (v: string | null) => v ?? '—' },
          { title: t('ct.logs.outcome'), dataIndex: 'outcome', render: (v: string) => <Pill tone={v === 'OK' ? 'ok' : v === 'SKIPPED' ? 'muted' : 'danger'}>{t(`ct.outcome.${v}`, { defaultValue: v })}</Pill> },
          { title: t('ct.logs.detail'), dataIndex: 'detail' },
          { title: t('ct.logs.ai'), dataIndex: 'aiCalls' },
          { title: t('ct.logs.who'), dataIndex: 'adminId', render: (v: number | null) => (v == null ? t('ct.logs.system') : `#${v}`) },
        ]}
      />
    </>
  );
}
