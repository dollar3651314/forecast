/**
 * 普通的"必填说明"弹窗：KYC 驳回原因、对账处理备注等（不是高危操作，不需要勾选确认）。
 */
import { useState } from 'react';
import { Alert, Form, Input, Modal } from 'antd';
import { useTranslation } from 'react-i18next';
import { errorText } from '../utils/errors';

export interface NoteModalProps {
  open: boolean;
  title: string;
  label: string;
  placeholder?: string;
  okText?: string;
  danger?: boolean;
  onCancel: () => void;
  onConfirm: (note: string) => Promise<unknown>;
}

export function NoteModal({ open, title, label, placeholder, okText, danger, onCancel, onConfirm }: NoteModalProps) {
  const { t } = useTranslation();
  const [form] = Form.useForm<{ note: string }>();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);

  const ok = async () => {
    setErr(null);
    let v: { note: string };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    setBusy(true);
    try {
      await onConfirm(v.note.trim());
      form.resetFields();
    } catch (e) {
      setErr(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      title={title}
      okText={okText ?? t('common.confirm')}
      cancelText={t('common.cancel')}
      okButtonProps={{ loading: busy, danger }}
      onOk={ok}
      onCancel={() => {
        setErr(null);
        form.resetFields();
        onCancel();
      }}
      destroyOnClose
      maskClosable={false}
    >
      <Form form={form} layout="vertical" preserve={false} name="note-modal">
        <Form.Item name="note" label={label} rules={[{ required: true, whitespace: true, message: t('common.requiredField', { field: label }) }, { max: 200, message: t('highRisk.reasonTooLong', { max: 200 }) }]}>
          <Input.TextArea rows={3} placeholder={placeholder} maxLength={200} showCount />
        </Form.Item>
      </Form>
      {err ? <Alert type="error" showIcon message={errorText(t, err)} /> : null}
    </Modal>
  );
}
