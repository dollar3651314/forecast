/**
 * 接口模式（S17）。构建变量 `VITE_API_MODE` 决定：
 * - `real`（默认）：用 fetch / WebSocket 调同源的后端 `/api/...`，登录态在 HttpOnly Cookie 里，前端不保存令牌；
 * - `mock`：浏览器内的模拟层（`src/mocks/`）和原型场景面板，原型预览站（Vercel）和单元测试用它。
 *
 * 构建时被替换成常量；页面只通过 `src/api/` 拿数据，不关心是哪种模式。
 */
export type ApiMode = 'real' | 'mock';

export const API_MODE: ApiMode = import.meta.env.VITE_API_MODE === 'mock' ? 'mock' : 'real';

/** 原型模式：显示原型横幅、场景面板和原型提示 */
export const IS_MOCK = API_MODE === 'mock';
