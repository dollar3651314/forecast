/**
 * 品牌名：前端只在这里写一次（技术方案/v1-架构方案.md 第 15 节，AQ-4）。品牌名不翻译，中英文界面一样。
 * 上线前沿用 Quick Come（Amos 2026-10-25：项目上线前都用 Quick Come 这个名字和 quickcome.online 的子域名，正式上线时才改名和域名；
 * 需求说明书 v1.2 第 0 节、架构方案 v1.1 第 15 节）。正式改名时只改这里、web/admin 和 web/site 的 brand.ts、各 index.html、
 * PWA 名称（vite.config.ts）、public/push-sw.js 和通知模板（后端新迁移），并打开 scripts/brand-check.json 里 Quick Come 的检查。
 */
export const BRAND_NAME = 'Quick Come';
