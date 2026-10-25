/** 真实接口请求层：错误码映射、401 / 必须改密码事件、请求方式（fetch 替身） */
import zh from '../i18n/zh-CN.json';
import en from '../i18n/en.json';
import { ERROR_KEYS, onAuthEvent, request, toApiError, withQuery } from './http';
import { ApiError } from './types';
import { errorText } from '../utils/errors';
import i18n from '../i18n';
import { fakeFetch } from '../test/fakeFetch';

type Tree = { [k: string]: string | Tree };
function has(tree: Tree, key: string): boolean {
  let cur: string | Tree | undefined = tree;
  for (const part of key.split('.')) cur = typeof cur === 'object' ? cur[part] : undefined;
  return typeof cur === 'string' && cur.trim() !== '';
}

afterEach(() => vi.unstubAllGlobals());

describe('错误码映射', () => {
  it('每个映射到的翻译键在中英文里都存在', () => {
    const keys = new Set([
      ...Object.values(ERROR_KEYS),
      'errors.reasonRequired',
      'errors.reasonTooLong',
      'errors.passwordWeak',
      'errors.network',
      'errors.serverError',
      'errors.unknown',
      'errors.fieldProblem',
    ]);
    const missing = [...keys].filter((k) => !has(zh as Tree, k) || !has(en as Tree, k));
    expect(missing).toEqual([]);
  });

  it('后端代码里的错误码都有映射（后台和后台调用的模块）', () => {
    // 来自 backend/src/main/java/com/forecast/app/admin/** 以及 WithdrawService、ReconService、KycService、NotifyService 的后台路径
    const backend = [
      'ACCOUNT_DISABLED', 'BUILT_IN_ROLE', 'CANNOT_ASSIGN_SUPER', 'CANNOT_DISABLE_SELF', 'CANNOT_EDIT_OWN_ROLES', 'CURRENT_PASSWORD_WRONG',
      'FORBIDDEN', 'INVALID_CREDENTIALS', 'IP_NOT_ALLOWED', 'LAST_SUPER_ADMIN', 'LOGIN_LOCKED', 'NOT_FOUND',
      'PASSWORD_CHANGE_REQUIRED', 'PERMISSION_EXCEEDS_OWN', 'ROLE_CODE_TAKEN', 'ROLE_IN_USE',
      'SAME_PASSWORD', 'TASK_NOT_FAILED', 'UNAUTHORIZED', 'USERNAME_TAKEN', 'WHITELIST_EXCLUDES_SELF', 'WITHDRAW_NOT_REVIEWABLE',
      'WITHDRAW_NOT_CANCELLABLE', 'ISSUE_NOT_OPEN', 'KYC_NOT_FOUND', 'KYC_NOT_PENDING', 'REJECT_REASON_REQUIRED', 'REJECT_REASON_TOO_LONG',
      'BUILT_IN_CANNOT_DISABLE', 'VALIDATION_FAILED', 'BAD_REQUEST', 'METHOD_NOT_ALLOWED', 'INTERNAL_ERROR',
      // 通用限流（P-05、SEC-15）
      'RATE_LIMITED',
      // v2 提现自动审批规则
      'CONFIG_CHANGED', 'NO_CHANGE',
    ];
    expect(backend.filter((c) => !ERROR_KEYS[c])).toEqual([]);
  });

  it('后端的登录、权限错误码有明确提示', () => {
    expect(toApiError(403, { code: 'IP_NOT_ALLOWED' }).reasonKey).toBe('errors.ipNotAllowed');
    expect(toApiError(403, { code: 'FORBIDDEN' }).reasonKey).toBe('errors.forbidden');
    expect(toApiError(403, { code: 'PASSWORD_CHANGE_REQUIRED' }).reasonKey).toBe('errors.passwordChangeRequired');
    expect(toApiError(401, { code: 'UNAUTHORIZED' }).reasonKey).toBe('errors.unauthorized');
    expect(toApiError(429, { code: 'LOGIN_LOCKED' }).reasonKey).toBe('errors.loginLocked');
    expect(toApiError(429, { code: 'RATE_LIMITED' }).reasonKey).toBe('errors.rateLimited');
    // 没有响应体的 429（例如 Nginx 外层限流的响应没有到达页面）也显示"操作太频繁"
    expect(toApiError(429, null).reasonKey).toBe('errors.rateLimited');
    expect(toApiError(400, { code: 'INVALID_CREDENTIALS' }).reasonKey).toBe('errors.invalidCredentials');
  });

  it('没有响应体时按 HTTP 状态判断；不认识的错误码显示通用原因', () => {
    expect(toApiError(401, null).code).toBe('UNAUTHORIZED');
    expect(toApiError(502, null).reasonKey).toBe('errors.serverError');
    expect(toApiError(400, { code: 'SOMETHING_NEW' }).reasonKey).toBe('errors.unknown');
  });

  it('只有"原因"字段出错时用原有提示；其他字段错误逐个说明', async () => {
    expect(toApiError(400, { code: 'VALIDATION_FAILED', fields: [{ field: 'reason', code: 'required' }] }).reasonKey).toBe('errors.reasonRequired');
    expect(toApiError(400, { code: 'VALIDATION_FAILED', fields: [{ field: 'note', code: 'tooLong' }] }).reasonKey).toBe('errors.reasonTooLong');
    expect(toApiError(400, { code: 'VALIDATION_FAILED', fields: [{ field: 'newPassword', code: 'weak' }] }).reasonKey).toBe('errors.passwordWeak');
    const err = toApiError(400, { code: 'VALIDATION_FAILED', fields: [{ field: 'email', code: 'invalid' }, { field: 'displayName', code: 'required' }] });
    expect(err.reasonKey).toBe('errors.validationFailed');
    await i18n.changeLanguage('zh-CN');
    expect(errorText(i18n.t, err)).toBe('提交的内容有误：邮箱格式不正确；姓名不能为空');
  });
});

