/**
 * 接口模式（构建变量 VITE_API_MODE）：
 * - real（默认）：调用后端 /api/admin/**，登录态在 HttpOnly Cookie 里；
 * - mock：原型模式，调用 src/mocks 的内存实现，保留原型横幅、场景面板、"切换演示身份"。
 * 原型构建（scripts/build-prototype.sh、vercel.json）和单元测试（vite.config.ts 的 test.env）显式设成 mock。
 *
 * API mode, chosen at build time: "real" (default) talks to the backend, "mock" keeps the prototype.
 */
/// <reference types="vite/client" />

export type ApiMode = 'real' | 'mock';

declare global {
  interface ImportMetaEnv {
    readonly VITE_API_MODE?: string;
  }
}

export const API_MODE: ApiMode = import.meta.env.VITE_API_MODE === 'mock' ? 'mock' : 'real';

/** 是否原型（模拟数据）模式 */
export const IS_MOCK = API_MODE === 'mock';
