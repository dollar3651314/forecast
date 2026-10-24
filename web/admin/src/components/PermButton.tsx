/**
 * 带权限的按钮：没有权限时置灰并提示"无权限"。
 */
import { Button, Tooltip, type ButtonProps } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';

export interface PermButtonProps extends ButtonProps {
  perm: string;
  /** 业务上不可用时的提示（例如"仅延迟期内可用"） */
  disabledReason?: string;
}

export function PermButton({ perm, disabled, disabledReason, children, ...rest }: PermButtonProps) {
  const { t } = useTranslation();
  const { can } = useAuth();
  const allowed = can(perm);
  const tip = !allowed ? t('common.noPermission') : disabled ? disabledReason : undefined;
  const btn = (
    <Button {...rest} disabled={!allowed || disabled} aria-disabled={!allowed || disabled} data-no-permission={!allowed || undefined}>
      {children}
    </Button>
  );
  return tip ? <Tooltip title={tip}>{btn}</Tooltip> : btn;
}

/** 没有权限时的小提示文字（用在开关、表单旁边） */
export function useCan() {
  return useAuth().can;
}
