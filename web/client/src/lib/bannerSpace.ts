/**
 * 顶部横幅的预留高度（forecast S12，原 S11 的 SQ-28）。
 * 页面顶部给"原型"横幅和"测试环境"标识条预留了 24px（`--banner-h`）。生产既没有原型横幅也没有测试标识，这 24px 就成了空白。
 * 做法和后台一样（web/admin/src/main.tsx、global.css）：
 * - 真实接口模式（测试环境、生产都是这个构建）启动时给根元素加 `no-proto`：预留高度变成 0，生产不留空白；
 * - 后端健康检查返回 `env`（测试环境 `FC_ENV_LABEL=test`）时 EnvBanner 再加 `env-on`：恢复 24px，标识条不遮挡内容；
 * - 原型（mock）模式不加，原型横幅照常占 24px。
 * CSS 在 styles/layout.css 的开头。
 */
import { IS_MOCK } from '../api/mode';

/** 根元素类名：真实模式，没有原型横幅 */
export const NO_PROTO_CLASS = 'no-proto';
/** 根元素类名：显示测试环境标识条 */
export const ENV_ON_CLASS = 'env-on';

/** 启动时调用一次（main.tsx）：真实模式不给原型横幅留高度 */
export function initBannerSpace(root: HTMLElement = document.documentElement, mock: boolean = IS_MOCK): void {
  if (!mock) root.classList.add(NO_PROTO_CLASS);
}

/** 显示测试环境标识条时调用：恢复预留高度 */
export function showEnvBannerSpace(root: HTMLElement = document.documentElement): void {
  root.classList.add(ENV_ON_CLASS);
}
