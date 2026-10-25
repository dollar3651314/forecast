/**
 * 手动发送通知给单个客户（高危：二次确认，写审计）。不支持群发（第一版推迟）。
 */
import { useState } from 'react';
import { Alert, Button, Checkbox, Form, Input, Select, Spin } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { searchCustomers, sendManualNotification } from '../../api';
import type { CustomerSummary, NotifyChannel } from '../../api/types';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { useAuth } from '../../auth/AuthContext';
import { PageHeader } from '../../components/PageHeader';

interface Values {
  customerId: number;
  channels: NotifyChannel[];
  title: string;
  body: string;
}

export function SendPage() {
  const { t } = useTranslation();
  const { can } = useAuth();
  const [form] = Form.useForm<Values>();
  const [options, setOptions] = useState<CustomerSummary[]>([]);
  const [searching, setSearching] = useState(false);
  const hr = useHighRisk();

  const search = async (kw: string) => {
    setSearching(true);
    try {
      setOptions(await searchCustomers(kw));
    } finally {
      setSearching(false);
    }
  };

  const submit = async () => {
    let v: Values;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const c = options.find((o) => o.id === v.customerId);
    hr.ask({
      title: t('send.confirmTitle'),
      description: t('send.confirmDesc', { name: c?.name ?? v.customerId, id: v.customerId, channels: v.channels.map((x) => t(`channel.${x}`)).join('、'), title: v.title }),
      confirmText: t('send.send'),
      successText: t('send.sent'),
      onConfirm: (r) => sendManualNotification({ ...v, title: v.title.trim(), body: v.body.trim(), reason: r.reason }),
      onDone: () => form.resetFields(),
    });
  };

  return (
    <>
      <PageHeader title={t('send.title')} sub={t('send.sub')} />
      <Alert type="warning" showIcon message={t('send.hint')} />
      <div className="card card-pad" style={{ maxWidth: 720 }}>
        <Form form={form} layout="vertical" name="manual-send" initialValues={{ channels: ['inbox'] }} onFocus={() => options.length || void search('')}>
          <Form.Item name="customerId" label={t('send.customer')} rules={[{ required: true, message: t('send.customerRequired') }]}>
            <Select
              showSearch
              filterOption={false}
              onSearch={(kw) => void search(kw)}
              onDropdownVisibleChange={(open) => open && !options.length && void search('')}
              notFoundContent={searching ? <Spin size="small" /> : t('common.empty')}
              placeholder={t('send.customerPh')}
              options={options.map((c) => ({ value: c.id, label: `${c.name} · ${c.email} · ID ${c.id}` }))}
            />
          </Form.Item>
          <Form.Item name="channels" label={t('send.channels')} rules={[{ required: true, type: 'array', min: 1, message: t('send.channelsRequired') }]} extra={t('send.channelsHint')}>
            <Checkbox.Group
              options={(['inbox', 'push', 'email'] as const).map((c) => ({ value: c, label: t(`channel.${c}`) }))}
            />
          </Form.Item>
          <Form.Item name="title" label={t('send.titleLabel')} rules={[{ required: true, whitespace: true, message: t('validation.required') }, { max: 60 }]}>
            <Input maxLength={60} showCount />
          </Form.Item>
          <Form.Item name="body" label={t('send.body')} rules={[{ required: true, whitespace: true, message: t('validation.required') }, { max: 500 }]}>
            <Input.TextArea rows={5} maxLength={500} showCount />
          </Form.Item>
          <Button type="primary" icon={<SendOutlined />} onClick={() => void submit()} disabled={!can('notify:send')}>
            {t('send.next')}
          </Button>
        </Form>
      </div>
      {hr.element}
    </>
  );
}
