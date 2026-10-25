/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// 后台部署在 /admin/ 子路径下（与客户端合并为同一个静态站点）
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
  base: '/admin/',
  plugins: [react()],
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 2000,
    rollupOptions: { treeshake: mockFree(mode) },
  },
  preview: { port: 4174 },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // 页面测试跑原型（mock）模式；真实接口层由 src/api/*.test.ts 直接测（fetch 替身）
    env: { VITE_API_MODE: 'mock' },
    css: false,
    testTimeout: 20000,
  },
}));
