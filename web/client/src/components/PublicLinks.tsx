/**
 * 不登录也能看的几页的入口（S13，需求第 5.1 节：开奖记录、周报、说明页（S15 加数据和文章 /insights）放在页脚和"我的 → 规则说明"里，不加底部导航入口）。
 * S16：加"数据"页（P13，上线后是首页旁边的数据页；框架的导航位置不动，C90）。
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
      <span aria-hidden="true">·</span>
      <Link to="/insights">{t('pub.insights')}</Link>
      <span aria-hidden="true">·</span>
      <Link to="/data">{t('pub.data')}</Link>
    </nav>
  );
}