describe('请求', () => {
  it('同源请求、带 Cookie，查询参数去掉空值', async () => {
    const { calls } = fakeFetch({ 'GET /api/admin/customers': { body: { items: [], total: 0, page: 1, pageSize: 10 } } });
    await request('/customers', { query: { keyword: '', status: undefined, page: 1, pageSize: 10 } });
    expect(calls[0].url).toBe('/api/admin/customers?page=1&pageSize=10');
    expect(calls[0].init.credentials).toBe('same-origin');
    expect(calls[0].method).toBe('GET');
    expect(withQuery('/x', { a: 'b c', n: null })).toBe('/x?a=b+c');
  });

  it('POST 发 JSON；空响应得到 undefined', async () => {
    const { calls } = fakeFetch({ 'POST /api/admin/tasks/1/retry': { status: 200 } });
    await expect(request('/tasks/1/retry', { method: 'POST', body: { a: 1 } })).resolves.toBeUndefined();
    expect(calls[0].body).toEqual({ a: 1 });
    expect((calls[0].init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });

  it('401 通知"登录已失效"，403 必须改密码通知跳改密码页；登录接口自己处理 401', async () => {
    const events: string[] = [];
    const off = onAuthEvent((e) => events.push(e));
    fakeFetch({ 'GET /api/admin/customers': { status: 401, body: { code: 'UNAUTHORIZED', message: '未登录' } } });
    await expect(request('/customers')).rejects.toMatchObject({ reasonKey: 'errors.unauthorized', status: 401 });
    fakeFetch({ 'GET /api/admin/customers': { status: 403, body: { code: 'PASSWORD_CHANGE_REQUIRED' } } });
    await expect(request('/customers')).rejects.toBeInstanceOf(ApiError);
    fakeFetch({ 'POST /api/admin/auth/login': { status: 401, body: { code: 'UNAUTHORIZED' } } });
    await expect(request('/auth/login', { method: 'POST', silentAuth: true })).rejects.toBeInstanceOf(ApiError);
    fakeFetch({ 'GET /api/admin/customers': { status: 403, body: { code: 'IP_NOT_ALLOWED' } } });
    await expect(request('/customers')).rejects.toMatchObject({ reasonKey: 'errors.ipNotAllowed' });
    off();
    expect(events).toEqual(['unauthorized', 'passwordChangeRequired']);
  });

  it('连不上服务器：网络错误', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))));
    await expect(request('/me')).rejects.toMatchObject({ reasonKey: 'errors.network' });
  });
});
