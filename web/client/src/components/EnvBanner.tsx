/**
 * 测试环境标识条（S20）：real 模式下后端健康检查返回非空 `env` 时，在页面顶部原型横幅的位置显示"测试环境"。
 * 固定在顶部预留的横幅高度里（--banner-h），不遮挡页面内容，也不接收点击；文字过长时省略，不会出现横向滚动。
 * mock 模式不显示（那里有原型横幅）。
 */
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { loadEnvLabel } from '../api/envLabel';
import { Icon } from './Icon';

export function EnvBanner() {
  const { t } = useTranslation();
  const [env, setEnv] = useState('');
  useEffect(() => {
    let alive = true;
    void loadEnvLabel().then((v) => {
      if (alive) setEnv(v);
    });
    return () => {
      alive = false;
    };
  }, []);
  if (!env) return null;
  return (
    <div className="env-banner" role="note" data-testid="env-banner">
      <Icon name="warn" size={12} strokeWidth={2.2} />
      {t('env.banner')}
    </div>
  );
}
