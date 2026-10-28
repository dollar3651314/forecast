/// <reference types="vitest/config" />
/**
 * 客户端的"公开版"构建（forecast S19，P11）：Cloudflare 上 www 的等候页兼看板。`npm run build:public` → `dist-public/`，
 * 再由 cf/scripts/build.mjs 把 `public.html` 改名成 `index.html` 放进 Worker 的静态资源目录。
 * 和 vite.config.ts 的区别：
 * - 入口是 public.html → src/public/main.tsx（只有 PrelaunchApp），交易页面不在产物里；
 * - 不加 vite-plugin-pwa（不注册 Service Worker）；
 * - 永远是真实接口模式（不打包 src/mocks/）；
 * - PUBLIC_STUBS 里列的模块换成 src/public/stubs/ 下的空实现：它们只在登录以后才会发请求，公开站永远是"没登录"，
 *   换掉以后产物里没有这些接口地址（cf/scripts/check-dist.mjs 检查"产物里没有交易入口"）。
 */
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const MOCKS_DIR = /[\\/]src[\\/]mocks[\\/]/;
/**
 * 登录后才用的实时推送（WebSocket /api/ws/user）：模块顶层会建一个连接对象（不连），Rollup 默认当成有副作用整个留下。
 * 公开版里没有页面用它，声明成没有副作用，打包时去掉。
 */
const LOGGED_IN_ONLY = /[\\/]src[\\/]api[\\/]real[\\/]events\.ts$/;

/** 原模块（相对 src/）→ 空实现（相对 src/public/stubs/） */
export const PUBLIC_STUBS: Record<string, string> = {
  'api/notifications.ts': 'notifications.ts',
};

function publicStubs(): Plugin {
  const map = new Map(Object.entries(PUBLIC_STUBS).map(([from, to]) => [path.join(here, 'src', from), path.join(here, 'src/public/stubs', to)]));
  return {
    name: 'fc-public-stubs',
    enforce: 'pre',
    async resolveId(source, importer, options) {
      if (!importer) return null;
      const r = await this.resolve(source, importer, { ...options, skipSelf: true });
      if (!r) return null;
      const to = map.get(r.id.split('?')[0]);
      return to ?? null;
    },
  };
}

export default defineConfig({
  base: '/',
  plugins: [publicStubs(), react()],
  define: { "import.meta.env.VITE_API_MODE": JSON.stringify("real"), "import.meta.env.VITE_PUBLIC_BUILD": JSON.stringify("true") },
  build: {
    outDir: 'dist-public',
    emptyOutDir: true,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      input: path.join(here, 'public.html'),
      treeshake: { moduleSideEffects: (id: string) => !MOCKS_DIR.test(id) && !LOGGED_IN_ONLY.test(id) },
    },
  },
});
