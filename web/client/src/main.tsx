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
import { initBannerSpace } from './lib/bannerSpace';

// 真实模式没有原型横幅：顶部不再预留 24px（测试环境显示标识条时 EnvBanner 再恢复，lib/bannerSpace.ts）
initBannerSpace();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
