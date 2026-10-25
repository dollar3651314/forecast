import { adjustAmountProblems, isPositiveInteger, isValidPercent, isValidRate, passwordProblems } from './validators';
import { auditDiff, sanitizeAuditPayload } from './audit';

describe('表单校验', () => {
  it('新密码一次给出全部问题', () => {
    expect(passwordProblems('abc')).toEqual(['validation.pwdMin', 'validation.pwdDigit']);
    expect(passwordProblems('1234567890')).toEqual(['validation.pwdLetter']);
    expect(passwordProblems('abcdef12345', 'abcdef12345')).toEqual(['validation.pwdSame']);
    expect(passwordProblems('abcdef12345')).toEqual([]);
  });
  it('余额调整：单笔上限和每日剩余额度同时检查', () => {
    expect(adjustAmountProblems('', '5000.00', '18750.00')).toEqual(['validation.amountRequired']);
    expect(adjustAmountProblems('1.234', '5000.00', '18750.00')).toEqual(['validation.amountFormat']);
    expect(adjustAmountProblems('6000', '5000.00', '18750.00')).toEqual(['validation.overSingle']);
    expect(adjustAmountProblems('6000', '5000.00', '100.00')).toEqual(['validation.overSingle', 'validation.overDaily']);
    expect(adjustAmountProblems('5000', '5000.00', '18750.00')).toEqual([]);
  });
  it('百分比、正整数、费率', () => {
    expect(isValidPercent('100')).toBe(true);
    expect(isValidPercent('1001')).toBe(false);
    expect(isPositiveInteger('250000')).toBe(true);
    expect(isPositiveInteger('0')).toBe(false);
    expect(isValidRate('-0.000192')).toBe(true);
    expect(isValidRate('1%')).toBe(false);
  });
});

describe('审计日志不显示密码字段', () => {
  it('过滤 password / pwd / passwordHash', () => {
    expect(sanitizeAuditPayload({ password: 'x', pwd: 'y', passwordHash: 'z', status: 'ok', mustChangePassword: true })).toEqual({ status: 'ok', mustChangePassword: true });
  });
  it('变更对比里没有密码', () => {
    const rows = auditDiff({ mustChangePassword: false, password: 'old' }, { mustChangePassword: true, password: 'Tmp#2026abc' });
    expect(rows.map((r) => r.field)).toEqual(['mustChangePassword']);
    expect(JSON.stringify(rows)).not.toContain('Tmp#2026abc');
  });
});
