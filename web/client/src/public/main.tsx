/**
 * 公开版入口（forecast S19，P11）：Cloudflare 上的 www 只有等候页兼看板、邮件里的确认和退订（S18 的 PrelaunchApp）。
 * 和 src/main.tsx 的区别：这里**只** import PrelaunchApp，交易页面（登录、注册、市场、下注、资产、我的）不会被打包进来；
 * 构建配置 vite.public.config.ts 另外把"登录后才用的接口模块"换成空实现（./stubs/），产物里连这些接口地址都没有（cf/scripts/check-dist.mjs 检查）。
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../i18n';
import '../styles/tokens.css';
import '../styles/base.css';
import '../styles/layout.css';
import '../styles/components.css';
import '../styles/pages.css';
import '../styles/market.css';
import PrelaunchApp from '../app/PrelaunchApp';
import { initBannerSpace } from '../lib/bannerSpace';

initBannerSpace();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <PrelaunchApp />
  </StrictMode>,
);
