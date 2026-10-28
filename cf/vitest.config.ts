/**
 * Worker 的测试（forecast S19）：vitest 在 Node 里跑，D1 用 wrangler getPlatformProxy 给的本地实现（底下是 Miniflare，真实的 SQLite，和线上 D1 同一套 SQL），
 * 每个测试文件一个新库并执行 migrations/ 的全部迁移。整条 HTTP 路径（路由、响应头、定时任务）另外用 wrangler 的本地开发服务器跑（test/worker.test.ts）。
 * 外部服务（Resend、OKX、币安、Access 公钥）全部用替身，不连真实服务（C54）。
 */
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
    testTimeout: 30_000,
    hookTimeout: 60_000,
    // Worker 的结构化日志（{"event":…}）不打印到测试输出
    onConsoleLog: (log) => !log.startsWith('{"event"'),
  },
});
