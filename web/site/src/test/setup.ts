import '@testing-library/jest-dom/vitest';

// jsdom 没有 matchMedia：默认按手机宽度（不匹配电脑版的媒体查询）
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({ matches: false, media: query, onchange: null, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false }) as MediaQueryList;
}
