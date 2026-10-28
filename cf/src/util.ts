/** 小工具：时间、随机串、哈希、打码（forecast S19） */

/** 时间一律存 UTC 的 ISO 字符串（毫秒，结尾 Z），按字符串比较就是按时间比较 */
export function iso(ms: number): string {
  return new Date(ms).toISOString();
}

/** 16 字节随机数的十六进制（32 个字符），和后端 WaitlistService.token() 一样 */
export function token(): string {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
}

export const TOKEN_RE = /^[0-9a-f]{32}$/;

export async function sha256(v: string): Promise<string> {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(v));
  return [...new Uint8Array(d)].map((x) => x.toString(16).padStart(2, '0')).join('');
}

/** 邮箱打码：保留第一个字符和域名（a***@example.com），和后端 WaitlistService.mask 一样 */
export function mask(email: string | null | undefined): string {
  const at = email ? email.indexOf('@') : -1;
  if (!email || at <= 0) return '***';
  return `${email[0]}***${email.slice(at)}`;
}

/** 语言规范成 zh-TW / zh-CN / en，和后端 WaitlistService.lang 一样 */
export function normLang(l: unknown): 'zh-TW' | 'zh-CN' | 'en' {
  if (typeof l !== 'string') return 'en';
  const s = l.trim();
  const low = s.toLowerCase();
  if (low === 'zh-tw' || low === 'zh-hk' || low === 'zh-hant') return 'zh-TW';
  if (low.startsWith('zh')) return 'zh-CN';
  return 'en';
}

/** 变量是不是"打开"（true / 1 / yes / on，大小写不论）；没填用默认值 */
export function flag(v: string | undefined, dflt: boolean): boolean {
  if (v === undefined || v.trim() === '') return dflt;
  return ['true', '1', 'yes', 'on'].includes(v.trim().toLowerCase());
}
