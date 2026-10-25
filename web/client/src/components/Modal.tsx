/**
 * 弹窗：手机为底部弹出面板，桌面为居中对话框。
 * 打开时锁定背景滚动，Esc 关闭，焦点移入弹窗、关闭后还给触发元素。
 */
import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { Icon } from './Icon';
import { useBodyLock } from './useBodyLock';

export interface ModalProps {
  open: boolean;
  title: ReactNode;
  subtitle?: ReactNode;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  /** sheet：手机上从底部弹出；dialog：始终居中 */
  variant?: 'sheet' | 'dialog';
  size?: 'sm' | 'md' | 'lg';
}

export function Modal({ open, title, subtitle, onClose, children, footer, variant = 'sheet', size = 'md' }: ModalProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const boxRef = useRef<HTMLDivElement>(null);
  useBodyLock(open);

  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const box = boxRef.current;
    const first = box?.querySelector<HTMLElement>('[data-autofocus], input:not([type=hidden]):not([disabled]), select, textarea, button:not(.modal-x)');
    (first ?? box)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      if (e.key === 'Tab' && box) {
        // 简单的焦点循环，键盘用户不会跳到背景
        const items = [...box.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')].filter((x) => !x.hasAttribute('disabled'));
        if (items.length === 0) return;
        const a = items[0];
        const z = items[items.length - 1];
        if (e.shiftKey && document.activeElement === a) {
          e.preventDefault();
          z.focus();
        } else if (!e.shiftKey && document.activeElement === z) {
          e.preventDefault();
          a.focus();
        }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      prev?.focus?.();
    };
    // 只在打开时执行一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  if (!open) return null;
  return createPortal(
    <div className={`modal-root modal-${variant}`} role="presentation">
      <div className="modal-mask" onClick={onClose} />
      <div ref={boxRef} className={`modal-box modal-${size}`} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        {variant === 'sheet' && <div className="modal-grab" aria-hidden="true" />}
        <div className="modal-head">
          <h2 id={titleId}>
            {title}
            {subtitle && <small>{subtitle}</small>}
          </h2>
          <button type="button" className="modal-x" onClick={onClose} aria-label={t('common.close')}>
            <Icon name="close" size={14} strokeWidth={2.6} />
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}

export function ConfirmDialog(props: {
  open: boolean;
  title: ReactNode;
  children: ReactNode;
  confirmText: string;
  tone?: 'primary' | 'buy' | 'sell' | 'danger';
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Modal
      open={props.open}
      title={props.title}
      onClose={props.onClose}
      variant="dialog"
      size="sm"
      footer={
        <div className="btn-row">
          <button type="button" className="btn btn-ghost" onClick={props.onClose} disabled={props.busy}>
            {t('common.cancel')}
          </button>
          <button type="button" className={`btn btn-${props.tone ?? 'primary'}`} onClick={props.onConfirm} disabled={props.busy} data-autofocus>
            {props.busy ? t('common.submitting') : props.confirmText}
          </button>
        </div>
      }
    >
      {props.children}
    </Modal>
  );
}
