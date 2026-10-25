/**
 * 管理员：新增、编辑、禁用/启用、重置密码（均为高危，二次确认）。
 * 非超级管理员不能分配超级管理员角色（修复 fxsystem S4）：界面上禁用该选项并说明，服务端同样拦截。
 * 新增和重置密码由系统生成临时密码，只在结果弹窗里显示一次（后台方案第 7 节；原型原来写的是发邮件），审计日志不记密码。
 */
import { useCallback, useState } from 'react';
import { Alert, Form, Input, Modal, Select, Space, Typography } from 'antd';
import { PlusOutlined, EditOutlined, KeyOutlined, StopOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { createAdmin, listAdmins, listRoles, resetAdminPassword, setAdminStatus, updateAdmin } from '../../api';
import type { AdminInput, AdminUser, TemporaryPassword } from '../../api/types';
import { SUPER_ADMIN_ROLE } from '../../config/permissions';
import { usePagedList } from '../../hooks/usePagedList';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../components/PageHeader';
import { FilterBar, FilterItem } from '../../components/FilterBar';
import { DataTable } from '../../components/DataTable';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { localName, shortTime } from '../../utils/display';
import { EMAIL_RE, USERNAME_RE } from '../../utils/validators';

type Filters = { keyword?: string };

export function AdminsPage() {
  const { t, i18n } = useTranslation();
  const { me } = useAuth();
  const isSuper = !!me?.isSuperAdmin;
  const list = usePagedList<AdminUser, Filters>(listAdmins, {});
  const rolesFetcher = useCallback(() => listRoles(), []);
  const roles = useApi(rolesFetcher);
  const [draft, setDraft] = useState<Filters>({});
  const [editing, setEditing] = useState<AdminUser | 'new' | null>(null);
  const [form] = Form.useForm<AdminInput>();
  const hr = useHighRisk();
  /** 刚生成的临时密码：只在这个弹窗里显示一次，关闭后不再保留 */
  const [tempPwd, setTempPwd] = useState<{ name: string; username: string; result: TemporaryPassword } | null>(null);

  const roleName = (id: string) => {
    const r = roles.data?.find((x) => x.id === id);
    return r ? localName(i18n.language, r.nameZh, r.nameEn) : id;
  };
  const superRoleId = roles.data?.find((r) => r.code === SUPER_ADMIN_ROLE)?.id;
  const targetIsSuper = (a: AdminUser) => !!superRoleId && a.roleIds.includes(superRoleId);

  const openForm = (a: AdminUser | 'new') => {
    setEditing(a);
    form.setFieldsValue(a === 'new' ? { username: '', displayName: '', email: '', roleIds: [] } : a);
  };

  const submitForm = async () => {
    let v: AdminInput;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const target = editing;
    setEditing(null);
    hr.ask({
      title: target === 'new' ? t('admins.createConfirm') : t('admins.editConfirm'),
      description: t('admins.confirmDesc', { name: v.displayName, username: target === 'new' ? v.username : (target as AdminUser).username, roles: v.roleIds.map(roleName).join('、') }),
      onConfirm: async () => {
        if (target === 'new') {
          const result = await createAdmin(v);
          setTempPwd({ name: v.displayName, username: v.username, result });
        } else {
          await updateAdmin((target as AdminUser).id, v);
        }
      },
      onDone: list.reload,
    });
  };

  const toggleStatus = (a: AdminUser) =>
    hr.ask({
      title: a.status === 'active' ? t('admins.disable') : t('admins.enable'),
      description: a.status === 'active' ? t('admins.disableDesc', { name: a.displayName }) : t('admins.enableDesc', { name: a.displayName }),
      onConfirm: (v) => setAdminStatus(a.id, a.status !== 'active', v.reason),
      onDone: list.reload,
    });

  const resetPwd = (a: AdminUser) =>
    hr.ask({
      title: t('admins.resetPwd'),
      description: t('admins.resetDesc', { name: a.displayName, email: a.email }),
      successText: t('admins.resetDone'),
      onConfirm: async (v) => {
        const result = await resetAdminPassword(a.id, v.reason);
        setTempPwd({ name: a.displayName, username: a.username, result });
      },
      onDone: list.reload,
    });

  const isNew = editing === 'new';
  return (
    <>
      <PageHeader
        title={t('admins.title')}
        sub={t('admins.sub')}
        extra={
          <PermButton perm="admin:edit" type="primary" icon={<PlusOutlined />} onClick={() => openForm('new')}>
            {t('admins.create')}
          </PermButton>
        }
      />
      <FilterBar onSearch={() => list.setFilters(draft)} onReset={() => { setDraft({}); list.setFilters({}); }}>
        <FilterItem label={t('admins.keyword')} htmlFor="a-kw">
          <Input id="a-kw" allowClear style={{ width: 220 }} placeholder={t('admins.keywordPh')} value={draft.keyword} onChange={(e) => setDraft({ keyword: e.target.value })} />
        </FilterItem>
      </FilterBar>
      <DataTable<AdminUser>
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
          {
            title: t('admins.account'),
            key: 'a',
            render: (_, r) => (
              <div className="user-cell">
                <b title={r.displayName}>{r.displayName}</b>
                <small className="ellipsis" style={{ maxWidth: 220 }} title={r.username}>{r.username}</small>
              </div>
            ),
          },
          { title: t('admins.email'), dataIndex: 'email' },
          {
            title: t('admins.roles'),
            dataIndex: 'roleIds',
            render: (v: string[]) => (
              <Space size={4} wrap>
                {v.map((id) => (
                  <Pill key={id} tone={id === superRoleId ? 'danger' : 'info'}>{roleName(id)}</Pill>
                ))}
              </Space>
            ),
          },
          {
            title: t('admins.status'),
            key: 's',
            render: (_, r) => (
              <Space size={4}>
                <Pill tone={r.status === 'active' ? 'ok' : 'muted'}>{t(`adminStatus.${r.status}`)}</Pill>
                {r.mustChangePassword ? <Pill tone="pend">{t('admins.mustChange')}</Pill> : null}
              </Space>
            ),
          },
          { title: t('admins.lastLogin'), dataIndex: 'lastLoginAt', render: (v: string | null) => (v ? <Num>{shortTime(v)}</Num> : t('customers.neverLogin')) },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) => {
              const protectedSuper = targetIsSuper(r) && !isSuper;
              const self = r.id === me?.admin.id;
              return (
                <Space size={4}>
                  <PermButton perm="admin:edit" size="small" icon={<EditOutlined />} disabled={protectedSuper} disabledReason={t('admins.superProtected')} onClick={() => openForm(r)}>
                    {t('common.edit')}
                  </PermButton>
                  <PermButton perm="admin:edit" size="small" icon={<KeyOutlined />} disabled={protectedSuper} disabledReason={t('admins.superProtected')} onClick={() => resetPwd(r)}>
                    {t('admins.resetPwd')}
                  </PermButton>
                  <PermButton
                    perm="admin:edit"
                    size="small"
                    danger={r.status === 'active'}
                    icon={r.status === 'active' ? <StopOutlined /> : <CheckCircleOutlined />}
                    disabled={protectedSuper || self}
                    disabledReason={self ? t('admins.cannotSelf') : t('admins.superProtected')}
                    onClick={() => toggleStatus(r)}
                  >
                    {r.status === 'active' ? t('admins.disable') : t('admins.enable')}
                  </PermButton>
                </Space>
              );
            },
          },
        ]}
      />
      <Modal
        open={editing !== null}
        title={isNew ? t('admins.create') : t('admins.edit')}
        okText={t('common.next')}
        cancelText={t('common.cancel')}
        onOk={() => void submitForm()}
        onCancel={() => setEditing(null)}
        destroyOnClose
        maskClosable={false}
      >
        <Form form={form} layout="vertical" name="admin-form" preserve={false}>
          <Form.Item name="username" label={t('admins.username')} extra={isNew ? t('admins.usernameHint') : undefined} rules={[{ required: true, pattern: USERNAME_RE, message: t('validation.usernameFormat') }]}>
            <Input disabled={!isNew} autoComplete="off" />
          </Form.Item>
          <Form.Item name="displayName" label={t('admins.displayName')} rules={[{ required: true, whitespace: true, message: t('validation.required') }, { max: 40 }]}>
            <Input maxLength={40} />
          </Form.Item>
          <Form.Item name="email" label={t('admins.email')} rules={[{ required: true, pattern: EMAIL_RE, message: t('validation.emailFormat') }]}>
            <Input autoComplete="off" />
          </Form.Item>
          <Form.Item name="roleIds" label={t('admins.roles')} rules={[{ required: true, type: 'array', min: 1, message: t('admins.roleRequired') }]} extra={!isSuper ? t('admins.superDisabledHint') : undefined}>
            <Select
              mode="multiple"
              options={(roles.data ?? []).map((r) => ({
                value: r.id,
                label: r.code === SUPER_ADMIN_ROLE && !isSuper ? t('admins.superOption', { name: localName(i18n.language, r.nameZh, r.nameEn) }) : localName(i18n.language, r.nameZh, r.nameEn),
                disabled: r.code === SUPER_ADMIN_ROLE && !isSuper,
              }))}
            />
          </Form.Item>
          {isNew ? <Alert type="info" showIcon message={t('admins.tempPwdHint')} /> : null}
        </Form>
      </Modal>
      {hr.element}
      <Modal
        open={!!tempPwd}
        title={t('admins.tempPwdTitle')}
        okText={t('admins.tempPwdSaved')}
        onOk={() => setTempPwd(null)}
        onCancel={() => setTempPwd(null)}
        footer={(_, { OkBtn }) => <OkBtn />}
        maskClosable={false}
        destroyOnClose
      >
        {tempPwd ? (
          <>
            <p>{t('admins.tempPwdFor', { name: tempPwd.name, username: tempPwd.username })}</p>
            <Typography.Paragraph
              className="num temp-pwd"
              aria-label={t('admins.tempPwdLabel')}
              copyable={{ text: tempPwd.result.temporaryPassword, tooltips: [t('common.copy'), t('common.copied')] }}
            >
              {tempPwd.result.temporaryPassword}
            </Typography.Paragraph>
            <Alert type="warning" showIcon message={t('admins.tempPwdOnce')} />
          </>
        ) : null}
      </Modal>
    </>
  );
}
