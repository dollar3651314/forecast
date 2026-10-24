/**
 * 角色：增删改、分配权限点（树形，按模块分组；高危权限标红）。超级管理员是内置角色，拥有全部权限，不能修改或删除。
 */
import { useCallback, useMemo, useState } from 'react';
import { Alert, Form, Input, Modal, Space, Tree } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { deleteRole, listPermissions, listRoles, saveRole } from '../../api';
import type { Role, RoleInput } from '../../api/types';
import { PERMISSION_MODULES } from '../../config/permissions';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../components/PageHeader';
import { DataTable } from '../../components/DataTable';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { localName } from '../../utils/display';

export function RolesPage() {
  const { t, i18n } = useTranslation();
  const { refresh } = useAuth();
  const rolesFetcher = useCallback(() => listRoles(), []);
  const permsFetcher = useCallback(() => listPermissions(), []);
  const roles = useApi(rolesFetcher);
  const perms = useApi(permsFetcher);
  const [editing, setEditing] = useState<Role | 'new' | null>(null);
  const [form] = Form.useForm<RoleInput>();
  const hr = useHighRisk();

  const treeData = useMemo(
    () =>
      PERMISSION_MODULES.map((m) => ({
        key: `module:${m}`,
        title: <b>{t(`permModule.${m}`)}</b>,
        children: (perms.data ?? [])
          .filter((p) => p.module === m)
          .map((p) => ({
            key: p.code,
            title: (
              <span>
                {t(`perm.${p.code.replace(':', '_')}`)} <Num className="muted">{p.code}</Num> {p.highRisk ? <span className="pill pill-danger">{t('highRisk.tag')}</span> : null}
              </span>
            ),
          })),
      })),
    [perms.data, t],
  );

  const open = (r: Role | 'new') => {
    setEditing(r);
    form.setFieldsValue(r === 'new' ? { code: '', nameZh: '', nameEn: '', description: '', permissions: [] } : r);
  };

  const after = () => {
    roles.reload();
    void refresh();
  };

  const submit = async () => {
    let v: RoleInput;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const target = editing;
    setEditing(null);
    const permissions = v.permissions.filter((p) => !p.startsWith('module:'));
    hr.ask({
      title: target === 'new' ? t('roles.createConfirm') : t('roles.editConfirm'),
      description: t('roles.confirmDesc', { name: v.nameZh, count: permissions.length }),
      onConfirm: () => saveRole(target === 'new' ? null : (target as Role).id, { ...v, permissions }),
      onDone: after,
    });
  };

  const remove = (r: Role) =>
    hr.ask({
      title: t('roles.delete'),
      description: t('roles.deleteDesc', { name: localName(i18n.language, r.nameZh, r.nameEn), count: r.memberCount }),
      onConfirm: () => deleteRole(r.id),
      onDone: after,
    });

  return (
    <>
      <PageHeader
        title={t('roles.title')}
        sub={t('roles.sub')}
        extra={
          <PermButton perm="role:edit" type="primary" icon={<PlusOutlined />} onClick={() => open('new')}>
            {t('roles.create')}
          </PermButton>
        }
      />
      <DataTable<Role>
        rowKey="id"
        loading={roles.loading}
        error={roles.error}
        onRetry={roles.reload}
        dataSource={roles.data}
        columns={[
          { title: t('roles.name'), key: 'n', render: (_, r) => <div className="user-cell"><b>{localName(i18n.language, r.nameZh, r.nameEn)}</b><small className="num">{r.code}</small></div> },
          { title: t('roles.description'), dataIndex: 'description', render: (v: string) => <span className="ellipsis" style={{ maxWidth: 300 }} title={v}>{v}</span> },
          { title: t('roles.permCount'), dataIndex: 'permissions', align: 'right', render: (v: string[]) => <Num>{v.length}</Num> },
          { title: t('roles.members'), dataIndex: 'memberCount', align: 'right', render: (v: number) => <Num>{v}</Num> },
          { title: t('roles.type'), dataIndex: 'builtIn', render: (v: boolean) => (v ? <Pill tone="danger">{t('roles.builtIn')}</Pill> : <Pill tone="muted">{t('roles.custom')}</Pill>) },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) => (
              <Space size={4}>
                <PermButton perm="role:edit" size="small" icon={<EditOutlined />} disabled={r.builtIn} disabledReason={t('roles.builtInHint')} onClick={() => open(r)}>
                  {t('common.edit')}
                </PermButton>
                <PermButton perm="role:edit" size="small" danger icon={<DeleteOutlined />} disabled={r.builtIn || r.memberCount > 0} disabledReason={r.builtIn ? t('roles.builtInHint') : t('roles.inUseHint')} onClick={() => remove(r)}>
                  {t('common.delete')}
                </PermButton>
              </Space>
            ),
          },
        ]}
      />
      <Modal
        open={editing !== null}
        title={editing === 'new' ? t('roles.create') : t('roles.edit')}
        okText={t('common.next')}
        cancelText={t('common.cancel')}
        onOk={() => void submit()}
        onCancel={() => setEditing(null)}
        destroyOnClose
        maskClosable={false}
        width={640}
      >
        <Form form={form} layout="vertical" name="role-form" preserve={false}>
          <Form.Item name="code" label={t('roles.code')} rules={[{ required: true, pattern: /^[a-z][a-z0-9_]{1,31}$/, message: t('validation.codeFormat') }]}>
            <Input disabled={editing !== 'new'} />
          </Form.Item>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <Form.Item name="nameZh" label={t('roles.nameZh')} style={{ flex: '1 1 180px' }} rules={[{ required: true, whitespace: true, message: t('validation.required') }]}>
              <Input maxLength={20} />
            </Form.Item>
            <Form.Item name="nameEn" label={t('roles.nameEn')} style={{ flex: '1 1 180px' }} rules={[{ required: true, whitespace: true, message: t('validation.required') }]}>
              <Input maxLength={40} />
            </Form.Item>
          </div>
          <Form.Item name="description" label={t('roles.description')}>
            <Input maxLength={100} />
          </Form.Item>
          <Alert type="info" showIcon style={{ marginBottom: 8 }} message={t('roles.treeHint')} />
          <Form.Item name="permissions" label={t('roles.permissions')} valuePropName="checkedKeys" trigger="onCheck" rules={[{ required: true, type: 'array', min: 1, message: t('roles.permRequired') }]}>
            <Tree checkable selectable={false} defaultExpandAll treeData={treeData} className="perm-tree" height={320} />
          </Form.Item>
        </Form>
      </Modal>
      {hr.element}
    </>
  );
}
