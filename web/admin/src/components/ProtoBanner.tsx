import { useTranslation } from 'react-i18next';
import { IS_MOCK } from '../api/mode';

/** 原型标识：页面顶部固定的细横幅，不能关闭。只在 mock 模式（原型预览站）下显示 */
export function ProtoBanner() {
  const { t } = useTranslation();
  if (!IS_MOCK) return null;
  return (
    <div className="proto-banner" role="note">
      {t('proto.banner')}
    </div>
  );
}
