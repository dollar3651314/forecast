/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// 客户端部署在站点根路径 `/`；后台在 `/admin/`（另一个工程）。
// 接口模式 VITE_API_MODE（src/api/mode.ts）：默认 real（同源 /api）；原型构建（scripts/build-prototype.sh、Vercel）设为 mock。
// 本地开发：`npm run dev` 把 /api（含 WebSocket）转发到后端，地址用 FC_API_PROXY 指定，默认 http://localhost:8080；
// 后端要设 FC_COOKIE_SECURE=false，否则 http 下浏览器不保存登录 Cookie。
// 真实接口模式（VITE_API_MODE 不是 mock）的构建不打包原型的模拟层 src/mocks/：
// 页面只经 src/api/ 取数据，那里按编译期常量 IS_MOCK 二选一，real 构建里模拟层的导出没有人用；
// 这里再声明 src/mocks/ 下的模块没有副作用，Rollup 就把它们整个去掉（模拟数据在模块顶层生成，否则会被当成副作用留下）。
// 原型（mock）构建和单元测试不受影响。验证方法见《项目索引》问题索引 GAP-5。
const MOCKS_DIR = /[\\/]src[\\/]mocks[\\/]/;
const mockFree = (mode: string) => {
  const isMock = loadEnv(mode, process.cwd(), 'VITE_').VITE_API_MODE === 'mock';
  return { moduleSideEffects: (id: string) => isMock || !MOCKS_DIR.test(id) };
};

export default defineConfig(({ mode }) => ({
  base: '/',
  server: {
    proxy: {
      '/api': { target: process.env.FC_API_PROXY || 'http://localhost:8080', ws: true },
    },
  },
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      injectRegister: 'auto',
      includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
      manifest: {
        // 品牌名占位，和 src/lib/brand.ts 一致（品牌待 Amos 确认）
        name: 'Quick Come',
        short_name: 'Quick Come',
        description: 'Quick Come — USDT 预测市场（彩池）',
        lang: 'zh-CN',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#ffffff',
        theme_color: '#1d4ed8',
        icons: [
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
          { src: 'favicon.svg', sizes: 'any', type: 'image/svg+xml' },
        ],
      },
      workbox: {
        // 后台 /admin/ 是另一个应用、/api/ 是后端、原型站的 /site/ 是官网（v2），客户端的 Service Worker 不能接管它们的导航请求
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/admin/, /^\/api/, /^\/site/],
        // 收到 Web Push 后显示系统通知、点击打开站内信（public/push-sw.js，S17）
        importScripts: ['push-sw.js'],
        globPatterns: ['**/*.{js,css,html,svg,png,ico,webmanifest}'],
      },
    }),
  ],
  build: { outDir: 'dist', chunkSizeWarningLimit: 900, rollupOptions: { treeshake: mockFree(mode) } },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    css: false,
    // 页面和模拟层的测试按原型模式运行；真实接口层的测试直接引用 src/api/real/（用 fetch 替身）
    env: { VITE_API_MODE: 'mock' },
  },
}));
