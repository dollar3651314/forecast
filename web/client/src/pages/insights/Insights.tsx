/**
 * 数据和文章列表 /insights（forecast S15，MF-11）：焦点战页（每场一页）、BTC 每小时涨跌统计、文章（都经 Amos 审核发布；数据实时）。不登录可看。
 * 搜索引擎看到的是后端 SSR 的同一份内容（Nginx SSI，content/page/InsightsProvider）。
 */
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { listInsights } from '../../api/public';
import { EmptyState, ErrorState, Loading } from '../../components/AsyncView';
import { GuestGate } from '../../components/GuestGate';
import { Icon } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { PublicLinks } from '../../components/PublicLinks';
import { useAsync } from '../../hooks/useAsync';
import { useLang } from '../../hooks/useLang';
import { useTitle } from '../../hooks/useTitle';
import { utcText } from '../../lib/market';

export default function Insights() {
  const { t } = useTranslation();
  const lang = useLang();
  useTitle(t('ins.title'));
  const s = useAsync(() => listInsights(lang), `insights|${lang}`);
  return (
    <div className="page page-insights">
      <PageHeader title={t('ins.title')} />
      <p className="res-intro muted">{t('ins.intro')}</p>
      <GuestGate error={s.error} />
      {s.error && !s.data ? (
        <section className="card">
          <ErrorState error={s.error} onRetry={s.reload} />
        </section>
      ) : !s.data ? (
        <section className="card">
          <Loading rows={4} />
        </section>
      ) : s.data.items.length === 0 ? (
        <section className="card">
          <EmptyState title={t('ins.empty')} />
        </section>
      ) : (
        <ul className="ins-list">
          {s.data.items.map((i) => (
            <li key={i.slug}>
              <Link to={`/insights/${i.slug}`} className="ins-card" data-testid="insight-item">
                <div className="ins-head">
                  <span className={`ins-kind k-${i.kind.toLowerCase()}`}>{t(`ins.kind.${i.kind}`)}</span>
                  <b>{i.title}</b>
                  <Icon name="chevronRight" size={16} className="chev" />
                </div>
                {i.summary && <p className="ins-summary">{i.summary}</p>}
                <span className="muted small num">{t('ins.updated', { at: utcText(i.updatedAt, true) })}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <PublicLinks />
    </div>
  );
}
