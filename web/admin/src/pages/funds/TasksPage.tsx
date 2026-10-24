/**
 * 任务表：邮件、推送任务的状态和重试；失败的可以手动重试。
 * 处理中超时会被自动回收（修复现有 Outbox 卡在"发送中"的问题）。
 */
import { useState } from 'react';
import { Alert, App, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { listTasks, retryTask } from '../../api';
import type { TaskItem, TaskQuery } from '../../api/types';
import { usePagedList } from '../../hooks/usePagedList';
import { useAction } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { taskTone } from '../../utils/status';
import { shortTime } from '../../utils/display';

type Filters = Omit<TaskQuery, 'page' | 'pageSize'>;
// 只有邮件和推送（提现的广播和确认不走任务表，后台方案第 8 节）
const TYPES = ['email', 'push'] as const;
const STATUSES = ['pending', 'processing', 'success', 'failed'] as const;

export function TasksPage() {
  const { t } = useTranslation();
  const { modal } = App.useApp();
  const run = useAction();
  const list = usePagedList<TaskItem, Filters>(listTasks, {});
  const [draft, setDraft] = useState<Filters>({});

  const retry = (task: TaskItem) =>
    modal.confirm({
      title: t('tasks.retryConfirm'),
      content: t('tasks.retryContent', { id: task.id, type: t(`taskType.${task.type}`), ref: task.bizRef }),
      okText: t('tasks.retry'),
      cancelText: t('common.cancel'),
      onOk: () => run(() => retryTask(task.id), t('tasks.retried'), list.reload),
    });

  return (
    <>
      <PageHeader title={t('tasks.title')} sub={t('tasks.sub')} />
      <Alert type="info" showIcon message={t('tasks.hint')} />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft({}); list.setFilters({}); }}>
        <FilterItem label={t('tasks.type')}>
          <Select aria-label={t('tasks.type')} allowClear style={{ width: 150 }} placeholder={t('common.all')} value={draft.type} onChange={(v) => setDraft({ ...draft, type: v })} options={TYPES.map((x) => ({ value: x, label: t(`taskType.${x}`) }))} />
        </FilterItem>
        <FilterItem label={t('tasks.status')}>
          <Select aria-label={t('tasks.status')} allowClear style={{ width: 120 }} placeholder={t('common.all')} value={draft.status} onChange={(v) => setDraft({ ...draft, status: v })} options={STATUSES.map((x) => ({ value: x, label: t(`taskStatus.${x}`) }))} />
        </FilterItem>
      </FilterBar>
      <DataTable<TaskItem>
        rowKey="id"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total ?? 0}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        rowClassName={(r) => (r.status === 'failed' ? 'row-alert' : '')}
        columns={[
          { title: t('tasks.id'), dataIndex: 'id', render: (v: string) => <Num>{v}</Num> },
          { title: t('tasks.type'), dataIndex: 'type', render: (v: string) => t(`taskType.${v}`) },
          { title: t('tasks.bizRef'), dataIndex: 'bizRef', render: (v: string) => <Num>{v}</Num> },
          { title: t('tasks.status'), dataIndex: 'status', render: (v: string) => <Pill tone={taskTone[v]}>{t(`taskStatus.${v}`)}</Pill> },
          { title: t('tasks.attempts'), key: 'a', align: 'right', render: (_, r) => <Num>{r.attempts}/{r.maxAttempts}</Num> },
          { title: t('tasks.nextRetry'), dataIndex: 'nextRetryAt', render: (v: string | null) => (v ? <Num>{shortTime(v)}</Num> : '—') },
          { title: t('tasks.lastError'), dataIndex: 'lastError', render: (v: string | null) => (v ? <span className="ellipsis text-danger" style={{ maxWidth: 260 }} title={v}>{v}</span> : '—') },
          { title: t('tasks.updatedAt'), dataIndex: 'updatedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) =>
              r.status === 'failed' ? (
                <PermButton perm="task:retry" size="small" onClick={() => retry(r)}>
                  {t('tasks.retry')}
                </PermButton>
              ) : (
                <span className="muted">—</span>
              ),
          },
        ]}
      />
    </>
  );
}
