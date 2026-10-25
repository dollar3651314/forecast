/**
 * 表单校验。规则 C56：一次返回全部错误（每个字段可以有多条），而不是遇到第一条就停。
 * 返回值里的字符串都是 i18n 键（在 validation.* 下）。
 */
import { cmp, dec, isDecimalString, sub, add, ZERO, type Dec } from './decimal';
import { splitDate } from './dateParts';
import type { Network, WithdrawInfo, WhitelistAddress } from '../api/types';

export type FieldErrors<K extends string = string> = Partial<Record<K, string[]>>;

function push<K extends string>(errs: FieldErrors<K>, field: K, key: string): void {
  (errs[field] ??= []).push(key);
}

export function hasErrors(errs: FieldErrors): boolean {
  return Object.values(errs).some((v) => v && v.length > 0);
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export function validateEmail(email: string): string[] {
  if (!email.trim()) return ['validation.required'];
  return EMAIL_RE.test(email.trim()) ? [] : ['validation.emailFormat'];
}

/** 密码：8–64 位，至少包含字母和数字（和后端一致） */
export function validatePassword(pw: string): string[] {
  if (!pw) return ['validation.required'];
  const out: string[] = [];
  if (pw.length < 8 || pw.length > 64) out.push('validation.passwordLength');
  if (!/[A-Za-z]/.test(pw) || !/\d/.test(pw)) out.push('validation.passwordMix');
  return out;
}

export function validateCode(code: string): string[] {
  if (!code.trim()) return ['validation.required'];
  return /^\d{6}$/.test(code.trim()) ? [] : ['validation.codeFormat'];
}

const NAME_RE = /^[\p{L}\p{M}' .-]+$/u;

function validateName(v: string, required: boolean): string[] {
  if (!v.trim()) return required ? ['validation.required'] : [];
  const out: string[] = [];
  if (v.trim().length > 50) out.push('validation.nameTooLong');
  if (!NAME_RE.test(v.trim())) out.push('validation.nameChars');
  return out;
}

/**
 * 出生日期：由三框组件 `DateFields` 合成（见 `lib/dateParts.ts`），必须是真实日期，年满 18 周岁，不早于 1900 年。
 * 错误按优先级只报一类（GOV.UK 的做法）：没填 → 没填完整 → 年份不是 4 位 → 日期不存在 → 早于 1900 年 → 未满 18 岁。
 */
export function validateBirthDate(v: string, today: Date = new Date()): string[] {
  const text = (v ?? '').trim();
  if (!text) return ['validation.required'];
  const p = splitDate(text);
  if (!p.y || !p.m || !p.d) return ['validation.dateIncomplete'];
  if (!/^\d{4}$/.test(p.y)) return ['validation.yearFormat'];
  const [y, mo, d] = [Number(p.y), Number(p.m), Number(p.d)];
  const dt = new Date(Date.UTC(y, mo - 1, d));
  if (mo < 1 || mo > 12 || dt.getUTCFullYear() !== y || dt.getUTCMonth() !== mo - 1 || dt.getUTCDate() !== d) {
    return ['validation.dateInvalid'];
  }
  if (y < 1900) return ['validation.dateTooEarly'];
  const adult = new Date(Date.UTC(y + 18, mo - 1, d));
  return adult.getTime() > today.getTime() ? ['validation.ageUnder18'] : [];
}

export interface ProfileForm {
  firstName: string;
  middleName: string;
  lastName: string;
  birthDate: string;
  nationality: string;
}

export function validateProfile(f: ProfileForm, today?: Date): FieldErrors<keyof ProfileForm> {
  const errs: FieldErrors<keyof ProfileForm> = {};
  validateName(f.firstName, true).forEach((k) => push(errs, 'firstName', k));
  validateName(f.middleName, false).forEach((k) => push(errs, 'middleName', k));
  validateName(f.lastName, true).forEach((k) => push(errs, 'lastName', k));
  validateBirthDate(f.birthDate, today).forEach((k) => push(errs, 'birthDate', k));
  if (!f.nationality) push(errs, 'nationality', 'validation.required');
  return errs;
}

export interface RegisterForm extends ProfileForm {
  email: string;
  code: string;
  password: string;
  confirm: string;
  agree: boolean;
}

export function validateRegister(f: RegisterForm, today?: Date): FieldErrors<keyof RegisterForm> {
  const errs: FieldErrors<keyof RegisterForm> = { ...validateProfile(f, today) };
  validateEmail(f.email).forEach((k) => push(errs, 'email', k));
  validateCode(f.code).forEach((k) => push(errs, 'code', k));
  validatePassword(f.password).forEach((k) => push(errs, 'password', k));
  if (!f.confirm) push(errs, 'confirm', 'validation.required');
  else if (f.confirm !== f.password) push(errs, 'confirm', 'validation.passwordMismatch');
  if (!f.agree) push(errs, 'agree', 'validation.mustAgree');
  return errs;
}

export function validateLogin(email: string, password: string): FieldErrors<'email' | 'password'> {
  const errs: FieldErrors<'email' | 'password'> = {};
  validateEmail(email).forEach((k) => push(errs, 'email', k));
  if (!password) push(errs, 'password', 'validation.required');
  return errs;
}

export function validateReset(f: { email: string; code: string; password: string; confirm: string }) {
  const errs: FieldErrors<'email' | 'code' | 'password' | 'confirm'> = {};
  validateEmail(f.email).forEach((k) => push(errs, 'email', k));
  validateCode(f.code).forEach((k) => push(errs, 'code', k));
  validatePassword(f.password).forEach((k) => push(errs, 'password', k));
  if (!f.confirm) push(errs, 'confirm', 'validation.required');
  else if (f.confirm !== f.password) push(errs, 'confirm', 'validation.passwordMismatch');
  return errs;
}

export function validateChangePassword(f: { current: string; password: string; confirm: string }) {
  const errs: FieldErrors<'current' | 'password' | 'confirm'> = {};
  if (!f.current) push(errs, 'current', 'validation.required');
  validatePassword(f.password).forEach((k) => push(errs, 'password', k));
  if (f.password && f.current && f.password === f.current) push(errs, 'password', 'validation.passwordSame');
  if (!f.confirm) push(errs, 'confirm', 'validation.required');
  else if (f.confirm !== f.password) push(errs, 'confirm', 'validation.passwordMismatch');
  return errs;
}

function positive(v: string): Dec | null {
  if (!isDecimalString(v)) return null;
  const d = dec(v);
  return cmp(d, ZERO) > 0 ? d : null;
}

function decimalsOf(v: string): number {
  return v.includes('.') ? v.split('.')[1].length : 0;
}

// ---------- 提现 ----------
export function validateWithdraw(
  f: { whitelistId: string; amount: string },
  info: WithdrawInfo,
  whitelist: WhitelistAddress[],
  now: Date = new Date(),
): FieldErrors<'whitelistId' | 'amount' | 'kyc'> {
  const errs: FieldErrors<'whitelistId' | 'amount' | 'kyc'> = {};
  if (info.kycStatus !== 'approved') push(errs, 'kyc', 'validation.kycRequired');
  const addr = whitelist.find((w) => w.id === f.whitelistId);
  if (!f.whitelistId || !addr) push(errs, 'whitelistId', 'validation.selectAddress');
  else if (new Date(addr.activeAt).getTime() > now.getTime()) push(errs, 'whitelistId', 'validation.addressNotActive');

  const a = f.amount.trim();
  if (!a) push(errs, 'amount', 'validation.required');
  else if (!positive(a)) push(errs, 'amount', 'validation.positiveNumber');
  else {
    const amt = dec(a);
    if (decimalsOf(a) > 2) push(errs, 'amount', 'validation.amountDigits');
    if (cmp(amt, dec(info.minAmount)) < 0) push(errs, 'amount', 'validation.withdrawMin');
    if (cmp(amt, dec(info.maxPerTx)) > 0) push(errs, 'amount', 'validation.withdrawMaxPerTx');
    // 当日累计必须严格小于上限
    if (cmp(add(dec(info.usedToday), amt), dec(info.dailyLimit)) >= 0) push(errs, 'amount', 'validation.withdrawDaily');
    if (cmp(amt, dec(info.available)) > 0) push(errs, 'amount', 'validation.withdrawBalance');
  }
  return errs;
}

/** 今日剩余额度（严格小于上限，所以展示为 上限 − 已用） */
export function dailyRemaining(info: WithdrawInfo): Dec {
  const r = sub(dec(info.dailyLimit), dec(info.usedToday));
  return cmp(r, ZERO) < 0 ? ZERO : r;
}

export function validateAddress(network: Network, address: string): string[] {
  const a = address.trim();
  if (!a) return ['validation.required'];
  const ok = network === 'TRC20' ? /^T[1-9A-HJ-NP-Za-km-z]{33}$/.test(a) : /^0x[0-9a-fA-F]{40}$/.test(a);
  return ok ? [] : [`validation.address${network === 'TRC20' ? 'Tron' : 'Evm'}`];
}

export function validateWhitelist(f: { network: Network; address: string; label: string }, existing: WhitelistAddress[], max: number) {
  const errs: FieldErrors<'address' | 'label' | 'form'> = {};
  validateAddress(f.network, f.address).forEach((k) => push(errs, 'address', k));
  if (existing.some((w) => w.network === f.network && w.address.toLowerCase() === f.address.trim().toLowerCase())) {
    push(errs, 'address', 'validation.addressDuplicate');
  }
  if (!f.label.trim()) push(errs, 'label', 'validation.required');
  else if (f.label.trim().length > 30) push(errs, 'label', 'validation.labelTooLong');
  if (existing.length >= max) push(errs, 'form', 'validation.whitelistFull');
  return errs;
}

// ---------- KYC ----------
export const KYC_MAX_BYTES = 2 * 1024 * 1024;
const KYC_TYPES = ['image/jpeg', 'image/png'];

export function validateKycFile(file: File | null): string[] {
  if (!file) return ['validation.required'];
  const out: string[] = [];
  if (!KYC_TYPES.includes(file.type)) out.push('validation.fileType');
  if (file.size > KYC_MAX_BYTES) out.push('validation.fileSize');
  return out;
}

export function validateKyc(f: { docType: string; docNumber: string; front: File | null; back: File | null; selfie: File | null }) {
  const errs: FieldErrors<'docType' | 'docNumber' | 'front' | 'back' | 'selfie'> = {};
  if (!f.docType) push(errs, 'docType', 'validation.required');
  const n = f.docNumber.trim();
  if (!n) push(errs, 'docNumber', 'validation.required');
  else if (f.docType === 'idCard' && !/^\d{17}[\dXx]$/.test(n)) push(errs, 'docNumber', 'validation.idCardFormat');
  else if (f.docType === 'passport' && !/^[A-Za-z0-9]{6,12}$/.test(n)) push(errs, 'docNumber', 'validation.passportFormat');
  validateKycFile(f.front).forEach((k) => push(errs, 'front', k));
  validateKycFile(f.back).forEach((k) => push(errs, 'back', k));
  validateKycFile(f.selfie).forEach((k) => push(errs, 'selfie', k));
  return errs;
}
