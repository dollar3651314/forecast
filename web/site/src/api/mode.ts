/**
 * 接口模式（构建变量 VITE_API_MODE，和 web/client 同一个约定）：
 * - `real`（默认）：测试环境标识调 `GET /api/health`；
 * - `mock`：不调接口，原型预览站（Vercel）和单元测试用。
 * forecast S1 删除了复制源的公开行情（`GET /api/public/quotes` 和模拟行情 `src/mocks/`），官网暂时没有模拟层。
 */
export const IS_MOCK = import.meta.env.VITE_API_MODE === 'mock';
