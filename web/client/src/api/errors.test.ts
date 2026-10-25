import zh from '../i18n/zh-CN.json';
import en from '../i18n/en.json';
import { ApiError, errorKey, FIELD_CODES, fromServer, SERVER_CODES } from './errors';

const has = (o: unknown, key: string) => key.split('.').reduce<unknown>((x, p) => (x && typeof x === 'object' ? (x as Record<string, unknown>)[p] : undefined), o) !== undefined;

describe('后端错误码映射', () => {
  it('每个后端错误码和字段错误码都有中英文翻译（AC-27）', () => {
    const keys = [...Object.values(SERVER_CODES).map((r) => `errors.${r}`), ...Object.values(FIELD_CODES), 'errors.unauthorized', 'errors.forbidden', 'errors.notFound', 'errors.badRequest', 'errors.withdrawInsufficient'];
    expect(keys.filter((k) => !has(zh, k))).toEqual([]);
    expect(keys.filter((k) => !has(en, k))).toEqual([]);
  });

  it('下注的 11 种拒单原因（需求第 3.3 节，后端 BetService.REJECT_CODES）都有自己的中英文原因', () => {
    const codes = ['MARKET_NOT_OPEN', 'MARKET_CLOSED', 'MARKET_PAUSED', 'MARKET_CANCELLED', 'BET_BELOW_MIN', 'BET_ABOVE_MAX', 'BET_OVER_USER_CAP', 'INSUFFICIENT_FUNDS', 'KYC_REQUIRED', 'USER_FROZEN', 'BET_STAFF_ACCOUNT'];
    const reasons = codes.map((c) => SERVER_CODES[c]);
    expect(reasons.filter((r) => !r)).toEqual([]);
    expect(new Set(reasons).size).toBe(11);
    for (const r of reasons) {
      expect(has(zh, `errors.${r}`)).toBe(true);
      expect(has(en, `errors.${r}`)).toBe(true);
    }
    expect(errorKey(fromServer(400, { code: 'BET_OVER_USER_CAP', message: '超过本市场的累计上限' }))).toBe('errors.betOverUserCap');
  });

  it('业务错误码换成 reason，不显示错误代码', () => {
    const e = fromServer(400, { code: 'WHITELIST_NOT_ACTIVE', message: '未生效', fields: [] });
    expect(e.reason).toBe('whitelistNotActive');
    expect(e.status).toBe(400);
    expect(errorKey(e)).toBe('errors.whitelistNotActive');
  });

  it('字段错误：两种写法的错误码都能翻译，按字段区分的优先', () => {
    const e = fromServer(400, {
      code: 'VALIDATION_FAILED',
      fields: [
        { field: 'email', code: 'EMAIL_INVALID' },
        { field: 'amount', code: 'amountDigits' },
        { field: 'label', code: 'tooLong' },
        { field: 'note', code: 'tooLong' },
        { field: 'x', code: 'SOMETHING_NEW' },
      ],
    });
    expect(e.reason).toBe('validationFailed');
    expect(e.fieldErrors).toEqual({
      email: ['validation.emailFormat'],
      amount: ['validation.amountDigits'],
      label: ['validation.labelTooLong'],
      note: ['validation.tooLong'],
      x: ['validation.invalid'],
    });
    // 页面没有逐个字段显示时，显示第一个字段的原因
    expect(errorKey(e)).toBe('validation.emailFormat');
  });

  it('接口特有的含义、字段改名、业务错误标在字段上', () => {
    const e = fromServer(
      400,
      { code: 'CURRENT_PASSWORD_WRONG', fields: [{ field: 'newPassword', code: 'PASSWORD_LENGTH' }] },
      { fieldNames: { newPassword: 'password' }, codeFields: { CURRENT_PASSWORD_WRONG: 'current' } },
    );
    expect(e.reason).toBe('passwordWrong');
    expect(e.fieldErrors).toEqual({ password: ['validation.passwordLength'], current: ['errors.passwordWrong'] });
    expect(fromServer(400, { code: 'INSUFFICIENT_BALANCE' }, { codes: { INSUFFICIENT_BALANCE: 'withdrawInsufficient' } }).reason).toBe('withdrawInsufficient');
  });

  it('没有错误体或不认识的错误码按 HTTP 状态处理', () => {
    expect(fromServer(502, undefined).reason).toBe('server');
    expect(fromServer(401, undefined).reason).toBe('unauthorized');
    expect(fromServer(404, { code: 'WHATEVER' }).reason).toBe('notFound');
    expect(fromServer(409, { code: 'BRAND_NEW' }).reason).toBe('badRequest');
  });

  it('限流 429 RATE_LIMITED 显示"操作太频繁"，带剩余秒数（P-05）', () => {
    const e = fromServer(429, { code: 'RATE_LIMITED', message: '请求太频繁', fields: [], cooldownSeconds: 12 });
    expect(e.reason).toBe('rateLimited');
    expect(e.cooldownSeconds).toBe(12);
    expect(errorKey(e)).toBe('errors.rateLimited');
    // 没有响应体的 429 也一样
    expect(fromServer(429, undefined).reason).toBe('rateLimited');
  });

  it('非接口错误显示"服务暂时不可用"', () => {
    expect(errorKey(new Error('x'))).toBe('errors.server');
    expect(errorKey(new ApiError('network'))).toBe('errors.network');
  });
});
