/**
 * 品牌名和联系邮箱：官网只在这里写一次（技术方案/v1-架构方案.md 第 15 节，AQ-4、AQ-5）。品牌名不翻译，中英文一样；
 * 翻译文件里用 {brand} 占位，translate 自动替换（src/i18n/index.ts）。
 * forecast S1 先用占位名 Forecast：品牌待 Amos 确认（开发记录/S1-复制骨架.md SQ-1）。定了以后改这里、index.html 的标题和描述。
 * 联系邮箱跟域名走，域名没定之前为空，页脚显示"待提供"。
 */
export const BRAND_NAME = 'Forecast';
export const CONTACT_EMAIL = '';
