/**
 * 后台真实接口的请求层：同源 fetch('/api/admin/...', {credentials: 'same-origin'})。
 * - 登录态在 HttpOnly Cookie（路径 /api/admin）里，前端不保存令牌。
 * - 后端错误统一是 {code, message, fields: [{field, code}]}，这里把 code 映射成翻译键（errors.*），界面只显示具体原因。
 * - 401：通知登录状态"已失效"（回登录页）；403 PASSWORD_CHANGE_REQUIRED：通知"必须先改密码"（跳改密码页）；
 *   403 IP_NOT_ALLOWED、FORBIDDEN 给出明确提示。
 *
 * HTTP layer for the real admin API: same-origin fetch, cookie session, backend error codes mapped to i18n keys.
 */
import { ApiError, type FieldError } from './types';

export const API_BASE = '/api/admin';

/** 后端错误码 → 翻译键。后端新增错误码时在这里补，并在 zh-CN.json、en.json 补翻译（http.test.ts 检查） */
export const ERROR_KEYS: Record<string, string> = {
  // 登录、权限（AdminInterceptor、AdminAuthService）
  UNAUTHORIZED: 'errors.unauthorized',
  FORBIDDEN: 'errors.forbidden',
  IP_NOT_ALLOWED: 'errors.ipNotAllowed',
  PASSWORD_CHANGE_REQUIRED: 'errors.passwordChangeRequired',
  ACCOUNT_DISABLED: 'errors.accountDisabled',
  INVALID_CREDENTIALS: 'errors.invalidCredentials',
  LOGIN_LOCKED: 'errors.loginLocked',
  // 通用限流（P-05）：后端按 IP、后台登录按 IP 超限，或 Nginx 外层限流，429 带 Retry-After
  RATE_LIMITED: 'errors.rateLimited',
  CURRENT_PASSWORD_WRONG: 'errors.currentPasswordWrong',
  SAME_PASSWORD: 'errors.samePassword',
  // 管理员、角色、配置（admin/system）
  USERNAME_TAKEN: 'errors.usernameTaken',
  CANNOT_ASSIGN_SUPER: 'errors.cannotAssignSuper',
  CANNOT_EDIT_OWN_ROLES: 'errors.cannotEditOwnRoles',
  CANNOT_DISABLE_SELF: 'errors.cannotDisableSelf',
  LAST_SUPER_ADMIN: 'errors.lastSuperAdmin',
  PERMISSION_EXCEEDS_OWN: 'errors.permissionExceedsOwn',
  ROLE_CODE_TAKEN: 'errors.roleCodeTaken',
  BUILT_IN_ROLE: 'errors.builtInRole',
  ROLE_IN_USE: 'errors.roleInUse',
  WHITELIST_EXCLUDES_SELF: 'errors.whitelistExcludesSelf',
  // 客户、资金、KYC、通知（admin/ops 及各模块的 api）
  TASK_NOT_FAILED: 'errors.taskNotFailed',
  ISSUE_NOT_OPEN: 'errors.issueNotOpen',
  WITHDRAW_NOT_REVIEWABLE: 'errors.withdrawNotReviewable',
  WITHDRAW_NOT_CANCELLABLE: 'errors.withdrawNotCancellable',
  // v2 提现自动审批规则（admin/ops/AutoApproveOps）：版本冲突 409、没有变化
  CONFIG_CHANGED: 'errors.configChanged',
  NO_CHANGE: 'errors.autoApproveNoChange',
  // v2 资金归集（admin/ops/SweepOps、wallet/sweep/SweepService）
  SWEEP_UNAVAILABLE: 'errors.sweepUnavailable',
  SWEEP_NOTHING: 'errors.sweepNothing',
  SWEEP_BATCH_ACTIVE: 'errors.sweepBatchActive',
  SWEEP_BATCH_STATE: 'errors.sweepBatchState',
  KYC_NOT_FOUND: 'errors.notFound',
  KYC_NOT_PENDING: 'errors.kycNotPending',
  REJECT_REASON_REQUIRED: 'errors.reasonRequired',
  REJECT_REASON_TOO_LONG: 'errors.reasonTooLong',
  BUILT_IN_CANNOT_DISABLE: 'errors.builtInCannotDisable',
  // forecast S16：等候页上线通知只能发一次；Telegram 模板预览以后文字改过、没有可预览的市场
  LAUNCH_ALREADY_SENT: 'errors.fc.LAUNCH_ALREADY_SENT',
  TELEGRAM_TEMPLATE_CHANGED: 'errors.fc.TELEGRAM_TEMPLATE_CHANGED',
  TELEGRAM_NO_PREVIEW: 'errors.fc.TELEGRAM_NO_PREVIEW',
  // 通用
  NOT_FOUND: 'errors.notFound',
  VALIDATION_FAILED: 'errors.validationFailed',
  BAD_REQUEST: 'errors.badRequest',
  METHOD_NOT_ALLOWED: 'errors.badRequest',
  FILE_TOO_LARGE: 'errors.badRequest',
  INTERNAL_ERROR: 'errors.serverError',
};

