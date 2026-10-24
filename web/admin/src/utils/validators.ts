/**
 * 表单校验规则（纯函数，便于单元测试）。返回翻译键列表：一次给出全部问题（规则 C56）。
 */
import { splitDate } from './dateParts';
import { compareAmounts, isValidAmountInput } from './money';

export const PASSWORD_MIN = 10;

/** 新密码规则：至少 10 位，同时包含字母和数字，不能与旧密码相同 */
export function passwordProblems(pw: string, oldPw?: string): string[] {
  const problems: string[] = [];
  if (pw.length < PASSWORD_MIN) problems.push('validation.pwdMin');
  if (!/[A-Za-z]/.test(pw)) problems.push('validation.pwdLetter');
  if (!/\d/.test(pw)) problems.push('validation.pwdDigit');
  if (oldPw && pw === oldPw) problems.push('validation.pwdSame');
  return problems;
}

/** 余额调整金额：正数、最多 2 位小数、不超过单笔上限、今日累计不超过每日上限 */
export function adjustAmountProblems(amount: string, singleLimit: string, dailyRemaining: string): string[] {
  const text = (amount ?? '').trim();
  if (!text) return ['validation.amountRequired'];
  if (!isValidAmountInput(text)) return ['validation.amountFormat'];
  const problems: string[] = [];
  if (compareAmounts(text, singleLimit) > 0) problems.push('validation.overSingle');
  if (compareAmounts(text, dailyRemaining) > 0) problems.push('validation.overDaily');
  return problems;
}

/** 百分比（追保线、强平线）：0 < x ≤ 1000，最多 2 位小数 */
export function isValidPercent(text: string): boolean {
  return isValidAmountInput(text, 2) && compareAmounts(text, '1000') <= 0;
}

/** 正整数（USD 阈值等） */
export function isPositiveInteger(text: string): boolean {
  return /^[1-9]\d{0,12}$/.test((text ?? '').trim());
}

/** 费率：可以为负（隔夜费），最多 8 位小数 */
export function isValidRate(text: string): boolean {
  return /^-?\d+(\.\d{1,8})?$/.test((text ?? '').trim());
}

export const USERNAME_RE = /^[a-z][a-z0-9._-]{2,39}$/;
export const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// ---------- v2 E1：后台编辑客户资料（规则和客户端注册一致：web/client/src/lib/validators.ts 的 validateProfile；后端 CustomerAdmin.updateProfile） ----------

/** 姓名允许的字符：字母（含中文）、组合符号、撇号、空格、点、连字符 */
const NAME_RE = /^[\p{L}\p{M}' .-]+$/u;
/** 姓名每段最多 50 字 */
export const NAME_MAX = 50;

/** 名、中间名、姓：返回翻译键（一次给出全部问题） */
export function nameProblems(v: string, required: boolean): string[] {
  const text = (v ?? '').trim();
  if (!text) return required ? ['validation.required'] : [];
  const out: string[] = [];
  if (text.length > NAME_MAX) out.push('validation.nameTooLong');
  if (!NAME_RE.test(text)) out.push('validation.nameChars');
  return out;
}

/**
 * 出生日期：由三框组件 `DateFields` 合成（`utils/dateParts.ts`），真实日期，不早于 1900 年、不晚于今天，年满 18 周岁（按 UTC 日期）。
 * 和客户端 `validateBirthDate` 同一顺序，只报一类：没填 → 没填完整 → 年份不是 4 位 → 日期不存在 → 早于 1900 年或晚于今天 → 未满 18 岁。
 */
export function birthDateProblems(v: string, today: Date = new Date()): string[] {
  const text = (v ?? '').trim();
  if (!text) return ['validation.required'];
  const p = splitDate(text);
  if (!p.y || !p.m || !p.d) return ['validation.dateIncomplete'];
  if (!/^\d{4}$/.test(p.y)) return ['validation.yearFormat'];
  const [y, mo, d] = [Number(p.y), Number(p.m), Number(p.d)];
  const dt = new Date(Date.UTC(y, mo - 1, d));
  if (mo < 1 || mo > 12 || dt.getUTCFullYear() !== y || dt.getUTCMonth() !== mo - 1 || dt.getUTCDate() !== d || y < 1900 || dt.getTime() > today.getTime()) {
    return ['validation.dateInvalid'];
  }
  const adult = new Date(Date.UTC(y + 18, mo - 1, d));
  return adult.getTime() > today.getTime() ? ['validation.ageUnder18'] : [];
}

/** 国籍：ISO 3166-1 两位大写字母 */
export function nationalityProblems(v: string): string[] {
  const text = (v ?? '').trim();
  if (!text) return ['validation.required'];
  return /^[A-Z]{2}$/.test(text) ? [] : ['validation.nationalityFormat'];
}

// ---------- v2 E3：新增品种 ----------

/** 品种代码：大写字母开头，大写字母和数字，2–16 位（例如 EURUSD、US30、JPN225），和后端 MarketAdmin 一致 */
export const SYMBOL_CODE_RE = /^[A-Z][A-Z0-9]{1,15}$/;
/** 报价货币：3–4 位大写字母 */
export const CURRENCY_RE = /^[A-Z]{3,4}$/;

/**
 * 模拟起始价（只在模拟报价源下使用）：正数、整数部分最多 16 位、小数位不超过品种的报价小数位 digits，和后端 MarketAdmin 一致。
 * 末尾的 0 不算小数位（1.2300 按 1.23）。空表示不填，返回空数组；digits 不确定（没填或不合法）时只查格式。
 */
export function simStartPriceProblems(v: string | undefined, digits: number | undefined | null): string[] {
  const text = (v ?? '').trim();
  if (!text) return [];
  const m = /^(\d+)(?:\.(\d+))?$/.exec(text);
  if (!m) return ['validation.simStartPrice'];
  const int = m[1].replace(/^0+/, '');
  const frac = (m[2] ?? '').replace(/0+$/, '');
  if ((!int && !frac) || int.length > 16) return ['validation.simStartPrice'];
  if (typeof digits === 'number' && Number.isInteger(digits) && digits >= 0 && digits <= 8 && frac.length > digits) return ['validation.simStartPriceDecimals'];
  return [];
}
