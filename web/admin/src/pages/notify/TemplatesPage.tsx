/**
 * 通知模板：列表和编辑。内置模板只能改文案，不能禁用（禁用会导致出金结算等流程失败）。
 * forecast S9（C6、C86）：需求第 7 节的编号（#1～#23）、受众（客户 / 按邮箱 / 员工）、员工模板的收件人（按权限点，SQ-14）；
 * 客户模板有简体、英文、繁体三种（繁体清空 = 按简体发）；文案里出现诱导收益的说法时提醒（市场策略 F-12、F-15，只提醒不拦）。
 * v2 品牌改名：数据库迁移 V20 只改了仍是原始默认文字的内置模板；运营改过的保留原样，这里提醒运营检查文案里还有旧品牌名的模板
 * （《v2-需求说明书》第 8 节）。
 */
import { useCallback, useState } from 'react';
import { Alert, Button, Drawer, Form, Input, Segmented, Space, Switch, Tooltip } from 'antd';
import { EditOutlined, LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { listTemplates, updateTemplate } from '../../api';
import type { NotifyTemplate } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useAction } from '../../hooks/useConfirmDialogs';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../components/PageHeader';
import { DataTable } from '../../components/DataTable';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Num } from '../../components/Num';
import { localName, shortTime } from '../../utils/display';
import { bannedIn } from '../../utils/market';

/** 旧品牌名（写成正则，构建产物里不出现旧品牌名的原文，scripts/check-brand.mjs） */
const OLD_BRAND = /falcon\s*x/i;
const hasOldBrand = (r: NotifyTemplate) => OLD_BRAND.test([r.titleZh, r.bodyZh, r.titleEn, r.bodyEn, r.titleTw, r.bodyTw].join('\n'));


type EditValues = Pick<NotifyTemplate, 'titleZh' | 'bodyZh' | 'titleEn' | 'bodyEn' | 'titleTw' | 'bodyTw' | 'enabled'>;
type Audience = 'all' | NotifyTemplate['audience'];

