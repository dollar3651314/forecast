import '@testing-library/jest-dom/vitest';
import { setLatency } from '../mocks/runtime';

// jsdom 没有 matchMedia（Ant Design 的响应式依赖它）
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: query.includes('min-width') ? true : false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }) as MediaQueryList;
}
// 测试里去掉模拟延迟
setLatency({ min: 0, max: 0, slow: 0 });
const origGCS = window.getComputedStyle;
window.getComputedStyle = (elt: Element) => origGCS(elt);
