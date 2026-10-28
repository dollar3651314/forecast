/**
 * 邮箱的格式规则（只有这一份，C108）：客户端所有表单（validators.ts 的 validateEmail）和 Cloudflare 上等候页的接口（cf/src/waitlist.ts，forecast S19）
 * 都用这里的函数，前后端对同一个输入的判断一样。这个文件不能引用别的模块（Worker 直接打包它）。
 * 规则：前后空白去掉以后，`本地部分@域名.后缀`（后缀至少 2 个字符），不能有空白和多余的 @；最长 254 个字符（和后端 @Size(max = 254) 一样）。
 */
export const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
export const EMAIL_MAX = 254;

/** 返回 i18n 键（validation.*）；没有错误返回空数组 */
export function validateEmail(email: string): string[] {
  const s = email.trim();
  if (!s) return ['validation.required'];
  return s.length <= EMAIL_MAX && EMAIL_RE.test(s) ? [] : ['validation.emailFormat'];
}
