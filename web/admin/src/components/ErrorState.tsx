import { Button, Result } from 'antd';
import { useTranslation } from 'react-i18next';
import { errorText } from '../utils/errors';

/** 接口出错时：显示具体原因和"重试"按钮，不显示错误代码 */
export function ErrorState({ error, onRetry, compact }: { error: unknown; onRetry: () => void; compact?: boolean }) {
  const { t } = useTranslation();
  return (
    <Result
      status="warning"
      className={compact ? 'error-state compact' : 'error-state'}
      title={t('errors.loadFailed')}
      subTitle={errorText(t, error)}
      extra={
        <Button type="primary" onClick={onRetry}>
          {t('common.retry')}
        </Button>
      }
    />
  );
}
