/**
 * P14 说明页（S13，MF-4；需求第 5.2 节 P14）：怎么玩（彩池怎么算、截止时确定赔率、只有一边有人押全额退款）、怎么充（三条链）、
 * 怎么提（单笔最少提现、证件 + 人脸认证、新地址生效时间、冷静期、大额延迟、盈利提现人工复核）。
 * 文字由后端按语言给（resources/content/public-pages.json，搜索引擎看到的是同一份），数字是参数设置里的当前值（C6：不写死）。不登录可看。
 */
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { getHowTo } from '../api/public';
import { useAuth } from '../app/contexts';
import { ErrorState, Loading } from '../components/AsyncView';
import { GuestGate } from '../components/GuestGate';
import { PageHeader } from '../components/Layout';
import { PublicLinks } from '../components/PublicLinks';
import { useAsync } from '../hooks/useAsync';
import { useLang } from '../hooks/useLang';
import { useTitle } from '../hooks/useTitle';

export default function HowTo() {
  const { t } = useTranslation();
  const lang = useLang();
  const { session } = useAuth();
  const s = useAsync(() => getHowTo(lang), `howto|${lang}`);
  useTitle(s.data?.h1 ?? t('pub.howTo'));
  return (
    <div className="page page-howto">
      <PageHeader title={s.data?.h1 ?? t('pub.howTo')} />
      <GuestGate error={s.error} />
      {s.error && !s.data ? (
        <section className="card">
          <ErrorState error={s.error} onRetry={s.reload} />
        </section>
      ) : !s.data ? (
        <section className="card">
          <Loading rows={6} />
        </section>
      ) : (
        <>
          <p className="howto-intro">{s.data.intro}</p>
          {s.data.sections.map((sec) => (
            <section key={sec.id} id={sec.id} className="card howto-sec">
              <h2>{sec.heading}</h2>
              <ol>
                {sec.items.map((i) => (
                  <li key={i}>{i}</li>
                ))}
              </ol>
            </section>
          ))}
          <div className="howto-cta">
            <Link to="/markets" className="btn btn-primary">
              {t('pub.toMarkets')}
            </Link>
            {!session && (
              <Link to="/register" className="btn btn-outline">
                {t('guest.register')}
              </Link>
            )}
          </div>
        </>
      )}
      <PublicLinks />
    </div>
  );
}
