import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { loadEnvLabel } from '../api/envLabel';

/** 根元素上的类名：显示标识条时恢复顶部横幅的高度（真实模式默认是 0，见 global.css） */
export const ENV_ON_CLASS = 'env-on';

/**
 * 测试环境标识条（S20）：真实模式下后端健康检查返回非空 `env` 时，在页面顶部原型横幅的位置显示"测试环境"。
 * 和原型横幅一样占顶部一条（--banner-h），顶栏、侧栏、菜单抽屉都往下让，不遮挡内容；文字过长时省略，不会出现横向滚动。
 * 原型（mock）模式不显示（那里有原型横幅）。
 */
export function EnvBanner() {
  const { t } = useTranslation();
  const [env, setEnv] = useState('');
  useEffect(() => {
    let alive = true;
    void loadEnvLabel().then((v) => {
      if (!alive) return;
      setEnv(v);
      if (v) document.documentElement.classList.add(ENV_ON_CLASS);
    });
    return () => {
      alive = false;
    };
  }, []);
  if (!env) return null;
  return (
    <div className="env-banner" role="note" data-testid="env-banner">
      {t('env.banner')}
    </div>
  );
}