export function TemplatesPage() {
  const { t, i18n } = useTranslation();
  const { can } = useAuth();
  const run = useAction();
  const fetcher = useCallback(() => listTemplates(), []);
  const { data, loading, error, reload } = useApi(fetcher);
  const [editing, setEditing] = useState<NotifyTemplate | null>(null);
  const [busy, setBusy] = useState(false);
  const [audience, setAudience] = useState<Audience>('all');
  const [form] = Form.useForm<EditValues>();
  const watched = Form.useWatch([], form) as EditValues | undefined;
  const banned = watched ? bannedIn([watched.titleZh, watched.bodyZh, watched.titleEn, watched.bodyEn, watched.titleTw, watched.bodyTw].join('\n')) : [];

  const save = async () => {
    if (!editing) return;
    let v: EditValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    setBusy(true);
    const patch = { ...v, titleTw: v.titleTw ?? '', bodyTw: v.bodyTw ?? '' };
    const ok = await run(() => updateTemplate(editing.id, editing.builtIn ? { ...patch, enabled: true } : patch), t('common.saved'), reload);
    setBusy(false);
    if (ok) setEditing(null);
  };

  const oldBrand = (data ?? []).filter(hasOldBrand).map((r) => r.code);
  const required = [{ required: true, whitespace: true, message: t('validation.required') }];
  const rows = (data ?? []).filter((r) => audience === 'all' || r.audience === audience);
  /** 繁体：标题和正文要么都填、要么都空（都空 = 按简体发） */
  const twPair = (other: 'titleTw' | 'bodyTw') => ({
    validator: (_r: unknown, v: string | undefined) => {
      const o = (form.getFieldValue(other) as string | undefined) ?? '';
      return !!v?.trim() === !!o.trim() ? Promise.resolve() : Promise.reject(new Error(t('templates.twPair')));
    },
  });

  return (
    <>
      <PageHeader title={t('templates.title')} sub={t('templates.sub')} />
      <Alert type="info" showIcon icon={<LockOutlined />} message={t('templates.builtInHint')} />
      {oldBrand.length ? <Alert type="warning" showIcon style={{ marginTop: 8 }} data-testid="old-brand-hint" message={t('templates.oldBrandHint', { codes: oldBrand.join(i18n.language.startsWith('zh') ? '、' : ', ') })} /> : null}
      <div className="fc-bar">
        <Segmented<Audience>
          value={audience}
          onChange={setAudience}
          options={(['all', 'user', 'any', 'staff'] as const).map((a) => ({ value: a, label: `${t(`templates.audience.${a}`)} ${(data ?? []).filter((r) => a === 'all' || r.audience === a).length}` }))}
        />
        <span className="muted">{t('templates.reqHint')}</span>
      </div>
      <DataTable<NotifyTemplate>
        rowKey="id"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={rows}
        columns={[
          { title: t('templates.reqNo'), dataIndex: 'reqNo', render: (v: string | null) => (v ? <Num strong>{v}</Num> : <span className="muted">—</span>) },
          { title: t('templates.code'), dataIndex: 'code', render: (v: string) => <Num>{v}</Num> },
          { title: t('templates.name'), key: 'n', render: (_, r) => <b>{localName(i18n.language, r.nameZh, r.nameEn)}</b> },
          { title: t('templates.type'), dataIndex: 'builtIn', render: (v: boolean) => (v ? <Pill tone="info">{t('templates.builtIn')}</Pill> : <Pill tone="muted">{t('templates.custom')}</Pill>) },
          { title: t('templates.channels'), dataIndex: 'channels', render: (v: string[]) => v.map((c) => t(`channel.${c}`)).join(' · ') },
          {
            title: t('templates.recipients'),
            key: 'to',
            render: (_, r) =>
              r.audience === 'staff' ? (
                r.staffPerms.length ? (
                  <span title={r.staffPerms.join(', ')}>{t('templates.byRole', { perms: r.staffPerms.join(' / ') })}</span>
                ) : (
                  <span className="muted">{t('templates.alertBox')}</span>
                )
              ) : (
                t(`templates.audience.${r.audience}`)
              ),
          },
          { title: t('templates.langs'), key: 'tw', render: (_, r) => (r.audience === 'staff' ? t('templates.zhEn') : r.titleTw ? t('templates.threeLangs') : <Pill tone="warn">{t('templates.noTw')}</Pill>) },
          { title: t('templates.titleCol'), key: 'tt', render: (_, r) => <span className="ellipsis" style={{ maxWidth: 220 }}>{i18n.language.startsWith('zh') ? r.titleZh : r.titleEn}</span> },
          { title: t('templates.status'), dataIndex: 'enabled', render: (v: boolean) => <Pill tone={v ? 'ok' : 'muted'}>{v ? t('templates.enabled') : t('templates.disabled')}</Pill> },
          { title: t('templates.updatedAt'), dataIndex: 'updatedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) => (
              <PermButton
                perm="notify_template:edit"
                size="small"
                icon={<EditOutlined />}
                onClick={() => {
                  setEditing(r);
                  form.setFieldsValue(r);
                }}
              >
                {t('common.edit')}
              </PermButton>
            ),
          },
        ]}
      />
      <Drawer
        open={!!editing}
        onClose={() => setEditing(null)}
        title={editing ? t('templates.editTitle', { name: localName(i18n.language, editing.nameZh, editing.nameEn) }) : ''}
        width={Math.min(560, typeof window !== 'undefined' ? window.innerWidth : 560)}
        destroyOnClose
        footer={
          <Space style={{ justifyContent: 'flex-end', width: '100%' }}>
            <Button onClick={() => setEditing(null)}>{t('common.cancel')}</Button>
            <Button type="primary" loading={busy} onClick={() => void save()} disabled={!can('notify_template:edit')}>
              {t('common.save')}
            </Button>
          </Space>
        }
      >
        {editing ? (
          <Form form={form} layout="vertical" name="template-edit">
            {editing.variables.length ? (
              <Alert
                type="info"
                style={{ marginBottom: 12 }}
                message={t('templates.variables')}
                description={<Num>{editing.variables.map((v) => `{${v}}`).join('  ')}</Num>}
              />
            ) : null}
            {editing.audience === 'staff' ? (
              <Alert type="info" style={{ marginBottom: 12 }} message={editing.staffPerms.length ? t('templates.byRoleLong', { perms: editing.staffPerms.join(' / ') }) : t('templates.alertBoxLong')} />
            ) : null}
            {banned.length ? <Alert type="warning" showIcon style={{ marginBottom: 12 }} data-testid="banned-hint" message={t('templates.banned', { words: banned.join('、') })} /> : null}
            <Form.Item name="titleZh" label={t('templates.titleZh')} rules={required}>
              <Input maxLength={200} />
            </Form.Item>
            <Form.Item name="bodyZh" label={t('templates.bodyZh')} rules={required}>
              <Input.TextArea rows={4} maxLength={1000} showCount />
            </Form.Item>
            <Form.Item name="titleEn" label={t('templates.titleEn')} rules={required}>
              <Input maxLength={200} />
            </Form.Item>
            <Form.Item name="bodyEn" label={t('templates.bodyEn')} rules={required}>
              <Input.TextArea rows={4} maxLength={1000} showCount />
            </Form.Item>
            {editing.audience !== 'staff' ? (
              <>
                <Form.Item name="titleTw" label={t('templates.titleTw')} dependencies={['bodyTw']} rules={[twPair('bodyTw')]} extra={t('templates.twHint')}>
                  <Input maxLength={200} />
                </Form.Item>
                <Form.Item name="bodyTw" label={t('templates.bodyTw')} dependencies={['titleTw']} rules={[twPair('titleTw')]}>
                  <Input.TextArea rows={4} maxLength={1000} showCount />
                </Form.Item>
              </>
            ) : null}
            <Form.Item
              name="enabled"
              label={t('templates.status')}
              valuePropName="checked"
              extra={editing.builtIn ? <span className="text-warn">{t('templates.cannotDisable')}</span> : undefined}
            >
              {editing.builtIn ? (
                <Tooltip title={t('templates.cannotDisable')}>
                  <Switch disabled checkedChildren={t('templates.enabled')} />
                </Tooltip>
              ) : (
                <Switch checkedChildren={t('templates.enabled')} unCheckedChildren={t('templates.disabled')} />
              )}
            </Form.Item>
          </Form>
        ) : null}
      </Drawer>
    </>
  );
}
