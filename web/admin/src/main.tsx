import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { Providers } from './Providers';
import { App } from './App';
import './i18n';
import './styles/global.css';
import './styles/markets.css';
import { IS_MOCK } from './api/mode';
import { BRAND_NAME } from './lib/brand';

// 真实模式没有原型横幅，布局不再给它留高度
if (!IS_MOCK) {
  document.documentElement.classList.add('no-proto');
  document.title = `${BRAND_NAME} 管理后台`;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter basename="/admin">
      <Providers>
        <App />
      </Providers>
    </BrowserRouter>
  </StrictMode>,
);
