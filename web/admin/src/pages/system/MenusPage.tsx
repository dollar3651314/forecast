/**
 * 菜单管理：查看后台菜单树（一级菜单和后端 Menus.java 一致，S9 起 11 个），调整排序和是否显示。每个页面都必须有菜单入口（AC-35）。
 * 菜单名称是翻译键，不能在这里改（后台方案第 7 节）；隐藏菜单不影响权限，权限只看权限点。
 */
import { useCallback, useState } from 'react';
import { Alert, InputNumber, Space, Switch, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import { listMenus, updateMenu } from '../../api';
import type { MenuNode } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useAction } from '../../hooks/useConfirmDialogs';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { PermButton } from '../../components/PermButton';
import { Num } from '../../components/Num';

type Draft = { order?: number; visible?: boolean };

export function MenusPage() {
  const { t } = useTranslation();
  const { can, refresh } = useAuth();
  const run = useAction();
  const fetcher = useCallback(() => listMenus(), []);
  const { data, loading, error, reload } = useApi(fetcher);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});

  const edit = (key: string, patch: Draft) => setDrafts((d) => ({ ...d, [key]: { ...d[key], ...patch } }));
  const changed = (r: MenuNode) => {
    const d = drafts[r.key];
    return !!d && ((d.order !== undefined && d.order !== r.order) || (d.visible !== undefined && d.visible !== r.visible));
  };
  const save = (r: MenuNode) => {
    const d = drafts[r.key] ?? {};
    void run(
      () => updateMenu(r.key, { order: d.order ?? r.order, visible: d.visible ?? r.visible }),
      t('common.saved'),
      () => {
        setDrafts((all) => {
          const next = { ...all };
          delete next[r.key];
          return next;
        });
        reload();
        // 侧边菜单的顺序和显示来自 /api/admin/me，保存后刷新
        void refresh();
      },
    );
  };

  return (
    <>
      <PageHeader title={t('menus.title')} sub={t('menus.sub')} />
      <Alert type="info" showIcon message={t('menus.hint')} />
      <div className="card table-card">
        {error ? (
          <ErrorState error={error} onRetry={reload} compact />
        ) : (
          <Table<MenuNode>
            rowKey="key"
            size="middle"
            loading={loading}
            dataSource={data}
            pagination={false}
            scroll={{ x: 'max-content' }}
            expandable={{ defaultExpandAllRows: true }}
            locale={{ emptyText: t('common.empty') }}
            columns={[
              { title: t('menus.name'), dataIndex: 'labelKey', render: (v: string, r) => (r.children ? <b>{t(v)}</b> : t(v)) },
              { title: t('menus.path'), dataIndex: 'path', render: (v: string | null) => (v ? <Num>/admin{v}</Num> : <span className="muted">{t('menus.group')}</span>) },
              { title: t('menus.permission'), dataIndex: 'permission', render: (v: string | null) => (v ? <Num>{v}</Num> : '—') },
              {
                title: t('menus.visible'),
                key: 'visible',
                render: (_, r) => (
                  <Switch
                    size="small"
                    aria-label={t('menus.visible')}
                    checked={drafts[r.key]?.visible ?? r.visible}
                    disabled={!can('menu:edit')}
                    onChange={(v) => edit(r.key, { visible: v })}
                  />
                ),
              },
              {
                title: t('menus.order'),
                key: 'order',
                render: (_, r) => (
                  <Space size={4}>
                    <InputNumber
                      size="small"
                      min={0}
                      max={100000}
                      precision={0}
                      aria-label={t('menus.order')}
                      value={drafts[r.key]?.order ?? r.order}
                      disabled={!can('menu:edit')}
                      onChange={(v) => edit(r.key, { order: v ?? 0 })}
                      style={{ width: 80 }}
                    />
                    <PermButton perm="menu:edit" size="small" disabled={!changed(r)} disabledReason={t('menus.noChange')} onClick={() => save(r)}>
                      {t('common.save')}
                    </PermButton>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </div>
    </>
  );
}
