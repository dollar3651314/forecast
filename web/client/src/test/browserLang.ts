// 客户端打开时默认跟随浏览器语言（i18n/index.ts，体验优化 1021 UX-1）；jsdom 的浏览器语言是 en-US。
// 单元测试按中文界面写：在加载 i18n 之前把浏览器语言设成 zh-CN（必须在 setup.ts 里排在 '../i18n' 前面导入）。
Object.defineProperty(window.navigator, 'languages', { value: ['zh-CN', 'zh'], configurable: true });
Object.defineProperty(window.navigator, 'language', { value: 'zh-CN', configurable: true });
