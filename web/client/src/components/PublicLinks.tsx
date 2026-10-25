/**
 * 不登录也能看的几页的入口（S13，需求第 5.1 节：开奖记录、周报、说明页放在页脚和"我的 → 规则说明"里，不加底部导航入口）。
 */
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

export function PublicLinks() {
  const { t } = useTranslation();
  return (
    <nav className="pub-links" aria-label={t('pub.linksLabel')}>
      <Link to="/results">{t('pub.results')}</Link>
      <span aria-hidden="true">·</span>
      <Link to="/results/weekly">{t('pub.weekly')}</Link>
      <span aria-hidden="true">·</span>
      <Link to="/how-to-play">{t('pub.howTo')}</Link>
    </nav>
  );
}
