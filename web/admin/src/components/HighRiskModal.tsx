/**
 * 高危确认弹窗（规则 C46：所有高危操作统一用这一个组件，统一测试）。
 * - 必填操作原因（写入审计日志）
 * - 必须勾选"我已确认影响"
 * - 提交时一次显示全部错误（规则 C56）
 * - 接口失败时弹窗不关闭，显示具体原因
 *
 * High-risk confirmation dialog shared by every dangerous action.
 */
import { useState, type ReactNode } from 'react';
import { Alert, Checkbox, Form, Input, Modal, Space, Typography } from 'antd';
import { ExclamationCircleFilled } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { errorText } from '../utils/errors';

export const REASON_MAX = 200;

export interface HighRiskValues {
  reason: string;
  [field: string]: unknown;
}

export interface HighRiskModalProps {
  open: boolean;
  /** 操作名称，例如"冻结客户" */
  title: string;
  /** 操作对象和影响说明 */
  description?: ReactNode;
  /** 原因输入框上方的额外表单项（Form.Item），例如调整金额 */
  extra?: ReactNode;
  initialValues?: Record<string, unknown>;
  confirmText?: string;
  reasonLabel?: string;
  onCancel: () => void;
  /** 返回的 Promise 失败时弹窗保持打开并显示原因 */
  onConfirm: (values: HighRiskValues) => Promise<unknown>;
}

export function HighRiskModal({ open, title, description, extra, initialValues, confirmText, reasonLabel, onCancel, onConfirm }: HighRiskModalProps) {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<unknown>(null);

  const handleOk = async () => {
    setSubmitError(null);
    let values: HighRiskValues;
    try {
      values = await form.validateFields();
    } catch {
      return; // 校验错误已一次性显示在各字段下
    }
    setSubmitting(true);
    try {
      const { acknowledged: _ack, ...rest } = values as HighRiskValues & { acknowledged?: boolean };
      void _ack;
      await onConfirm({ ...rest, reason: String(rest.reason).trim() });
      form.resetFields();
    } catch (err) {
      setSubmitError(err);
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    setSubmitError(null);
    form.resetFields();
    onCancel();
  };

  return (
    <Modal
      open={open}
      title={
        <Space size={8}>
          <ExclamationCircleFilled style={{ color: 'var(--danger)' }} aria-hidden />
          <span>{title}</span>
          <span className="pill pill-danger">{t('highRisk.tag')}</span>
        </Space>
      }
      okText={confirmText ?? t('highRisk.confirm')}
      cancelText={t('common.cancel')}
      okButtonProps={{ danger: true, loading: submitting }}
      onOk={handleOk}
      onCancel={handleCancel}
      destroyOnClose
      maskClosable={false}
      width={520}
      className="high-risk-modal"
    >
      {description ? <div className="high-risk-desc">{description}</div> : null}
      <Form form={form} layout="vertical" initialValues={initialValues} requiredMark preserve={false} name="high-risk">
        {extra}
        <Form.Item
          name="reason"
          label={reasonLabel ?? t('highRisk.reason')}
          rules={[
            { required: true, whitespace: true, message: t('highRisk.reasonRequired') },
            { max: REASON_MAX, message: t('highRisk.reasonTooLong', { max: REASON_MAX }) },
          ]}
        >
          <Input.TextArea rows={3} placeholder={t('highRisk.reasonPlaceholder')} showCount maxLength={REASON_MAX} />
        </Form.Item>
        <Form.Item
          name="acknowledged"
          valuePropName="checked"
          rules={[{ validator: (_r, v) => (v ? Promise.resolve() : Promise.reject(new Error(t('highRisk.ackRequired')))) }]}
          style={{ marginBottom: 4 }}
        >
          <Checkbox>{t('highRisk.ack')}</Checkbox>
        </Form.Item>
      </Form>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {t('highRisk.auditNote')}
      </Typography.Text>
      {submitError ? <Alert type="error" showIcon style={{ marginTop: 12 }} message={t('highRisk.failed')} description={errorText(t, submitError)} /> : null}
    </Modal>
  );
}
