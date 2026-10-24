import '@testing-library/jest-dom/vitest';
import { setMockDelay } from '../mocks/delay';
import './browserLang';
import '../i18n';

// 测试里不需要模拟网络延迟
setMockDelay(0, 0);

// jsdom 没有 matchMedia
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({ matches: false, media: query, onchange: null, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false }) as MediaQueryList;
}

// 模拟接口在测试结束后仍可能回调（报价订阅、推送），这类 act 警告不代表问题，过滤掉以免淹没真正的错误
const origError = console.error;
console.error = (...args: unknown[]) => {
  if (typeof args[0] === 'string' && args[0].includes('not wrapped in act')) return;
  origError(...args);
};
