/**
 * 自动刷新开关（v2 E7）：显示"每 5 秒自动刷新"，可以暂停 / 继续；页面不可见时显示已停止。
 */
import { Button } from 'antd';
import { PauseOutlined, CaretRightOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AutoRefresh } from '../hooks/useAutoRefresh';

export function AutoRefreshControl({ auto }: { auto: AutoRefresh }) {
  const { t } = useTranslation();
  const seconds = Math.round(auto.intervalMs / 1000);
  const status = auto.paused ? t('autoRefresh.paused') : auto.visible ? t('autoRefresh.on', { seconds }) : t('autoRefresh.hidden');
  return (
    <span className="auto-refresh" role="status" aria-live="polite">
      <span>{status}</span>
      <Button
        size="small"
        icon={auto.paused ? <CaretRightOutlined /> : <PauseOutlined />}
        onClick={() => auto.setPaused(!auto.paused)}
        aria-pressed={auto.paused}
      >
        {auto.paused ? t('autoRefresh.resume') : t('autoRefresh.pause')}
      </Button>
    </span>
  );
}
