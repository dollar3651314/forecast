/**
 * 首页（占位，forecast S1）：复制源的行情页删除后，登录后先落在这里。市场列表、市场详情、下注按需求说明书第 5 节在原型（M1）和 S11 做；
 * 这里只引导客户先充值、完成身份认证。文案在 i18n 的 home.*（PM 负责，C6）。
 */
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { EmptyState } from '../components/AsyncView';
import { PageHeader } from '../components/Layout';

export default function Home() {
  const { t } = useTranslation();
  return (
    <div className="page page-home">
      <PageHeader title={t('home.title')} />
      <section className="card">
        <EmptyState
          title={t('home.title')}
          hint={t('home.body')}
          action={
            <div className="acts-inline">
              <Link to="/assets" className="btn btn-primary">
                {t('home.goAssets')}
              </Link>
              <Link to="/me/kyc" className="btn btn-ghost">
                {t('home.goKyc')}
              </Link>
            </div>
          }
        />
      </section>
    </div>
  );
}
