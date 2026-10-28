/**
 * 端到端测试配置（S19，复制源的端到端测试方案）。
 * 默认形态：后端要先以 e2e profile 启动（e2e/start-backend.sh）；本配置只负责启动静态站点和 /api 转发（server.mjs）。
 * 部署形态（E2E_MODE=deployed，由 e2e/run-deployed.sh 设置，方案第 9 节）：不启动 server.mjs，浏览器经 Nginx 镜像访问两个假域名，
 * 用 --host-resolver-rules 把它们指到 127.0.0.1，忽略自签名证书错误（只在这个形态）。
 * 用例串行执行（同一个后端、同一个模拟行情），每一步都有自己的超时，失败时报出卡在哪一步，保留 trace、截图和录像。
 */
import { defineConfig, devices } from '@playwright/test';
import { ADMIN_URL, CLIENT_URL, DEPLOYED, PRELAUNCH_URL, SITE_URL } from './tests/targets';

const PORT = Number(process.env.E2E_PORT || 4173);

/** 部署形态：三个假域名（客户端、后台、官网）和 S18 的对外预热域名都解析到本机；忽略自签名证书（--ignore-certificate-errors 也放行 Service Worker 脚本的证书检查） */
const deployedHosts = [CLIENT_URL, ADMIN_URL, SITE_URL, PRELAUNCH_URL].filter(Boolean).map((u) => new URL(u).hostname);
const deployedBrowser = DEPLOYED
  ? {
      ignoreHTTPSErrors: true,
      launchOptions: {
        args: [`--host-resolver-rules=${deployedHosts.map((h) => `MAP ${h} 127.0.0.1`).join(', ')}`, '--ignore-certificate-errors'],
      },
    }
  : {};
// 机器上配了 https_proxy 时，Chromium（headless shell 不认 --no-proxy-server）会把 https 请求交给代理，代理解析不了假域名：
// 把假域名加进 no_proxy（浏览器由测试进程启动，继承这里的环境变量）
if (DEPLOYED) {
  for (const k of ['no_proxy', 'NO_PROXY']) process.env[k] = [process.env[k], ...deployedHosts].filter(Boolean).join(',');
}

export default defineConfig({
  testDir: './tests',
  // 整个主流程（注册到提现完成）一个用例，给足时间；每一步另有超时（tests/support.ts 的 step）
  timeout: 8 * 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  use: {
    baseURL: CLIENT_URL,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    // 客户端是 PWA：不让 Service Worker 缓存页面和接口，每次都拿到最新构建、请求都能被记录
    serviceWorkers: 'block',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], ...deployedBrowser } }],
  // 部署形态由 run-deployed.sh 起好 Nginx 和后端容器，这里不启动 server.mjs
  webServer: DEPLOYED
    ? undefined
    : {
        command: 'node server.mjs',
        url: `http://127.0.0.1:${PORT}/`,
        reuseExistingServer: !process.env.CI,
        timeout: 30_000,
        env: { E2E_PORT: String(PORT) },
      },
});