/** 登录状态事件：AuthContext 订阅后处理跳转 */
export type AuthEvent = 'unauthorized' | 'passwordChangeRequired';
const listeners = new Set<(e: AuthEvent) => void>();

export function onAuthEvent(listener: (e: AuthEvent) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function emit(e: AuthEvent): void {
  listeners.forEach((l) => l(e));
}

interface ErrorBody {
  code?: string;
  message?: string;
  fields?: FieldError[] | null;
}

/** 把后端的错误响应转成 ApiError（翻译键 + 字段错误） */
export function toApiError(status: number, body: ErrorBody | null): ApiError {
  const code = body?.code ?? (status === 401 ? 'UNAUTHORIZED' : status === 403 ? 'FORBIDDEN' : status === 429 ? 'RATE_LIMITED' : status >= 500 ? 'INTERNAL_ERROR' : undefined);
  const fields = (body?.fields ?? []).filter((f): f is FieldError => !!f && typeof f.field === 'string');
  // 只有"原因"一个字段出错时，直接用已有的提示
  if (code === 'VALIDATION_FAILED' && fields.length === 1 && (fields[0].field === 'reason' || fields[0].field === 'note')) {
    if (fields[0].code === 'required') return new ApiError('errors.reasonRequired', undefined, { status, code, fields });
    if (fields[0].code === 'tooLong') return new ApiError('errors.reasonTooLong', undefined, { status, code, fields });
  }
  if (code === 'VALIDATION_FAILED' && fields.length === 1 && fields[0].code === 'weak') {
    return new ApiError('errors.passwordWeak', undefined, { status, code, fields });
  }
  const key = (code && ERROR_KEYS[code]) || (status >= 500 ? 'errors.serverError' : 'errors.unknown');
  return new ApiError(key, undefined, { status, code, fields: fields.length ? fields : undefined });
}

type Query = Record<string, string | number | boolean | null | undefined>;

/** 拼查询参数：空值不传 */
export function withQuery(path: string, query?: Query): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue;
    params.append(k, String(v));
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  query?: Query;
  body?: unknown;
  /** 登录接口自己处理 401，不触发"登录已失效" */
  silentAuth?: boolean;
}

/** 发请求并解析 JSON；非 2xx 抛 ApiError。返回 204 或空响应时得到 undefined */
export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET';
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(withQuery(API_BASE + path, opts.query), {
      method,
      credentials: 'same-origin',
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    });
  } catch {
    throw new ApiError('errors.network');
  }
  const text = await res.text();
  let data: unknown = undefined;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = undefined;
    }
  }
  if (!res.ok) {
    const err = toApiError(res.status, (data as ErrorBody | undefined) ?? null);
    if (!opts.silentAuth) {
      if (err.code === 'UNAUTHORIZED') emit('unauthorized');
      else if (err.code === 'PASSWORD_CHANGE_REQUIRED') emit('passwordChangeRequired');
    }
    throw err;
  }
  return data as T;
}
