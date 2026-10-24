/**
 * 权限点字典：所有权限点按模块列出，高危权限全部登记并标红（修复 fxsystem S6）。只读，权限点由代码定义。
 */
import { useCallback, useState } from 'react';
import { Alert, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { listPermissions } from '../../api';
import type { PermissionDef } from '../../api/types';
import { PERMISSION_MODULES } from '../../config/permissions';
import { useApi } from '../../hooks/useApi';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';

export function PermissionsPage() {
  const { t } = useTranslation();
  const fetcher = useCallback(() => listPermissions(), []);
  const { data, loading, error, reload } = useApi(fetcher);
  const [module, setModule] = useState<string | undefined>();
  const [draft, setDraft] = useState<string | undefined>();
  const rows = (data ?? []).filter((p) => !module || p.module === module);
  return (
    <>
      <PageHeader title={t('permissions.title')} sub={t('permissions.sub', { count: data?.length ?? 0, high: data?.filter((p) => p.highRisk).length ?? 0 })} />
      <Alert type="info" showIcon message={t('permissions.hint')} />
      <FilterBar onSearch={() => setModule(draft)} onReset={() => { setDraft(undefined); setModule(undefined); }}>
        <FilterItem label={t('permissions.module')}>
          <Select aria-label={t('permissions.module')} allowClear style={{ width: 150 }} placeholder={t('common.all')} value={draft} onChange={setDraft} options={PERMISSION_MODULES.map((m) => ({ value: m, label: t(`permModule.${m}`) }))} />
        </FilterItem>
      </FilterBar>
      <DataTable<PermissionDef>
        rowKey="code"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={rows}
        columns={[
          { title: t('permissions.code'), dataIndex: 'code', render: (v: string) => <Num>{v}</Num> },
          { title: t('permissions.name'), dataIndex: 'code', key: 'name', render: (v: string) => t(`perm.${v.replace(':', '_')}`) },
          { title: t('permissions.module'), dataIndex: 'module', render: (v: string) => t(`permModule.${v}`) },
          { title: t('permissions.level'), dataIndex: 'highRisk', render: (v: boolean) => (v ? <Pill tone="danger">{t('highRisk.tag')}</Pill> : <Pill tone="muted">{t('permissions.normal')}</Pill>) },
        ]}
      />
    </>
  );
}
