import type { WhitelistAddress, WithdrawInfo } from '../api/types';
import { validateKyc, validatePassword, validateRegister, validateWhitelist, validateWithdraw } from './validators';

const today = new Date('2026-10-14T00:00:00Z');

describe('注册校验（一次显示全部错误）', () => {
  it('空表单时每个必填字段都有错误', () => {
    const e = validateRegister({ email: '', code: '', password: '', confirm: '', firstName: '', middleName: '', lastName: '', birthDate: '', nationality: '', agree: false }, today);
    expect(Object.keys(e).sort()).toEqual(['agree', 'birthDate', 'code', 'confirm', 'email', 'firstName', 'lastName', 'nationality', 'password'].sort());
  });
  it('同一字段可以有多条错误；未满 18 岁被拒', () => {
    const e = validateRegister({ email: 'a@b.co', code: '123456', password: 'short', confirm: 'x', firstName: '思远', middleName: '', lastName: '欧阳', birthDate: '2010-01-01', nationality: 'CN', agree: true }, today);
    expect(e.password).toEqual(['validation.passwordLength', 'validation.passwordMix']);
    expect(e.confirm).toEqual(['validation.passwordMismatch']);
    expect(e.birthDate).toEqual(['validation.ageUnder18']);
    expect(e.firstName).toBeUndefined();
  });
  it('不存在的日期', () => {
    const e = validateRegister({ email: 'a@b.co', code: '123456', password: 'Abcdefg1', confirm: 'Abcdefg1', firstName: 'A', middleName: '', lastName: 'B', birthDate: '1990-02-30', nationality: 'CN', agree: true }, today);
    expect(e.birthDate).toEqual(['validation.dateInvalid']);
  });
});


describe('密码规则（8–64 位，字母和数字，和后端一致）', () => {
  it('64 位可以，65 位超长；只有字母或只有数字不行', () => {
    expect(validatePassword('Abcdefg1'.repeat(8))).toEqual([]);
    expect(validatePassword('Abcdefg1'.repeat(8) + 'x')).toEqual(['validation.passwordLength']);
    expect(validatePassword('onlyletters')).toEqual(['validation.passwordMix']);
    expect(validatePassword('1234567890')).toEqual(['validation.passwordMix']);
  });
});

const info: WithdrawInfo = {
  kycStatus: 'approved', available: '8218.63', usedToday: '25000', minAmount: '10', maxPerTx: '10000', dailyLimit: '30000', largeAmount: '3000', largeDelayHours: 6, coolingHours: 2,
  fees: { ERC20: '5.00', TRC20: '1.00', BSC: '0.50' }, maxWhitelist: 10,
};
const wl: WhitelistAddress[] = [
  { id: 'A1', network: 'TRC20', address: 'TQ8nB4rW7xYp2LkM9vD3sF6hJ1cZ5gw3kP', label: 'a', createdAt: '2026-09-01T00:00:00Z', activeAt: '2026-09-02T00:00:00Z' },
  { id: 'A3', network: 'BSC', address: '0x9E2b1D8f3C6a5B0e9F7d2C1b8A3e6D5f4C214A7c', label: 'b', createdAt: '2026-10-13T23:00:00Z', activeAt: '2026-10-14T23:00:00Z' },
];

describe('提现校验', () => {
  it('金额同时违反多条规则时全部列出', () => {
    const e = validateWithdraw({ whitelistId: 'A1', amount: '12000.123' }, info, wl, today);
    expect(e.amount).toEqual(['validation.amountDigits', 'validation.withdrawMaxPerTx', 'validation.withdrawDaily', 'validation.withdrawBalance']);
  });
  it('最小额、未生效地址、未认证', () => {
    const e = validateWithdraw({ whitelistId: 'A3', amount: '5' }, { ...info, kycStatus: 'pending' }, wl, today);
    expect(e.amount).toEqual(['validation.withdrawMin']);
    expect(e.whitelistId).toEqual(['validation.addressNotActive']);
    expect(e.kyc).toEqual(['validation.kycRequired']);
  });
  it('当日累计必须严格小于 30,000', () => {
    expect(validateWithdraw({ whitelistId: 'A1', amount: '5000' }, info, wl, today).amount).toEqual(['validation.withdrawDaily']);
    expect(validateWithdraw({ whitelistId: 'A1', amount: '4999.99' }, info, wl, today)).toEqual({});
  });
  it('白名单地址格式和数量上限', () => {
    expect(validateWhitelist({ network: 'TRC20', address: '0x123', label: '' }, wl, 10)).toEqual({ address: ['validation.addressTron'], label: ['validation.required'] });
    const full = Array.from({ length: 10 }, (_, i) => ({ ...wl[0], id: `x${i}` }));
    expect(validateWhitelist({ network: 'BSC', address: '0x4A7c9E2b1D8f3C6a5B0e9F7d2C1b8A3e6D5f4C21', label: 'ok' }, full, 10).form).toEqual(['validation.whitelistFull']);
  });
});

describe('KYC 校验', () => {
  it('文件超过 2MB 和格式错误同时报出', () => {
    const big = new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'a.gif', { type: 'image/gif' });
    const ok = new File([new Uint8Array(10)], 'b.png', { type: 'image/png' });
    const e = validateKyc({ docType: 'idCard', docNumber: '123', front: big, back: ok, selfie: null });
    expect(e.front).toEqual(['validation.fileType', 'validation.fileSize']);
    expect(e.back).toBeUndefined();
    expect(e.selfie).toEqual(['validation.required']);
    expect(e.docNumber).toEqual(['validation.idCardFormat']);
  });
});

