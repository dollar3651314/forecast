import type { ReactNode } from 'react';
import { Result } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { IS_MOCK } from '../api/mode';

/** 页面级权限：没有查看权限时显示 403 说明（菜单里也不会出现该入口） */
export function RequirePerm({ perm, children }: { perm?: string; children: ReactNode }) {
  const { t } = useTranslation();
  const { can } = useAuth();
  if (!can(perm)) {
    return <Result status="403" title={t('errors.forbiddenTitle')} subTitle={IS_MOCK ? t('errors.forbiddenPage') : t('errors.forbiddenPageReal')} />;
  }
  return <>{children}</>;
}
