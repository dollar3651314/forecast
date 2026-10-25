/**
 * 官网的整体布局：测试环境标识 → 风险提示条（常驻，W9）→ 导航 → 页面 → 页脚；手机版底部固定"登录 | 免费注册"（W8）。
 * 按宽度 1024 切换电脑、手机两套结构（C64）。
 */
import { useEffect } from 'react';
import { RouterProvider } from './app/router';
import { useRouter } from './app/routerContext';
import { EnvBanner, Footer, HeaderDesktop, HeaderMobile, RiskBar, StickyCta } from './components/Chrome';
import { useIsDesktop } from './hooks/useMediaQuery';
import { useI18n } from './i18n';
import { LangProvider } from './i18n/I18nProvider';
import type { Lang } from './i18n/lang';
import { HomeDesktop, HomeMobile } from './pages/Home';
import { LegalPage } from './pages/Legal';

function Shell() {
  const desktop = useIsDesktop();
  const { page } = useRouter();
  const { t } = useI18n();
  // 直接打开带锚点的地址（例如 /#faq）时滚到对应区块
  useEffect(() => {
    const id = window.location.hash.slice(1);
    if (id) requestAnimationFrame(() => document.getElementById(id)?.scrollIntoView());
  }, []);
  let body;
  if (page === 'terms' || page === 'privacy' || page === 'risk') body = <LegalPage which={page} />;
  else if (page === 'home') body = desktop ? <HomeDesktop /> : <HomeMobile />;
  else
    body = (
      <main className="legal">
        <div className="wrap">
          <h1>{t('notFound.title')}</h1>
        </div>
      </main>
    );
  return (
    <div className={`site ${desktop ? 'is-desktop' : 'is-mobile'}`} data-layout={desktop ? 'desktop' : 'mobile'}>
      <EnvBanner />
      <RiskBar short={!desktop} />
      {desktop ? <HeaderDesktop /> : <HeaderMobile />}
      {body}
      <Footer />
      {!desktop && <StickyCta />}
    </div>
  );
}

export default function App({ initialLang }: { initialLang?: Lang }) {
  return (
    <LangProvider initial={initialLang}>
      <RouterProvider>
        <Shell />
      </RouterProvider>
    </LangProvider>
  );
}
