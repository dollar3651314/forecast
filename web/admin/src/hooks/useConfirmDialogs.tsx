/**
 * 页面里使用高危确认弹窗 / 备注弹窗的便捷方式：
 *   const hr = useHighRisk();
 *   hr.ask({ title, description, onConfirm: (v) => api.xxx(id, v.reason), onDone: reload });
 *   return <>{...}{hr.element}</>;
 * 成功后自动关闭弹窗并提示"操作成功"。
 */
import { useCallback, useState, type ReactNode } from 'react';
import { App } from 'antd';
import { useTranslation } from 'react-i18next';
import { HighRiskModal, type HighRiskValues } from '../components/HighRiskModal';
import { NoteModal } from '../components/NoteModal';
import { errorText } from '../utils/errors';

export interface HighRiskRequest {
  title: string;
  description?: ReactNode;
  extra?: ReactNode;
  initialValues?: Record<string, unknown>;
  confirmText?: string;
  successText?: string;
  onConfirm: (values: HighRiskValues) => Promise<unknown>;
  onDone?: () => void;
}

export function useHighRisk() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [req, setReq] = useState<HighRiskRequest | null>(null);
  const ask = useCallback((r: HighRiskRequest) => setReq(r), []);

  const element = (
    <HighRiskModal
      open={!!req}
      title={req?.title ?? ''}
      description={req?.description}
      extra={req?.extra}
      initialValues={req?.initialValues}
      confirmText={req?.confirmText}
      onCancel={() => setReq(null)}
      onConfirm={async (v) => {
        if (!req) return;
        await req.onConfirm(v);
        void message.success(req.successText ?? t('common.success'));
        setReq(null);
        req.onDone?.();
      }}
    />
  );
  return { ask, element };
}

export interface NoteRequest {
  title: string;
  label: string;
  placeholder?: string;
  okText?: string;
  danger?: boolean;
  successText?: string;
  onConfirm: (note: string) => Promise<unknown>;
  onDone?: () => void;
}

export function useNoteDialog() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [req, setReq] = useState<NoteRequest | null>(null);
  const ask = useCallback((r: NoteRequest) => setReq(r), []);
  const element = (
    <NoteModal
      open={!!req}
      title={req?.title ?? ''}
      label={req?.label ?? ''}
      placeholder={req?.placeholder}
      okText={req?.okText}
      danger={req?.danger}
      onCancel={() => setReq(null)}
      onConfirm={async (note) => {
        if (!req) return;
        await req.onConfirm(note);
        void message.success(req.successText ?? t('common.success'));
        setReq(null);
        req.onDone?.();
      }}
    />
  );
  return { ask, element };
}

/** 普通操作（不需要原因）：执行并提示成功或失败原因 */
export function useAction() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  return useCallback(
    async (fn: () => Promise<unknown>, successText?: string, onDone?: () => void) => {
      try {
        await fn();
        void message.success(successText ?? t('common.success'));
        onDone?.();
        return true;
      } catch (err) {
        void message.error(errorText(t, err));
        return false;
      }
    },
    [message, t],
  );
}
