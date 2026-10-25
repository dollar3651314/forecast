/**
 * 品牌名和联系邮箱：官网只在这里写一次（技术方案/v1-架构方案.md 第 15 节，AQ-4、AQ-5）。品牌名不翻译，中英文一样；
 * 翻译文件里用 {brand} 占位，translate 自动替换（src/i18n/index.ts）。
 * 上线前沿用 Quick Come（Amos 2026-10-25，需求说明书 v1.2 第 0 节、架构方案 v1.1 第 15 节）；正式改名时改这里、index.html 的标题和描述
 * （其余地方见 web/client/src/lib/brand.ts 的说明）。
 * 联系邮箱跟正式域名走，正式上线前为空，页脚显示"待提供"。
 */
export const BRAND_NAME = 'Quick Come';
export const CONTACT_EMAIL = '';
