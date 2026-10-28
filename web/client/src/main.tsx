import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './i18n';
import './styles/tokens.css';
import './styles/base.css';
import './styles/layout.css';
import './styles/components.css';
import './styles/pages.css';
import './styles/market.css';
import App from './App';
import PrelaunchApp from './app/PrelaunchApp';
import { initBannerSpace } from './lib/bannerSpace';
import { isPrelaunch } from './lib/siteMode';

// 真实模式没有原型横幅：顶部不再预留 24px（测试环境显示标识条时 EnvBanner 再恢复，lib/bannerSpace.ts）
initBannerSpace();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* S18：对外预热域名只渲染等候页（Nginx 在页面里填 fc-site-mode=prelaunch，lib/siteMode.ts） */}
    {isPrelaunch() ? <PrelaunchApp /> : <App />}
  </StrictMode>,
);
